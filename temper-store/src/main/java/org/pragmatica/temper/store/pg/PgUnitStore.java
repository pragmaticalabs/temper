package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.facts.UnitEndFacts;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.domain.verdict.UnitOutcome;
import org.pragmatica.temper.domain.verdict.Verdict;
import org.pragmatica.temper.domain.verdict.VerdictInput;
import org.pragmatica.temper.domain.verdict.Verdicts;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.AttemptRef;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HoldingRow;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobFinish;
import org.pragmatica.temper.store.api.LostSettled;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredAttempt;
import org.pragmatica.temper.store.api.StoredEvidence;
import org.pragmatica.temper.store.api.UnitFinishRow;
import org.pragmatica.temper.store.api.UnitManifestRows;
import org.pragmatica.temper.store.api.UnitStartedRow;
import org.pragmatica.temper.store.api.UnitStore;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [UnitStore] over Postgres. `judge` is the build this controller runs (m2-design §1.7): a grant records it on the
/// unit (`units.granted_judge`) and a completion that finishes the job writes it on the result (`Finishing`).
record PgUnitStore(Db db, Judge judge) implements UnitStore {
    static final String ACTIVE_STATES = "('GRANTED','UNKNOWN','RUNNING','CANCELLING')";
    /// The error reason of a unit whose manifest is unsupported (m1-design §2.5).
    static final String MANIFEST_ERROR = "manifest: ";
    /// The grant's candidates without their locks: the same rows, in the same order, up to the same
    /// [UnitStore#CANDIDATE_LIMIT]. The digest reads it (`PgDigestStore`) so that its explanation sees the queue a grant
    /// sees; a `READ ONLY` transaction could not take the locks anyway. It reads every class: the digest is not for one
    /// host, so the explanation applies the CPU-class filter per host itself (`GrantExplain`, m4-design §9.2).
    static final String QUEUED_UNITS = queued("");

    /// m4-design §9.2 (placement is a filter, not a fit, F9): a `dedicated_cpu` job's unit is never a candidate of a
    /// host whose `hosts.shared_cpu` is set (`hostParam`, the granting host: $1 in [#CANDIDATES]). It is never read, never locked by that host's grant
    /// transaction and never its head, so it cannot start the host's starvation clock. [#GRANT] reads the class again in
    /// its own statement and applies the same filter ($2 there): the only writer of `hosts.shared_cpu` is the
    /// controller's host upsert, which completes before the scheduler starts, so both reads see one value; and should a
    /// stale read ever let a dedicated unit through this filter, [#GRANT] still refuses it.
    private static String sharedCpuFilter(String hostParam) {
        return "AND (NOT j.dedicated_cpu OR NOT (SELECT h.shared_cpu FROM hosts h WHERE h.name = " + hostParam + ")) ";
    }

    private static String queued(String filter) {
        return "SELECT u.id, u.job_id, u.name, u.kind, u.memory_mb, u.needs_port_group, j.priority, j.submitted_at, j.dedicated_cpu "
             + "FROM units u JOIN jobs j ON j.id = u.job_id WHERE u.state = 'QUEUED' " + filter
             + "ORDER BY j.priority DESC, j.submitted_at ASC, u.id ASC LIMIT " + UnitStore.CANDIDATE_LIMIT;
    }

    /// m0-design §5.3. At most one grant per unit: the candidate rows stay locked from this SELECT until commit, and a
    /// second claimer skips them (SKIP LOCKED) instead of waiting; the policy may only name a locked candidate. The
    /// conditional UPDATE (`state = 'QUEUED'`, [#GRANT]) re-checks the state, so a writer that bypasses these locks
    /// still cannot produce a second grant.
    private static final String CANDIDATES = queued(sharedCpuFilter("$1")) + " FOR UPDATE OF u SKIP LOCKED";

    /// Rows are locked in id order (the subquery) before the update, the same order `UnitLock.lockAll` uses, so a lease
    /// refresh never deadlocks with a cancel or supersede. Each listed attempt is keyed `job/unit/attempt`, so a
    /// heartbeat of a superseded attempt refreshes nothing (m4-design §4.3).
    private static final String LEASES = "UPDATE units SET lease_until = now() + make_interval(secs => $2) WHERE id IN ("
                                       + "SELECT id FROM units WHERE host = $1 AND state IN " + ACTIVE_STATES
                                       + " AND (job_id::text || '/' || name || '/' || attempt) = ANY($3::text[]) ORDER BY id FOR NO KEY UPDATE)";

