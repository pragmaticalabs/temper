package org.pragmatica.temper.controller.host;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.flow.Collect;
import org.pragmatica.temper.controller.policy.DbUnit;
import org.pragmatica.temper.controller.policy.DiskUnit;
import org.pragmatica.temper.controller.policy.ReconcileAction;
import org.pragmatica.temper.controller.policy.Reconciler;
import org.pragmatica.temper.controller.policy.StoreAttempt;
import org.pragmatica.temper.controller.result.JobDecision;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.HolderStatusDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.StoredAttempt;
import org.pragmatica.temper.store.api.UnitFinishRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// Runs reconcile after a handshake (m0-design §6.4): the store's active units on this host against the worker's
/// disk scan in `Hello`, decided by the pure [Reconciler], then each action executed on the new session.
///
/// Loading the store's units and every action must succeed, or end in a settled unit, for the link to go UP (see
/// [#execute]); otherwise the session is closed and the reconnect decides again. `MarkLost` and `CompleteCancelled`
/// are single idempotent store transactions, so repeating them is safe.
public record ReconcileRun(HostName host, LinkEnv env, WorkerEvents events) {
    private static final Logger log = LoggerFactory.getLogger(ReconcileRun.class);

    public static ReconcileRun reconcileRun(HostName host, LinkEnv env, WorkerEvents events) {
        return new ReconcileRun(host, env, events);
    }

    /// The store's active units are this link's current attempts ([WorkerEvents#reconciled]) before any action runs.
    /// Rule 0's input, the store's current attempt of every scanned unit, is read after them.
    public Promise<List<ReconcileAction>> run(Connection connection, Message.Hello hello) {
        var attempts = diskAttempts(hello);
        var disk = disk(hello);

        return env.units()
                  .active(host)
                  .withSuccess(events::reconciled)
                  .flatMap(active -> decided(active, disk))
                  .flatMap(decision -> executeAll(Snapshot.snapshot(connection,
                                                                    decision.active(),
                                                                    attempts),
                                                  decision.actions()));
    }

    /// What one reconcile decided, with the store's active units it decided from (by unit).
    record Decision(Map<UnitRef, DbUnit> active, List<ReconcileAction> actions) {}

    /// One reconcile's execution context: the session its requests go over, the store's active units it decided from
    /// (by unit; they settle a `stale-attempt` answer, [#staleAttempt]) and the attempt of each scanned directory.
    record Snapshot(Connection connection, Map<UnitRef, DbUnit> active, Map<UnitRef, Integer> attempts) {
        static Snapshot snapshot(Connection connection, Map<UnitRef, DbUnit> active, Map<UnitRef, Integer> attempts) {
            return new Snapshot(connection, active, attempts);
        }
    }

    private Promise<Decision> decided(List<ActiveUnit> active, List<DiskUnit> disk) {
        var db = active.stream().map(DbUnit::dbUnit).toList();

        return env.units()
                  .attempts(disk.stream().map(DiskUnit::ref).toList())
                  .withSuccess(stored -> warnAhead(disk, stored))
                  .map(stored -> new Decision(byRef(db),
                                              Reconciler.decide(env.instance(),
                                                                db,
                                                                disk,
                                                                storeAttempts(stored))));
    }

    /// A disk attempt above the store's means the store lost a grant (a restored database): rule 0 discards it, and it
    /// is logged WARN with both attempts, as no regular path leaves one. A unit of another controller instance is
    /// not this reconcile's (F2) and is left out, as rule 0 leaves it.
    private Unit warnAhead(List<DiskUnit> disk, List<StoredAttempt> stored) {
        stored.forEach(current -> disk.stream()
                                      .filter(unit -> !unit.belongsToAnother(env.instance()))
                                      .filter(unit -> unit.ref()
                                                          .equals(current.ref()))
                                      .filter(unit -> unit.attempt() > current.attempt())
                                      .forEach(unit -> log.warn("[{}] {} holds attempt {} above the store's {}: to be discarded",
                                                                host,
                                                                unit.ref().containerName(),
                                                                unit.attempt(),
                                                                current.attempt())));

        return unit();
    }

