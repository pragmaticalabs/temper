package org.pragmatica.kiln.service.scan;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.service.scan.ScanTransportError.NonZero;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.kiln.service.scan.ScanFixture.MAVEN_MAIN;
import static org.pragmatica.kiln.service.scan.ScanFixture.complete;
import static org.pragmatica.kiln.service.scan.ScanFixture.facts;
import static org.pragmatica.kiln.service.scan.ScanFixture.linux;
import static org.pragmatica.kiln.service.scan.ScanFixture.pathFirst;
import static org.pragmatica.kiln.service.scan.ScanFixture.real;
import static org.pragmatica.kiln.service.scan.ScanFixture.repo;
import static org.pragmatica.kiln.service.scan.ScanFixture.rows;
import static org.pragmatica.kiln.service.scan.ScanFixture.scanned;
import static org.pragmatica.kiln.service.scan.ScanFixture.script;
import static org.pragmatica.kiln.service.scan.ScanFixture.transport;

/// K-14 (kiln-f1-design §7.2, Q-14): `processes` holds the PIDs of the scanning user's processes whose cwd is the
/// checkout or under it, and `maven` whether one of them runs the Maven launcher. Linux reads `/proc/<pid>/cwd`; the
/// macOS branch (a fake `uname` printing `Darwin` and a fake `lsof` first on `PATH`) runs one
/// `lsof -a -d cwd -u "$USER" -Fn`. The macOS leg against the real `lsof` is the recorded manual run (WP4 state file).
/// These run on Linux (the build host); elsewhere they are skipped, never passed.
///
/// Probes (recorded in the WP4 state file): skip the `/proc` probe → [#scan_listsTheProcessesWhoseCwdIsInsideTheCheckout]
/// reads no PID; skip the `lsof` branch → [#scan_onDarwin_readsTheCwdsFromOneLsof] reads no PID (the fake's processes
/// really sit elsewhere, so `/proc` cannot stand in for it); match a bare prefix (no `/`) → the sibling `a2` is red.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KilnScanProbeIT {
    @TempDir
    Path home;

    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void stopStarted() {
        started.forEach(Process::destroyForcibly);
    }

    @Test
    void scan_listsTheProcessesWhoseCwdIsInsideTheCheckout() throws IOException {
        assumeTrue(linux(), "the /proc probe runs on Linux");
        var co = home.resolve("work/co");
        var a = repo(co.resolve("a"));
        var a2 = repo(co.resolve("a2"));
        var m = repo(co.resolve("m"));
        var idle = repo(co.resolve("idle"));
        var inA = sleeper(Files.createDirectories(a.resolve("sub/deeper")), "sleep");
        var inA2 = sleeper(a2, "sleep");
        var maven = sleeper(m, MAVEN_MAIN);

        var rows = rows(complete(scanned(transport(home), List.of("~/work/co/*"), List.of())));

        assertThat(facts(rows, a).processes()).containsExactly(inA);
        assertThat(facts(rows, a).maven()).isFalse();
        assertThat(facts(rows, a2).processes()).as("a sibling sharing a's prefix is not inside a").containsExactly(inA2);
        assertThat(facts(rows, m).processes()).containsExactly(maven);
        assertThat(facts(rows, m).maven()).isTrue();
        assertThat(facts(rows, idle).processes()).isEmpty();
        assertThat(facts(rows, idle).maven()).isFalse();
    }

    /// The macOS branch on Linux: the fake `lsof` claims two real processes (a plain one, and one whose argv0 is the
    /// Maven launcher, read back through `ps`) sit in `a/sub` and `m`; really they sit elsewhere.
    @Test
    void scan_onDarwin_readsTheCwdsFromOneLsof() throws IOException {
        assumeTrue(linux(), "the fake Darwin runs on the Linux build host");
        var co = home.resolve("work/co");
        var a = repo(co.resolve("a"));
        var m = repo(co.resolve("m"));
        var idle = repo(co.resolve("idle"));
        var elsewhere = Files.createDirectories(home.resolve("elsewhere"));
        var plain = sleeper(elsewhere, "sleep");
        var maven = sleeper(elsewhere, MAVEN_MAIN);
        var bin = fakeDarwin("printf 'p%s\\nfcwd\\nn%s\\np%s\\nfcwd\\nn%s\\n' " + plain + " '" + real(a) + "/sub' "
                             + maven + " '" + real(m) + "'\n");

        var rows = rows(complete(scanned(transport(home, pathFirst(bin)), List.of("~/work/co/*"), List.of())));

        assertThat(facts(rows, a).processes()).containsExactly(plain);
        assertThat(facts(rows, a).maven()).isFalse();
        assertThat(facts(rows, m).processes()).containsExactly(maven);
        assertThat(facts(rows, m).maven()).isTrue();
        assertThat(facts(rows, idle).processes()).isEmpty();
        assertThat(Files.readAllLines(home.resolve("lsof.calls"))).as("one lsof, for the caller's cwds")
                                                                  .containsExactly("-a -d cwd -u " + System.getProperty("user.name") + " -Fn");
    }

    /// A probe that lists no process at all (not even the scan) fails the scan (70): an empty probe must not read as
    /// "no process in any checkout".
    @Test
    void scan_fails_whenTheProbeListsNoProcess() throws IOException {
        repo(home.resolve("work/co/a"));
        var bin = fakeDarwin("exit 1\n");

        var failure = transport(home, pathFirst(bin)).scan(List.of("~/work/co/*"), List.of()).await();

        Cause cause = failure.fold(origin -> origin, _ -> null);
        assertThat(cause).isInstanceOf(NonZero.class);
        assertThat(((NonZero) cause).rc()).isEqualTo(70);
    }

    /// A `bin` holding a `uname` that prints `Darwin` and an `lsof` that logs its arguments, then runs `lsofBody`.
    private Path fakeDarwin(String lsofBody) throws IOException {
        var bin = home.resolve("bin");
        script(bin.resolve("uname"), "echo Darwin\n");
        script(bin.resolve("lsof"), "echo \"$*\" >> '" + home.resolve("lsof.calls") + "'\n" + lsofBody);
        return bin;
    }

    /// A `sleep` with its cwd in `dir`, its argv0 `name`; its PID.
    private long sleeper(Path dir, String name) throws IOException {
        var process = HermeticProcess.builder(List.of("bash", "-c", "exec -a " + name + " sleep 600")).directory(dir.toFile())
                                                                                                .start();
        started.add(process);
        return process.pid();
    }
}
