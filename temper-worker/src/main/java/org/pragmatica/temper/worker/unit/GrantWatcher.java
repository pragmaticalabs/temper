package org.pragmatica.temper.worker.unit;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.hold.GrantLine;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerTimings.HoldTimings;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.link.Outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// One per holding unit (m1-design §1.2): watches bb-hold from the enqueue to the container, every `grantPoll`.
///
/// Each tick reads the holder FIRST and `bbhold.err` SECOND: a holder seen dead has written every line it
/// ever will, so "dead, and no `granted` line" really means it exited before the grant. bb-hold's rc is
/// ambiguous once granted (it is then argv's or the container's), so the branch is on the `granted` line,
/// never on the rc alone (maintainer ruling M1-14).
///
/// | Observation | Action |
/// |---|---|
/// | `granted <t>` seen | `bbhold.ticket`, then `start` = now (the timeout counts from the grant, not the enqueue); resolve the container by name; once it exists: `container`, held for the `HoldMonitor`, a late cancel honoured, timer and exit watcher armed, unsolicited `UnitStarted` |
/// | granted, no container after `containerWait`, holder dead | `ERROR(port-group: docker run failed …)` with the rc and the `bbhold.err` tail |
/// | dead before the grant, rc 3 (a duplicate holder) | one more enqueue after `duplicateRetry`; a second rc 3 is `ERROR` |
/// | rc 2 | `ERROR(port-group: bb-hold refused (rc 2))` |
/// | rc 143 or 9 and the unit in the cancelled set | `CANCELLED` |
/// | rc 143 otherwise | `ERROR(port-group: bb-hold signalled)` |
/// | rc 9 otherwise | `ERROR(port-group: ticket left the queue (rc 9))` (cancelled in bb-queue, or reaped as abandoned) |
///
/// Every final dead-holder outcome above (not the duplicate retry, whose next holder must not race a reap) is
/// reported first, then [BbQueueReap] releases a ticket the holder may have left (a `kill -9`'d holder cleans up
/// nothing).
///
/// A watcher re-armed by `Reattach` has no rc (the holder is not this JVM's child): it classifies from the
/// `bbhold.err` lines bb-hold prints for each of those exits, and failing that reports
/// `ERROR(port-group: bb-hold exited before grant)`.
public record GrantWatcher(UnitFiles files,
                           DockerCli docker,
                           HolderProbe holders,
                           UnitRegistry registry,
                           UnitTimer timer,
                           UnitWatcher watcher,
                           FactsProbe facts,
                           Outbox outbox,
                           Timers timers,
                           InstantSource clock,
                           HoldTimings timings,
                           PortGroupLauncher launcher,
                           BbQueueReap reap) {
    private static final Logger LOG = LoggerFactory.getLogger(GrantWatcher.class);
    public static final int ERR_TAIL_CHARS = 300;

    public static GrantWatcher grantWatcher(UnitFiles files,
                                            DockerCli docker,
                                            HolderProbe holders,
                                            UnitRegistry registry,
                                            UnitTimer timer,
                                            UnitWatcher watcher,
                                            FactsProbe facts,
                                            Outbox outbox,
                                            Timers timers,
                                            InstantSource clock,
                                            HoldTimings timings,
                                            PortGroupLauncher launcher,
                                            BbQueueReap reap) {
        return new GrantWatcher(files,
                                docker,
                                holders,
                                registry,
                                timer,
                                watcher,
                                facts,
                                outbox,
                                timers,
                                clock,
                                timings,
                                launcher,
                                reap);
    }

    /// Starts watching unless a watcher of the unit already runs; returns at once.
    public Unit watch(Watch watch) {
        return registry.startHolding(watch.ref(), watch.attempt())
               ? scheduled(watch)
               : unit();
    }

    /// One observation and its consequence. A unit no longer holding this attempt (forgotten after its report, or the
    /// attempt replaced or discarded) ends the watch.
    Promise<Step> tick(Watch watch) {
        return registry.isHolding(watch.ref(), watch.attempt())
               ? observed(watch.ref()).flatMap(observation -> decided(watch, observation))
               : Promise.success(Step.done());
    }

    // ---- scheduling ----------------------------------------------------------------------------------------
    private Unit scheduled(Watch watch) {
        timers.schedule(() -> ticked(watch), timings.grantPoll());

        return unit();
    }

    /// The scheduler's `Runnable`: fire-and-forget by contract.
    ///
    /// FER (degrade forward): a tick that fails (docker or a file read failing for a moment) observes nothing,
    /// so the watch goes on unchanged and the next tick looks again; the failure is logged. Nothing is decided on
    /// a failed observation, so no outcome is guessed.
    @Contract
    private void ticked(Watch watch) {
        // May run late: nobody waits for a tick; `followed` only schedules the next one or ends the watch.
        tick(watch).onFailure(cause -> LOG.warn("unit {}: grant watch tick failed: {}",
                                                watch.ref().containerName(),
                                                cause))
            .recover(_ -> Step.next(watch))
            .onSuccess(this::followed);
    }

    private Unit followed(Step step) {
        return switch (step) {
            case Step.Next next -> scheduled(next.watch());
            case Step.Done _ -> unit();
        };
    }

    // ---- observation: holder first, then bbhold.err ------------------------------------------------------
    private Promise<Observation> observed(UnitRef ref) {
        return holders.status(ref)
                      .map(status -> status.or(HolderStatus.DEAD))
                      .flatMap(status -> observation(ref, status))
                      .async();
    }

    private Result<Observation> observation(UnitRef ref, HolderStatus holder) {
        return files.text(ref, UnitFile.BBHOLD_ERR)
                    .map(text -> Observation.observation(holder,
                                                         text.or("")));
    }

    private Promise<Step> decided(Watch watch, Observation observation) {
        return GrantLine.ticket(observation.errText())
                        .map(ticket -> granted(watch, observation, ticket))
                        .or(() -> notGranted(watch, observation));
    }

    // ---- granted -------------------------------------------------------------------------------------------
    /// `bbhold.ticket` and `start` are written once, at the first tick that sees the grant: a watcher re-armed
    /// after a worker restart keeps the start the first worker recorded.
    private Promise<Step> granted(Watch watch, Observation observation, String ticket) {
        var ref = watch.ref();

        return files.storeOnce(ref, UnitFile.BBHOLD_TICKET, ticket)
                    .flatMap(_ -> grantStart(ref))
                    .async()
                    .flatMap(_ -> docker.containerId(ref.containerName()))
                    .flatMap(container -> resolved(watch.grantedAt(grantSeen(watch)),
                                                   observation,
                                                   container));
    }

    private Result<Boolean> grantStart(UnitRef ref) {
        return EpochNanos.epochNanos(clock.instant()).flatMap(now -> files.storeOnce(ref,
                                                                                     UnitFile.START,
                                                                                     Long.toString(now)));
    }

    private Instant grantSeen(Watch watch) {
        return watch.grantedAt()
                    .or(clock::instant);
    }

    private Promise<Step> resolved(Watch watch, Observation observation, Option<String> container) {
        return container.map(id -> started(watch, id))
                        .or(() -> stillAbsent(watch, observation));
    }

    private Promise<Step> stillAbsent(Watch watch, Observation observation) {
        return observation.holder() == HolderStatus.DEAD && waitedOut(watch)
               ? exitCode(watch).flatMap(rc -> failed(watch,
                                                      StartFailureKind.ERROR,
                                                      dockerRunFailed(rc,
                                                                      observation.errText())))
                         .ensureWith(_ -> reap.reaped(watch.ref()))
               : Promise.success(Step.next(watch));
    }

    private boolean waitedOut(Watch watch) {
        return watch.grantedAt()
                    .map(at -> Duration.between(at,
                                                clock.instant())
                                       .compareTo(timings.containerWait().duration()) >= 0)
                    .or(false);
    }

    static String dockerRunFailed(Option<Integer> rc, String errText) {
        return "port-group: docker run failed" + rc.map(code -> " (rc " + code + ")")
                                                   .or("") + ": " + tail(errText);
    }

    private static String tail(String text) {
        var trimmed = text.strip();

        return trimmed.length() <= ERR_TAIL_CHARS
               ? trimmed
               : trimmed.substring(trimmed.length() - ERR_TAIL_CHARS);
    }

    /// The container runs: record it, hand it to the `HoldMonitor`, honour a cancel that raced the grant, arm the
    /// timer (from `start`, the grant) and the exit watcher, then tell the controller. The watch ends only once
    /// `UnitStarted` is posted; every step before is idempotent (the same files, the timer replaced, the watcher and
    /// the `HoldMonitor` keyed by unit), so a failed step leaves the unit holding and the next tick runs them again.
    private Promise<Step> started(Watch watch, String containerId) {
        return owned(watch, containerId).fold(result -> afterOwned(watch, containerId, result));
    }

    /// Recorded, held, a late cancel honoured, timer and exit watcher armed: from here the worker bounds the
    /// container and collects its exit.
    private Promise<Instant> owned(Watch watch, String containerId) {
        return recordedAsHeld(watch, containerId).flatMap(_ -> armed(watch, containerId).async());
    }

    private Promise<Step> afterOwned(Watch watch, String containerId, Result<Instant> owned) {
        return owned.fold(cause -> notOwned(watch, containerId, cause), _ -> reported(watch, containerId));
    }

    /// A container the worker cannot take over (no timer or exit watcher, or no `UnitStarted` to tell the controller)
    /// must not run on. The next tick tries again; once `containerWait` has passed since the grant with the steps
    /// still failing, the container is killed and the unit ends `ERROR(infra: …)`.
    private Promise<Step> notOwned(Watch watch, String containerId, Cause cause) {
        LOG.warn("unit {}: granted container {} not taken over yet: {}",
                 watch.ref().containerName(),
                 containerId,
                 cause);

        return waitedOut(watch)
               ? abandoned(watch, containerId, cause)
               : Promise.success(Step.next(watch));
    }

    /// FER (degrade forward): a kill that fails is logged and the failure is still reported; the container then
    /// has no timer, and the controller's `ERROR` makes the unit's end visible.
    private Promise<Step> abandoned(Watch watch, String containerId, Cause cause) {
        var ref = watch.ref();

        return docker.kill(containerId)
                     .onFailure(killFailure -> LOG.error("unit {}: kill of the abandoned container {} failed: {}",
                                                         ref.containerName(),
                                                         containerId,
                                                         killFailure))
                     .recover(_ -> unit())
                     .flatMap(_ -> failed(watch,
                                          StartFailureKind.ERROR,
                                          "infra: " + cause.message()))
                     .withSuccess(_ -> registry.forget(ref,
                                                       watch.attempt()));
    }

    /// `UnitStarted`, after which the watch ends. A message that cannot be built (an unreadable `marker` or `start`,
    /// the facts probe failing) is a take-over failure like the others: retried on the next tick, and past
    /// `containerWait` since the grant the container is killed and the unit ends `ERROR(infra: …)`. The exit watcher
    /// is armed by then, so the exit report of that killed container follows the `UnitStartFailed`; the controller
    /// has already ended the unit and ignores it.
    private Promise<Step> reported(Watch watch, String containerId) {
        return unitStarted(watch.ref(), containerId).fold(result -> afterBuilt(watch, containerId, result));
    }

    private Promise<Step> afterBuilt(Watch watch, String containerId, Result<Message> built) {
        return built.fold(cause -> notOwned(watch, containerId, cause), message -> startedPosted(watch, message));
    }

    /// FER (degrade forward): a post that fails (the only way `Outbox.post` fails is a broken link) leaves the unit
    /// holding; the next tick runs the idempotent steps again and posts again. The container keeps running bounded by
    /// its timer meanwhile, and a `Reattach` after the reconnect re-sends `UnitStarted` in any case.
    private Promise<Step> startedPosted(Watch watch, Message message) {
        return posted(message).withSuccess(_ -> registry.stopHolding(watch.ref(),
                                                                     watch.attempt()))
                     .map(_ -> Step.done())
                     .onFailure(cause -> LOG.warn("unit {}: UnitStarted not posted, posting again: {}",
                                                  watch.ref().containerName(),
                                                  cause))
                     .recover(_ -> Step.next(watch));
    }

    /// Re-sends the `UnitStarted` of a held unit whose container runs (a `Reattach` after a reconnect), ticket
    /// included.
    ///
    /// FER (degrade forward): a re-send that cannot be built or posted is logged and dropped. The unit itself is
    /// unaffected (bounded by its timer, its exit collected), only the controller's start facts stay missing until the
    /// next `Reattach` re-sends them.
    public Promise<Unit> resent(UnitRef ref, String containerId) {
        return resentWith(ref, unitStarted(ref, containerId));
    }

    /// Re-sends the `UnitStarted` of a unit granted its port group (it has a `bbhold.ticket`) whose exit is about to
    /// be collected (integration F4): its first `UnitStarted` may have been lost with the link, and its container
    /// exited before the reconnect, so reconcile `Collect`s it instead of re-attaching it. Posted before the exit
    /// report, so the controller records the start (ticket included) before it completes the unit; a repeat is a
    /// no-op there (`UnitStore.started` records a unit's first start only). A unit never granted has nothing to
    /// re-send.
    ///
    /// FER (degrade forward), as [#resent(UnitRef, String)]: a re-send that cannot be built or posted is logged and
    /// dropped, and the exit is collected regardless; the unit then completes without its start facts, as before.
    public Promise<Unit> resentBeforeCollect(UnitRef ref) {
        return files.exists(ref, UnitFile.BBHOLD_TICKET)
               ? resentWith(ref,
                            files.container(ref).async().flatMap(containerId -> unitStarted(ref, containerId)))
               : Promise.unitPromise();
    }

    private Promise<Unit> resentWith(UnitRef ref, Promise<Message> started) {
        return started.flatMap(this::posted)
                      .onFailure(cause -> LOG.warn("unit {}: UnitStarted not re-sent: {}",
                                                   ref.containerName(),
                                                   cause))
                      .recover(_ -> unit());
    }

    /// `container`, the unit handed to the `HoldMonitor`, then a cancel that raced the grant honoured.
    private Promise<Unit> recordedAsHeld(Watch watch, String containerId) {
        var ref = watch.ref();

        return files.store(ref, UnitFile.CONTAINER, containerId)
                    .onSuccessRun(() -> registry.hold(ref, containerId))
                    .async()
                    .flatMap(_ -> lateCancelHonoured(watch, containerId));
    }

    private Promise<Unit> lateCancelHonoured(Watch watch, String containerId) {
        var ref = watch.ref();

        return registry.isCancelled(ref, watch.attempt())
               ? files.storeOnce(ref, UnitFile.KILLED, UnitCanceller.CANCEL)
                      .async()
                      .flatMap(_ -> docker.kill(containerId))
               : Promise.success(unit());
    }

    private Result<Instant> armed(Watch watch, String containerId) {
        return timer.arm(watch.ref(),
                         watch.timeout())
                    .onSuccessRun(() -> watcher.watch(watch.ref(),
                                                      watch.attempt(),
                                                      containerId));
    }

    private Promise<Unit> posted(Message message) {
        return outbox.post(message)
                     .async();
    }

    /// The `UnitStarted` from the files recorded at the grant: `MissingState` when a required file is absent,
    /// `CorruptState` when one does not parse.
    Promise<Message> unitStarted(UnitRef ref, String containerId) {
        return recordedStart(ref).async()
                            .mapWith(_ -> facts.probe(),
                                     (recorded, host) -> unitStartedMessage(ref, containerId, recorded, host));
    }

    /// `image`, `marker`, `start` and the bb-hold ticket as recorded; `start` and `bbhold.ticket` were written at
    /// the grant.
    private Result<RecordedStart> recordedStart(UnitRef ref) {
        return Result.all(files.text(ref, UnitFile.IMAGE),
                          required(ref, UnitFile.MARKER),
                          required(ref, UnitFile.START),
                          files.text(ref, UnitFile.BBHOLD_TICKET),
                          files.attempt(ref))
                     .map(RecordedStart::recordedStart);
    }

    private Result<Long> required(UnitRef ref, UnitFile file) {
        return files.nanos(ref, file)
                    .flatMap(nanos -> nanos.toResult(WorkerError.MissingState.FACTORY.apply(files.path(ref, file))));
    }

    private static Message unitStartedMessage(UnitRef ref, String containerId, RecordedStart recorded, HostFacts host) {
        return new Message.UnitStarted(ref.job().value(),
                                       ref.unit().value(),
                                       recorded.attempt(),
                                       containerId,
                                       recorded.image().map(String::trim).or(""),
                                       recorded.marker(),
                                       EpochNanos.instant(recorded.start()),
                                       HostFactsDto.hostFactsDto(host),
                                       recorded.ticket().map(String::trim));
    }

    // ---- not granted ---------------------------------------------------------------------------------------
    private Promise<Step> notGranted(Watch watch, Observation observation) {
        return observation.holder() == HolderStatus.DEAD
               ? exitCode(watch).flatMap(rc -> exitedBeforeGrant(watch, rc, observation.errText()))
               : Promise.success(Step.next(watch));
    }

    private Promise<Step> exitedBeforeGrant(Watch watch, Option<Integer> rc, String errText) {
        return switch (beforeGrant(rc,
                                   errText,
                                   registry.isCancelled(watch.ref(), watch.attempt()),
                                   watch.retry().isPresent())) {
            case BeforeGrant.Retry _ -> retriedOnce(watch);
            case BeforeGrant.Failed failed -> failed(watch, failed.kind(), failed.reason()).ensureWith(_ -> reap.reaped(watch.ref()));
        };
    }

    /// bb-hold's rc when this JVM spawned it; bounded by one poll period, because the holder is already seen dead
    /// and its exit is about to be (or already was) reaped.
    ///
    /// FER (degrade forward): an rc that does not arrive in time is unknown, and an unknown rc is classified from
    /// the `bbhold.err` lines instead, exactly as a re-armed watcher does.
    private Promise<Option<Integer>> exitCode(Watch watch) {
        return watch.exit()
                    .map(this::rcWithin)
                    .or(() -> Promise.success(none()));
    }

    /// `timeout` fails the exit promise itself when the rc does not arrive in time. A decision re-run on a later tick
    /// (its report was not posted) then reads no rc and classifies from the `bbhold.err` lines: every exit that
    /// changes the verdict (rc 2, 3, 9, the 143 signal) prints its own line, so the verdict stands and only the
    /// "(rc N)" detail of an `EXITED` or failed `docker run` reason can be lost (and a 143 whose line is missing reads
    /// `EXITED` rather than `SIGNALLED`: `ERROR` either way).
    private Promise<Option<Integer>> rcWithin(Promise<Integer> exit) {
        return exit.timeout(timings.grantPoll())
                   .map(Option::some)
                   .recover(_ -> none());
    }

    /// `beforeGrant` decides a retry only while the watch carries one.
    private Promise<Step> retriedOnce(Watch watch) {
        return watch.retry()
                    .map(again -> retried(watch, again))
                    .or(() -> duplicateRefused(watch));
    }

    private Promise<Step> duplicateRefused(Watch watch) {
        return failed(watch, StartFailureKind.ERROR, DUPLICATE);
    }

    /// The one retry of a duplicate refusal: a fresh enqueue after `duplicateRetry`, unless the unit was cancelled
    /// meanwhile; the next watch carries no retry, so a second rc 3 is final.
    private Promise<Step> retried(Watch watch, Retry retry) {
        LOG.warn("unit {}: bb-hold refused a duplicate holder; enqueueing once more",
                 watch.ref().containerName());

        return Promise.<Unit> promise(timings.duplicateRetry(),
                                      Result::unitResult)
                      .flatMap(_ -> enqueuedAgainUnlessCancelled(watch, retry));
    }

    private Promise<Step> enqueuedAgainUnlessCancelled(Watch watch, Retry retry) {
        return registry.isCancelled(watch.ref(), watch.attempt())
               ? failed(watch, StartFailureKind.CANCELLED, CANCELLED)
               : enqueuedAgain(watch, retry);
    }

    /// An enqueue that fails is final (the launcher already signalled a holder it spawned but could not record):
    /// `ERROR(port-group: …)` with the failure, never a retry on every tick.
    private Promise<Step> enqueuedAgain(Watch watch, Retry retry) {
        return launcher.enqueue(retry.launch(),
                                retry.hold())
                       .map(holding -> Step.next(watch.retriedAs(holding)))
                       .fold(result -> afterEnqueue(watch, result));
    }

    private Promise<Step> afterEnqueue(Watch watch, Result<Step> enqueued) {
        return enqueued.fold(cause -> failed(watch, StartFailureKind.ERROR, REENQUEUE_FAILED + cause.message()),
                             Promise::success);
    }

    /// Registry updates that end a watch run with `withSuccess`, in chain order: `onSuccessRun` would run them
    /// asynchronously, after the step the caller already holds.
    ///
    /// The hold failed: tell the controller, then remove the unit's network (nothing else was created; no
    /// container exists). The watch ends only once the report is posted: a post that fails (the link is down)
    /// leaves the unit holding, and the next tick decides and posts again.
    ///
    /// BER (compensate by inverse): the per-unit network created at prepare is removed (`network rm` is
    /// idempotent; a Gate unit, on the host network, created none and the removal finds nothing). Its own failure
    /// is logged and the reply still goes out: the name is deterministic and the leftover holds no unit state.
    private Promise<Step> failed(Watch watch, StartFailureKind kind, String reason) {
        var ref = watch.ref();

        LOG.warn("unit {}: hold ended before the container ran: {} {}", ref.containerName(), kind, reason);

        return posted(ref,
                      watch.attempt(),
                      kind,
                      reason).async()
                     .flatMap(_ -> networkRemoved(ref))
                     .withSuccess(_ -> registry.stopHolding(ref,
                                                            watch.attempt()))
                     .map(_ -> Step.done());
    }

    /// The unsolicited `UnitStartFailed` of a hold that ended, in the attempt the watch is of (protocol v3).
    private Result<Unit> posted(UnitRef ref, int attempt, StartFailureKind kind, String reason) {
        return outbox.post(startFailed(ref, attempt, kind, reason));
    }

    private static Message startFailed(UnitRef ref, int attempt, StartFailureKind kind, String reason) {
        return new Message.UnitStartFailed(ref.job().value(),
                                           ref.unit().value(),
                                           attempt,
                                           kind,
                                           reason,
                                           none());
    }

    private Promise<Unit> networkRemoved(UnitRef ref) {
        return docker.networkRemove(ref.networkName())
                     .onFailure(cause -> LOG.error("unit {}: network removal after a failed hold failed: {}",
                                                   ref.containerName(),
                                                   cause))
                     .recover(_ -> unit());
    }

    // ---- classification before the grant (pure) -----------------------------------------------------------
    static final String DUPLICATE = "port-group: bb-hold refused a duplicate holder (rc 3)";
    static final String REFUSED = "port-group: bb-hold refused (rc 2)";
    static final String SIGNALLED = "port-group: bb-hold signalled";
    static final String LEFT_QUEUE = "port-group: ticket left the queue (rc 9)";
    static final String EXITED = "port-group: bb-hold exited before grant";
    static final String CANCELLED = "cancelled";
    static final String REENQUEUE_FAILED = "port-group: bb-hold enqueue after a duplicate refusal failed: ";

    /// What a holder that exited before the grant means. `rc` wins when known; otherwise the `bbhold.err` line
    /// bb-hold prints for that exit decides.
    static BeforeGrant beforeGrant(Option<Integer> rc, String errText, boolean cancelled, boolean canRetry) {
        return switch (rc.map(HoldExit::ofRc)
                         .or(() -> HoldExit.ofText(errText))) {
            case DUPLICATE -> duplicate(cancelled, canRetry);
            case REFUSED -> BeforeGrant.failed(StartFailureKind.ERROR, REFUSED);
            case SIGNALLED -> cancelledOr(cancelled, SIGNALLED);
            case LEFT_QUEUE -> cancelledOr(cancelled, LEFT_QUEUE);
            case OTHER -> BeforeGrant.failed(StartFailureKind.ERROR,
                                             EXITED + rc.map(code -> " (rc " + code + ")").or(""));
        };
    }

    /// A cancelled unit is never enqueued again.
    private static BeforeGrant duplicate(boolean cancelled, boolean canRetry) {
        return canRetry && !cancelled
               ? BeforeGrant.retry()
               : cancelledOr(cancelled, DUPLICATE);
    }

    private static BeforeGrant cancelledOr(boolean cancelled, String reason) {
        return cancelled
               ? BeforeGrant.failed(StartFailureKind.CANCELLED, CANCELLED)
               : BeforeGrant.failed(StartFailureKind.ERROR, reason);
    }

    /// bb-hold's exits before a grant, by rc or by the stderr line it prints for each.
    enum HoldExit {
        REFUSED,
        DUPLICATE,
        LEFT_QUEUE,
        SIGNALLED,
        OTHER;
        static HoldExit ofRc(int rc) {
            return switch (rc) {
                case 2 -> REFUSED;
                case 3 -> DUPLICATE;
                case 9 -> LEFT_QUEUE;
                case 143 -> SIGNALLED;
                default -> OTHER;
            };
        }
        /// The first marker `bbhold.err` contains, in this order (the duplicate refusal is also a `refusing:` line).
        static HoldExit ofText(String errText) {
            return Option.from(TEXT_MARKERS.stream()
                                           .filter(marker -> errText.contains(marker.text()))
                                           .map(TextMarker::exit)
                                           .findFirst()).or(OTHER);
        }
        private static final List<TextMarker> TEXT_MARKERS = List.of(TextMarker.textMarker("refusing: another bb-hold holds",
                                                                                           DUPLICATE),
                                                                     TextMarker.textMarker("refusing:", REFUSED),
                                                                     TextMarker.textMarker("left the queue while waiting",
                                                                                           LEFT_QUEUE),
                                                                     TextMarker.textMarker("bb-hold: signal:", SIGNALLED));
    }

    /// A `bbhold.err` line fragment and the exit it marks.
    record TextMarker(String text, HoldExit exit) {
        static TextMarker textMarker(String text, HoldExit exit) {
            return new TextMarker(text, exit);
        }
    }

    sealed interface BeforeGrant {
        record Retry() implements BeforeGrant {}

        record Failed(StartFailureKind kind, String reason) implements BeforeGrant {}

        static BeforeGrant retry() {
            return new Retry();
        }

        static BeforeGrant failed(StartFailureKind kind, String reason) {
            return new Failed(kind, reason);
        }
    }

    // ---- state ---------------------------------------------------------------------------------------------
    /// One hold being watched. `exit` is bb-hold's rc as this JVM sees it (none after a restart); `retry` is the
    /// launch to repeat once on a duplicate refusal (none after a restart, and after the retry); `grantedAt` is
    /// when this watcher first saw the grant.
    public record Watch(UnitRef ref,
                        int attempt,
                        Duration timeout,
                        Option<Promise<Integer>> exit,
                        Option<Retry> retry,
                        Option<Instant> grantedAt) {
        /// A hold this worker just enqueued; the launch is kept for the one duplicate retry.
        public static Watch watch(UnitLaunch launch, UnitLauncher.Launch.Holding holding) {
            return new Watch(launch.request().ref(),
                             launch.request().attempt(),
                             launch.request().timeout(),
                             some(holding.exit()),
                             launch.request().portGroup().map(hold -> Retry.retry(launch, hold)),
                             none());
        }

        /// A hold found waiting after a worker restart (`Reattach`, reconcile rule 3).
        public static Watch watch(UnitRef ref, int attempt, Duration timeout) {
            return new Watch(ref, attempt, timeout, none(), none(), none());
        }

        Watch grantedAt(Instant at) {
            return new Watch(ref, attempt, timeout, exit, retry, some(at));
        }

        Watch retriedAs(UnitLauncher.Launch.Holding holding) {
            return new Watch(ref, attempt, timeout, some(holding.exit()), none(), none());
        }
    }

    /// The launch a duplicate refusal repeats.
    public record Retry(UnitLaunch launch, HoldRequest hold) {
        static Retry retry(UnitLaunch launch, HoldRequest hold) {
            return new Retry(launch, hold);
        }
    }

    record RecordedStart(Option<String> image, long marker, long start, Option<String> ticket, int attempt) {
        static RecordedStart recordedStart(Option<String> image,
                                           long marker,
                                           long start,
                                           Option<String> ticket,
                                           int attempt) {
            return new RecordedStart(image, marker, start, ticket, attempt);
        }
    }

    record Observation(HolderStatus holder, String errText) {
        static Observation observation(HolderStatus holder, String errText) {
            return new Observation(holder, errText);
        }
    }

    sealed interface Step {
        record Next(Watch watch) implements Step {}

        record Done() implements Step {}

        static Step next(Watch watch) {
            return new Next(watch);
        }

        static Step done() {
            return new Done();
        }
    }
}