    /// The grant judge (ruling R-M2-11: a job counts under the build that granted it) is a column of the unit, written
    /// by this one UPDATE, not of the job: a grant locks units only, never a job (`JobLock`: job first, then units), so
    /// an UPDATE of `jobs` here would take that order backwards against a concurrent cancel or supersede; and a requeue
    /// ([#REQUEUE]) clears it with the rest of the attempt, so a job whose first attempt never ran counts under the
    /// build that grants it again. Every grant is a new attempt (m4-design §4.1): `attempt` grows by one, and the
    /// host's `shared_cpu` is snapshotted on the unit, so the class of the host that ran the current attempt never
    /// depends on a config that changed since (§9.4). The CPU-class filter of [#CANDIDATES] is repeated here as a second
    /// line of defence: a dedicated-CPU unit is never granted to a shared-CPU host, even when the policy names it.
    static final String GRANT = "UPDATE units u SET state = 'GRANTED', host = $2, granted_at = now(), granted_judge = $4, "
                              + "lease_until = now() + make_interval(secs => $3), attempt = u.attempt + 1, "
                              + "shared_cpu = (SELECT h.shared_cpu FROM hosts h WHERE h.name = $2) "
                              + "FROM jobs j WHERE u.id = $1 AND u.state = 'QUEUED' AND j.id = u.job_id " + sharedCpuFilter("$2")
                              + "RETURNING u.id, u.job_id, u.name, u.kind, u.args::text AS args, u.timeout_s, u.memory_mb, j.source_sha, "
                              + "u.needs_port_group, j.priority, u.manifest_spec::text AS manifest_spec, u.granted_at, u.attempt";

    /// #111: the grant transaction's fence, in epoch milliseconds, or [#NO_FENCE]; `is_local` true, so it ends with the
    /// transaction. Always set: the V18 trigger refuses a grant commit that did not set it.
    static final String NO_FENCE = "none";
    static final String GRANT_FENCE = "SELECT set_config('temper.grant_fence', $1, true)";

    private static final String INSERT_TESTCASES = "INSERT INTO testcases (job_id, unit_id, class, name, outcome, time_s, rerun) "
                                                 + "SELECT $1, $2, t.class, t.name, t.outcome, t.time_s::double precision, t.rerun::boolean "
                                                 + "FROM unnest($3::text[], $4::text[], $5::text[], $6::text[], $7::text[]) "
                                                 + "AS t(class, name, outcome, time_s, rerun)";

    /// Facts are kept per attempt (m4-design §6, F4): a requeued unit's next attempt records its own.
    private static final String INSERT_FACTS = "INSERT INTO unit_facts (unit_id, attempt, phase, facts) VALUES ($1, $2, $3, $4::jsonb) "
                                             + "ON CONFLICT (unit_id, attempt, phase) DO NOTHING";

    /// A unit that waits in bb-hold (m1-design §1.3): only a GRANTED or CANCELLING unit that neither started nor
    /// already waits, on the reporting host in the reported attempt (m4-design §4.3); the conditional UPDATE is the
    /// whole guard, so a repeat or a late `UnitHolding` changes nothing.
    private static final String HOLDING = "UPDATE units SET port_group = 'waiting', hold_since = now(), marker_epoch_ns = $3 "
                                        + "WHERE job_id = $1 AND name = $2 AND state IN ('GRANTED','CANCELLING') "
                                        + "AND started_at IS NULL AND port_group IS NULL AND host = $4 AND attempt = $5 RETURNING id, attempt";

    /// A unit that waited for its hold, or whose start names a ticket, held the group from its start.
    private static final String START = "UPDATE units SET state = CASE WHEN state IN ('GRANTED','UNKNOWN') THEN 'RUNNING' ELSE state END, "
                                      + "started_at = $2, container_id = $3, image_id = $4, marker_epoch_ns = $5, "
                                      + "port_group = CASE WHEN port_group IS NOT NULL OR $6::text IS NOT NULL THEN 'held' END, "
                                      + "bbhold_ticket = coalesce($6::text, bbhold_ticket), "
                                      + "hold_since = CASE WHEN port_group IS NOT NULL OR $6::text IS NOT NULL THEN $2 END "
                                      + "WHERE id = $1";

    /// A unit that runs but whose start was not recorded (ruling R-13, amended). A unit that waited for its hold holds
    /// the group now (a hold unit starts only after its grant), since when is not known.
    private static final String START_UNKNOWN = "UPDATE units SET state = 'UNKNOWN', "
                                              + "port_group = CASE WHEN port_group IS NOT NULL THEN 'held' END, hold_since = NULL "
                                              + "WHERE id = $1";

    private static final String REQUEUE = "UPDATE units SET state = 'QUEUED', host = NULL, granted_at = NULL, granted_judge = NULL, "
                                        + "lease_until = NULL, shared_cpu = NULL, "
                                        + "marker_epoch_ns = NULL, port_group = NULL, bbhold_ticket = NULL, hold_since = NULL "
                                        + "WHERE job_id = $1 AND name = $2 AND state = 'GRANTED' AND started_at IS NULL AND host = $3 AND attempt = $4";

    @Override
    public Promise<List<Granted>> grant(HostName host,
                                        Fn1<GrantDecision, List<QueuedUnit>> policy,
                                        TimeSpan lease,
                                        int maxGrants,
                                        Option<Instant> fence) {
        return db.inTransaction(tx -> fenced(tx, fence).flatMap(_ -> grantIn(tx,
                                                                             new GrantRound(host,
                                                                                            policy,
                                                                                            seconds(lease),
                                                                                            judge),
                                                                             maxGrants)));
    }

