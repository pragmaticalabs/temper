package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.task.Command;
import org.pragmatica.temper.domain.task.EvidenceOutcome;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.NewTask;
import org.pragmatica.temper.domain.task.Stage;
import org.pragmatica.temper.domain.task.TaskId;


/// The task store (m3-design §5, §6). Every command is one transaction: the row locked `FOR UPDATE`, `TaskMachine`
/// applied, the row updated under its fence and the events appended with the state's after-image, so the row is always
/// the fold of its events. A refused command changes nothing and fails with the machine's `TaskError`, or with
/// [StoreError.TaskNotFound] for an unknown id. Times come from the caller's clock (`now`), events included.
public interface TaskStore {
    /// TRIAGED/READY with its `created` and `ready(created)` events (§5.4). Every id in `after` must exist, else
    /// [StoreError.AfterUnknown].
    Promise<TaskRow> create(NewTask task, Instant now);
    Promise<Option<TaskRow>> task(TaskId id);
    /// Newest first, as [TaskQuery] filters.
    Promise<List<TaskRow>> list(TaskQuery query);
    /// Every event of the task, oldest first; empty for an unknown id.
    Promise<List<TaskEventRow>> events(TaskId id);
    /// Items, rulings, evidence ids and linked jobs; empty parts for an unknown id.
    Promise<TaskDetail> detail(TaskId id);
    /// READY tasks of the worker stages (or of `stage`) in dispatcher order (§8), at most `limit`.
    Promise<List<TaskRow>> ready(Option<Stage> stage, int limit);
    /// T2 under `SELECT … FOR UPDATE SKIP LOCKED` and an UPDATE conditional on READY: a task another claim holds
    /// locked, or one no longer READY, is `TaskError.NOT_READY`, so of any number of concurrent claims at most one wins.
    /// The read point is the task's head, else the grant's base tip (§6.5). The answer carries the claimed row and its
    /// detail, read in the claim's own transaction, so nothing after the commit can lose the token's answer.
    Promise<ClaimedTask> claim(TaskId id, ClaimGrant grant, Instant now);
    /// Whether `lease` holds now, a read (`LEASE_LOST` when it does not): a caller's check before work of its own; the
    /// writes under the lease stay fenced by their own conditional UPDATE.
    Promise<Unit> holds(Lease lease);
    /// A lease-token command (T3, T5, T7, T10 by the holder): the UPDATE is conditional on `(id, LEASED, epoch, hash)`,
    /// and no row updated is `TaskError.LEASE_LOST` (§6.4). A [Command.Wait]'s jobs must be linked to the task, else
    /// [StoreError.JobNotLinked]; a park stores its ruling request.
    Promise<TaskRow> leased(Lease lease, Command command, Instant now);
    /// T4, T9, T9′, T13: stores `evidence` as accepted at the lease's epoch and applies its already-validated outcome,
    /// fenced as [#leased] (the seam WP-2's validation calls).
    Promise<TaskRow> evidence(Lease lease, NewEvidence evidence, EvidenceOutcome outcome, Instant now);
    /// A command of the maintainer's (T10, T11, T14, T15, T16) by `actor`. A [Command.Rule] needs the open ruling to offer its
    /// option ([StoreError.OptionNotOffered]) and writes the ruling once; a park stores its request.
    Promise<TaskRow> command(TaskId id, Command command, String actor, Instant now);
    /// One sweep at `now` (§6.2, §6.6, T1): every LEASED task whose step deadline passed, or whose TTL passed with no
    /// non-terminal job linked at its epoch, gets T8; every WAITING task whose awaited jobs are all terminal (a CI wait
    /// excepted) gets T6; every TRIAGED task whose `after` tasks have reached VERIFY gets T1. Each task is its own
    /// transaction; one that is locked is skipped.
    Promise<SweepOutcome> sweep(Instant now);
    /// At a controller start (m3-design §6.2 [G]): every LEASED task whose TTL lapsed before `start`, whose step deadline
    /// has not passed and that was not extended at its current epoch yet, gets `start` + its stage's TTL, its step
    /// deadline unchanged; live leases are untouched. Answers the tasks extended. Each task is its own transaction.
    Promise<List<TaskId>> restarted(Instant start);
}
