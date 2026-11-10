package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.domain.verdict.Verdict;
import org.pragmatica.temper.store.api.EventPayload;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// A `jobs` row locked `FOR NO KEY UPDATE`. Every transaction that locks a job and its units locks the job first and
/// the units in id order; transactions that touch only units (grant, lease refresh, requeue) never wait for a job
/// lock and lock units in id order too (grant skips locked rows), so no lock cycle forms. NO KEY keeps the events
/// FK's KEY SHARE free.
record JobLock(JobId id,
               JobState state,
               boolean keep,
               boolean superseded,
               Option<Instant> cancelRequestedAt,
               Option<String> sha,
               Option<String> ref,
               String recipeKind) {
    static final String COLUMNS = "id, state, keep, superseded, cancel_requested_at, source_sha, ref, recipe_kind";

    static Promise<Option<JobLock>> lock(QueryExecutor tx, JobId id) {
        return Sql.query(tx,
                         "SELECT " + COLUMNS + " FROM jobs WHERE id = $1 FOR NO KEY UPDATE",
                         id.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, JobLock::jobLock));
    }

    static Result<JobLock> jobLock(PgRow row) {
        return Result.all(Rows.jobId(row, "id"),
                          Rows.enumValue(JobState.class,
                                         row.getString("state")))
                     .map((id, state) -> new JobLock(id,
                                                     state,
                                                     row.getBoolean("keep"),
                                                     row.getBoolean("superseded"),
                                                     option(row.getInstant("cancel_requested_at")),
                                                     Rows.text(row, "source_sha"),
                                                     Rows.text(row, "ref"),
                                                     row.getString("recipe_kind")))
                     .mapError(Rows.corrupt("jobs"));
    }

    /// `queued`: the job facts every event of this job carries.
    EventPayload payload() {
        return EventPayload.eventPayload(sha, ref, some(recipeKind), none(), none(), none(), none(), none());
    }

    /// `started` (no verdict yet) and `unit-finished` (the unit's verdict).
    EventPayload unitPayload(UnitName unit, Option<Verdict> verdict) {
        return EventPayload.eventPayload(sha,
                                         ref,
                                         some(recipeKind),
                                         some(unit.value()),
                                         verdict.map(Verdict::kind),
                                         verdict.flatMap(Verdict::detail),
                                         none(),
                                         none());
    }

    /// `finished` / `cancelled`; `known` is the record's `KNOWN(#n)` annotations (m2-design §4.3).
    EventPayload verdictPayload(Verdict verdict, Counts counts, List<String> known) {
        return EventPayload.eventPayload(sha,
                                         ref,
                                         some(recipeKind),
                                         none(),
                                         some(verdict.kind()),
                                         verdict.detail(),
                                         some(counts),
                                         none(),
                                         known);
    }

    /// `superseded`.
    EventPayload supersededPayload(JobId by) {
        return EventPayload.eventPayload(sha, ref, some(recipeKind), none(), none(), none(), none(), some(by.value()));
    }
}
