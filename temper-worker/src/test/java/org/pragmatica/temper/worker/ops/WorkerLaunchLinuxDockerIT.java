package org.pragmatica.temper.worker.ops;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerAvailable;
import org.pragmatica.temper.worker.docker.DockerSupport;
import org.pragmatica.temper.worker.unit.DockerRunArgs;
import org.pragmatica.temper.worker.unit.RunnerImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// `ops/worker-launch` on Linux (the runner image: Ubuntu, GNU coreutils, bash, java), where `run` executes the very
/// private copy it hashed (`~/.temper/run/worker-<ver>.<pid>.jar`).
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class WorkerLaunchLinuxDockerIT {
    record Outcome(int rc, String output) {}

    @Test
    void installThenRun_onLinux_executesTheHashedCopy() throws Exception {
        var version = DockerSupport.runnerVersion();
        var image = DockerRunArgs.image(version);
        var scratch = DockerSupport.scratch("launch");

        ok(RunnerImage.runnerImage(DockerSupport.docker(), WorkerPaths.workerPaths(scratch.resolve("build")))
                      .ensure(version)
                      .await());

        var home = Files.createDirectories(scratch.resolve("home"));
        var jar = WorkerLaunchTest.helloJar();
        var sha = HexFormat.of()
                           .formatHex(MessageDigest.getInstance("SHA-256")
                                                   .digest(jar));
        var script = Path.of("..", "ops", "worker-launch")
                         .toAbsolutePath()
                         .normalize();

        assertThat(launch(image, script, home, "install 2.0 " + sha, jar).rc()).isZero();

        var run = launch(image, script, home, "run 2.0", new byte[0]);

        assertThat(run.rc()).as(run.output())
                            .isZero();
        assertThat(run.output()).contains("hello-from-worker file:/h/.temper/run/worker-2.0.");

        // Tamper from inside the VM: a host-side write may sit in the shared-folder cache the container reads.
        DockerSupport.run("docker", "run", "--rm", "--user", owner(home), "-v", home + ":/h", image, "truncate", "-s", "-1", "/h/.temper/worker-2.0.jar");
        assertThat(launch(image, script, home, "run 2.0", new byte[0]).rc()).isEqualTo(65);
        assertThat(launch(image, script, home, "install 3.0 " + "1".repeat(64), jar).rc()).isEqualTo(65);
        assertThat(home.resolve(".temper/worker-3.0.jar")).doesNotExist();
    }

    /// The evidence verb with GNU coreutils (`stat -c`, `xargs -r`, GNU tar): the small set streams, a symlinked
    /// unit directory is refused, the cap holds.
    @Test
    void evidence_onLinux_streamsTheSmallSet_refusesALink_andEnforcesTheCap() throws Exception {
        var version = DockerSupport.runnerVersion();
        var image = DockerRunArgs.image(version);
        var scratch = DockerSupport.scratch("evidence");

        ok(RunnerImage.runnerImage(DockerSupport.docker(), WorkerPaths.workerPaths(scratch.resolve("build")))
                      .ensure(version)
                      .await());

        var home = Files.createDirectories(scratch.resolve("home"));
        var unit = WorkerLaunchEvidenceTest.unitTree(home, home.resolve("outside.txt"));
        var script = Path.of("..", "ops", "worker-launch")
                         .toAbsolutePath()
                         .normalize();

        Files.createSymbolicLink(home.resolve(".temper/jobs/7/units/escape"), Path.of("/etc"));

        var small = evidence(image, script, home, "evidence 7 main small");

        assertThat(small.rc()).as(small.stderr())
                              .isZero();
        assertThat(WorkerLaunchEvidenceTest.members(small.stdout())).contains(".temper/marker", "log.tail", "manifest/manifest.json")
                                                                    .doesNotContain("log");
        assertThat(evidence(image, script, home, "evidence 7 escape full").rc()).isEqualTo(64);

        try (var file = new java.io.RandomAccessFile(unit.resolve("reports/huge.xml")
                                                         .toFile(), "rw")) {
            file.setLength(256L * 1024 * 1024 + 1);
        }

        assertThat(evidence(image, script, home, "evidence 7 main small").rc()).isEqualTo(65);
    }

    private static WorkerLaunchEvidenceTest.Outcome evidence(String image, Path script, Path home, String command) throws IOException,
                                                                                                                      InterruptedException {
        var process = HermeticProcess.builder(List.of("docker",
                                                 "run",
                                                 "-i",
                                                 "--rm",
                                                 "--user",
                                                 owner(home),
                                                 "-v",
                                                 script + ":/wl/worker-launch:ro",
                                                 "-v",
                                                 home + ":" + home,
                                                 "-e",
                                                 "HOME=" + home,
                                                 "-e",
                                                 "SSH_ORIGINAL_COMMAND=" + command,
                                                 image,
                                                 "bash",
                                                 "/wl/worker-launch")).start();

        process.getOutputStream()
               .close();

        var stdout = process.getInputStream()
                            .readAllBytes();
        var stderr = new String(process.getErrorStream()
                                       .readAllBytes(), StandardCharsets.UTF_8);

        return new WorkerLaunchEvidenceTest.Outcome(process.waitFor(), stdout, stderr);
    }

    private static String owner(Path path) throws IOException {
        return Files.getAttribute(path, "unix:uid") + ":" + Files.getAttribute(path, "unix:gid");
    }

    private static Outcome launch(String image, Path script, Path home, String command, byte[] stdin) throws IOException,
                                                                                                         InterruptedException {
        var uid = owner(home);
        var process = HermeticProcess.builder(List.of("docker",
                                                 "run",
                                                 "-i",
                                                 "--rm",
                                                 "--user",
                                                 uid,
                                                 "-v",
                                                 script + ":/wl/worker-launch:ro",
                                                 "-v",
                                                 home + ":/h",
                                                 "-e",
                                                 "HOME=/h",
                                                 "-e",
                                                 "SSH_ORIGINAL_COMMAND=" + command,
                                                 image,
                                                 "bash",
                                                 "/wl/worker-launch")).redirectErrorStream(true)
                                                                      .start();

        try (var in = process.getOutputStream()) {
            in.write(stdin);
        }

        var output = new String(process.getInputStream()
                                       .readAllBytes(), StandardCharsets.UTF_8);

        return new Outcome(process.waitFor(), output);
    }
}
