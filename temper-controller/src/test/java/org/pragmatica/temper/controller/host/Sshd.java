package org.pragmatica.temper.controller.host;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assumptions;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import com.github.dockerjava.api.model.Capability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/// A real `sshd` in a container (alpine + openssh-server, host keys generated at start) and a [ProcessRunner] that
/// runs the real `ssh` client against it: the link's argv is kept whole, only pointed at the container (`-p <port>`)
/// (`container.getHost()`, the gateway inside a bb netns, not `localhost`) and at this test's `known_hosts` and key. Every spawn is timed ([#spawned]). Needs docker and an `ssh` client;
/// without docker the test is skipped (reported as skipped, never as passed).
final class Sshd implements AutoCloseable {
    /// [LinkHarness]'s host as configured; [#pointed] redirects its argv to the container.
    static final ControllerConfig.Host HOST = ControllerConfig.Host.host(LinkHarness.HOST,
                                                                         "localhost",
                                                                         "builder",
                                                                         Path.of("/home/builder/.temper/key"),
                                                                         Path.of("/home/builder/.temper/known_hosts"),
                                                                         5,
                                                                         20480,
                                                                         false);

    final GenericContainer<?> container;
    final Path dir;
    final Path knownHosts;
    final Path key;
    final List<Timed> spawned = new CopyOnWriteArrayList<>();
    /// The mapped port, kept after the container stops.
    final int port;

    /// One spawn: when it started and, once it exited, when.
    static final class Timed {
        final Instant started = Instant.now();
        volatile Instant exited;
    }

    Sshd() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "docker is not available");
        dir = Files.createTempDirectory("temper-sshd");
        knownHosts = dir.resolve("known_hosts");
        key = dir.resolve("id_ed25519");
        Files.writeString(knownHosts, "");
        keygen(key);
        container = new GenericContainer<>(new ImageFromDockerfile("temper-test-sshd-iptables", false)
                                               .withDockerfileFromBuilder(builder -> builder.from("alpine:3.20")
                                                                                            .run("apk add --no-cache openssh-server iptables")
                                                                                            .cmd("sh", "-c", "ssh-keygen -A && exec /usr/sbin/sshd -D -e")
                                                                                            .build()))
            .withExposedPorts(22)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN))
            .waitingFor(Wait.forListeningPort());
        container.start();
        port = container.getMappedPort(22);
    }

    /// A deterministic blackhole on any docker host: sshd's own network namespace drops every packet to port 22, so
    /// nothing answers (no RST, no banner) whatever docker's port forwarding is (DNAT or its userland proxy, which
    /// accepts and then never gets through). Fails loudly when the rule cannot be set (nft, else legacy iptables).
    void blackhole() throws IOException, InterruptedException {
        var result = container.execInContainer("sh", "-c", "iptables -I INPUT -p tcp --dport 22 -j DROP"
                                                          + " || iptables-legacy -I INPUT -p tcp --dport 22 -j DROP");
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("cannot blackhole sshd: " + result.getStderr());
        }
    }

    int port() {
        return port;
    }

    /// A fresh ed25519 key pair at `path` (no passphrase).
    static void keygen(Path path) throws IOException, InterruptedException {
        var process = HermeticProcess.builder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", path.toString()).inheritIO().start();
        if (process.waitFor() != 0) {
            throw new IOException("ssh-keygen failed");
        }
    }

    /// `known_hosts` pinning `publicKey` (K1) for the container's address; the container answers with its own key (K2).
    void pin(Path publicKey) throws IOException {
        var parts = Files.readString(publicKey).trim().split(" ");
        Files.writeString(knownHosts, "[" + container.getHost() + "]:" + port() + " " + parts[0] + " " + parts[1] + "\n");
    }

    /// The link's argv, pointed at the container.
    List<String> pointed(List<String> argv) {
        var out = new ArrayList<String>();
        for (var index = 0; index < argv.size(); index++) {
            var arg = argv.get(index);
            if (arg.startsWith("UserKnownHostsFile=")) {
                out.add("UserKnownHostsFile=" + knownHosts);
            } else if (arg.equals("-i")) {
                out.add("-i");
                out.add(key.toString());
                index++;
            } else if (arg.contains("@")) {
                out.add("-p");
                out.add(String.valueOf(port()));
                out.add(arg.substring(0, arg.indexOf('@') + 1) + container.getHost());
            } else {
                out.add(arg);
            }
        }
        return out;
    }

    /// The real runner, through [#pointed].
    ProcessRunner runner() {
        var real = ProcessRunner.processRunner();
        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return Command.command(pointed(command.argv()), Map.of(), command.timeout())
                              .map(Command::inheritingEnvironment)
                              .async()
                              .flatMap(real::run);
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                var timed = new Timed();
                return Command.command(pointed(command.argv()), Map.of(), command.timeout())
                              .map(Command::inheritingEnvironment)
                              .flatMap(real::spawn)
                              .onSuccess(process -> {
                                  spawned.add(timed);
                                  process.exit().onResultRun(() -> timed.exited = Instant.now());
                              });
            }
        };
    }

    /// The real client once, as the link runs it: its whole stderr.
    String once(List<String> argv) throws IOException {
        var process = runner().spawn(Command.command(argv, Map.of(), TimeSpan.timeSpan(60).seconds()).unwrap()).unwrap();
        process.stdin().close();
        var stderr = new String(process.stderr().readAllBytes(), StandardCharsets.UTF_8);
        process.exit().await(TimeSpan.timeSpan(90).seconds());
        return stderr;
    }

    static Duration took(Timed timed) {
        return Duration.between(timed.started, timed.exited);
    }

    @Override
    public void close() {
        container.stop();
    }
}
