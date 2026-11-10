package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.dedup.DedupKey;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.UnitSpec;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.store.api.CancelOutcome;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.JobQuery;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.JobStore;
import org.pragmatica.temper.store.api.NewJob;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SubmitOutcome;
import org.pragmatica.temper.store.api.TaskLink;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// [JobStore] over Postgres. `judge` is the build this controller runs (m2-design §1.7): every job it inserts names it
/// (`jobs.judge`), and so does every result its cancels and supersedes write (`Finishing`).
record PgJobStore(Db db, Judge judge) implements JobStore {
    /// The dedup state set, derived from the one domain rule (`DedupKey.reusable`): QUEUED, UNKNOWN, RUNNING, PASSED,
    /// FAILED, EMPTY.
    static final String REUSABLE_STATES = Arrays.stream(JobState.values())
                                                .filter(DedupKey::reusable)
                                                .map(state -> "'" + state.name() + "'")
                                                .collect(Collectors.joining(",",
                                                                            "(",
                                                                            ")"));

    /// `rerun_of` ($16) is the parent of a rerun job, from its recipe ([Submission#rerunOf]); NULL for every other job.
    /// `task_id` and `task_epoch` ($17, $18) link a job submitted under a lease (m3-design §6.4); NULL otherwise.
    /// `dedicated_cpu` ($19, m4-design §9.2) is the job's CPU class, read by the grant's candidate filter.
    private static final String INSERT_JOB = "INSERT INTO jobs (submitted_by, priority, keep, fresh, source, source_sha, ref, recipe, "
                                           + "recipe_kind, recipe_canonical, dedup_key, state, refusal_code, refusal_detail, judge, finished_at, rerun_of, "
                                           + "task_id, task_epoch, dedicated_cpu) "
                                           + "VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, $8::jsonb, $9, $10, $11, $12, $13, $14, $15, "
                                           + "CASE WHEN $12::text = 'REFUSED' THEN now() END, $16, $17, $18, $19) RETURNING id";

    /// `manifest_spec` travels as text with `''` for a unit without one (an array element never binds SQL NULL).
    /// `side_effects` has no default (V11): every unit is written with the planner's value (`UnitSpec.sideEffects`,
    /// m4-design §5.1), which a host-loss settle reads from the row.
    private static final String INSERT_UNITS = "INSERT INTO units (job_id, name, kind, state, args, requests_tests, needs_port_group, "
                                             + "memory_mb, timeout_s, manifest_spec, side_effects) "
                                             + "SELECT $1, u.name, u.kind, 'QUEUED', u.args::jsonb, u.requests_tests::boolean, "
                                             + "u.needs_port_group::boolean, u.memory_mb::int, u.timeout_s::int, NULLIF(u.manifest_spec, '')::jsonb, "
                                             + "u.side_effects "
                                             + "FROM unnest($2::text[], $3::text[], $4::text[], $5::text[], $6::text[], $7::text[], $8::text[], "
                                             + "$9::text[], $10::text[]) "
                                             + "AS u(name, kind, args, requests_tests, needs_port_group, memory_mb, timeout_s, manifest_spec, side_effects)";

    /// Victims of a by-ref submit (spec §2.4): earlier QUEUED, UNKNOWN or RUNNING jobs of the same `(ref, recipe)` at
    /// another SHA, not yet superseded; the recipe compares as `recipe_canonical`, so module-test `pl` and test
    /// selections count. A job already superseded keeps its first `superseded_by` and gets no second event; a
    /// CANCELLING job is already on its way to CANCELLED, so a user cancel never turns into a supersede [G].
    private static final String VICTIMS = "SELECT " + JobLock.COLUMNS
                                        + " FROM jobs WHERE ref = $1 AND recipe_canonical = $2 "
                                        + "AND source_sha <> $3 AND id < $4 AND state IN ('QUEUED','UNKNOWN','RUNNING') AND NOT superseded "
                                        + "ORDER BY id FOR NO KEY UPDATE";

    /// Advisory-lock namespaces (two-int form, so they never collide with the single-bigint keys 7070 of the migration
    /// runner and 70700001 of the event order): one key per dedup key, one per `(ref, recipe)`.
    private static final int DEDUP_LOCK_SPACE = 7071;
    private static final int SUPERSEDE_LOCK_SPACE = 7072;

