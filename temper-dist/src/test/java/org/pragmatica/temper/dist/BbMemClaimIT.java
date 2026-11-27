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
/// what the queue's own live launches are still expected to claim (E - their tree's RSS, E = 6 GB by default), below the floor (8 GB);
/// a floor below E is refused (CTO ruling 2026-11-27: the grant's own E is not subtracted again). Every row runs REAL `bb-build __run` waiters through the production path (reap, pick, grant, ledger line, all in
/// one queue-lock hold); only MemAvailable (BB_TEST_MEMINFO), the process list (BB_TEST_PS), /proc/vmstat (BB_TEST_VMSTAT), the launch
/// (BB_TEST_MVN: a bash script that holds until <name>.go exists), `docker inspect` (a script on PATH, answered from a file) and bb-netns-run
/// (runs the launch directly) are fakes. An isolated ticket (BB_PORT_GROUP=1 with docker on PATH, so iso=1) counts its container's tree too.
///
/// Probes: make mem_held skip the claim term -> [#threeWaiters_at20GbFloor8_thirdIsGranted_theFourthIsHeld] and [#twoWaitersInTheSameSecond_neverSpendTheSameHeadroom]
/// red; count a launch's claim as E whatever its tree holds -> [#aTreeAtItsExpectedSize_claimsNothingMore] red; let the claim rule apply with
/// no live launch -> [#nothingRunning_anOutsideJobHoldingTheFloor_keepsTheStarvationOverride] still green but #53's rows red; let the memory
/// override pass while a launch runs -> [#aClaimHeldTicket_isNeverOverridden_whileALaunchRuns] red; drop grant_record ->
/// [#theGrantRecordsMemAvailableAndOomKill] red; ignore the container tree in queue_claims ->
/// [#anIsolatedLaunch_countsItsContainersMemory_notOnlyItsLedgerTree] red; write the ledger line after the lock release (ledger_add after
/// `exec 8>&-` in bb-build) -> [#twoWaitersInTheSameSecond_neverSpendTheSameHeadroom] red (the ledger line is delayed 8 s by
/// BB_TEST_LEDGER_DELAY, so the window is open for every waiter).
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
        // docker: only `inspect -f '{{.Name}} {{.State.Pid}}' <name>...`, answered from $HOLD_DIR/container-pids ("<name> <pid>" lines); an unknown name
        // is an error on stderr and exit 1 while the known ones are still printed, as docker does. The format string is pinned: a changed one exits 2.
        BbFixtures.executable(tools.resolve("docker"),
                              "#!/bin/bash\n[ \"$1\" = inspect ] && [ \"$2\" = -f ] && [ \"$3\" = '{{.Name}} {{.State.Pid}}' ] || exit 2\nshift 3\nrc=0\n"
                              + "for n in \"$@\"; do l=$(grep -E \"^$n \" \"$HOLD_DIR/container-pids\" 2>/dev/null) && echo \"/$l\" || { echo \"Error: No such object: $n\" >&2; rc=1; }; done\nexit $rc\n");
        // bb-netns-run <id> <checkout> <mvn> args...: the real one runs maven in a container; this one runs the launch directly
        BbFixtures.executable(tools.resolve("bb-netns-run"), "#!/bin/bash\nshift 2\nexec \"$@\"\n");
        Files.writeString(dir.resolve("container-pids"), "");
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
    void threeWaiters_at20GbFloor8_thirdIsGranted_theFourthIsHeld() throws Exception {
        Files.writeString(dir.resolve("max-reactors"), "4\n");
        enqueue("a");
        enqueue("b");
        enqueue("c");
        var d = enqueue("d");

        assertThat(within(60_000, () -> ups() == 3)).as("a, b and the third start: 20 - 2 x 6 claims to come = 8 >= floor 8").isTrue();
        Thread.sleep(12_000);  // more than two 5 s poll rounds of every waiter
        assertThat(ups()).as("the fourth is held: 20 - 3 x 6 = 2 GB < floor 8").isEqualTo(3);
        assertThat(in("waiting", d)).isTrue();
        assertThat(lib("mem_held").stdout())
                .startsWith("held: MemAvailable 20.0 GB - queue claims to come 18.0 GB (")
                .contains(") = 2.0 GB < floor 8 GB");
        assertThat(bbQueue()).contains("MEMORY GATE").contains("held: MemAvailable 20.0 GB - queue claims to come 18.0 GB");
    }

    @Test
    void aTreeAtItsExpectedSize_claimsNothingMore() throws Exception {
        memAvailableKb(13 * GIB_KB + GIB_KB / 2);
        var a = enqueue("a");

        assertThat(within(30_000, () -> up("a"))).as("a alone: the floor alone decides").isTrue();
        var pid = launchPid(a);

        // the launch's tree holds 4 GB + 3 GB = 7 GB >= E: nothing more to claim, MemAvailable already has it
        Files.writeString(dir.resolve("ps"), pid + " 1 " + 4 * GIB_KB + " bash launch\n" + (pid + 1_000_000) + " " + pid + " " + 3 * GIB_KB + " java fork\n");
        assertThat(lib("queue_claims").stdout().strip()).isEqualTo(a + " " + pid + " " + 7 * GIB_KB + " 0");
        var b = enqueue("b");

        assertThat(within(40_000, () -> up("b"))).as("13.5 - 0 = 13.5 >= 8; a double count of a's 7 GB (13.5 - 6 = 7.5) would hold it").isTrue();
        var c = enqueue("c");

        Thread.sleep(12_000);
        assertThat(up("c")).as("b is new (tree 0): 13.5 - 6 = 7.5 < 8").isFalse();
        assertThat(in("waiting", c)).isTrue();
        assertThat(lib("mem_held").stdout()).contains("queue claims to come 6.0 GB (" + a + " 0.0 GB, " + b + " 6.0 GB)");
    }

    @Test
    void twoWaitersInTheSameSecond_neverSpendTheSameHeadroom() throws Exception {
        memAvailableKb(13 * GIB_KB);
        more.put("BB_TEST_LEDGER_DELAY", "8");  // ledger_add sleeps 8 s before writing: with the line written in the lock hold, the other waiters wait on the lock; written after it, they pick inside the window
        enqueue("a");
        enqueue("b");
        enqueue("c");

        assertThat(within(40_000, () -> ups() >= 1)).isTrue();
        Thread.sleep(12_000);
        assertThat(ups()).as("grants serialise on the queue lock and the ledger line is written in the grant's lock hold: the second"
                             + " waiter sees the first launch (13 - 6 GB claim = 7 < 8); without it 13 >= 8 grants").isEqualTo(1);
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
        memAvailableKb(13 * GIB_KB);  // a's claim 6: 13 - 6 = 7 < 8
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
            // an off-queue JVM: -Xmx14g, RSS 1 GB -> 13 GB of headroom still to come
            Files.writeString(dir.resolve("ps"), "999999 1 " + GIB_KB + " java -Xmx14g Tlc\n");
            assertThat(lib("mem_held").stdout().strip())
                    .isEqualTo("held: MemAvailable 25.0 GB - off-queue JVM headroom 13.0 GB - queue claims to come 6.0 GB (job-" + BbFixtures.RUN
                               + " 6.0 GB) = 6.0 GB < floor 8 GB");
            Files.writeString(dir.resolve("ps"), "");
            assertThat(lib("mem_held").stdout().strip()).as("without the off-queue JVM: 25 - 6 = 19 >= 8").isEmpty();
        } finally {
            holder.destroyForcibly();
        }
    }

    /// An isolated ticket (iso=1) runs maven in docker container bb-<id>, outside the ledger pid's tree: the container's init tree counts too.
    /// Tree 0.5 GB + container 3 GB: claim 6 - 3.5 = 2.5 GB (counting the ledger tree only gives 5.5 GB). At 10.5 GB: 10.5 - 2.5 = 8 >= 8, so b
    /// starts; with the container ignored 10.5 - 5.5 = 5 < 8 and b is held.
    @Test
    void anIsolatedLaunch_countsItsContainersMemory_notOnlyItsLedgerTree() throws Exception {
        memAvailableKb(10 * GIB_KB + GIB_KB / 2);
        more.put("PATH", tools + ":" + System.getenv("PATH"));  // the fake docker: iso=1 needs `command -v docker`, queue_claims calls it
        var a = enqueue("a", Map.of("BB_PORT_GROUP", "1"));

        assertThat(within(30_000, () -> up("a"))).isTrue();
        assertThat(field("running", a, "iso")).as("a is an isolated ticket").isEqualTo("1");
        var pid = launchPid(a);
        var container = 777_000L;
        var half = GIB_KB / 2;

        Files.writeString(dir.resolve("ps"), pid + " 1 " + half + " bash bb-build __run\n"
                                              + "700 1 2000 containerd-shim\n"
                                              + container + " 700 " + 1000 + " docker-init\n"
                                              + (container + 1) + " " + container + " " + (3 * GIB_KB - 1000) + " java maven\n");
        Files.writeString(dir.resolve("container-pids"), "bb-" + a + " " + container + "\n");
        assertThat(lib("queue_claims").stdout().strip()).isEqualTo(a + " " + pid + " " + (half + 3 * GIB_KB) + " " + (6 * GIB_KB - half - 3 * GIB_KB));
        var b = enqueue("b");

        assertThat(within(40_000, () -> up("b"))).as("10.5 - 2.5 = 8 >= 8; with the container ignored (claim 5.5) 5 < 8").isTrue();
        assertThat(in("waiting", b)).isFalse();
    }

    /// A container that is gone, or not running (State.Pid 0), adds nothing: the claim is E - tree, the over-claim of a launch with no container.
    @Test
    void anIsolatedLaunch_whoseContainerCannotBeRead_claimsEMinusItsTree() throws Exception {
        more.put("PATH", tools + ":" + System.getenv("PATH"));
        var a = enqueue("a", Map.of("BB_PORT_GROUP", "1"));

        assertThat(within(30_000, () -> up("a"))).isTrue();
        var pid = launchPid(a);

        Files.writeString(dir.resolve("ps"), pid + " 1 " + GIB_KB + " bash bb-build __run\n" + "778001 1 " + 4 * GIB_KB + " java orphaned-container-java\n");
        for (var answer : List.of("", "bb-" + a + " 0\n", "bb-other-1 778001\n")) {
            Files.writeString(dir.resolve("container-pids"), answer);
            assertThat(lib("queue_claims").stdout().strip()).as("answer '%s'", answer.strip()).isEqualTo(a + " " + pid + " " + GIB_KB + " " + 5 * GIB_KB);
        }
    }

    /// $MEMCLAIM: the first line holding a positive integer of any size (blanks and leading zeros allowed) is E in GiB; any other line is skipped;
    /// no such line, or no file, gives 6. 1000 is 1000, not 6.
    @Test
    void theClaimFile_acceptsEveryPositiveInteger_andSkipsEverythingElse() throws Exception {
        var cases = new java.util.LinkedHashMap<String, String>();

        cases.put("1000\n", "1000");
        cases.put("  07 \n", "7");
        cases.put("123456789012\n", "123456789012");
        cases.put("0\n9\n", "9");
        cases.put("abc\n-3\n5x\n\n", "6");
        cases.put("0\n", "6");
        cases.put("", "6");
        for (var entry : cases.entrySet()) {
            Files.writeString(dir.resolve("mem-claim-gb"), entry.getKey());
            assertThat(lib("mem_claim_gb").stdout().strip()).as("file '%s'", entry.getKey().replace("\n", "|")).isEqualTo(entry.getValue());
        }
        Files.delete(dir.resolve("mem-claim-gb"));
        assertThat(lib("mem_claim_gb").stdout().strip()).as("no file").isEqualTo("6");

        // a 12-digit E goes through the claim arithmetic unchanged (awk doubles, no shell integer overflow)
        var holder = BbFixtures.process("sleep", "600").start();

        try {
            Files.writeString(queue.resolve("running").resolve("job-" + BbFixtures.RUN), "started=1\n");
            BbFixtures.recordLaunch(queue, "job-" + BbFixtures.RUN, holder.pid());
            Files.writeString(dir.resolve("mem-claim-gb"), "123456789012\n");
            assertThat(lib("queue_claims").stdout().strip()).endsWith(" 0 " + 123_456_789_012L * GIB_KB);
        } finally {
            holder.destroyForcibly();
        }
    }

    /// CTO ruling 2026-11-27: a floor below E is refused. pick() grants nothing while it holds, not even through the starvation override
    /// (MemAvailable 20 GB: only the refusal stops a); the MEMORY GATE line names both values; a fixed config is accepted without a restart.
    @Test
    void aFloorBelowE_grantsNothing_untilTheConfigIsFixed() throws Exception {
        memAvailableKb(20 * GIB_KB);
        more.put("BB_LOAD_STARVE_SECS", "2");
        Files.writeString(dir.resolve("mem-floor-gb"), "4\n");  // E is the default 6
        var a = enqueue("a");

        Thread.sleep(15_000);  // past the starvation bound, three poll rounds
        assertThat(in("waiting", a)).as("refused config: held").isTrue();
        assertThat(up("a")).isFalse();
        assertThat(lib("mem_held").stdout()).startsWith("refused: memory floor 4 GB (file ").contains("queue claim E 6 GB (file ");
        assertThat(bbQueue()).contains("refused: memory floor 4 GB");
        Files.writeString(dir.resolve("mem-floor-gb"), "6\n");
        assertThat(within(40_000, () -> up("a"))).as("floor 6 = E 6: accepted").isTrue();
    }

    /// The refusal outranks #53's starvation override: MemAvailable 5 GB < floor 8 and no launch runs, the exact case in which the override grants
    /// (see nothingRunning_anOutsideJobHoldingTheFloor_keepsTheStarvationOverride); with the floor file at 4 < E 6 nothing is granted, and once fixed the override grants.
    @Test
    void aFloorBelowE_isNotLiftedByTheStarvationOverride() throws Exception {
        memAvailableKb(5 * GIB_KB);
        more.put("BB_LOAD_STARVE_SECS", "2");
        Files.writeString(dir.resolve("mem-floor-gb"), "4\n");  // E is the default 6
        var a = enqueue("a");

        Thread.sleep(15_000);  // past the starvation bound, three poll rounds
        assertThat(in("waiting", a)).as("refused config: the override does not pass it").isTrue();
        assertThat(Files.readString(queue.resolve("waiting").resolve(a))).doesNotContain("mem_override=");
        Files.delete(dir.resolve("mem-floor-gb"));  // floor 8 >= E 6: accepted; 5 < 8, so the override is what grants
        assertThat(within(40_000, () -> up("a"))).as("config fixed: the override grants as before").isTrue();
        assertThat(Files.readString(queue.resolve("running").resolve(a))).contains("mem_override=");
    }

    /// mem_config_refusal: refused exactly when floor < E, both as resolved (floor default 8, E default 6; any size of E).
    @Test
    void theConfigRefusal_isFloorBelowE_andNothingElse() throws Exception {
        var cases = new String[][]{
                // floor file, claim file, refused
                {"", "", "no"}, {"8\n", "6\n", "no"}, {"6\n", "6\n", "no"}, {"5\n", "6\n", "yes"}, {"8\n", "10\n", "yes"},
                {"8\n", "1000\n", "yes"}, {"1000\n", "1000\n", "no"}, {"8\n", "123456789012\n", "yes"}, {"", "8\n", "no"}, {"", "9\n", "yes"},
                {"abc\n", "7\n", "no"}   // an unreadable floor is the default 8
        };

        for (var c : cases) {
            Files.writeString(dir.resolve("mem-floor-gb"), c[0]);
            Files.writeString(dir.resolve("mem-claim-gb"), c[1]);
            var out = lib("mem_config_refusal").stdout();

            if (c[2].equals("yes")) {
                assertThat(out).as("floor '%s' claim '%s'", c[0].strip(), c[1].strip()).startsWith("refused: memory floor ").contains("mem-floor-gb").contains("mem-claim-gb");
            } else {
                assertThat(out).as("floor '%s' claim '%s'", c[0].strip(), c[1].strip()).isEmpty();
            }
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
        return enqueue(name, Map.of());
    }

    private String enqueue(String name, Map<String, String> extraEnv) throws Exception {
        var co = checkout(name);
        var enqueueEnv = env();

        enqueueEnv.putAll(extraEnv);
        var run = BbFixtures.run(List.of(tools.resolve("bb-build").toString(), co.toString(), name), enqueueEnv, dir, null);

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
