package org.pragmatica.temper.worker.link;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.unit.UnitRegistry;

import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.HoldMonitor;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The heartbeat (m0-design §7.2, §7.7): the watched units for lease refresh, fresh facts, and the cgroup sample
/// that later becomes the unit's end facts.
class HeartbeatTaskTest {
    @Test
    void beat_postsTheRunningUnitsAndFacts_andSamplesTheirCgroups(@TempDir Path cgroup) throws Exception {
        var scope = Files.createDirectories(cgroup.resolve("system.slice/docker-cid.scope"));

        Files.writeString(scope.resolve("memory.peak"), "42\n");

        var registry = UnitRegistry.unitRegistry();
        var sampler = CgroupSampler.cgroupSampler(cgroup, () -> Instant.EPOCH);
        var outbox = new RecordingOutbox();
        var facts = HostFacts.hostFacts(1, 2, 3, 0, 1, none(), none(), 4, 5, 6, Instant.EPOCH, List.of());

        registry.startWatching(Fixtures.ref(9, "main"), 1, "cid");
        var paths = WorkerPaths.workerPaths(cgroup.resolve("temper"));
        var files = UnitFiles.unitFiles(paths);

        ok(HeartbeatTask.heartbeatTask(registry,
                                       sampler,
                                       () -> Promise.success(facts),
                                       outbox,
                                       files,
                                       HoldMonitor.holdMonitor(Fixtures.noHolders(paths, files),
                                                               DockerCli.dockerCli(new FakeRunner(), Map.of(), Fixtures.fastTimings()),
                                                               files,
                                                               registry,
                                                               Fixtures.reap(new FakeRunner()),
                                                               Fixtures.fastHold()
                                                                       .observationGap()),
                                       0)
                        .beat()
                        .await());

        assertThat(outbox.posted()).singleElement()
                                   .isInstanceOfSatisfying(Message.Heartbeat.class, heartbeat -> {
                                       assertThat(heartbeat.running()).containsExactly(new UnitRefDto(9, "main", 1));
                                       assertThat(heartbeat.facts()
                                                           .cores()).isEqualTo(3);
                                   });
        assertThat(sampler.lastSample("cid")
                          .flatMap(CgroupSampler.CgroupSample::memoryPeakBytes)).isEqualTo(Option.some(42L));
    }

    /// Protocol v3: each running unit is named in the attempt its directory holds (none recorded: a pre-M4 unit, attempt
    /// 1); a unit whose `attempt` does not parse is left out of the beat rather than named in a guessed attempt.
    @Test
    void beat_namesEachUnitInItsAttempt_andLeavesOutAnUnreadableOne(@TempDir Path root) {
        var registry = UnitRegistry.unitRegistry();
        var outbox = new RecordingOutbox();
        var paths = WorkerPaths.workerPaths(root.resolve("temper"));
        var files = UnitFiles.unitFiles(paths);
        var facts = HostFacts.hostFacts(1, 2, 3, 0, 1, none(), none(), 4, 5, 6, Instant.EPOCH, List.of());

        registry.startWatching(Fixtures.ref(10, "main"), 1, "c10");
        registry.startWatching(Fixtures.ref(11, "main"), 1, "c11");
        registry.startWatching(Fixtures.ref(12, "main"), 1, "c12");
        ok(files.store(Fixtures.ref(10, "main"), UnitFile.ATTEMPT, "3"));
        ok(files.store(Fixtures.ref(11, "main"), UnitFile.ATTEMPT, "three"));

        ok(HeartbeatTask.heartbeatTask(registry,
                                       CgroupSampler.cgroupSampler(root, () -> Instant.EPOCH),
                                       () -> Promise.success(facts),
                                       outbox,
                                       files,
                                       HoldMonitor.holdMonitor(Fixtures.noHolders(paths, files),
                                                               DockerCli.dockerCli(new FakeRunner(), Map.of(), Fixtures.fastTimings()),
                                                               files,
                                                               registry,
                                                               Fixtures.reap(new FakeRunner()),
                                                               Fixtures.fastHold()
                                                                       .observationGap()),
                                       0)
                        .beat()
                        .await());

        assertThat(outbox.posted()).singleElement()
                                   .isInstanceOfSatisfying(Message.Heartbeat.class,
                                                           heartbeat -> assertThat(heartbeat.running()).containsExactlyInAnyOrder(new UnitRefDto(10, "main", 3),
                                                                                                                               new UnitRefDto(12, "main", 1)));
    }
}