    @Override
    public Promise<SubmitOutcome> submit(NewJob job) {
        var now = linkTime(job);

        return Submission.submission(job)
                         .async()
                         .flatMap(submission -> db.inTransaction(tx -> fencedSubmit(tx, submission, judge, now)));
    }

    /// A refused job under a lease is linked and fenced as an accepted one ([#fenced]); any other is one INSERT.
    @Override
    public Promise<JobId> recordRefused(NewJob job, Refusal refusal) {
        var now = linkTime(job);

        return job.task()
                  .map(_ -> db.inTransaction(tx -> fencedRefused(tx, job, refusal, judge, now)))
                  .or(() -> insertJob(db.executor(),
                                      job,
                                      JobState.REFUSED,
                                      some(refusal),
                                      judge));
    }

    // ---- a submit under a lease (m3-design §6.4) ---------------------------------------------------------------------
    /// The lease is checked first, under the task's row lock and before the dedup lock, so a stale epoch is
    /// `TaskError.LEASE_LOST` before anything is written and the whole transaction rolls back with it. The task's TTL
    /// restarts (an accepted lease-token call, §6.2) on the store's wall clock, and its `job-linked` event names the job
    /// once its id is known.
    record Linked<T>(Option<TaskCommands.Applied> link, T outcome) {}

    private static Promise<SubmitOutcome> fencedSubmit(Transaction tx,
                                                       Submission submission,
                                                       Judge judge,
                                                       Instant now) {
        return fenced(tx,
                      submission.job(),
                      now).mapWith(_ -> submitIn(tx, submission, judge),
                                   Linked<SubmitOutcome>::new)
                     .flatMap(linked -> recordedLink(tx,
                                                     linked,
                                                     linkFacts(linked.outcome()),
                                                     now));
    }

    private static Promise<JobId> fencedRefused(Transaction tx, NewJob job, Refusal refusal, Judge judge, Instant now) {
        return fenced(tx, job, now).mapWith(_ -> insertJob(tx,
                                                           job,
                                                           JobState.REFUSED,
                                                           some(refusal),
                                                           judge),
                                            Linked<JobId>::new)
                     .flatMap(linked -> recordedLink(tx,
                                                     linked,
                                                     refusedFacts(linked.outcome(),
                                                                  refusal),
                                                     now));
    }

    /// The time a linked submit writes into its task (the TTL restart, the event): the submit's own, from the caller's
    /// clock (verifier V3: never the store's wall clock). An unlinked submit writes no task, and its time is never read.
    private static Instant linkTime(NewJob job) {
        return job.task()
                  .map(TaskLink::at)
                  .map(TaskCommands::stored)
                  .or(Instant.EPOCH);
    }

    private static Promise<Option<TaskCommands.Applied>> fenced(Transaction tx, NewJob job, Instant now) {
        return job.task()
                  .map(link -> leaseChecked(tx,
                                            link.lease(),
                                            now))
                  .or(() -> Promise.success(none()));
    }

    private static Promise<Option<TaskCommands.Applied>> leaseChecked(Transaction tx, Lease lease, Instant now) {
        return TaskCommands.linked(tx, lease, now).map(Option::some);
    }

    private static <T> Promise<T> recordedLink(Transaction tx,
                                               Linked<T> linked,
                                               Map<String, String> facts,
                                               Instant now) {
        return linked.link()
                     .map(applied -> linkEvents(tx,
                                                applied,
                                                facts,
                                                linked.outcome(),
                                                now))
                     .or(() -> Promise.success(linked.outcome()));
    }

    private static <T> Promise<T> linkEvents(Transaction tx,
                                             TaskCommands.Applied applied,
                                             Map<String, String> facts,
                                             T outcome,
                                             Instant now) {
        return TaskCommands.recorded(tx,
                                     applied.withFacts(facts),
                                     TaskCommands.holder(applied.before()),
                                     now)
                           .map(_ -> outcome);
    }

    private static Map<String, String> refusedFacts(JobId job, Refusal refusal) {
        return Map.of("job", job.toString(), "refused", refusal.code());
    }

