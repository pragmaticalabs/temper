package org.pragmatica.kiln.service.scan;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.store.HostCounts;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPgFixture;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/// K-5 (kiln-f1-design §7.2): a host that stops answering changes no row and no count. A real `sshd` in docker (alpine,
/// openssh, bash, git; as temper's `controller/host/Sshd`) whose only key is bound to the real `kiln-scan` by a forced
/// command; [HostScanner] reaches it through [SshScanTransport] and the real `ssh` client. Scan once (two checkouts),
/// stop the container, scan again (hot): the source reads `UNREACHABLE`; the checkout rows and the counts are those of
/// the first scan; `host_pass` keeps the full pass's row as it was (COMPLETE, rows 2) and records the failed hot pass as
/// `UNREACHABLE` with rows 0 (`passFailed` writes the pass, never a checkout row). The roots are absolute, so a wrongly COMPLETE empty listing would delete. Without docker the
/// test is skipped (reported as skipped, never as passed).
///
/// Mutation (recorded in the WP8 state file): map a transport failure in [HostScanner] to
/// `ScanOutcome.Complete(List.of(), 0, none())`: the second pass deletes both rows and the count reads 0.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class UnreachableHostIT {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(120).seconds();
    private static final HostName BIGBOY = HostName.hostName("bigboy").unwrap();
    private static final String CO = "/home/builder/work/co";
    private static final List<String> ROOTS = List.of(CO + "/*");

    @TempDir
    Path dir;

    private GenericContainer<?> container;
    private KilnStore store;

    @BeforeEach
    void start() throws IOException, InterruptedException {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "docker is not available");
        var key = dir.resolve("kiln_scan");
        keygen(key);
        var authorized = "restrict,command=\"exec ~/tools/kiln-scan\" " + Files.readString(Path.of(key + ".pub")).strip() + "\n";
        container = new GenericContainer<>(new ImageFromDockerfile("kiln-test-sshd-scan", false)
                                               .withDockerfileFromBuilder(builder -> builder.from("alpine:3.20")
                                                                                            .run("apk add --no-cache openssh-server bash git coreutils findutils procps")
                                                                                            .run("adduser -D -s /bin/bash builder && sed -i 's/^builder:!/builder:*/' /etc/shadow"
                                                                                                 + " && mkdir -p " + CO + " /home/builder/tools /home/builder/.ssh"
                                                                                                 + " && for r in a b; do git init -q -b main " + CO + "/$r"
                                                                                                 + " && git -C " + CO + "/$r -c user.name=k -c user.email=k@k commit -q --allow-empty -m one;"
                                                                                                 + " done && chown -R builder:builder /home/builder && chmod 700 /home/builder/.ssh")
                                                                                            .cmd("sh", "-c", "ssh-keygen -A && exec /usr/sbin/sshd -D -e")
                                                                                            .build()))
            .withCopyToContainer(MountableFile.forHostPath(ScanFixture.SCRIPT, 0755), "/home/builder/tools/kiln-scan")
            .withCopyToContainer(Transferable.of(authorized, 0600), "/home/builder/.ssh/authorized_keys")
            .withExposedPorts(22)
            .waitingFor(Wait.forListeningPort());
        container.start();
        container.execInContainer("chown", "-R", "builder:builder", "/home/builder/.ssh", "/home/builder/tools");
        var hostKey = container.execInContainer("cat", "/etc/ssh/ssh_host_ed25519_key.pub").getStdout().strip().split(" ");
        Files.writeString(dir.resolve("known_hosts"), "[" + container.getHost() + "]:" + container.getMappedPort(22) + " " + hostKey[0] + " " + hostKey[1] + "\n");
        store = KilnPgFixture.freshStore();
    }

    @AfterEach
    void stop() {
        if (container != null) {
            container.stop();
        }
        if (store != null) {
            store.close().await(WAIT);
        }
    }

    @Test
    void aHostThatStopsAnswering_changesNoRowAndNoCount() {
        var host = KilnConfig.Host.host(BIGBOY, container.getHost(), "builder", dir.resolve("kiln_scan"), dir.resolve("known_hosts"), ROOTS);
        var transport = SshScanTransport.sshScanTransport(host, pointed(container.getMappedPort(22)), TimeSpan.timeSpan(60).seconds());
        var scanner = HostScanner.hostScanner(ScanHost.scanHost(BIGBOY, ROOTS, transport),
                                              store.checkouts(),
                                              store.sources(),
                                              () -> ScanFakes.T0,
                                              Duration.ofHours(48),
                                              () -> {});

        var first = scanner.full().await(WAIT).unwrap().unwrap();
        assertThat(first).as("the scan through sshd and the forced command").isInstanceOf(SourceState.Ok.class);
        var rows = await(store.checkouts().rows(BIGBOY));
        var counts = await(store.views().counts());
        var fullPass = KilnPgFixture.hostPass(store);
        assertThat(fullPass).singleElement().asString().contains("\"kind\": \"full\"", "\"outcome\": \"COMPLETE\"", "\"rows\": 2");
        assertThat(rows).extracting(row -> row.path().value()).containsExactly(CO + "/a", CO + "/b");
        assertThat(rows).allSatisfy(row -> assertThat(row.facts().isPresent()).isTrue());

        container.stop();
        var second = scanner.hot().await(WAIT).unwrap().unwrap();

        assertThat(second).isInstanceOf(SourceState.Unreachable.class);
        assertThat(((SourceState.Unreachable) second).reason()).contains("255");
        assertThat(await(store.checkouts().rows(BIGBOY))).isEqualTo(rows);
        assertThat(await(store.views().counts())).isEqualTo(counts);
        assertThat(KilnPgFixture.hostPass(store)).hasSize(2)
                                                 .contains(fullPass.getFirst())
                                                 .anySatisfy(row -> assertThat(row).contains("\"kind\": \"hot\"",
                                                                                             "\"outcome\": \"UNREACHABLE\"",
                                                                                             "\"rows\": 0"));
        assertThat(counts).extracting(HostCounts::host).contains(BIGBOY);
        assertThat(await(store.sources().all())).extracting(SourceStatus::source).contains(Source.host(BIGBOY));
    }

    /// The real `ssh` with the transport's argv, pointed at the container's mapped port.
    private static ProcessRunner pointed(int port) {
        var real = ProcessRunner.processRunner();

        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                throw new UnsupportedOperationException("the transport spawns");
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                var argv = new ArrayList<String>();
                for (var arg : command.argv()) {
                    if (arg.startsWith("builder@")) {
                        argv.add("-p");
                        argv.add(String.valueOf(port));
                    }
                    argv.add(arg);
                }
                return Command.command(argv, command.env(), command.timeout()).flatMap(real::spawn);
            }
        };
    }

    private static void keygen(Path path) throws IOException, InterruptedException {
        var process = HermeticProcess.builder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", path.toString()).inheritIO().start();
        assertThat(process.waitFor()).isZero();
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await(WAIT).unwrap();
    }
}
