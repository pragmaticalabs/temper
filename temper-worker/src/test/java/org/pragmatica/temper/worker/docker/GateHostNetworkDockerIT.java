package org.pragmatica.temper.worker.docker;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.File;
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

/// A Gate unit on the host network, end to end against a real docker daemon (m1-design §14, T1 run 2): the unit's
/// own program uses Testcontainers as pragmatica's `AwsLocalStackContractTest` does. It starts a container, reaches
/// it through `getHost()` and the mapped port, then joins `getHost()` to the container-INTERNAL port and fetches
/// that URL from inside the started container, which works only when `getHost()` is `localhost` (bb's host build).
/// Under the bridged args (`TESTCONTAINERS_HOST_OVERRIDE=<unit network gateway>`) the in-container fetch fails and
/// the unit exits non-zero.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class GateHostNetworkDockerIT {
    private static final String PROBE = """
        import org.testcontainers.containers.GenericContainer;
        import org.testcontainers.utility.DockerImageName;

        public class Probe {
            public static void main(String[] args) throws Exception {
                try (var server = new GenericContainer<>(DockerImageName.parse(args[0])).withExposedPorts(8000)
                                                                                       .withCommand("python3", "-m", "http.server", "8000")) {
                    server.start();
                    var host = server.getHost();
                    System.out.println("getHost=" + host);
                    try (var socket = new java.net.Socket(host, server.getMappedPort(8000))) {
                        System.out.println("mapped port reachable");
                    }
                    var internal = server.execInContainer("python3", "-c",
                        "import urllib.request; urllib.request.urlopen('http://" + host + ":8000/', timeout=5); print('internal port via getHost ok')");
                    System.out.println(internal.getStdout() + internal.getStderr());
                    System.exit(internal.getExitCode());
                }
            }
        }
        """;

    @Test
    void gateUnit_runsOnTheHostNetwork_soTestcontainersGetHostIsLocalhost() throws Exception {
        var version = DockerSupport.runnerVersion();
        var image = DockerRunArgs.image(version);
        var scratch = DockerSupport.scratch("gate-host");
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
        var job = 910_000_000L + System.nanoTime() % 1_000_000L;
        var ref = Fixtures.ref(job, "gate");
        var workspace = Files.createDirectories(paths.workspaceHome(ref.job()))
                             .getParent();
        var lib = testcontainersCopiedTo(Files.createDirectories(workspace.resolve("lib")));
        var probe = Files.writeString(workspace.resolve("Probe.java"), PROBE);

        try {
            var reply = ok(router.route(new Message.StartUnit(job,
                                                              "gate",
                                                              1,
                                                              "GATE_UNSHARDED",
                                                              List.of(List.of("java", "-cp", lib + "/*", probe.toString(), image)),
                                                              Duration.ofMinutes(5),
                                                              1024,
                                                              512,
                                                              version,
                                                              Option.none(),
                                                              Option.none(),
                                                              Option.none(),
                                                              Option.none(),
                                                              "temper"))
                                 .await());

            assertThat(reply).isInstanceOfSatisfying(Reply.Respond.class,
                                                     respond -> assertThat(respond.message()).isInstanceOf(Message.UnitStarted.class));
            assertThat(DockerSupport.run("docker", "inspect", "--type", "container", "-f", "{{.HostConfig.NetworkMode}}", ref.containerName())).isEqualTo("host");
            assertThat(exists("network", ref)).as("a Gate unit creates no network")
                                              .isFalse();

            var exited = awaitExit(outbox);

            assertThat(paths.logFile(ref)).content()
                                          .contains("getHost=localhost", "mapped port reachable", "internal port via getHost ok");
            assertThat(exited.rc()).isZero();
            assertThat(ok(router.route(new Message.UnitReportAck(job, "gate", 1))
                                .await())).isEqualTo(Reply.silent());
            assertThat(exists("container", ref)).isFalse();
        } finally {
            cleanUp(ref);
        }
    }

    /// The test classpath's jars (Testcontainers and its dependencies), without logback (the unit's program logs
    /// through slf4j's no-op provider), copied into the workspace the unit mounts.
    private static Path testcontainersCopiedTo(Path lib) throws Exception {
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));

        for (var entry : classpath.split(File.pathSeparator)) {
            var jar = Path.of(entry);

            if (entry.endsWith(".jar") && !jar.getFileName()
                                               .toString()
                                               .startsWith("logback")) {
                Files.copy(jar, lib.resolve(jar.getFileName()));
            }
        }
        return lib;
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