    private static Map<String, String> linkFacts(SubmitOutcome outcome) {
        return switch (outcome) {
            case SubmitOutcome.Accepted accepted -> Map.of("job",
                                                           accepted.job().toString());
            case SubmitOutcome.Deduplicated deduplicated -> Map.of("job",
                                                                   deduplicated.job().toString(),
                                                                   "deduplicated",
                                                                   "true");
        };
    }

    @Override
    public Promise<Option<JobRow>> job(JobId id) {
        return db.inTransaction(tx -> loadIn(tx, id));
    }

    @Override
    public Promise<List<JobRow>> list(JobQuery q) {
        return db.inTransaction(tx -> listIn(tx, q));
    }

    /// One read-only snapshot, so a job and its units are read at the same instant.
    private static Promise<List<JobRow>> listIn(Transaction tx, JobQuery q) {
        return Sql.snapshot(tx)
                  .flatMap(_ -> page(tx, q))
                  .flatMap(resultSet -> Jobs.withUnits(tx,
                                                       Sql.rows(resultSet)));
    }

    private static Promise<PgResultSet> page(Transaction tx, JobQuery q) {
        return Sql.query(tx,
                         "SELECT " + Rows.JOB_COLUMNS + Rows.JOB_FROM
                        + " WHERE ($1::text IS NULL OR j.state = $1::text) AND ($2::text IS NULL OR j.submitted_by = $2::text)"
                        + " AND ($3::text IS NULL OR j.source_sha = $3::text) AND ($4::text IS NULL OR j.ref = $4::text)"
                        + " AND ($6::bigint IS NULL OR j.id < $6::bigint)"
                        + " ORDER BY j.id DESC LIMIT $5",
                         Sql.nullable(q.state().map(JobState::name)),
                         Sql.nullable(q.submittedBy().map(OwnerTag::value)),
                         Sql.nullable(q.sha().map(Sha::value)),
                         Sql.nullable(q.ref().map(Ref::value)),
                         q.limit(),
                         Sql.nullable(q.before().map(JobId::value)));
    }

    /// One read-only snapshot, so the job and its units are read at the same instant.
    private static Promise<Option<JobRow>> loadIn(Transaction tx, JobId id) {
        return Sql.snapshot(tx).flatMap(_ -> Jobs.load(tx, id));
    }

    @Override
    public Promise<List<GateRow>> gateCandidates(ShaPrefix sha) {
        return PgDigestStore.inSnapshot(db, tx -> GateRows.read(tx, sha));
    }

    @Override
    public Promise<CancelOutcome> requestCancel(JobId id) {
        return db.inTransaction(tx -> cancelIn(tx, id, judge));
    }

    // ---- submit ----------------------------------------------------------------------------------------------
    private static Promise<SubmitOutcome> submitIn(Transaction tx, Submission submission, Judge judge) {
        return Sql.execute(tx,
                           "SELECT pg_advisory_xact_lock($1, hashtext($2))",
                           DEDUP_LOCK_SPACE,
                           submission.key().value())
                  .flatMap(_ -> reusable(tx, submission))
                  .flatMap(found -> found.map(Promise::<SubmitOutcome> success)
                                         .or(() -> accept(tx, submission, judge)));
    }

    private static Promise<Option<SubmitOutcome>> reusable(QueryExecutor tx, Submission submission) {
        return submission.job()
                         .fresh()
               ? Promise.success(none())
               : latestReusable(tx,
                                submission.key(),
                                submission.job().task().map(TaskLink::lease));
    }

