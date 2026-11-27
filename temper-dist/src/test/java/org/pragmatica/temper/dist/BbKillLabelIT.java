package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// #131: a ticket's outcome says OOM-KILLED only when the host's kernel recorded an OOM kill during that ticket's run AND the ticket's
/// own process ended by SIGKILL (rc 137); KILLED for any other SIGKILL, naming bb-build's bound when it was the bound; no label for
/// an ordinary non-zero rc. The evidence is written by bb-build into the ticket (`oom_kill_start=`/`oom_kill_end=` from /proc/vmstat,
/// `mem_avail_kb_start=`/`mem_avail_kb_end=` from /proc/meminfo, read at the grant and right after the job's wait) and read by
/// bb-result (`outcome:` line, `outcome`/`outcomeNote`/`kernel` in --json, `--outcome` one-line mode), echoed by bb-wait and bb-await.
/// Real `bb-build __run` waiters with a stub maven; the host counters are fake files (seams BB_TEST_VMSTAT, BB_TEST_MEMINFO).
/// A ticket of before the change (no `oom_kill_start=`) prints exactly what it printed.
///
/// Probes (mutate, the named rows turn red): label by `rc == 137` alone (drop the delta test in bb-result) ->
/// [#twoConcurrentTickets_oneOomKilledOneExitingOne_onlyTheKilledOneIsOomKilled] stays green but [#aSigkillWithNoKernelOomKill_isKilled_notOom] red;
/// label by the delta alone (drop the rc test) -> the concurrent row and [#anExitOneInAWindowWhereTheCounterMoved_hasNoLabel] red;
/// read oom_kill_end at the lock instead of right after the job's wait -> not detectable here (a window only); skip the bound_hit test ->
/// [#aBoundKillInAWindowWithAnOomKill_isKilledByTheBound_notOom] red; write the fields on the ticket without `unavailable` -> [#unreadableCounters_...] red.
class BbKillLabelIT {
    private static final String ME = "klb";

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private Path vmstat;
    private Path ampleMeminfo;
    private final List<Process> started = new ArrayList<>();
    private int seq;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock, pgrep and GNU tools");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        for (var script : List.of("bb-build", "bb-result", "bb-wait", "bb-await", "bb-queue")) {
            BbFixtures.executable(tools.resolve(script), Files.readString(BbFixtures.OPS_BB.resolve(script)));
        }

        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        vmstat = Files.writeString(dir.resolve("vmstat"), "nr_free_pages 1\noom_kill 5\npgfault 9\n");
        // bb-queue-lib's memory floor (8 GiB of MemAvailable) holds a grant, and /proc/meminfo is the host's: a build on a 32 GB host sits near that floor while
        // other Maven runs share it, so a row's ticket was never granted and `finished` timed out (60 s, once). A fixed ample file takes the host out of every row.
        ampleMeminfo = Files.writeString(dir.resolve("meminfo-ample"), "MemTotal:       200000000 kB\nMemAvailable:   100000000 kB\n");
    }

    @AfterEach
    void cleanUp() {
        for (var process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private Map<String, String> env(Map<String, String> more) {
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
        env.put("BB_TEST_VMSTAT", vmstat.toString());
        env.put("BB_TEST_MEMINFO", ampleMeminfo.toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_MAX_REACTORS", "5");
        env.putAll(more);

        return env;
    }

    private Path checkout(String name) throws Exception {
        var co = Files.createDirectories(dir.resolve("work").resolve(name));

        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }

        return Files.createDirectories(co.resolve(".bb")).getParent();
    }

    /// A waiting ticket for a fresh checkout and its waiter, running the stub maven `mvnBody`; returns the ticket id.
    private String start(String name, String mvnBody, String extraFields, Map<String, String> more) throws Exception {
        var co = checkout(ME + "-" + name);
        var id = "1795733112" + String.format("%09d", ++seq) + "-" + (RUN_PID * 100 + seq);
        var mvn = BbFixtures.executable(dir.resolve("stubmvn-" + name), "#!/bin/sh\n" + mvnBody + "\n");

        Files.writeString(queue.resolve("waiting").resolve(id),
                          "id=" + id
                         + "\nowner=" + ME
                         + "\nco=" + co
                         + "\nsha=abc\nkey=\ncap=5\nlimit=0\ncreated=" + (System.currentTimeMillis() * 1_000_000L)
                         + "\ngroup=0\n" + extraFields + "args=-B test \n");
        var env = env(more);

        env.put("BB_TEST_MVN", mvn.toString());
        var builder = BbFixtures.processClean(List.of("bash", tools.resolve("bb-build").toString(), "__run", id, "-B", "test"), env);

        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        started.add(builder.start());

        return id;
    }

    private void finished(String id) throws Exception {
        for (var i = 0; i < 600 && !Files.exists(queue.resolve("done").resolve(id)); i++) {
            Thread.sleep(100);
        }

        assertThat(queue.resolve("done").resolve(id)).as("ticket " + id + " finished").exists();
    }

    /// The ticket was granted and carries the start of its window: running/<id> holds `oom_kill_start=`. A row that bumps the counter from another ticket starts that
    /// ticket only after this, so the verdict of the row never depends on which waiter won the lock first.
    private void granted(String id) throws Exception {
        for (var i = 0; i < 600; i++) {
            var running = queue.resolve("running").resolve(id);

            if (Files.exists(running) && Files.readString(running).contains("\noom_kill_start=")) {
                return;
            }
            Thread.sleep(100);
        }

        throw new AssertionError("ticket " + id + " was never granted (no oom_kill_start= in running/)");
    }

    private String field(String id, String key) throws IOException {
        return Files.readAllLines(queue.resolve("done").resolve(id))
                    .stream()
                    .filter(l -> l.startsWith(key + "="))
                    .reduce((a, b) -> b)
                    .map(l -> l.substring(key.length() + 1))
                    .orElse("");
    }

    private BbFixtures.Outcome tool(String script, String... args) throws Exception {
        var command = new ArrayList<String>(List.of(script.equals("bb-result") ? "python3" : "bash",
                                                    tools.resolve(script).toString()));

        command.addAll(List.of(args));

        return BbFixtures.run(command, env(Map.of()), null, null);
    }

    /// bb-queue-lib finds a ticket's waiter by `pgrep -u $USER -f "bb-build __run <id>"`, host-wide, and the bound runner's kill set (kill_set) TERMs and KILLs everything that matches. A fixed id
    /// ("...001-7001") is therefore shared by every copy of this class running on one host at once (several builds on a CI host): a bound row of one copy killed the waiter of the same-id ticket of
    /// another, whose ticket stayed in running/ and `finished` timed out after 60 s (cloudbb-10, 4 concurrent builds, one build of four failed in each of 3 loaded sets of runs, a different row each time; the dump showed running/<id> with
    /// bound_hit= and no `bb-build __run <id>` process). The pid of this JVM, which is unique among the JVMs alive on the host, is the id's pid half, so no two concurrent copies share an id.
    private static final long RUN_PID = ProcessHandle.current().pid();
    private static final String KILL_SELF = "kill -9 $$";

    private String bump(int to) {
        return "echo 'oom_kill " + to + "' > " + vmstat + "\n";
    }

    // ---- bb-build writes the kernel's evidence into the ticket ------------------------------------------------------
    @Test
    void theTicketCarriesTheCountersOfItsOwnWindow() throws Exception {
        var meminfo = Files.writeString(dir.resolve("meminfo-1"), "MemTotal: 99 kB\nMemAvailable:   100000000 kB\n");
        var id = start("window", bump(6) + "exit 1", "", Map.of("BB_TEST_MEMINFO", meminfo.toString()));

        finished(id);
        assertThat(field(id, "oom_kill_start")).isEqualTo("5");
        assertThat(field(id, "oom_kill_end")).isEqualTo("6");
        assertThat(field(id, "mem_avail_kb_start")).isEqualTo("100000000");
        assertThat(field(id, "mem_avail_kb_end")).isEqualTo("100000000");
        assertThat(field(id, "rc")).isEqualTo("1");
    }

    @Test
    void unreadableCounters_areRecordedAsUnavailable_neverAsZero() throws Exception {
        var id = start("blind", KILL_SELF, "", Map.of("BB_TEST_VMSTAT", dir.resolve("no-such-vmstat").toString(),
                                                      "BB_TEST_MEMINFO", dir.resolve("no-such-meminfo").toString()));

        finished(id);
        assertThat(field(id, "oom_kill_start")).isEqualTo("unavailable");
        assertThat(field(id, "oom_kill_end")).isEqualTo("unavailable");
        assertThat(field(id, "mem_avail_kb_start")).isEqualTo("unavailable");
        assertThat(field(id, "mem_avail_kb_end")).isEqualTo("unavailable");
    }

    @Test
    void unreadableCounters_areLabelledKilled_neverOomKilled() throws Exception {
        var id = start("blind2", KILL_SELF, "", Map.of("BB_TEST_VMSTAT", dir.resolve("no-such-vmstat").toString()));

        finished(id);
        var out = tool("bb-result", id);

        assertThat(out.stdout()).contains("rc=137").contains("outcome: KILLED").contains("unavailable").doesNotContain("OOM-KILLED");
    }

    // ---- bb-result's label ----------------------------------------------------------------------------------------------
    @Test
    void anOomKill_isLabelledOomKilled_inTextJsonAndTheOneLineMode() throws Exception {
        var id = start("oom", bump(6) + KILL_SELF, "", Map.of());

        finished(id);
        assertThat(field(id, "rc")).isEqualTo("137");
        var text = tool("bb-result", id);

        assertThat(text.stdout()).contains("rc=137").contains("outcome: OOM-KILLED").contains("oom_kill 5 -> 6");
        var json = tool("bb-result", id, "--json").stdout();

        assertThat(json).contains("\"outcome\": \"OOM-KILLED\"").contains("\"oomKillStart\": 5").contains("\"oomKillEnd\": 6");
        var one = tool("bb-result", id, "--outcome");

        assertThat(one.rc()).isZero();
        assertThat(one.stdout().strip()).startsWith("outcome: OOM-KILLED").doesNotContain("\n");
    }

    @Test
    void aSigkillWithNoKernelOomKill_isKilled_notOom() throws Exception {
        var id = start("kill9", KILL_SELF, "", Map.of());

        finished(id);
        var text = tool("bb-result", id);

        assertThat(text.stdout()).contains("rc=137").contains("outcome: KILLED").contains("no kernel OOM kill").doesNotContain("OOM-KILLED");
        assertThat(tool("bb-result", id, "--json").stdout()).contains("\"outcome\": \"KILLED\"");
    }

    @Test
    void twoConcurrentTickets_oneOomKilledOneExitingOne_onlyTheKilledOneIsOomKilled() throws Exception {
        var flag = dir.resolve("a-done");
        var failing = start("exit1",
                            "until [ -f " + flag + " ]; do sleep 0.1; done\nexit 1",
                            "",
                            Map.of());

        granted(failing);   // the production order: the failing ticket's window opened (its start field is written) before the victim's stub bumps the counter
        var killed = start("victim", bump(6) + "touch " + flag + "\n" + KILL_SELF, "", Map.of());

        finished(killed);
        finished(failing);
        assertThat(field(failing, "oom_kill_start")).isEqualTo("5");
        assertThat(field(failing, "oom_kill_end")).as("the failing ticket's window saw the counter move").isEqualTo("6");
        assertThat(tool("bb-result", killed).stdout()).contains("outcome: OOM-KILLED");
        var ordinary = tool("bb-result", failing).stdout();

        assertThat(ordinary).contains("rc=1").doesNotContain("outcome:").doesNotContain("OOM");
        assertThat(tool("bb-result", failing, "--json").stdout()).doesNotContain("outcome");
        assertThat(tool("bb-result", failing, "--outcome").stdout()).isEmpty();
    }

    @Test
    void anExitOneInAWindowWhereTheCounterMoved_hasNoLabel() throws Exception {
        var id = start("fail", bump(7) + "exit 1", "", Map.of());

        finished(id);
        assertThat(tool("bb-result", id).stdout()).contains("rc=1").doesNotContain("outcome:");
    }

    @Test
    void aBoundKillInAWindowWhereAnotherProcessWasOomKilled_isKilledByTheBound_notOom() throws Exception {
        var id = start("bound", bump(6) + "sleep 60", "bound=2\n", Map.of());

        finished(id);
        assertThat(field(id, "bound_hit")).isNotEmpty();
        assertThat(field(id, "oom_kill_end")).isEqualTo("6");
    }

    @Test
    void aBoundKillInAWindowWhereAnotherProcessWasOomKilled_isLabelledKilledByTheBound_notOom() throws Exception {
        var id = start("bound2", bump(6) + "sleep 60", "bound=2\n", Map.of());

        finished(id);
        var out = tool("bb-result", id).stdout();

        assertThat(out).contains("rc=124").contains("outcome: KILLED").contains("bound of 2s").doesNotContain("OOM-KILLED");
        assertThat(tool("bb-result", id, "--json").stdout()).contains("\"outcome\": \"KILLED\"");
    }

    @Test
    void aTicketOfBeforeTheChange_printsExactlyWhatItPrinted() throws Exception {
        var co = dir.resolve("work/old-co");
        var id = "1795733112000000001-1";

        Files.createDirectories(co);
        Files.writeString(queue.resolve("done").resolve(id),
                          "id=" + id + "\nowner=" + ME + "\nco=" + co + "\ncreated=1795733112000000000\nstarted=1795733113\nstate=finished\nrc=137\nended=1795733130\n");
        for (var args : List.of(List.<String>of(), List.of("--json"), List.of("--outcome"))) {
            var all = new ArrayList<String>(List.of(id));

            all.addAll(args);
            var out = tool("bb-result", all.toArray(String[]::new));

            assertThat(out.rc()).isZero();
            assertThat(out.stdout()).doesNotContain("outcome").doesNotContain("OOM").doesNotContain("kernel");
        }

        assertThat(tool("bb-result", id).stdout()).startsWith("ticket=" + id + " state=finished rc=137 wall=");
        assertThat(tool("bb-wait", id).stdout()).contains("rc=137").doesNotContain("outcome");
        assertThat(tool("bb-await", "--ticket", id).stdout()).contains("rc=137").doesNotContain("outcome");
    }

    // ---- the other printers ---------------------------------------------------------------------------------------------
    @Test
    void bbWaitAndBbAwait_echoTheLabel_ofAKilledTicketOnly() throws Exception {
        var killed = start("pk", bump(6) + KILL_SELF, "", Map.of());
        var failed = start("pf", "exit 1", "", Map.of());

        finished(killed);
        finished(failed);
        for (var printer : List.<List<String>>of(List.of("bb-wait", killed), List.of("bb-await", "--ticket", killed))) {
            var out = tool(printer.get(0), printer.subList(1, printer.size()).toArray(String[]::new));

            assertThat(out.rc()).as(out.stdout() + out.stderr()).isEqualTo(137);
            assertThat(out.stdout()).contains("rc=137").contains("outcome: OOM-KILLED");
            assertThat(lines(out.stdout())).as("the printer's own line and bb-result's ONE label line, nothing of bb-result's summary: " + out.stdout()).hasSize(2);
            assertThat(lines(out.stdout()).get(1)).startsWith("outcome: OOM-KILLED (kernel oom_kill 5 -> 6");
        }

        for (var printer : List.<List<String>>of(List.of("bb-wait", failed), List.of("bb-await", "--ticket", failed))) {
            var out = tool(printer.get(0), printer.subList(1, printer.size()).toArray(String[]::new));

            assertThat(out.rc()).isEqualTo(1);
            assertThat(out.stdout()).contains("rc=1").doesNotContain("outcome");
        }
    }

    // ---- rc 124 half of the printers, a job's own 124, a refused ticket (bb-mutate probes of #131) -------------------------
    private String doneTicket(String id, String fields) throws Exception {
        return doneTicket(id, "finished", fields);
    }

    private String doneTicket(String id, String state, String fields) throws Exception {
        var co = dir.resolve("work/done-co-" + id);

        Files.createDirectories(co);
        Files.writeString(queue.resolve("done").resolve(id),
                          "id=" + id + "\nowner=" + ME + "\nco=" + co + "\ncreated=1795733112000000000\nstarted=1795733113\nstate=" + state + "\nended=1795733130\n" + fields);
        return id;
    }

    private static List<String> lines(String out) {
        return out.strip().lines().toList();
    }

    private static final String WINDOW_MOVED = "oom_kill_start=5\noom_kill_end=6\nmem_avail_kb_start=10\nmem_avail_kb_end=9\n";

    @Test
    void bbWaitAndBbAwait_echoTheBoundLabel_ofARc124Ticket() throws Exception {
        var id = doneTicket("1795733112000000011-1", "rc=124\nbound_hit=1795733125\nbound=2\n" + WINDOW_MOVED);

        for (var printer : List.<List<String>>of(List.of("bb-wait", id), List.of("bb-await", "--ticket", id))) {
            var out = tool(printer.get(0), printer.subList(1, printer.size()).toArray(String[]::new));

            assertThat(out.rc()).as(out.stdout() + out.stderr()).isEqualTo(124);
            assertThat(out.stdout()).contains("rc=124").contains("outcome: KILLED").contains("bound of 2s").doesNotContain("OOM-KILLED");
            assertThat(lines(out.stdout())).as("the printer's own line and the ONE label line: " + out.stdout()).hasSize(2);
            assertThat(lines(out.stdout()).get(1)).isEqualTo("outcome: KILLED (stopped by bb-build's bound of 2s)");
        }
    }

    @Test
    void aRc124WithoutBoundHit_isTheJobsOwnExit_noLabelInAnyPrinter_whateverTheCounterDid() throws Exception {
        var id = doneTicket("1795733112000000012-1", "rc=124\n" + WINDOW_MOVED);

        for (var printer : List.<List<String>>of(List.of("bb-wait", id), List.of("bb-await", "--ticket", id), List.of("bb-result", id),
                                                 List.of("bb-result", id, "--outcome"), List.of("bb-result", id, "--json"))) {
            var out = tool(printer.get(0), printer.subList(1, printer.size()).toArray(String[]::new));

            assertThat(out.stdout()).as(printer.toString()).doesNotContain("outcome: ").doesNotContain("OOM-KILLED").doesNotContain("\"outcome\": \"");
        }
    }

    @Test
    void aRefusedTicket_hasNoLabel_whateverItsRcAndCounterSay() throws Exception {
        var id = doneTicket("1795733112000000013-1", "rc=137\nrefused=resolved-junit\n" + WINDOW_MOVED);

        for (var args : List.of(List.<String>of(), List.of("--outcome"), List.of("--json"))) {
            var all = new ArrayList<String>(List.of(id));

            all.addAll(args);
            var out = tool("bb-result", all.toArray(String[]::new));

            assertThat(out.stdout()).as(args.toString()).doesNotContain("outcome: ").doesNotContain("OOM-KILLED").doesNotContain("\"outcome\": \"");
        }
    }

    @Test
    void bbResultJsonAndOutcomeTogether_areAUsageError_inEitherOrder() throws Exception {
        var id = doneTicket("1795733112000000014-1", "rc=137\n" + WINDOW_MOVED);

        for (var args : List.of(List.of(id, "--json", "--outcome"), List.of(id, "--outcome", "--json"))) {
            var out = tool("bb-result", args.toArray(String[]::new));

            assertThat(out.rc()).as(args.toString()).isEqualTo(2);
            assertThat(out.stdout()).as(args.toString()).isEmpty();
        }
    }

    @Test
    void bbResultJson_carriesTheOutcomeNote_ofBothLabels() throws Exception {
        var oom = tool("bb-result", doneTicket("1795733112000000015-1", "rc=137\n" + WINDOW_MOVED), "--json").stdout();
        var bound = tool("bb-result", doneTicket("1795733112000000016-1", "rc=124\nbound_hit=1795733125\nbound=2\n" + WINDOW_MOVED), "--json").stdout();

        assertThat(oom).contains("\"outcome\": \"OOM-KILLED\"").contains("\"outcomeNote\": \"kernel oom_kill 5 -> 6, MemAvailable 10 -> 9 kB\"");
        assertThat(bound).contains("\"outcome\": \"KILLED\"").contains("\"outcomeNote\": \"stopped by bb-build").contains("bound of 2s");
    }

    // ---- fix round 1 of #144: rows for the survivors of v144a/v144b ------------------------------------------------------
    /// The window ends when the job ends (right after the waiter's wait), not after the bound runner has finished stopping the ticket's processes. The verdict depends only on the ORDER
    /// of two events, never on how long the waiter takes between the job's exit and its end read: BB_TEST_VMSTAT is a FIFO served by a loop of this test, which sees each read as it
    /// happens. It serves oom_kill 5 until the bound runner has written .bb/rc (the kill set is reaped: the moment the old row approximated with a 2 s sleep) and 6 from then on.
    /// A fake `java` (the runner's kill set), once TERMed, stays alive until the server has served the second read (the end read) and only then dies, so the runner cannot finish
    /// first however long the waiter stalls (up to the runner's 5 s KILL grace, see Limits). Probes: end read after `wait "$W"` -> the java is only KILLed after 5 s, the runner
    /// finishes, the read gets 6, red; a `sleep 3` before the end read -> still 5, green (the old row, a bump 2 s after the TERM, went red).
    @Test
    void theWindowEndsWhenTheJobEnds_notWhenTheBoundRunnerHasStoppedTheTicketsProcesses() throws Exception {
        var fifo = dir.resolve("vmstat-fifo");
        var served = dir.resolve("vmstat-served");
        var endRead = dir.resolve("vmstat-end-read");
        var runnerRc = dir.resolve("work").resolve(ME + "-late").resolve(".bb").resolve("rc");
        var server = BbFixtures.executable(dir.resolve("vmstatd.sh"),
                                           "#!/bin/bash\ntrap '' PIPE\nn=0\nwhile :; do\n"
                                          + "  { n=$((n+1)); if [ -e " + runnerRc + " ]; then v=6; else v=5; fi\n"
                                          + "    printf 'nr_free_pages 1\\noom_kill %s\\npgfault 9\\n' \"$v\"; echo \"$n:$v\" >> " + served + "\n"
                                          + "    [ \"$n\" -lt 2 ] || : > " + endRead + "; } > " + fifo + "\ndone\n");
        var fakeJava = BbFixtures.executable(dir.resolve("fakejava.sh"),
                                             "#!/bin/bash\ntrap 'while [ ! -e " + endRead + " ]; do sleep 0.1; done; exit 0' TERM\nwhile :; do sleep 0.2; done\n");

        assertThat(BbFixtures.run(List.of("mkfifo", fifo.toString()), Map.of(), null, null).rc()).isZero();
        started.add(BbFixtures.processClean(List.of("bash", server.toString()), Map.of("PATH", System.getenv("PATH")))
                             .redirectErrorStream(true)
                             .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                             .start());
        var id = start("late", "bash -c 'exec -a java bash " + fakeJava + "' &\nsleep 60", "bound=2\n", Map.of("BB_TEST_VMSTAT", fifo.toString()));

        finished(id);
        assertThat(field(id, "bound_hit")).isNotEmpty();
        assertThat(Files.readString(served).strip()).as("the counter was read twice, both while the runner was still stopping the ticket").isEqualTo("1:5\n2:5");
        assertThat(BbFixtures.run(List.of("cat", fifo.toString()), Map.of(), null, null).stdout()).as("the row's premise: once the runner is done the counter reads 6").contains("oom_kill 6");
        assertThat(field(id, "oom_kill_start")).isEqualTo("5");
        assertThat(field(id, "oom_kill_end")).as("a read after the job ended and the runner finished is not the job's window").isEqualTo("5");
    }

    @Test
    void aTicketThatIsNotFinished_isNeverLabelled_whateverItsRcAndCounterSay() throws Exception {
        var id = doneTicket("1795733112000000021-1", "cancelled", "rc=137\n" + WINDOW_MOVED);

        for (var args : List.of(List.<String>of(), List.of("--outcome"), List.of("--json"))) {
            var all = new ArrayList<String>(List.of(id));

            all.addAll(args);
            var out = tool("bb-result", all.toArray(String[]::new));

            assertThat(out.stdout()).as(args.toString()).doesNotContain("outcome: ").doesNotContain("OOM-KILLED").doesNotContain("\"outcome\": \"");
        }
    }

    @Test
    void aBoundKillWhoseBoundIsMissingOrNotANumber_isLabelledWithoutANumber() throws Exception {
        for (var bound : List.of("", "bound=abc\n")) {
            var id = doneTicket("17957331120000000" + (bound.isEmpty() ? "31" : "32") + "-1", "rc=124\nbound_hit=1795733125\n" + bound + WINDOW_MOVED);

            assertThat(tool("bb-result", id, "--outcome").stdout().strip()).as(bound).isEqualTo("outcome: KILLED (stopped by bb-build's bound)");
            assertThat(tool("bb-result", id, "--json").stdout()).as(bound).contains("\"outcomeNote\": \"stopped by bb-build's bound\"");
        }
    }

    @Test
    void aSigkillWithExactlyOneCounterUnavailable_isKilled_neverOomKilled() throws Exception {
        var noEnd = doneTicket("1795733112000000041-1", "rc=137\noom_kill_start=5\noom_kill_end=unavailable\nmem_avail_kb_start=10\nmem_avail_kb_end=9\n");
        var noStart = doneTicket("1795733112000000042-1", "rc=137\noom_kill_start=unavailable\noom_kill_end=6\nmem_avail_kb_start=10\nmem_avail_kb_end=9\n");

        assertThat(tool("bb-result", noEnd, "--outcome").stdout().strip()).isEqualTo("outcome: KILLED (kernel OOM counter unavailable, oom_kill 5 -> unavailable)");
        assertThat(tool("bb-result", noStart, "--outcome").stdout().strip()).isEqualTo("outcome: KILLED (kernel OOM counter unavailable, oom_kill unavailable -> 6)");
        assertThat(tool("bb-result", noEnd).stdout()).contains("rc=137").doesNotContain("OOM-KILLED");
        assertThat(tool("bb-result", noStart, "--json").stdout()).contains("\"outcome\": \"KILLED\"");
    }

    /// bb-wait and bb-await ask bb-result for its ONE line (`<id> --outcome`), for a finished ticket of rc 124 or 137 only: a stub bb-result shows what they call it with.
    /// Probes: drop `--outcome` (the printers would dump bb-result's whole summary), print for any state -> red.
    @Test
    void theWaitAndAwaitPrinters_askBbResultForItsOneLine_onlyForAFinishedRc124Or137() throws Exception {
        var stub = Files.createDirectories(dir.resolve("stub-tools"));

        for (var script : List.of("bb-wait", "bb-await", "bb-queue", "bb-queue-lib.sh")) {
            BbFixtures.executable(stub.resolve(script), Files.readString(BbFixtures.OPS_BB.resolve(script)));
        }

        BbFixtures.executable(stub.resolve("bb-result"), "import sys\nprint('STUB-CALLED ' + ' '.join(sys.argv[1:]))\n");
        var killed = doneTicket("1795733112000000051-1", "rc=137\n" + WINDOW_MOVED);
        var bound = doneTicket("1795733112000000052-1", "rc=124\nbound_hit=1795733125\n" + WINDOW_MOVED);
        var cancelled = doneTicket("1795733112000000053-1", "cancelled", "rc=137\n" + WINDOW_MOVED);
        var plain = doneTicket("1795733112000000054-1", "rc=1\n" + WINDOW_MOVED);

        for (var printer : List.of("bb-wait", "bb-await")) {
            for (var id : List.of(killed, bound, cancelled, plain)) {
                var command = new ArrayList<String>(List.of("bash", stub.resolve(printer).toString()));

                if (printer.equals("bb-await")) {
                    command.add("--ticket");
                }

                command.add(id);
                var out = BbFixtures.run(command, env(Map.of()), null, null);

                if (id.equals(killed) || id.equals(bound)) {
                    assertThat(lines(out.stdout())).as(printer + " " + id + ": " + out.stdout() + out.stderr()).hasSize(2).last().isEqualTo("STUB-CALLED " + id + " --outcome");
                } else {
                    assertThat(out.stdout()).as(printer + " " + id).doesNotContain("STUB-CALLED");
                }
            }
        }
    }
}
