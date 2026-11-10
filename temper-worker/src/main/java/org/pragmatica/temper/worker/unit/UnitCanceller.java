package org.pragmatica.temper.worker.unit;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.docker.DockerCli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// `CancelUnit` (spec §6.4, m0-design §7.8, m1-design §1.7). The incident rule: the build is killed before its
/// driver. A running container is killed first; its driver (bb-hold, for a hold unit) is never signalled while
/// that container runs, because bb-hold released by a signal leaves its container running (maintainer ruling M1-14),
/// while a killed container makes bb-hold's `docker wait` return and release the ticket by itself.
///
/// `killed=cancel` is written before the kill (unless a timeout's `killed` is already there), so the exit the kill
/// produces is always attributed: the collector reads `killedBy=cancel`, never an unexplained rc 137.
///
/// The unit is added to the cancelled set before anything else, so a `StartUnit` that has not reached the launch
/// yet ends `CANCELLED`, one just past it kills its own container, and a `GrantWatcher` reports a holder that exits
/// on the signal as `CANCELLED`.
///
/// **Direct unit** (M0):
/// - `CANCELLING`: the container was running and is killed; `UnitExited` (rc 137, `killedBy=cancel`) follows;
/// - `EXITED`: the container had already exited; normal collection applies;
/// - `CANCELLED`: no container exists and no start is in flight; the unit never runs;
/// - `CANCELLING` also when no container exists yet but a `StartUnit` is in flight: that start sees the cancel.
///
/// **Hold unit** (a `bbhold.pid` or `bbhold.err` exists):
/// 1. the container exists and runs: `killed=cancel`, `docker kill`, `CANCELLING` (bb-hold then releases);
/// 2. it exited: `EXITED`;
/// 3. no container: `killed=cancel`, SIGTERM to the identified holder (bb-hold dequeues its ticket and exits 143),
///    wait up to `holderExit` for it to be gone, then **re-check the container by name** and kill it if it
///    appeared (the grant can race the signal) → `CANCELLING`; otherwise `CANCELLING` while this worker's
///    `GrantWatcher` (or an in-flight start) will report the end, `CANCELLED` when nothing will (no watcher, the
///    holder is gone, and no container ever ran).
public record UnitCanceller(DockerCli docker,
                            UnitFiles files,
                            UnitRegistry registry,
                            HolderProbe holders,
                            TimeSpan holderExit) {
    private static final Logger LOG = LoggerFactory.getLogger(UnitCanceller.class);
    public static final String CANCEL = "cancel";
    /// The holder-exit wait polls this many times.
    public static final int EXIT_POLLS = 20;

    public enum CancelState {
        CANCELLING,
        EXITED,
        CANCELLED
    }

    public static UnitCanceller unitCanceller(DockerCli docker,
                                              UnitFiles files,
                                              UnitRegistry registry,
                                              HolderProbe holders,
                                              TimeSpan holderExit) {
        return new UnitCanceller(docker, files, registry, holders, holderExit);
    }

    /// Cancels the attempt this host holds of the unit (or the unit when it holds none yet).
    public Promise<CancelState> cancel(UnitRef ref, int attempt) {
        registry.cancel(ref, attempt);

        return cancelledAs(ref, holderStatus(ref));
    }

    /// Cancels an attempt that has not reached this host yet while it still holds a lower one (protocol v3): a
    /// `StartUnit` of it may be replacing the lower attempt right now, or may come later. Nothing of the lower
    /// attempt is touched; the cancel is remembered for the named attempt, which its start honours: `CANCELLING`
    /// while a start of that attempt is in flight, `CANCELLED` when none is (a start of the lower attempt in flight
    /// never sees this cancel, so it promises no end).
    public CancelState cancelAhead(UnitRef ref, int attempt) {
        registry.cancel(ref, attempt);

        return registry.isStarting(ref, attempt)
               ? CancelState.CANCELLING
               : CancelState.CANCELLED;
    }

    /// FER (degrade forward): a holder that cannot be read (a corrupt `bbhold.pid`) can only belong to a hold unit
    /// and cannot be identified, so it reads as a dead holder: the cancel then goes by the container's NAME (a
    /// running one is killed), and nothing is signalled. A cancel never fails for want of a readable pid file.
    private Option<HolderStatus> holderStatus(UnitRef ref) {
        return holders.status(ref)
                      .onFailure(cause -> LOG.error("unit {}: unreadable bb-hold holder, cancelling by container name: {}",
                                                    ref.containerName(),
                                                    cause))
                      .or(some(HolderStatus.DEAD));
    }

    private Promise<CancelState> cancelledAs(UnitRef ref, Option<HolderStatus> holder) {
        return holder.isPresent()
               ? heldCancelled(ref)
               : container(ref).flatMap(container -> byStatus(ref, container));
    }

    // ---- direct unit ---------------------------------------------------------------------------------------
    private Promise<CancelState> byStatus(UnitRef ref, String container) {
        return docker.status(container)
                     .flatMap(status -> cancelled(ref, container, status));
    }

    private Promise<CancelState> cancelled(UnitRef ref, String container, ContainerStatus status) {
        return switch (status) {
            case RUNNING -> killed(ref, container);
            case EXITED -> Promise.success(CancelState.EXITED);
            case ABSENT -> Promise.success(registry.isStarting(ref)
                                           ? CancelState.CANCELLING
                                           : CancelState.CANCELLED);
        };
    }

    /// `killed=cancel` (unless a timeout got there first), then the kill.
    private Promise<CancelState> killed(UnitRef ref, String container) {
        return files.storeOnce(ref, UnitFile.KILLED, CANCEL)
                    .async()
                    .flatMap(_ -> docker.kill(container))
                    .map(_ -> CancelState.CANCELLING);
    }

    private Promise<String> container(UnitRef ref) {
        return files.container(ref)
                    .async();
    }

    // ---- hold unit -----------------------------------------------------------------------------------------
    /// The container by NAME: before the grant no id is recorded, and after it the name is the same container.
    private Promise<CancelState> heldCancelled(UnitRef ref) {
        return docker.status(ref.containerName())
                     .flatMap(status -> heldCancelled(ref, status));
    }

    private Promise<CancelState> heldCancelled(UnitRef ref, ContainerStatus status) {
        return switch (status) {
            case RUNNING -> killed(ref, ref.containerName());
            case EXITED -> Promise.success(CancelState.EXITED);
            case ABSENT -> signalled(ref);
        };
    }

    /// No container yet: attribute whatever may still start, signal the driver, then watch both until the holder is
    /// gone. The grant can land between the container check and the signal (no check closes that window: bb-hold
    /// is a separate process), so the container is looked at on every poll, and a container that appears is killed
    /// at once rather than after the wait.
    private Promise<CancelState> signalled(UnitRef ref) {
        return holderSignalled(ref).async()
                              .flatMap(_ -> awaited(ref, EXIT_POLLS));
    }

    /// `killed=cancel` (unless a timeout got there first), then SIGTERM to the identified holder.
    ///
    /// FER (degrade forward): a signal that cannot be sent (the holder unreadable) is logged; the polls below still
    /// kill a container that appears, and the `GrantWatcher` honours the cancel at the grant.
    private Result<Boolean> holderSignalled(UnitRef ref) {
        return files.storeOnce(ref, UnitFile.KILLED, CANCEL)
                    .flatMap(_ -> terminated(ref));
    }

    private Result<Boolean> terminated(UnitRef ref) {
        return holders.terminate(ref)
                      .onSuccess(sent -> LOG.info("unit {}: cancel while holding, SIGTERM sent to bb-hold: {}",
                                                  ref.containerName(),
                                                  sent))
                      .onFailure(cause -> LOG.error("unit {}: cancel while holding, bb-hold not signalled: {}",
                                                    ref.containerName(),
                                                    cause))
                      .recover(_ -> false);
    }

    /// One poll: the container first (a running one is killed now), then the holder. The polls end when the holder
    /// is gone or `holderExit` has passed; the container is then checked a last time. A holder still alive after
    /// the wait is logged: a container it starts later is killed by the `GrantWatcher`'s late-cancel check (the
    /// unit is in the cancelled set and `killed=cancel` is recorded).
    private Promise<CancelState> awaited(UnitRef ref, int pollsLeft) {
        return docker.status(ref.containerName())
                     .flatMap(status -> polled(ref, status, pollsLeft));
    }

    private Promise<CancelState> polled(UnitRef ref, ContainerStatus status, int pollsLeft) {
        return status == ContainerStatus.ABSENT
               ? holderPolled(ref, holderStatus(ref), pollsLeft)
               : rechecked(ref, status);
    }

    private Promise<CancelState> holderPolled(UnitRef ref, Option<HolderStatus> holder, int pollsLeft) {
        return isGone(holder) || pollsLeft <= 0
               ? lastLook(ref, holder)
               : Promise.<Unit> promise(pollDelay(), Result::unitResult).flatMap(_ -> awaited(ref, pollsLeft - 1));
    }

    /// The holder is gone (or the wait is over): a container it started just before exiting is still caught.
    private Promise<CancelState> lastLook(UnitRef ref, Option<HolderStatus> holder) {
        return Promise.success(stillAliveLogged(ref, holder))
                      .flatMap(_ -> docker.status(ref.containerName()))
                      .flatMap(status -> rechecked(ref, status));
    }

    private static Unit stillAliveLogged(UnitRef ref, Option<HolderStatus> status) {
        status.filter(holder -> holder != HolderStatus.DEAD)
              .onPresent(holder -> LOG.warn("unit {}: bb-hold still {} after the cancel's SIGTERM",
                                            ref.containerName(),
                                            holder));

        return unit();
    }

    private static boolean isGone(Option<HolderStatus> status) {
        return status.filter(holder -> holder != HolderStatus.DEAD)
                     .isEmpty();
    }

    private TimeSpan pollDelay() {
        return TimeSpan.fromDuration(holderExit.duration().dividedBy(EXIT_POLLS));
    }

    private Promise<CancelState> rechecked(UnitRef ref, ContainerStatus status) {
        return switch (status) {
            case RUNNING -> killed(ref, ref.containerName());
            case EXITED -> Promise.success(CancelState.EXITED);
            case ABSENT -> Promise.success(registry.isHolding(ref) || registry.isStarting(ref)
                                           ? CancelState.CANCELLING
                                           : CancelState.CANCELLED);
        };
    }
}
