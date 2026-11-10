package org.pragmatica.temper.worker.link;

import java.util.List;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.protocol.message.VoidReason;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.unit.HoldMonitor;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The 10-second `Heartbeat` (m0-design §7.2, m1-design §1.3, §1.4, §4.5). One beat:
///
/// 1. samples the running containers' cgroups (a unit's end facts get `memory.peak` and `cpu.stat` from here,
///    m0-design §7.7);
/// 2. probes the host facts, and per running unit:
///    - **run floor:** free space on the docker root or the workspace filesystem below `runFloorBytes` writes
///      `void = disk` (first writer wins) and does NOT kill the unit (maintainer ruling M1-10); exit processing turns
///      it into `VOID(disk)` with the observed reds still listed;
///    - `mem-available-min-kb`: the lowest host `MemAvailable` seen while the unit ran (sizing, §8.3);
/// 3. sends the `Heartbeat` (the watched units and the units waiting in bb-hold, whose leases the controller
///    refreshes) and, in parallel, runs the `HoldMonitor` round of every held unit.
public record HeartbeatTask(UnitRegistry registry,
                            CgroupSampler cgroups,
                            FactsProbe facts,
                            Outbox outbox,
                            UnitFiles files,
                            HoldMonitor holds,
                            long runFloorBytes) {
    private static final Logger LOG = LoggerFactory.getLogger(HeartbeatTask.class);

    public static HeartbeatTask heartbeatTask(UnitRegistry registry,
                                              CgroupSampler cgroups,
                                              FactsProbe facts,
                                              Outbox outbox,
                                              UnitFiles files,
                                              HoldMonitor holds,
                                              long runFloorBytes) {
        return new HeartbeatTask(registry, cgroups, facts, outbox, files, holds, runFloorBytes);
    }

    /// One beat: sample, probe, mark, then send ∥ hold round (Fork-Join: the send and the rounds share nothing).
    public Promise<Unit> beat() {
        var running = registry.running();

        cgroups.sampleAll(running.stream().map(UnitRegistry.RunningUnit::containerId).toList());

        return facts.probe()
                    .map(host -> sampled(running, host))
                    .map(host -> new Message.Heartbeat(refs(registry.leased()),
                                                       HostFactsDto.hostFactsDto(host)))
                    .flatMap(this::sentWithHoldRound);
    }

    private Promise<Unit> sentWithHoldRound(Message heartbeat) {
        return Promise.all(posted(heartbeat),
                           holds.tick())
                      .map((_, _) -> unit());
    }

    private Promise<Unit> posted(Message heartbeat) {
        return outbox.post(heartbeat)
                     .async();
    }

    /// Starts beating every `period` (first beat after one period).
    public Unit start(TimeSpan period) {
        SharedScheduler.scheduleAtFixedRate(this::beatLogged, period);

        return unit();
    }

    /// The scheduler's `Runnable`: a missed beat is logged; the next one comes `period` later, and the controller
    /// declares the link down only after 35 s without any frame.
    @Contract
    private void beatLogged() {
        beat().onFailure(cause -> LOG.warn("heartbeat failed: {}", cause));
    }

    /// Iteration over the running units: the run floor and the `MemAvailable` minimum of each.
    private HostFacts sampled(List<UnitRegistry.RunningUnit> running, HostFacts host) {
        running.forEach(unit -> unitSampled(unit.ref(), host));

        return host;
    }

    /// FER (degrade forward): a unit file that cannot be written is logged and the beat goes on: the heartbeat
    /// keeps every lease alive, and the next beat (10 s later) writes again, so a missed `void=disk` mark is
    /// re-attempted on every beat while the disk stays below the floor.
    private Unit unitSampled(UnitRef ref, HostFacts host) {
        return floorChecked(ref, host).flatMap(_ -> memSampled(ref, host))
                           .onFailure(cause -> LOG.error("unit {}: heartbeat sample not recorded: {}",
                                                         ref.containerName(),
                                                         cause))
                           .or(unit());
    }

    private Result<Unit> floorChecked(UnitRef ref, HostFacts host) {
        return isBelowFloor(host)
               ? files.storeOnce(ref,
                                 UnitFile.VOID,
                                 VoidReason.DISK.wire())
                      .map(written -> floorLogged(ref, host, written))
               : Result.unitResult();
    }

    boolean isBelowFloor(HostFacts host) {
        return host.freeDockerRootBytes() < runFloorBytes || host.freeWorkspaceBytes() < runFloorBytes;
    }

    /// Logged once per unit: when this beat is the one that wrote the mark.
    private static Unit floorLogged(UnitRef ref, HostFacts host, boolean written) {
        some(written).filter(Boolean::booleanValue)
            .onPresentRun(() -> LOG.error("unit {}: free disk below the run floor (docker root {} B, workspace {} B): VOID(disk), left running",
                                          ref.containerName(),
                                          host.freeDockerRootBytes(),
                                          host.freeWorkspaceBytes()));

        return unit();
    }

    private Result<Unit> memSampled(UnitRef ref, HostFacts host) {
        return files.count(ref, UnitFile.MEM_AVAILABLE_MIN)
                    .flatMap(recorded -> lowerStored(ref,
                                                     recorded,
                                                     host.memAvailableKb()));
    }

    private Result<Unit> lowerStored(UnitRef ref, Option<Long> recorded, long sample) {
        return recorded.filter(lowest -> lowest <= sample)
                       .isPresent()
               ? Result.unitResult()
               : files.store(ref, UnitFile.MEM_AVAILABLE_MIN, Long.toString(sample));
    }

    /// Each unit in the attempt its directory holds (protocol v3).
    private List<UnitRefDto> refs(List<UnitRef> leased) {
        return leased.stream()
                     .flatMap(ref -> leasedRef(ref).stream())
                     .toList();
    }

    /// FER (degrade forward): a unit whose `attempt` does not parse is left out of this beat and logged, rather than
    /// named in an attempt it may not be: its lease is not refreshed, which the controller only shows (architecture §3
    /// "Leases"), and the same read fails visibly at its exit.
    private Option<UnitRefDto> leasedRef(UnitRef ref) {
        return files.attempt(ref)
                    .map(attempt -> UnitRefDto.unitRefDto(ref, attempt))
                    .onFailure(cause -> LOG.error("unit {}: unreadable attempt, left out of the heartbeat: {}",
                                                  ref.containerName(),
                                                  cause))
                    .option();
    }
}