    /// A submit under a lease reuses only a job linked to the same task at the same epoch [G]: a job linked to another
    /// lease is not evidence of this one (m3-design §7.3 `job-not-linked`), so the linked submit gets its own. A submit
    /// without a lease reuses any job, linked or not, as before.
    private static Promise<Option<SubmitOutcome>> latestReusable(QueryExecutor tx, DedupKey key, Option<Lease> lease) {
        return Sql.query(tx,
                         "SELECT id, state FROM jobs WHERE dedup_key = $1 AND state IN " + REUSABLE_STATES
                        + " AND ($2::bigint IS NULL OR (task_id = $2::bigint AND task_epoch = $3::bigint)) ORDER BY id DESC LIMIT 1",
                         key.value(),
                         Sql.nullable(lease.map(present -> present.task()
                                                                  .value())),
                         Sql.nullable(lease.map(Lease::epoch)))
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgJobStore::deduplicated));
    }

    private static Result<SubmitOutcome> deduplicated(PgRow row) {
        return Result.all(Rows.jobId(row, "id"),
                          Rows.enumValue(JobState.class,
                                         row.getString("state")))
                     .<SubmitOutcome> map(SubmitOutcome.Deduplicated::new)
                     .mapError(Rows.corrupt("jobs"));
    }

    private static Promise<SubmitOutcome> accept(Transaction tx, Submission submission, Judge judge) {
        return lockRef(tx, submission).flatMap(_ -> insertJob(tx,
                                                              submission.job(),
                                                              JobState.QUEUED,
                                                              none(),
                                                              judge))
                      .flatMap(id -> insertUnits(tx,
                                                 id,
                                                 submission.job().units()))
                      .flatMap(id -> lockJob(tx, id))
                      .flatMap(lock -> effects(tx, lock, submission, judge))
                      .flatMap(effects -> appendAndAccept(tx, effects));
    }

    /// Serialises by-ref submits of one `(ref, recipe)`: without it two pushes at different SHAs would not see
    /// each other's uncommitted job, and neither would supersede the other. Taken after the dedup lock, always.
    private static Promise<Unit> lockRef(QueryExecutor tx, Submission submission) {
        return submission.source()
                         .ref()
                         .map(ref -> Sql.execute(tx,
                                                 "SELECT pg_advisory_xact_lock($1, hashtext($2 || chr(10) || $3))",
                                                 SUPERSEDE_LOCK_SPACE,
                                                 ref.value(),
                                                 supersedeKey(submission)))
                         .or(Promise::unitPromise);
    }

    /// The recipe alone, without `RecipeOptions` (the unit-timeout override), priority, keep or owner: a push that
    /// changes only those still supersedes.
    private static String supersedeKey(Submission submission) {
        return submission.job()
                         .recipe()
                         .canonical();
    }

    private static Promise<JobLock> lockJob(QueryExecutor tx, JobId id) {
        return JobLock.lock(tx, id).flatMap(lock -> lock.async(StoreError.JobNotFound.FACTORY.apply(id)));
    }

    /// The new job's `queued` event first, then the supersede effects.
    private static Promise<Effects> effects(Transaction tx, JobLock lock, Submission submission, Judge judge) {
        return supersede(tx, lock, submission, judge).map(effects -> effects.after(queued(lock)));
    }

    private static Promise<SubmitOutcome> appendAndAccept(Transaction tx, Effects effects) {
        return EventWriter.appendAll(tx,
                                     effects.events())
                          .map(_ -> effects.accepted());
    }

    private static Promise<JobId> insertJob(QueryExecutor executor,
                                            NewJob job,
                                            JobState state,
                                            Option<Refusal> refusal,
                                            Judge judge) {
        return Submission.documents(job)
                         .async()
                         .flatMap(documents -> Sql.query(executor,
                                                         INSERT_JOB,
                                                         job.submittedBy().value(),
                                                         job.priority().value(),
                                                         job.keep(),
                                                         job.fresh(),
                                                         documents.source(),
                                                         Sql.nullable(Submission.sha(job).map(Sha::value)),
                                                         Sql.nullable(Submission.ref(job).map(Ref::value)),
                                                         documents.recipe(),
                                                         job.recipe().kind(),
                                                         job.recipe().canonical(),
                                                         Sql.nullable(job.dedupKey().map(DedupKey::value)),
                                                         state.name(),
                                                         Sql.nullable(refusal.map(Refusal::code)),
                                                         Sql.nullable(refusal.map(Refusal::detail)),
                                                         judge.value(),
                                                         Sql.nullable(Submission.rerunOf(job).map(JobId::value)),
                                                         Sql.nullable(job.task()
                                                                         .map(link -> link.lease()
                                                                                          .task()
                                                                                          .value())),
                                                         Sql.nullable(job.task().map(link -> link.lease()
                                                                                                 .epoch())),
                                                         job.dedicatedCpu()))
                         .flatMap(PgJobStore::returnedId);
    }

    private static Promise<JobId> returnedId(PgResultSet resultSet) {
        return Sql.first(resultSet)
                  .async(StoreError.General.NO_ROW_RETURNED)
                  .flatMap(row -> Rows.jobId(row, "id").async());
    }

    private static Promise<JobId> insertUnits(QueryExecutor tx, JobId id, List<UnitSpec> units) {
        return Result.all(Result.allOf(units.stream().map(unit -> Json.write(unit.steps())).toList()),
                          Result.allOf(units.stream().map(PgJobStore::manifestSpec).toList()))
                     .map(UnitDocuments::new)
                     .async()
                     .flatMap(documents -> executeInsertUnits(tx, id, units, documents))
                     .map(_ -> id);
    }

    private static Promise<Unit> executeInsertUnits(QueryExecutor tx,
                                                    JobId id,
                                                    List<UnitSpec> units,
                                                    UnitDocuments documents) {
        return Sql.execute(tx,
                           INSERT_UNITS,
                           id.value(),
                           Sql.texts(units.stream().map(unit -> unit.name()
                                                                    .value())),
                           Sql.texts(units.stream().map(unit -> unit.kind()
                                                                    .name())),
                           Sql.texts(documents.args().stream()),
                           Sql.texts(units.stream().map(unit -> String.valueOf(unit.requestsTests()))),
                           Sql.texts(units.stream().map(unit -> String.valueOf(unit.needsPortGroup()))),
                           Sql.texts(units.stream().map(unit -> String.valueOf(unit.memory().value()))),
                           Sql.texts(units.stream().map(unit -> String.valueOf(unit.timeout().toSeconds()))),
                           Sql.texts(documents.manifests().stream()),
                           Sql.texts(units.stream().map(unit -> unit.sideEffects()
                                                                    .wire())));
    }

    /// Per unit, in order: its `args` json and its `manifest_spec` json (`''` without a spec).
    private record UnitDocuments(List<String> args, List<String> manifests) {}

    private static Result<String> manifestSpec(UnitSpec unit) {
        return unit.manifest()
                   .map(spec -> Json.write(ManifestSpecDto.manifestSpecDto(spec)))
                   .or(() -> success(""));
    }

    private static PendingEvent queued(JobLock lock) {
        return PendingEvent.pendingEvent(lock.id(), EventRow.Kind.QUEUED, lock.payload());
    }

    // ---- supersede (spec §2.4) -------------------------------------------------------------------------------
    /// What a transaction produced besides rows: events to append last and units the worker must kill.
    record Effects(JobId job, List<PendingEvent> events, List<UnitRef> toKill) {
        static Effects none(JobId job) {
            return new Effects(job, List.of(), List.of());
        }

        Effects plus(Effects other) {
            return new Effects(job,
                               Stream.concat(events.stream(), other.events.stream()).toList(),
                               Stream.concat(toKill.stream(), other.toKill.stream()).toList());
        }

        Effects after(PendingEvent first) {
            return new Effects(job,
                               Stream.concat(Stream.of(first), events.stream()).toList(),
                               toKill);
        }

        SubmitOutcome accepted() {
            return new SubmitOutcome.Accepted(job, toKill);
        }
    }

    /// Only a job submitted by ref supersedes (`Pushed{ref}`); earlier non-terminal jobs of the same
    /// `(ref, recipe)` at another SHA are victims, locked in id order.
    private static Promise<Effects> supersede(Transaction tx, JobLock lock, Submission submission, Judge judge) {
        return submission.source()
                         .ref()
                         .map(ref -> supersedeRef(tx,
                                                  lock,
                                                  ref,
                                                  supersedeKey(submission),
                                                  submission.source().sha(),
                                                  judge))
                         .or(() -> Promise.success(Effects.none(lock.id())));
    }

    private static Promise<Effects> supersedeRef(Transaction tx,
                                                 JobLock lock,
                                                 Ref ref,
                                                 String recipe,
                                                 Sha sha,
                                                 Judge judge) {
        return Sql.query(tx,
                         VICTIMS,
                         ref.value(),
                         recipe,
                         sha.value(),
                         lock.id().value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, JobLock::jobLock))
                  .flatMap(victims -> Sql.sequence(victims,
                                                   victim -> supersedeOne(tx,
                                                                          lock.id(),
                                                                          victim,
                                                                          judge)))
                  .map(effects -> effects.stream()
                                         .reduce(Effects.none(lock.id()),
                                                 Effects::plus));
    }

    /// RUNNING with `keep` (a GRANTED unit counts as running: its container may already be starting): runs on, marked only. Nothing active (QUEUED, `keep` or not): cancelled now. Otherwise:
    /// CANCELLING, its active units killed (spec §2.4 table; `keep` matters only for a running job).
    private static Promise<Effects> supersedeOne(Transaction tx, JobId by, JobLock victim, Judge judge) {
        return UnitLock.lockAll(tx,
                                victim.id())
                       .flatMap(units -> supersedeLocked(tx, by, victim, units, judge));
    }

    private static Promise<Effects> supersedeLocked(Transaction tx,
                                                    JobId by,
                                                    JobLock victim,
                                                    List<UnitLock> units,
                                                    Judge judge) {
        return victim.keep() && hasActive(units)
               ? keepRunning(tx, by, victim)
               : supersedeUnkept(tx, by, victim, units, judge);
    }

    private static Promise<Effects> keepRunning(Transaction tx, JobId by, JobLock victim) {
        return Cancellation.markSuperseded(tx, victim, by).map(_ -> new Effects(by,
                                                                                List.of(supersededEvent(by, victim)),
                                                                                List.of()));
    }

    private static Promise<Effects> supersedeUnkept(Transaction tx,
                                                    JobId by,
                                                    JobLock victim,
                                                    List<UnitLock> units,
                                                    Judge judge) {
        return hasActive(units)
               ? Cancellation.cancelRunning(tx, victim, some(by)).map(toKill -> new Effects(by,
                                                                                            List.of(supersededEvent(by,
                                                                                                                    victim)),
                                                                                            toKill))
               : Cancellation.cancelNow(tx, victim, some(by), judge).map(finished -> new Effects(by,
                                                                                                 List.of(supersededEvent(by,
                                                                                                                         victim),
                                                                                                         finished.event()),
                                                                                                 List.of()));
    }

    private static PendingEvent supersededEvent(JobId by, JobLock victim) {
        return PendingEvent.pendingEvent(victim.id(), EventRow.Kind.SUPERSEDED, victim.supersededPayload(by));
    }

    private static boolean hasActive(List<UnitLock> units) {
        return units.stream()
                    .anyMatch(UnitLock::active);
    }

    // ---- cancel ----------------------------------------------------------------------------------------------
    private static Promise<CancelOutcome> cancelIn(Transaction tx, JobId id, Judge judge) {
        return lockJob(tx, id).flatMap(job -> cancelLocked(tx, job, judge));
    }

    private static Promise<CancelOutcome> cancelLocked(Transaction tx, JobLock job, Judge judge) {
        return job.state()
                  .isTerminal()
               ? Promise.success(new CancelOutcome.AlreadyTerminal(job.id(), job.state()))
               : UnitLock.lockAll(tx,
                                  job.id())
                         .flatMap(units -> cancelActive(tx, job, units, judge));
    }

    private static Promise<CancelOutcome> cancelActive(Transaction tx, JobLock job, List<UnitLock> units, Judge judge) {
        return hasActive(units)
               ? Cancellation.cancelRunning(tx, job, none()).map(toKill -> new CancelOutcome.Cancelling(job.id(), toKill))
               : Cancellation.cancelNow(tx, job, none(), judge).flatMap(finished -> appendCancelled(tx, job, finished));
    }

    private static Promise<CancelOutcome> appendCancelled(Transaction tx, JobLock job, Finishing.Finished finished) {
        return EventWriter.append(tx,
                                  finished.event())
                          .map(_ -> new CancelOutcome.Cancelled(job.id(),
                                                                finished.state()));
    }
}
