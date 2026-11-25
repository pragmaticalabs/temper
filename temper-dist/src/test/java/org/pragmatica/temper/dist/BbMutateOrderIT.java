package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// Mutant tickets (#116, CTO ruling of 2026-11-24): pick decides. Per host at most one mutant ticket of an owner RUNS, and of the owner's waiting
/// ones the smallest created= is next; a ticket that is not eligible takes no part in pick. order_at= (the batch's submit time, never before
/// its driver's start) places the eligible one against other owners' tickets. A waiting ticket is dropped when its driver is gone; a running one
/// ends at its bound, driver or no driver. The real bb-build, bb-queue-lib.sh and bb-mutate run on a throwaway queue; maven is a script. A DRIVER
/// is either the real bb-mutate or a stand-in named bb-mutate that makes one bb-build call and stays alive.
///
/// Probes (each red on its own row):
///  - mutant_waits sorts by order_at instead of created -> [#twoBatchesOfOneOwner_alternateBuildByBuild]
///  - mutant_waits ignores running/ -> [#parallelSubmitsOfOneOwner_neverRunTwoMutantTickets]
///  - pick's `oldest` taken over every waiting ticket -> [#anIneligibleTicket_isNotTakenByTheStarvationOverride]
///  - the bound runner is not started -> [#aKilledDriversRunningBuild_endsAtItsBound_andNotBefore]
///  - the order_at >= driver start check removed -> [#anOrderAtOutsideTheDriversLifetime_isRefused_andNothingIsQueued]
///  - never-in-the-future check removed -> [#anOrderAtOutsideTheDriversLifetime_isRefused_andNothingIsQueued]
///  - created_s() prefers order_at -> [#orderAt_neverReachesTheLoadStarvationOverride]
///  - order() ignores order_at -> [#aMutantTicket_sortsByItsBatchSubmitTime_aheadOfALaterTicket]
///  - driver check removed from the waiter -> [#aKilledDriver_leavesNoWaitingTicket_andItsRunningBuildFinishes]
///  - mutate_driver check removed -> [#aCallWhoseDriverIsMissingOrForeign_isRefused_andNothingIsQueued]
class BbMutateOrderIT {
    private static final long SECOND = 1_000_000_000L;

    private static final String DRIVER = """
        #!/usr/bin/env python3
        # stand-in driver: <checkout> <call file> [NAME=value (SELF = this pid, START[-1] = its start in ns) | NAME (removed)]...
        # takes its order_at, writes <call file>.up, waits while <call file>.gate exists, then makes its call
        import os, subprocess, sys, time
        co, call, changes = sys.argv[1], sys.argv[2], sys.argv[3:]
        start = open("/proc/self/stat").read().rpartition(")")[2].split()[19]
        btime = next(int(l.split()[1]) for l in open("/proc/stat") if l.startswith("btime "))
        ns = btime * 10**9 + int(start) * (10**9 // os.sysconf("SC_CLK_TCK"))
        known = {"SELF": str(os.getpid()), "START": str(ns), "START-1": str(ns - 1)}
        env = dict(os.environ, BB_VIA="bb-mutate:spec:m1", BB_ORDER_AT=str(time.time_ns()), BB_DRIVER_PID=str(os.getpid()), BB_DRIVER_START=start,
                   BB_MUTANT_BOUND="600")
        for c in changes:
            k, sep, v = c.partition("=")
            env.pop(k, None)
            if sep:
                env[k] = known.get(v, v)
        open(call + ".up.tmp", "w").write(env.get("BB_ORDER_AT", ""))
        os.rename(call + ".up.tmp", call + ".up")
        while os.path.exists(call + ".gate"):
            time.sleep(0.1)
        cp = subprocess.run([os.environ["HOME"] + "/tools/bb-build", co, "run"], stdin=subprocess.DEVNULL, capture_output=True, text=True, env=env)
        open(call + ".tmp", "w").write(f"{cp.returncode}\\n{cp.stdout}\\n{cp.stderr}")
        os.rename(call + ".tmp", call)
        time.sleep(1200)
        """;

