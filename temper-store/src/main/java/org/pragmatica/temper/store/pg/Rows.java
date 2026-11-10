package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.DateTime;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.protocol.api.EventKind;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.LostAttempt;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.RerunRow;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.UnitRow;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Row parsing: every stored value goes back through its domain factory (parse, don't validate), and a value that
/// no longer parses is a `StoreError.CorruptRow` of its table.
sealed interface Rows {
    /// `reruns` is the job's rerun jobs (`jobs.rerun_of`, served by the partial index `jobs_rerun_of`) as one json array,
    /// oldest first, each with its state and its result's annotations; a refused rerun request is left out. It is read in
    /// the row's own statement, so a view and a gate answer see the reruns of the same snapshot as the row. A rerun row
    /// that no longer parses makes its parent's row corrupt, exactly as one of its units would: only manual SQL can write
    /// one (`state` is a `JobState` name, `annotations` the result's), and a parent shown without it would hide it. The
    /// correlated `json_agg` runs for every row read (a list page, a Gate candidate): an index probe of `jobs_rerun_of`
    /// per row, at most 1000 rows a page, which is cheap at temper's scale.
    String JOB_COLUMNS = "j.id, j.submitted_by, j.submitted_at, j.priority, j.keep, j.fresh, j.unpushed, j.ref, j.source_sha, "
                       + "j.recipe::text AS recipe, j.dedup_key, j.state, j.refusal_code, j.refusal_detail, j.superseded, "
                       + "j.superseded_by, j.cancel_requested_at, j.finished_at, j.dedicated_cpu, r.verdict, r.reason, "
                       + "(SELECT coalesce(json_agg(json_build_object('job', c.id, 'state', c.state, 'annotations', "
                       + "coalesce(to_json(cr.annotations), '[]'::json)) ORDER BY c.id), '[]'::json) FROM jobs c "
                       + "LEFT JOIN results cr ON cr.job_id = c.id WHERE c.rerun_of = j.id AND c.state <> 'REFUSED')::text AS reruns";

    String JOB_FROM = " FROM jobs j LEFT JOIN results r ON r.job_id = j.id";

    /// `lost_attempts` is the unit's `unit_attempts` rows (m4-design §2.3) as one json array, oldest first, read in the
    /// row's own statement so a view never shows a requeued unit without the attempt it lost; the instants are written
    /// as ISO-8601 UTC text ([#utc]) so the json carries no session time zone. An index probe of the primary key per unit.
    String UNIT_COLUMNS = "u.id, u.job_id, u.name, u.kind, u.state, u.reason, u.host, u.args::text AS args, u.requests_tests, "
                        + "u.needs_port_group, u.memory_mb, u.timeout_s, u.lease_until, u.granted_at, u.started_at, "
                        + "u.ended_at, u.rc, u.killed_by, u.marker_epoch_ns, u.image_id, u.container_id, u.tests_attr, u.rerun, "
                        + "u.port_group, u.bbhold_ticket, u.hold_since, u.attempt, u.shared_cpu, u.side_effects, "
                        + "(SELECT f.facts -> 'tcLeftovers' FROM unit_facts f WHERE f.unit_id = u.id AND f.attempt = u.attempt "
                        + "AND f.phase = 'end')::text "
                        + "AS tc_leftovers, "
                        + "(SELECT coalesce(json_agg(json_build_object('attempt', a.attempt, 'host', a.host, 'grantedAt', " + utc("a.granted_at")
                        + ", 'startedAt', " + utc("a.started_at")
                        + ", 'endedAt', " + utc("a.ended_at")
                        + ") ORDER BY a.attempt), '[]'::json) FROM unit_attempts a WHERE a.unit_id = u.id)::text AS lost_attempts";

    String TESTCASE_SELECT = "SELECT t.class, t.name, t.outcome, t.time_s, t.rerun, u.name AS unit "
                           + "FROM testcases t JOIN units u ON u.id = t.unit_id ";

    /// A timestamptz as ISO-8601 UTC text with microseconds (`2026-10-27T10:00:00.123456Z`); NULL stays NULL.
    private static String utc(String column) {
        return "to_char(" + column + " AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')";
    }

    static Fn1<Cause, Cause> corrupt(String table) {
        return cause -> StoreError.CorruptRow.FACTORY.apply(table, cause);
    }

    static Option<String> text(PgRow row, String column) {
        return option(row.getString(column));
    }

    static <E extends Enum<E>> Result<E> enumValue(Class<E> type, String raw) {
        return Option.from(Arrays.stream(type.getEnumConstants())
                                 .filter(constant -> constant.name()
                                                             .equals(raw))
                                 .findFirst()).toResult(invalid(type.getSimpleName()).apply(raw));
    }

    /// `units.killed_by` form: `timeout` / `cancel`.
    static String killedBy(KillCause cause) {
        return cause.name()
                    .toLowerCase(Locale.ROOT);
    }

    static Result<KillCause> killCause(String raw) {
        return enumValue(KillCause.class, raw.toUpperCase(Locale.ROOT));
    }

    static Result<EventRow.Kind> eventKind(String raw) {
        return Option.from(Arrays.stream(EventKind.values()).filter(kind -> kind.wire()
                                                                                .equals(raw)).findFirst())
                     .map(WireRecords::kind)
                     .toResult(invalid("event kind").apply(raw));
    }

    static Result<JobId> jobId(PgRow row, String column) {
        return JobId.jobId(row.getLong(column));
    }

    /// The `name` column of a `units` row.
    static Result<UnitName> unitName(PgRow row) {
        return UnitName.unitName(row.getString("name")).mapError(corrupt("units"));
    }

    static Result<JobRow> jobRow(PgRow row, List<UnitRow> units) {
        return Result.all(jobId(row, "id"),
                          OwnerTag.ownerTag(row.getString("submitted_by")),
                          Values.optional(text(row, "ref"),
                                          Ref::ref),
                          Values.optional(text(row, "source_sha"),
                                          Sha::sha),
                          recipe(row),
                          Values.optional(text(row, "dedup_key"),
                                          DedupKey::dedupKey),
                          enumValue(JobState.class,
                                    row.getString("state")),
                          Values.optional(option(row.getLong("superseded_by")),
                                          JobId::jobId),
                          reruns(row))
                     .map((id, owner, ref, sha, recipe, key, state, supersededBy, reruns) -> JobRow.jobRow(id,
                                                                                                           owner,
                                                                                                           row.getInstant("submitted_at"),
                                                                                                           row.getInt("priority"),
                                                                                                           row.getBoolean("keep"),
                                                                                                           row.getBoolean("fresh"),
                                                                                                           row.getBoolean("unpushed"),
                                                                                                           ref,
                                                                                                           sha,
                                                                                                           recipe,
                                                                                                           key,
                                                                                                           state,
                                                                                                           text(row,
                                                                                                                "refusal_code"),
                                                                                                           text(row,
                                                                                                                "refusal_detail"),
                                                                                                           row.getBoolean("superseded"),
                                                                                                           supersededBy,
                                                                                                           option(row.getInstant("cancel_requested_at")),
                                                                                                           option(row.getInstant("finished_at")),
                                                                                                           text(row,
                                                                                                                "verdict"),
                                                                                                           text(row,
                                                                                                                "reason"),
                                                                                                           units,
                                                                                                           reruns)
                                                                                                   .dedicated(row.getBoolean("dedicated_cpu")))
                     .mapError(corrupt("jobs"));
    }

    /// The `reruns` json array of [#JOB_COLUMNS].
    private static Result<List<RerunRow>> reruns(PgRow row) {
        return Json.read(row.getString("reruns"),
                         RerunJson[].class)
                   .flatMap(Rows::rerunRows);
    }

    private static Result<List<RerunRow>> rerunRows(RerunJson[] reruns) {
        return Result.allOf(Arrays.stream(reruns).map(RerunJson::rerunRow).toList());
    }

    /// One element of the `reruns` array as Postgres builds it; Jackson constructs it, nothing else does.
    record RerunJson(long job, String state, List<String> annotations) {
        Result<RerunRow> rerunRow() {
            return Result.all(JobId.jobId(job),
                              enumValue(JobState.class, state))
                         .map((id, parsed) -> RerunRow.rerunRow(id, parsed, annotations));
        }
    }

    static Result<UnitRow> unitRow(PgRow row) {
        return Result.all(UnitName.unitName(row.getString("name")),
                          enumValue(UnitKind.class,
                                    row.getString("kind")),
                          enumValue(UnitState.class,
                                    row.getString("state")),
                          Values.optional(text(row, "host"),
                                          HostName::hostName),
                          Json.read(row.getString("args"),
                                    Json.STEPS),
                          Values.optional(text(row, "killed_by"),
                                          Rows::killCause),
                          Values.optional(text(row, "tc_leftovers"),
                                          Rows::tcLeftovers),
                          Values.optional(text(row, "port_group"),
                                          PortGroup::portGroup),
                          sideEffects(row.getString("side_effects")),
                          lostAttempts(row))
                     .map((name, kind, state, host, steps, killed, leftovers, portGroup, sideEffects, lost) -> UnitRow.unitRow(row.getLong("id"),
                                                                                                                               name,
                                                                                                                               kind,
                                                                                                                               state,
                                                                                                                               text(row,
                                                                                                                                    "reason"),
                                                                                                                               host,
                                                                                                                               steps,
                                                                                                                               row.getBoolean("requests_tests"),
                                                                                                                               row.getBoolean("needs_port_group"),
                                                                                                                               row.getInt("memory_mb"),
                                                                                                                               Duration.ofSeconds(row.getInt("timeout_s")),
                                                                                                                               option(row.getInstant("lease_until")),
                                                                                                                               option(row.getInstant("granted_at")),
                                                                                                                               option(row.getInstant("started_at")),
                                                                                                                               option(row.getInstant("ended_at")),
                                                                                                                               option(row.getInt("rc")),
                                                                                                                               killed,
                                                                                                                               option(row.getLong("marker_epoch_ns")),
                                                                                                                               text(row,
                                                                                                                                    "image_id"),
                                                                                                                               text(row,
                                                                                                                                    "container_id"),
                                                                                                                               option(row.getLong("tests_attr")),
                                                                                                                               row.getBoolean("rerun"),
                                                                                                                               leftovers,
                                                                                                                               portGroup,
                                                                                                                               option(row.getInstant("hold_since")),
                                                                                                                               text(row,
                                                                                                                                    "bbhold_ticket"),
                                                                                                                               row.getInt("attempt"),
                                                                                                                               option(row.getBoolean("shared_cpu")),
                                                                                                                               sideEffects,
                                                                                                                               lost))
                     .mapError(corrupt("units"));
    }

    /// `units.side_effects` (m4-design §5): `none` or `external`.
    static Result<SideEffects> sideEffects(String raw) {
        return Option.from(Arrays.stream(SideEffects.values()).filter(value -> value.wire()
                                                                                    .equals(raw)).findFirst()).toResult(invalid("side effects").apply(raw));
    }

    /// The `lost_attempts` json array of [#UNIT_COLUMNS].
    private static Result<List<LostAttempt>> lostAttempts(PgRow row) {
        return Json.read(row.getString("lost_attempts"),
                         LostAttemptJson[].class)
                   .flatMap(attempts -> Result.allOf(Arrays.stream(attempts).map(LostAttemptJson::lostAttempt).toList()));
    }

    /// One element of the `lost_attempts` array as Postgres builds it; Jackson constructs it, nothing else does.
    record LostAttemptJson(int attempt, String host, String grantedAt, String startedAt, String endedAt) {
        Result<LostAttempt> lostAttempt() {
            return Result.all(HostName.hostName(host),
                              instant(grantedAt),
                              Values.optional(option(startedAt),
                                              LostAttemptJson::instant),
                              instant(endedAt))
                         .map((name, granted, started, ended) -> LostAttempt.lostAttempt(attempt,
                                                                                         name,
                                                                                         granted,
                                                                                         started,
                                                                                         ended));
        }

        private static Result<Instant> instant(String text) {
            return DateTime.parseInstant(text);
        }
    }

    /// The `tcLeftovers` object of a unit's end facts.
    private static Result<TcLeftovers> tcLeftovers(String json) {
        return Json.read(json, TcLeftoversDto.class).flatMap(TcLeftoversDto::toDomain);
    }

    /// A unit row keyed by its job id, for grouping a multi-job unit query.
    static Result<Map.Entry<Long, UnitRow>> ownedUnit(PgRow row) {
        return unitRow(row).map(unit -> Map.entry(row.getLong("job_id"), unit));
    }

    static Result<TestcaseRow> testcaseRow(PgRow row) {
        return Result.all(UnitName.unitName(row.getString("unit")),
                          Outcome.outcome(row.getString("outcome")))
                     .map((unit, outcome) -> TestcaseRow.testcaseRow(unit,
                                                                     row.getString("class"),
                                                                     row.getString("name"),
                                                                     outcome,
                                                                     row.getDouble("time_s"),
                                                                     row.getBoolean("rerun")))
                     .mapError(corrupt("testcases"));
    }

    static Result<EventRow> eventRow(PgRow row) {
        return Result.all(Values.optional(option(row.getLong("job_id")),
                                          JobId::jobId),
                          eventKind(row.getString("kind")),
                          Json.read(row.getString("payload"),
                                    EventPayload.class))
                     .map((job, kind, payload) -> EventRow.eventRow(row.getLong("seq"),
                                                                    row.getInstant("at"),
                                                                    job,
                                                                    kind,
                                                                    payload))
                     .mapError(corrupt("events"));
    }

    private static Result<Recipe> recipe(PgRow row) {
        return Json.read(row.getString("recipe"),
                         RecipeDto.class)
                   .flatMap(RecipeDto::toDomain);
    }

    record unused() implements Rows {}
}
