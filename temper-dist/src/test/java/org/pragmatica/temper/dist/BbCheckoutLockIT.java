package org.pragmatica.temper.dist;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Issue #119: concurrent `bb-checkout` runs on one host used to race on the mirror's ref locks
/// ("cannot lock ref refs/pull/N/head: is at X but expected Y") before any checkout existed. The fix is an exclusive flock on
/// `~/work/pragmatica.git.lock` around fetch, rev-parse and clone, a bounded wait (exit 6), a bounded fetch (exit 7), the lock fd INHERITED by
/// every git (the kernel keeps the lock while any of them lives, so a killed bb-checkout never frees the lock under a running git) and gc in the
/// foreground. HOME of the fixture contains a space: every row runs the script, and the printed operator command, with a quoted path.
///
/// The race is made deterministic, not statistical: a `uploadpack.packObjectsHook` (global config of the fixture HOME) parks the FIRST pack
/// request of a fetch AFTER that fetch has read the mirror's old ref values and BEFORE it takes the ref locks; a second caller then runs
/// to completion (without the lock) or queues (with it); the first is released afterwards. Without the lock the first run ALWAYS ends
/// with "is at Y but expected X". Other git steps are parked by a `git` shim ahead of the real one on PATH, gc's repack by a GIT_EXEC_PATH
/// wrapper. The stress row (8 callers, 3 rounds, refs added, force-moved and deleted meanwhile) is statistical.
/// The waits below poll a marker or a process state with a deadline: the marker/state is the event, there is no fixed sleep.
///
/// Mutations (each red in the named row): close fd 9 for the fetch -> the three signal rows
/// [#aSecondCaller_waitsForTheGitOfABbCheckoutKilledWithTerm_andThenSucceedsAtTheRightSha] (also ...Hup..., ...Kill...); for the rev-parse or
/// the clone, or release before the clone -> [#theLockStaysWithAGitParkedInTheRevParse_whenBbCheckoutIsKilled] / [...InTheClone...];
/// remove the `flock` lines -> [#aSecondCaller_whileTheFirstIsParkedMidFetch_queues_andBothSucceed] (always red) and
/// [#eightCallers_whileRefsMove_allSucceedAtTheRightSha] (usually red); drop `timeout` -> [#aHungFetch_isBounded_refused_andLeavesNothing];
/// drop `-k` or use `--foreground` -> [#timeoutsKill_reachesTheWholeProcessGroup_soAHelperThatIgnoresTermDies]; drop the `-w` bound ->
/// [#aLockWaitThatExpires_isRefusedWithExit6_andLeavesNothing]; call every 137 or 124 an expiry ->
/// [#anOutsideSigkill_ofTheFetch_isNotCalledABoundExpiry_andKeeps137] / [#aStatus124FromGitItself_beforeTheBound_isNotCalledAnExpiry];
/// drop `gc.autoDetach=false` -> [#autoGc_runsInTheForegroundInsideTheFetch_notDetached]; drop the holder pids from the waiting line -> the
/// signal rows and the queue row; loosen the bound check -> [#aBoundThatIsNotOneToFiveAsciiDigitsAtLeastOne_isRefusedWithExit2_andLeavesNothing];
/// change the listed/removed set or unquote the printed command -> [#aFetchKilledAtTheRefUpdate_leavesBlockingFiles_thatAreListedNotDeleted_andThePrintedCommandRecovers].
/// `-Dbb.lockit.rounds=N` runs the statistical row for N rounds of 8 callers and names how many were red.
class BbCheckoutLockIT {
    private static final long DEADLINE_MILLIS = 90_000L;

    @TempDir
    Path dir;

    private Path home;
    private Path origin;
    private Path lane;
    private Path gate;
    private Path lock;
    private String c1;
    private String c2;
    private int counter;
    private final List<Process> started = new ArrayList<>();
    private final List<ProcessHandle> orphans = new ArrayList<>();