    /// maven: records its BB_* environment (env.<checkout>, whole before the next line), then its start order (a checkout named in runs.log has its
    /// env file), waits while <dir>/hold or <dir>/hold.<checkout> exists, or <dir>/hold.mut1 and the file holds MUT1, or <dir>/step without
    /// <dir>/step.<n> (n = its place in runs.log), then writes the surefire report of module A, failing when the module's file is mutated.
    private static final String LAUNCH = """
        #!/bin/bash
        n=${PWD##*/}
        env | grep -E '^BB_(ORDER_AT|DRIVER_|MUTANT_|VIA|OWNER)' > "$HOLD_DIR/env.$n.tmp"; mv "$HOLD_DIR/env.$n.tmp" "$HOLD_DIR/env.$n"
        echo "$n" >> "$HOLD_DIR/runs.log"; k=$(wc -l < "$HOLD_DIR/runs.log")
        while [ -e "$HOLD_DIR/hold" ] || [ -e "$HOLD_DIR/hold.$n" ] || { [ -e "$HOLD_DIR/hold.mut1" ] && grep -q MUT1 A/src/f.txt; } \\
          || { [ -e "$HOLD_DIR/step" ] && [ ! -e "$HOLD_DIR/step.$k" ]; }; do sleep 0.2; done
        f=0; b=""; if grep -q MUT A/src/f.txt; then f=1; b='<failure message="x">x</failure>'; fi
        mkdir -p A/target/surefire-reports
        echo "<testsuite name=\\"ATest\\" tests=\\"1\\" failures=\\"$f\\" errors=\\"0\\" skipped=\\"0\\"><testcase classname=\\"ATest\\" name=\\"t1\\" time=\\"0\\">$b</testcase></testsuite>" \\
          > A/target/surefire-reports/TEST-ATest.xml
        exit $f
        """;

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private Path driver;
    private final List<Process> processes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock, pgrep and GNU tools");
        dir = dir.toRealPath();
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        for (var tool : List.of("bb-build", "bb-mutate", "bb-queue")) {
            BbFixtures.executable(tools.resolve(tool),
                                  Files.readString(BbFixtures.OPS_BB.resolve(tool)));
        }

        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        BbFixtures.executable(tools.resolve("bb-cp-record"), "#!/bin/sh\nexit 0\n");
        BbFixtures.executable(tools.resolve("launch"), LAUNCH);
        Files.createDirectories(dir.resolve("drv"));
        driver = BbFixtures.executable(dir.resolve("drv/bb-mutate"), DRIVER);
        Files.writeString(dir.resolve("max-reactors"), "1\n");
        Files.writeString(dir.resolve("priorities"), "vip 5\n");
        Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        Files.writeString(dir.resolve("meminfo"), "MemAvailable:   67108864 kB\n");
        Files.writeString(dir.resolve("ps"), "");
    }

    @AfterEach
    void cleanUp() throws Exception {
        processes.forEach(Process::destroyForcibly);
        for (var state : List.of("waiting", "running")) {
            for (var id : ids(state)) {
                for (var pid : BbFixtures.waiters(id)) {
                    ProcessHandle.of(pid).ifPresent(h -> h.descendants()
                                                          .forEach(ProcessHandle::destroyForcibly));
                }

                BbFixtures.kill(BbFixtures.waiters(id));
            }
        }

        BbFixtures.kill(under(dir));
    }

    private static List<Long> under(Path root) throws Exception {
        return BbFixtures.run(List.of("pgrep",
                                      "-u",
                                      System.getProperty("user.name"),
                                      "-f",
                                      "--",
                                      root.toString()),
                              Map.of(),
                              null,
                              null)
                         .stdout()
                         .lines()
                         .filter(line -> !line.isBlank())
                         .map(line -> Long.parseLong(line.strip()))
                         .toList();
    }

    private Map<String, String> env(String owner) {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", owner);
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_LOADAVG",
                dir.resolve("loadavg").toString());
        env.put("BB_TEST_MEMINFO",
                dir.resolve("meminfo").toString());
        env.put("BB_TEST_PS",
                dir.resolve("ps").toString());
        env.put("BB_TEST_MAX_REACTORS_FILE",
                dir.resolve("max-reactors").toString());
        env.put("BB_TEST_TICKET_LOG",
                dir.resolve("log.tsv").toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_DEDUP", "0");
        env.put("BB_NO_CP_RECORD", "1");
        env.put("BB_TEST_MVN",
                tools.resolve("launch").toString());
        env.put("HOLD_DIR", dir.toString());

        return env;
    }

    private Path checkout(String name) throws Exception {
        var co = dir.resolve("work/co").resolve(name);
        var script = """
            set -e
            mkdir -p "$1/.mvn" "$1/A/src" && cd "$1"
            git init -q . && git init -q --bare "$2"
            echo "-Dmaven.repo.local=$PWD/.m2-local" > .mvn/maven.config
            printf '.bb/\\ntarget/\\n.m2-local/\\n' > .gitignore
            printf 'ok\\nko\\n' > A/src/f.txt
            git add -A && git -c user.name=t -c user.email=t@t commit -qm init
            git remote add origin "$2" && git push -q origin HEAD:refs/heads/main && git fetch -q origin
            git checkout -q --detach
            """;
        var run = BbFixtures.run(List.of("bash",
                                         "-c",
                                         script,
                                         "bash",
                                         co.toString(),
                                         dir.resolve(name + ".git").toString()),
                                 Map.of("HOME", dir.toString()),
                                 null,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return co;
    }

    private record Call(int rc, String out, long pid) {
        String ticket() {
            assertThat(rc).as(out).isZero();

            return out.replaceAll("(?s).*queued ticket=(\\S+).*", "$1");
        }
    }

    private Process start(List<String> command, Map<String, String> env, Path output) throws Exception {
        var process = BbFixtures.process(command, env)
                                .redirectErrorStream(true)
                                .redirectOutput(output.toFile())
                                .start();

        processes.add(process);

        return process;
    }

    /// One bb-build call of a stand-in driver, which stays alive after it.
    private Call drive(String owner, String checkout, String... changes) throws Exception {
        return drive(driver, owner, checkout, changes);
    }

    private Call drive(Path script, String owner, String checkout, String... changes) throws Exception {
        return answer(checkout, launch(script, owner, checkout, changes));
    }

    private Process launch(Path script, String owner, String checkout, String... changes) throws Exception {
        var command = new ArrayList<>(List.of("python3",
                                              script.toString(),
                                              checkout(checkout).toString(),
                                              call(checkout).toString()));

        command.addAll(List.of(changes));

        return start(command, env(owner), dir.resolve(checkout + ".driver.log"));
    }

    private Path call(String checkout) {
        return dir.resolve(checkout + ".call");
    }

    private Call answer(String checkout, Process process) throws Exception {
        assertThat(within(60,
                          () -> Files.exists(call(checkout)))).as(Files.readString(dir.resolve(checkout + ".driver.log")))
                  .isTrue();
        var text = Files.readString(call(checkout));

        return new Call(Integer.parseInt(text.substring(0, text.indexOf('\n'))),
                        text,
                        process.pid());
    }

    /// A driver that has taken its order_at (its submit time) and makes its call only at [#enqueue].
    private Process submit(String owner, String checkout, String... changes) throws Exception {
        Files.writeString(dir.resolve(checkout + ".call.gate"), "");
        var process = launch(driver, owner, checkout, changes);

        assertThat(within(60, () -> Files.exists(dir.resolve(checkout + ".call.up")))).isTrue();

        return process;
    }

    private long orderAt(String checkout) throws Exception {
        return Long.parseLong(Files.readString(dir.resolve(checkout + ".call.up")));
    }

    private Call enqueue(String checkout, Process submitted) throws Exception {
        Files.delete(dir.resolve(checkout + ".call.gate"));

        return answer(checkout, submitted);
    }

    /// An ordinary bb-build call (no driver, no BB_ORDER_AT) plus `more` in its environment.
    private Call plain(String owner, String checkout, Map<String, String> more) throws Exception {
        var env = env(owner);

        env.putAll(more);
        var run = BbFixtures.run(List.of(tools.resolve("bb-build").toString(),
                                         checkout(checkout).toString(),
                                         "run"),
                                 env,
                                 dir,
                                 null);

        return new Call(run.rc(),
                        run.stdout() + run.stderr(),
                        0);
    }

    private String plain(String owner, String checkout) throws Exception {
        return plain(owner, checkout, Map.of()).ticket();
    }

    /// Occupies the only slot until <dir>/hold.<checkout> is removed.
    private String blocker() throws Exception {
        Files.writeString(dir.resolve("hold.blk-1"), "");
        var id = plain("blk", "blk-1");

        assertThat(within(60, () -> runs().contains("blk-1"))).as("the blocker runs").isTrue();

        return id;
    }

    private void release(String checkout) throws Exception {
        Files.delete(dir.resolve("hold." + checkout));
    }

    private List<String> runs() {
        return unchecked(() -> Files.exists(dir.resolve("runs.log"))
                               ? Files.readAllLines(dir.resolve("runs.log"))
                               : List.of());
    }

    private List<String> ids(String state) {
        return unchecked(() -> {
            try (var names = Files.list(queue.resolve(state))) {
                return names.map(p -> p.getFileName()
                                       .toString())
                            .sorted()
                            .toList();
            }
        });
    }

    private String field(String state, String id, String name) {
        return unchecked(() -> Files.readAllLines(queue.resolve(state).resolve(id))).stream()
                        .filter(line -> line.startsWith(name + "="))
                        .map(line -> line.substring(name.length() + 1))
                        .reduce("",
                                (first, last) -> last);
    }

    private boolean in(String state, String id) {
        return Files.exists(queue.resolve(state).resolve(id));
    }

    private interface Throwing<T> {
        T get() throws Exception;
    }

    private static <T> T unchecked(Throwing<T> read) {
        try {
            return read.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean within(long seconds, Supplier<Boolean> condition) throws InterruptedException {
        var deadline = System.nanoTime() + seconds * SECOND;

        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return true;
            }

            Thread.sleep(200);
        }

        return condition.get();
    }

    private static long now() {
        var now = Instant.now();

        return now.getEpochSecond() * SECOND + now.getNano();
    }

    private void nothingQueued() {
        assertThat(List.of(ids("waiting"), ids("running"), ids("done"))).containsOnly(List.of());
    }

    private String lib(Path lib, Path root, String function) throws Exception {
        var env = env("mo");

        env.put("BB_TEST_QUEUE_ROOT", root.toString());
        var run = BbFixtures.run(List.of("bash", "-c", ". \"$1\" && " + function, "bash", lib.toString()),
                                 env,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout()
                  .strip();
    }

    // ---------------------------------------------------------------- the turn
    /// At most one RUNNING mutant ticket of an owner, under parallel submits: four drivers of one owner call bb-build at once on a host with room for
    /// five launches, and one of their tickets runs. Controls: tickets enqueued BEHIND the three waiting ones (two ordinary ones of the same owner, a
    /// mutant one of another owner) do run meanwhile, so the waiting ones were passed over by the rule and not by a cap; and the lock-held sampler
    /// sees two running tickets of that owner. Afterwards the four run one at a time, by created=.
    @Test
    void parallelSubmitsOfOneOwner_neverRunTwoMutantTickets() throws Exception {
        Files.writeString(dir.resolve("max-reactors"), "9\n");
        Files.writeString(dir.resolve("hold"), "");
        var drivers = new ArrayList<Process>();

        for (var i = 1; i <= 4; i++) {
            drivers.add(submit("mo", "mo-" + i));
        }

        for (var i = 1; i <= 4; i++) {
            Files.delete(dir.resolve("mo-" + i + ".call.gate"));
        }

        var tickets = new HashMap<String, String>();

        for (var i = 1; i <= 4; i++) {
            tickets.put("mo-" + i,
                        answer("mo-" + i, drivers.get(i - 1)).ticket());
        }

        try (var mutants = new Sampler(() -> count("running", "mo", "^via=bb-mutate:"));
             var all = new Sampler(() -> count("running", "mo", "^id="))) {
            plain("mo", "mo-ord-1");
            plain("mo", "mo-ord-2");
            drive("mp", "mp-1").ticket();
            assertThat(within(60,
                              () -> runs().containsAll(List.of("mo-ord-1", "mo-ord-2", "mp-1")))).as("what is behind the waiting mutant tickets runs: " + runs())
                      .isTrue();
            assertThat(runs().stream()
                           .filter(run -> run.matches("mo-[0-9]"))).as("running mutant builds of mo").hasSize(1);
            Files.delete(dir.resolve("hold"));
            assertThat(within(180, () -> ids("done").size() == 7)).as(ids("waiting").toString()).isTrue();
            assertThat(mutants.max()).as("running mutant tickets of mo").isEqualTo(1);
            assertThat(all.max()).as("running tickets of mo").isGreaterThanOrEqualTo(2);
        }

        assertThat(runs().stream().filter(run -> run.matches("mo-[0-9]"))).containsExactlyElementsOf(tickets.keySet()
                                                                                                         .stream()
                                                                                                         .sorted(Comparator.comparing(run -> field("done",
                                                                                                                                                   tickets.get(run),
                                                                                                                                                   "created")))
                                                                                                         .toList());
        assertThat(tickets.values()).allMatch(id -> field("done", id, "rc").equals("0"));
    }

    /// Writes one ticket of a static queue.
    private void ticket(Path root, String state, String id, String owner, boolean mutant, long created, String more) throws Exception {
        Files.writeString(root.resolve(state).resolve(id),
                          "id=" + id + "\nowner=" + owner + "\nco=" + dir + "/none\ncreated=" + created + "\n" + (mutant
                                                                                                                  ? "via=bb-mutate:spec:m1\n"
                                                                                                                  : "") + more + "args=run\n");
    }

    private List<String> grantable(Path root) throws Exception {
        return lib(tools.resolve("bb-queue-lib.sh"), root, "grantable").lines()
                  .toList();
    }

    private String pick(Path root, int launches) throws Exception {
        return lib(tools.resolve("bb-queue-lib.sh"), root, "pick " + launches + " none");
    }

    /// The owner's next mutant ticket is the one with the smallest created= (tie: the smallest id), whatever the order_at=, and none while one runs.
    /// Controls: with another owner on one of the two, both are grantable and order_at= sorts them; an ordinary ticket of the owner is always
    /// grantable; a mutant ticket without order_at= (a bb-mutate before #116) counts, waiting and running.
    @Test
    void theTurn_goesByCreatedThenId_neverByOrderAt_andNotWhileOneRuns() throws Exception {
        var root = goldenQueue();
        var t = now();

        ticket(root, "waiting", "5-1", "mo", true, t + 100, "order_at=" + (t + 900) + "\n");
        ticket(root, "waiting", "4-1", "mo", true, t + 200, "order_at=" + (t + 10) + "\n");
        ticket(root, "waiting", "6-1", "mo", false, t + 2000, "");
        assertThat(grantable(root)).containsExactly("5-1", "6-1");
        ticket(root, "waiting", "4-1", "mp", true, t + 200, "order_at=" + (t + 10) + "\n");
        assertThat(grantable(root)).containsExactly("4-1", "5-1", "6-1");
        ticket(root, "waiting", "4-1", "mo", true, t + 100, "order_at=" + (t + 950) + "\n");
        assertThat(grantable(root)).containsExactly("4-1", "6-1");
        ticket(root, "waiting", "3-1", "mo", true, t + 50, "");
        assertThat(grantable(root)).containsExactly("3-1", "6-1");
        ticket(root, "running", "1-1", "mo", true, t, "");
        assertThat(grantable(root)).containsExactly("6-1");
        ticket(root, "running", "1-1", "mo", false, t, "");
        assertThat(grantable(root)).containsExactly("3-1", "6-1");
    }

    /// An ineligible ticket skipped for its cap gets no blocked_since=. Controls: in the same pick an ordinary ticket of that cap gets it, and so does
    /// the mutant ticket itself once its owner's running one is gone.
    @Test
    void anIneligibleTicket_accruesNoBlockedSince() throws Exception {
        var root = goldenQueue();
        var t = now();

        ticket(root, "running", "1-1", "mo", true, t, "");
        ticket(root, "waiting", "2-1", "mo", true, t + 1, "cap=1\n");
        ticket(root, "waiting", "3-1", "ord", false, t + 2, "cap=1\n");
        assertThat(pick(root, 1)).isEmpty();
        assertThat(Files.readString(root.resolve("waiting/3-1"))).contains("\nblocked_since=");
        assertThat(Files.readString(root.resolve("waiting/2-1"))).doesNotContain("blocked_since=");
        Files.delete(root.resolve("running/1-1"));
        assertThat(pick(root, 1)).isEmpty();
        assertThat(Files.readString(root.resolve("waiting/2-1"))).contains("\nblocked_since=");
    }

    /// Under a held load gate the starvation override takes the oldest ELIGIBLE ticket: an older ineligible mutant ticket is neither taken nor does its
    /// age let anything else through. Control: with its owner's running ticket gone, the same ticket is the one taken.
    @Test
    void anIneligibleTicket_isNotTakenByTheStarvationOverride() throws Exception {
        var root = goldenQueue();
        var t = now();

        Files.writeString(dir.resolve("loadavg"), "9999.00 9999.00 9999.00 1/1 1\n");
        ticket(root, "running", "1-1", "mo", true, t - 7300 * SECOND, "");
        ticket(root, "waiting", "2-1", "mo", true, t - 7200 * SECOND, "cap=5\n");
        ticket(root, "waiting", "3-1", "ord", false, t - 3600 * SECOND, "cap=5\n");
        assertThat(pick(root, 0)).isEqualTo("3-1");
        ticket(root, "waiting", "3-1", "ord", false, t, "cap=5\n");
        assertThat(pick(root, 0)).as("no ELIGIBLE ticket has starved").isEmpty();
        Files.delete(root.resolve("running/1-1"));
        assertThat(pick(root, 0)).isEqualTo("2-1");
    }

    /// An ineligible ticket blocks no ticket behind it: neither by an expired blocked_since= it got while it was eligible (the starvation bound), nor as
    /// a port-group ticket that waits for the group to drain. Controls: with its owner's running ticket gone, the same queues grant nothing.
    @Test
    void anIneligibleTicket_blocksNoTicketBehindIt() throws Exception {
        var starved = goldenQueue();
        var t = now();

        Files.writeString(dir.resolve("max-reactors"), "9\n");  // at host cap 1 the one launch stops every ticket, eligible or not
        ticket(starved, "running", "1-1", "mo", true, t, "");
        ticket(starved, "waiting", "2-1", "mo", true, t + 1, "cap=1\nblocked_since=1\n");
        ticket(starved, "waiting", "3-1", "ord", false, t + 2, "cap=5\n");
        assertThat(pick(starved, 1)).isEqualTo("3-1");
        Files.delete(starved.resolve("running/1-1"));
        assertThat(pick(starved, 1)).isEmpty();

        ticket(starved, "running", "1-1", "mo", true, t, "");
        ticket(starved, "waiting", "2-1", "mo", true, t + 1, "cap=1\ngroup=1\n");
        ticket(starved, "waiting", "3-1", "ord", false, t + 2, "cap=5\ngroup=1\niso=1\n");
        assertThat(pick(starved, 1)).isEqualTo("3-1");
        Files.delete(starved.resolve("running/1-1"));
        assertThat(pick(starved, 1)).isEmpty();
    }

    private Process mutate(Path tool, String owner, String name, String build, int mutants, String... more) throws Exception {
        var mutations = new ArrayList<String>();

        for (var i = 1; i <= mutants; i++) {
            mutations.add("""
                          {"id":"m%d","file":"A/src/f.txt","kind":"lit","old":"ok","new":"MUT%d","expect":["ATest"]}""".formatted(i,
                                                                                                                                  i));
        }

        Files.writeString(dir.resolve(name + ".json"),
                          "{\"build\":" + build + ",\"mutations\":[" + String.join(",", mutations) + "]}");

        var command = new ArrayList<>(List.of("python3",
                                              tool.toString(),
                                              checkout(name).toString(),
                                              dir.resolve(name + ".json").toString(),
                                              dir.resolve("out-" + name).toString(),
                                              "--single"));

        command.addAll(List.of(more));

        return start(command, env(owner), dir.resolve(name + ".driver.log"));
    }

    private String mutateLog(String name) {
        var log = dir.resolve("out-" + name).resolve("bb-mutate.log");

        return unchecked(() -> Files.exists(log)
                               ? Files.readString(log)
                               : "");
    }

    /// The tickets of `owner` in `state`/ with a line matching `pattern`, counted under the queue lock.
    private int count(String state, String owner, String pattern) {
        var count = unchecked(() -> BbFixtures.run(List.of("bash",
                                                           "-c",
                                                           "exec 8> \"$1/.lock\"; flock 8; grep -ls \"^owner=$3\\$\" \"$1/$2\"/* | xargs -r grep -ls -e \"$4\" | wc -l",
                                                           "bash",
                                                           queue.toString(),
                                                           state,
                                                           owner,
                                                           pattern),
                                                   env("mo"),
                                                   dir,
                                                   null));

        return Integer.parseInt(count.stdout().strip());
    }

    /// The maximum of a value read in a loop from construction to the first [#max].
    private static final class Sampler implements AutoCloseable {
        private final AtomicInteger max = new AtomicInteger();
        private final AtomicInteger samples = new AtomicInteger();
        private final AtomicBoolean stop = new AtomicBoolean();
        private final Thread thread;

        Sampler(Supplier<Integer> value) {
            thread = Thread.ofVirtual().start(() -> {
                while (!stop.get()) {
                    max.accumulateAndGet(value.get(), Math::max);
                    samples.incrementAndGet();
                }
            });
        }

        int max() throws InterruptedException {
            close();
            assertThat(samples.get()).isGreaterThan(20);

            return max.get();
        }

        @Override
        public void close() throws InterruptedException {
            stop.set(true);
            thread.join();
        }
    }

    /// The order in which the builds of two real bb-mutate runs start on a host with one slot: `a` (3 builds), submitted first, and `b` (2 builds),
    /// submitted while a's control runs. Every build is held until released; after a release the load gate holds every grant until each run that has
    /// builds left has its next ticket waiting, so each grant chooses between the two.
    private List<String> startOrder(String ownerA, String ownerB) throws Exception {
        var builds = Map.of(ownerA + "-a", 3, ownerB + "-b", 2);

        Files.writeString(dir.resolve("step"), "");
        var a = mutate(tools.resolve("bb-mutate"), ownerA, ownerA + "-a", "{\"pl\":\"A\"}", 2);

        assertThat(within(120, () -> runs().size() == 1)).as(mutateLog(ownerA + "-a")).isTrue();
        var b = mutate(tools.resolve("bb-mutate"), ownerB, ownerB + "-b", "{\"pl\":\"A\"}", 1);

        assertThat(within(120, () -> ids("waiting").size() == 1)).as(mutateLog(ownerB + "-b")).isTrue();
        assertThat(runs()).as("builds started when b's first ticket is queued").hasSize(1);
        for (var k = 1; k <= 5; k++) {
            var started = k;

            assertThat(within(60, () -> runs().size() == started)).as(k + ": " + runs()).isTrue();
            Files.writeString(dir.resolve("loadavg"), "9999.00 9999.00 9999.00 1/1 1\n");
            Files.writeString(dir.resolve("step." + k), "");
            var left = builds.entrySet()
                             .stream()
                             .filter(run -> runs().stream()
                                                .filter(run.getKey()::equals)
                                                .count() < run.getValue())
                             .count();

            assertThat(within(60,
                              () -> ids("running").isEmpty() && ids("waiting").size() == left)).as(k + ": " + ids("waiting"))
                      .isTrue();
            Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        }

        assertThat(a.waitFor(60, TimeUnit.SECONDS) && b.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(List.of(a.exitValue(), b.exitValue())).as(mutateLog(ownerA + "-a") + mutateLog(ownerB + "-b")).containsOnly(0);
        assertThat(outcomes(ownerA + "-a")).containsExactly("m1 RED", "m2 RED");
        assertThat(outcomes(ownerB + "-b")).containsExactly("m1 RED");

        return runs().stream()
                   .map(run -> run.substring(run.length() - 1))
                   .toList();
    }

    /// Two batches of one owner alternate build by build, so the newer one's first build starts at most one build after its submit.
    @Test
    void twoBatchesOfOneOwner_alternateBuildByBuild() throws Exception {
        assertThat(startOrder("mo", "mo")).containsExactly("a", "b", "a", "b", "a");
    }

    /// The control of the test above: the same two batches of two owners are placed by order_at=, so the older one's builds all go first.
    @Test
    void twoBatchesOfTwoOwners_runInSubmitOrder() throws Exception {
        assertThat(startOrder("mo", "mp")).containsExactly("a", "a", "a", "b", "b");
    }

    private void oneRunsOneWaits_whileAnOrdinaryTicketBehindRuns() throws Exception {
        assertThat(within(120,
                          () -> count("running", "mo", "^via=bb-mutate:") == 1 && count("waiting", "mo", "^via=bb-mutate:") == 1)).as(mutateLog("mo-a") + mutateLog("mo-b"))
                  .isTrue();
        plain("mo", "mo-ord-1");
        assertThat(within(60, () -> runs().contains("mo-ord-1"))).as("the ordinary ticket behind the waiting one runs").isTrue();
        assertThat(count("running", "mo", "^via=bb-mutate:")).isEqualTo(1);
        assertThat(count("waiting", "mo", "^via=bb-mutate:")).isEqualTo(1);
    }

    /// Two real runs of one owner on a host with room for both: never two running mutant tickets, at most one waiting per live driver, and both finish
    /// with every mutant RED.
    @Test
    void twoRunsOfOneOwner_neverRunTwoMutantTickets_andBothFinish() throws Exception {
        Files.writeString(dir.resolve("max-reactors"), "9\n");
        Files.writeString(dir.resolve("hold"), "");
        try (var running = new Sampler(() -> count("running", "mo", "^via=bb-mutate:"));
             var waiting = new Sampler(() -> count("waiting", "mo", "^via=bb-mutate:"))) {
            var a = mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\"}", 2);
            var b = mutate(tools.resolve("bb-mutate"), "mo", "mo-b", "{\"pl\":\"A\"}", 1);

            oneRunsOneWaits_whileAnOrdinaryTicketBehindRuns();
            Files.delete(dir.resolve("hold"));
            assertThat(a.waitFor(300, TimeUnit.SECONDS) && b.waitFor(300, TimeUnit.SECONDS)).isTrue();
            assertThat(List.of(a.exitValue(), b.exitValue())).as(mutateLog("mo-a") + mutateLog("mo-b")).containsOnly(0);
            assertThat(running.max()).isEqualTo(1);
            assertThat(waiting.max()).isBetween(1, 2);
        }

        assertThat(outcomes("mo-a")).containsExactly("m1 RED", "m2 RED");
        assertThat(outcomes("mo-b")).containsExactly("m1 RED");
    }

    /// Two runs of the bb-mutate before #116 (no BB_ORDER_AT) take turns too: their tickets are mutant tickets.
    @Test
    void twoRunsOfTheBbMutateBefore116_takeTurnsToo() throws Exception {
        var old = BbFixtures.executable(dir.resolve("drv/old-bb-mutate"), resource("bb-mutate.pre116.prev"));

        Files.writeString(dir.resolve("max-reactors"), "9\n");
        Files.writeString(dir.resolve("hold"), "");
        mutate(old, "mo", "mo-a", "{\"pl\":\"A\"}", 1);
        mutate(old, "mo", "mo-b", "{\"pl\":\"A\"}", 1);
        oneRunsOneWaits_whileAnOrdinaryTicketBehindRuns();
    }

    /// The control of the two tests above: with the three tools before #116 the two runs do run two mutant tickets at once, so the count can be two.
    @Test
    void twoRunsWithTheToolsBefore116_runTwoMutantTickets() throws Exception {
        for (var tool : List.of("bb-build", "bb-mutate")) {
            BbFixtures.executable(tools.resolve(tool), resource(tool + ".pre116.prev"));
        }

        Files.writeString(tools.resolve("bb-queue-lib.sh"), resource("bb-queue-lib.sh.pre116.prev"));
        Files.writeString(dir.resolve("max-reactors"), "9\n");
        Files.writeString(dir.resolve("hold"), "");
        mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\"}", 1);
        mutate(tools.resolve("bb-mutate"), "mo", "mo-b", "{\"pl\":\"A\"}", 1);
        assertThat(within(120,
                          () -> count("running", "mo", "^via=bb-mutate:") == 2)).as(mutateLog("mo-a") + mutateLog("mo-b"))
                  .isTrue();
    }

    private List<String> outcomes(String name) throws Exception {
        return Files.readAllLines(dir.resolve("out-" + name).resolve("results.tsv"))
                    .stream()
                    .skip(1)
                    .map(line -> line.split("\t") [0] + " " + line.split("\t") [1])
                    .toList();
    }

    private static String resource(String name) throws Exception {
        try (var in = BbMutateOrderIT.class.getResourceAsStream("/bb/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /// A mutant ticket that waits for its turn longer than its own bound is not timed out by it: the bound starts at its own start. The other ticket
    /// of the owner is planted between the control and the first mutant; the bound is 20 s, and the planted one ends once the mutant has waited 30 s.
    @Test
    void theWaitForTheTurn_doesNotCountAgainstTheMutantsBound() throws Exception {
        Files.writeString(dir.resolve("hold"), "");
        var run = mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\",\"mutantTimeoutSecs\":20}", 1);

        assertThat(within(120, () -> runs().size() == 1)).as(mutateLog("mo-a")).isTrue();
        var other = start(List.of("bash", "-c", "exec -a 'bb-build __run 1-planted' sleep 600"),
                          env("mo"),
                          dir.resolve("planted.log"));

        ticket(queue, "running", "1-planted", "mo", true, 1, "cap=3\n");
        Files.delete(dir.resolve("hold"));
        assertThat(within(120, () -> ids("waiting").size() == 1)).as(mutateLog("mo-a")).isTrue();
        var mutant = ids("waiting").getFirst();

        assertThat(field("waiting", mutant, "bound")).isEqualTo("20");
        assertThat(within(60,
                          () -> now() - Long.parseLong(field("waiting", mutant, "created")) > 30 * SECOND)).isTrue();
        assertThat(run.isAlive()).as(mutateLog("mo-a")).isTrue();
        assertThat(ids("running")).containsExactly("1-planted");
        assertThat(ids("waiting")).containsExactly(mutant);
        other.destroyForcibly().waitFor();
        assertThat(run.waitFor(180, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(mutateLog("mo-a")).isZero();
        assertThat(outcomes("mo-a")).containsExactly("m1 RED");
    }

    /// A control that never started within --timeout ends the run with exit 4 (the queue wait's code, as for a mutant), its ticket gone and nothing run.
    @Test
    void aControlThatNeverStarted_exits4() throws Exception {
        blocker();
        var run = mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\"}", 1, "--timeout", "0.2");

        assertThat(run.waitFor(180, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(mutateLog("mo-a")).isEqualTo(4);
        assertThat(mutateLog("mo-a") + Files.readString(dir.resolve("mo-a.driver.log"))).contains("control ABORT");
        assertThat(ids("waiting")).isEmpty();
        assertThat(runs()).containsExactly("blk-1");
    }

    /// A control that started and overran --timeout is a failed control: exit 2, its build stopped.
    @Test
    void aControlThatOverran_exits2() throws Exception {
        Files.writeString(dir.resolve("hold.mo-a"), "");
        var run = mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\"}", 1, "--timeout", "0.4");

        assertThat(run.waitFor(180, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(mutateLog("mo-a")).isEqualTo(2);
        assertThat(mutateLog("mo-a") + Files.readString(dir.resolve("mo-a.driver.log"))).contains("control TIMEOUT");
        assertThat(within(60, () -> inCheckout("mo-a").isEmpty())).isTrue();
        assertThat(runs()).containsExactly("mo-a");
    }

    // ---------------------------------------------------------------- proof 2 and 3
    /// Proof 2: a mutant ticket enqueued AFTER an ordinary ticket runs before it when its batch was submitted before that ticket. Control, in the
    /// same queue: a mutant ticket whose batch was submitted after the ordinary ticket runs after it. The waiting order and the real grants agree.
    @Test
    void aMutantTicket_sortsByItsBatchSubmitTime_aheadOfALaterTicket() throws Exception {
        blocker();
        var submitted = submit("mo", "mo-1");
        var ordinary = plain("ord", "ord-1");
        var late = drive("mp", "mp-1").ticket();
        var early = enqueue("mo-1", submitted).ticket();

        assertThat(lib(tools.resolve("bb-queue-lib.sh"), queue, "order")).isEqualTo(String.join("\n",
                                                                                                early,
                                                                                                ordinary,
                                                                                                late));
        assertThat(field("waiting", early, "order_at")).isEqualTo(Long.toString(orderAt("mo-1")));
        release("blk-1");
        assertThat(within(120, () -> runs().size() == 4)).isTrue();
        assertThat(runs()).containsExactly("blk-1", "mo-1", "ord-1", "mp-1");
    }

    /// Proof 3: a ticket of a higher priority goes before a mutant ticket whatever the mutant ticket's order_at. Control: the ordinary ticket of
    /// priority 0, enqueued before both, goes last, so the order_at was in force.
    @Test
    void aHigherPriorityTicket_goesBeforeAnEarlierOrderAt() throws Exception {
        blocker();
        var submitted = submit("mo", "mo-1");
        var ordinary = plain("ord", "ord-1");
        var mutant = enqueue("mo-1", submitted).ticket();
        var vip = plain("vip", "vip-1");

        assertThat(lib(tools.resolve("bb-queue-lib.sh"), queue, "order")).isEqualTo(String.join("\n",
                                                                                                vip,
                                                                                                mutant,
                                                                                                ordinary));
        release("blk-1");
        assertThat(within(120, () -> runs().size() == 4)).isTrue();
        assertThat(runs()).containsExactly("blk-1", "vip-1", "mo-1", "ord-1");
    }

    // ---------------------------------------------------------------- proof 4
    private void abandoned(String id, String checkout) throws Exception {
        assertThat(within(60, () -> in("done", id))).as("ticket dropped: " + ids("waiting")).isTrue();
        assertThat(field("done", id, "state")).isEqualTo("abandoned");
        assertThat(Files.readString(dir.resolve("work/co").resolve(checkout).resolve(".bb/rc")).strip()).isEqualTo("9");
        assertThat(Files.readString(dir.resolve("work/co").resolve(checkout).resolve(".bb/build.log"))).contains("driver process gone");
        assertThat(within(60,
                          () -> unchecked(() -> BbFixtures.waiters(id)).isEmpty())).isTrue();
        assertThat(runs()).doesNotContain(checkout);
    }

    private static void sigkill(long pid) {
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::destroyForcibly)).contains(true);
    }

    /// Proof 4: kill -9 of a driver drops its WAITING ticket (done/, state=abandoned, .bb/rc 9, its waiter exits, nothing ran). Controls: a second
    /// waiting ticket whose driver lives is still waiting afterwards, and a RUNNING ticket whose driver was killed keeps running and finishes 0.
    @Test
    void aKilledDriver_leavesNoWaitingTicket_andItsRunningBuildFinishes() throws Exception {
        Files.writeString(dir.resolve("hold.mr-1"), "");
        var running = drive("mr", "mr-1");

        assertThat(within(60, () -> runs().contains("mr-1"))).isTrue();
        var killed = drive("mo", "mo-1");
        var alive = drive("mp", "mp-1");

        assertThat(ids("waiting")).containsExactlyInAnyOrder(killed.ticket(), alive.ticket());
        sigkill(running.pid());
        sigkill(killed.pid());
        abandoned(killed.ticket(), "mo-1");
        var later = drive("mq", "mq-1");

        sigkill(later.pid());
        abandoned(later.ticket(), "mq-1");  // a ticket enqueued and dropped after it: the others were tested for a gone driver meanwhile
        assertThat(ids("waiting")).containsExactly(alive.ticket());
        assertThat(ids("running")).containsExactly(running.ticket());
        release("mr-1");
        assertThat(within(60, () -> in("done", running.ticket()))).isTrue();
        assertThat(field("done", running.ticket(), "state")).isEqualTo("finished");
        assertThat(field("done", running.ticket(), "rc")).isEqualTo("0");
        assertThat(within(60, () -> runs().contains("mp-1"))).as("the living driver's ticket is granted").isTrue();
    }

    /// Proof 4, a driver that is a zombie (killed, its parent never reaps it): its pid and start time still exist, and it is gone all the same.
    @Test
    void aZombieDriver_leavesNoWaitingTicket() throws Exception {
        blocker();
        var call = dir.resolve("mo-1.call");

        start(List.of("bash",
                      "-c",
                      "python3 \"$@\" & exec sleep 600",
                      dir.toString(),
                      driver.toString(),
                      checkout("mo-1").toString(),
                      call.toString()),
              env("mo"),
              dir.resolve("mo-1.driver.log"));
        assertThat(within(60, () -> Files.exists(call))).isTrue();
        var id = ids("waiting").getFirst();
        var pid = field("waiting", id, "driver_pid");

        sigkill(Long.parseLong(pid));
        assertThat(within(10,
                          () -> unchecked(() -> Files.readString(Path.of("/proc", pid, "stat"))).contains(") Z "))).isTrue();
        abandoned(id, "mo-1");
    }

    /// Proof 4, a recycled pid: the driver's pid runs, with another start time than the ticket's (BB_TEST_RESTART_PID reads it as reused).
    @Test
    void aRecycledDriverPid_leavesNoWaitingTicket() throws Exception {
        blocker();
        abandoned(drive("mo", "mo-1", "BB_TEST_RESTART_PID=SELF").ticket(), "mo-1");
    }

    // ---------------------------------------------------------------- the bound
    /// The processes whose cwd is the checkout.
    private List<Long> inCheckout(String checkout) {
        var co = dir.resolve("work/co").resolve(checkout);

        return ProcessHandle.allProcesses()
                            .map(ProcessHandle::pid)
                            .filter(pid -> {
                                try {
                                    return Files.readSymbolicLink(Path.of("/proc", pid.toString(), "cwd")).equals(co);
                                } catch (java.io.IOException e) {
                                    return false;
                                }
                            })
                            .sorted()
                            .toList();
    }

    private void endedAtItsBound(String id, String checkout, int bound) throws Exception {
        assertThat(within(120, () -> in("done", id))).as("ended at its bound").isTrue();
        assertThat(field("done", id, "state")).isEqualTo("finished");
        assertThat(field("done", id, "rc")).isEqualTo("124");
        assertThat(field("done", id, "job_end")).isEmpty();
        assertThat(Files.readString(dir.resolve("work/co").resolve(checkout).resolve(".bb/rc")).strip()).isEqualTo("124");
        assertThat(Files.readString(dir.resolve("work/co").resolve(checkout).resolve(".bb/build.log"))).contains("bound of " + bound + "s reached");
        hitAtItsBound(id, bound);
    }

    /// Both fields are whole seconds rounded down, so only a difference of bound + 1 shows that the whole bound had passed.
    /// Mutation: the deadline NOW + BOUND (it stops a job up to 1 s before its bound) -> red.
    private void hitAtItsBound(String id, int bound) {
        assertThat(Long.parseLong(field("done", id, "bound_hit")) - Long.parseLong(field("done", id, "started"))).isBetween(bound + 1L,
                                                                                                                           bound + 60L);
    }

    /// A RUNNING build ends at the bound its ticket carries, and not before, when its driver was killed (-9) and when it lives, with the same verdict. A
    /// process that only stands in the checkout is not touched. Control: a build with a bound of 600 s whose driver was killed at the same time runs
    /// on, and finishes 0 with no bound_hit=.
    @Test
    void aKilledDriversRunningBuild_endsAtItsBound_andNotBefore() throws Exception {
        Files.writeString(dir.resolve("max-reactors"), "9\n");
        Files.writeString(dir.resolve("hold"), "");
        Files.writeString(dir.resolve("hold.mp-1"), "");
        var killed = drive("mo", "mo-1", "BB_MUTANT_BOUND=20");
        var unbounded = drive("mp", "mp-1");
        var living = drive("mq", "mq-1", "BB_MUTANT_BOUND=20");

        assertThat(within(60, () -> runs().size() == 3)).as(runs().toString()).isTrue();
        sigkill(killed.pid());
        sigkill(unbounded.pid());
        var stands = start(List.of("bash", "-c", "cd \"$1\" && exec sleep 600", "bash", dir.resolve("work/co/mo-1").toString()),
                           env("mo"),
                           dir.resolve("stands.log"));

        endedAtItsBound(killed.ticket(), "mo-1", 20);
        endedAtItsBound(living.ticket(), "mq-1", 20);
        assertThat(inCheckout("mo-1")).as("only what stands in the checkout is left").containsExactly(stands.pid());
        assertThat(inCheckout("mq-1")).isEmpty();
        assertThat(ProcessHandle.of(living.pid())).as("the living driver").isPresent();
        assertThat(ids("running")).containsExactly(unbounded.ticket());
        Files.delete(dir.resolve("hold"));
        release("mp-1");
        assertThat(within(60, () -> in("done", unbounded.ticket()))).isTrue();
        assertThat(field("done", unbounded.ticket(), "rc")).isEqualTo("0");
        assertThat(field("done", unbounded.ticket(), "bound_hit")).isEmpty();
    }

    /// The bound holds without the waiter too: driver and waiter killed (-9), the build runs on, ends at its bound with .bb/rc 124, and the ticket is
    /// orphaned by the next reap. Control: after the two kills the build is still running.
    @Test
    void aRunningBuildWithoutDriverAndWaiter_endsAtItsBound() throws Exception {
        Files.writeString(dir.resolve("hold.mo-1"), "");
        var call = drive("mo", "mo-1", "BB_MUTANT_BOUND=20");
        var id = call.ticket();

        assertThat(within(60, () -> runs().contains("mo-1"))).isTrue();
        var waiters = BbFixtures.waiters(id);
        var waiter = waiters.stream()
                            .filter(pid -> ProcessHandle.of(pid)
                                                        .flatMap(ProcessHandle::parent)
                                                        .filter(parent -> waiters.contains(parent.pid()))
                                                        .isEmpty())
                            .toList();

        assertThat(waiter).as("the waiter of " + waiters).hasSize(1);
        sigkill(call.pid());
        sigkill(waiter.getFirst());
        assertThat(in("running", id)).isTrue();
        assertThat(inCheckout("mo-1")).as("the build runs on").isNotEmpty();
        assertThat(dir.resolve("work/co/mo-1/.bb/rc")).doesNotExist();
        assertThat(within(120, () -> Files.exists(dir.resolve("work/co/mo-1/.bb/rc")))).isTrue();
        assertThat(Files.readString(dir.resolve("work/co/mo-1/.bb/rc")).strip()).isEqualTo("124");
        assertThat(inCheckout("mo-1")).isEmpty();
        assertThat(within(60,
                          () -> unchecked(() -> BbFixtures.waiters(id)).isEmpty())).isTrue();
        lib(tools.resolve("bb-queue-lib.sh"), queue, "exec 8> \"$Q/.lock\" && flock 8 && reap");
        assertThat(field("done", id, "state")).isEqualTo("orphaned");
        hitAtItsBound(id, 20);
    }

    /// A job that returned by itself before its bound is not hit, though what bb-build does after it (bb-cp-record) is still running at the bound: the
    /// ticket has no bound_hit= and its rc is the job's. Control: the bound runner lived at the job's end and went at the bound.
    /// Mutation: drop the job_end test from the bound runner -> bound_hit= and rc 124, red.
    @Test
    void aJobThatEndedBeforeItsBound_isNotHit_thoughItsTicketStillRuns() throws Exception {
        BbFixtures.executable(tools.resolve("bb-cp-record"),
                              "#!/bin/bash\nwhile [ -e \"$HOLD_DIR/hold.cp\" ]; do sleep 0.2; done\n");
        Files.writeString(dir.resolve("hold.cp"), "");
        Files.writeString(dir.resolve("hold.mo-1"), "");
        var id = drive("mo", "mo-1", "BB_MUTANT_BOUND=15").ticket();

        assertThat(within(60, () -> runs().contains("mo-1") && boundRunners(id) > 0)).as("job and bound runner run").isTrue();
        release("mo-1");
        assertThat(within(60, () -> !field("running", id, "job_end").isEmpty())).isTrue();
        assertThat(boundRunners(id)).as("the bound runner at the job's end").isPositive();
        assertThat(within(60, () -> boundRunners(id) == 0)).as("the bound runner went at the bound").isTrue();
        assertThat(now() / SECOND - Long.parseLong(field("running", id, "started"))).as("past the bound").isGreaterThanOrEqualTo(16);
        assertThat(in("running", id)).isTrue();
        Files.delete(dir.resolve("hold.cp"));
        assertThat(within(60, () -> in("done", id))).isTrue();
        assertThat(field("done", id, "rc")).isEqualTo("0");
        assertThat(field("done", id, "bound_hit")).isEmpty();
        assertThat(Long.parseLong(field("done", id, "job_end")) - Long.parseLong(field("done", id, "started"))).isLessThan(15);
        assertThat(Files.readString(dir.resolve("work/co/mo-1/.bb/rc")).strip()).isEqualTo("0");
    }

    private static int boundRunners(String id) {
        return (int) ProcessHandle.allProcesses()
                                  .filter(process -> process.info()
                                                            .commandLine()
                                                            .filter(line -> line.contains(" __bound " + id + " "))
                                                            .isPresent())
                                  .count();
    }

    /// A mutant that hangs past its bound is TIMEOUT in the results of a live bb-mutate, and the run goes on to the next mutant.
    @Test
    void aHungMutantOfALiveRun_isTimeout_andTheRunGoesOn() throws Exception {
        Files.writeString(dir.resolve("hold.mut1"), "");
        var run = mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\",\"mutantTimeoutSecs\":20}", 2);

        assertThat(run.waitFor(240, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(mutateLog("mo-a")).isZero();
        assertThat(outcomes("mo-a")).containsExactly("m1 TIMEOUT", "m2 RED");
        assertThat(Files.readString(dir.resolve("out-mo-a/results.tsv"))).contains("exceeded 20s from its own Maven start; job killed (counted detected)");
        assertThat(inCheckout("mo-a")).isEmpty();
    }

    // ---------------------------------------------------------------- the order_at window and the bb-mutate-only rule
    /// The call's whole answer to a refusal: rc 2 and one line, so no shell message ("integer expression expected", "value too great") beside it.
    private static void refusedInOneLine(String row, Call call, String reason) {
        assertThat(call.rc()).as(row + ": " + call.out()).isEqualTo(2);
        assertThat(call.out().lines().skip(1).filter(line -> !line.isBlank()).toList()).as(row)
                  .singleElement()
                  .asString()
                  .startsWith("refusing: BB_ORDER_AT")
                  .contains(reason);
    }

    /// order_at is accepted from the driver's start to now: 1 ns before its start, a minute ahead and an hour ahead are refused with rc 2 and nothing is
    /// queued. Control: the driver's start itself is accepted, and so is the time the driver reads for itself (the default of every other row here).
    @Test
    void anOrderAtOutsideTheDriversLifetime_isRefused_andNothingIsQueued() throws Exception {
        refusedInOneLine("before", drive("mo", "mo-0", "BB_ORDER_AT=START-1"), "is before the start of its driver");
        for (var ahead : List.of(60, 3600)) {
            refusedInOneLine("ahead " + ahead,
                             drive("mo", "mo-" + ahead, "BB_ORDER_AT=" + (now() + ahead * SECOND)),
                             "is in the future");
        }

        nothingQueued();
        var accepted = drive("mo", "mo-1", "BB_ORDER_AT=START");

        assertThat(accepted.rc()).as(accepted.out()).isZero();
    }

    /// A malformed order_at is refused with rc 2, one line and nothing queued: empty, seconds, a letter, a leading zero, a newline with a second field,
    /// 20 digits, and above 2^63 (19 digits, so in the future). So is a missing or malformed bound.
    @Test
    void aMalformedOrderAtOrBound_isRefusedInOneLine_andNothingIsQueued() throws Exception {
        var row = 0;

        for (var value : List.of("",
                                 "1790000000",
                                 "179000000000000000x",
                                 "0790000000000000000",
                                 "1790000000000000000\npriority=9",
                                 "17900000000000000000")) {
            refusedInOneLine(value, drive("mo", "mo-" + row++, "BB_ORDER_AT=" + value), "must be epoch nanoseconds");
        }

        refusedInOneLine("2^63", drive("mo", "mo-" + row++, "BB_ORDER_AT=9999999999999999999"), "is in the future");
        for (var bound : List.of("BB_MUTANT_BOUND",
                                 "BB_MUTANT_BOUND=",
                                 "BB_MUTANT_BOUND=0",
                                 "BB_MUTANT_BOUND=060",
                                 "BB_MUTANT_BOUND=1.5",
                                 "BB_MUTANT_BOUND=60\npriority=9",
                                 "BB_MUTANT_BOUND=1000000",
                                 "BB_MUTANT_BOUND=99999999999999999999")) {
            refusedInOneLine(bound, drive("mo", "mo-" + row++, bound), "needs BB_MUTANT_BOUND");
        }

        for (var number : List.of("BB_DRIVER_PID=9999999999", "BB_DRIVER_START=9999999999999999999", "BB_DRIVER_START=99999999999999999999")) {
            refusedInOneLine(number, drive("mo", "mo-" + row++, number), "accepted only from bb-mutate");
        }

        nothingQueued();
        assertThat(drive("mo", "mo-ok").rc()).isZero();
    }

    /// order_at is accepted only from bb-mutate: a call with no driver, with a driver of another start time, with ANOTHER running bb-mutate as its
    /// driver, from a script of another name, or without a bb-mutate marker is refused with rc 2 and nothing is queued. Control: the script that
    /// is refused under another name is accepted under the name bb-mutate.
    @Test
    void aCallWhoseDriverIsMissingOrForeign_isRefused_andNothingIsQueued() throws Exception {
        var stranger = drive("mo", "mo-0", "BB_ORDER_AT=0");  // refused, and alive: a running process named bb-mutate
        var strangerStart = BbFixtures.pstart(stranger.pid());
        var unnamed = Files.copy(driver, dir.resolve("drv/not-mutate"), StandardCopyOption.COPY_ATTRIBUTES);
        var rows = new HashMap<String, Call>();

        assertThat(strangerStart).matches("[0-9]+");
        rows.put("no driver pid", drive("mo", "mo-1", "BB_DRIVER_PID"));
        rows.put("no driver start", drive("mo", "mo-2", "BB_DRIVER_START"));
        rows.put("another start time", drive("mo", "mo-3", "BB_DRIVER_START=1"));
        rows.put("a foreign driver",
                 drive("mo", "mo-4", "BB_DRIVER_PID=" + stranger.pid(), "BB_DRIVER_START=" + strangerStart));
        rows.put("another name", drive(unnamed, "mo", "mo-5"));
        rows.put("no marker", drive("mo", "mo-6", "BB_VIA"));
        rows.put("another marker", drive("mo", "mo-7", "BB_VIA=bb-test:spec:m1"));
        rows.put("no bb-mutate at all",
                 plain("mo",
                       "mo-8",
                       Map.of("BB_ORDER_AT", Long.toString(now() - SECOND), "BB_VIA", "bb-mutate:spec:m1", "BB_MUTANT_BOUND", "60")));
        assertThat(rows.entrySet()).allSatisfy(row -> {
            assertThat(row.getValue().rc()).as(row.getKey() + ": " + row.getValue().out())
                      .isEqualTo(2);
            assertThat(row.getValue().out()).as(row.getKey())
                      .contains("accepted only from bb-mutate");
        });
        nothingQueued();
        assertThat(drive("mo", "mo-9").rc()).isZero();
    }

    /// order_at and the driver variables stop at the ticket: neither the waiter nor the build inherits them, nor does a build of a call that
    /// carries only stray BB_DRIVER_* and BB_MUTANT_BOUND variables. Control: BB_OWNER and BB_VIA, passed the same way, do reach the build.
    @Test
    void theOrderAndDriverVariables_reachNeitherTheWaiterNorTheBuild() throws Exception {
        Files.writeString(dir.resolve("hold.mo-1"), "");
        var id = drive("mo", "mo-1").ticket();

        assertThat(within(60, () -> runs().contains("mo-1"))).isTrue();
        var waiter = BbFixtures.waiters(id).getFirst();
        var waiterEnv = List.of(Files.readString(Path.of("/proc", waiter.toString(), "environ")).split("\0"));

        assertThat(waiterEnv).contains("BB_OWNER=mo", "BB_VIA=bb-mutate:spec:m1");
        assertThat(waiterEnv).noneMatch(v -> v.startsWith("BB_ORDER_AT") || v.startsWith("BB_DRIVER_") || v.startsWith("BB_MUTANT_"));
        assertThat(Files.readAllLines(dir.resolve("env.mo-1"))).containsExactlyInAnyOrder("BB_OWNER=mo",
                                                                                          "BB_VIA=bb-mutate:spec:m1");
        release("mo-1");
        plain("ord", "ord-1", Map.of("BB_DRIVER_PID", "1", "BB_DRIVER_START", "1", "BB_MUTANT_BOUND", "1")).ticket();
        assertThat(within(60, () -> runs().contains("ord-1"))).isTrue();
        assertThat(Files.readAllLines(dir.resolve("env.ord-1"))).containsExactly("BB_OWNER=ord");
    }

    /// The install order is lib, bb-build, bb-mutate and the rollback order its reverse, so a host only ever holds these mixes; in none of them does a
    /// build inherit a mutant variable. Control: the mix the order rules out (a bb-mutate that passes the variables to a bb-build that does not
    /// take them) does leak.
    @Test
    void noMixOfTheInstallOrRollbackOrder_leaksAMutantVariableIntoTheBuild() throws Exception {
        assertThat(leaked("mo-0", false, false, false)).isEmpty();
        assertThat(leaked("mo-1", true, false, false)).isEmpty();
        assertThat(leaked("mo-2", true, true, false)).isEmpty();
        assertThat(leaked("mo-3", true, true, true)).isEmpty();
        assertThat(leaked("mo-4", true, false, true)).contains("BB_ORDER_AT", "BB_DRIVER_PID", "BB_DRIVER_START", "BB_MUTANT_BOUND");
    }

    /// The mutant variables in the environment of the last build of a whole run with each tool new or as before #116.
    private List<String> leaked(String name, boolean lib, boolean build, boolean mutate) throws Exception {
        for (var tool : Map.of("bb-queue-lib.sh", lib, "bb-build", build, "bb-mutate", mutate).entrySet()) {
            BbFixtures.executable(tools.resolve(tool.getKey()),
                                  tool.getValue()
                                  ? Files.readString(BbFixtures.OPS_BB.resolve(tool.getKey()))
                                  : resource(tool.getKey() + ".pre116.prev"));
        }

        var run = mutate(tools.resolve("bb-mutate"), "mo", name, "{\"pl\":\"A\"}", 1);

        assertThat(run.waitFor(180, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(mutateLog(name)).isZero();
        assertThat(outcomes(name)).containsExactly("m1 RED");
        assertThat(within(60, () -> ids("running").isEmpty())).isTrue();

        return Files.readAllLines(dir.resolve("env." + name))
                    .stream()
                    .map(line -> line.split("=") [0])
                    .filter(v -> v.matches("BB_(ORDER_AT|DRIVER_.*|MUTANT_.*)"))
                    .toList();
    }

    // ---------------------------------------------------------------- proof 6, created= and proof 7
    /// created= of a mutant ticket is its true enqueue time, whatever its order_at.
    @Test
    void createdOfAMutantTicket_isItsTrueEnqueueTime() throws Exception {
        blocker();
        var submitted = submit("mo", "mo-1");
        var before = now();
        var id = enqueue("mo-1", submitted).ticket();
        var after = now();

        assertThat(orderAt("mo-1")).isLessThan(before);

        assertThat(Long.parseLong(field("waiting", id, "created"))).isBetween(before, after);
        assertThat(field("waiting", id, "order_at")).isEqualTo(Long.toString(orderAt("mo-1")));
        assertThat(Long.parseLong(lib(tools.resolve("bb-queue-lib.sh"), queue, "created_s \"$Q/waiting/" + id + "\""))).isBetween(before / SECOND,
                                                                                                                                  after / SECOND);
    }

    private Path goldenQueue() throws Exception {
        var root = Files.createDirectories(dir.resolve("golden"));

        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(root.resolve(d));
        }

        return root;
    }

    /// Proof 6: under a held load gate, the starvation override reads created= alone. A mutant ticket enqueued now with an order_at two hours old is
    /// NOT granted; control: the same ticket with a created= two hours old is.
    @Test
    void orderAt_neverReachesTheLoadStarvationOverride() throws Exception {
        blocker();
        var id = drive("mo", "mo-1").ticket();
        var real = Files.readString(queue.resolve("waiting").resolve(id)).replaceAll("(?m)^order_at=.*$",
                                                                                     "order_at=" + (now() - 7200 * SECOND));

        assertThat(real).contains("\norder_at=");
        var root = goldenQueue();
        var ticket = root.resolve("waiting").resolve(id);

        Files.writeString(dir.resolve("loadavg"), "9999.00 9999.00 9999.00 1/1 1\n");
        Files.writeString(ticket, real.replaceAll("(?m)^created=.*$", "created=" + now()));
        assertThat(lib(tools.resolve("bb-queue-lib.sh"), root, "pick 0 none")).isEmpty();
        Files.writeString(ticket, real.replaceAll("(?m)^created=.*$", "created=" + (now() - 7200 * SECOND)));
        assertThat(lib(tools.resolve("bb-queue-lib.sh"), root, "pick 0 none")).isEqualTo(id);
    }

    /// Proof 7 (golden): tickets without order_at, in the format bb-build writes today, sort exactly as order() before #116 sorted them: by priority (an
    /// owner's, a sha's), then created=, equal and unreadable created= included. The ticket text is a real ticket of this bb-build. Control: one
    /// order_at in the same queue changes the new order and not the old one; a non-numeric order_at changes neither.
    @Test
    void ticketsWithoutOrderAt_sortExactlyAsBefore116() throws Exception {
        blocker();
        var real = Files.readString(queue.resolve("waiting").resolve(plain("ord", "ord-1")));
        var root = goldenQueue();
        var old = Files.writeString(dir.resolve("old-lib.sh"), resource("bb-queue-lib.sh.pre116.prev"));
        var lib = tools.resolve("bb-queue-lib.sh");
        var waiting = root.resolve("waiting");

        assertThat(real).contains("\ncreated=", "\ngroup=", "\nargs=").doesNotContain("order_at=", "driver_pid=");
        Files.writeString(dir.resolve("priorities"), "vip 5\nabcdef012 3\nlow -2\n");
        var rows = List.of(new String[]{"1790000000000000009-1", "ord", "", "1790000000000000300"},
                           new String[]{"1790000000000000008-1", "ord", "", "1790000000000000200"},
                           new String[]{"1790000000000000007-2", "ord", "", "1790000000000000200"},

        // equal created=
        new String[]{"1790000000000000007-1", "ord", "", "1790000000000000200"},
                           new String[]{"1790000000000000006-1", "ord", "", "garbled"},
                           new String[]{"1790000000000000005-1", "ord", "", ""},
                           new String[]{"1790000000000000004-1", "vip", "", "1790000000000000900"},
                           new String[]{"1790000000000000003-1", "ord", "abcdef0123456789abcdef0123456789abcdef01", "1790000000000000901"},
                           new String[]{"1790000000000000002-1", "low", "", "1790000000000000001"},
                           new String[]{"1790000000000000001-1", "ord", "", "99"});

        for (var row : rows) {
            var text = real.replaceAll("(?m)^id=.*$",
                                       "id=" + row[0])
                           .replaceAll("(?m)^owner=.*$",
                                       "owner=" + row[1])
                           .replaceAll("(?m)^created=.*$",
                                       "created=" + row[3]);

            Files.writeString(waiting.resolve(row[0]),
                              row[2].isEmpty()
                              ? text
                              : text.replaceAll("(?m)^sha=.*$", "sha=" + row[2]));
        }

        var none = waiting.resolve("1790000000000000000-9");

        Files.writeString(none, real.replaceAll("(?m)^created=.*\n", ""));
        var before = lib(old, root, "order");

        assertThat(before.lines()).containsExactly("1790000000000000004-1",
                                                   "1790000000000000003-1",
                                                   "1790000000000000000-9",
                                                   "1790000000000000005-1",
                                                   "1790000000000000006-1",
                                                   "1790000000000000001-1",
                                                   "1790000000000000007-1",
                                                   "1790000000000000007-2",
                                                   "1790000000000000008-1",
                                                   "1790000000000000009-1",
                                                   "1790000000000000002-1");
        assertThat(lib(lib, root, "order")).isEqualTo(before);
        var last = waiting.resolve("1790000000000000009-1");
        var text = Files.readString(last);

        Files.writeString(last, text.replace("\nargs=", "\norder_at=17x\nargs="));
        assertThat(lib(lib, root, "order")).isEqualTo(before);
        Files.writeString(last, text.replace("\nargs=", "\norder_at=1790000000000000100\nargs="));
        assertThat(lib(old, root, "order")).isEqualTo(before);
        assertThat(lib(lib, root, "order").lines().toList().indexOf("1790000000000000009-1")).isEqualTo(6);
    }

    // ---------------------------------------------------------------- final round (#117 review N2-N4, items 3 and 5)
    /// An ordinary bb-build call on `co` (an existing checkout), with `more` in its environment.
    private Call plainAt(String owner, Path co, Map<String, String> more) throws Exception {
        var env = env(owner);

        env.putAll(more);
        var run = BbFixtures.run(List.of(tools.resolve("bb-build").toString(), co.toString(), "run"), env, dir, null);

        return new Call(run.rc(),
                        run.stdout() + run.stderr(),
                        0);
    }

    /// A clone of checkout `like` under another name: the same HEAD, the same tracked maven.config, so the same dedup key.
    private Path identical(String name, String like) throws Exception {
        var co = dir.resolve("work/co").resolve(name);
        var run = BbFixtures.run(List.of("git", "clone", "-q", dir.resolve("work/co").resolve(like).toString(), co.toString()),
                                 Map.of("HOME", dir.toString()),
                                 null,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return co;
    }

    private static final Map<String, String> DEDUP_ON = Map.of("BB_DEDUP", "1");

    /// N4: nothing dedups onto a bb-mutate ticket. A plain build identical to a run's WAITING control is queued and runs (not rc 7: it would get no
    /// result when the driver dies, its ticket being dropped); the control's ticket carries an empty key=. Control: two plain identical builds do
    /// dedup (rc 7), so the clone is identical. Mutation: write the ticket's key= for a bb-mutate call (drop the `KEY=""` rule) -> rc 7 here, red.
    @Test
    void aPlainBuildIdenticalToAWaitingControl_isNotDedupedOntoIt_andRuns() throws Exception {
        blocker();
        var control = drive("mo", "mo-1").ticket();

        assertThat(in("waiting", control)).isTrue();
        assertThat(field("waiting", control, "key")).isEmpty();
        var plain = plainAt("ord", identical("ord-1", "mo-1"), DEDUP_ON);

        assertThat(plain.rc()).as(plain.out()).isZero();
        assertThat(plain.out()).doesNotContain("dedup:");
        assertThat(field("waiting", plain.ticket(), "key")).as("a plain ticket keeps its key").hasSize(16);
        var first = plain("ord", "ord-2");
        var second = plainAt("ord", identical("ord-3", "ord-2"), DEDUP_ON);

        assertThat(first).isNotBlank();
        assertThat(second.rc()).as("control: two plain identical builds dedup").isEqualTo(7);
        assertThat(second.out()).contains("dedup: identical build is waiting");
    }

    /// N4, after the control finished: a plain identical build is not rc 8 ("already finished", its result read from the control's checkout) and
    /// runs. Control: a finished PLAIN build does dedup an identical one (rc 8). Mutation: as above.
    @Test
    void aPlainBuildIdenticalToAFinishedControl_isNotDedupedOntoIt_andRuns() throws Exception {
        var control = drive("mo", "mo-1").ticket();

        assertThat(within(60, () -> in("done", control))).isTrue();
        assertThat(field("done", control, "state")).isEqualTo("finished");
        assertThat(field("done", control, "key")).isEmpty();
        var plain = plainAt("ord", identical("ord-1", "mo-1"), DEDUP_ON);

        assertThat(plain.rc()).as(plain.out()).isZero();
        assertThat(plain.out()).doesNotContain("dedup:");
        assertThat(within(60, () -> in("done", plain.ticket()))).isTrue();
        var first = plain("ord", "ord-2");

        assertThat(within(60, () -> in("done", first))).isTrue();
        var second = plainAt("ord", identical("ord-3", "ord-2"), DEDUP_ON);

        assertThat(second.rc()).as("control: a finished plain build dedups an identical one").isEqualTo(8);
    }

    /// Item 3: under BB_QUEUE=0 the legacy script gets none of BB_ORDER_AT, BB_DRIVER_PID, BB_DRIVER_START and BB_MUTANT_BOUND, however they were
    /// passed (the call is not refused for them there: there is no ticket to order); BB_OWNER and BB_VIA, passed the same way, arrive (control).
    /// Mutation: drop the `unset` of the BB_QUEUE=0 branch -> the four reach it, red.
    @Test
    void underBbQueue0_theMutantVariablesNeverReachTheDirectBuild() throws Exception {
        BbFixtures.executable(tools.resolve("bb-build-direct"),
                              "#!/bin/bash\nenv | grep -E '^BB_(ORDER_AT|DRIVER_|MUTANT_|VIA|OWNER)' | sort > \"$HOLD_DIR/direct.env\"\n");
        var call = plain("ord",
                         "ord-q0",
                         Map.of("BB_QUEUE",
                                "0",
                                "BB_ORDER_AT",
                                "1790000000000000000",
                                "BB_DRIVER_PID",
                                "1",
                                "BB_DRIVER_START",
                                "1",
                                "BB_MUTANT_BOUND",
                                "60",
                                "BB_VIA",
                                "bb-mutate:spec:m1"));

        assertThat(call.rc()).as(call.out()).isZero();
        assertThat(Files.readAllLines(dir.resolve("direct.env"))).containsExactly("BB_OWNER=ord", "BB_VIA=bb-mutate:spec:m1");
        nothingQueued();
    }

    /// N3: a clock stepped BACK during a run refuses its later builds, as the header says. The run's order_at is taken, then the clock (a `date`
    /// shim) is 30 s behind it: the call is refused, rc 2, one line, "is in the future", nothing queued. Control: the same call with the clock
    /// back in step is accepted. Mutation: drop the never-in-the-future check -> accepted, red.
    @Test
    void aClockStepBackDuringARun_refusesItsLaterBuilds_untilItIsBack() throws Exception {
        var shim = Files.createDirectories(dir.resolve("shim"));

        BbFixtures.executable(shim.resolve("date"),
                              "#!/bin/bash\nif [ \"$*\" = '+%s%N' ]; then echo $(( $(/usr/bin/date +%s%N) - $(cat \"$HOLD_DIR/clock.back\" 2>/dev/null || echo 0) * 1000000000 ));"
                              + " else exec /usr/bin/date \"$@\"; fi\n");
        var path = "PATH=" + shim + ":" + System.getenv("PATH");
        var stepped = submit("mo", "mo-1", path);
        var inStep = submit("mo", "mo-2", path);

        Files.writeString(dir.resolve("clock.back"), "30");
        refusedInOneLine("clock 30 s back", enqueue("mo-1", stepped), "is in the future");
        nothingQueued();
        Files.delete(dir.resolve("clock.back"));
        var accepted = enqueue("mo-2", inStep);

        assertThat(accepted.rc()).as(accepted.out()).isZero();
    }

    /// N2: a ticket still WAITING when bb-build is rolled back runs without its bound: its waiter starts the bound runner from the bb-build file of
    /// the grant, which is the one before #116 and has no `__bound` mode. After the grant the build runs past its bound of 5 s (and 2 s of
    /// slack), no bound runner stands, and the ticket ends by itself, rc 0, with no bound_hit=. The rollback is by rename, as the installer's.
    /// Mutation: none (this pins a documented limit; it turns red when the limit goes, and the header and installer text must then go too).
    @Test
    void aTicketWaitingAtARollbackOfBbBuild_runsWithoutItsBound() throws Exception {
        blocker();
        Files.writeString(dir.resolve("hold.mo-1"), "");
        var id = drive("mo", "mo-1", "BB_MUTANT_BOUND=5").ticket();

        assertThat(field("waiting", id, "bound")).isEqualTo("5");
        var rolledBack = BbFixtures.executable(tools.resolve("bb-build.new"), resource("bb-build.pre116.prev"));

        Files.move(rolledBack, tools.resolve("bb-build"), StandardCopyOption.ATOMIC_MOVE);
        release("blk-1");
        assertThat(within(60, () -> runs().contains("mo-1"))).as(runs().toString()).isTrue();
        assertThat(field("running", id, "bound")).isEqualTo("5");
        var started = Long.parseLong(field("running", id, "started"));

        assertThat(within(60, () -> now() / SECOND - started >= 12)).isTrue();
        assertThat(in("running", id)).as("past its bound and a half").isTrue();
        assertThat(field("running", id, "bound_hit")).isEmpty();
        assertThat(boundRunners(id)).as("no bound runner started").isZero();
        assertThat(inCheckout("mo-1")).as("the build runs on").isNotEmpty();
        release("mo-1");
        assertThat(within(60, () -> in("done", id))).isTrue();
        assertThat(field("done", id, "rc")).isEqualTo("0");
        assertThat(field("done", id, "bound_hit")).isEmpty();
    }

    /// Item 5, pid reuse: the bound runner stops a job only while the process of that pid has the START TIME the ticket's waiter recorded. A runner
    /// handed the pid of a running job with another start time (the pid was reused) whose deadline has passed ends without a stop: no bound_hit=,
    /// the build runs on. Control: the same call with the true start time stops it (bound_hit=, rc 124). Mutation: drop the start-time comparison
    /// from job_runs in the bound runner -> the first call stops the job, red.
    @Test
    void aBoundRunnerHandedAReusedPid_stopsNothing() throws Exception {
        Files.writeString(dir.resolve("hold.mo-1"), "");
        var id = drive("mo", "mo-1", "BB_MUTANT_BOUND=600").ticket();

        assertThat(within(60, () -> runs().contains("mo-1") && boundRunners(id) > 0)).isTrue();
        var args = ProcessHandle.allProcesses()
                                .flatMap(process -> process.info().commandLine().stream())
                                .filter(line -> line.contains(" __bound " + id + " "))
                                .findFirst()
                                .orElseThrow()
                                .split(" ");
        var co = args[args.length - 4];
        var pid = args[args.length - 3];
        var start = args[args.length - 2];

        assertThat(co).isEqualTo(dir.resolve("work/co/mo-1").toString());
        var reused = BbFixtures.run(List.of("bash", tools.resolve("bb-build").toString(), "__bound", id, co, pid, "" + (Long.parseLong(start) + 1), "1"),
                                    env("mo"),
                                    dir,
                                    null);

        assertThat(reused.rc()).as(reused.stderr()).isZero();
        assertThat(field("running", id, "bound_hit")).isEmpty();
        assertThat(inCheckout("mo-1")).as("the build runs on").isNotEmpty();
        assertThat(ProcessHandle.of(Long.parseLong(pid))).isPresent();
        var same = BbFixtures.run(List.of("bash", tools.resolve("bb-build").toString(), "__bound", id, co, pid, start, "1"), env("mo"), dir, null);

        assertThat(same.rc()).as(same.stderr()).isZero();
        assertThat(field("running", id, "bound_hit")).as("control: the true start time is stopped").isNotEmpty();
        assertThat(within(120, () -> Files.exists(dir.resolve("work/co/mo-1/.bb/rc")))).isTrue();
        assertThat(Files.readString(dir.resolve("work/co/mo-1/.bb/rc")).strip()).isEqualTo("124");
        assertThat(inCheckout("mo-1")).isEmpty();
        assertThat(within(60,
                          () -> unchecked(() -> BbFixtures.waiters(id)).isEmpty())).isTrue();
        lib(tools.resolve("bb-queue-lib.sh"), queue, "exec 8> \"$Q/.lock\" && flock 8 && reap");
        assertThat(field("done", id, "state")).isEqualTo("orphaned");
        assertThat(field("done", id, "bound_hit")).isNotEmpty();
    }
}
