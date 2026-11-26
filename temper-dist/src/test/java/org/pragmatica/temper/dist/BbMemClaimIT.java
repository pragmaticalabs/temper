package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// QUEUE CLAIMS TO COME (issue #130) in `ops/bb/bb-queue-lib.sh`: a grant must not leave MemAvailable, minus the off-queue headroom, minus
/// what the queue's own live launches are still expected to claim (E - their tree's RSS, E = 6 GB by default), minus the grant's own E,
/// below the floor (8 GB). Every row runs REAL `bb-build __run` waiters through the production path (reap, pick, grant, ledger line, all in
/// one queue-lock hold); only MemAvailable (BB_TEST_MEMINFO), the process list (BB_TEST_PS), /proc/vmstat (BB_TEST_VMSTAT) and the launch
/// (BB_TEST_MVN: a bash script that holds until <name>.go exists) are fakes.
///
/// Probes: make mem_held skip the claim term -> [#threeWaiters_at20GbFloor8_thirdIsHeld] and [#twoWaitersInTheSameSecond_neverSpendTheSameHeadroom]
/// red; count a launch's claim as E whatever its tree holds -> [#aTreeAtItsExpectedSize_claimsNothingMore] red; let the claim rule apply with
/// no live launch -> [#nothingRunning_anOutsideJobHoldingTheFloor_keepsTheStarvationOverride] still green but #53's rows red; let the memory
/// override pass while a launch runs -> [#aClaimHeldTicket_isNeverOverridden_whileALaunchRuns] red; drop grant_record ->
/// [#theGrantRecordsMemAvailableAndOomKill] red.
class BbMemClaimIT {
    private static final String ME = "mc";
    private static final long GIB_KB = 1024L * 1024L;

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private final Map<String, String> more = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock, pgrep and GNU tools");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        BbFixtures.executable(tools.resolve("bb-build"), Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        BbFixtures.executable(tools.resolve("bb-cp-record"), "#!/bin/sh\nexit 0\n");
        BbFixtures.executable(tools.resolve("launch"),
                              "#!/bin/bash\n: > \"$HOLD_DIR/$1.up\"\nwhile [ ! -e \"$HOLD_DIR/$1.go\" ]; do sleep 0.3; done\n");
        Files.writeString(dir.resolve("max-reactors"), "3\n");
        Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        Files.writeString(dir.resolve("ps"), "");
        Files.writeString(dir.resolve("vmstat"), "nr_free_pages 1\noom_kill 7\n");
        memAvailableKb(20 * GIB_KB);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (var names = Files.list(dir)) {
            for (var up : names.filter(p -> p.getFileName().toString().endsWith(".up")).toList()) {
                Files.writeString(dir.resolve(up.getFileName().toString().replace(".up", ".go")), "");
            }
        }

        for (var state : List.of("waiting", "running")) {
            try (var ids = Files.list(queue.resolve(state))) {
                for (var id : ids.map(p -> p.getFileName().toString()).toList()) {
                    for (var pid : BbFixtures.waiters(id)) {
                        ProcessHandle.of(pid).ifPresent(h -> h.descendants().forEach(ProcessHandle::destroyForcibly));
                    }

                    BbFixtures.kill(BbFixtures.waiters(id));
                }
            }
        }

