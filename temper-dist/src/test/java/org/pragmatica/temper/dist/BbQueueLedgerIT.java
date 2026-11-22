package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// The LAUNCH LEDGER of `ops/bb/bb-queue-lib.sh` (CTO ruling 2026-11-19): the cap counts what the queue launched, recorded at spawn
/// as "<ticket> <pid> <start> <weight>" in <queue>/launches under the queue lock; an entry counts while its pid runs with the recorded
/// start time. No process pattern is an input: every launch here is a java process that the reactor pattern (set to match nothing)
/// never sees. The waiter holds the queue lock only for the slot decision and the ledger write, never while its launch runs.
///
/// Probes: make ledger_count return reactors() -> [#aRunningJavaLaunch_countsAgainstTheCap] red; drop the start-time comparison in
/// ledger_live -> [#aRecycledPid_isNotCounted_andReapDropsIt] red; restore bb-build's wait-for-a-visible-reactor loop under the lock
/// (or drop ledger_add) -> [#aSecondWaiter_takesASlot_whileTheFirstLaunchRuns] red (and the first test, for a dropped ledger_add).
///
/// The same waiter harness also pins bb-build's cleanup after a SIGKILLed bb-cp-record (vbt4 L4): on rc 124/137 it kills the process
/// group named in <co>/.bb/cp-record.pgid only when that pid still has the recorded start time, never by pattern. Probes: drop the kill
/// -> [#aSigkilledCpRecord_hasItsRecordedMavenGroupKilled] red; drop the start-time check -> [#aPgidFileWithAnotherStartTime_killsNothing] red.
class BbQueueLedgerIT {
    private static final String ME = "led";

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private Path capFile;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock, pgrep and GNU tools");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        BbFixtures.executable(tools.resolve("bb-build"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        BbFixtures.executable(tools.resolve("bb-cp-record"), "#!/bin/sh\nexit 0\n");
        // the launch: `quick` exits at once, anything else is a JAVA process that holds until <dir>/<name>.go exists
        BbFixtures.executable(tools.resolve("launch"),
                              "#!/bin/bash\n[ \"$1\" = quick ] && exit 0\nexec \"$HOLD_JAVA\" -cp \"$HOLD_CP\" '" + Hold.class.getName()
                             + "' \"$HOLD_DIR/$1\"\n");  // single-quoted: the nested class name holds a '$'
        capFile = Files.writeString(dir.resolve("max-reactors"), "1\n");
        Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        Files.writeString(dir.resolve("meminfo"), "MemAvailable:   67108864 kB\n");
        Files.writeString(dir.resolve("ps"), "");
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (var names = Files.list(dir)) {
            for (var up : names.filter(p -> p.getFileName()
                                             .toString()
                                             .endsWith(".up")).toList()) {
                Files.writeString(dir.resolve(up.getFileName().toString().replace(".up", ".go")),
                                  "");
            }
        }

        for (var state : List.of("waiting", "running")) {
            try (var ids = Files.list(queue.resolve(state))) {
                for (var id : ids.map(p -> p.getFileName()
                                            .toString()).toList()) {
                    for (var pid : BbFixtures.waiters(id)) {
                        ProcessHandle.of(pid).ifPresent(h -> h.descendants()
                                                              .forEach(ProcessHandle::destroyForcibly));
                    }

                    BbFixtures.kill(BbFixtures.waiters(id));
                }
            }
        }

