package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// Off-queue reactors (CTO ruling 2026-11-20): `offqueue_reactors` of `ops/bb/bb-queue-lib.sh` names every maven launcher the queue did
/// not start, by pid, start, owner and full cwd, and `bb-queue` / `bb-tickets` publish it. The process table, /proc and `docker ps` are
/// fixtures (BB_TEST_PS, BB_TEST_PROC, BB_TEST_DOCKER_PS), so no real process is needed. A launcher is the queue's own when a
/// `bb-build __run` process is an ancestor, or when it runs in a container named temper-* (a temper CI job) or bb-* (bb-netns-run).
///
/// Probes: drop the `bb-build __run` ancestor test -> [#aLauncherUnderABbBuildWaiter_isTheQueuesOwn] and
/// [#offQueueReactors_areListedWithOwnerAndFullCwd_theQueuesAndContainersAreNot] red; drop the cgroup/container test ->
/// [#aTemperJobContainer_isNotOffQueue_andNeitherIsAQueueBuildContainer] red; break the owner prefix rule (`%%-*` -> `##*-`) ->
/// [#theOwner_isTheCheckoutPrefix_elseUnowned] red; stop `bb-tickets` printing `offq` ->
/// [#bbTickets_carriesTheLaunchersInItsTrailer_escaped_andAnEmptyListWhenThereAreNone_noKeyWhenTheReadFails] red.
class BbOffQueueReactorIT {
    private static final String ID_TEMPER = "a".repeat(64);
    private static final String ID_QUEUE = "b".repeat(64);
    private static final String ID_OTHER = "c".repeat(64);
    private static final long BTIME = 1_700_000_000L;

    @TempDir
    Path dir;

