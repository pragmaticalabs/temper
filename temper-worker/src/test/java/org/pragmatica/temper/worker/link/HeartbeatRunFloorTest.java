package org.pragmatica.temper.worker.link;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.unit.HoldMonitor;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The heartbeat's in-run duties (m1-design §4.5, maintainer ruling M1-10): below the 10 GiB run floor every running
/// unit is marked `void=disk` once and NOT killed; the lowest `MemAvailable` is kept per unit; units waiting in
/// bb-hold are leased too (§1.3).
class HeartbeatRunFloorTest {
    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path home;

    private final FakeRunner runner = new FakeRunner();
    private final UnitRegistry registry = UnitRegistry.unitRegistry();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final AtomicReference<HostFacts> host = new AtomicReference<>();
    private final UnitRef running = Fixtures.ref(7, "main");
    private UnitFiles files;

    @BeforeEach
    void setUp() {
        files = UnitFiles.unitFiles(WorkerPaths.workerPaths(home.resolve(".temper")));
        registry.startWatching(running, 1, "cid");
        facts(500 * GIB, 500 * GIB, 4000);
    }

    private void facts(long freeDockerRoot, long freeWorkspace, long memAvailableKb) {
        host.set(HostFacts.hostFacts(1, 1, 8, 0, 1, none(), none(), memAvailableKb, freeDockerRoot, freeWorkspace, Instant.EPOCH, List.of()));
    }

    private void beat() {
        var docker = DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings());

        ok(HeartbeatTask.heartbeatTask(registry,
                                       CgroupSampler.cgroupSampler(home, () -> Instant.EPOCH),
                                       () -> Promise.success(host.get()),
                                       outbox,
                                       files,
                                       HoldMonitor.holdMonitor(Fixtures.noHolders(files), docker, files, registry, Fixtures.reap(runner), Fixtures.fastHold()
                                                                                                                     .observationGap()),
                                       WorkerConfig.DEFAULT_RUN_FLOOR_BYTES)
                        .beat()
                        .await());
    }

    @Test
    void belowTheFloor_onEitherFilesystem_marksVoidDiskOnce_andKillsNothing() {
        facts(500 * GIB, 9 * GIB, 4000);
        beat();
        beat();

        assertThat(ok(files.text(running, UnitFile.VOID))).isEqualTo(some("disk"));
        assertThat(runner.callsStartingWith(List.of("docker", "kill"))).isEmpty();
        assertThat(outbox.posted()).hasSize(2);

        var other = Fixtures.ref(8, "main");

        registry.startWatching(other, 1, "cid8");
        facts(9 * GIB, 500 * GIB, 4000);
        beat();

        assertThat(ok(files.text(other, UnitFile.VOID))).isEqualTo(some("disk"));
    }

    @Test
    void aboveTheFloor_marksNothing() {
        beat();

        assertThat(files.exists(running, UnitFile.VOID)).isFalse();
    }

    /// The floor itself is not below the floor, on either filesystem.
    @Test
    void exactlyAtTheFloor_marksNothing() {
        facts(WorkerConfig.DEFAULT_RUN_FLOOR_BYTES, WorkerConfig.DEFAULT_RUN_FLOOR_BYTES, 4000);
        beat();

        assertThat(files.exists(running, UnitFile.VOID)).isFalse();
    }

    /// Every beat runs the `HoldMonitor` round of the held units: a held unit whose holder is gone while its
    /// container runs is voided `port-group-lost` and killed.
    @Test
    void aBeat_runsTheHoldRound_ofEveryHeldUnit() {
        var held = Fixtures.ref(9, "gate");

        ok(files.store(held, UnitFile.BBHOLD_PID, "4242 77"));
        registry.hold(held, "hcid");
        runner.on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "hcid"), 0, "running\n", "");
        beat();

        assertThat(ok(files.text(held, UnitFile.VOID))).isEqualTo(some("port-group-lost"));
        assertThat(runner.callsStartingWith(List.of("docker", "kill", "hcid"))).hasSize(1);
    }

    /// The first writer wins: a unit already voided for its lost port group stays `port-group-lost`.
    @Test
    void anEarlierVoid_isKept() {
        ok(files.store(running, UnitFile.VOID, "port-group-lost"));
        facts(GIB, GIB, 4000);
        beat();

        assertThat(ok(files.text(running, UnitFile.VOID))).isEqualTo(some("port-group-lost"));
    }

    @Test
    void theLowestMemAvailable_isKeptPerUnit() {
        beat();
        facts(500 * GIB, 500 * GIB, 1500);
        beat();
        facts(500 * GIB, 500 * GIB, 3000);
        beat();

        assertThat(ok(files.count(running, UnitFile.MEM_AVAILABLE_MIN))).isEqualTo(some(1500L));
    }

    @Test
    void aUnitWaitingInBbHold_isLeased() {
        registry.startHolding(Fixtures.ref(9, "gate"), 1);
        beat();

        assertThat(outbox.posted()).singleElement()
                                   .isInstanceOfSatisfying(Message.Heartbeat.class,
                                                           heartbeat -> assertThat(heartbeat.running()).containsExactlyInAnyOrder(new UnitRefDto(7, "main", 1),
                                                                                                                                  new UnitRefDto(9, "gate", 1)));
    }
}
