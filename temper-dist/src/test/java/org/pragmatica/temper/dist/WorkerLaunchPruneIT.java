package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/worker-launch run <ver>` prunes old installed worker jars (owner decision A2): `~/.temper` keeps the jar being
/// launched and the previous one, the installed jar most recently launched or installed (its mtime, which `install`
/// writes and every `run` stamps). Nothing but an installed pair (`worker-<ver>.jar` with a well-formed `.sha256`
/// record) is touched; a version with a run copy (a running worker) is kept; a failed or skipped prune never stops the
/// launch.
///
/// The java the launcher execs is a fake (`~/.temper/java-home` naming a `bin/java` shell script that prints its
/// arguments), so the tests depend on no java on PATH, and the fake ends at once, as a worker that exited.
/// Mtimes are set explicitly, whole minutes apart (but for the tie), so the order never depends on the clock's resolution.
/// The lock tests hold the launcher's flock from outside (flock(1) or python3, whichever is on PATH) or widen a prune
/// with a slow `stat` on PATH (the launcher has no test hook).
class WorkerLaunchPruneIT {
    private static final Path SCRIPT = Path.of("..", "ops", "worker-launch")
                                           .toAbsolutePath()
                                           .normalize();
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final String V0 = "0.1.0";
    private static final String V1 = "0.2.0+aaaaaaaaaaaa";
    private static final String V2 = "0.2.0+bbbbbbbbbbbb";
    private static final String V3 = "0.2.0+cccccccccccc-dirty";
    private static final String V4 = "0.2.0+unjudged";
    /// The bash on this JVM's PATH, by absolute path, so a test's own PATH for the launcher cannot hide it.
    private static final Path BASH = Stream.of(System.getenv("PATH")
                                                     .split(":"))
                                           .map(dir -> Path.of(dir, "bash"))
                                           .filter(Files::isExecutable)
                                           .findFirst()
                                           .orElseThrow();

    @TempDir
    Path home;

    private Path temper;

    record Outcome(int rc, String output) {}

    @BeforeEach
    void fakeJava() throws IOException {
        temper = Files.createDirectories(home.resolve(".temper"));
        var java = Files.createDirectories(home.resolve("jdk/bin"))
                        .resolve("java");
        Files.writeString(java, "#!/bin/sh\necho \"fake-java $*\"\n[ -z \"${FAKE_JAVA_SLEEP:-}\" ] || exec sleep \"$FAKE_JAVA_SLEEP\"\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(temper.resolve("java-home"), home.resolve("jdk") + "\n");
    }

    /// The launcher carries no test hook: no `TEST_` anywhere in it. A variable it read could reach it from a requester
    /// whenever sshd's AcceptEnv matches it (`restrict` does not stop that), and the tests need none (a slow `stat` on
    /// PATH widens the lock window instead).
    @Test
    void launcher_readsNoTestVariable() throws IOException {
        assertThat(Files.readString(SCRIPT)).doesNotContain("TEST_");
    }

    /// The previous jar is the newest other one by mtime, not by name and not the one installed just before the current:
    /// launching V2 keeps V2 and V4 (newest), and prunes V0, V1 and V3 with their records.
    @Test
    void run_keepsTheCurrentAndThePrevious_andPrunesTheRest() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        installed(V3, 4);
        installed(V4, 5);

        var outcome = run(V2);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("fake-java -XX:+ExitOnOutOfMemoryError -jar ")
                                    .contains("pruned worker-" + V0 + ".jar")
                                    .contains("pruned worker-" + V1 + ".jar")
                                    .contains("pruned worker-" + V3 + ".jar");
        assertThat(pairs()).containsExactlyInAnyOrder(V2, V4);
        assertThat(temper.resolve("worker-" + V0 + ".jar.sha256")).doesNotExist();
    }

    /// "Previous" follows launches: V0 installed first and launched later is the newest other jar when V2 is launched,
    /// so V0 is kept and V1, installed after V0 but launched never since, is pruned.
    @Test
    void run_stampsItsJar_soThePreviousIsTheLastLaunched() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);

        var first = run(V0);

        assertThat(first.rc()).as(first.output()).isZero();
        assertThat(pairs()).as("V0 launched: V2 is its previous").containsExactlyInAnyOrder(V0, V2);
        installed(V1, 4);
        assertThat(Files.getLastModifiedTime(temper.resolve("worker-" + V0 + ".jar"))
                        .toInstant()).as("the stamp of the launch").isAfter(BASE.plusSeconds(3600));

        var second = run(V2);

        assertThat(second.rc()).as(second.output()).isZero();
        assertThat(pairs()).containsExactlyInAnyOrder(V0, V2);
    }

    /// A version a running worker holds (a run copy named after a live pid, this JVM's) is never pruned, however old.
    @Test
    void run_keepsAVersionWithARunningWorker() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        installed(V3, 4);
        var running = Files.writeString(Files.createDirectories(temper.resolve("run"))
                                             .resolve("worker-" + V0 + "." + ProcessHandle.current()
                                                                                         .pid() + ".jar"),
                                        "running");

        var outcome = run(V3);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(pairs()).containsExactlyInAnyOrder(V0, V2, V3);
        assertThat(running).hasContent("running");
    }

    /// Only an installed pair is a candidate: every near miss below is older than every jar and stays as it was.
    @Test
    void run_touchesNothingButInstalledPairs() throws Exception {
        installed(V1, 10);
        installed(V2, 11);
        var nearMisses = new ArrayList<Path>();
        nearMisses.add(old(temper.resolve("worker-0.0.1.jar.part")));
        nearMisses.add(old(temper.resolve("worker-0.0.2.part.jar")));
        nearMisses.add(old(temper.resolve("worker-0.0.3.jar.bak-" + "e".repeat(64))));
        nearMisses.add(old(temper.resolve(".worker-0.0.4.jar.Ab12Cd")));
        nearMisses.add(old(temper.resolve(".worker-0.0.4.jar.Ab12Cd.sha256")));
        nearMisses.add(old(temper.resolve("worker-0.0.5.jar")));
        nearMisses.add(old(temper.resolve("worker-0.0.6.jar")));
        nearMisses.add(old(Files.writeString(temper.resolve("worker-0.0.6.jar.sha256"), "not a sha256\n")));
        nearMisses.add(old(temper.resolve("worker-0.0.7.jar.sha256")));
        nearMisses.add(old(temper.resolve("worker-bad version.jar")));
        nearMisses.add(old(temper.resolve("worker-bad version.jar.sha256")));
        nearMisses.add(old(temper.resolve("temper-worker.jar")));
        var target = old(home.resolve("elsewhere.jar"));
        var link = Files.createSymbolicLink(temper.resolve("worker-0.0.8.jar"), target);
        nearMisses.add(old(Files.writeString(temper.resolve("worker-0.0.8.jar.sha256"), sha256("elsewhere") + "\n")));
        var directory = Files.createDirectories(temper.resolve("worker-0.0.9.jar"));
        nearMisses.add(old(Files.writeString(temper.resolve("worker-0.0.9.jar.sha256"), sha256("x") + "\n")));
        installed("0.0.10", 0);

        var outcome = run(V2);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("pruned worker-0.0.10.jar");
        assertThat(nearMisses).allMatch(Files::exists);
        assertThat(link).isSymbolicLink();
        assertThat(target).exists();
        assertThat(directory).isDirectory();
        assertThat(temper.resolve("worker-" + V1 + ".jar")).exists();
        assertThat(temper.resolve("worker-" + V2 + ".jar")).exists();
        assertThat(temper.resolve("worker-0.0.10.jar")).doesNotExist();
        assertThat(temper.resolve("worker-0.0.10.jar.sha256")).doesNotExist();
    }

    /// A prune that fails (a `~/.temper` it cannot delete from) is logged, and the launch goes on.
    @Test
    void run_launchesAnyway_whenThePruneFails() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "root deletes from a read-only directory");
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        Files.createDirectories(temper.resolve("run"));
        Files.setPosixFilePermissions(temper, PosixFilePermissions.fromString("r-x------"));
        try {
            var outcome = run(V2);

            assertThat(outcome.rc()).as(outcome.output()).isZero();
            assertThat(outcome.output()).contains("could not prune worker-" + V0 + ".jar")
                                        .contains("pruning old worker jars failed")
                                        .contains("fake-java -XX:+ExitOnOutOfMemoryError -jar ");
            assertThat(pairs()).containsExactlyInAnyOrder(V0, V1, V2);
        } finally {
            Files.setPosixFilePermissions(temper, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /// Every jar tied for the newest second is kept, on both OSes: V1 and V3 are 400 ms apart within one second (bash 5's
    /// `-nt` would tell them apart, bash 3.2's would not), so neither is pruned; V0 is.
    @Test
    void run_keepsEveryJarTiedForTheNewestSecond() throws Exception {
        installed(V0, 1);
        installed(V1, 5);
        installed(V2, 2);
        installed(V3, 5);
        Files.setLastModifiedTime(temper.resolve("worker-" + V1 + ".jar"), FileTime.from(BASE.plusSeconds(300).plusMillis(100)));
        Files.setLastModifiedTime(temper.resolve("worker-" + V3 + ".jar"), FileTime.from(BASE.plusSeconds(300).plusMillis(500)));

        var outcome = run(V2);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(pairs()).containsExactlyInAnyOrder(V1, V2, V3);
    }

    /// Only the launched jar installed: nothing to prune, and nothing fails.
    @Test
    void run_withNoOtherJar_prunesNothing_andReportsNoFailure() throws Exception {
        installed(V2, 3);

        var outcome = run(V2);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).doesNotContain("failed")
                                    .doesNotContain("pruned")
                                    .contains("fake-java -XX:+ExitOnOutOfMemoryError -jar ");
        assertThat(pairs()).containsExactly(V2);
    }

    /// The lock held by another process past the 5-second wait: the claim goes on without it, the prune is skipped
    /// (logged), the launch goes on, and nothing is deleted.
    @Test
    void run_skipsThePrune_whileAnotherHoldsTheLock() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);

        try (var holder = Holder.hold(lockFile(), 15)) {
            var outcome = run(V2);

            assertThat(outcome.rc()).as(outcome.output()).isZero();
            assertThat(outcome.output()).contains("claiming worker-" + V2 + ".jar without it")
                                        .contains("pruning skipped")
                                        .contains("fake-java -XX:+ExitOnOutOfMemoryError -jar ");
            assertThat(pairs()).containsExactlyInAnyOrder(V0, V1, V2);
        }
    }

    /// Two prunes at once are serialised: the launcher's `stat` is a wrapper that sleeps a second, and a prune calls it
    /// under the lock (the stat flavour once, then once per candidate jar), so the first prune holds the lock about 3
    /// seconds and the second about 2. Serialised, the two launches take at least 4.5 seconds; overlapping prunes would
    /// take about 3. Both run, and neither skips its prune.
    @Test
    void run_twoPrunesAtOnce_areSerialised() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        var path = Map.of("PATH", slowStat(1) + ":" + System.getenv("PATH"));
        var started = System.nanoTime();

        var first = CompletableFuture.supplyAsync(() -> unchecked(V1, path));
        var second = CompletableFuture.supplyAsync(() -> unchecked(V2, path));

        for (var outcome : List.of(first.join(), second.join())) {
            assertThat(outcome.rc()).as(outcome.output()).isZero();
            assertThat(outcome.output()).doesNotContain("skipped")
                                        .doesNotContain("without it");
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(4500));
        assertThat(pairs()).containsExactlyInAnyOrder(V1, V2);
    }

    /// A launch killed with -9 while it holds the lock (inside its prune, in a slow `stat`) does not block the next
    /// launch: the kernel dropped its lock, so the next one prunes at once, without waiting out the 5 seconds.
    @Test
    void run_aHolderKilledWithSigkill_doesNotBlockTheNextLaunch() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        var holder = launcher("run " + V1, Map.of("PATH", slowStat(60) + ":" + System.getenv("PATH"))).start();
        try {
            awaitFile(statCalled());
        } finally {
            killTree(holder);
        }
        var started = System.nanoTime();

        var outcome = run(V2);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).doesNotContain("skipped")
                                    .doesNotContain("without it")
                                    .contains("pruned worker-" + V0 + ".jar");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    /// A running worker never holds the lock (the launcher closes it before the exec): while V1's worker runs, an
    /// install and a second launch's prune (of V4, installed old while the worker runs) both take the lock at once and
    /// succeed.
    @Test
    void run_aRunningWorker_doesNotHoldTheLock() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        var worker = launcher("run " + V1, Map.of("FAKE_JAVA_SLEEP", "60")).start();
        try {
            awaitLine(worker, "fake-java");
            installed(V4, 0);

            var install = install(V3, "worker " + V3);
            var second = run(V2);

            assertThat(install.rc()).as(install.output()).isZero();
            assertThat(install.output()).doesNotContain("without the lock");
            assertThat(second.rc()).as(second.output()).isZero();
            assertThat(second.output()).doesNotContain("skipped")
                                       .doesNotContain("without it")
                                       .contains("pruned worker-" + V4 + ".jar");
            assertThat(pairs()).as("V1 runs, V2 launched, V3 newest").containsExactlyInAnyOrder(V1, V2, V3);
        } finally {
            killTree(worker);
        }
    }

    /// A python3 that fails its self-test (the macOS stub without the Command Line Tools) and no flock: no lock tool.
    /// The install goes on without the lock (logged), and the prune is skipped (logged), never done unlocked.
    @Test
    void aBrokenPython_isNoLockTool_installsGoOn_prunesAreSkipped() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        var path = Map.of("PATH", stub("python3", "echo 'xcode-select: note: no developer tools were found' >&2\nexit 1")
                                  + ":" + withoutLockTools());

        var install = install(V3, "worker " + V3, path);
        var launch = run(V2, path);

        assertThat(install.rc()).as(install.output()).isZero();
        assertThat(install.output()).contains("installing worker-" + V3 + ".jar without the lock");
        assertThat(launch.rc()).as(launch.output()).isZero();
        assertThat(launch.output()).contains("pruning skipped: neither flock nor a working python3");
        assertThat(pairs()).containsExactlyInAnyOrder(V0, V1, V2, V3);
    }

    /// A python3 that passes the self-test but fails taking the lock: a tool failure, not "held". The install goes on
    /// without the lock (no 70), and the prune is skipped; both are logged.
    @Test
    void aLockToolFailure_isNotATimeout_installsGoOn_prunesAreSkipped() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        var path = Map.of("PATH", stub("python3", "[ \"$3\" = 'import fcntl' ] && exit 0\necho 'Traceback: OSError' >&2\nexit 1")
                                  + ":" + withoutLockTools());

        var install = install(V3, "worker " + V3, path);
        var launch = run(V2, path);

        assertThat(install.rc()).as(install.output()).isZero();
        assertThat(install.output()).contains("the lock tool python3 failed (rc 1)")
                                    .contains("installing worker-" + V3 + ".jar without the lock");
        assertThat(launch.rc()).as(launch.output()).isZero();
        assertThat(launch.output()).contains("pruning skipped: no lock");
        assertThat(pairs()).containsExactlyInAnyOrder(V0, V1, V2, V3);
    }

    /// The post-claim check: a version pruned while its launch waited for the lock is not installed (66), not a failed
    /// copy (70). The test deletes the jar while it holds the lock, as a prune would, then lets the launch claim.
    @Test
    void run_findsItsJarPrunedBeforeItsClaim_notInstalled() throws Exception {
        installed(V1, 2);
        installed(V2, 3);

        try (var holder = Holder.hold(lockFile(), 4)) {
            var launch = CompletableFuture.supplyAsync(() -> unchecked(V2, Map.of()));
            Thread.sleep(1500);
            Files.delete(temper.resolve("worker-" + V2 + ".jar"));

            var outcome = launch.join();

            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(66);
            assertThat(outcome.output()).contains("worker-" + V2 + ".jar is not installed");
        }
        try (var copies = Files.list(temper.resolve("run"))) {
            assertThat(copies.map(path -> path.getFileName()
                                              .toString())).as("no claim is left behind").containsOnly(".lock");
        }
    }

    /// Without flock and python3 on PATH there is no lock, so pruning is skipped (logged), never done unlocked, and the
    /// launch goes on.
    @Test
    void run_skipsThePrune_withoutALockTool() throws Exception {
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);

        var outcome = run(V2, Map.of("PATH", withoutLockTools().toString()));

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("pruning skipped: neither flock nor a working python3")
                                    .contains("fake-java -XX:+ExitOnOutOfMemoryError -jar ");
        assertThat(pairs()).containsExactlyInAnyOrder(V0, V1, V2);
    }

    /// A non-contention error from python's flock (ENOLCK) is a tool failure, not a timeout: it is not retried until the
    /// deadline, so the install goes on unlocked (no 70) and the prune is skipped, both logged. A `sitecustomize` on
    /// PYTHONPATH replaces `fcntl.flock` with one raising ENOLCK (`fcntl` is built into some pythons, so a fake module
    /// could not shadow it); the wrapper drops the launcher's `-I`, so PYTHONPATH and `site` apply.
    @Test
    void aNonBlockingFlockError_isAToolFailure_notATimeout() throws Exception {
        var python = Stream.of(System.getenv("PATH")
                                     .split(":"))
                           .map(dir -> Path.of(dir, "python3"))
                           .filter(Files::isExecutable)
                           .findFirst();
        Assumptions.assumeTrue(python.isPresent(), "no python3 to raise ENOLCK");
        installed(V0, 1);
        installed(V1, 2);
        installed(V2, 3);
        var fake = Files.createDirectories(home.resolve("enolck"));
        Files.writeString(fake.resolve("sitecustomize.py"),
                          "import errno\nimport fcntl\n\n\ndef _enolck(fd, operation):\n"
                          + "    raise OSError(errno.ENOLCK, 'No locks available')\n\n\nfcntl.flock = _enolck\n");
        var wrapper = stub("python3", "[ \"$1\" = -I ] && shift\nPYTHONPATH='" + fake + "' exec " + python.get() + " \"$@\"");
        var path = Map.of("PATH", wrapper + ":" + withoutLockTools());
        var started = System.nanoTime();

        var install = install(V3, "worker " + V3, path);
        var launch = run(V2, path);

        assertThat(install.rc()).as(install.output()).isZero();
        assertThat(install.output()).contains("the lock tool python3 failed (rc 1)")
                                    .contains("installing worker-" + V3 + ".jar without the lock");
        assertThat(launch.rc()).as(launch.output()).isZero();
        assertThat(launch.output()).contains("pruning skipped: no lock");
        assertThat(pairs()).containsExactlyInAnyOrder(V0, V1, V2, V3);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("never waited out the deadline")
                                                                 .isLessThan(Duration.ofSeconds(5));
    }

    /// `install` renames under the same lock: held for a moment, the install waits and then succeeds.
    @Test
    void install_waitsForTheLock() throws Exception {
        Files.createDirectories(temper.resolve("run"));
        long started;

        try (var holder = Holder.hold(lockFile(), 2)) {
            started = System.nanoTime();
            var outcome = install(V1, "worker " + V1);

            assertThat(outcome.rc()).as(outcome.output()).isZero();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(1000));
        assertThat(pairs()).containsExactly(V1);
    }

    /// Held past the 5-second wait, `install` installs nothing (70) and leaves no temporary behind.
    @Test
    void install_installsNothing_whileTheLockIsHeldPastTheWait() throws Exception {
        Files.createDirectories(temper.resolve("run"));

        try (var holder = Holder.hold(lockFile(), 15)) {
            var outcome = install(V1, "worker " + V1);

            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(70);
            assertThat(outcome.output()).contains("nothing installed");
        }
        try (var listed = Files.list(temper)) {
            assertThat(listed.map(path -> path.getFileName()
                                              .toString())).containsExactlyInAnyOrder("java-home", "run");
        }
    }

    /// A pair as `install` leaves it: the jar, its record, the jar's mtime `minutes` after [#BASE].
    private void installed(String version, int minutes) throws Exception {
        var content = "worker " + version;
        var jar = Files.writeString(temper.resolve("worker-" + version + ".jar"), content);
        Files.writeString(temper.resolve("worker-" + version + ".jar.sha256"), sha256(content) + "\n");
        Files.setLastModifiedTime(jar, FileTime.from(BASE.plusSeconds(60L * minutes)));
    }

    /// A file (created when absent) older than every installed jar.
    private static Path old(Path file) throws IOException {
        if (Files.notExists(file)) {
            Files.writeString(file, "x");
        }
        Files.setLastModifiedTime(file, FileTime.from(BASE.minusSeconds(3600)));
        return file;
    }

    /// The versions of the installed pairs left in `~/.temper`.
    private List<String> pairs() throws IOException {
        try (Stream<Path> listed = Files.list(temper)) {
            return listed.map(path -> path.getFileName()
                                          .toString())
                         .filter(name -> name.startsWith("worker-") && name.endsWith(".jar"))
                         .filter(name -> Files.exists(temper.resolve(name + ".sha256")))
                         .map(name -> name.substring("worker-".length(), name.length() - ".jar".length()))
                         .toList();
        }
    }

    private Outcome unchecked(String version, Map<String, String> env) {
        try {
            return run(version, env);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private Outcome run(String version) throws IOException, InterruptedException {
        return run(version, Map.of());
    }

    /// `run <version>` as the live key's forced command asks it, with `env` added.
    private Outcome run(String version, Map<String, String> env) throws IOException, InterruptedException {
        return ran(launcher("run " + version, env).start());
    }

    private ProcessBuilder launcher(String command, Map<String, String> env) {
        var builder = HermeticProcess.builder(List.of(BASH.toString(), SCRIPT.toString()));
        builder.environment().put("HOME", home.toString());
        builder.environment().put("SSH_ORIGINAL_COMMAND", command);
        builder.environment().putAll(env);
        builder.redirectErrorStream(true);
        return builder;
    }

    private Outcome install(String version, String content) throws Exception {
        return install(version, content, Map.of());
    }

    /// `install <version> <sha256>` with `content` as the jar on stdin, with `env` added.
    private Outcome install(String version, String content, Map<String, String> env) throws Exception {
        var process = launcher("install " + version + " " + sha256(content), env).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return ran(process);
    }

    private static Outcome ran(Process process) throws IOException, InterruptedException {
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        return new Outcome(process.waitFor(), output);
    }

    /// A directory holding a `stat` that creates [#statCalled], sleeps `seconds`, then runs the real stat. (A marker
    /// file, not output: the launcher's first `stat` has its output discarded.)
    private Path slowStat(int seconds) throws IOException {
        var realStat = Stream.of(System.getenv("PATH")
                                       .split(":"))
                             .map(dir -> Path.of(dir, "stat"))
                             .filter(Files::isExecutable)
                             .findFirst()
                             .orElseThrow();
        return stub("stat", ": > '" + statCalled() + "'\nsleep " + seconds + "\nexec " + realStat + " \"$@\"");
    }

    private Path statCalled() {
        return home.resolve("stat-called");
    }

    /// A directory holding one shell script `name` with `body`.
    private Path stub(String name, String body) throws IOException {
        var dir = Files.createDirectories(home.resolve("stub-" + name));
        var script = Files.writeString(dir.resolve(name), "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return dir;
    }

    /// Waits (20 seconds at most) until `file` exists.
    private static void awaitFile(Path file) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(20)
                                                   .toNanos();
        while (Files.notExists(file)) {
            assertThat(System.nanoTime()).as("waiting for " + file).isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    /// Reads `process`'s output until a line containing `marker` (it is then past that point).
    private static void awaitLine(Process process, String marker) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        for (var line = reader.readLine(); line != null; line = reader.readLine()) {
            if (line.contains(marker)) {
                return;
            }
        }
        throw new AssertionError("the launcher ended before printing " + marker);
    }

    /// Kills `process` and everything it started with SIGKILL.
    private static void killTree(Process process) throws InterruptedException {
        process.descendants()
               .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly()
               .waitFor();
    }

    private Path lockFile() throws IOException {
        return Files.createDirectories(temper.resolve("run"))
                    .resolve(".lock");
    }

    /// A PATH of links to every program of the system directories except flock and python: the launcher finds no lock
    /// tool, wherever one is installed.
    private Path withoutLockTools() throws IOException {
        var sys = Files.createDirectories(home.resolve("sys"));
        for (var dir : List.of("/usr/bin", "/bin", "/usr/sbin", "/sbin")) {
            var path = Path.of(dir);
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (Stream<Path> programs = Files.list(path)) {
                for (var program : programs.toList()) {
                    var name = program.getFileName()
                                      .toString();
                    if (!name.startsWith("flock") && !name.startsWith("python") && Files.notExists(sys.resolve(name),
                                                                                                    LinkOption.NOFOLLOW_LINKS)) {
                        Files.createSymbolicLink(sys.resolve(name), program);
                    }
                }
            }
        }
        return sys;
    }

    /// Another process holding the launcher's lock (flock(1) when on PATH, else python3's fcntl.flock) for `seconds`,
    /// held once [#hold] returns; closing ends it and every process it started (a flock(1) child inherits the lock).
    record Holder(Process process) implements AutoCloseable {
        static Holder hold(Path lock, int seconds) throws IOException {
            var command = onPath("flock")
                          ? List.of("flock", lock.toString(), "sh", "-c", "echo held; sleep " + seconds)
                          : List.of("python3", "-c", "import fcntl, sys, time\nf = open(sys.argv[1], 'a')\n"
                                                   + "fcntl.flock(f, fcntl.LOCK_EX)\nprint('held', flush=True)\n"
                                                   + "time.sleep(float(sys.argv[2]))", lock.toString(), String.valueOf(seconds));
            Assumptions.assumeTrue(onPath(command.getFirst()), "neither flock nor python3 to hold the lock");
            var process = HermeticProcess.builder(command).redirectErrorStream(true)
                                                     .start();
            var line = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertThat(line).as("the holder took the lock").isEqualTo("held");
            return new Holder(process);
        }

        @Override
        public void close() throws InterruptedException {
            process.descendants()
                   .forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly()
                   .waitFor();
        }

        private static boolean onPath(String program) {
            return Stream.of(System.getenv("PATH")
                                   .split(":"))
                         .anyMatch(dir -> Files.isExecutable(Path.of(dir, program)));
        }
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
    }
}
