package org.pragmatica.temper.worker.docker;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.RecordingOutbox;
import org.pragmatica.temper.worker.WorkerAssembly;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.link.Reply;
import org.pragmatica.temper.worker.unit.DockerRunArgs;
import org.pragmatica.temper.worker.unit.RunnerImage;
import org.pragmatica.temper.worker.unit.Timers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// A unit end to end against a real docker daemon (A10 worker side, locally): `StartUnit` creates the per-unit
/// network and a detached container with no published port; the timer fires at the bound, dumps every JVM with
/// `jcmd` BEFORE `docker kill`; the exit is collected as rc 137 / `killedBy=timeout`; `UnitReportAck` removes the
/// container and the network. Uses the real runner image ([RunnerImageDockerIT] builds the same version).
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class UnitLifecycleDockerIT {
    private static final String SLEEPER = """
        public class Sleep {
            public static void main(String[] args) throws Exception {
                System.out.println("sleeping");
                Thread.sleep(600_000);
            }
        }
        """;

    @Test
    void unit_timesOut_withThreadDumps_thenIsCollectedAndRemoved() throws Exception {
        var version = DockerSupport.runnerVersion();
        var image = DockerRunArgs.image(version);
        var scratch = DockerSupport.scratch("lifecycle");
        var paths = WorkerPaths.workerPaths(scratch.resolve(".temper"));
        var docker = DockerSupport.docker();

        ok(RunnerImage.runnerImage(docker, paths)
                      .ensure(version)
                      .await());

        var home = Path.of(System.getProperty("user.home"));
        var dockerGid = Integer.parseInt(DockerSupport.run("docker", "run", "--rm", "-v", "/var/run/docker.sock:/s", image, "stat", "-c", "%g", "/s"));
        var proc = Files.createDirectories(scratch.resolve("proc"));

        Files.writeString(proc.resolve("loadavg"), "0.1 0.2 0.3 1/1 1\n");
        Files.writeString(proc.resolve("meminfo"), "MemAvailable: 5 kB\n");

        var config = new WorkerConfig(paths,
                                      "it",
                                      "local",
                                      (Integer) Files.getAttribute(home, "unix:uid"),
                                      (Integer) Files.getAttribute(home, "unix:gid"),
                                      dockerGid,
                                      WorkerConfig.DOCKER_SOCKET,
                                      1,
                                      WorkerConfig.toolEnv(System.getenv()),
                                      proc,
                                      scratch.resolve("no-cgroup"),
                                      Option.some(scratch),
                                      ok(WorkerConfig.bbQueue(Map.of(), scratch)),
                                      scratch.resolve("no-bb-hold"),
                                      1,
                                      DockerSupport.timings());
        var outbox = new RecordingOutbox();
        var router = ok(WorkerAssembly.assemble(config,
                                                ProcessRunner.processRunner(),
                                                TemperJson.mapper(),
                                                outbox,
                                                InstantSource.system(),
                                                Timers.shared())
                                      .await()).router();
        var job = 900_000_000L + System.nanoTime() % 1_000_000L;
        var ref = Fixtures.ref(job, "main");
        var workspace = Files.createDirectories(paths.workspaceHome(ref.job()))
                             .getParent();
        var sleeper = Files.writeString(workspace.resolve("Sleep.java"), SLEEPER);

        try {
            var reply = ok(router.route(new Message.StartUnit(job,
                                                              "main",
                                                              1,
                                                              "MVN",
                                                              List.of(List.of("java", sleeper.toString())),
                                                              Duration.ofSeconds(25),
                                                              1024,
                                                              512,
                                                              version,
                                                              Option.none(),
                                                              Option.none(),
                                                              Option.none(),
                                                              Option.none(), "temper"))
                                 .await());

            assertThat(reply).isInstanceOfSatisfying(Reply.Respond.class,
                                                     respond -> assertThat(respond.message()).isInstanceOf(Message.UnitStarted.class));
            assertThat(DockerSupport.run("docker", "inspect", "--type", "container", "-f", "{{.State.Status}}", ref.containerName())).isEqualTo("running");
            assertThat(DockerSupport.run("docker", "inspect", "--type", "container", "-f", "{{.HostConfig.NetworkMode}}", ref.containerName())).isEqualTo(ref.networkName());
            assertThat(DockerSupport.run("docker", "inspect", "--type", "container", "-f", "{{json .HostConfig.PortBindings}}", ref.containerName())).isIn("{}", "null");
            assertThat(DockerSupport.run("docker", "inspect", "--type", "container", "-f", "{{.HostConfig.PublishAllPorts}}", ref.containerName())).isEqualTo("false");

            var exited = awaitExit(outbox);

            assertThat(exited.rc()).isEqualTo(137);
            assertThat(exited.killedBy()).isEqualTo(Option.some(KilledBy.TIMEOUT));
            assertThat(exited.threadDumps()).as("RunSteps and the sleeping JVM")
                                            .isGreaterThanOrEqualTo(2);
            try (var dumps = Files.list(paths.threadDumpsDir(ref))) {
                assertThat(dumps.filter(file -> file.toString()
                                                    .endsWith(".txt"))
                                .toList()).allSatisfy(file -> assertThat(file).content()
                                                                              .contains("Full thread dump"));
            }
            assertThat(paths.logFile(ref)).content()
                                          .contains("sleeping");
            assertThat(outbox.awaitPosted(Message.UnitReport.class, 1)).last()
                                                                       .satisfies(chunk -> assertThat(chunk.last()).isTrue());

            assertThat(ok(router.route(new Message.UnitReportAck(job, "main", 1))
                                .await())).isEqualTo(Reply.silent());
            assertThat(exists("container", ref)).isFalse();
            assertThat(exists("network", ref)).isFalse();
        } finally {
            cleanUp(ref);
        }
    }

    private static Message.UnitExited awaitExit(RecordingOutbox outbox) throws InterruptedException {
        for (var wait = 0; wait < 240; wait++) {
            var exited = outbox.awaitPosted(Message.UnitExited.class, 1);

            if (!exited.isEmpty()) {
                return exited.getFirst();
            }
        }
        throw new AssertionError("no UnitExited within the wait: " + outbox.posted());
    }

    private static boolean exists(String kind, UnitRef ref) throws Exception {
        var process = HermeticProcess.builder("docker", kind, "inspect", ref.containerName()).redirectErrorStream(true)
                                                                                     .start();

        process.getInputStream()
               .readAllBytes();
        return process.waitFor() == 0;
    }

    private static void cleanUp(UnitRef ref) throws Exception {
        HermeticProcess.builder("docker", "rm", "-f", ref.containerName()).start()
                                                                     .waitFor();
        HermeticProcess.builder("docker", "network", "rm", ref.networkName()).start()
                                                                        .waitFor();
    }
}
