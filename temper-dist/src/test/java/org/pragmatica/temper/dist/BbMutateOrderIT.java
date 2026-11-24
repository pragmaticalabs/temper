package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
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


/// Mutant tickets (#116, CTO ruling B'): one mutant ticket per owner queued or running on a host, ordered by its batch's submit time
/// (order_at=), dropped while waiting when its driver is gone. The real bb-build, bb-queue-lib.sh and bb-mutate run on a throwaway queue;
/// maven is a script. A DRIVER is either the real bb-mutate or a stand-in named bb-mutate that makes one bb-build call and stays alive.
///
/// Probes (each red on its own row):
///  - never-in-the-future check removed -> [#aFutureOrderAt_isRefused_andNothingIsQueued]
///  - created_s() prefers order_at -> [#orderAt_neverReachesTheLoadStarvationOverride]
///  - order() ignores order_at -> [#aMutantTicket_sortsByItsBatchSubmitTime_aheadOfALaterTicket]
///  - one-ticket check removed -> [#aSecondMutantCallOfAnOwner_isRefusedRc20_whileOneIsWaitingOrRunning]
///  - driver check removed from the waiter -> [#aKilledDriver_leavesNoWaitingTicket_andItsRunningBuildFinishes]
///  - mutate_driver check removed -> [#aCallWhoseDriverIsMissingOrForeign_isRefused_andNothingIsQueued]
class BbMutateOrderIT {
    private static final long SECOND = 1_000_000_000L;

    private static final String DRIVER = """
        #!/usr/bin/env python3
        # stand-in driver: <checkout> <call file> [NAME=value (SELF = this pid) | NAME (removed)]...
        import os, subprocess, sys, time
        co, call, changes = sys.argv[1], sys.argv[2], sys.argv[3:]
        start = open("/proc/self/stat").read().rpartition(")")[2].split()[19]
        env = dict(os.environ, BB_VIA="bb-mutate:spec:m1", BB_ORDER_AT=str(time.time_ns()), BB_DRIVER_PID=str(os.getpid()), BB_DRIVER_START=start)
        for c in changes:
            k, sep, v = c.partition("=")
            env.pop(k, None)
            if sep:
                env[k] = str(os.getpid()) if v == "SELF" else v
        cp = subprocess.run([os.environ["HOME"] + "/tools/bb-build", co, "run"], stdin=subprocess.DEVNULL, capture_output=True, text=True, env=env)
        open(call + ".tmp", "w").write(f"{cp.returncode}\\n{cp.stdout}\\n{cp.stderr}")
        os.rename(call + ".tmp", call)
        time.sleep(1200)
        """;

