package org.pragmatica.temper.domain.task;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.Sha;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The part of a task that [TaskMachine] changes (m3-design §5.2, §5.3): the projected columns of `tasks`, whose every
/// change is a `task_events` row carrying it (the projection rule, `TaskProjectionIT`). The task's static facts (slug,
/// brief, branch, `after`, …) are the store's row around it.
///
/// - `raisedFrom` is the stage a NEEDS_RULING task returns to (T11); none otherwise.
/// - `readySince` is when the task last became READY (the dispatcher's fourth key, §8); none while not READY.
/// - `waiting` is what a WAITING task waits for; empty otherwise.
/// - `closedAt` is set once by T15/T16.
public record TaskState(Stage stage,
                        Status status,
                        Option<Stage> raisedFrom,
                        Counters counters,
                        LeaseState lease,
                        Option<Sha> head,
                        Option<Integer> pr,
                        Option<Instant> readySince,
                        Waiting waiting,
                        Audit audit,
                        Option<Instant> closedAt) {
    public static TaskState taskState(Stage stage,
                                      Status status,
                                      Option<Stage> raisedFrom,
                                      Counters counters,
                                      LeaseState lease,
                                      Option<Sha> head,
                                      Option<Integer> pr,
                                      Option<Instant> readySince,
                                      Waiting waiting,
                                      Audit audit,
                                      Option<Instant> closedAt) {
        return new TaskState(stage, status, raisedFrom, counters, lease, head, pr, readySince, waiting, audit, closedAt);
    }

    /// A created task (§5.4): TRIAGED/READY, never leased, its PR if one was named.
    public static TaskState created(int roundCap, Option<Integer> pr, Instant now) {
        return new TaskState(Stage.TRIAGED,
                             Status.READY,
                             none(),
                             Counters.initial(roundCap),
                             LeaseState.NONE,
                             none(),
                             pr,
                             some(now),
                             Waiting.NOTHING,
                             Audit.NONE,
                             none());
    }

    public long epoch() {
        return lease.epoch();
    }

    /// Enters `next` as a new visit (T1, T4, T9, T11, T12, T13, T14) in its entry status; a CI entry waits on CI. The
    /// lease ends without a revocation: a leased task moved on by its own evidence keeps its epoch.
    TaskState entered(Stage next, Instant now) {
        return placed(next,
                      next.entryStatus(),
                      none(),
                      counters.nextVisit(),
                      lease.ended(),
                      now,
                      next == Stage.CI
                      ? Waiting.CI_ONLY
                      : Waiting.NOTHING);
    }

    /// READY again in the same stage and visit (T7, a first T8 expiry), after a revocation.
    TaskState requeued(Counters counted, Instant now) {
        return placed(stage, Status.READY, raisedFrom, counted, lease.revoked(), now, Waiting.NOTHING);
    }

    /// WAITING in the same visit (T5), the lease revoked.
    TaskState waitingOn(Waiting awaited, Instant now) {
        return placed(stage, Status.WAITING, raisedFrom, counters, lease.revoked(), now, awaited);
    }

    /// READY in the same visit once what it waited for is settled (T6).
    TaskState resumed(Instant now) {
        return placed(stage, Status.READY, raisedFrom, counters, lease, now, Waiting.NOTHING);
    }

    /// LEASED to a worker at the next epoch (T2).
    TaskState leased(LeaseState granted) {
        return new TaskState(stage,
                             Status.LEASED,
                             raisedFrom,
                             counters,
                             granted,
                             head,
                             pr,
                             none(),
                             waiting,
                             audit,
                             closedAt);
    }

    /// The same lease with its TTL clock restarted (T3).
    TaskState extended(LeaseState extended) {
        return new TaskState(stage,
                             status,
                             raisedFrom,
                             counters,
                             extended,
                             head,
                             pr,
                             readySince,
                             waiting,
                             audit,
                             closedAt);
    }

    /// NEEDS_RULING/HELD (T8 second expiry, T9′, T10, T12 park), raised from the current stage; a live lease is
    /// revoked.
    TaskState parked(Counters counted, Instant now) {
        return placed(Stage.NEEDS_RULING, Status.HELD, some(stage), counted, revokedIfLeased(), now, Waiting.NOTHING);
    }

    /// A terminal stage (T15, T16); a live lease is revoked.
    TaskState closed(Stage terminal, Instant now) {
        return new TaskState(terminal,
                             Status.CLOSED,
                             none(),
                             counters,
                             revokedIfLeased(),
                             head,
                             pr,
                             none(),
                             Waiting.NOTHING,
                             audit,
                             some(now));
    }

    /// The head and PR an accepted evidence document named (WP-2 derives them); absent ones keep the known values.
    TaskState withWork(Option<Sha> newHead, Option<Integer> newPr) {
        return new TaskState(stage,
                             status,
                             raisedFrom,
                             counters,
                             lease,
                             newHead.orElse(head),
                             newPr.orElse(pr),
                             readySince,
                             waiting,
                             audit,
                             closedAt);
    }

    TaskState withCounters(Counters counted) {
        return new TaskState(stage, status, raisedFrom, counted, lease, head, pr, readySince, waiting, audit, closedAt);
    }

    private LeaseState revokedIfLeased() {
        return status == Status.LEASED
               ? lease.revoked()
               : lease;
    }

    /// `readySince` is the moment of becoming READY, none in any other status.
    private TaskState placed(Stage next,
                             Status nextStatus,
                             Option<Stage> nextRaisedFrom,
                             Counters counted,
                             LeaseState nextLease,
                             Instant now,
                             Waiting awaited) {
        return new TaskState(next,
                             nextStatus,
                             nextRaisedFrom,
                             counted,
                             nextLease,
                             head,
                             pr,
                             nextStatus == Status.READY
                             ? some(now)
                             : none(),
                             awaited,
                             audit,
                             closedAt);
    }
}