    /// The fence the V18 deferred trigger reads at COMMIT, local to this transaction (#111): a time, or `none` for a
    /// host without `retireAt`. Never left unset.
    private static Promise<Unit> fenced(Transaction tx, Option<Instant> fence) {
        return Sql.execute(tx,
                           GRANT_FENCE,
                           fence.map(at -> String.valueOf(at.toEpochMilli())).or(NO_FENCE));
    }

    @Override
    public Promise<Unit> holding(HostName host, UnitRef ref, int attempt, HoldingRow row) {
        return Json.write(HostFactsDto.hostFactsDto(row.facts()))
                   .async()
                   .flatMap(facts -> holdingTx(new Hold(host, ref, attempt, row),
                                               facts));
    }

    @Override
    public Promise<Unit> started(HostName host, UnitRef ref, int attempt, UnitStartedRow row) {
        return db.inTransaction(tx -> startedIn(tx, new Start(host, ref, attempt, row)));
    }

    @Override
    public Promise<Unit> startUnknown(HostName host, UnitRef ref, int attempt) {
        return db.inTransaction(tx -> startUnknownIn(tx, host, ref, attempt));
    }

    @Override
    public Promise<Unit> refreshLeases(HostName host, List<AttemptRef> running, TimeSpan lease) {
        return Sql.execute(db.executor(),
                           LEASES,
                           host.value(),
                           seconds(lease),
                           Sql.texts(running.stream().map(PgUnitStore::attemptKey)));
    }