    private static Map<UnitRef, DbUnit> byRef(List<DbUnit> db) {
        return db.stream()
                 .collect(Collectors.toUnmodifiableMap(DbUnit::ref,
                                                       Function.identity(),
                                                       (first, _) -> first));
    }

    /// The store's current attempt of each scanned unit, as seen from this host: `here` when the store has it on this
    /// host (a requeued unit has no host, m4-design §4.5).
    private List<StoreAttempt> storeAttempts(List<StoredAttempt> stored) {
        return stored.stream()
                     .map(attempt -> StoreAttempt.storeAttempt(attempt.ref(),
                                                               attempt.attempt(),
                                                               attempt.host().filter(host::equals).isPresent()))
                     .toList();
    }

    /// The attempt of each unit directory the worker scanned (protocol v3, m4-design §7): a directory written before
    /// M4 reports none and is attempt 1. A request that removes what is on disk (`KillOrphan`; `Discard` carries its own)
    /// names this attempt; one whose answer can complete the unit names the store's ([#requestAttempt]).
    static Map<UnitRef, Integer> diskAttempts(Message.Hello hello) {
        return hello.units()
                    .stream()
                    .flatMap(dto -> diskAttempt(dto).stream())
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                          Map.Entry::getValue,
                                                          (first, _) -> first));
    }

    private static Option<Map.Entry<UnitRef, Integer>> diskAttempt(DiskUnitDto dto) {
        return diskRef(dto).map(ref -> Map.entry(ref,
                                                 dto.attempt().or(PRE_M4_ATTEMPT)))
                      .option();
    }

    /// The attempt of a unit directory without `.temper/attempt` (m4-design §7).
    static final int PRE_M4_ATTEMPT = 1;

    /// Rule 0's discards first, then the other actions (m4-design §4.5): a superseded attempt is gone before anything
    /// about the current one runs, and a failed discard fails the reconcile before it. Within each phase, fork-join:
    /// every action is independent (one unit each), and each settles its own failure. `Collect.all`: the failure is
    /// only logged before reconnecting (`HostLink`), naming every action that failed.
    private Promise<List<ReconcileAction>> executeAll(Snapshot run, List<ReconcileAction> actions) {
        var discards = actions.stream().filter(ReconcileAction.Discard.class::isInstance).toList();
        var others = actions.stream().filter(action -> !(action instanceof ReconcileAction.Discard)).toList();

        return executed(run, discards).flatMap(_ -> executed(run, others))
                       .map(_ -> actions);
    }

    private Promise<List<Unit>> executed(Snapshot run, List<ReconcileAction> actions) {
        return Collect.all(actions.stream().map(action -> execute(run, action)).toList());
    }

    /// The attempt a reconcile request names (m4-design §4.2, §4.5). `Collect` and `Reattach` can lead to a result
    /// being accepted, so they name the store's current attempt of the unit (this reconcile's snapshot,
    /// [WorkerEvents#reconciled]): a host still holding an older attempt answers `stale-attempt`, and that attempt's
    /// result is never taken for the current one's. `KillOrphan` and `Cancel` act on what the worker reported, so they
    /// name the disk attempt; `Discard` names the attempt rule 0 found superseded. A unit with no attempt in the
    /// snapshot falls back to its disk attempt.
    private int requestAttempt(ReconcileAction action, Map<UnitRef, Integer> attempts) {
        return switch (action) {
            case ReconcileAction.Collect collect -> storeAttempt(collect.ref(), attempts);
            case ReconcileAction.Reattach reattach -> storeAttempt(reattach.ref(), attempts);
            case ReconcileAction.Discard discard -> discard.attempt();
            default -> diskAttempt(action.ref(), attempts);
        };
    }

    private int storeAttempt(UnitRef ref, Map<UnitRef, Integer> attempts) {
        return events.attemptOf(ref)
                     .or(() -> diskAttempt(ref, attempts));
    }

    /// A unit reconcile acts on is on disk (rules 1-6 act on scanned units); one that is not gets attempt 1.
    private static int diskAttempt(UnitRef ref, Map<UnitRef, Integer> attempts) {
        return attempts.getOrDefault(ref, PRE_M4_ATTEMPT);
    }

    private List<DiskUnit> disk(Message.Hello hello) {
        return hello.units()
                    .stream()
                    .flatMap(dto -> diskUnit(dto).onFailure(cause -> log.warn("[{}] unreadable disk unit {}/{}: {}",
                                                                              host,
                                                                              dto.job(),
                                                                              dto.unit(),
                                                                              cause.message()))
                                            .option()
                                            .stream())
                    .toList();
    }

    /// Wire input from the worker: the job id and unit name must parse. Read here, at the link, so the reconcile
    /// policy sees only domain types.
    static Result<DiskUnit> diskUnit(DiskUnitDto dto) {
        return diskRef(dto).map(ref -> diskUnit(ref, dto));
    }

    private static Result<UnitRef> diskRef(DiskUnitDto dto) {
        return Result.all(JobId.jobId(dto.job()),
                          UnitName.unitName(dto.unit()))
                     .map(UnitRef::unitRef);
    }

    private static DiskUnit diskUnit(UnitRef ref, DiskUnitDto dto) {
        return DiskUnit.diskUnit(ref,
                                 dto.attempt().or(PRE_M4_ATTEMPT),
                                 dto.hasMarker(),
                                 dto.rc(),
                                 containerState(option(dto.container()).or(ContainerStatus.ABSENT)),
                                 dto.holder().map(HolderStatusDto::toDomain),
                                 dto.ticket(),
                                 dto.instance());
    }

    private static ContainerState containerState(ContainerStatus status) {
        return switch (status) {
            case RUNNING -> ContainerState.RUNNING;
            case EXITED -> ContainerState.EXITED;
            case ABSENT -> ContainerState.ABSENT;
        };
    }

    /// How a failed action is handled:
    /// - an orphan kill: logged and dropped (FER: the orphan is in no store state and nothing waits for it);
    /// - a discard: the reconcile fails (m4-design §4.5): the host gets no grant while a superseded attempt may remain;
    /// - a `stale-attempt` answer to `Collect` or `Reattach`: the host holds another attempt than the store's current
    ///   one, so the current one is not on its disk ([#staleAttempt]);
    /// - any other worker `Failure` answer (it cannot collect, re-attach or kill the unit): a deterministic answer that
    ///   would repeat on every reconnect, so the unit is settled as `ERROR(infra)` and the reconcile goes on [G];
    /// - anything else (the link, the store): transient, so the reconcile fails, the session is closed and the
    ///   reconnect (with growing backoff) decides again.
    Promise<Unit> execute(Snapshot run, ReconcileAction action) {
        return perform(run.connection(),
                       action,
                       requestAttempt(action,
                                      run.attempts())).onSuccess(_ -> log.info("[{}] reconcile {}", host, action))
                      .onFailure(cause -> log.warn("[{}] reconcile {} failed: {}",
                                                   host,
                                                   action,
                                                   cause.message()))
                      .fold(result -> result.fold(cause -> afterFailure(run, action, cause),
                                                  Promise::success));
    }

    private Promise<Unit> afterFailure(Snapshot run, ReconcileAction action, Cause cause) {
        return switch (action) {
            case ReconcileAction.KillOrphan _ -> Promise.unitPromise();
            case ReconcileAction.Discard _ -> failed(action, cause);
            default -> settledOrFailed(run, action, cause);
        };
    }

    private Promise<Unit> settledOrFailed(Snapshot run, ReconcileAction action, Cause cause) {
        return switch (cause) {
            case LinkError.WorkerFailure failure when STALE_ATTEMPT.equals(failure.code()) -> staleAttempt(run,
                                                                                                           action,
                                                                                                           failure);
            case LinkError.WorkerFailure failure -> errored(action, failure);
            default -> failed(action, cause);
        };
    }

    /// The worker's code for a request naming another attempt than the one it holds (m4-design §4.2).
    static final String STALE_ATTEMPT = "stale-attempt";

    /// Entry criterion (c), m4-design §4.5. Rule 0 already discarded every attempt the scan showed superseded, so this
    /// answer means the host's disk changed after its `Hello` (a start of the old session still being served). The
    /// store's current attempt is not on this host's disk, and it is settled as rule 6 settles a unit with no
    /// directory ([Reconciler#notOnDisk]): GRANTED and never started → `Requeue` (nothing of it ran: its start would
    /// have written its attempt before its marker), CANCELLING and never started → CANCELLED, started → `ERROR(lost)`.
    /// Never `ERROR(infra)`: no infrastructure failed.
    /// - `Collect`: the attempt the host holds has exited; it can store nothing (`Superseded`), and the next reconcile
    ///   discards it. The reconcile goes on.
    /// - `Reattach`: the attempt the host holds still runs and no timer bounds it. After the settle the reconcile fails,
    ///   so the reconnect's fresh scan discards it (rule 0) before the host goes UP.
    /// - `Cancel` (only ever beside a `Reattach` of the same CANCELLING unit): nothing; the `Reattach` settles the unit
    ///   and forces the reconnect.
    /// Any other action answered `stale-attempt` (`StartUnknown` sends no request, `KillOrphan` and `Discard` are
    /// handled before) is settled as any worker failure.
    private Promise<Unit> staleAttempt(Snapshot run, ReconcileAction action, LinkError.WorkerFailure failure) {
        return switch (action) {
            case ReconcileAction.Collect collect -> notOnDisk(run, collect.ref());
            case ReconcileAction.Reattach reattach -> reconnectAfter(notOnDisk(run, reattach.ref()), reattach.ref());
            case ReconcileAction.Cancel _ -> Promise.unitPromise();
            default -> errored(action, failure);
        };
    }

    private static Promise<Unit> reconnectAfter(Promise<Unit> settled, UnitRef ref) {
        return settled.flatMap(_ -> ReconcileError.StaleAttempt.FACTORY.apply(ref).promise());
    }

    private Promise<Unit> notOnDisk(Snapshot run, UnitRef ref) {
        return option(run.active().get(ref)).map(this::settledNotOnDisk)
                     .or(Promise::unitPromise);
    }

    private Promise<Unit> settledNotOnDisk(DbUnit unit) {
        return Collect.all(Reconciler.notOnDisk(unit).stream().map(this::staleSettled).toList()).mapToUnit();
    }

    private Promise<Unit> staleSettled(ReconcileAction settle) {
        return settled(settle).onSuccess(_ -> log.info("[{}] reconcile {}: stale-attempt answer, settled", host, settle));
    }

    /// The reconcile actions that only write the store, in the unit's snapshot attempt; any other action is none of
    /// them and settles nothing.
    private Promise<Unit> settled(ReconcileAction action) {
        return switch (action) {
            case ReconcileAction.MarkLost lost -> finish(lost.ref(),
                                                         UnitFinishes.lost(Instant.now()));
            case ReconcileAction.Requeue requeue -> requeued(requeue.ref());
            case ReconcileAction.CompleteCancelled cancelled -> finish(cancelled.ref(),
                                                                       UnitFinishes.cancelled(Instant.now()));
            default -> Promise.unitPromise();
        };
    }

    private Promise<Unit> errored(ReconcileAction action, LinkError.WorkerFailure failure) {
        return finish(action.ref(),
                      UnitFinishes.errored(UnitFinishes.workerFailure(failure.code(), failure.detail()),
                                           Instant.now()));
    }

    private static Promise<Unit> failed(ReconcileAction action, Cause cause) {
        return ReconcileError.ActionFailed.FACTORY.apply(action.ref(),
                                                         cause)
                                                  .promise();
    }

    private Promise<Unit> perform(Connection connection, ReconcileAction action, int attempt) {
        return switch (action) {
            case ReconcileAction.Collect collect -> acked(connection,
                                                          new Message.Collect(job(collect.ref()),
                                                                              unitName(collect.ref()),
                                                                              attempt));
            case ReconcileAction.Reattach reattach -> acked(connection,
                                                            new Message.Reattach(job(reattach.ref()),
                                                                                 unitName(reattach.ref()),
                                                                                 attempt,
                                                                                 reattach.timeout()));
            case ReconcileAction.StartUnknown unknown -> startUnknown(unknown.ref());
            case ReconcileAction.MarkLost _, ReconcileAction.Requeue _, ReconcileAction.CompleteCancelled _ -> settled(action);
            case ReconcileAction.KillOrphan orphan -> cancelUnit(connection, orphan.ref(), attempt);
            case ReconcileAction.Cancel cancel -> cancelUnit(connection, cancel.ref(), attempt);
            case ReconcileAction.Discard discard -> acked(connection,
                                                          new Message.DiscardUnit(job(discard.ref()),
                                                                                  unitName(discard.ref()),
                                                                                  attempt));
        };
    }

    /// Scoped to the unit's current attempt, read by this reconcile ([WorkerEvents#reconciled]); a unit whose attempt
    /// is no longer known has ended since, and has nothing to mark. The unit is known unrecorded first
    /// ([WorkerEvents#reconciledRunning]), so a refused mark still shows it UNKNOWN, never QUEUED.
    private Promise<Unit> startUnknown(UnitRef ref) {
        return events.attemptOf(ref)
                     .map(attempt -> markedUnknown(ref, attempt))
                     .or(Promise::unitPromise);
    }

    private Promise<Unit> markedUnknown(UnitRef ref, int attempt) {
        events.reconciledRunning(ref, attempt);

        return env.units()
                  .startUnknown(host, ref, attempt);
    }

    /// In the attempt this reconcile's snapshot holds ([WorkerEvents#reconciled]): a unit granted again since (here or
    /// elsewhere) is left alone by the store (m4-design §4.3).
    private Promise<Unit> requeued(UnitRef ref) {
        return events.attemptOf(ref)
                     .map(attempt -> env.units()
                                        .requeue(host, ref, attempt))
                     .or(Promise::unitPromise);
    }

    private Promise<Unit> cancelUnit(Connection connection, UnitRef ref, int attempt) {
        return acked(connection, new Message.CancelUnit(job(ref), unitName(ref), attempt));
    }

    /// The request must be answered with `Ack`.
    private Promise<Unit> acked(Connection connection, Message request) {
        return connection.request(request,
                                  env.timing().request())
                         .flatMap(reply -> Connection.expect(reply, Message.Ack.class).async())
                         .mapToUnit();
    }

    /// In the attempt this reconcile's snapshot of the store's active rows holds ([WorkerEvents#reconciled], m4-design
    /// §4.3); every active row has a grant time, so every one has its attempt there. A unit whose attempt is no longer
    /// known has ended since (its entry is forgotten only after the ending write committed): nothing is left to
    /// finish. A unit requeued or granted elsewhere since the snapshot is `Superseded` by the completing transaction,
    /// nothing written.
    private Promise<Unit> finish(UnitRef ref, UnitFinishRow row) {
        return events.attemptOf(ref)
                     .map(attempt -> finished(ref, attempt, row))
                     .or(Promise::unitPromise);
    }

    private Promise<Unit> finished(UnitRef ref, int attempt, UnitFinishRow row) {
        return env.units()
                  .complete(host,
                            ref,
                            attempt,
                            row,
                            List.of(),
                            none(),
                            none(),
                            JobDecision::decide)
                  .withSuccess(env.evidence()::finished)
                  .withSuccess(_ -> events.ended(ref, attempt))
                  .map(_ -> env.wake()
                               .signal());
    }

    private static long job(UnitRef ref) {
        return ref.job()
                  .value();
    }

    private static String unitName(UnitRef ref) {
        return ref.unit()
                  .value();
    }
}