    /// maven: records its BB_* environment (env.<checkout>, whole before the next line), then its start order (a checkout named in runs.log has its env file), waits while <dir>/hold or <dir>/hold.<checkout> exists, then reports one testcase
    /// of module A, failing when the module's file is mutated.
    private static final String LAUNCH = """
        #!/bin/bash
        n=${PWD##*/}
        env | grep -E '^BB_(ORDER_AT|DRIVER_|VIA|OWNER)' > "$HOLD_DIR/env.$n.tmp"; mv "$HOLD_DIR/env.$n.tmp" "$HOLD_DIR/env.$n"
        echo "$n" >> "$HOLD_DIR/runs.log"
        while [ -e "$HOLD_DIR/hold" ] || [ -e "$HOLD_DIR/hold.$n" ]; do sleep 0.2; done
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
        for (var tool : List.of("bb-build", "bb-mutate")) {
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
        var call = dir.resolve(checkout + ".call");
        var command = new ArrayList<>(List.of("python3",
                                              script.toString(),
                                              checkout(checkout).toString(),
                                              call.toString()));

        command.addAll(List.of(changes));
        var process = start(command, env(owner), dir.resolve(checkout + ".driver.log"));

        assertThat(within(60,
                          () -> Files.exists(call))).as(Files.readString(dir.resolve(checkout + ".driver.log")))
                  .isTrue();
        var text = Files.readString(call);

        return new Call(Integer.parseInt(text.substring(0, text.indexOf('\n'))),
                        text,
                        process.pid());
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

    // ---------------------------------------------------------------- proof 1
    /// Proof 1: while an owner's mutant ticket waits or runs, the next such call is refused with rc 20 and queues nothing. Controls: another
    /// owner's mutant call and the same owner's ordinary call are queued; once the ticket is done, the owner's next mutant call is queued.
    @Test
    void aSecondMutantCallOfAnOwner_isRefusedRc20_whileOneIsWaitingOrRunning() throws Exception {
        blocker();
        Files.writeString(dir.resolve("hold.mo-1"), "");
        var first = drive("mo", "mo-1").ticket();

        assertThat(in("waiting", first)).isTrue();
        var waiting = drive("mo", "mo-2");

        assertThat(waiting.rc()).as(waiting.out()).isEqualTo(20);
        assertThat(waiting.out()).contains(first + " in waiting/");
        assertThat(ids("waiting")).containsExactly(first);
        assertThat(dir.resolve("work/co/mo-2/.bb/queued")).doesNotExist();
        var other = drive("mp", "mp-1").ticket();
        var ordinary = plain("mo", "mo-3");

        assertThat(ids("waiting")).containsExactlyInAnyOrder(first, other, ordinary);
        release("blk-1");
        assertThat(within(60, () -> in("running", first))).isTrue();
        var running = drive("mo", "mo-4");

        assertThat(running.rc()).as(running.out()).isEqualTo(20);
        assertThat(running.out()).contains(first + " in running/");
        release("mo-1");
        assertThat(within(60, () -> in("done", first))).isTrue();
        assertThat(drive("mo", "mo-5").rc()).isZero();
    }

    private Process mutate(Path tool, String owner, String name, String build, int mutants) throws Exception {
        var mutations = new ArrayList<String>();

        for (var i = 1; i <= mutants; i++) {
            mutations.add("""
                          {"id":"m%d","file":"A/src/f.txt","kind":"lit","old":"ok","new":"MUT%d","expect":["ATest"]}""".formatted(i,
                                                                                                                                  i));
        }

        Files.writeString(dir.resolve(name + ".json"),
                          "{\"build\":" + build + ",\"mutations\":[" + String.join(",", mutations) + "]}");

        return start(List.of("python3",
                             tool.toString(),
                             checkout(name).toString(),
                             dir.resolve(name + ".json").toString(),
                             dir.resolve("out-" + name).toString(),
                             "--single"),
                     env(owner),
                     dir.resolve(name + ".driver.log"));
    }

    private String mutateLog(String name) {
        var log = dir.resolve("out-" + name).resolve("bb-mutate.log");

        return unchecked(() -> Files.exists(log)
                               ? Files.readString(log)
                               : "");
    }

    /// The owner's mutant tickets in waiting/ and running/, counted under the queue lock.
    private int mutantTickets() {
        var count = unchecked(() -> BbFixtures.run(List.of("bash",
                                                           "-c",
                                                           "exec 8> \"$1/.lock\"; flock 8; grep -ls '^via=bb-mutate:' \"$1\"/waiting/* \"$1\"/running/* | wc -l",
                                                           "bash",
                                                           queue.toString()),
                                                   env("mo"),
                                                   dir,
                                                   null));

        return Integer.parseInt(count.stdout().strip());
    }

    /// Proof 1 with two real bb-mutate runs of one owner: the count of its mutant tickets, sampled under the queue lock for the whole of both
    /// runs, is never above 1, and both runs finish with every mutant RED.
    @Test
    void twoRunsOfOneOwner_neverHoldTwoMutantTickets_andBothFinish() throws Exception {
        var max = new AtomicInteger();
        var samples = new AtomicInteger();
        var stop = new AtomicBoolean();
        var sampler = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                max.accumulateAndGet(mutantTickets(), Math::max);
                samples.incrementAndGet();
            }
        });

        Files.writeString(dir.resolve("hold"), "");
        var a = mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\"}", 2);
        var b = mutate(tools.resolve("bb-mutate"), "mo", "mo-b", "{\"pl\":\"A\"}", 1);

        assertThat(within(120, () -> (mutateLog("mo-a") + mutateLog("mo-b")).contains("waiting for its turn"))).isTrue();
        assertThat(within(60, () -> runs().size() == 1)).isTrue();
        Files.delete(dir.resolve("hold"));
        assertThat(a.waitFor(300, TimeUnit.SECONDS) && b.waitFor(300, TimeUnit.SECONDS)).isTrue();
        stop.set(true);
        sampler.join();
        assertThat(List.of(a.exitValue(), b.exitValue())).as(mutateLog("mo-a") + mutateLog("mo-b")).containsOnly(0);
        assertThat(outcomes("mo-a")).containsExactly("m1 RED", "m2 RED");
        assertThat(outcomes("mo-b")).containsExactly("m1 RED");
        assertThat(samples.get()).isGreaterThan(20);
        assertThat(max.get()).isEqualTo(1);
    }

    /// The control of the test above: the same two runs of the bb-mutate before #116 (which passes no BB_ORDER_AT, so bb-build never refuses it)
    /// do hold two tickets at once, so the sampler can see two.
    @Test
    void twoRunsOfTheOldTool_holdTwoMutantTickets() throws Exception {
        var old = BbFixtures.executable(dir.resolve("drv/old-bb-mutate"), resource("bb-mutate.pre116.prev"));

        Files.writeString(dir.resolve("hold"), "");
        mutate(old, "mo", "mo-a", "{\"pl\":\"A\"}", 1);
        mutate(old, "mo", "mo-b", "{\"pl\":\"A\"}", 1);
        assertThat(within(120, () -> mutantTickets() == 2)).as(mutateLog("mo-a") + mutateLog("mo-b")).isTrue();
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

    /// A mutant that waits for the owner's other mutant ticket longer than its own bound is not timed out by it: the bound starts at its own
    /// Maven start. The other ticket is planted between the control and the first mutant and held for 30 s; the bound is 20 s.
    @Test
    void theWaitForTheTurn_doesNotCountAgainstTheMutantsBound() throws Exception {
        Files.writeString(dir.resolve("hold"), "");
        var run = mutate(tools.resolve("bb-mutate"), "mo", "mo-a", "{\"pl\":\"A\",\"mutantTimeoutSecs\":20}", 1);

        assertThat(within(120, () -> runs().size() == 1)).as(mutateLog("mo-a")).isTrue();
        var other = start(List.of("bash", "-c", "exec -a 'bb-build __run 1-planted' sleep 600"),
                          env("mo"),
                          dir.resolve("planted.log"));

        Files.writeString(queue.resolve("running/1-planted"),
                          "id=1-planted\nowner=mo\nco=" + dir
                         + "/none\ncap=3\ncreated=1\nvia=bb-mutate:spec:other\nargs=run\n");
        Files.delete(dir.resolve("hold"));
        assertThat(within(120,
                          () -> mutateLog("mo-a").contains("[m1] waiting for its turn"))).as(mutateLog("mo-a"))
                  .isTrue();
        Thread.sleep(30_000);
        assertThat(run.isAlive()).as(mutateLog("mo-a")).isTrue();
        assertThat(ids("running")).containsExactly("1-planted");
        assertThat(ids("waiting")).isEmpty();
        other.destroyForcibly().waitFor();
        assertThat(run.waitFor(180, TimeUnit.SECONDS)).isTrue();
        assertThat(run.exitValue()).as(mutateLog("mo-a")).isZero();
        assertThat(outcomes("mo-a")).containsExactly("m1 RED");
    }

    // ---------------------------------------------------------------- proof 2 and 3
    /// Proof 2: a mutant ticket enqueued AFTER an ordinary ticket runs before it when its batch was submitted before that ticket. Control, in the
    /// same queue: a mutant ticket whose batch was submitted after the ordinary ticket runs after it. The waiting order and the real grants agree.
    @Test
    void aMutantTicket_sortsByItsBatchSubmitTime_aheadOfALaterTicket() throws Exception {
        blocker();
        var submitted = now();
        var ordinary = plain("ord", "ord-1");
        var late = drive("mp", "mp-1").ticket();
        var early = drive("mo", "mo-1", "BB_ORDER_AT=" + submitted).ticket();

        assertThat(lib(tools.resolve("bb-queue-lib.sh"), queue, "order")).isEqualTo(String.join("\n",
                                                                                                early,
                                                                                                ordinary,
                                                                                                late));
        assertThat(field("waiting", early, "order_at")).isEqualTo(Long.toString(submitted));
        release("blk-1");
        assertThat(within(120, () -> runs().size() == 4)).isTrue();
        assertThat(runs()).containsExactly("blk-1", "mo-1", "ord-1", "mp-1");
    }

    /// Proof 3: a ticket of a higher priority goes before a mutant ticket whatever the mutant ticket's order_at. Control: the ordinary ticket of
    /// priority 0, enqueued before both, goes last, so the order_at was in force.
    @Test
    void aHigherPriorityTicket_goesBeforeAnEarlierOrderAt() throws Exception {
        blocker();
        var ordinary = plain("ord", "ord-1");
        var mutant = drive("mo", "mo-1", "BB_ORDER_AT=" + (now() - 7200 * SECOND)).ticket();
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
        Thread.sleep(12_000);  // two more polls of every waiter
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

    // ---------------------------------------------------------------- proof 5 and the bb-mutate-only rule
    /// Proof 5: an order_at in the future (by an hour, and by a minute) is refused with rc 2 and nothing is queued. Control: the same call with a
    /// time in the past is queued.
    @Test
    void aFutureOrderAt_isRefused_andNothingIsQueued() throws Exception {
        for (var ahead : List.of(60, 3600)) {
            var call = drive("mo", "mo-" + ahead, "BB_ORDER_AT=" + (now() + ahead * SECOND));

            assertThat(call.rc()).as(call.out()).isEqualTo(2);
            assertThat(call.out()).contains("is in the future");
        }

        nothingQueued();
        assertThat(drive("mo", "mo-1", "BB_ORDER_AT=" + (now() - 60 * SECOND)).rc()).isZero();
    }

    /// A malformed order_at is refused with rc 2 and nothing is queued: empty, seconds, a letter, a leading zero, a newline with a second field,
    /// above 2^63.
    @Test
    void aMalformedOrderAt_isRefused_andNothingIsQueued() throws Exception {
        var row = 0;

        for (var value : List.of("",
                                 "1790000000",
                                 "179000000000000000x",
                                 "0790000000000000000",
                                 "1790000000000000000\npriority=9",
                                 "9999999999999999999")) {
            var call = drive("mo", "mo-" + row++, "BB_ORDER_AT=" + value);

            assertThat(call.rc()).as(value + ": " + call.out()).isEqualTo(2);
            assertThat(call.out()).as(value).contains("refusing: BB_ORDER_AT");
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
                       Map.of("BB_ORDER_AT", Long.toString(now() - SECOND), "BB_VIA", "bb-mutate:spec:m1")));
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
    /// carries only stray BB_DRIVER_* variables. Control: BB_OWNER and BB_VIA, passed the same way, do reach the build.
    @Test
    void theOrderAndDriverVariables_reachNeitherTheWaiterNorTheBuild() throws Exception {
        Files.writeString(dir.resolve("hold.mo-1"), "");
        var id = drive("mo", "mo-1").ticket();

        assertThat(within(60, () -> runs().contains("mo-1"))).isTrue();
        var waiter = BbFixtures.waiters(id).getFirst();
        var waiterEnv = List.of(Files.readString(Path.of("/proc", waiter.toString(), "environ")).split("\0"));

        assertThat(waiterEnv).contains("BB_OWNER=mo", "BB_VIA=bb-mutate:spec:m1");
        assertThat(waiterEnv).noneMatch(v -> v.startsWith("BB_ORDER_AT") || v.startsWith("BB_DRIVER_"));
        assertThat(Files.readAllLines(dir.resolve("env.mo-1"))).containsExactlyInAnyOrder("BB_OWNER=mo",
                                                                                          "BB_VIA=bb-mutate:spec:m1");
        release("mo-1");
        plain("ord", "ord-1", Map.of("BB_DRIVER_PID", "1", "BB_DRIVER_START", "1")).ticket();
        assertThat(within(60, () -> runs().contains("ord-1"))).isTrue();
        assertThat(Files.readAllLines(dir.resolve("env.ord-1"))).containsExactly("BB_OWNER=ord");
    }

    // ---------------------------------------------------------------- proof 6, created= and proof 7
    /// created= of a mutant ticket is its true enqueue time, whatever its order_at.
    @Test
    void createdOfAMutantTicket_isItsTrueEnqueueTime() throws Exception {
        blocker();
        var orderAt = now() - 7200 * SECOND;
        var before = now();
        var id = drive("mo", "mo-1", "BB_ORDER_AT=" + orderAt).ticket();
        var after = now();

        assertThat(Long.parseLong(field("waiting", id, "created"))).isBetween(before, after);
        assertThat(field("waiting", id, "order_at")).isEqualTo(Long.toString(orderAt));
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
        var id = drive("mo", "mo-1", "BB_ORDER_AT=" + (now() - 7200 * SECOND)).ticket();
        var real = Files.readString(queue.resolve("waiting").resolve(id));
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
}
