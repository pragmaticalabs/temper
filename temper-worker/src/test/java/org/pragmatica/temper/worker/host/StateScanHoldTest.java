package org.pragmatica.temper.worker.host;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.HolderStatusDto;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.ProcFixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.HolderProbe;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The startup scan of hold units (m1-design §1.5): the holder status and the grant ticket split reconcile rules
/// 3-5; a dead holder's absent container is inspected a second time before it is reported.
class StateScanHoldTest {
    private static final List<String> STATUS = List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "temper-5-gate");

    @TempDir
    Path home;

    private WorkerPaths paths;
    private UnitFiles files;
    private Path proc;
    private final FakeRunner runner = new FakeRunner();
    private final UnitRef ref = Fixtures.ref(5, "gate");

    @BeforeEach
    void setUp() {
        paths = WorkerPaths.workerPaths(home.resolve(".temper"));
        files = UnitFiles.unitFiles(paths);
        proc = home.resolve("proc");
        ok(files.store(ref, UnitFile.MARKER, "1"));
        ok(files.store(ref, UnitFile.BBHOLD_PID, "4242 77"));
    }

    private DiskUnitDto scanned() {
        return ok(StateScan.stateScan(paths,
                                      files,
                                      DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()),
                                      HolderProbe.procHolderProbe(proc, files),
                                      Fixtures.fastHold()
                                              .absentRecheck())
                           .scan()
                           .await()).getFirst();
    }

    @Test
    void aWaitingHolder_isReported_withNoTicket() throws Exception {
        var process = Files.createDirectories(proc.resolve("4242"));

        Files.writeString(process.resolve("stat"), ProcFixtures.stat(4242, 77));
        Files.write(process.resolve("cmdline"), ProcFixtures.cmdline("temper-5-gate"));
        ok(files.store(ref, UnitFile.BBHOLD_ERR, "bb-hold: queued ticket 1760-4242\n"));
        runner.on(STATUS, 1, "", "Error: No such object");

        assertThat(scanned()).isEqualTo(DiskUnitDto.diskUnitDto(5,
                                                                "gate",
                                                                true,
                                                                Option.none(),
                                                                ContainerStatus.ABSENT,
                                                                Option.some(HolderStatusDto.WAITING),
                                                                Option.none(), Option.none(), Option.none()));
        assertThat(runner.callsStartingWith(STATUS)).hasSize(1);
    }

    /// Dead and granted, no container: inspected twice (a `docker run` child may outlive bb-hold by a moment),
    /// then reported with its ticket (reconcile rule 5).
    @Test
    void aDeadGrantedHolder_withNoContainer_isInspectedTwice_andCarriesItsTicket() {
        ok(files.store(ref, UnitFile.BBHOLD_ERR, "granted 1760-4242\n"));
        runner.on(STATUS, 1, "", "Error: No such object");

        assertThat(scanned()).extracting(DiskUnitDto::holder, DiskUnitDto::ticket, DiskUnitDto::container)
                             .containsExactly(Option.some(HolderStatusDto.DEAD), Option.some("1760-4242"), ContainerStatus.ABSENT);
        assertThat(runner.callsStartingWith(STATUS)).hasSize(2);
    }

    /// A corrupt `bbhold.pid` cannot identify a holder: it is reported dead (never as no holder at all, which would
    /// hide the hold unit from reconcile), and the scan goes on.
    @Test
    void aCorruptPidFile_isReportedDead() {
        ok(files.store(ref, UnitFile.BBHOLD_PID, "not a pid"));
        runner.on(STATUS, 1, "", "Error: No such object");

        assertThat(scanned().holder()).isEqualTo(Option.some(HolderStatusDto.DEAD));
    }

    @Test
    void theSecondInspect_reportsAContainerThatAppeared() {
        ok(files.store(ref, UnitFile.BBHOLD_ERR, "granted 1760-4242\n"));
        runner.on(STATUS, 1, "", "Error: No such object");
        runner.on(STATUS,
                  argv -> runner.callsStartingWith(STATUS)
                                .size() < 2
                          ? Result.success(FakeRunner.completed(1, "", "Error: No such object"))
                          : Result.success(FakeRunner.completed(0, "running", "")));

        assertThat(scanned().container()).isEqualTo(ContainerStatus.RUNNING);
    }
}