        reapEverythingUnder(dir);
    }

    /// Nothing started under <root> may outlive the test: [#aLedgerLaunch_isNeverAnOffQueueReactor_evenAfterItsWaiterIsKilled] leaves an
    /// orphaned job subshell that writes <co>/.bb/end and .bb/rc once its launch exits. The CI DirectoryNotEmpty on <root> (run 44763520377)
    /// was not this: it was the waiter's ticket-log append after the done move, fixed in to_done (bb-queue-lib.sh, log before the move).
    private static void reapEverythingUnder(Path root) throws Exception {
        var alive = (Supplier<List<Long>>) () -> {
            try {
                return BbFixtures.run(List.of("pgrep", "-u", System.getProperty("user.name"), "-f", "--", root.toString()), Map.of(), null, null)
                                 .stdout()
                                 .lines()
                                 .filter(line -> !line.isBlank())
                                 .map(line -> Long.parseLong(line.strip()))
                                 .toList();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        if (!within(20_000, () -> alive.get().isEmpty())) {
            BbFixtures.kill(alive.get());
        }
    }

    private Map<String, String> env(Map<String, String> more) {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", ME);
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_LOADAVG",
                dir.resolve("loadavg").toString());
        env.put("BB_TEST_MEMINFO",
                dir.resolve("meminfo").toString());
        env.put("BB_TEST_PS",
                dir.resolve("ps").toString());
        env.put("BB_TEST_MAX_REACTORS_FILE", capFile.toString());
        env.put("BB_TEST_TICKET_LOG",
                dir.resolve("log.tsv").toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_DEDUP", "0");
        env.put("BB_NO_CP_RECORD", "1");
        env.put("BB_TEST_MVN",
                tools.resolve("launch").toString());
        env.put("HOLD_JAVA",
                ProcessHandle.current().info().command().orElse("java"));
        env.put("HOLD_CP", holdClasses());
        env.put("HOLD_DIR", dir.toString());
        env.putAll(more);

        return env;
    }

    private static String holdClasses() {
        try {
            return Path.of(Hold.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path checkout(String name) throws Exception {
        var co = Files.createDirectories(dir.resolve("work").resolve(ME + "-" + name));

        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }

        return co;
    }

    /// Enqueues `bb-build <co> <name>` (the launch script gets <name> as its first argument); returns the ticket id.
    private String enqueue(String name) throws Exception {
        var co = checkout(name);
        var run = BbFixtures.run(List.of(tools.resolve("bb-build").toString(),
                                         co.toString(),
                                         name),
                                 env(Map.of()),
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        var id = run.stdout().replaceAll("(?s).*queued ticket=(\\S+).*", "$1").strip();

        assertThat(id).as(run.stdout()).doesNotContain(" ");

        return id;
    }

    private boolean in(String state, String id) {
        return Files.exists(queue.resolve(state).resolve(id));
    }

    private static boolean within(long millis, BooleanSupplier condition) throws InterruptedException {
        var deadline = System.currentTimeMillis() + millis;

        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.sleep(200);
        }

        return condition.getAsBoolean();
    }

    private BbFixtures.Outcome lib(String function, Map<String, String> more) throws Exception {
        return BbFixtures.run(List.of("bash",
                                      "-c",
                                      ". \"$1\" && " + function,
                                      "bash",
                                      tools.resolve("bb-queue-lib.sh").toString()),
                              env(more),
                              dir,
                              null);
    }

    private String ledgerCount(Map<String, String> more) throws Exception {
        return lib("ledger_count", more).stdout()
                  .strip();
    }

    @Test
    void aRunningJavaLaunch_countsAgainstTheCap() throws Exception {
        var first = enqueue("hold-a");

        assertThat(within(30_000,
                          () -> Files.exists(dir.resolve("hold-a.up")))).as("the java launch started").isTrue();
        assertThat(in("running", first)).isTrue();
        assertThat(lib("reactors", Map.of()).stdout().strip()).as("no pattern sees the java launch").isEqualTo("0");
        assertThat(ledgerCount(Map.of())).as("the ledger does").isEqualTo("1");
        assertThat(Files.readString(queue.resolve("launches"))).startsWith(first + " ");
        var second = enqueue("quick");

        Thread.sleep(12_000);  // more than two 5 s poll rounds of the second waiter
        assertThat(in("waiting", second)).as("host cap 1 is taken by the running java launch").isTrue();
        Files.writeString(dir.resolve("hold-a.go"), "");
        assertThat(within(30_000, () -> in("done", second))).as("granted once the java launch exits").isTrue();
        assertThat(within(15_000, () -> in("done", first))).isTrue();
    }

    /// #66's offqueue_reactors and the ledger agree on what the queue launched: a ledger launch (here matched by the reactor pattern) is
    /// the queue's own by its `bb-build __run` ancestry, also after a kill -9 of its waiter (the job subshell forked from the waiter keeps
    /// its argv); a look-alike started outside the queue (detached, so this test's own build ticket is not its ancestor) is listed.
    /// Probe: drop the ancestor test in offqueue_reactors -> red.
    @Test
    void aLedgerLaunch_isNeverAnOffQueueReactor_evenAfterItsWaiterIsKilled() throws Exception {
        var id = enqueue("hold-o");

        try {
            assertThat(BbFixtures.process("bash", "-c", "setsid \"$0\" -cp \"$1\" \"$2\" \"$3\" < /dev/null > /dev/null 2>&1 &",
                                          ProcessHandle.current().info().command().orElse("java"), holdClasses(), Hold.class.getName(),
                                          dir.resolve("stray").toString()).start().waitFor()).isZero();
            assertThat(within(30_000, () -> Files.exists(dir.resolve("hold-o.up"))
                                            && Files.exists(dir.resolve("stray.up")))).as("both java processes started").isTrue();
            var launch = holder("hold-o");
            var stray = holder("stray");
            var offq = Map.of("BB_TEST_PS", "", "BB_TEST_REACTOR_PATTERN", "[B]bQueueLedgerIT.Hold");
            var listed = lib("offqueue_reactors", offq).stdout();

            assertThat(listed).as("the stray is off-queue").contains(stray.pid() + "\t");
            assertThat(listed).as("the ledger launch is the queue's own").doesNotContain(launch.pid() + "\t");
            var waiter = launch.parent().flatMap(ProcessHandle::parent).orElseThrow();

            assertThat(waiter.info().commandLine().orElse("")).contains("bb-build __run " + id);
            waiter.destroyForcibly();
            assertThat(within(10_000, () -> !waiter.isAlive())).isTrue();
            assertThat(ledgerCount(Map.of())).as("the job subshell outlives its waiter").isEqualTo("1");
            assertThat(lib("offqueue_reactors", offq).stdout()).as("still the queue's own").doesNotContain(launch.pid() + "\t")
                                                               .contains(stray.pid() + "\t");
        } finally {
            Files.writeString(dir.resolve("hold-o.go"), "");
            Files.writeString(dir.resolve("stray.go"), "");
        }
    }

    /// The live Hold process whose argument is <dir>/<name>.
    /// vbt6 B2: a waiter SIGKILLed after it spawned the launch and before the launch's ledger entry is written (window widened by a
    /// test-local `sleep 6` before the only ledger_add) must not let a second ticket past host cap 1: the grant and the ledger entry are
    /// one step for the next grant (the job subshell writes its own entry under the inherited queue lock). Red on 32e2ad8b6, where the
    /// waiter wrote the entry after the spawn and its death released the lock with the ledger at 0. The slot does not leak either.
    @Test
    void aWaiterSigkilledBetweenSpawnAndLedgerWrite_neverLetsASecondTicketPastTheCap() throws Exception {
        var src = Files.readString(BbFixtures.OPS_BB.resolve("bb-build"));
        var grant = "if [ \"$(pick \"$ID\")\" = \"$ID\" ]; then break; fi";
        // #88: once race.arm names the first ticket, the next waiter poll releases that launch AFTER its reap kept the ticket and BEFORE
        // its pick reads the ledger, and waits until the launch is gone: the ordering CI hit by chance, forced on every run.
        var releaseBetweenReapAndPick = "if [ -f \"$HOLD_DIR/race.arm\" ]; then F=$(cat \"$HOLD_DIR/race.arm\"); rm -f \"$HOLD_DIR/race.arm\";"
                                        + " : > \"$HOLD_DIR/hold-a.go\"; for _ in $(seq 300); do waiter_alive \"$F\" || break; sleep 0.1; done; fi; ";
        var widened = src.replace("ledger_add \"$ID\"", "sleep 6; ledger_add \"$ID\"").replace(grant, releaseBetweenReapAndPick + grant);

        assertThat(widened.split("sleep 6; ledger_add", -1)).as("exactly one ledger_add widened").hasSize(2);
        assertThat(widened.split("race.arm\" \\]", -1)).as("exactly one grant check gets the reap/pick release").hasSize(2);
        BbFixtures.executable(tools.resolve("bb-build"), widened);
        var first = enqueue("hold-a");
        var waiter = new AtomicReference<List<Long>>(List.of());

        assertThat(within(30_000, () -> {
            List<Long> pids;
            try {
                pids = BbFixtures.waiters(first);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            waiter.set(pids.stream()
                           .filter(p -> ProcessHandle.of(p).flatMap(ProcessHandle::parent).map(h -> !pids.contains(h.pid())).orElse(true))
                           .toList());
            return in("running", first) && pids.size() >= 2;
        })).as("the launch subshell was spawned").isTrue();
        assertThat(waiter.get()).as("one waiter").hasSize(1);
        BbFixtures.kill(waiter.get());
        assertThat(within(5_000, () -> ProcessHandle.of(waiter.get().get(0)).isEmpty())).as("the waiter is dead").isTrue();
        assertThat(BbFixtures.waiters(first)).as("its launch subshell lives on").isNotEmpty();
        var second = enqueue("quick");
        var overGranted = within(15_000, () -> in("done", second));
        var ledger = ledgerCount(Map.of());

        Files.writeString(dir.resolve("race.arm"), first);
        assertThat(overGranted).as("no over-grant: the second ticket finished while the first launch held host cap 1 (ledger count then: "
                                   + ledger + ")")
                  .isFalse();
        assertThat(ledger).as("the first launch is in the ledger").isEqualTo("1");
        assertThat(within(60_000, () -> in("done", second))).as("the second ticket runs once the first launch exits").isTrue();
        assertThat(within(30_000, () -> in("done", first))).as("no leak: the first ticket leaves running/ (reaped) once its launch exits")
                  .isTrue();
    }

    private ProcessHandle holder(String name) {
        var arg = dir.resolve(name).toString();

        return ProcessHandle.allProcesses()
                            .filter(h -> h.info().arguments().map(a -> List.of(a).contains(arg)).orElse(false))
                            .findFirst()
                            .orElseThrow();
    }

    @Test
    void aRecycledPid_isNotCounted_andReapDropsIt() throws Exception {
        var live = BbFixtures.process("sleep", "600").start();

        try {
            var pid = live.pid();

            BbFixtures.recordLaunch(queue, "t-live", pid);
            assertThat(ledgerCount(Map.of())).as("live pid, recorded start time").isEqualTo("1");
            assertThat(ledgerCount(Map.of("BB_TEST_RESTART_PID",
                                          Long.toString(pid)))).as("the same pid read as restarted")
                      .isEqualTo("0");
            Files.writeString(queue.resolve("launches"), "t-other " + pid + " 1 1\n");
            assertThat(ledgerCount(Map.of())).as("the same pid with another start time is another process")
                      .isEqualTo("0");
            var waiting = Files.writeString(queue.resolve("waiting").resolve("w-" + BbFixtures.RUN),
                                            "owner=" + ME
                                           + "\ncap=5\ncreated=" + (System.currentTimeMillis() * 1_000_000L)
                                           + "\nco=" + dir
                                           + "\nargs=-B install\n");

            assertThat(lib("pick w-" + BbFixtures.RUN,
                           Map.of()).stdout().strip()).as("host cap 1 is free: the recycled pid holds no slot")
                      .isEqualTo(waiting.getFileName().toString());
            BbFixtures.recordLaunch(queue, "t-live", pid);
            assertThat(lib("pick w-" + BbFixtures.RUN,
                           Map.of()).stdout().strip()).as("the real launch holds the only slot")
                      .isEmpty();
            assertThat(lib("ledger_write",
                           Map.of("BB_TEST_RESTART_PID", Long.toString(pid))).rc()).isZero();
            assertThat(Files.readString(queue.resolve("launches"))).as("reap's prune drops entries that are not live")
                      .isEmpty();
        } finally {
            live.destroyForcibly();
        }
    }

    @Test
    void aSecondWaiter_takesASlot_whileTheFirstLaunchRuns() throws Exception {
        Files.writeString(capFile, "2\n");
        var first = enqueue("hold-b");

        assertThat(within(30_000,
                          () -> Files.exists(dir.resolve("hold-b.up")))).as("the first launch started").isTrue();
        var t0 = System.currentTimeMillis();
        var second = enqueue("quick");

        assertThat(within(20_000,
                          () -> in("done", second))).as("granted and finished while the first launch still runs")
                  .isTrue();
        assertThat(System.currentTimeMillis() - t0).as("no 60 s wait on the queue lock").isLessThan(25_000);
        assertThat(in("running", first)).as("the first launch is still running").isTrue();
        assertThat(Files.readString(queue.resolve("done").resolve(second))).contains("\nrc=0\n");
    }

    /// A bb-cp-record stand-in that starts a "maven" in its own session whose group also holds a NON-leader child (as maven's surefire
    /// forks are), names the leader in .bb/cp-record.pgid (with its real start time, or `1` when `staleStart`), writes the leader's and
    /// the child's pids to <dir>/victim.pid and <dir>/victim-child.pid, and then SIGKILLs itself, as `timeout -k` would. Returns
    /// {leader, child}. Probe (vbt5 M1): `kill -KILL -- "$PG"` (the pid, not the group) leaves the child alive -> red.
    private long[] sigkilledRecord(boolean staleStart) throws Exception {
        BbFixtures.executable(tools.resolve("bb-cp-record"),
                              "#!/bin/bash\nco=$2\n"
                             + "setsid bash -c 'sleep 600 & echo $! > \"$HOLD_DIR/victim-child.pid\"; wait' < /dev/null > /dev/null 2>&1 &\np=$!\n"
                             + "for _ in $(seq 50); do [ \"$(ps -o pgid= -p $p | tr -d ' ')\" = \"$p\" ] && [ -s \"$HOLD_DIR/victim-child.pid\" ] && break; sleep 0.1; done\n"
                             + (staleStart
                                ? "s=1\n"
                                : "s=$(sed 's/^.*) //' /proc/$p/stat | cut -d' ' -f20)\n")
                             + "echo \"$p $s\" > \"$co/.bb/cp-record.pgid\"\necho $p > \"$HOLD_DIR/victim.pid\"\nkill -KILL $$\n");
        var id = enqueue("quick");

        assertThat(within(30_000, () -> in("done", id))).as("the ticket finished").isTrue();
        assertThat(Files.readString(queue.resolve("done").resolve(id))).as("the record never changes the build's rc")
                  .contains("\nrc=0\n");
        var leader = Long.parseLong(Files.readString(dir.resolve("victim.pid")).strip());
        var child = Long.parseLong(Files.readString(dir.resolve("victim-child.pid")).strip());

        assertThat(child).as("the child is not the group leader").isNotEqualTo(leader);
        return new long[]{leader, child};
    }

    private static void destroy(long... pids) {
        for (var pid : pids) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private String buildLog(String name) throws IOException {
        return Files.readString(dir.resolve("work").resolve(ME + "-" + name).resolve(".bb/build.log"));
    }

    @Test
    void aSigkilledCpRecord_hasItsRecordedMavenGroupKilled() throws Exception {
        var victims = sigkilledRecord(false);

        try {
            assertThat(within(10_000,
                              () -> ProcessHandle.of(victims[0]).isEmpty())).as("the recorded maven (group leader) was killed")
                      .isTrue();
            assertThat(within(10_000,
                              () -> ProcessHandle.of(victims[1]).isEmpty())).as("a non-leader member of its group (a surefire fork) was killed too")
                      .isTrue();
            assertThat(buildLog("quick")).contains("cp-record's maven (process group " + victims[0] + ") killed",
                                                   "cp-record skipped");
            assertThat(dir.resolve("work").resolve(ME + "-quick").resolve(".bb/cp-record.pgid")).doesNotExist();
        } finally {
            destroy(victims);
        }
    }

    @Test
    void aPgidFileWithAnotherStartTime_killsNothing() throws Exception {
        var victims = sigkilledRecord(true);

        try {
            Thread.sleep(2_000);
            assertThat(ProcessHandle.of(victims[0])).as("a recycled pid (start time differs) is never signalled")
                      .isPresent();
            assertThat(ProcessHandle.of(victims[1])).as("nor its group").isPresent();
            assertThat(buildLog("quick")).doesNotContain("killed").contains("cp-record skipped");
        } finally {
            destroy(victims);
        }
    }

    /// The java launch: marks <path>.up, then holds until <path>.go exists (bounded at 120 s).
    public static final class Hold {
        public static void main(String[] args) throws Exception {
            var base = args[0];

            Files.writeString(Path.of(base + ".up"), "");
            var deadline = System.currentTimeMillis() + 120_000;

            while (!Files.exists(Path.of(base + ".go")) && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }
        }
    }
}