    private final String marker = "offq-reactor-" + BbFixtures.RUN + "-" + UUID.randomUUID();
    private Map<String, String> env;
    private Path proc;
    private Path ps;
    private Path dockerPs;
    private Path co;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(BbFixtures.linux(), "bb-queue-lib.sh uses pgrep -c and flock");
        proc = Files.createDirectories(dir.resolve("proc"));
        ps = dir.resolve("ps");
        dockerPs = dir.resolve("docker-ps");
        co = Files.createDirectories(dir.resolve("work").resolve("co"));
        Files.writeString(proc.resolve("stat"), "cpu 1 2 3\nbtime " + BTIME + "\n");
        Files.writeString(ps, "");
        Files.writeString(dockerPs, "");
        env = new HashMap<>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_TEST_QUEUE_ROOT",
                Files.createDirectories(dir.resolve("q")).toString());
        env.put("BB_TEST_PRIORITIES",
                Files.writeString(dir.resolve("prio"), "").toString());
        env.put("BB_TEST_PORT_MODULES",
                Files.writeString(dir.resolve("pgm"), "").toString());
        env.put("BB_TEST_PG_SLOTS_FILE",
                Files.writeString(dir.resolve("slots"), "3\n").toString());
        env.put("BB_TEST_TICKET_LOG",
                dir.resolve("log.tsv").toString());
        env.put("BB_TEST_REACTOR_PATTERN",
                "[" + marker.charAt(0) + "]" + marker.substring(1));
        env.put("BB_TEST_MEMINFO",
                Files.writeString(dir.resolve("meminfo"), "MemAvailable:   67108864 kB\n").toString());
        env.put("BB_TEST_LOADAVG",
                Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/100 1\n").toString());
        env.put("BB_TEST_PS", ps.toString());
        env.put("BB_TEST_PROC", proc.toString());
        env.put("BB_TEST_DOCKER_PS", dockerPs.toString());
    }

    @Test
    void offQueueReactors_areListedWithOwnerAndFullCwd_theQueuesAndContainersAreNot() throws Exception {
        var checkout = co.resolve("v3436i-head");
        var loose = dir.resolve("v3455d");

        processes("  100     1 1000 bash /h/tools/bb-build __run t1 test -pl x",
                  "  101   100 9000 /usr/bin/java -classpath boot " + marker,
                  "  200     1 9000 /usr/bin/java -classpath boot " + marker,
                  "  300     1 9000 /usr/bin/java -classpath boot " + marker,
                  "  500     1 1000 sleep 5");
        proc("101", co.resolve("q1-x"), 10, "0::/user.slice");
        proc("200", checkout, 6000, "0::/user.slice");
        proc("300", loose, 12_345, "0::/user.slice");
        proc("500", loose, 1, "0::/user.slice");
        assertThat(offq()).isEqualTo("200\t" + (BTIME + 60)
                                    + "\tv3436i\t" + checkout
                                    + "\n"
                                    + "300\t" + (BTIME + 123)
                                    + "\tunowned\t" + loose
                                    + "\n");
    }

    @Test
    void aLauncherUnderABbBuildWaiter_isTheQueuesOwn() throws Exception {
        processes("  100     1 1000 bash /h/tools/bb-build __run t1 test",
                  "  110   100 1000 bash -c mvn",
                  "  120   110 9000 /usr/bin/java -classpath boot " + marker);
        proc("120", co.resolve("q1-x"), 10, "0::/user.slice");
        assertThat(offq()).as("a grandchild of the waiter").isEmpty();
    }

    @Test
    void aTemperJobContainer_isNotOffQueue_andNeitherIsAQueueBuildContainer() throws Exception {
        processes("  399     1 1000 /usr/bin/containerd-shim-runc-v2 -id x",
                  "  400   399 9000 /usr/bin/java -classpath boot " + marker,
                  "  410   399 9000 /usr/bin/java -classpath boot " + marker,
                  "  420   399 9000 /usr/bin/java -classpath boot " + marker,
                  "  430   399 9000 /usr/bin/java -classpath boot " + marker);
        proc("400", co.resolve("t-1"), 10, "0::/system.slice/docker-" + ID_TEMPER + ".scope");
        proc("410", co.resolve("t-2"), 10, "12:cpu:/docker/" + ID_QUEUE + "\n0::/");
        proc("420", dir.resolve("other"), 10, "0::/system.slice/docker-" + ID_OTHER + ".scope");
        proc("430", dir.resolve("lost"), 10, "0::/system.slice/docker-" + "d".repeat(64) + ".scope");
        Files.writeString(dockerPs,
                          ID_TEMPER
                         + " temper-job9-unit1\n" + ID_QUEUE
                         + " bb-1795150033515302636-1530446\n" + ID_OTHER
                         + " postgres-main\n");
        assertThat(offq().lines().map(line -> line.split("\t") [0]).toList()).as("a temper-* and a bb-* container are the queue's; another container's launcher, and one in a container docker does not list, are off-queue")
                  .containsExactly("420", "430");
    }

    /// A queued port-group ticket with BB_PG_ISOLATE runs its mvn in a container `bb-<ticket id>` (label bb.ticket) through bb-netns-run, so
    /// its ppid chain ends at containerd-shim, never at `bb-build __run` (seen live: cloudbb-6 pid 21520, ~/work/co/v3468b-m). It is the
    /// queue's own, whatever its cwd; a launcher in another container is not. Mutation: match only `temper-*` names in offqueue_reactors ->
    /// red.
    @Test
    void aNetnsTicketsReactor_isTheQueuesOwn_aLookAlikeContainerIsNot() throws Exception {
        processes("  399     1 1000 /usr/bin/containerd-shim-runc-v2 -id x",
                  "  400   399 1000 /sbin/docker-init -- /usr/bin/bash -c mvn",
                  "  410   400 9000 /usr/bin/java -classpath boot " + marker,
                  "  420   399 9000 /usr/bin/java -classpath boot " + marker);
        proc("410", co.resolve("v3468b-m"), 10, "0::/system.slice/docker-" + ID_QUEUE + ".scope");
        proc("420", co.resolve("v3468b-n"), 10, "0::/system.slice/docker-" + ID_OTHER + ".scope");
        Files.writeString(dockerPs,
                          ID_QUEUE + " bb-1795150033515302636-1530446\n" + ID_OTHER + " mybb-1795150033515302636-1\n");
        assertThat(offq().lines().map(line -> line.split("\t") [0]).toList()).containsExactly("420");
    }

    @Test
    void whenDockerCannotAnswer_aContainerisedLauncherIsListed_neverHidden() throws Exception {
        processes("  400     1 9000 /usr/bin/java -classpath boot " + marker);
        proc("400", co.resolve("t-1"), 10, "0::/system.slice/docker-" + ID_TEMPER + ".scope");
        Files.delete(dockerPs);
        assertThat(offq().lines().map(line -> line.split("\t") [0]).toList()).containsExactly("400");
    }

    /// The `docker ps` of offqueue_reactors is on the pick path (mem_held reads it) and bounded by `timeout -s KILL 5`: a docker that hangs AND
    /// ignores TERM (`trap '' TERM`; a plain `timeout 5` held 25.01 s in v130d) is cut at 5 s, and the containerised launcher is listed (over-reports,
    /// never hides). Window [4.9 s, 8 s): `timeout` cannot fire early; the 3 s above the bound is the awk after the cut (0.01-0.03 s measured at
    /// load 15), so a loaded host does not reach it, and a 12 s bound is 4 s past it. Mutant: `timeout 5 docker ps` (TERM) -> 25 s, red.
    @Test
    void aDockerPsIgnoringTerm_isCutAtFiveSeconds_theLauncherIsListed() throws Exception {
        var tools = Files.createDirectories(dir.resolve("tools"));

        BbFixtures.executable(tools.resolve("docker"), "#!/bin/bash\ntrap '' TERM\nexec sleep 25\n");
        env.remove("BB_TEST_DOCKER_PS");
        env.put("PATH", tools + ":" + System.getenv("PATH"));
        processes("  400     1 9000 /usr/bin/java -classpath boot " + marker);
        proc("400", co.resolve("t-1"), 10, "0::/system.slice/docker-" + ID_TEMPER + ".scope");

        var t0 = System.nanoTime();
        var listed = offq().lines().map(line -> line.split("\t") [0]).toList();
        var seconds = (System.nanoTime() - t0) / 1_000_000_000.0;

        assertThat(listed).containsExactly("400");
        assertThat(seconds).as("cut at the 5 s bound, not before (it hung) and not at the 25 s hang").isBetween(4.9, 8.0);
    }

    @Test
    void aLauncherThatVanishedFromProc_isSkipped() throws Exception {
        processes("  200     1 9000 /usr/bin/java -classpath boot " + marker);
        assertThat(offq()).isEmpty();
    }

    @Test
    void theOwner_isTheCheckoutPrefix_elseUnowned() throws Exception {
        var root = co.toString();

        assertThat(owner(root + "/v3436i-head")).isEqualTo("v3436i");
        assertThat(owner(root + "/m9a0-impl/aether/node")).as("a submodule below the checkout").isEqualTo("m9a0");
        assertThat(owner(root + "/solo")).as("no dash: the whole name").isEqualTo("solo");
        assertThat(owner(root + "/tSM-m1b-r3")).as("up to the first dash").isEqualTo("tSM");
        assertThat(owner(dir + "/v3455d")).as("outside ~/work/co").isEqualTo("unowned");
        assertThat(owner(root)).as("the root itself").isEqualTo("unowned");
        assertThat(owner(root + "/-odd")).as("an empty prefix").isEqualTo("unowned");
    }

    @Test
    void bbQueue_listsEachOffQueueLauncherBelowTheRunningRows_inTheWorkersSkippedShape() throws Exception {
        var loose = dir.resolve("v3455d");

        processes("  200     1 9000 /usr/bin/java -classpath boot " + marker);
        proc("200", loose, 6000, "0::/user.slice");
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-queue").toString(),
                                         "0"),
                                 env,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();
        var lines = run.stdout().lines().toList();
        var first = lines.indexOf("  + 1 reactor(s) not started through the queue (direct mvn or BB_QUEUE=0)");

        assertThat(first).as(run.stdout()).isGreaterThan(0);
        assertThat(lines.get(first + 1)).startsWith("  + off-queue pid 200 owner unowned age ")
                  .endsWith(" cwd " + loose);
        assertThat(run.stdout().lines().filter(line -> line.startsWith("RUNNING")).count()).isOne();
        var offq = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-queue").toString(),
                                          "offq"),
                                  env,
                                  dir,
                                  null);

        assertThat(offq.stdout()).as("the sentinel line first").isEqualTo("offq v1\n200\t" + (BTIME + 60) + "\tunowned\t" + loose + "\n");
    }

    @Test
    void bbTickets_carriesTheLaunchersInItsTrailer_escaped_andAnEmptyListWhenThereAreNone_noKeyWhenTheReadFails() throws Exception {
        for (var sub : List.of("running", "waiting", "done")) {
            Files.createDirectories(dir.resolve("q").resolve(sub));
        }

        var none = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-tickets").toString()),
                                  env,
                                  dir,
                                  null);

        assertThat(none.rc()).as(none.stderr()).isZero();
        assertThat(none.stdout().lines().toList().getLast()).as("a successful read that found none says so")
                  .endsWith(",\"offq\":[]}");
        var stub = Files.createDirectories(dir.resolve("stub"));

        Files.copy(BbFixtures.OPS_BB.resolve("bb-tickets"), stub.resolve("bb-tickets"));
        Files.setPosixFilePermissions(stub.resolve("bb-tickets"),
                                      java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(stub.resolve("bb-queue"), "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(stub.resolve("bb-queue"),
                                      java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        var failed = BbFixtures.run(List.of(stub.resolve("bb-tickets").toString()),
                                    env,
                                    dir,
                                    null);

        assertThat(failed.rc()).as(failed.stderr()).isZero();
        assertThat(failed.stdout().lines().toList().getLast()).as("a failed read has no offq key, so the controller keeps its list")
                  .doesNotContain("offq");
        var odd = dir.resolve("a\"b\\c d");

        processes("  200     1 9000 /usr/bin/java -classpath boot " + marker);
        proc("200", odd, 6000, "0::/user.slice");
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-tickets").toString()),
                                 env,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();
        var trailer = run.stdout().lines().toList().getLast();

        assertThat(trailer).contains("\"offq\":[{\"pid\":200,\"startS\":" + (BTIME + 60)
                                    + ",\"owner\":\"unowned\",\"cwd\":\"" + dir.toString()
                                                                               .replace("\\", "\\\\")
                                                                               .replace("\"", "\\\"")
                                    + "/a\\\"b\\\\c d\"}]}");
    }

    /// The owner is a checkout directory name, so it needs the same JSON escaping as the cwd: a checkout `a"b\\c-x` must not make the
    /// trailer invalid (the host's whole collection would fail). Mutation: leave `$3` unescaped in bb-tickets -> red.
    @Test
    void bbTickets_escapesAnOwnerWithAQuoteAndABackslash_theTrailerStaysValidJson() throws Exception {
        var checkout = co.resolve("a\"b\\c-x");

        processes("  200     1 9000 /usr/bin/java -classpath boot " + marker);
        proc("200", checkout, 6000, "0::/user.slice");
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-tickets").toString()),
                                 env,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();
        var trailer = run.stdout().lines().toList().getLast();

        assertThat(trailer).contains("\"owner\":\"a\\\"b\\\\c\",\"cwd\":\"");
        var file = Files.writeString(dir.resolve("trailer.json"), trailer);
        var json = BbFixtures.run(List.of("python3", "-c", "import json,sys; json.load(open(sys.argv[1]))", file.toString()),
                                  env,
                                  dir,
                                  null);

        assertThat(json.rc()).as("the trailer parses: " + json.stderr()).isZero();
    }

    /// An older `bb-queue` given `offq` runs its plain listing, which prints no sentinel: that is a failed read (no `offq` key,
    /// the controller keeps its list), not a successful empty one that would clear the marks. Mutation: drop the sentinel check in
    /// bb-tickets -> red.
    @Test
    void bbTickets_anOlderBbQueueWithoutTheSentinel_isAFailedRead_noOffqKey() throws Exception {
        for (var sub : List.of("running", "waiting", "done")) {
            Files.createDirectories(dir.resolve("q").resolve(sub));
        }
        var stub = Files.createDirectories(dir.resolve("stub-old"));
        var exec = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");

        Files.copy(BbFixtures.OPS_BB.resolve("bb-tickets"), stub.resolve("bb-tickets"));
        Files.setPosixFilePermissions(stub.resolve("bb-tickets"), exec);
        Files.writeString(stub.resolve("bb-queue"),
                          "#!/bin/sh\nprintf 'RUNNING 0\\n  + 1 reactor(s) not started through the queue\\n'\n");
        Files.setPosixFilePermissions(stub.resolve("bb-queue"), exec);
        var run = BbFixtures.run(List.of(stub.resolve("bb-tickets").toString()),
                                 env,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(run.stdout().lines().toList().getLast()).as("an older bb-queue: the read failed, not found none")
                  .doesNotContain("offq");
    }

    // ---- fixtures ---------------------------------------------------------------------------------------------
    private String offq() throws Exception {
        var run = lib("offqueue_reactors");

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout();
    }

    private String owner(String cwd) throws Exception {
        var run = BbFixtures.run(List.of("bash",
                                         "-c",
                                         ". \"$1\" && offq_owner \"$2\"",
                                         "bash",
                                         BbFixtures.OPS_BB.resolve("bb-queue-lib.sh").toString(),
                                         cwd),
                                 env,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout()
                  .strip();
    }

    private BbFixtures.Outcome lib(String function) throws Exception {
        return BbFixtures.run(List.of("bash",
                                      "-c",
                                      ". \"$1\" && " + function,
                                      "bash",
                                      BbFixtures.OPS_BB.resolve("bb-queue-lib.sh").toString()),
                              env,
                              dir,
                              null);
    }

    private void processes(String... lines) throws IOException {
        Files.writeString(ps, String.join("\n", lines) + "\n");
    }

    /// A fake /proc/<pid>: cwd (a symlink, dangling is fine), cgroup, and a stat whose 22nd field is `ticks` since boot.
    private void proc(String pid, Path cwd, long ticks, String cgroup) throws IOException {
        var home = Files.createDirectories(proc.resolve(pid));

        Files.createSymbolicLink(home.resolve("cwd"), cwd);
        Files.writeString(home.resolve("cgroup"), cgroup + "\n");
        Files.writeString(home.resolve("stat"), pid + " (java) S " + "0 ".repeat(18) + ticks + " 0 0\n");
    }
}
