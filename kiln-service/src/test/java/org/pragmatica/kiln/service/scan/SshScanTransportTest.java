package org.pragmatica.kiln.service.scan;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.scan.ScanTransportError.Failed;
import org.pragmatica.kiln.service.scan.ScanTransportError.NoLines;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.scan.ScanFakes.path;

/// [SshScanTransport] and [SshCommand] against a fake `ssh` (a script run in its place, recording its argv, stdin and
/// environment): the argv is a list of words, never a shell string, and every failure of ssh is a failed call (K-5).
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SshScanTransportTest {
    private static final KilnConfig.Host HOST = KilnConfig.Host.host(HostName.hostName("bigboy").unwrap(),
                                                                     "192.0.2.71",
                                                                     "builder",
                                                                     Path.of("/home/kiln/.ssh/kiln_scan"),
                                                                     Path.of("/home/kiln/.ssh/known_hosts"),
                                                                     List.of("~/work/co/*", "/srv/co/*"));

    @TempDir
    Path dir;

    @Test
    void sshCommand_isTheArgvOfTheConfiguredHost_withTheWordsLast() {
        assertThat(SshCommand.scan(HOST, List.of("scan", "~/work/co/*", "/srv/co/*")))
            .containsExactly("ssh", "-T",
                             "-o", "BatchMode=yes",
                             "-o", "ConnectTimeout=15",
                             "-o", "ServerAliveInterval=15",
                             "-o", "ServerAliveCountMax=3",
                             "-o", "StrictHostKeyChecking=yes",
                             "-o", "UserKnownHostsFile=/home/kiln/.ssh/known_hosts",
                             "-o", "IdentitiesOnly=yes",
                             "-i", "/home/kiln/.ssh/kiln_scan",
                             "builder@192.0.2.71",
                             "scan", "~/work/co/*", "/srv/co/*");
    }

    @Test
    void scan_sendsTheRootsAsWords_andTheFullListOnStdin() throws IOException {
        var transport = transport("{\"end\":true,\"count\":0,\"nonGit\":0}\n", 0, "");

        var lines = transport.scan(HOST.roots(), List.of(path("/srv/co/a"), path("/srv/co/b c"))).await().unwrap();

        assertThat(lines).containsExactly("{\"end\":true,\"count\":0,\"nonGit\":0}");
        assertThat(argv()).endsWith("builder@192.0.2.71", "scan", "~/work/co/*", "/srv/co/*");
        assertThat(Files.readString(dir.resolve("stdin"))).isEqualTo("/srv/co/a\n/srv/co/b c\n");
    }

    @Test
    void ssh_seesOnlyPathAndHome() throws IOException {
        transport("x\n", 0, "").scan(HOST.roots(), List.of()).await().unwrap();

        var names = Files.readAllLines(dir.resolve("env")).stream().map(line -> line.split("=", 2)[0]).toList();
        assertThat(names).contains("PATH", "HOME").allMatch(name -> Set.of("PATH", "HOME", "PWD", "SHLVL", "_", "OLDPWD").contains(name));
    }

    @Test
    void reach_sendsTheShasSorted_andSize_sendsRootAndPath() throws IOException {
        var a = Sha.sha("a".repeat(40)).unwrap();
        var b = Sha.sha("b".repeat(40)).unwrap();
        var transport = transport("x\n", 0, "");

        transport.reach(Set.of(b, a)).await().unwrap();
        assertThat(argv()).endsWith("reach", a.value(), b.value());

        transport.size("~/work/co/*", path("/home/builder/work/co/a")).await().unwrap();
        assertThat(argv()).endsWith("size", "~/work/co/*", "/home/builder/work/co/a");
    }

    @Test
    void sshsOwnExit255_failsTheCall() throws IOException {
        var cause = failure(transport("", 255, "ssh: connect to host 192.0.2.71 port 22: Connection refused").scan(HOST.roots(), List.of()));

        assertThat(cause).isInstanceOfSatisfying(NonZero.class, nonZero -> {
            assertThat(nonZero.rc()).isEqualTo(255);
            assertThat(nonZero.stderrTail()).contains("Connection refused");
        });
    }

    @Test
    void aNonZeroRc_withLines_stillFailsTheCall() throws IOException {
        var cause = failure(transport("{\"kind\":\"stub\"}\n", 74, "read error").scan(HOST.roots(), List.of()));

        assertThat(cause).isInstanceOfSatisfying(NonZero.class, nonZero -> assertThat(nonZero.rc()).isEqualTo(74));
    }

    @Test
    void anEmptyAnswer_failsTheCall() throws IOException {
        assertThat(failure(transport("", 0, "").scan(HOST.roots(), List.of()))).isInstanceOf(NoLines.class);
    }

    @Test
    void aSessionPastTheTimeout_isKilled_andFailsTheCall() throws IOException {
        Files.writeString(dir.resolve("sleep"), "30");
        var started = System.nanoTime();

        assertThat(failure(transport("x\n", 0, "", TimeSpan.timeSpan(1).seconds()).scan(HOST.roots(), List.of()))).isInstanceOf(Failed.class);
        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(20);
    }

    /// Fix round ruling 1: kiln-scan splits its request on single spaces, so a path with a space is never sent.
    @Test
    void aWordWithWhitespace_isNeverSent() throws IOException {
        var transport = transport("x\n", 0, "");

        assertThat(failure(transport.size("/srv/co/*", path("/srv/co/b c")))).isInstanceOf(ScanTransportError.Uncarried.class);
        assertThat(dir.resolve("argv")).doesNotExist();
    }

    /// Fix round ruling 1: through the size pass the path is refused, its size stays unknown and it goes to the back
    /// of the queue (the refusal memory), so the next tick measures another path.
    @Test
    void aPathWithASpace_isRefusedBySize_andTheNextTickMeasuresAnother() throws IOException {
        var transport = transport("{\"path\":\"/srv/co/z\",\"kb\":9}\n", 0, "");
        var store = new ScanFakes.RecordingStore();
        store.rows = List.of(ScanFakes.row("/srv/co/b c"), ScanFakes.row("/srv/co/z"));
        var clock = new java.util.concurrent.atomic.AtomicReference<>(ScanFakes.T0);
        var scanner = HostScanner.hostScanner(ScanHost.scanHost(ScanFakes.HOST, List.of("/srv/co/*"), transport), store,
                                              new ScanFakes.RecordingSources(), clock::get, Duration.ofHours(48), () -> {});

        assertThat(scanner.size().await().unwrap().unwrap()).isInstanceOf(HostScanner.Sized.Refused.class);
        assertThat(dir.resolve("argv")).doesNotExist();
        clock.set(ScanFakes.T0.plusSeconds(5));
        assertThat(scanner.size().await().unwrap().unwrap()).isEqualTo(new HostScanner.Sized.Measured(path("/srv/co/z"), 9));
        assertThat(store.sizes).extracting(ScanFakes.Size::path).containsExactly(path("/srv/co/z"));
    }

    /// Fix round ruling 2: stdout past 64 MiB is a failed call (the process killed), never a listing.
    @Test
    void stdoutOver64MiB_failsTheCall() throws IOException {
        Files.writeString(dir.resolve("big"), "");

        assertThat(failure(transport("", 0, "").scan(HOST.roots(), List.of()))).isInstanceOf(ScanTransportError.TooLarge.class);
    }

    private List<String> argv() throws IOException {
        return Files.readAllLines(dir.resolve("argv"));
    }

    private static Cause failure(Promise<List<String>> call) {
        var result = call.await(TimeSpan.timeSpan(30).seconds());

        assertThat(result.isFailure()).as("%s", result).isTrue();
        return result.fold(cause -> cause, _ -> null);
    }

    private SshScanTransport transport(String stdout, int rc, String stderr) throws IOException {
        return transport(stdout, rc, stderr, TimeSpan.timeSpan(30).seconds());
    }

    private SshScanTransport transport(String stdout, int rc, String stderr, TimeSpan timeout) throws IOException {
        Files.writeString(dir.resolve("out"), stdout);
        Files.writeString(dir.resolve("err"), stderr);
        Files.writeString(dir.resolve("rc"), String.valueOf(rc));
        var d = dir.toString();
        var script = Files.writeString(dir.resolve("fake-ssh"),
                                       "printf '%s\\n' \"$@\" > '" + d + "/argv'\n"
                                       + "env > '" + d + "/env'\n"
                                       + "cat > '" + d + "/stdin'\n"
                                       + "[ -f '" + d + "/sleep' ] && sleep \"$(cat '" + d + "/sleep')\"\n"
                                       + "[ -f '" + d + "/big' ] && head -c " + (ScanExec.MAX_BYTES + 1) + " /dev/zero | tr '\\000' x\n"
                                       + "cat '" + d + "/out'\n"
                                       + "cat '" + d + "/err' >&2\n"
                                       + "exit \"$(cat '" + d + "/rc')\"\n");
        return SshScanTransport.sshScanTransport(HOST, fakeSsh(script), timeout);
    }

    /// Runs `bash <script>` in place of `ssh`, with the command's own words and environment.
    private static ProcessRunner fakeSsh(Path script) {
        var real = ProcessRunner.processRunner();

        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                throw new UnsupportedOperationException("the transport spawns");
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                assertThat(command.argv().getFirst()).isEqualTo("ssh");
                assertThat(command.inheritEnv()).isFalse();
                var argv = Stream.concat(Stream.of("bash", script.toString()), command.argv().stream().skip(1)).toList();
                return Command.command(argv, command.env(), command.timeout()).flatMap(real::spawn);
            }
        };
    }
}
