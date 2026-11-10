package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.LostSettle;
import org.pragmatica.temper.domain.verdict.VerdictInput;


/// Units: grant, start, lease, completion (spec §6, m0-design §5.3).
public interface UnitStore {
    /// The most QUEUED units one [#grant] reads (its candidates); a unit beyond them waits for a later grant.
    int CANDIDATE_LIMIT = 200;

    /// One transaction: `SELECT … FOR UPDATE OF u SKIP LOCKED` over the QUEUED units (priority desc, submit time
    /// asc, id asc, at most [#CANDIDATE_LIMIT]), then up to `maxGrants` rounds of `policy` + the conditional
    /// `UPDATE … WHERE state = 'QUEUED'`. Each round passes the units still queued; a unit granted in an earlier
    /// round is no longer in the list, so the policy must account for it. A decision naming a unit that is not
    /// in the list ends the rounds. The §5.3 signature returns only the grants, so the policy closure owns its own
    /// state across rounds and ticks (the starvation state in [GrantDecision#next] and the host snapshot it updates
    /// per grant); the store reads neither. Every grant is a new attempt (m4-design §4.1): it increments
    /// `units.attempt` ([Granted#attempt]) and snapshots the host's `shared_cpu` on the unit.
    /// The transaction's COMMIT is fenced (#111, fails closed): with `fence` present and the database clock at COMMIT at
    /// or past it, the COMMIT fails and nothing of the transaction lands; every unit stays QUEUED with its attempt
    /// unchanged, and the promise fails. The fence is judged when the transaction commits, not when it was decided or
    /// began, so a call that outlives its caller's timeout cannot land late. `Option.none()` is the deliberate
    /// "unfenced" choice (a host without `retireAt`); it reaches the database as the sentinel `none`. There is no
    /// overload without a fence: the V18 trigger refuses any QUEUED to GRANTED commit that did not set one, so a caller
    /// must choose.
    Promise<List<Granted>> grant(HostName host,
                                 Fn1<GrantDecision, List<QueuedUnit>> policy,
                                 TimeSpan lease,
                                 int maxGrants,
                                 Option<Instant> fence);

    /// A hold unit waits in bb-hold (`UnitHolding`, m1-design §1.3): a GRANTED or CANCELLING unit that has not
    /// started, in `attempt` on `host`, gets `port_group = 'waiting'`, `hold_since = now()`, its marker, and
    /// `unit_facts` phase `hold` of that attempt. It stays GRANTED (no new state [P]). Idempotent: a repeat, a unit
    /// already waiting or started, a terminal unit, or one on another host or in another attempt (m4-design §4.3) is a
    /// no-op. An unknown unit fails with [StoreError.UnitNotFound].
    Promise<Unit> holding(HostName host, UnitRef ref, int attempt, HoldingRow row);
    /// GRANTED or UNKNOWN → RUNNING (a CANCELLING unit records its start but stays CANCELLING); job QUEUED or UNKNOWN →
    /// RUNNING with the event `started`; `unit_facts` phase `start`. A unit that was waiting for its hold, or whose row
    /// carries a ticket, becomes `port_group = 'held'` with `bbhold_ticket` and `hold_since = startedAt`. Idempotent: a
    /// repeat, or a unit already terminal, is a no-op, and so is a start from a host the unit is not on (`host`, ruling
    /// R-13: a host that no longer owns the unit never records its start) or of an attempt that is not the unit's
    /// current one (`attempt`, m4-design §4.3).
    Promise<Unit> started(HostName host, UnitRef ref, int attempt, UnitStartedRow row);
    /// A unit runs but its start could not be recorded (ruling R-13, amended): a GRANTED unit on `host` that has not
    /// started, in `attempt` ([Granted#attempt], m4-design §4.3), → UNKNOWN (a unit that waited for its hold
    /// → `port_group = 'held'`, `hold_since` unknown); job QUEUED → UNKNOWN; event `start-unknown`. A later [#started]
    /// still records the start (UNKNOWN → RUNNING). Idempotent: a repeat, a unit that started, is CANCELLING, terminal,
    /// on another host or in another attempt (requeued, then granted again: a late mark never lands on the new attempt,
    /// verification F4) is a no-op. An unknown unit fails with [StoreError.UnitNotFound].
    Promise<Unit> startUnknown(HostName host, UnitRef ref, int attempt);
    /// `lease_until = now() + lease` for the listed attempts that are active on `host` and current (m4-design §4.3).
    Promise<Unit> refreshLeases(HostName host, List<AttemptRef> running, TimeSpan lease);
    /// GRANTED, UNKNOWN, RUNNING and CANCELLING units on `host`.
    Promise<List<ActiveUnit>> active(HostName host);
    /// The current attempt of each listed unit (m4-design §4.5), in unit id order; a unit the store does not know is
    /// not listed.
    Promise<List<StoredAttempt>> attempts(List<UnitRef> refs);

