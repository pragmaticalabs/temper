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
import org.junit.jupiter.api.Disabled;
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
        var id = "1795733112" + String.format("%09d", ++seq) + "-" + (7000 + seq);
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

    private static final String KILL_SELF = "kill -9 $$";

    private String bump(int to) {
        return "echo 'oom_kill " + to + "' > " + vmstat + "\n";
    }

    // ---- bb-build writes the kernel's evidence into the ticket ------------------------------------------------------
    @Test
    void theTicketCarriesTheCountersOfItsOwnWindow() throws Exception {
        var meminfo = Files.writeString(dir.resolve("meminfo-1"), "MemTotal: 99 kB\nMemAvailable:   1234 kB\n");
        var id = start("window", bump(6) + "exit 1", "", Map.of("BB_TEST_MEMINFO", meminfo.toString()));

        finished(id);
        assertThat(field(id, "oom_kill_start")).isEqualTo("5");
        assertThat(field(id, "oom_kill_end")).isEqualTo("6");
        assertThat(field(id, "mem_avail_kb_start")).isEqualTo("1234");
        assertThat(field(id, "mem_avail_kb_end")).isEqualTo("1234");
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

    @Disabled("lane131c: printers")
    @Test
    void unreadableCounters_areLabelledKilled_neverOomKilled() throws Exception {
        var id = start("blind2", KILL_SELF, "", Map.of("BB_TEST_VMSTAT", dir.resolve("no-such-vmstat").toString()));

        finished(id);
        var out = tool("bb-result", id);

        assertThat(out.stdout()).contains("rc=137").contains("outcome: KILLED").contains("unavailable").doesNotContain("OOM-KILLED");
    }

    // ---- bb-result's label ----------------------------------------------------------------------------------------------
    @Disabled("lane131c: printers")
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

    @Disabled("lane131c: printers")
    @Test
    void aSigkillWithNoKernelOomKill_isKilled_notOom() throws Exception {
        var id = start("kill9", KILL_SELF, "", Map.of());

        finished(id);
        var text = tool("bb-result", id);

        assertThat(text.stdout()).contains("rc=137").contains("outcome: KILLED").contains("no kernel OOM kill").doesNotContain("OOM-KILLED");
        assertThat(tool("bb-result", id, "--json").stdout()).contains("\"outcome\": \"KILLED\"");
    }

    @Disabled("lane131c: printers")
    @Test
    void twoConcurrentTickets_oneOomKilledOneExitingOne_onlyTheKilledOneIsOomKilled() throws Exception {
        var flag = dir.resolve("a-done");
        var failing = start("exit1",
                            "until [ -f " + flag + " ]; do sleep 0.1; done\nexit 1",
                            "",
                            Map.of());
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

    @Disabled("lane131c: printers")
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

    @Disabled("lane131c: printers")
    @Test
    void aBoundKillInAWindowWhereAnotherProcessWasOomKilled_isLabelledKilledByTheBound_notOom() throws Exception {
        var id = start("bound2", bump(6) + "sleep 60", "bound=2\n", Map.of());

        finished(id);
        var out = tool("bb-result", id).stdout();

        assertThat(out).contains("rc=124").contains("outcome: KILLED").contains("bound of 2s").doesNotContain("OOM-KILLED");
        assertThat(tool("bb-result", id, "--json").stdout()).contains("\"outcome\": \"KILLED\"");
    }

    @Disabled("lane131c: printers")
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
    @Disabled("lane131c: printers")
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
        }

        for (var printer : List.<List<String>>of(List.of("bb-wait", failed), List.of("bb-await", "--ticket", failed))) {
            var out = tool(printer.get(0), printer.subList(1, printer.size()).toArray(String[]::new));

            assertThat(out.rc()).isEqualTo(1);
            assertThat(out.stdout()).contains("rc=1").doesNotContain("outcome");
        }
    }
}