    @BeforeEach
    void fixture() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-checkout takes a flock(1): util-linux, bb hosts and CI are Linux");
        home = dir.resolve("home dir");
        origin = dir.resolve("origin.git");
        lane = dir.resolve("lane");
        gate = dir.resolve("gate");
        lock = home.resolve("work/pragmatica.git.lock");
        Files.createDirectories(home.resolve("tools"));
        Files.createDirectories(gate);
        ok(git(dir, "init", "-q", "--bare", "-b", "main", origin.toString()));
        ok(git(dir, "init", "-q", "-b", "main", lane.toString()));
        ok(git(lane, "remote", "add", "origin", origin.toString()));
        Files.createDirectories(lane.resolve(".mvn"));
        Files.writeString(lane.resolve(".mvn/maven.config"), "-T 1\n");
        c1 = commit("one");
        ok(git(lane, "push", "-q", "origin", "main", "main:refs/pull/1/head", "main:refs/pull/2/head", "main:refs/heads/stable"));
        ok(git(dir, "clone", "-q", "--mirror", origin.toString(), home.resolve("work/pragmatica.git").toString()));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-checkout"), home.resolve("tools/bb-checkout"));
        BbFixtures.executable(home.resolve("tools/bb-checkout"), Files.readString(home.resolve("tools/bb-checkout")));
        Files.writeString(dir.resolve("hook.sh"), hookScript());
        BbFixtures.executable(dir.resolve("hook.sh"), Files.readString(dir.resolve("hook.sh")));
        Files.writeString(home.resolve(".gitconfig"), "[uploadpack]\n\tpackObjectsHook = " + dir.resolve("hook.sh") + "\n");
        // the origin moves on: main and pull/1 move, pull/2 is deleted, pull/3 is new; `stable` stays at c1
        c2 = commit("two");
        ok(git(lane, "push", "-q", "-f", "origin", "main", "main:refs/pull/1/head", "main:refs/pull/3/head"));
        ok(git(lane, "push", "-q", "origin", ":refs/pull/2/head"));
    }

    @AfterEach
    void cleanup() throws Exception {
        // release anything still parked, then wait for the recorded processes to end before the temp dir goes
        Files.writeString(gate.resolve("release"), "x");
        for (var p : started) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
        // a row that failed before it released its parked git (a mutant run does that on purpose) must not leave it behind: an orphan is
        // reparented to init, so only its handle reaches it, and a shim parks until a file appears that a failed row never wrote
        for (var handle : orphans) {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
        }
        for (var handle : orphans) {
            handle.onExit().get(60, TimeUnit.SECONDS);
        }
    }

    /// parks the first pack request while `armed` exists (mkdir is the atomic "first"), says `held`, waits for `release`
    private String hookScript() {
        return "#!/bin/sh\n"
               + "G=" + gate + "\n"
               + "if [ -e \"$G/armed\" ] && mkdir \"$G/first\" 2>/dev/null; then\n"
               + "  : > \"$G/held\"\n"
               + "  i=0\n"
               + "  while [ ! -e \"$G/release\" ] && [ $i -lt 2400 ]; do sleep 0.05; i=$((i+1)); done\n"
               + "fi\n"
               + "exec \"$@\"\n";
    }

    // ---- helpers

    private Map<String, String> env(Map<String, String> extra) {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "t");
        env.put("GIT_AUTHOR_EMAIL", "t@example.invalid");
        env.put("GIT_COMMITTER_NAME", "t");
        env.put("GIT_COMMITTER_EMAIL", "t@example.invalid");
        env.putAll(extra);

        return env;
    }

    private BbFixtures.Outcome git(Path cwd, String... args) throws Exception {
        var command = new ArrayList<String>();

        command.add("git");
        command.addAll(List.of(args));

        return BbFixtures.run(command, env(Map.of()), cwd, null);
    }

    private static BbFixtures.Outcome ok(BbFixtures.Outcome run) {
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();

        return run;
    }

    private String commit(String message) throws Exception {
        Files.writeString(lane.resolve("f"), message + " " + (counter++) + "\n");
        ok(git(lane, "add", "-A"));
        ok(git(lane, "commit", "-q", "-m", message));

        return ok(git(lane, "rev-parse", "HEAD")).stdout().strip();
    }

    private void arm() throws IOException {
        Files.writeString(gate.resolve("armed"), "x");
    }

    private void release() throws IOException {
        Files.writeString(gate.resolve("release"), "x");
    }

    /// a bb-checkout run, started and left running; stdout and stderr go to files. HUP is reset to its default for the run: a build started
    /// under nohup/setsid hands its children SIGHUP as ignored, and an ignoring process cannot be killed by it (nothing to guarantee then)
    private Run start(String name, String ref, Map<String, String> extra) throws IOException {
        var out = dir.resolve(name + ".out");
        var err = dir.resolve(name + ".err");
        var env = new HashMap<>(extra);

        env.put("BB_OWNER", "s1");
        var builder = BbFixtures.process(List.of("env", "--default-signal=HUP", "bash", home.resolve("tools/bb-checkout").toString(), name, ref), env(env));

        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        var process = builder.start();

        started.add(process);

        return new Run(name, process, out, err);
    }

    private Run start(String name, String ref) throws IOException {
        return start(name, ref, Map.of());
    }

    private record Run(String name, Process process, Path out, Path err) {
        String stdout() throws IOException {
            return Files.readString(out);
        }

        String stderr() throws IOException {
            return Files.readString(err);
        }

        List<String> stderrLines() throws IOException {
            return Files.readAllLines(err);
        }

        int finish() throws InterruptedException {
            assertThat(process.waitFor(DEADLINE_MILLIS, TimeUnit.MILLISECONDS)).as(name + " did not finish").isTrue();

            return process.exitValue();
        }
    }

    private static void await(String what, BooleanSupplier condition) {
        var end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);

        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("timed out waiting for " + what).isLessThan(end);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    private void awaitHeld(Run first) {
        await("the first fetch to be parked", () -> Files.exists(gate.resolve("held")) || !first.process().isAlive());
        assertThat(Files.exists(gate.resolve("held"))).as("the first run reached its pack request: " + first.name()).isTrue();
    }

    /// the second caller has either finished (no lock: it ran past the parked first) or is queueing on the lock
    private void awaitFinishedOrQueued(Run second) {
        await(second.name() + " finished or queueing",
              () -> !second.process().isAlive() || contains(second.err(), "waiting for the mirror lock"));
    }

    private static boolean contains(Path file, String text) {
        try {
            return Files.exists(file) && Files.readString(file).contains(text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path co(String name) {
        return home.resolve("work/co").resolve(name);
    }

    private String mirrorRef(String ref) throws Exception {
        var run = git(home.resolve("work/pragmatica.git"), "rev-parse", "--verify", "-q", ref);

        return run.rc() == 0 ? run.stdout().strip() : null;
    }

    private String head(String name) throws Exception {
        return ok(git(co(name), "rev-parse", "HEAD")).stdout().strip();
    }

    private static void assertNoRefRace(Run run) throws IOException {
        assertThat(run.stderr()).as(run.name() + " stderr").doesNotContain("cannot lock ref").doesNotContain("Unable to create");
    }

    /// the lock is held by someone: a non-blocking flock on the lock file fails
    private boolean lockHeld() throws Exception {
        return BbFixtures.run(List.of("flock", "-n", lock.toString(), "true"), env(Map.of()), null, null).rc() != 0;
    }

    /// the process itself has the lock file open with the flock on it (/proc/<pid>/fdinfo of an fd that is the lock file carries a `lock:` line):
    /// holding the lock through an ancestor that happens to outlive it is not the same, that ancestor can die first
    private boolean holdsTheLock(long pid) throws IOException {
        try (var fds = Files.list(Path.of("/proc/" + pid + "/fd"))) {
            for (var fd : (Iterable<Path>) fds::iterator) {
                try {
                    if (Files.isSameFile(fd, lock) && Files.readAllLines(Path.of("/proc/" + pid + "/fdinfo/" + fd.getFileName())).stream().anyMatch(l -> l.startsWith("lock:"))) {
                        return true;
                    }
                } catch (java.nio.file.NoSuchFileException gone) {
                    // the fd closed between the listing and the read: not the lock
                }
            }
        }

        return false;
    }

    /// the SigIgn mask of a live process (bit 0 is SIGHUP)
    private static long sigIgn(long pid) throws IOException {
        for (var line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
            if (line.startsWith("SigIgn:")) {
                return Long.parseUnsignedLong(line.substring(7).trim(), 16);
            }
        }
        throw new IOException("no SigIgn in /proc/" + pid + "/status");
    }

    private void signal(String signal, long pid) throws Exception {
        ok(BbFixtures.run(List.of("kill", "-" + signal, Long.toString(pid)), env(Map.of()), null, null));
    }

    /// the pid a shim or hook wrote to `file` (the file may exist before its content does)
    private long awaitPid(Path file, Run run) throws IOException {
        await("a pid in " + file.getFileName(), () -> pidIn(file) || !run.process().isAlive());
        assertThat(pidIn(file)).as(run.name() + " reached " + file.getFileName() + "; stderr: " + run.stderr()).isTrue();

        return Long.parseLong(Files.readString(file).strip());
    }

    private static boolean pidIn(Path file) {
        try {
            return Files.exists(file) && Files.readString(file).endsWith("\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean ends(ProcessHandle handle, long seconds) throws Exception {
        try {
            handle.onExit().get(seconds, TimeUnit.SECONDS);

            return true;
        } catch (java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private void awaitLockFree() {
        await("the lock to be free", () -> {
            try {
                return !lockHeld();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---- the race

    @Test
    void aSecondCaller_whileTheFirstIsParkedMidFetch_queues_andBothSucceed() throws Exception {
        arm();
        var first = start("s1-a", c2);

        awaitHeld(first);
        var second = start("s1-b", c2);

        awaitFinishedOrQueued(second);
        release();

        assertThat(first.finish()).as(first.stderr()).isZero();
        assertThat(second.finish()).as(second.stderr()).isZero();
        assertNoRefRace(first);
        assertNoRefRace(second);
        assertThat(contains(second.err(), "waiting for the mirror lock")).as("the second caller said it queues").isTrue();
        assertThat(second.stderr()).as("it names the holder").contains("held by pid:", " " + first.process().pid() + " (bash)");
        assertThat(head("s1-a")).isEqualTo(c2);
        assertThat(head("s1-b")).isEqualTo(c2);
        assertThat(mirrorRef("refs/heads/main")).isEqualTo(c2);
        assertThat(mirrorRef("refs/pull/1/head")).isEqualTo(c2);
        assertThat(mirrorRef("refs/pull/3/head")).isEqualTo(c2);
        assertThat(mirrorRef("refs/pull/2/head")).as("deleted upstream, pruned").isNull();
    }

    @Test
    void aLockWaitThatExpires_isRefusedWithExit6_andLeavesNothing() throws Exception {
        arm();
        var first = start("s1-a", c2);

        awaitHeld(first);
        var second = start("s1-b", c2, Map.of("BB_MIRROR_LOCK_WAIT", "1"));

        assertThat(second.finish()).as(second.stderr()).isEqualTo(6);
        assertThat(second.stderr()).contains("mirror lock " + lock + " not obtained within 1s");
        assertThat(Files.exists(co("s1-b"))).as("no checkout dir after the refusal").isFalse();
        release();

        assertThat(first.finish()).as(first.stderr()).isZero();
        assertThat(head("s1-a")).isEqualTo(c2);
    }

    /// the lock fd is inherited: when bb-checkout dies the kernel keeps the lock for the git it started. A second caller must WAIT (and say who
    /// holds it) while that fetch is parked, and SUCCEED at the right sha once it ends. Closing the fd for the fetch frees the lock at once.
    private void aSecondCallerWaitsForTheGitOfABbCheckoutKilledWith(String signal) throws Exception {
        arm();
        var first = start("s1-a", c2);

        awaitHeld(first);
        var timeoutPid = first.process().children().findFirst().orElseThrow().pid();

        first.process().descendants().forEach(orphans::add);
        assertThat(sigIgn(first.process().pid()) & 1).as("SIGHUP is not ignored by bb-checkout").isZero();
        signal(signal, first.process().pid());
        first.process().onExit().get(30, TimeUnit.SECONDS);
        assertThat(lockHeld()).as("the lock stays with the git that bb-checkout started").isTrue();
        var second = start("s1-b", c2, Map.of("BB_MIRROR_LOCK_WAIT", "60"));

        awaitFinishedOrQueued(second);
        assertThat(second.process().isAlive()).as("the second caller waits while the fetch is parked: " + second.stderr()).isTrue();
        assertThat(second.stderr()).contains("waiting for the mirror lock", "held by pid:", " " + timeoutPid + " (timeout)");
        release();

        assertThat(second.finish()).as(second.stderr()).isZero();
        assertNoRefRace(second);
        assertThat(head("s1-b")).isEqualTo(c2);
        assertThat(mirrorRef("refs/heads/main")).isEqualTo(c2);
        assertThat(mirrorRef("refs/pull/2/head")).as("deleted upstream, pruned").isNull();
    }

    @Test
    void aSecondCaller_waitsForTheGitOfABbCheckoutKilledWithTerm_andThenSucceedsAtTheRightSha() throws Exception {
        aSecondCallerWaitsForTheGitOfABbCheckoutKilledWith("TERM");
    }

    @Test
    void aSecondCaller_waitsForTheGitOfABbCheckoutKilledWithHup_andThenSucceedsAtTheRightSha() throws Exception {
        aSecondCallerWaitsForTheGitOfABbCheckoutKilledWith("HUP");
    }

    @Test
    void aSecondCaller_waitsForTheGitOfABbCheckoutKilledWithKill_andThenSucceedsAtTheRightSha() throws Exception {
        aSecondCallerWaitsForTheGitOfABbCheckoutKilledWith("KILL");
    }

    /// a git step other than the fetch, parked by a shim: bb-checkout is killed, the parked git alone must keep the lock until it ends
    private void theLockStaysWithAGitParkedIn(String verb) throws Exception {
        var run = start("s1-a", c2, gitShim(verb, "mkdir " + gate.resolve("parked-" + verb) + " 2>/dev/null && { echo $$ > " + gate.resolve("pid-" + verb)
                                                  + "; while [ ! -e " + gate.resolve("release-" + verb) + " ]; do sleep 0.05; done; }"));
        var parked = ProcessHandle.of(awaitPid(gate.resolve("pid-" + verb), run)).orElseThrow();

        orphans.add(parked);
        assertThat(holdsTheLock(parked.pid())).as("the parked git " + verb + " itself has the lock fd: an enclosing subshell is not the holder").isTrue();
        signal("KILL", run.process().pid());
        run.process().onExit().get(30, TimeUnit.SECONDS);
        assertThat(lockHeld()).as("the lock stays with the parked git " + verb).isTrue();
        Files.writeString(gate.resolve("release-" + verb), "x");
        assertThat(ends(parked, 60)).as("the parked git ended").isTrue();
        awaitLockFree();
    }

    @Test
    void theLockStaysWithAGitParkedInTheRevParse_whenBbCheckoutIsKilled() throws Exception {
        theLockStaysWithAGitParkedIn("rev-parse");
    }

    @Test
    void theLockStaysWithAGitParkedInTheClone_whenBbCheckoutIsKilled() throws Exception {
        theLockStaysWithAGitParkedIn("clone");
    }

    // ---- bounds and fd hygiene

    /// a `git` ahead of the real one on PATH: a call with `verb` among its arguments runs `body` first (it may exec); everything else is the real git
    private Map<String, String> gitShim(String verb, String body) throws Exception {
        var real = realGit();
        var shim = Files.createDirectories(dir.resolve("shim"));
        var script = "#!/bin/sh\n"
                     + "case \" $* \" in *\" " + verb + " \"*)\n" + body + "\n;; esac\n"
                     + "exec " + real + " \"$@\"\n";

        Files.writeString(shim.resolve("git"), script);
        BbFixtures.executable(shim.resolve("git"), script);

        return Map.of("PATH", shim + ":" + System.getenv("PATH"));
    }

    private Map<String, String> gitShim(String fetchBody) throws Exception {
        return gitShim("fetch", fetchBody);
    }

    private static String realGit() {
        for (var entry : System.getenv("PATH").split(":")) {
            var candidate = Path.of(entry, "git");

            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        throw new IllegalStateException("no git on PATH");
    }

    @Test
    void aHungFetch_isBounded_refused_andLeavesNothing() throws Exception {
        var env = new HashMap<>(gitShim("exec sleep 600"));

        env.put("BB_MIRROR_FETCH_TIMEOUT", "1");
        var hung = start("s1-a", c2, env);

        assertThat(hung.finish()).as(hung.stderr()).isEqualTo(7);
        assertThat(hung.stderr()).contains("the mirror fetch exceeded 1s");
        assertThat(Files.exists(co("s1-a"))).as("no checkout dir after the refusal").isFalse();
        // the lock went with the script: the next caller does not queue and succeeds
        var next = start("s1-b", c2, Map.of("BB_MIRROR_LOCK_WAIT", "60"));

        assertThat(next.finish()).as(next.stderr()).isZero();
        assertThat(next.stderr()).doesNotContain("waiting for the mirror lock");
        assertThat(head("s1-b")).isEqualTo(c2);
    }

    @Test
    void aFetchThatIgnoresTerm_isKilledAfterTheGrace_andIsStillExit7() throws Exception {
        var env = new HashMap<>(gitShim("trap '' TERM\nexec sleep 600"));

        env.put("BB_MIRROR_FETCH_TIMEOUT", "1");
        var stubborn = start("s1-a", c2, env);

        assertThat(stubborn.finish()).as(stubborn.stderr()).isEqualTo(7);
        assertThat(stubborn.stderr()).contains("the mirror fetch exceeded 1s");
        assertThat(Files.exists(co("s1-a"))).isFalse();
    }

    /// rc 137 is also what an outside SIGKILL (the OOM killer) looks like: well before the bound it is not an expiry
    @Test
    void anOutsideSigkill_ofTheFetch_isNotCalledABoundExpiry_andKeeps137() throws Exception {
        var run = start("s1-a", c2, gitShim("kill -KILL $$"));

        assertThat(run.finish()).as(run.stderr()).isEqualTo(137);
        assertThat(run.stderr()).contains("status 137", "before its 300s bound", "not a bound expiry").doesNotContain("exceeded");
        assertThat(Files.exists(co("s1-a"))).isFalse();
    }

    /// 124 is also what a git that exits 124 on its own looks like: before the bound it is passed on, not called an expiry
    @Test
    void aStatus124FromGitItself_beforeTheBound_isNotCalledAnExpiry() throws Exception {
        var run = start("s1-a", c2, gitShim("exit 124"));

        assertThat(run.finish()).as(run.stderr()).isEqualTo(124);
        assertThat(run.stderr()).contains("status 124", "before its 300s bound", "not a bound expiry").doesNotContain("exceeded");
        assertThat(Files.exists(co("s1-a"))).isFalse();
    }

    /// timeout runs in its own process group and signals all of it: a helper of the git that ignores TERM dies at timeout's KILL
    @Test
    void timeoutsKill_reachesTheWholeProcessGroup_soAHelperThatIgnoresTermDies() throws Exception {
        var helperPid = dir.resolve("helper.pid");
        var env = new HashMap<>(gitShim("sh -c 'trap \"\" TERM; echo $$ > " + helperPid + "; exec sleep 600' &\ntrap '' TERM\nexec sleep 600"));

        env.put("BB_MIRROR_FETCH_TIMEOUT", "1");
        var run = start("s1-a", c2, env);
        var helper = ProcessHandle.of(awaitPid(helperPid, run)).orElseThrow();

        orphans.add(helper);
        assertThat(run.finish()).as(run.stderr()).isEqualTo(7);
        assertThat(ends(helper, 20)).as("the TERM-ignoring helper of the fetch was killed with the group").isTrue();
        assertThat(Files.exists(co("s1-a"))).isFalse();
    }

    /// the named limit: the `timeout` process itself killed leaves its git unbounded, running and holding the lock; the next caller names that git and
    /// gives up with exit 6 after its wait
    @Test
    void aKilledTimeout_leavesItsGitRunningAndHoldingTheLock_aNamedLimit() throws Exception {
        var gitPid = dir.resolve("git.pid");
        var run = start("s1-a", c2, gitShim("echo $$ > " + gitPid + "\nexec sleep 600"));
        var git = ProcessHandle.of(awaitPid(gitPid, run)).orElseThrow();

        orphans.add(git);
        signal("KILL", git.parent().orElseThrow().pid());

        assertThat(run.finish()).as(run.stderr()).isEqualTo(137);
        assertThat(run.stderr()).contains("not a bound expiry");
        assertThat(git.isAlive()).as("the git outlives its killed timeout").isTrue();
        assertThat(lockHeld()).as("and holds the lock").isTrue();
        var next = start("s1-b", c2, Map.of("BB_MIRROR_LOCK_WAIT", "1"));

        assertThat(next.finish()).as(next.stderr()).isEqualTo(6);
        assertThat(next.stderr()).contains("held by pid:", " " + git.pid() + " (");
        git.destroyForcibly();
        assertThat(ends(git, 20)).isTrue();
        awaitLockFree();
    }

    /// a bound that is not 1 to 5 ASCII digits (at least 1) would mean no bound, or a timer that cannot be set: refused before anything is created
    @Test
    void aBoundThatIsNotOneToFiveAsciiDigitsAtLeastOne_isRefusedWithExit2_andLeavesNothing() throws Exception {
        var bad = List.of("BB_MIRROR_FETCH_TIMEOUT=0", "BB_MIRROR_FETCH_TIMEOUT=0000", "BB_MIRROR_FETCH_TIMEOUT=100000", "BB_MIRROR_FETCH_TIMEOUT=12s",
                          "BB_MIRROR_FETCH_TIMEOUT=-5", "BB_MIRROR_FETCH_TIMEOUT=\u0663", "BB_MIRROR_LOCK_WAIT=0", "BB_MIRROR_LOCK_WAIT=999999",
                          "BB_MIRROR_LOCK_WAIT=1.5");
        var n = 0;

        for (var entry : bad) {
            var name = "s1-bad" + n++;
            var run = start(name, c2, Map.of(entry.substring(0, entry.indexOf('=')), entry.substring(entry.indexOf('=') + 1)));

            assertThat(run.finish()).as(entry + ": " + run.stderr()).isEqualTo(2);
            assertThat(run.stderr()).as(entry).contains("must be 1 to 5 ASCII digits");
            assertThat(Files.exists(co(name))).as(entry + ": nothing created").isFalse();
        }
        assertThat(mirrorRef("refs/heads/main")).as("no fetch ran").isEqualTo(c1);
    }

    @Test
    void aBoundOfFiveDigitsOrWithLeadingZeros_isAccepted() throws Exception {
        var run = start("s1-a", c2, Map.of("BB_MIRROR_FETCH_TIMEOUT", "08", "BB_MIRROR_LOCK_WAIT", "99999"));

        assertThat(run.finish()).as(run.stderr()).isZero();
        assertThat(head("s1-a")).isEqualTo(c2);
    }

    /// gc runs in the foreground, inside the fetch (and so inside the lock and the bound): a repack parked by a wrapper is a DESCENDANT of the
    /// still-running bb-checkout. A detached gc would be an orphan, outliving the script.
    @Test
    void autoGc_runsInTheForegroundInsideTheFetch_notDetached() throws Exception {
        var mirror = home.resolve("work/pragmatica.git");
        var real = Path.of(ok(git(dir, "--exec-path")).stdout().strip());
        var wrapper = Files.createDirectories(dir.resolve("execpath"));

        ok(git(mirror, "repack", "-q", "-a", "-d"));
        ok(git(mirror, "config", "gc.autoPackLimit", "1"));
        ok(git(mirror, "config", "fetch.unpackLimit", "1"));
        try (var entries = Files.list(real)) {
            for (var entry : entries.toList()) {
                if (!entry.getFileName().toString().equals("git")) {
                    Files.createSymbolicLink(wrapper.resolve(entry.getFileName().toString()), entry);
                }
            }
        }
        var script = "#!/bin/sh\nif [ \"$1\" = repack ] && mkdir " + gate.resolve("repack") + " 2>/dev/null; then\n  echo $$ > " + gate.resolve("repack.pid")
                     + "\n  while [ ! -e " + gate.resolve("release") + " ]; do sleep 0.05; done\nfi\nexec " + real.resolve("git") + " \"$@\"\n";

        Files.writeString(wrapper.resolve("git"), script);
        BbFixtures.executable(wrapper.resolve("git"), script);
        var run = start("s1-a", c2, Map.of("GIT_EXEC_PATH", wrapper.toString()));
        var repack = awaitPid(gate.resolve("repack.pid"), run);

        ProcessHandle.of(repack).ifPresent(orphans::add);
        assertThat(run.process().isAlive()).as("bb-checkout still runs while gc is parked: gc is inside the fetch").isTrue();
        assertThat(run.process().descendants().anyMatch(d -> d.pid() == repack)).as("the parked repack is a descendant of bb-checkout: gc did not detach").isTrue();
        release();

        assertThat(run.finish()).as(run.stderr()).isZero();
        assertThat(head("s1-a")).isEqualTo(c2);
    }

    private static final List<String> BLOCKING = List.of("-type", "f", "(", "-name", "*.lock", "-o", "-name", "packed-refs.new", ")");

    /// every *.lock and packed-refs.new anywhere in the mirror (the blocking ones among them, and decoys that do not block)
    private List<String> lockLike() throws Exception {
        var command = new ArrayList<String>(List.of("find", home.resolve("work/pragmatica.git").toString()));

        command.addAll(BLOCKING);
        var found = BbFixtures.run(command, env(Map.of()), null, null);

        return found.stdout().lines().sorted().toList();
    }

    private static boolean blocks(String file) {
        return file.contains("/refs/") || file.endsWith("/HEAD.lock") || file.endsWith("/packed-refs.lock") || file.endsWith("/packed-refs.new");
    }

    /// the named limit: a git killed -9 while it updates refs leaves *.lock files and packed-refs.new (measured: ref update -> <ref>.lock;
    /// packed-refs rewrite -> packed-refs.lock + packed-refs.new; pack receipt -> only objects/pack/tmp_pack_*, which does not block). Later
    /// callers fail with git's rc and are told which files (ALL of them, not a capped list) and the operator command; the script deletes nothing;
    /// the printed command, run as printed from a HOME with a space, removes exactly those (not tmp_pack_*, not the *.lock that do not block a fetch)
    /// and the next caller succeeds
    @Test
    void aFetchKilledAtTheRefUpdate_leavesBlockingFiles_thatAreListedNotDeleted_andThePrintedCommandRecovers() throws Exception {
        var mirror = home.resolve("work/pragmatica.git");
        var hook = mirror.resolve("hooks/reference-transaction");
        var hookText = "#!/bin/sh\n[ \"$1\" = prepared ] && [ -e " + gate.resolve("kill") + " ] && kill -KILL $PPID\nexit 0\n";
        var tmpPack = mirror.resolve("objects/pack/tmp_pack_left");
        // decoys: do not block a fetch (measured), must be neither listed nor removed
        var decoys = List.of(mirror.resolve("objects/info/commit-graph.lock"), mirror.resolve("config.lock"), mirror.resolve("shallow.lock"),
                             mirror.resolve("hooks/pre-push.lock"));

        Files.createDirectories(hook.getParent());
        Files.writeString(hook, hookText);
        BbFixtures.executable(hook, hookText);
        Files.writeString(tmpPack, "partial");
        Files.writeString(gate.resolve("kill"), "x");
        var killed = start("s1-a", c2);

        assertThat(killed.finish()).as(killed.stderr()).isEqualTo(137);
        var extra = new ArrayList<Path>();

        extra.add(mirror.resolve("HEAD.lock"));
        Files.createDirectories(mirror.resolve("refs/tags"));
        for (var i = 0; i < 25; i++) {
            extra.add(mirror.resolve("refs/tags/t" + i + ".lock"));
        }
        for (var file : extra) {
            Files.writeString(file, "");
        }
        for (var decoy : decoys) {
            Files.writeString(decoy, "");
        }
        var left = lockLike();
        var blocking = left.stream().filter(BbCheckoutLockIT::blocks).toList();

        assertThat(blocking).as("the killed fetch left blocking files in the mirror").isNotEmpty();
        assertThat(blocking).as("packed-refs.new is among them").anyMatch(f -> f.endsWith("/packed-refs.new"));
        assertThat(blocking.size()).as("more than the 20 a capped listing would show").isGreaterThan(25);
        Files.delete(gate.resolve("kill"));
        var next = start("s1-b", c2);

        assertThat(next.finish()).as(next.stderr()).isNotZero();
        assertThat(next.stderr()).contains("File exists", "blocking files in the mirror", "only when no git runs on the mirror");
        var stderrLines = next.stderrLines();

        blocking.forEach(f -> assertThat(stderrLines).as("listed: " + f).anyMatch(l -> l.contains(f)));
        decoys.forEach(f -> assertThat(stderrLines).as("not listed: " + f).noneMatch(l -> l.contains(f.toString()) && l.startsWith("-")));
        assertThat(lockLike()).as("the script deleted none of them").isEqualTo(left);
        assertThat(Files.exists(tmpPack)).as("the script touched no tmp_pack").isTrue();
        assertThat(Files.exists(co("s1-b"))).isFalse();
        var at = stderrLines.indexOf(stderrLines.stream().filter(l -> l.contains("the command below removes exactly")).findFirst().orElseThrow());
        var command = stderrLines.get(at + 1);

        assertThat(command).startsWith("flock " + lock.toString().replace(" ", "\\ ") + " find ");
        var operator = BbFixtures.run(List.of("bash", "-c", command), env(Map.of()), null, null);

        assertThat(operator.rc()).as(operator.stderr()).isZero();
        assertThat(lockLike().stream().filter(BbCheckoutLockIT::blocks).toList()).as("the printed command removed every blocking file").isEmpty();
        assertThat(lockLike()).as("and only those: the decoys stay").hasSize(decoys.size()).allMatch(f -> decoys.stream().anyMatch(d -> d.toString().equals(f)));
        assertThat(Files.exists(tmpPack)).as("the printed command left tmp_pack alone").isTrue();
        var recovered = start("s1-c", c2);

        assertThat(recovered.finish()).as(recovered.stderr()).isZero();
        assertThat(head("s1-c")).isEqualTo(c2);
    }

    /// the named limit: a child that outlives its git (here: left running in the background) keeps the lock for as long as it lives
    @Test
    void aChildThatOutlivesItsGit_keepsTheLockAsLongAsItLives_aNamedLimit() throws Exception {
        var pidFile = dir.resolve("bg.pid");
        var body = "sleep 600 > /dev/null 2>&1 < /dev/null &\n" + "echo $! > " + pidFile;
        var run = start("s1-a", c2, gitShim(body));

        assertThat(run.finish()).as(run.stderr()).isZero();
        var background = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).strip())).orElseThrow();

        orphans.add(background);
        assertThat(background.isAlive()).as("the child that outlives bb-checkout is still running").isTrue();
        assertThat(lockHeld()).as("the lock is held by it").isTrue();
        background.destroyForcibly();
        assertThat(ends(background, 20)).isTrue();
        awaitLockFree();
    }

    // ---- many callers, refs moving

    @Test
    void eightCallers_whileRefsMove_allSucceedAtTheRightSha() throws Exception {
        var spare = new ArrayList<String>();

        for (var i = 0; i < 12; i++) {
            spare.add(commit("spare" + i));
        }
        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var rounds = Integer.getInteger("bb.lockit.rounds", 3);
        var red = new ArrayList<String>();
        var mover = Thread.ofPlatform().start(() -> move(spare, stop, failure));

        try {
            for (var round = 0; round < rounds; round++) {
                var runs = new ArrayList<Run>();

                for (var i = 0; i < 8; i++) {
                    runs.add(start("s1-r" + round + "-" + i, "stable"));
                }
                var roundRed = false;

                for (var run : runs) {
                    var rc = run.finish();

                    // the moving ORIGIN, not the mirror: a ref the mover deletes between upload-pack's advertisement and the want makes git itself fail
                    // ("not our ref 000...", measured 1 caller in ~480 on a build host with a normal tool); the lock does not reach the origin
                    if (rc != 0 && run.stderr().contains("upload-pack: not our ref")) {
                        continue;
                    }
                    if (rc != 0 || run.stderr().contains("cannot lock ref") || !run.stdout().contains("sha=" + c1) || !c1.equals(head(run.name()))) {
                        roundRed = true;
                        if (red.size() < 3) {
                            red.add(run.name() + " rc=" + rc + ": " + run.stderr().replace("\n", " | "));
                        }
                    }
                }
                if (roundRed) {
                    red.add("round " + round + " red");
                }
            }
        } finally {
            stop.set(true);
            mover.join();
        }
        assertThat(failure.get()).as("the ref mover").isNull();
        assertThat(red).as("rounds red: " + red.stream().filter(r -> r.startsWith("round ")).count() + " of " + rounds).isEmpty();
    }

    /// adds, force-moves and deletes refs/heads and refs/pull refs in the fake origin until told to stop
    private void move(List<String> spare, AtomicBoolean stop, AtomicReference<Throwable> failure) {
        try {
            var n = 0;

            while (!stop.get()) {
                var sha = spare.get(n % spare.size());

                ok(git(lane, "push", "-q", "-f", "origin", sha + ":refs/pull/" + (10 + n % 4) + "/head"));
                ok(git(lane, "push", "-q", "-f", "origin", sha + ":refs/heads/churn/" + (n % 5)));
                ok(git(lane, "push", "-q", "-f", "origin", spare.get((n + 1) % spare.size()) + ":refs/pull/1/head"));

                if (n % 3 == 2) {
                    // a delete of a ref that is already gone is refused by git: that is not the point here
                    git(lane, "push", "-q", "origin", ":refs/pull/" + (10 + (n + 1) % 4) + "/head");
                    git(lane, "push", "-q", "origin", ":refs/heads/churn/" + ((n + 2) % 5));
                }
                n++;
            }
        } catch (Throwable t) {
            failure.set(t);
        }
    }
}