    /// One transaction. A unit already terminal answers [CompleteOutcome.AlreadyComplete], a QUEUED unit (a stale
    /// report after a requeue) answers [CompleteOutcome.NotActive], an active unit that is not on `host` in `attempt`
    /// answers [CompleteOutcome.Superseded] (m4-design §4.3: the check runs under the unit's row lock, in the
    /// transaction that would write); none of them writes anything.
    /// Otherwise: insert the testcases; the unit's manifest (`manifests` + `manifest_units`, m1-design §2.5) and its
    /// small evidence row (`evidence`, §3.2) when present; set the unit's terminal state (its single-unit verdict),
    /// `unit_facts` phase `end`, event `unit-finished`. An `Unsupported` manifest sets the unit's error reason to
    /// `manifest: <reason>` unless `finish` already carries one, so the unit is `ERROR(manifest: …)`, never a pass
    /// without coverage (Q-M1-7).
    /// When every unit of the job is then terminal, `decideJob` receives the job's [VerdictInput] (`cancelled` when a
    /// cancel was requested, `superseded` from the job, `manifest` when every unit with a manifest spec delivered a
    /// computed manifest), unless the job is already terminal; a `Some` writes the result row (its `evidence` and
    /// `manifest` blocks from the stored rows), sets the job's terminal state and appends `finished` (or `cancelled`
    /// for a CANCELLED verdict).
    Promise<CompleteOutcome> complete(HostName host,
                                      UnitRef ref,
                                      int attempt,
                                      UnitFinishRow finish,
                                      List<TestcaseRow> rows,
                                      Option<UnitManifestRows> manifest,
                                      Option<StoredEvidence> evidence,
                                      Fn1<Option<JobFinish>, VerdictInput> decideJob);

    /// A host is LOST (m4-design §2.3, rulings R-M4-2, R-M4-5, R-M4-13): every unit active on `host` is settled by
    /// [LostSettle], one transaction per unit (job lock, then unit lock), in unit id order; a unit no longer active on
    /// `host` under its lock is skipped, so a repeat settles nothing twice and a grant that committed just before the
    /// loss is caught by the next call.
    /// - L1 CANCELLING → CANCELLED (`killedBy = cancel`), completed as by [#complete].
    /// - L2 `side_effects = external`, L3 the requeue bound reached → the lost attempt is a `unit_attempts` row and a
    ///   `unit-lost` event; the unit ends `ERROR(host-lost)`, completed as by [#complete].
    /// - L4 otherwise → the lost attempt is a `unit_attempts` row and a `unit-lost` event; the unit is QUEUED again with
    ///   its attempt columns cleared, its `attempt` kept (the next grant increments it) and `host_lost_requeues + 1`. A
    ///   RUNNING job stays RUNNING; an UNKNOWN job with no UNKNOWN unit left goes back to QUEUED.
    /// Guarantee (R-M4-3): at most one stored result per unit, while its steps may run twice: the lost attempt can store
    /// nothing after this (its host and attempt no longer match, [#complete]), and the column's CHECK
    /// (`host_lost_requeues BETWEEN 0 AND 1`) fails a second requeue's transaction. `now` is the lost attempt's end.
    /// A unit whose transaction fails fails the call and leaves the later units for the next call.
    Promise<List<LostSettled>> settleLost(HostName host, Instant now, Fn1<Option<JobFinish>, VerdictInput> decideJob);

    /// GRANTED and never started → QUEUED; the attempt's marker and hold columns are cleared (a hold that died
    /// before its grant, m1-design §1.5 rule 4, runs again from scratch). Any other state is left alone. A
    /// CANCELLING unit that never started is finished with [#complete] instead (it becomes CANCELLED). Scoped to one
    /// attempt (m4-design §4.3): only a unit granted to `host` in `attempt` is requeued, so a requeue decided from a
    /// reconcile's snapshot never lands on a later grant of the unit, here or on another host; such a unit is left
    /// alone, as any unit in another state.
    Promise<Unit> requeue(HostName host, UnitRef ref, int attempt);
}
