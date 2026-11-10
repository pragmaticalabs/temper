package org.pragmatica.temper.worker.gc;

import java.time.Duration;
import java.time.InstantSource;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.docker.OrphanNetwork;
import org.pragmatica.temper.worker.unit.HolderProbe;
import org.pragmatica.temper.worker.unit.UnitRefs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// Removes the per-unit networks no unit will ever remove: a network is created before its container, and removed by
/// the unit's collect or discard, so a report ack or a `DiscardUnit` that never arrives (the host went dark, the
/// worker restarted, the job was collected away) leaves it behind, and enough of them exhaust Docker's address pools
/// (bigboy, 2026-11: 28 `temper-<job>-main` networks, and every new `docker network create` failed).
///
/// A network is an orphan when all of these hold:
/// 1. it carries the label `temper.job` (selected by label, never by name: a network another tool made is not touched);
/// 2. it holds no container;
/// 3. it was created more than [#MIN_AGE] ago;
/// 4. it is not a waiting hold unit's: a unit queued for its port-group grant has its network and no container yet,
///    for as long as it waits, so a live `bb-hold` holder (`WAITING`) keeps its network.
///
/// The sweep runs at worker start and after a job's checkout GC. It is best effort and never fails its caller.
public record NetworkSweep(DockerCli docker, HolderProbe holders, InstantSource clock, Duration minAge) {
    private static final Logger LOG = LoggerFactory.getLogger(NetworkSweep.class);
    /// How old a network must be before it may be swept. Several workers can share one Docker host (the acceptance
    /// tests run local workers side by side), and a network is created before its container: a network created a
    /// moment ago and not yet attached is a unit starting, not a leak. The start of a unit (workspace, image check,
    /// network, container) takes seconds to a minute or two, so ten minutes leaves a wide margin while a real orphan
    /// is still reclaimed within one worker start or GC tick.
    public static final Duration MIN_AGE = Duration.ofMinutes(10);

    public static NetworkSweep networkSweep(DockerCli docker,
                                            HolderProbe holders,
                                            InstantSource clock,
                                            Duration minAge) {
        return new NetworkSweep(docker, holders, clock, minAge);
    }

    /// FER (degrade forward): a sweep that fails (docker unreachable, a network that will not go) is logged and the
    /// caller goes on; the orphan stays until the next sweep (worker start, or the next GC tick), which asks again.
    public Promise<Unit> sweep() {
        return docker.networkOrphans(clock.instant().minus(minAge))
                     .flatMap(this::removedAll)
                     .withFailure(cause -> LOG.warn("network sweep failed: {}", cause))
                     .recover(_ -> unit());
    }

    /// Fork-Join: each network is removed on its own; a failed removal is recovered per network, so one network that
    /// will not go does not keep the rest.
    private Promise<Unit> removedAll(List<OrphanNetwork> orphans) {
        return Promise.allOf(orphans.stream().filter(orphan -> !waiting(orphan)).map(this::removed).toList()).mapToUnit();
    }

    /// FER (degrade forward): see [#sweep]; the failure is logged at the removal, per network.
    private Promise<Unit> removed(OrphanNetwork orphan) {
        return docker.networkRemove(orphan.name())
                     .withSuccess(_ -> LOG.info("network {}: removed, an orphan of job {} (no container, older than {})",
                                                orphan.name(),
                                                orphan.job(),
                                                minAge))
                     .withFailure(cause -> LOG.warn("network {}: removing the orphan failed: {}",
                                                    orphan.name(),
                                                    cause))
                     .recover(_ -> unit());
    }

    /// A hold unit whose holder is alive and not yet granted. A network whose labels name no unit, or whose holder
    /// cannot be read, is not waiting: nothing then says it is wanted.
    private boolean waiting(OrphanNetwork orphan) {
        return Number.parseLong(orphan.job())
                     .flatMap(job -> UnitRefs.unitRef(job,
                                                      orphan.unit()))
                     .flatMap(holders::status)
                     .or(Option.<HolderStatus> none())
                     .filter(HolderStatus.WAITING::equals)
                     .isPresent();
    }
}
