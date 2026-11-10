package org.pragmatica.temper.worker.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The startup scan (m0-design §7.2 step 3) that `Hello` carries for reconcile; a running container's
/// `temper.instance` label rides along (F2). The unit's `.temper/attempt` too (protocol v3): none for a directory without
/// one (pre-M4, unit 2) or with an unreadable one (unit 4, logged).
class StateScanTest {
    @Test
    void scan_reportsMarkerRcAndContainerState_perUnitDirectory(@TempDir Path home) throws IOException {
        var paths = WorkerPaths.workerPaths(home);
        var files = UnitFiles.unitFiles(paths);
        var runner = new FakeRunner();

        ok(files.store(Fixtures.ref(1, "main"), UnitFile.MARKER, "1"));
        ok(files.store(Fixtures.ref(1, "main"), UnitFile.RC, "0"));
        ok(files.store(Fixtures.ref(1, "main"), UnitFile.ATTEMPT, "2"));
        ok(files.store(Fixtures.ref(2, "main"), UnitFile.MARKER, "1"));
        Files.createDirectories(paths.unitDir(Fixtures.ref(3, "main")));
        ok(files.store(Fixtures.ref(4, "main"), UnitFile.RC, "garbage"));
        ok(files.store(Fixtures.ref(4, "main"), UnitFile.ATTEMPT, "garbage"));
        Files.createDirectories(paths.jobsDir()
                                     .resolve("not-a-job/units/main"));
        Files.createDirectories(paths.jobsDir()
                                     .resolve("5/units/Bad_Name"));
        runner.on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "temper-1-main"), 0, "exited\n", "")
              .on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "temper-2-main"), 0, "running\n", "")
              .on(List.of("docker", "inspect", "--type", "container", "-f", "{{index .Config.Labels \"temper.instance\"}}", "temper-2-main"), 0, "acceptance\n", "")
              .on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "temper-3-main"), 1, "", "Error: No such object: temper-3-main")
              .on(List.of("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", "temper-4-main"), 1, "", "Error: No such object");

        var units = ok(StateScan.stateScan(paths, files, DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), Fixtures.noHolders(paths, files), Fixtures.fastHold().absentRecheck())
                                .scan()
                                .await())
                                .stream()
                                .sorted(Comparator.comparingLong(DiskUnitDto::job))
                                .toList();

        assertThat(units).containsExactly(DiskUnitDto.diskUnitDto(1, "main", true, Option.some(0), ContainerStatus.EXITED, Option.none(), Option.none(), Option.none(), Option.some(2)),
                                          DiskUnitDto.diskUnitDto(2, "main", true, Option.none(), ContainerStatus.RUNNING, Option.none(), Option.none(), Option.some("acceptance"), Option.none()),
                                          DiskUnitDto.diskUnitDto(3, "main", false, Option.none(), ContainerStatus.ABSENT, Option.none(), Option.none(), Option.none(), Option.none()),
                                          DiskUnitDto.diskUnitDto(4, "main", false, Option.none(), ContainerStatus.ABSENT, Option.none(), Option.none(), Option.none(), Option.none()));
    }

    @Test
    void scan_isEmpty_onAFreshHost(@TempDir Path home) {
        var paths = WorkerPaths.workerPaths(home);

        assertThat(ok(StateScan.stateScan(paths, UnitFiles.unitFiles(paths), DockerCli.dockerCli(new FakeRunner(), Map.of(), Fixtures.fastTimings()), Fixtures.noHolders(paths, UnitFiles.unitFiles(paths)), Fixtures.fastHold().absentRecheck())
                               .scan()
                               .await())).isEmpty();
    }

    @Test
    void scan_fails_whenDockerItselfFails(@TempDir Path home) {
        var paths = WorkerPaths.workerPaths(home);
        var files = UnitFiles.unitFiles(paths);
        var runner = new FakeRunner().on(List.of("docker", "inspect"), 1, "", "Cannot connect to the Docker daemon");

        ok(files.store(Fixtures.ref(1, "main"), UnitFile.MARKER, "1"));
        Fixtures.failure(StateScan.stateScan(paths, files, DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), Fixtures.noHolders(paths, files), Fixtures.fastHold().absentRecheck())
                                  .scan()
                                  .await());
    }
}
