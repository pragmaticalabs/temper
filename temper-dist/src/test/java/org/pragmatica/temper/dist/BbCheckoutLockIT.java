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
/// `~/work/pragmatica.git.lock` around fetch, rev-parse and clone, a bounded wait (exit 6), a bounded fetch (exit 7), the lock fd closed in
/// every child, and gc in the foreground.
///
/// The race is made deterministic, not statistical: a `uploadpack.packObjectsHook` (global config of the fixture HOME) parks the FIRST pack
/// request of a fetch AFTER that fetch has read the mirror's old ref values and BEFORE it takes the ref locks; a second caller then runs
/// to completion (without the lock) or queues (with it); the first is released afterwards. Without the lock the first run ALWAYS ends
/// with "is at Y but expected X". The stress row (8 callers, 3 rounds, refs added, force-moved and deleted meanwhile) is statistical.
/// The waits below poll a marker or a process state with a deadline: the marker/state is the event, there is no fixed sleep.
///
/// Mutations: remove the `flock` lines -> [#aSecondCaller_whileTheFirstIsParkedMidFetch_queues_andBothSucceed] (always red) and
/// [#eightCallers_whileRefsMove_allSucceedAtTheRightSha] (usually red); drop `9>&-` from the fetch ->
/// [#theLockFd_isNotInheritedByAChildThatOutlivesTheScript]; drop `timeout` -> [#aHungFetch_isBounded_refused_andLeavesNothing];
/// drop the `-w` bound -> [#aLockWaitThatExpires_isRefusedWithExit6_andLeavesNothing]; call every 137 an expiry ->
/// [#anOutsideSigkill_ofTheFetch_isNotCalledABoundExpiry_andKeeps137]; delete the lock files -> [#aFetchKilledAtTheRefUpdate_leavesLockFiles_thatAreListedNotDeleted_andTheOperatorCommandRecovers].
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
        home = dir.resolve("home");
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
        Files.writeString(home.resolve("hook.sh"), hookScript());
        BbFixtures.executable(home.resolve("hook.sh"), Files.readString(home.resolve("hook.sh")));
        Files.writeString(home.resolve(".gitconfig"), "[uploadpack]\n\tpackObjectsHook = " + home.resolve("hook.sh") + "\n");
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

    /// a bb-checkout run, started and left running; stdout and stderr go to files
    private Run start(String name, String ref, Map<String, String> extra) throws IOException {
        var out = dir.resolve(name + ".out");
        var err = dir.resolve(name + ".err");
        var env = new HashMap<>(extra);

        env.put("BB_OWNER", "s1");
        var builder = BbFixtures.process(List.of("bash", home.resolve("tools/bb-checkout").toString(), name, ref), env(env));

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

    @Test
    void aKilledHolder_releasesTheLock_atOnce() throws Exception {
        arm();
        var first = start("s1-a", c2);

        awaitHeld(first);
        first.process().descendants().forEach(orphans::add);
        first.process().destroyForcibly();
        first.process().onExit().get(30, TimeUnit.SECONDS);
        var second = start("s1-b", c2, Map.of("BB_MIRROR_LOCK_WAIT", "60"));

        assertThat(second.finish()).as(second.stderr()).isZero();
        assertThat(second.stderr()).as("the lock was free at once: the caller never queued").doesNotContain("waiting for the mirror lock");
        assertThat(head("s1-b")).isEqualTo(c2);
    }

    // ---- bounds and fd hygiene

    /// a `git` ahead of the real one on PATH: `fetch` runs `fetchBody` first (it may exec); everything else is the real git
    private Map<String, String> gitShim(String fetchBody) throws Exception {
        var real = realGit();
        var shim = Files.createDirectories(dir.resolve("shim"));
        var script = "#!/bin/sh\n"
                     + "case \" $* \" in *\" fetch \"*)\n" + fetchBody + "\n;; esac\n"
                     + "exec " + real + " \"$@\"\n";

        Files.writeString(shim.resolve("git"), script);
        BbFixtures.executable(shim.resolve("git"), script);

        return Map.of("PATH", shim + ":" + System.getenv("PATH"));
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
        assertThat(run.stderr()).contains("killed by SIGKILL", "before its 300s bound", "not a bound expiry").doesNotContain("exceeded");
        assertThat(Files.exists(co("s1-a"))).isFalse();
    }

    private List<String> lockFiles() throws Exception {
        var found = BbFixtures.run(List.of("find", home.resolve("work/pragmatica.git").toString(), "-name", "*.lock", "-type", "f"),
                                   env(Map.of()),
                                   null,
                                   null);

        return found.stdout().lines().toList();
    }

    /// the named limit: a git killed -9 at the ref-update point leaves *.lock files; later callers fail with git's rc, are told which
    /// files and the operator command, and nothing is deleted by the script; the operator command makes the next caller succeed
    @Test
    void aFetchKilledAtTheRefUpdate_leavesLockFiles_thatAreListedNotDeleted_andTheOperatorCommandRecovers() throws Exception {
        var hook = home.resolve("work/pragmatica.git/hooks/reference-transaction");
        var hookText = "#!/bin/sh\n[ \"$1\" = prepared ] && [ -e " + gate.resolve("kill") + " ] && kill -KILL $PPID\nexit 0\n";

        Files.createDirectories(hook.getParent());
        Files.writeString(hook, hookText);
        BbFixtures.executable(hook, hookText);
        Files.writeString(gate.resolve("kill"), "x");
        var killed = start("s1-a", c2);

        assertThat(killed.finish()).as(killed.stderr()).isEqualTo(137);
        var left = lockFiles();

        assertThat(left).as("the killed fetch left lock files in the mirror").isNotEmpty();
        Files.delete(gate.resolve("kill"));
        var next = start("s1-b", c2);

        assertThat(next.finish()).as(next.stderr()).isNotZero();
        assertThat(next.stderr()).contains("File exists", "lock files in the mirror", "-name '*.lock' -type f -delete");
        var stderrLines = next.stderrLines();

        left.forEach(f -> assertThat(stderrLines).as("listed: " + f).anyMatch(l -> l.contains(f)));
        assertThat(lockFiles()).as("the script deleted none of them").isEqualTo(left);
        assertThat(Files.exists(co("s1-b"))).isFalse();
        var operator = BbFixtures.run(List.of("find", home.resolve("work/pragmatica.git").toString(), "-name", "*.lock", "-type", "f", "-delete"),
                                      env(Map.of()),
                                      null,
                                      null);

        assertThat(operator.rc()).isZero();
        var recovered = start("s1-c", c2);

        assertThat(recovered.finish()).as(recovered.stderr()).isZero();
        assertThat(head("s1-c")).isEqualTo(c2);
    }

    @Test
    void theLockFd_isNotInheritedByAChildThatOutlivesTheScript() throws Exception {
        var pidFile = dir.resolve("bg.pid");
        var body = "sleep 600 > /dev/null 2>&1 < /dev/null &\n" + "echo $! > " + pidFile;
        var run = start("s1-a", c2, gitShim(body));

        assertThat(run.finish()).as(run.stderr()).isZero();
        var pid = Long.parseLong(Files.readString(pidFile).strip());
        var background = ProcessHandle.of(pid).orElseThrow();

        orphans.add(background);
        assertThat(background.isAlive()).as("the child that outlives bb-checkout is still running").isTrue();
        var probe = BbFixtures.run(List.of("flock", "-n", lock.toString(), "true"), env(Map.of()), null, null);

        background.destroyForcibly();
        assertThat(probe.rc()).as("the lock is free although a child of the fetch is still alive: " + probe.stderr()).isZero();
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

                    if (rc != 0 || run.stderr().contains("cannot lock ref") || !run.stdout().contains("sha=" + c1) || !c1.equals(head(run.name()))) {
                        roundRed = true;
                        if (red.size() < 3) {
                            red.add(run.name() + " rc=" + rc + ": " + run.stderr().lines().findFirst().orElse(""));
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