        var alive = (Supplier<List<Long>>) () -> {
            try {
                return BbFixtures.run(List.of("pgrep", "-u", System.getProperty("user.name"), "-f", "--", dir.toString()),
                                      Map.of(), null, null)
                                 .stdout().lines().filter(line -> !line.isBlank()).map(line -> Long.parseLong(line.strip())).toList();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        try {
            if (!within(20_000, () -> alive.get().isEmpty())) {
                BbFixtures.kill(alive.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void threeWaiters_at20GbFloor8_thirdIsHeld() throws Exception {
        var a = enqueue("a");
        var b = enqueue("b");
        var c = enqueue("c");

        assertThat(within(40_000, () -> ups() == 2)).as("two start").isTrue();
        Thread.sleep(12_000);  // more than two 5 s poll rounds of every waiter
        assertThat(ups()).as("the third is held: 20 - 2 x 6 claims to come - its own 6 = 2 GB < floor 8").isEqualTo(2);
        var held = List.of(a, b, c).stream().filter(id -> in("waiting", id)).toList();

        assertThat(held).hasSize(1);
        assertThat(lib("mem_held").stdout())
                .startsWith("held: MemAvailable 20.0 GB - queue claims to come 12.0 GB (")
                .contains("- this grant 6.0 GB = 2.0 GB < floor 8 GB");
        assertThat(bbQueue()).contains("MEMORY GATE").contains("held: MemAvailable 20.0 GB - queue claims to come 12.0 GB");
    }

    @Test
    void aTreeAtItsExpectedSize_claimsNothingMore() throws Exception {
        memAvailableKb(14 * GIB_KB + GIB_KB / 2);
        var a = enqueue("a");

        assertThat(within(30_000, () -> up("a"))).as("a alone: the floor alone decides").isTrue();
        var pid = launchPid(a);

        // the launch's tree holds 4 GB + 3 GB = 7 GB >= E: nothing more to claim, MemAvailable already has it
        Files.writeString(dir.resolve("ps"), pid + " 1 " + 4 * GIB_KB + " bash launch\n" + (pid + 1_000_000) + " " + pid + " " + 3 * GIB_KB + " java fork\n");
        assertThat(lib("queue_claims").stdout().strip()).isEqualTo(a + " " + pid + " " + 7 * GIB_KB + " 0");
        var b = enqueue("b");

        assertThat(within(40_000, () -> up("b"))).as("14.5 - 0 - own 6 = 8.5 >= 8; a double count (14.5 - 6 - 6) would hold it").isTrue();
        var c = enqueue("c");

        Thread.sleep(12_000);
        assertThat(up("c")).as("b is new (tree 0): 14.5 - 6 - 6 = 2.5 < 8").isFalse();
        assertThat(in("waiting", c)).isTrue();
        assertThat(lib("mem_held").stdout()).contains("queue claims to come 6.0 GB (" + a + " 0.0 GB, " + b + " 6.0 GB)");
    }

    @Test
    void twoWaitersInTheSameSecond_neverSpendTheSameHeadroom() throws Exception {
        memAvailableKb(14 * GIB_KB);
        enqueue("a");
        enqueue("b");
        enqueue("c");

        assertThat(within(40_000, () -> ups() >= 1)).isTrue();
        Thread.sleep(12_000);
        assertThat(ups()).as("grants serialise on the queue lock and the ledger line is written in the grant's lock hold: the second"
                             + " waiter sees the first launch (6 GB claim + 6 GB own > 14 - 8)").isEqualTo(1);
        assertThat(Files.readAllLines(queue.resolve("launches"))).hasSize(1);
    }

    @Test
    void nothingRunning_anOutsideJobHoldingTheFloor_keepsTheStarvationOverride() throws Exception {
        memAvailableKb(5 * GIB_KB);
        more.put("BB_LOAD_STARVE_SECS", "2");
        var a = enqueue("a");

        assertThat(within(40_000, () -> up("a"))).as("no ticket of any kind runs: #53's override grants the starved ticket").isTrue();
        assertThat(Files.readString(queue.resolve("running").resolve(a))).contains("mem_override=");
        assertThat(field("running", a, "mem_avail_kb_grant")).as("the override grant is recorded too").isEqualTo(Long.toString(5 * GIB_KB));
        assertThat(field("running", a, "oom_kill_grant")).isEqualTo("7");
    }

    @Test
    void aClaimHeldTicket_isNeverOverridden_whileALaunchRuns() throws Exception {
        memAvailableKb(14 * GIB_KB);
        more.put("BB_LOAD_STARVE_SECS", "2");
        enqueue("a");
        assertThat(within(30_000, () -> up("a"))).isTrue();
        var b = enqueue("b");

        Thread.sleep(15_000);  // well past the 2 s starvation bound, three poll rounds
        assertThat(in("waiting", b)).as("a launch runs: the claim rule holds b and no override passes it").isTrue();
        assertThat(Files.readString(queue.resolve("waiting").resolve(b))).doesNotContain("mem_override=");
        Files.writeString(dir.resolve("a.go"), "");
        assertThat(within(40_000, () -> up("b"))).as("a ends, nothing claims: b goes by #53's floor alone").isTrue();
    }

    @Test
    void theGrantRecordsMemAvailableAndOomKill() throws Exception {
        var a = enqueue("a");

        assertThat(within(30_000, () -> up("a"))).isTrue();
        assertThat(field("running", a, "mem_avail_kb_grant")).isEqualTo(Long.toString(20 * GIB_KB));
        assertThat(field("running", a, "oom_kill_grant")).isEqualTo("7");
    }

    @Test
    void anUnreadableVmstat_writesNoOomLine_butTheGrantStands() throws Exception {
        more.put("BB_TEST_VMSTAT", dir.resolve("no-vmstat").toString());
        var a = enqueue("a");

        assertThat(within(30_000, () -> up("a"))).isTrue();
        assertThat(field("running", a, "mem_avail_kb_grant")).isEqualTo(Long.toString(20 * GIB_KB));
        assertThat(Files.readString(queue.resolve("running").resolve(a))).doesNotContain("oom_kill_grant");
    }

    @Test
    void aBbHoldTicket_claimsNothing_andTheClaimFileSetsE() throws Exception {
        var holder = BbFixtures.process("sleep", "600").start();

        try {
            Files.writeString(queue.resolve("running").resolve("hold-" + BbFixtures.RUN), "kind=bb-hold\nstarted=1\n");
            Files.writeString(queue.resolve("running").resolve("job-" + BbFixtures.RUN), "started=1\n");
            BbFixtures.recordLaunch(queue, "hold-" + BbFixtures.RUN, holder.pid());
            BbFixtures.recordLaunch(queue, "job-" + BbFixtures.RUN, holder.pid());
            assertThat(lib("queue_claims").stdout().strip()).as("only the job claims; the bb-hold ticket is skipped")
                    .isEqualTo("job-" + BbFixtures.RUN + " " + holder.pid() + " 0 " + 6 * GIB_KB);
            Files.writeString(dir.resolve("mem-claim-gb"), "10\n");
            assertThat(lib("queue_claims").stdout().strip()).endsWith(" 0 " + 10 * GIB_KB);
            Files.writeString(dir.resolve("mem-claim-gb"), "0\nnot a number\n");
            assertThat(lib("queue_claims").stdout().strip()).as("malformed: default 6").endsWith(" 0 " + 6 * GIB_KB);
        } finally {
            holder.destroyForcibly();
        }
    }

    @Test
    void aPickForAnotherCaller_recordsNothing_andThePickForTheGrantedTicketRecordsOnce() throws Exception {
        var first = waitingTicket("first", 20);
        var second = waitingTicket("second", 10);

        assertThat(lib("pick 0 " + second).stdout().strip()).as("the first ticket is the candidate").isEqualTo(first);
        assertThat(Files.readString(queue.resolve("waiting").resolve(first))).doesNotContain("mem_avail_kb_grant");
        assertThat(lib("pick 0 " + first).stdout().strip()).isEqualTo(first);
        assertThat(Files.readAllLines(queue.resolve("waiting").resolve(first)).stream().filter(l -> l.startsWith("mem_avail_kb_grant=")).count())
                .isEqualTo(1);
        assertThat(Files.readAllLines(queue.resolve("waiting").resolve(first)).stream().filter(l -> l.startsWith("oom_kill_grant=")).count())
                .isEqualTo(1);
    }

    @Test
    void aClaimHeldGrant_alsoSubtractsTheOffQueueHeadroom() throws Exception {
        var holder = BbFixtures.process("sleep", "600").start();

        try {
            memAvailableKb(25 * GIB_KB);
            Files.writeString(queue.resolve("running").resolve("job-" + BbFixtures.RUN), "started=1\n");
            BbFixtures.recordLaunch(queue, "job-" + BbFixtures.RUN, holder.pid());
            // an off-queue JVM: -Xmx8g, RSS 1 GB -> 7 GB of headroom still to come
            Files.writeString(dir.resolve("ps"), "999999 1 " + GIB_KB + " java -Xmx8g Tlc\n");
            assertThat(lib("mem_held").stdout().strip())
                    .isEqualTo("held: MemAvailable 25.0 GB - off-queue JVM headroom 7.0 GB - queue claims to come 6.0 GB (job-" + BbFixtures.RUN
                               + " 6.0 GB) - this grant 6.0 GB = 6.0 GB < floor 8 GB");
            Files.writeString(dir.resolve("ps"), "");
            assertThat(lib("mem_held").stdout().strip()).as("without the off-queue JVM: 25 - 6 - 6 = 13 >= 8").isEmpty();
        } finally {
            holder.destroyForcibly();
        }
    }

    /// A waiting ticket created `ageSecs` ago, as BbMemFloorIT writes one; no waiter: only `pick` is called on it.
    private String waitingTicket(String name, long ageSecs) throws IOException {
        var id = name + "-" + BbFixtures.RUN;
        var created = (System.currentTimeMillis() / 1000 - ageSecs) * 1_000_000_000L;

        Files.writeString(queue.resolve("waiting").resolve(id),
                          "owner=memclaim\nsha=0000000\ncreated=" + created + "\nco=" + dir.resolve("co-" + name) + "\nargs=-B install\n");

        return id;
    }

    private Map<String, String> env() {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", ME);
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_LOADAVG", dir.resolve("loadavg").toString());
        env.put("BB_TEST_MEMINFO", dir.resolve("meminfo").toString());
        env.put("BB_TEST_PS", dir.resolve("ps").toString());
        env.put("BB_TEST_VMSTAT", dir.resolve("vmstat").toString());
        env.put("BB_TEST_MEM_CLAIM_FILE", dir.resolve("mem-claim-gb").toString());
        env.put("BB_TEST_MEM_FLOOR_FILE", dir.resolve("mem-floor-gb").toString());
        env.put("BB_TEST_MAX_REACTORS_FILE", dir.resolve("max-reactors").toString());
        env.put("BB_TEST_TICKET_LOG", dir.resolve("log.tsv").toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_DEDUP", "0");
        env.put("BB_NO_CP_RECORD", "1");
        env.put("BB_TEST_MVN", tools.resolve("launch").toString());
        env.put("HOLD_DIR", dir.toString());
        env.putAll(more);

        return env;
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

    /// Enqueues `bb-build <co> <name>` (the launch gets <name> as its first argument); returns the ticket id.
    private String enqueue(String name) throws Exception {
        var co = checkout(name);
        var run = BbFixtures.run(List.of(tools.resolve("bb-build").toString(), co.toString(), name), env(), dir, null);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        var id = run.stdout().replaceAll("(?s).*queued ticket=(\\S+).*", "$1").strip();

        assertThat(id).as(run.stdout()).doesNotContain(" ");

        return id;
    }

    private boolean in(String state, String id) {
        return Files.exists(queue.resolve(state).resolve(id));
    }

    private boolean up(String name) {
        return Files.exists(dir.resolve(name + ".up"));
    }

    private long ups() {
        try (var names = Files.list(dir)) {
            return names.filter(p -> p.getFileName().toString().endsWith(".up")).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long launchPid(String ticket) throws IOException {
        return Files.readAllLines(queue.resolve("launches")).stream()
                    .filter(l -> l.startsWith(ticket + " "))
                    .map(l -> Long.parseLong(l.split(" ")[1]))
                    .findFirst().orElseThrow();
    }

    private String field(String state, String id, String key) throws IOException {
        var prefix = key + "=";

        return Files.readAllLines(queue.resolve(state).resolve(id)).stream()
                    .filter(line -> line.startsWith(prefix))
                    .map(line -> line.substring(prefix.length()))
                    .reduce((first, second) -> second)
                    .orElse("");
    }

    private void memAvailableKb(long kb) throws IOException {
        Files.writeString(dir.resolve("meminfo"),
                          "MemTotal:       32000000 kB\nMemFree:          100000 kB\nMemAvailable:   " + kb + " kB\n");
    }

    private BbFixtures.Outcome lib(String function) throws Exception {
        return BbFixtures.run(List.of("bash", "-c", ". \"$1\" && " + function, "bash", tools.resolve("bb-queue-lib.sh").toString()),
                              env(), dir, null);
    }

    private String bbQueue() throws Exception {
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-queue").toString(), "0"), env(), dir, null);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout();
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
}
