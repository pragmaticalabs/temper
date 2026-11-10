package org.pragmatica.temper.worker.unit;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.hold.HoldAction;
import org.pragmatica.temper.domain.hold.HoldRules;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.VoidReason;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.docker.DockerCli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Liveness of a held port group while its container runs (m1-design §1.4), checked on every heartbeat for every
/// held unit ([UnitRegistry#held]).
///
/// **One round, in this read order** (the precondition of [HoldRules#decide], pinned by `HoldMonitorTest`):
/// 1. the holder;
/// 2. after `observationGap`, the holder again;
/// 3. the container.
///
/// Holder before container: bb-hold exits *because* the container exited, so a container read first could see
/// "RUNNING", then a holder that died of that very exit, and void a clean unit. The second observation absorbs a
/// pid-file read racing its write. Both observations live in one round, so a holder that died while no worker
/// was attached is caught on the first heartbeat after `Reattach` re-arms the unit.
///
/// On `VOID_PORT_GROUP_LOST`: `void = port-group-lost` (first writer wins), then `docker kill`, then, whatever the
/// kill's outcome, [BbQueueReap] releases the dead holder's ticket. The normal exit path reports `UnitExited.voidReason`, so the unit
/// ends `VOID(port-group-lost)` with its observed reds listed; that report follows the container's exit, never the
/// reap, which only ends this round.
public record HoldMonitor(HolderProbe holders,
                          DockerCli docker,
                          UnitFiles files,
                          UnitRegistry registry,
                          BbQueueReap reap,
                          TimeSpan observationGap) {
    private static final Logger LOG = LoggerFactory.getLogger(HoldMonitor.class);

    public static HoldMonitor holdMonitor(HolderProbe holders,
                                          DockerCli docker,
                                          UnitFiles files,
                                          UnitRegistry registry,
                                          BbQueueReap reap,
                                          TimeSpan observationGap) {
        return new HoldMonitor(holders, docker, files, registry, reap, observationGap);
    }

    /// One round for every held unit (Fork-Join: the rounds are independent). A round that fails is logged and
    /// leaves its unit for the next heartbeat.
    public Promise<List<HoldAction>> tick() {
        return Promise.allOf(registry.held().stream().map(this::roundLogged).toList()).map(HoldMonitor::actions);
    }

    /// One round for one unit: holder, holder, container, decide, act.
    public Promise<HoldAction> round(UnitRef ref, String container) {
        return holder(ref).flatMap(earlier -> laterHolder(ref, earlier))
                     .flatMap(holders -> withContainer(holders, container))
                     .map(HoldMonitor::decided)
                     .flatMap(action -> acted(ref, container, action));
    }

    /// FER (degrade forward): a failed round (docker or a file read failing for a moment) decides nothing, so the
    /// unit is neither voided nor cleared; the next heartbeat runs a fresh round. The failure is logged.
    private Promise<HoldAction> roundLogged(UnitRegistry.RunningUnit unit) {
        return round(unit.ref(),
                     unit.containerId()).onFailure(cause -> LOG.warn("unit {}: hold check failed: {}",
                                                                     unit.ref().containerName(),
                                                                     cause))
                    .recover(_ -> HoldAction.NONE);
    }

    private static List<HoldAction> actions(List<Result<HoldAction>> rounds) {
        return rounds.stream()
                     .map(round -> round.or(HoldAction.NONE))
                     .toList();
    }

    /// A unit that is not (or no longer) a hold unit has no holder to lose: it reads as `HELD`, never `DEAD`.
    private Promise<HolderStatus> holder(UnitRef ref) {
        return holders.status(ref)
                      .map(status -> status.or(HolderStatus.HELD))
                      .async();
    }

    private Promise<Holders> laterHolder(UnitRef ref, HolderStatus earlier) {
        return Promise.<Unit> promise(observationGap, Result::unitResult)
                      .flatMap(_ -> holder(ref))
                      .map(later -> Holders.holders(earlier, later));
    }

    private Promise<Observed> withContainer(Holders holders, String container) {
        return docker.status(container)
                     .map(status -> Observed.observed(holders,
                                                      containerState(status)));
    }

    private static HoldAction decided(Observed observed) {
        return HoldRules.decide(observed.holders().earlier(),
                                observed.holders().later(),
                                observed.container());
    }

    static ContainerState containerState(ContainerStatus status) {
        return switch (status) {
            case RUNNING -> ContainerState.RUNNING;
            case EXITED -> ContainerState.EXITED;
            case ABSENT -> ContainerState.ABSENT;
        };
    }

    private Promise<HoldAction> acted(UnitRef ref, String container, HoldAction action) {
        return switch (action) {
            case NONE -> Promise.success(action);
            case VOID_PORT_GROUP_LOST -> voidedAndKilled(ref, container).map(_ -> action);
        };
    }

    private Promise<Unit> voidedAndKilled(UnitRef ref, String container) {
        LOG.error("unit {}: bb-hold holder is gone while the container runs: VOID(port-group-lost), killing it",
                  ref.containerName());

        return files.storeOnce(ref,
                               UnitFile.VOID,
                               VoidReason.PORT_GROUP_LOST.wire())
                    .async()
                    .flatMap(_ -> killedThenReaped(ref, container));
    }

    /// The reap follows the kill attempt whatever its outcome: a `docker kill` that fails (a slow daemon, a CLI timeout
    /// after the kill took effect, a container that exited by itself) still leaves a dead holder's ticket, and the next
    /// round reads the container EXITED, decides `NONE` and never reaps. The kill's own result is kept, so a failed kill
    /// still fails the round (logged by [#roundLogged]); the reap itself never fails ([BbQueueReap#reaped]).
    private Promise<Unit> killedThenReaped(UnitRef ref, String container) {
        return docker.kill(container)
                     .fold(killed -> reapedAfter(ref, killed));
    }

    private Promise<Unit> reapedAfter(UnitRef ref, Result<Unit> killed) {
        return reap.reaped(ref)
                   .flatMap(_ -> killed.async());
    }

    record Holders(HolderStatus earlier, HolderStatus later) {
        static Holders holders(HolderStatus earlier, HolderStatus later) {
            return new Holders(earlier, later);
        }
    }

    record Observed(Holders holders, ContainerState container) {
        static Observed observed(Holders holders, ContainerState container) {
            return new Observed(holders, container);
        }
    }
}
