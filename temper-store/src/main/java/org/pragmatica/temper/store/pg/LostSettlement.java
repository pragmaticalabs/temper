package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.LostSettle;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.domain.verdict.VerdictInput;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobFinish;
import org.pragmatica.temper.store.api.LostSettled;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.UnitFinishRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [org.pragmatica.temper.store.api.UnitStore#settleLost] over Postgres (m4-design §2.3): per unit one transaction, the
/// decision is [LostSettle]'s over the locked row, and a unit that ends (L1–L3) completes through the same writes as a
/// report ([PgUnitStore#finishUnit]) with no testcases, manifest or evidence.
sealed interface LostSettlement {
    Logger LOG = LoggerFactory.getLogger(LostSettlement.class);

    /// The settle's inputs: the LOST host, the lost attempts' end, the job decision and the controller's build.
    record Loss(HostName host, Instant now, Fn1<Option<JobFinish>, VerdictInput> decideJob, Judge judge) {
        static Loss loss(HostName host, Instant now, Fn1<Option<JobFinish>, VerdictInput> decideJob, Judge judge) {
            return new Loss(host, now, decideJob, judge);
        }
    }

    /// The row's settle inputs besides the lock's: its declaration and its requeues so far.
    record LostRow(SideEffects sideEffects, int requeues) {}

    String LOST_ROW = "SELECT side_effects, host_lost_requeues FROM units WHERE id = $1";

    /// The lost attempt, from the unit row as it stands under the lock (write-once, V11).
    String INSERT_ATTEMPT = "INSERT INTO unit_attempts (unit_id, attempt, host, granted_at, started_at, ended_at, shared_cpu, outcome) "
                          + "SELECT id, attempt, host, granted_at, started_at, $2, shared_cpu, '" + LostSettle.HOST_LOST
                          + "' "
                          + "FROM units WHERE id = $1";

    /// The listed host is still LOST, read inside the settle's transaction: a host that recovered between the listing
    /// and the transaction is settled by its own recovery, not by a stale tick.
    String HOST_LOST = "SELECT count(*) AS n FROM hosts WHERE name = $1 AND state = 'LOST'";

    /// L4: the unit is QUEUED again with every column of the lost attempt cleared; `attempt` stays and the next grant
    /// increments it. The lost attempt's row is written in the same statement and only for a unit this moves, so the
    /// affected count (1 or 0) says whether the unit moved; both sub-statements read the row as it stood before.
    /// `host_lost_requeues + 1` is checked by the column's CHECK (`BETWEEN 0 AND 1`), so a second requeue fails the
    /// transaction instead of running the unit a third time.
    String REQUEUE_LOST = "WITH lost AS (INSERT INTO unit_attempts (unit_id, attempt, host, granted_at, started_at, ended_at, shared_cpu, outcome) "
                        + "SELECT id, attempt, host, granted_at, started_at, $4, shared_cpu, '" + LostSettle.HOST_LOST
                        + "' "
                        + "FROM units WHERE id = $1 AND host = $2 AND attempt = $3 AND state IN " + PgUnitStore.ACTIVE_STATES
                        + " RETURNING unit_id) "
                        + "UPDATE units SET state = 'QUEUED', host = NULL, granted_at = NULL, granted_judge = NULL, lease_until = NULL, "
                        + "started_at = NULL, container_id = NULL, image_id = NULL, marker_epoch_ns = NULL, port_group = NULL, "
                        + "bbhold_ticket = NULL, hold_since = NULL, shared_cpu = NULL, host_lost_requeues = host_lost_requeues + 1 "
                        + "WHERE id IN (SELECT unit_id FROM lost)";

    /// After L2–L4 an UNKNOWN job with no UNKNOWN unit left is QUEUED again only when every unit of it is QUEUED: then
    /// nothing of it was recorded as running or finished (R-M4-16, Q-M4-5). Otherwise a unit is active or finished, and
    /// the job is RUNNING. A job the settle finished, or a RUNNING job, is not UNKNOWN and is left alone. No `started`
    /// event is appended: no start was reported [G].
    String UNKNOWN_JOB_SETTLED = "UPDATE jobs SET state = CASE WHEN EXISTS (SELECT 1 FROM units WHERE job_id = $1 AND state <> 'QUEUED') "
                               + "THEN 'RUNNING' ELSE 'QUEUED' END WHERE id = $1 AND state = 'UNKNOWN' "
                               + "AND NOT EXISTS (SELECT 1 FROM units WHERE job_id = $1 AND state = 'UNKNOWN')";

    static Promise<List<LostSettled>> settleAll(Db db, Loss loss, List<ActiveUnit> units) {
        return Sql.sequence(units,
                            unit -> db.inTransaction(tx -> settleOne(tx, loss, unit))
                                      .onFailure(cause -> logFailed(loss, unit, cause)))
                  .map(LostSettlement::settled);
    }

    /// The call fails at the first unit whose transaction fails; the units after it wait for the next liveness tick.
    private static void logFailed(Loss loss, ActiveUnit unit, Cause cause) {
        LOG.warn("Settle of unit {} on LOST host {} failed, the rest wait for the next tick: {}",
                 unit.ref(),
                 loss.host().value(),
                 cause.message());
    }

    private static List<LostSettled> settled(List<Option<LostSettled>> outcomes) {
        return outcomes.stream()
                       .flatMap(Option::stream)
                       .toList();
    }

    private static Promise<Option<LostSettled>> settleOne(Transaction tx, Loss loss, ActiveUnit unit) {
        return PgUnitStore.lockJob(tx,
                                   unit.ref())
                          .flatMap(job -> settleJob(tx, loss, job, unit));
    }

    private static Promise<Option<LostSettled>> settleJob(Transaction tx, Loss loss, JobLock job, ActiveUnit listed) {
        return PgUnitStore.lockUnit(tx,
                                    listed.ref())
                          .flatMap(locked -> settleLocked(tx, loss, job, locked, listed));
    }

    /// Under the lock the unit may have finished, been cancelled to its end, left the host, or been granted again
    /// since it was listed (a stale tick): only the listed attempt, still active on the host, is settled, and only
    /// while the host is still LOST. Otherwise nothing is settled (the repeat of a settle is a no-op).
    private static Promise<Option<LostSettled>> settleLocked(Transaction tx,
                                                             Loss loss,
                                                             JobLock job,
                                                             UnitLock unit,
                                                             ActiveUnit listed) {
        return unit.active() && unit.host()
                                    .equals(some(loss.host())) && unit.attempt() == listed.attempt()
               ? hostLost(tx, loss).flatMap(lost -> settleWhileLost(tx, loss, job, unit, listed, lost))
               : Promise.success(none());
    }

    private static Promise<Boolean> hostLost(Transaction tx, Loss loss) {
        return Sql.query(tx,
                         HOST_LOST,
                         loss.host().value())
                  .map(resultSet -> resultSet.index(0)
                                             .getLong("n") == 1);
    }

    private static Promise<Option<LostSettled>> settleWhileLost(Transaction tx,
                                                                Loss loss,
                                                                JobLock job,
                                                                UnitLock unit,
                                                                ActiveUnit listed,
                                                                boolean lost) {
        return lost
               ? settleActive(tx, loss, job, unit, listed)
               : Promise.success(none());
    }

    private static Promise<Option<LostSettled>> settleActive(Transaction tx,
                                                             Loss loss,
                                                             JobLock job,
                                                             UnitLock unit,
                                                             ActiveUnit listed) {
        return lostRow(tx, unit).flatMap(row -> apply(tx,
                                                      loss,
                                                      job,
                                                      unit,
                                                      listed,
                                                      LostSettle.settle(unit.state(), row.sideEffects(), row.requeues())));
    }

    private static Promise<LostRow> lostRow(Transaction tx, UnitLock unit) {
        return Sql.query(tx,
                         LOST_ROW,
                         unit.id())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, LostSettlement::lostRow))
                  .flatMap(row -> row.async(StoreError.UnitNotFound.FACTORY.apply(unit.ref().job(),
                                                                                  unit.ref().unit())));
    }

    private static Result<LostRow> lostRow(PgRow row) {
        return Rows.sideEffects(row.getString("side_effects"))
                   .map(sideEffects -> new LostRow(sideEffects,
                                                   row.getInt("host_lost_requeues")))
                   .mapError(Rows.corrupt("units"));
    }

    private static Promise<Option<LostSettled>> apply(Transaction tx,
                                                      Loss loss,
                                                      JobLock job,
                                                      UnitLock unit,
                                                      ActiveUnit listed,
                                                      LostSettle.Outcome outcome) {
        return switch (outcome) {
            case CANCELLED -> finish(tx, loss, job, unit, outcome, cancelled(loss.now())).map(Option::some);
            case EXTERNAL, BOUND -> endLost(tx, loss, job, unit, outcome).map(Option::some);
            case REQUEUED -> requeue(tx, loss, job, unit, listed);
        };
    }

    /// L2, L3: the lost attempt is recorded, the unit ends `ERROR(host-lost)`, then the job state rule applies.
    private static Promise<LostSettled> endLost(Transaction tx,
                                                Loss loss,
                                                JobLock job,
                                                UnitLock unit,
                                                LostSettle.Outcome outcome) {
        return Sql.execute(tx,
                           INSERT_ATTEMPT,
                           unit.id(),
                           Sql.utc(loss.now()))
                  .flatMap(_ -> announceLoss(tx, job, unit))
                  .flatMap(_ -> finish(tx,
                                       loss,
                                       job,
                                       unit,
                                       outcome,
                                       hostLost(loss.now())))
                  .flatMap(settled -> settleJobState(tx, job).map(_ -> settled));
    }

    /// The lost attempt is a `unit_attempts` row and a `unit-lost` event (m4-design §2.4 "loss visibility").
    private static Promise<Unit> announceLoss(Transaction tx, JobLock job, UnitLock unit) {
        return EventWriter.append(tx,
                                  PendingEvent.pendingEvent(job.id(),
                                                            EventRow.Kind.UNIT_LOST,
                                                            job.unitPayload(unit.ref().unit(),
                                                                            none())))
                          .mapToUnit();
    }

    private static Promise<Unit> settleJobState(Transaction tx, JobLock job) {
        return Sql.execute(tx,
                           UNKNOWN_JOB_SETTLED,
                           job.id().value());
    }

    /// L4 of the listed attempt: the event and the job state rule follow only a unit the requeue moved
    /// (`changed == 1`); an unmoved unit gets no attempt row (written by the same statement), no event, and is not
    /// reported.
    private static Promise<Option<LostSettled>> requeue(Transaction tx,
                                                        Loss loss,
                                                        JobLock job,
                                                        UnitLock unit,
                                                        ActiveUnit listed) {
        return Sql.update(tx,
                          REQUEUE_LOST,
                          unit.id(),
                          loss.host().value(),
                          listed.attempt(),
                          Sql.utc(loss.now()))
                  .flatMap(changed -> requeued(tx, job, unit, changed));
    }

    private static Promise<Option<LostSettled>> requeued(Transaction tx, JobLock job, UnitLock unit, int changed) {
        return changed == 1
               ? announceLoss(tx, job, unit).flatMap(_ -> settleJobState(tx, job))
                             .map(_ -> some(LostSettled.lostSettled(unit.ref(),
                                                                    unit.attempt(),
                                                                    LostSettle.Outcome.REQUEUED,
                                                                    none())))
               : Promise.success(none());
    }

    private static Promise<LostSettled> finish(Transaction tx,
                                               Loss loss,
                                               JobLock job,
                                               UnitLock unit,
                                               LostSettle.Outcome outcome,
                                               UnitFinishRow row) {
        return PgUnitStore.finishUnit(tx,
                                      new PgUnitStore.Completion(loss.host(),
                                                                 unit.ref(),
                                                                 unit.attempt(),
                                                                 row,
                                                                 List.of(),
                                                                 none(),
                                                                 none(),
                                                                 loss.decideJob(),
                                                                 loss.judge()),
                                      job,
                                      unit)
                          .map(completed -> LostSettled.lostSettled(unit.ref(),
                                                                    unit.attempt(),
                                                                    outcome,
                                                                    finishedJob(completed)));
    }

    private static Option<JobState> finishedJob(CompleteOutcome completed) {
        return switch (completed) {
            case CompleteOutcome.Completed done -> done.job();
            case CompleteOutcome.AlreadyComplete _, CompleteOutcome.NotActive _, CompleteOutcome.Superseded _ -> none();
        };
    }

    /// L1: as a cancel of a unit that never reports (`killedBy = cancel`), so the unit judges CANCELLED.
    private static UnitFinishRow cancelled(Instant now) {
        return UnitFinishRow.unitFinishRow(none(), some(KillCause.CANCEL), now, none(), 0, none(), none());
    }

    /// L2, L3: `ERROR(host-lost)`.
    private static UnitFinishRow hostLost(Instant now) {
        return UnitFinishRow.unitFinishRow(none(), none(), now, none(), 0, none(), some(LostSettle.HOST_LOST));
    }

    record unused() implements LostSettlement {}
}