    @Override
    public Promise<List<StoredAttempt>> attempts(List<UnitRef> refs) {
        return Sql.query(db.executor(),
                         "SELECT job_id, name, attempt, host, state FROM units "
                        + "WHERE (job_id, name) IN (SELECT * FROM unnest($1::text[]::bigint[], $2::text[])) ORDER BY id",
                         Sql.texts(refs.stream().map(ref -> String.valueOf(ref.job().value()))),
                         Sql.texts(refs.stream().map(ref -> ref.unit()
                                                               .value())))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgUnitStore::storedAttempt));
    }

    @Override
    public Promise<List<ActiveUnit>> active(HostName host) {
        return Sql.query(db.executor(),
                         "SELECT id, job_id, name, kind, state, memory_mb, timeout_s, lease_until, started_at, needs_port_group, "
                        + "port_group, bbhold_ticket, granted_at, attempt FROM units "
                        + "WHERE host = $1 AND state IN " + ACTIVE_STATES
                        + " ORDER BY id",
                         host.value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgUnitStore::activeUnit));
    }

    @Override
    public Promise<CompleteOutcome> complete(HostName host,
                                             UnitRef ref,
                                             int attempt,
                                             UnitFinishRow finish,
                                             List<TestcaseRow> rows,
                                             Option<UnitManifestRows> manifest,
                                             Option<StoredEvidence> evidence,
                                             Fn1<Option<JobFinish>, VerdictInput> decideJob) {
        return ownRows(ref, rows).async()
                      .map(valid -> new Completion(host,
                                                   ref,
                                                   attempt,
                                                   withManifestError(finish, manifest),
                                                   valid,
                                                   manifest,
                                                   evidence,
                                                   decideJob,
                                                   judge))
                      .flatMap(this::completeTx);
    }

    private Promise<Unit> holdingTx(Hold hold, String facts) {
        return db.inTransaction(tx -> holdingIn(tx, hold, facts));
    }

    private Promise<CompleteOutcome> completeTx(Completion completion) {
        return db.inTransaction(tx -> completeIn(tx, completion));
    }

    @Override
    public Promise<List<LostSettled>> settleLost(HostName host,
                                                 Instant now,
                                                 Fn1<Option<JobFinish>, VerdictInput> decideJob) {
        return active(host).flatMap(units -> LostSettlement.settleAll(db,
                                                                      LostSettlement.Loss.loss(host,
                                                                                               now,
                                                                                               decideJob,
                                                                                               judge),
                                                                      units));
    }

    @Override
    public Promise<Unit> requeue(HostName host, UnitRef ref, int attempt) {
        return Sql.update(db.executor(),
                          REQUEUE,
                          ref.job().value(),
                          ref.unit().value(),
                          host.value(),
                          attempt)
                  .flatMap(changed -> requeued(ref, changed));
    }

    /// Nothing changed: a known unit in another state is left alone; an unknown unit fails.
    private Promise<Unit> requeued(UnitRef ref, int changed) {
        return changed > 0
               ? Promise.unitPromise()
               : exists(db.executor(), ref);
    }

    // ---- grant -----------------------------------------------------------------------------------------------
    private record GrantRound(HostName host,
                              Fn1<GrantDecision, List<QueuedUnit>> policy,
                              double leaseSeconds,
                              Judge judge) {}

    private static Promise<List<Granted>> grantIn(Transaction tx, GrantRound round, int maxGrants) {
        return Sql.query(tx,
                         CANDIDATES,
                         round.host().value())
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgUnitStore::queuedUnit))
                  .flatMap(queued -> rounds(tx,
                                            round,
                                            maxGrants,
                                            queued,
                                            List.of()));
    }

    private static Promise<List<Granted>> rounds(Transaction tx,
                                                 GrantRound round,
                                                 int left,
                                                 List<QueuedUnit> queued,
                                                 List<Granted> granted) {
        return left <= 0
               ? Promise.success(granted)
               : decide(tx, round, left, queued, granted);
    }

    /// A decision naming no unit, or a unit not among the locked candidates, ends the rounds.
    private static Promise<List<Granted>> decide(Transaction tx,
                                                 GrantRound round,
                                                 int left,
                                                 List<QueuedUnit> queued,
                                                 List<Granted> granted) {
        return round.policy()
                    .apply(queued)
                    .grant()
                    .filter(unitId -> listed(queued, unitId))
                    .map(unitId -> grantAndContinue(tx, round, left, queued, granted, unitId))
                    .or(() -> Promise.success(granted));
    }

    private static Promise<List<Granted>> grantAndContinue(Transaction tx,
                                                           GrantRound round,
                                                           int left,
                                                           List<QueuedUnit> queued,
                                                           List<Granted> granted,
                                                           long unitId) {
        return grantOne(tx, round, unitId).flatMap(one -> rounds(tx,
                                                                 round,
                                                                 left - 1,
                                                                 without(queued, unitId),
                                                                 Stream.concat(granted.stream(), one.stream()).toList()));
    }

    private static Promise<Option<Granted>> grantOne(Transaction tx, GrantRound round, long unitId) {
        return Sql.query(tx,
                         GRANT,
                         unitId,
                         round.host().value(),
                         round.leaseSeconds(),
                         round.judge().value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgUnitStore::granted))
                  .flatMap(granted -> recordCheckout(tx, round, granted));
    }

    /// The host will hold the job's directory from `PrepareJob` on (`checkouts`, m1-design §4.3); kept across a requeue.
    private static Promise<Option<Granted>> recordCheckout(Transaction tx, GrantRound round, Option<Granted> granted) {
        return granted.map(present -> insertCheckout(tx, round, present))
                      .or(Promise::unitPromise)
                      .map(_ -> granted);
    }

    private static Promise<Unit> insertCheckout(Transaction tx, GrantRound round, Granted granted) {
        return Sql.execute(tx,
                           "INSERT INTO checkouts (job_id, host) VALUES ($1, $2) ON CONFLICT DO NOTHING",
                           granted.ref().job().value(),
                           round.host().value());
    }

    private static boolean listed(List<QueuedUnit> queued, long unitId) {
        return queued.stream()
                     .anyMatch(unit -> unit.unitId() == unitId);
    }

    private static List<QueuedUnit> without(List<QueuedUnit> queued, long unitId) {
        return queued.stream()
                     .filter(unit -> unit.unitId() != unitId)
                     .toList();
    }

    static Result<QueuedUnit> queuedUnit(PgRow row) {
        return Rows.enumValue(UnitKind.class,
                              row.getString("kind"))
                   .map(kind -> QueuedUnit.queuedUnit(row.getLong("id"),
                                                      row.getInt("priority"),
                                                      row.getInstant("submitted_at"),
                                                      row.getInt("memory_mb"),
                                                      row.getBoolean("needs_port_group"),
                                                      kind,
                                                      row.getBoolean("dedicated_cpu")))
                   .mapError(Rows.corrupt("units"));
    }

    private static Result<Granted> granted(PgRow row) {
        return Result.all(Rows.jobId(row, "job_id"),
                          UnitName.unitName(row.getString("name")),
                          Rows.enumValue(UnitKind.class,
                                         row.getString("kind")),
                          Json.read(row.getString("args"),
                                    Json.STEPS),
                          Sha.sha(row.getString("source_sha")),
                          Values.optional(Rows.text(row, "manifest_spec"),
                                          PgUnitStore::manifestSpec))
                     .map((job, name, kind, steps, sha, manifest) -> Granted.granted(UnitRef.unitRef(job, name),
                                                                                     row.getLong("id"),
                                                                                     kind,
                                                                                     steps,
                                                                                     Duration.ofSeconds(row.getInt("timeout_s")),
                                                                                     row.getInt("memory_mb"),
                                                                                     sha,
                                                                                     row.getBoolean("needs_port_group"),
                                                                                     row.getInt("priority"),
                                                                                     manifest,
                                                                                     row.getInstant("granted_at"),
                                                                                     row.getInt("attempt")))
                     .mapError(Rows.corrupt("units"));
    }

    private static Result<ManifestSpec> manifestSpec(String json) {
        return Json.read(json, ManifestSpecDto.class).flatMap(ManifestSpecDto::toDomain);
    }

    private static Result<ActiveUnit> activeUnit(PgRow row) {
        return Result.all(Rows.jobId(row, "job_id"),
                          UnitName.unitName(row.getString("name")),
                          Rows.enumValue(UnitState.class,
                                         row.getString("state")),
                          Values.optional(Rows.text(row, "port_group"),
                                          PortGroup::portGroup),
                          Rows.enumValue(UnitKind.class,
                                         row.getString("kind")))
                     .map((job, name, state, portGroup, kind) -> ActiveUnit.activeUnit(UnitRef.unitRef(job, name),
                                                                                       row.getLong("id"),
                                                                                       state,
                                                                                       row.getInt("memory_mb"),
                                                                                       Duration.ofSeconds(row.getInt("timeout_s")),
                                                                                       Option.option(row.getInstant("lease_until")),
                                                                                       Option.option(row.getInstant("started_at")),
                                                                                       row.getBoolean("needs_port_group"),
                                                                                       portGroup,
                                                                                       Rows.text(row, "bbhold_ticket"),
                                                                                       kind,
                                                                                       Option.option(row.getInstant("granted_at")),
                                                                                       row.getInt("attempt")))
                     .mapError(Rows.corrupt("units"));
    }

    private static Result<StoredAttempt> storedAttempt(PgRow row) {
        return Result.all(Rows.jobId(row, "job_id"),
                          UnitName.unitName(row.getString("name")),
                          Values.optional(Rows.text(row, "host"),
                                          HostName::hostName),
                          Rows.enumValue(UnitState.class,
                                         row.getString("state")))
                     .map((job, name, host, state) -> StoredAttempt.storedAttempt(UnitRef.unitRef(job, name),
                                                                                  row.getInt("attempt"),
                                                                                  host,
                                                                                  state))
                     .mapError(Rows.corrupt("units"));
    }

    private static String unitKey(UnitRef ref) {
        return ref.job()
                  .value() + "/" + ref.unit()
                                      .value();
    }

    private static String attemptKey(AttemptRef attempt) {
        return unitKey(attempt.ref()) + "/" + attempt.attempt();
    }

    private static double seconds(TimeSpan span) {
        return span.duration()
                   .toMillis() / 1000.0;
    }

    // ---- locks -----------------------------------------------------------------------------------------------
    static Promise<JobLock> lockJob(QueryExecutor tx, UnitRef ref) {
        return JobLock.lock(tx,
                            ref.job())
                      .flatMap(job -> job.async(notFound(ref)));
    }

    static Promise<UnitLock> lockUnit(QueryExecutor tx, UnitRef ref) {
        return UnitLock.lock(tx, ref).flatMap(unit -> unit.async(notFound(ref)));
    }

    private static StoreError notFound(UnitRef ref) {
        return StoreError.UnitNotFound.FACTORY.apply(ref.job(), ref.unit());
    }

    private static Promise<Unit> exists(QueryExecutor executor, UnitRef ref) {
        return lockFree(executor, ref).flatMap(unit -> unit.async(notFound(ref)))
                       .mapToUnit();
    }

    private static Promise<Option<UnitLock>> lockFree(QueryExecutor executor, UnitRef ref) {
        return Sql.query(executor,
                         UnitLock.SELECT + "WHERE job_id = $1 AND name = $2",
                         ref.job().value(),
                         ref.unit().value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, UnitLock::unitLock));
    }

    // ---- holding ---------------------------------------------------------------------------------------------
    private record Hold(HostName host, UnitRef ref, int attempt, HoldingRow row) {}

    private static Promise<Unit> holdingIn(Transaction tx, Hold hold, String facts) {
        return Sql.query(tx,
                         HOLDING,
                         hold.ref().job().value(),
                         hold.ref().unit().value(),
                         hold.row().marker().nanos(),
                         hold.host().value(),
                         hold.attempt())
                  .flatMap(resultSet -> holdFacts(tx,
                                                  hold.ref(),
                                                  Sql.first(resultSet),
                                                  facts));
    }

    /// The unit now waits: its `hold` facts, of this attempt. Nothing changed: a known unit is a repeat or another
    /// attempt (no-op), an unknown one fails.
    private static Promise<Unit> holdFacts(Transaction tx, UnitRef ref, Option<PgRow> waiting, String facts) {
        return waiting.map(row -> Sql.execute(tx,
                                              INSERT_FACTS,
                                              row.getLong("id"),
                                              row.getInt("attempt"),
                                              "hold",
                                              facts))
                      .or(() -> exists(tx, ref));
    }

    // ---- started ---------------------------------------------------------------------------------------------
    private record Start(HostName host, UnitRef ref, int attempt, UnitStartedRow row) {}

    private static Promise<Unit> startedIn(Transaction tx, Start start) {
        return lockJob(tx, start.ref()).flatMap(job -> startJob(tx, job, start));
    }

    private static Promise<Unit> startJob(Transaction tx, JobLock job, Start start) {
        return lockUnit(tx, start.ref()).flatMap(unit -> startLocked(tx, job, start, unit));
    }

    /// Only the first start of a GRANTED, UNKNOWN (or meanwhile CANCELLING) unit on the starting host, in its current
    /// attempt, is recorded; anything else is a repeat, a start from a host that no longer owns the unit (requeued,
    /// granted elsewhere; R-13), or a start of a superseded attempt (requeued and granted again, even to this host;
    /// m4-design §4.3).
    private static Promise<Unit> startLocked(Transaction tx, JobLock job, Start start, UnitLock unit) {
        return startable(unit, start.host(), start.attempt())
               ? recordStart(tx, job, unit, start.row())
               : Promise.unitPromise();
    }

    private static boolean startable(UnitLock unit, HostName host, int attempt) {
        return (unit.state() == UnitState.GRANTED || unit.state() == UnitState.UNKNOWN || unit.state() == UnitState.CANCELLING)
               && unit.startedAt()
                      .isEmpty()
               && current(unit, host, attempt);
    }

    /// The unit is on `host` in `attempt`: the attempt guard of every write about one attempt (m4-design §4.3).
    private static boolean current(UnitLock unit, HostName host, int attempt) {
        return unit.host()
                   .equals(some(host)) && unit.attempt() == attempt;
    }

    private static Promise<Unit> recordStart(Transaction tx, JobLock job, UnitLock unit, UnitStartedRow row) {
        return Json.write(HostFactsDto.hostFactsDto(row.facts()))
                   .async()
                   .flatMap(facts -> writeStart(tx, unit, row, facts))
                   .flatMap(_ -> Sql.update(tx,
                                            "UPDATE jobs SET state = 'RUNNING' WHERE id = $1 AND state IN ('QUEUED','UNKNOWN')",
                                            job.id().value()))
                   .flatMap(changed -> announceStart(tx, job, unit, changed > 0));
    }

    private static Promise<Unit> writeStart(Transaction tx, UnitLock unit, UnitStartedRow row, String facts) {
        return Sql.execute(tx,
                           START,
                           unit.id(),
                           Sql.utc(row.startedAt()),
                           row.containerId(),
                           row.imageId(),
                           row.marker().nanos(),
                           Sql.nullable(row.ticket()))
                  .flatMap(_ -> Sql.execute(tx,
                                            INSERT_FACTS,
                                            unit.id(),
                                            unit.attempt(),
                                            "start",
                                            facts));
    }

    /// `started` is a job event: appended only when this start moved the job from QUEUED or UNKNOWN to RUNNING.
    private static Promise<Unit> announceStart(Transaction tx, JobLock job, UnitLock unit, boolean jobStarted) {
        return jobStarted
               ? EventWriter.append(tx,
                                    PendingEvent.pendingEvent(job.id(),
                                                              EventRow.Kind.STARTED,
                                                              job.unitPayload(unit.ref().unit(),
                                                                              none())))
                            .mapToUnit()
               : Promise.unitPromise();
    }

    // ---- start unknown ---------------------------------------------------------------------------------------
    private static Promise<Unit> startUnknownIn(Transaction tx, HostName host, UnitRef ref, int attempt) {
        return lockJob(tx, ref).flatMap(job -> unknownJob(tx, job, host, ref, attempt));
    }

    private static Promise<Unit> unknownJob(Transaction tx, JobLock job, HostName host, UnitRef ref, int attempt) {
        return lockUnit(tx, ref).flatMap(unit -> unknownLocked(tx, job, host, unit, attempt));
    }

    /// Only a GRANTED unit on the reporting host whose start was not recorded, in `attempt`, becomes UNKNOWN; a unit
    /// that started, is CANCELLING (its state already says what happens next), terminal, on another host, or granted
    /// again since (a later attempt, verification F4, m4-design §4.3) is left alone.
    private static Promise<Unit> unknownLocked(Transaction tx, JobLock job, HostName host, UnitLock unit, int attempt) {
        return unit.state() == UnitState.GRANTED && startable(unit, host, attempt)
               ? recordUnknown(tx, job, unit)
               : Promise.unitPromise();
    }

    /// `start-unknown` is a unit event: appended for every unit this marks.
    private static Promise<Unit> recordUnknown(Transaction tx, JobLock job, UnitLock unit) {
        return Sql.execute(tx,
                           START_UNKNOWN,
                           unit.id())
                  .flatMap(_ -> Sql.execute(tx,
                                            "UPDATE jobs SET state = 'UNKNOWN' WHERE id = $1 AND state = 'QUEUED'",
                                            job.id().value()))
                  .flatMap(_ -> EventWriter.append(tx,
                                                   PendingEvent.pendingEvent(job.id(),
                                                                             EventRow.Kind.START_UNKNOWN,
                                                                             job.unitPayload(unit.ref().unit(),
                                                                                             none()))))
                  .mapToUnit();
    }

    // ---- complete --------------------------------------------------------------------------------------------
    record Completion(HostName host,
                      UnitRef ref,
                      int attempt,
                      UnitFinishRow finish,
                      List<TestcaseRow> rows,
                      Option<UnitManifestRows> manifest,
                      Option<StoredEvidence> evidence,
                      Fn1<Option<JobFinish>, VerdictInput> decideJob,
                      Judge judge) {}

    /// An unsupported manifest is the unit's error (`ERROR(manifest: <reason>)`, Q-M1-7) unless the worker or the
    /// controller already gave one.
    private static UnitFinishRow withManifestError(UnitFinishRow finish, Option<UnitManifestRows> manifest) {
        return UnitFinishRow.unitFinishRow(finish.rc(),
                                           finish.killedBy(),
                                           finish.endedAt(),
                                           finish.endFacts(),
                                           finish.testsAttr(),
                                           finish.voidReason(),
                                           finish.errorReason()
                                                 .orElse(() -> manifest.flatMap(PgUnitStore::unsupportedReason)));
    }

    private static Option<String> unsupportedReason(UnitManifestRows manifest) {
        return switch (manifest.outcome()) {
            case ManifestOutcome.Unsupported unsupported -> some(MANIFEST_ERROR + unsupported.reason());
            case ManifestOutcome.Computed _ -> none();
        };
    }

    /// Fail-fast on the first row attributed to another unit, so the caller sees the typed `ForeignTestcase`.
    private static Result<List<TestcaseRow>> ownRows(UnitRef ref, List<TestcaseRow> rows) {
        return Option.from(rows.stream().filter(row -> !row.unit()
                                                           .equals(ref.unit())).findFirst())
                     .map(row -> StoreError.ForeignTestcase.FACTORY.apply(ref.unit(),
                                                                          row.unit())
                                                                   .<List<TestcaseRow>> result())
                     .or(() -> Result.success(rows));
    }

    private static Promise<CompleteOutcome> completeIn(Transaction tx, Completion completion) {
        return lockJob(tx, completion.ref()).flatMap(job -> completeJob(tx, completion, job));
    }

    private static Promise<CompleteOutcome> completeJob(Transaction tx, Completion completion, JobLock job) {
        return lockUnit(tx, completion.ref()).flatMap(unit -> completeLocked(tx, completion, job, unit));
    }

    /// Completion is idempotent: a unit already terminal writes nothing. A QUEUED unit was requeued after its report was
    /// sent (a stale report): nothing is written either, and neither for an active unit that is not on the reporting
    /// host in the reported attempt ([#completeCurrent]).
    private static Promise<CompleteOutcome> completeLocked(Transaction tx,
                                                           Completion completion,
                                                           JobLock job,
                                                           UnitLock unit) {
        return unit.state() == UnitState.QUEUED
               ? Promise.success(new CompleteOutcome.NotActive(completion.ref(), unit.state()))
               : completeActive(tx, completion, job, unit);
    }

    private static Promise<CompleteOutcome> completeActive(Transaction tx,
                                                           Completion completion,
                                                           JobLock job,
                                                           UnitLock unit) {
        return unit.state()
                   .isTerminal()
               ? Promise.success(new CompleteOutcome.AlreadyComplete(completion.ref(), unit.state()))
               : completeCurrent(tx, completion, job, unit);
    }

    /// m4-design §4.3 (R-M4-3, at most one stored result per unit): the attempt guard runs under the unit's row lock in
    /// the transaction that writes, so a report of a superseded attempt, or from a host the unit left, stores nothing
    /// however late it arrives.
    private static Promise<CompleteOutcome> completeCurrent(Transaction tx,
                                                            Completion completion,
                                                            JobLock job,
                                                            UnitLock unit) {
        return current(unit, completion.host(), completion.attempt())
               ? finishUnit(tx, completion, job, unit)
               : Promise.success(new CompleteOutcome.Superseded(completion.ref(),
                                                                unit.state(),
                                                                completion.attempt(),
                                                                unit.attempt()));
    }

    static Promise<CompleteOutcome> finishUnit(Transaction tx, Completion completion, JobLock job, UnitLock unit) {
        var verdict = unitVerdict(completion, unit);
        var state = UnitState.fromVerdict(verdict);

        return insertTestcases(tx,
                               job,
                               unit,
                               completion.rows()).flatMap(_ -> writeManifest(tx,
                                                                             job,
                                                                             unit,
                                                                             completion.manifest()))
                              .flatMap(_ -> writeEvidence(tx,
                                                          job,
                                                          unit,
                                                          completion.evidence()))
                              .flatMap(_ -> updateFinished(tx,
                                                           unit,
                                                           completion.finish(),
                                                           state,
                                                           verdict))
                              .flatMap(_ -> endFacts(tx,
                                                     unit,
                                                     completion.finish().endFacts()))
                              .flatMap(_ -> maybeFinishJob(tx, completion, job))
                              .flatMap(finished -> appendCompletion(tx, job, unit, verdict, finished));
    }

    /// The unit's own state is its single-unit verdict (`UnitState.fromVerdict`); a unit that was CANCELLING, or
    /// killed by a cancel, judges as cancelled.
    private static Verdict unitVerdict(Completion completion, UnitLock unit) {
        var finish = completion.finish();
        var outcome = UnitOutcome.unitOutcome(unit.ref().unit(),
                                              unit.requestsTests(),
                                              unit.rerun(),
                                              finish.rc(),
                                              finish.killedBy(),
                                              finish.voidReason(),
                                              finish.errorReason(),
                                              completion.rows(),
                                              finish.testsAttr());

        return Verdicts.evaluate(VerdictInput.verdictInput(List.of(outcome), cancelled(unit, finish), false, none())).verdict();
    }

    private static boolean cancelled(UnitLock unit, UnitFinishRow finish) {
        return unit.state() == UnitState.CANCELLING || finish.killedBy()
                                                             .filter(cause -> cause == KillCause.CANCEL)
                                                             .isPresent();
    }

    private static Promise<Unit> insertTestcases(Transaction tx, JobLock job, UnitLock unit, List<TestcaseRow> rows) {
        return Sql.execute(tx,
                           INSERT_TESTCASES,
                           job.id().value(),
                           unit.id(),
                           Sql.texts(rows.stream().map(TestcaseRow::className)),
                           Sql.texts(rows.stream().map(TestcaseRow::name)),
                           Sql.texts(rows.stream().map(row -> row.outcome()
                                                                 .wire())),
                           Sql.texts(rows.stream().map(row -> Double.toString(row.timeSeconds()))),
                           Sql.texts(rows.stream().map(row -> Boolean.toString(row.rerun()))));
    }

    private static Promise<Unit> writeManifest(Transaction tx,
                                               JobLock job,
                                               UnitLock unit,
                                               Option<UnitManifestRows> manifest) {
        return manifest.map(present -> Manifests.write(tx,
                                                       job.id(),
                                                       unit.id(),
                                                       present.outcome()))
                       .or(Promise::unitPromise);
    }

    private static Promise<Unit> writeEvidence(Transaction tx,
                                               JobLock job,
                                               UnitLock unit,
                                               Option<StoredEvidence> evidence) {
        return evidence.map(small -> EvidenceRows.upsert(tx,
                                                         job.id(),
                                                         unit.id(),
                                                         EvidenceKind.SMALL,
                                                         small))
                       .or(Promise::unitPromise);
    }

    private static Promise<Unit> updateFinished(Transaction tx,
                                                UnitLock unit,
                                                UnitFinishRow finish,
                                                UnitState state,
                                                Verdict verdict) {
        return Sql.execute(tx,
                           "UPDATE units SET state = $2, reason = $3, rc = $4, killed_by = $5, ended_at = $6, tests_attr = $7 "
                          + "WHERE id = $1",
                           unit.id(),
                           state.name(),
                           Sql.nullable(verdict.fullReason()),
                           Sql.nullable(finish.rc()),
                           Sql.nullable(finish.killedBy().map(Rows::killedBy)),
                           Sql.utc(finish.endedAt()),
                           finish.testsAttr());
    }

    private static Promise<Unit> endFacts(Transaction tx, UnitLock unit, Option<UnitEndFacts> facts) {
        return facts.map(present -> writeEndFacts(tx, unit, present))
                    .or(Promise::unitPromise);
    }

    private static Promise<Unit> writeEndFacts(Transaction tx, UnitLock unit, UnitEndFacts facts) {
        return Json.write(UnitEndFactsDto.unitEndFactsDto(facts))
                   .async()
                   .flatMap(json -> Sql.execute(tx,
                                                INSERT_FACTS,
                                                unit.id(),
                                                unit.attempt(),
                                                "end",
                                                json));
    }

    /// When every unit is terminal, and the job is not finished already (its result row is write-once), the controller's
    /// `decideJob` decides; `Some` finishes the job.
    private static Promise<Option<Finishing.Finished>> maybeFinishJob(Transaction tx,
                                                                      Completion completion,
                                                                      JobLock job) {
        return Finishing.snapshot(tx,
                                  job.id())
                        .flatMap(snapshot -> consultWhenDone(tx, completion, job, snapshot));
    }

    private static Promise<Option<Finishing.Finished>> consultWhenDone(Transaction tx,
                                                                       Completion completion,
                                                                       JobLock job,
                                                                       Finishing.Snapshot snapshot) {
        return snapshot.allUnitsTerminal() && !snapshot.job()
                                                       .state()
                                                       .isTerminal()
               ? consult(tx, completion, job, snapshot)
               : Promise.success(none());
    }

    private static Promise<Option<Finishing.Finished>> consult(Transaction tx,
                                                               Completion completion,
                                                               JobLock job,
                                                               Finishing.Snapshot snapshot) {
        return completion.decideJob()
                         .apply(snapshot.input())
                         .map(decision -> finishWith(tx,
                                                     job,
                                                     snapshot,
                                                     decision,
                                                     completion.judge()))
                         .or(() -> Promise.success(none()));
    }

    private static Promise<Option<Finishing.Finished>> finishWith(Transaction tx,
                                                                  JobLock job,
                                                                  Finishing.Snapshot snapshot,
                                                                  JobFinish decision,
                                                                  Judge judge) {
        return Finishing.finish(tx,
                                job,
                                snapshot,
                                decision.evaluation(),
                                judge)
                        .map(Option::some);
    }

    /// Events last: `unit-finished`, then `finished` / `cancelled` when the job finished.
    private static Promise<CompleteOutcome> appendCompletion(Transaction tx,
                                                             JobLock job,
                                                             UnitLock unit,
                                                             Verdict verdict,
                                                             Option<Finishing.Finished> finished) {
        var unitFinished = PendingEvent.pendingEvent(job.id(),
                                                     EventRow.Kind.UNIT_FINISHED,
                                                     job.unitPayload(unit.ref().unit(),
                                                                     some(verdict)));

        return EventWriter.appendAll(tx,
                                     Stream.concat(Stream.of(unitFinished),
                                                   finished.map(Finishing.Finished::event).stream())
                                           .toList())
                          .map(_ -> new CompleteOutcome.Completed(unit.ref(),
                                                                  UnitState.fromVerdict(verdict),
                                                                  finished.map(Finishing.Finished::state)));
    }
}
