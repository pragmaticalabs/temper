package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// The build verdict is the ticket's `rc=` (the build process's exit status taken by the waiter), not the checkout's `.bb/rc`
/// (bbrc1, 2026-11-12). Real `bb-build __run` waiters with a stub maven; a `flock` shim parks the waiter between the end of the build
/// and the finalizing of its ticket, so a test can delete or rewrite `.bb/rc` in exactly that window. `bb-temper wait|report` run
/// against an `ssh` stub that executes the remote command locally.
///
/// Probes: read the ticket rc from `.bb/rc` again in bb-build -> [#fileDeletedMidFinalize_ticketKeepsTheExitStatus_andBbWaitReportsTheMismatch]
/// red; drop the mismatch check in bb-wait -> the two bb-wait mismatch tests red; drop `chmod 444` in write_rc ->
/// [#theConvenienceCopy_isReadOnly_andAnUntamperedRunWaitsCleanly] red; replace write_rc's rename by a `>` redirection ->
/// [#aReadOnlyRc_doesNotBreakTheNextBuildInTheSameCheckout] and the expired/cancelled writer tests red; make bb-temper read `.bb/rc`
/// -> the bb-temper source=ticket tests red; drop bb-temper's mismatch exit -> the bb-temper mismatch tests red.
class BbRcTicketRecordIT {
    private static final String ME = "rct";

    /// CTO requirement (#45): what a lane is told on exit 71. Pinned at all three sites: bb-wait, bb-temper wait, bb-temper report.
    private static final String ADVICE = "the ticket record is the verdict; re-run the build in a fresh checkout; never edit or restore .bb/rc";

    private static final Pattern RC_LINE = Pattern.compile("^rc=(\\d+) wall=-?\\d+s$", Pattern.MULTILINE);

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private Path shim;
    private final List<Process> started = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock, pgrep and GNU tools");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        for (var script : List.of("bb-build", "bb-queue", "bb-wait")) {
            BbFixtures.executable(tools.resolve(script),
                                  Files.readString(BbFixtures.OPS_BB.resolve(script)));
        }

        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        shim = dir.resolve("shim");
        BbFixtures.executable(shim.resolve("flock"),
                              "#!/bin/bash\nif [ \"$1\" = 8 ] && [ -f \"$SHIM_RC\" ]; then touch \"$SHIM_RC.blocked\";"
                             + " until [ -f \"$SHIM_GO\" ]; do sleep 0.1; done; fi\nexec \"$SHIM_FLOCK\" \"$@\"\n");
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
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_LOADAVG",
                dir.resolve("loadavg").toString());
        env.put("BB_PORT_GROUP", "0");
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

    private Path ticket(String state, String id, Path co, int cap, int limit) throws IOException {
        return ticket(state, id, co, cap, limit, "-B test");
    }

    private Path ticket(String state, String id, Path co, int cap, int limit, String args) throws IOException {
        return Files.writeString(queue.resolve(state).resolve(id),
                                 "id=" + id
                                + "\nowner=" + ME
                                + "\nco=" + co
                                + "\nsha=abc\nkey=\ncap=" + cap
                                + "\nlimit=" + limit
                                + "\ncreated=" + (System.currentTimeMillis() * 1_000_000L)
                                + "\ngroup=0\nargs=" + args + " \n");
    }

    private String field(String state, String id, String key) throws IOException {
        return Files.readAllLines(queue.resolve(state).resolve(id))
                    .stream()
                    .filter(l -> l.startsWith(key + "="))
                    .reduce((a, b) -> b)
                    .map(l -> l.substring(key.length() + 1))
                    .orElse("");
    }

    private static String mode(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    private Process waiter(String id, String mvnBody, Map<String, String> more, Path go) throws Exception {
        BbFixtures.executable(dir.resolve("stubmvn"), "#!/bin/sh\n" + mvnBody + "\n");
        var env = env(more);

        env.put("BB_TEST_MVN",
                dir.resolve("stubmvn").toString());
        env.put("BB_MAX_REACTORS", "5");
        env.put("SHIM_GO", go.toString());
        env.put("SHIM_FLOCK",
                Stream.of(System.getenv("PATH").split(":"))
                      .map(d -> Path.of(d, "flock"))
                      .filter(Files::isExecutable)
                      .findFirst()
                      .orElseThrow()
                      .toString());
        env.put("PATH", shim + ":" + env.get("PATH"));
        var builder = BbFixtures.processClean(List.of("bash",
                                                      tools.resolve("bb-build").toString(),
                                                      "__run",
                                                      id),
                                              env);

        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();

        started.add(process);

        return process;
    }

    /// A waiter whose build (a stub maven exiting `mvnExit`) has finished and written its `.bb/rc` copy, parked at its final lock.
    private Process parked(String id, Path co, int mvnExit, Path go) throws Exception {
        var rc = co.resolve(".bb/rc");
        var process = waiter(id,
                             "exit " + mvnExit,
                             Map.of("SHIM_RC", rc.toString()),
                             go);

        for (var i = 0; i < 200 && !Files.exists(co.resolve(".bb/rc.blocked")); i++) {
            Thread.sleep(50);
        }

        assertThat(co.resolve(".bb/rc.blocked")).as("waiter parked at its final lock").exists();

        return process;
    }

    private void release(Process waiter, Path go) throws Exception {
        Files.createFile(go);
        assertThat(waiter.waitFor(20, TimeUnit.SECONDS)).as("waiter exits").isTrue();
    }

    private BbFixtures.Outcome tool(String script, Map<String, String> more, String... args) throws Exception {
        var command = new ArrayList<String>(List.of("bash",
                                                    tools.resolve(script).toString()));

        command.addAll(List.of(args));

        return BbFixtures.run(command, env(more), null, null);
    }

    // ---- bb-build / bb-wait --------------------------------------------------------------------------------------
    @Test
    void fileDeletedMidFinalize_ticketKeepsTheExitStatus_andBbWaitReportsTheMismatch() throws Exception {
        var co = checkout("rct-a");
        var id = "100-" + BbFixtures.RUN;
        var go = dir.resolve("go");

        ticket("waiting", id, co, 5, 0);
        var waiter = parked(id, co, 3, go);

        Files.delete(co.resolve(".bb/rc"));
        release(waiter, go);
        assertThat(field("done", id, "state")).isEqualTo("finished");
        assertThat(field("done", id, "rc")).as("the exit status of the build, not 'unknown' from a missing file")
                  .isEqualTo("3");
        var wait = tool("bb-wait", Map.of(), id);

        assertThat(wait.rc()).as(wait.stdout() + wait.stderr()).isEqualTo(71);
        assertThat(wait.stderr()).contains("RC-MISMATCH ticket=3 file=absent");
        assertThat(wait.stderr()).contains(ADVICE);
    }

    @Test
    void fileRewrittenMidFinalize_ticketKeepsTheExitStatus_andBbWaitReportsTheMismatch() throws Exception {
        var co = checkout("rct-b");
        var id = "200-" + BbFixtures.RUN;
        var go = dir.resolve("go");

        ticket("waiting", id, co, 5, 0);
        var waiter = parked(id, co, 3, go);

        Files.delete(co.resolve(".bb/rc"));
        Files.writeString(co.resolve(".bb/rc"), "0\n");
        release(waiter, go);
        assertThat(field("done", id, "rc")).isEqualTo("3");
        var wait = tool("bb-wait", Map.of(), id);

        assertThat(wait.rc()).as(wait.stdout() + wait.stderr()).isEqualTo(71);
        assertThat(wait.stderr()).contains("RC-MISMATCH ticket=3 file=0");
        assertThat(wait.stdout()).as("the one summary line still carries the ticket's rc").contains("rc=3");
    }

    /// vbt8 L2 (CTO ruling R3: the refusal replaces the verdict everywhere). Probe: drop the `refused:` echo in bb-wait -> red.
    @Test
    void bbWait_ofARefusedTicket_printsRefusedAndItsReason_notAPlainRc15() throws Exception {
        var co = checkout("rct-r");
        var id = "150-" + BbFixtures.RUN;

        Files.writeString(ticket("done", id, co, 5, 0),
                          "state=finished\nstarted=100\nended=105\nrc=15\nrefused=timeout: the stale-jar check did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed\nverdict_source=guard\n",
                          java.nio.file.StandardOpenOption.APPEND);
        var wait = tool("bb-wait", Map.of(), id);

        assertThat(wait.rc()).as(wait.stdout() + wait.stderr()).isEqualTo(15);
        assertThat(wait.stdout()).contains("ticket=" + id + " state=finished rc=15 ", "\nrefused: timeout: the stale-jar check did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed");
    }

    @Test
    void theConvenienceCopy_isReadOnly_andAnUntamperedRunWaitsCleanly() throws Exception {
        var co = checkout("rct-c");
        var id = "300-" + BbFixtures.RUN;
        var go = dir.resolve("go");

        ticket("waiting", id, co, 5, 0);
        var waiter = parked(id, co, 3, go);

        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).isEqualTo("3");
        assertThat(mode(co.resolve(".bb/rc"))).isEqualTo("r--r--r--");
        release(waiter, go);
        var wait = tool("bb-wait", Map.of(), id);

        assertThat(wait.rc()).as(wait.stdout() + wait.stderr()).isEqualTo(3);
        assertThat(wait.stderr()).doesNotContain("RC-MISMATCH");
        assertThat(wait.stdout().lines().findFirst()).as("reap's launch count (#90) never precedes the ticket= line: " + wait.stdout())
                                                    .hasValueSatisfying(line -> assertThat(line).startsWith("ticket=" + id + " "));
        assertThat(mode(co.resolve(".bb/rc"))).isEqualTo("r--r--r--");
    }

    @Test
    void aReadOnlyRc_doesNotBreakTheNextBuildInTheSameCheckout() throws Exception {
        var co = checkout("rct-d");
        var first = "400-" + BbFixtures.RUN;
        var go = dir.resolve("go");

        ticket("waiting", first, co, 5, 0);
        release(parked(first, co, 3, go), go);
        assertThat(mode(co.resolve(".bb/rc"))).isEqualTo("r--r--r--");
        // the enqueue path removes the old copy (rm -f works through directory permission)
        var enqueue = tool("bb-build", Map.of("BB_MAX_REACTORS", "0", "BB_DEDUP", "0"), co.toString(), "-B", "again");

        assertThat(enqueue.rc()).as(enqueue.stdout() + enqueue.stderr()).isZero();
        assertThat(co.resolve(".bb/rc")).doesNotExist();
        var second = Files.readString(co.resolve(".bb/queued")).strip();

        BbFixtures.kill(BbFixtures.waiters(second));
        Files.deleteIfExists(queue.resolve("waiting").resolve(second));
        // and the next granted build launches over a leftover 444 file and finishes with a fresh 444 copy
        Files.writeString(co.resolve(".bb/rc"), "3\n");
        Files.setPosixFilePermissions(co.resolve(".bb/rc"), PosixFilePermissions.fromString("r--r--r--"));
        var third = "500-" + BbFixtures.RUN;

        ticket("waiting", third, co, 5, 0);
        var waiter = waiter(third, "exit 0", Map.of(), dir.resolve("go-none"));

        assertThat(waiter.waitFor(30, TimeUnit.SECONDS)).as("waiter exits").isTrue();
        assertThat(field("done", third, "rc")).isEqualTo("0");
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).isEqualTo("0");
        assertThat(mode(co.resolve(".bb/rc"))).isEqualTo("r--r--r--");
    }

    /// CTO 2026-11-13: lanes deleted `.bb/rc` to "reset" a checkout. bb-build then refused with "a build appears to be running", which
    /// told them nothing. Once the build's ticket is in done/ (`.bb/ticket` names it) and no waiting/running ticket names the
    /// checkout, the refusal names the hand deletion, the ticket and what to do instead; a live ticket or no ticket at all keeps the
    /// running message. Probes: drop the done/ lookup -> red; drop the live-ticket check -> the running-ticket half red; drop the
    /// state=finished check -> the orphaned/cancelled half red.
    @Test
    void aDeletedRc_ofAFinishedTicket_isRefusedAsAHandDeletion_whileALiveTicketStillReadsAsRunning() throws Exception {
        var co = checkout("rct-g");
        var id = "1100-" + BbFixtures.RUN;

        ticket("waiting", id, co, 5, 0);
        var waiter = waiter(id, "exit 0", Map.of(), dir.resolve("go-none"));

        assertThat(waiter.waitFor(30, TimeUnit.SECONDS)).as("waiter exits").isTrue();
        assertThat(field("done", id, "state")).isEqualTo("finished");
        Files.delete(co.resolve(".bb/rc"));
        var rerun = tool("bb-build", Map.of("BB_MAX_REACTORS", "0", "BB_DEDUP", "0"), co.toString(), "-B", "again");

        assertThat(rerun.rc()).as(rerun.stdout() + rerun.stderr()).isEqualTo(3);
        assertThat(rerun.stderr()).contains(".bb/rc in " + co + " was deleted by hand after ticket " + id + " finished")
                  .contains("never rm it")
                  .contains("bb-wait " + id)
                  .contains("rerun bb-build in a fresh checkout")
                  .doesNotContain("appears to be running");
        assertThat(co.resolve(".bb/queued")).doesNotExist();
        // the same state while a ticket naming this checkout is running: a build may really be running, so the old message stays
        var live = "1200-" + BbFixtures.RUN;

        ticket("running", live, co, 5, 0);
        var running = tool("bb-build", Map.of("BB_MAX_REACTORS", "0", "BB_DEDUP", "0"), co.toString(), "-B", "again");

        assertThat(running.rc()).as(running.stdout() + running.stderr()).isEqualTo(3);
        assertThat(running.stderr()).contains("a build appears to be running in " + co)
                  .doesNotContain("deleted by hand");
        // and with no ticket for this .bb/start (e.g. a BB_QUEUE=0 build): the running message, never a guessed deletion
        Files.delete(queue.resolve("running").resolve(live));
        Files.writeString(co.resolve(".bb/start"), "1\n");
        Files.delete(co.resolve(".bb/ticket"));   // #80: a run with no ticket (BB_QUEUE=0, or from before #80) is never blamed on a done/ ticket
        var ticketless = tool("bb-build", Map.of("BB_MAX_REACTORS", "0", "BB_DEDUP", "0"), co.toString(), "-B", "again");

        assertThat(ticketless.rc()).as(ticketless.stdout() + ticketless.stderr()).isEqualTo(3);
        assertThat(ticketless.stderr()).contains("a build appears to be running in " + co)
                  .doesNotContain("deleted by hand");
        // v50a: an orphaned (reaped) or cancelled run carries started= too, but its killed build may never have written .bb/rc, so its
        // done/ ticket is not evidence of a hand deletion; only state=finished with a numeric rc= is
        for (var state : List.of("orphaned", "cancelled")) {
            var gone = ticket("done", state + "-" + BbFixtures.RUN, co, 5, 0);

            Files.writeString(gone, "started=1\nstate=" + state + "\n", java.nio.file.StandardOpenOption.APPEND);
            Files.writeString(co.resolve(".bb/ticket"), gone.getFileName() + "\n");   // #80: .bb/ticket names the run that owns .bb
            var killed = tool("bb-build", Map.of("BB_MAX_REACTORS", "0", "BB_DEDUP", "0"), co.toString(), "-B", "again");

            assertThat(killed.rc()).as(state + ": " + killed.stdout() + killed.stderr()).isEqualTo(3);
            assertThat(killed.stderr()).as(state)
                      .contains("a build appears to be running in " + co)
                      .doesNotContain("deleted by hand");
            Files.delete(gone);
        }
    }

    /// A running ticket whose waiter is gone, whose checkout holds a plain `.bb/rc` (`fileRc`) written for the ticket's own run.
    private Path runningWithDeadWaiter(String name, String id, String fileRc) throws Exception {
        var co = checkout(name);
        var t = ticket("running", id, co, 5, 0);

        Files.writeString(t, Files.readString(t) + "started=7\n");
        Files.writeString(co.resolve(".bb/start"), "7\n");
        Files.writeString(co.resolve(".bb/end"), "9\n");
        Files.writeString(co.resolve(".bb/rc"), fileRc + "\n");

        return co;
    }

    /// vt42 finding 1, path 1: reap of a ticket whose waiter died must not take the rc from `.bb/rc`.
    @Test
    void reapOfATicketWhoseWaiterDied_isOrphanedWithNoRc_neverTheFilesValue() throws Exception {
        var id = "600-" + BbFixtures.RUN;

        runningWithDeadWaiter("rct-r1", id, "0");
        var reap = tool("bb-queue", Map.of(), "reap");

        assertThat(reap.rc()).as(reap.stdout() + reap.stderr()).isZero();
        assertThat(field("done", id, "state")).isEqualTo("orphaned");
        assertThat(field("done", id, "rc")).as("the file's 0 is not a verdict").isEmpty();
        var wait = tool("bb-wait", Map.of(), id);

        assertThat(wait.rc()).as(wait.stdout() + wait.stderr()).isEqualTo(70);
        assertThat(wait.stderr()).doesNotContain("RC-MISMATCH");
    }

    /// vt42 item 3: bb-mutate on a per-mutant timeout kills the job, writes `.bb/rc`=124 itself, then runs `bb-queue reap`. The ticket is
    /// orphaned while the file says 124: bb-wait exits 70 (never 71, which is for finished tickets with a numeric rc), and the next
    /// bb-build in that checkout is accepted (the file exists, so no "build appears to be running" refusal).
    @Test
    void bbMutateTimeoutSequence_killPlus124PlusReap_isOrphaned_waitIs70Not71_andTheNextBuildIsAccepted() throws Exception {
        var id = "700-" + BbFixtures.RUN;
        var co = runningWithDeadWaiter("rct-r2", id, "124");
        var reap = tool("bb-queue", Map.of(), "reap");

        assertThat(reap.rc()).as(reap.stdout() + reap.stderr()).isZero();
        assertThat(field("done", id, "state")).isEqualTo("orphaned");
        assertThat(field("done", id, "rc")).isEmpty();
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).as("bb-mutate's own 124 is left for its poll")
                  .isEqualTo("124");
        var wait = tool("bb-wait", Map.of(), id);

        assertThat(wait.rc()).as(wait.stdout() + wait.stderr()).isEqualTo(70);
        assertThat(wait.stderr()).doesNotContain("RC-MISMATCH");
        var next = tool("bb-build", Map.of("BB_MAX_REACTORS", "0", "BB_DEDUP", "0"), co.toString(), "-B", "again");

        assertThat(next.rc()).as(next.stdout() + next.stderr()).isZero();
        BbFixtures.kill(BbFixtures.waiters(Files.readString(co.resolve(".bb/queued")).strip()));
    }

    /// 71 is for finished tickets with a numeric rc only: an orphaned or cancelled ticket next to a disagreeing, matching-start file stays 70 / 9.
    @Test
    void rcMismatch_neverFiresForAnOrphanedOrCancelledTicket() throws Exception {
        var co = checkout("rct-r3");
        var t = ticket("done", "800-" + BbFixtures.RUN, co, 5, 0);

        Files.writeString(t, Files.readString(t) + "started=7\nstate=orphaned\nended=9\n");
        var u = ticket("done", "900-" + BbFixtures.RUN, co, 5, 0);

        Files.writeString(u, Files.readString(u) + "started=7\nstate=cancelled\nended=9\n");
        Files.writeString(co.resolve(".bb/start"), "7\n");
        Files.writeString(co.resolve(".bb/rc"), "0\n");
        var orphaned = tool("bb-wait", Map.of(), "800-" + BbFixtures.RUN);
        var cancelled = tool("bb-wait", Map.of(), "900-" + BbFixtures.RUN);

        assertThat(orphaned.rc()).as(orphaned.stdout() + orphaned.stderr()).isEqualTo(70);
        assertThat(cancelled.rc()).as(cancelled.stdout() + cancelled.stderr()).isEqualTo(9);
        assertThat(orphaned.stderr() + cancelled.stderr()).doesNotContain("RC-MISMATCH");
    }

    @Test
    void expiredAndCancelledWriters_replaceAReadOnlyRc() throws Exception {
        var co = checkout("rct-e");
        var stale = co.resolve(".bb/rc");

        Files.writeString(stale, "3\n");
        Files.setPosixFilePermissions(stale, PosixFilePermissions.fromString("r--r--r--"));
        // expired: cap 0 never grants, limit 5 s
        var expired = "600-" + BbFixtures.RUN;

        ticket("waiting", expired, co, 0, 5);
        var waiter = waiter(expired, "exit 0", Map.of(), dir.resolve("go-none"));

        assertThat(waiter.waitFor(40, TimeUnit.SECONDS)).as("waiter expires").isTrue();
        assertThat(field("done", expired, "state")).isEqualTo("expired");
        assertThat(Files.readString(stale).strip()).isEqualTo("5");
        assertThat(mode(stale)).isEqualTo("r--r--r--");
        // cancelled while waiting
        Files.delete(stale);
        Files.writeString(stale, "3\n");
        Files.setPosixFilePermissions(stale, PosixFilePermissions.fromString("r--r--r--"));
        var cancelled = "700-" + BbFixtures.RUN;

        ticket("waiting", cancelled, co, 5, 0);
        var cancel = tool("bb-queue", Map.of(), "cancel", cancelled);

        assertThat(cancel.rc()).as(cancel.stdout() + cancel.stderr()).isZero();
        assertThat(field("done", cancelled, "state")).isEqualTo("cancelled");
        assertThat(Files.readString(stale).strip()).isEqualTo("9");
        assertThat(mode(stale)).isEqualTo("r--r--r--");
        // cancelled while running, over the stale copy of an earlier run
        Files.delete(stale);
        Files.writeString(stale, "0\n");
        Files.setPosixFilePermissions(stale, PosixFilePermissions.fromString("r--r--r--"));
        Files.writeString(co.resolve(".bb/start"), "3\n");
        var running = "800-" + BbFixtures.RUN;

        Files.writeString(queue.resolve("running").resolve(running),
                          "id=" + running
                         + "\nowner=" + ME
                         + "\nco=" + co
                         + "\nsha=abc\nkey=\ncap=5\nlimit=0\ncreated=1\ngroup=0\nargs=-B test \nstarted=7\n");
        var cancelRunning = tool("bb-queue", Map.of(), "cancel", "--running", running);

        assertThat(cancelRunning.rc()).as(cancelRunning.stdout() + cancelRunning.stderr()).isZero();
        assertThat(field("done", running, "state")).isEqualTo("cancelled");
        assertThat(Files.readString(stale).strip()).isEqualTo("9");
        assertThat(mode(stale)).isEqualTo("r--r--r--");
    }

    // ---- bb-temper wait / report ---------------------------------------------------------------------------------
    private Path home;
    private Path co;

    private void temperSandbox() throws Exception {
        home = Files.createDirectories(dir.resolve("th"));
        TestHosts.write(home);
        var bin = Files.createDirectories(dir.resolve("bin"));

        MacScripts.executable(bin.resolve("ssh"), "exec bash -c \"${@: -1}\"\n");
        Files.createDirectories(home.resolve(".temper/bb-bind"));
        Files.writeString(home.resolve(".temper/bb-bind/rct-x"), "bigboy\n");
        co = Files.createDirectories(home.resolve("work/co/rct-x/.bb"));
        co = co.getParent();
        Files.writeString(co.resolve(".bb/build.log"), "BUILD FAILURE\n");
        Files.writeString(co.resolve(".bb/start"), "100\n");
        Files.writeString(co.resolve(".bb/end"), "130\n");
        Files.createFile(co.resolve(".bb/run.marker"));
        Files.createDirectories(dir.resolve("tq"));
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(dir.resolve("tq").resolve(d));
        }
    }

    private void tq(String state, String id, String body) throws IOException {
        Files.writeString(dir.resolve("tq").resolve(state).resolve(id),
                          "id=" + id + "\nowner=rct\nco=" + co + "\n" + body);
    }

    private MacScripts.Result temper(String verb) throws Exception {
        var env = MacScripts.env(Map.of("HOME",
                                        home.toString(),
                                        "PATH",
                                        dir.resolve("bin") + ":/usr/bin:/bin",
                                        "BB_OWNER",
                                        ME,
                                        "BB_TEST_QUEUE_ROOT",
                                        dir.resolve("tq").toString()));

        return MacScripts.run(List.of("bash", MacScripts.BB_TEMPER.toString(), verb, "rct-x"),
                              env,
                              dir);
    }

    private void fileRc(String content) throws IOException {
        var f = co.resolve(".bb/rc");

        Files.deleteIfExists(f);
        if (content != null) {
            Files.writeString(f, content + "\n");
        }
    }

    @Test
    void report_takesTheRcFromTheNewestTicket_andKeepsTheLegacyLinesAndAddsSource() throws Exception {
        temperSandbox();
        tq("done", "1000000000000000001-1", "state=finished\nrc=1\nended=130\n");
        tq("done", "1000000000000000002-1", "state=finished\nrc=3\nended=130\n");
        fileRc("3");
        var run = temper("report");

        assertThat(run.rc()).as(run.err()).isZero();
        var lines = run.out().lines().toList();

        assertThat(lines.get(0)).isEqualTo("rc=3 wall=30s");
        assertThat(lines).contains("source=ticket ticket=1000000000000000002-1");
        assertThat(lines.get(lines.size() - 1)).startsWith("known on Linux:");
        assertThat(RC_LINE.matcher(run.out()).find()).isTrue();
    }

    @Test
    void report_withTheFileDeletedOrRewritten_printsTheTicketRc_andFailsWith71() throws Exception {
        temperSandbox();
        tq("done", "1000000000000000002-1", "state=finished\nrc=3\nended=130\n");
        fileRc(null);
        var absent = temper("report");

        fileRc("0");
        var rewritten = temper("report");

        assertThat(absent.rc()).as(absent.err()).isEqualTo(71);
        assertThat(absent.out()).startsWith("rc=3 wall=");
        assertThat(absent.err()).contains("RC-MISMATCH ticket=3 file=absent");
        assertThat(rewritten.rc()).isEqualTo(71);
        assertThat(rewritten.out()).startsWith("rc=3 wall=");
        assertThat(rewritten.err()).contains("RC-MISMATCH ticket=3 file=0");
        assertThat(rewritten.err()).contains(ADVICE);
        assertThat(absent.err()).contains(ADVICE);
    }

    @Test
    void report_ofACheckoutWithNoTicket_readsTheFile_andSaysSourceFile() throws Exception {
        temperSandbox();
        fileRc("0");
        var withFile = temper("report");

        fileRc(null);
        var without = temper("report");

        assertThat(withFile.rc()).as(withFile.err()).isZero();
        assertThat(withFile.out()).startsWith("rc=0 wall=").contains("source=file");
        assertThat(withFile.err()).doesNotContain("RC-MISMATCH");
        assertThat(without.out()).startsWith("rc=running wall=").contains("source=file");
    }

    @Test
    void report_ofACheckoutWhoseNewestTicketIsRunning_isRunning_notTheOlderDoneRc() throws Exception {
        temperSandbox();
        tq("done", "1000000000000000001-1", "state=finished\nrc=0\nended=130\n");
        tq("running", "1000000000000000005-1", "started=140\n");
        fileRc(null);
        var run = temper("report");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).startsWith("rc=running wall=").contains("source=ticket ticket=1000000000000000005-1");
    }

    @Test
    void report_ofACancelledTicket_isNineAndAgreesWithTheFile() throws Exception {
        temperSandbox();
        tq("done", "1000000000000000002-1", "state=cancelled\nended=130\n");
        fileRc("9");
        var run = temper("report");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).startsWith("rc=9 wall=");
    }

    @Test
    void wait_returnsTheTicketRc_withSourceTicket_andExits71OnADisagreeingFile() throws Exception {
        temperSandbox();
        tq("done", "1000000000000000002-1", "state=finished\nrc=3\nended=130\n");
        fileRc("3");
        var agree = temper("wait");

        fileRc("0");
        var disagree = temper("wait");

        fileRc(null);
        var absent = temper("wait");

        assertThat(agree.rc()).as(agree.err()).isEqualTo(3);
        assertThat(agree.out()).contains("source=ticket ticket=1000000000000000002-1").contains("rc=3");
        assertThat(disagree.rc()).as(disagree.err()).isEqualTo(71);
        assertThat(disagree.err()).contains("RC-MISMATCH ticket=3 file=0");
        assertThat(disagree.err()).contains(ADVICE);
        assertThat(absent.rc()).isEqualTo(71);
        assertThat(absent.err()).contains("RC-MISMATCH ticket=3 file=absent");
    }

    /// vbt8 L2: bb-temper wait and report on a refused ticket carry `refused: <reason>`. Probe: drop either echo in bb-temper -> that half red.
    @Test
    void waitAndReport_ofARefusedTicket_printRefusedAndItsReason() throws Exception {
        temperSandbox();
        tq("done", "1000000000000000002-1", "state=finished\nrc=15\nended=130\nrefused=timeout: the stale-jar check did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed\nverdict_source=guard\n");
        fileRc("15");
        var wait = temper("wait");
        var report = temper("report");

        assertThat(wait.rc()).as(wait.err()).isEqualTo(15);
        assertThat(wait.out()).contains("rc=15", "refused: timeout: the stale-jar check did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed");
        assertThat(report.out()).contains("rc=15", "refused: timeout: the stale-jar check did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed");
    }

    @Test
    void wait_ofACheckoutWithNoTicket_usesTheFile_andSaysSourceFile() throws Exception {
        temperSandbox();
        fileRc("4");
        var run = temper("wait");

        assertThat(run.rc()).as(run.err()).isEqualTo(4);
        assertThat(run.out()).contains("source=file").contains("rc=4");
    }

    // ---- launch fields (#76, CTO ruling 2026-11-21): the container writes .bb/fields.<id>; the waiter copies only the grammar ------------

    /// The launch a bb-test ticket runs (`__launch <module> <repeat> <Class>`), and the one passing report it writes into the ticket's
    /// report dir: since #86 the waiter counts a launch's executed testcases, and a launch that executed none is refused rc 19, so the
    /// rows below that test field recording at rc 0 must have run one testcase.
    private static final String LAUNCH_ARGS = "__launch m 1 LfIT";
    private static final String ONE_PASSING_REPORT = "mkdir -p m/target/bb-test-reports/$BB_TICKET"
                                                     + " && printf '<testsuite><testcase classname=\"LfIT\" name=\"t\"/></testsuite>\\n'"
                                                     + " > m/target/bb-test-reports/$BB_TICKET/TEST-LfIT.xml; ";

    /// A ticket whose job is `body` run in its checkout, waited to done/. `launch`: a BB_LAUNCH ticket (what bb-test enqueues), whose
    /// job first writes one passing report.
    private String launched(String id, Path co, String body, boolean launch) throws Exception {
        return launched(id, co, body, launch, Map.of());
    }

    private String launched(String id, Path co, String body, boolean launch, Map<String, String> extra) throws Exception {
        return launched(id, co, launch ? ONE_PASSING_REPORT + body : body, launch, extra, launch ? LAUNCH_ARGS : "-B test");
    }

    private String launched(String id, Path co, String body, boolean launch, Map<String, String> extra, String args) throws Exception {
        ticket("waiting", id, co, 5, 0, args);
        var more = new HashMap<>(extra);

        if (launch) {
            more.put("BB_LAUNCH", dir.resolve("stubmvn").toString());
        }
        var waiter = waiter(id, body, more, dir.resolve("go-" + id));

        assertThat(waiter.waitFor(30, TimeUnit.SECONDS)).as("waiter exits").isTrue();
        assertThat(co.resolve(".bb/fields." + id)).as("the fields file is removed after the read").doesNotExist();

        return Files.readString(queue.resolve("done").resolve(id));
    }

    @Test
    void launchFields_forgedRcCoAndAnOffGrammarVerdict_areNotCopied_andTheTicketRecordsTheRejection_neverAPass() throws Exception {
        var co = checkout("lf-forged");
        var id = "500-" + BbFixtures.RUN;
        var text = launched(id, co, "printf 'rc=0\\nco=/x\\nverdict_source=pwned\\n' > .bb/fields.$BB_TICKET; exit 0", true);

        assertThat(text).doesNotContain("co=/x", "verdict_source=pwned");
        assertThat(text.lines().filter(l -> l.startsWith("rc="))).as("one rc= line, the waiter's").hasSize(1);
        assertThat(field("done", id, "co")).isEqualTo(co.toString());
        assertThat(field("done", id, "fields_rejected")).isEqualTo("rc,co,verdict_source");
        assertThat(field("done", id, "verdict_source")).isEqualTo("missing");
        assertThat(field("done", id, "rc")).as("a rejection is never a pass: java's 0 is recorded as 18").isEqualTo("18");
    }

    @Test
    void launchFields_inGrammar_areCopiedJustBeforeTheRc() throws Exception {
        var co = checkout("lf-ok");
        var java = launched("510-" + BbFixtures.RUN, co, "printf 'verdict_source=java\\n' > .bb/fields.$BB_TICKET; exit 0", true);

        assertThat(java).contains("\nverdict_source=java\nstate=finished\nrc=0\n").doesNotContain("fields_rejected");
        var max = "r".repeat(1024);
        var refused = launched("511-" + BbFixtures.RUN, co,
                               "printf 'refused=" + max + "\\nverdict_source=guard\\n' > .bb/fields.$BB_TICKET; exit 15", true);

        assertThat(refused).contains("\nrefused=" + max + "\nverdict_source=guard\nstate=finished\nrc=15\n").doesNotContain("fields_rejected");
    }

    /// The zero-testcase gate's effect on the record (#86): the same in-grammar launch as above, but it writes no report, so its
    /// selected class executed 0 testcases. The fields are still copied, and the ticket records rc 19, never java's 0.
    /// Mutation: drop the launch count from bb-build's job tail -> rc=0 recorded, red.
    @Test
    void launchFields_aLaunchThatExecutedNoTestcase_recordsRc19_withItsFieldsCopied() throws Exception {
        var co = checkout("lf-zero");
        var id = "515-" + BbFixtures.RUN;
        var text = launched(id, co, "printf 'verdict_source=java\\n' > .bb/fields.$BB_TICKET; exit 0", true, Map.of(), LAUNCH_ARGS);

        assertThat(text).contains("\nverdict_source=java\nstate=finished\nrc=19\n").doesNotContain("fields_rejected");
        assertThat(Files.readString(co.resolve(".bb/build.log"))).contains("[ERROR] bb-build: refused rc 0 -> 19: ");
    }

    @Test
    void launchFields_refusedOffGrammar_isRejected_eachForm() throws Exception {
        var co = checkout("lf-grammar");
        var id = "520-" + BbFixtures.RUN;
        var text = launched(id, co, "printf 'refused==lead\\nrefused=tab\\there\\nrefused=" + "r".repeat(1025)
                                    + "\\nrefused=\\nverdict_source=java\\r\\nverdict_source=guard\\n' > .bb/fields.$BB_TICKET; exit 15", true);

        assertThat(text).doesNotContain("refused=", "verdict_source=java");
        assertThat(field("done", id, "fields_rejected")).isEqualTo("refused,verdict_source");
        assertThat(field("done", id, "verdict_source")).isEqualTo("guard");
        assertThat(field("done", id, "rc")).as("a non-zero rc is kept").isEqualTo("15");
    }

    @Test
    void launchFields_aLaunchThatWritesNothing_recordsAMissingVerdict_neverAPass() throws Exception {
        var co = checkout("lf-missing");
        var zero = "530-" + BbFixtures.RUN;

        launched(zero, co, "exit 0", true);
        assertThat(field("done", zero, "verdict_source")).isEqualTo("missing");
        assertThat(field("done", zero, "rc")).isEqualTo("18");
        var killed = "531-" + BbFixtures.RUN;

        launched(killed, co, "kill -9 $$", true);
        assertThat(field("done", killed, "verdict_source")).isEqualTo("missing");
        assertThat(field("done", killed, "rc")).isEqualTo("137");
    }

    @Test
    void launchFields_aRewrittenTicketMarker_refusesTheCopy() throws Exception {
        var co = checkout("lf-mismatch");
        var id = "540-" + BbFixtures.RUN;
        var text = launched(id, co, "echo other > .bb/ticket; printf 'verdict_source=java\\n' > .bb/fields.$BB_TICKET; exit 0", true);

        assertThat(text).doesNotContain("verdict_source=java");
        assertThat(field("done", id, "fields_rejected")).isEqualTo("ticket_mismatch");
        assertThat(field("done", id, "verdict_source")).isEqualTo("missing");
        assertThat(field("done", id, "rc")).isEqualTo("18");
    }

    @Test
    void launchFields_aTicketMarkerChangedBetweenTheReadAndTheLock_copiesNothing_neverAPass() throws Exception {
        var co = checkout("lf-changed");
        var id = "545-" + BbFixtures.RUN;
        var seam = Map.of("BB_TEST_BEFORE_LOCK", "echo other > '" + co.resolve(".bb/ticket") + "'");
        var text = launched(id, co, "printf 'verdict_source=java\\n' > .bb/fields.$BB_TICKET; exit 0", true, seam);

        assertThat(text).doesNotContain("verdict_source=java");
        assertThat(field("done", id, "fields_rejected")).isEqualTo("ticket_changed");
        assertThat(field("done", id, "verdict_source")).isEqualTo("missing");
        assertThat(field("done", id, "rc")).isEqualTo("18");
    }

    @Test
    void launchFields_anotherTicketsFile_aFifo_andAMavenTicketsFile_areRejected() throws Exception {
        var co = checkout("lf-foreign");
        var foreign = "550-" + BbFixtures.RUN;
        var text = launched(foreign, co, "printf 'verdict_source=java\\n' > .bb/fields.OTHER-1; exit 0", true);

        assertThat(text).doesNotContain("verdict_source=java");
        assertThat(field("done", foreign, "fields_rejected")).isEqualTo("foreign_file");
        assertThat(co.resolve(".bb/fields.OTHER-1")).doesNotExist();
        var fifo = "551-" + BbFixtures.RUN;

        launched(fifo, co, "mkfifo .bb/fields.$BB_TICKET; exit 0", true);
        assertThat(field("done", fifo, "fields_rejected")).isEqualTo("not_regular");
        assertThat(field("done", fifo, "rc")).isEqualTo("18");
        var maven = "552-" + BbFixtures.RUN;
        var mavenText = launched(maven, co, "printf 'verdict_source=java\\n' > .bb/fields.$BB_TICKET; exit 0", false);

        assertThat(mavenText).as("a maven ticket has no verdict_source at all").doesNotContain("verdict_source=");
        assertThat(field("done", maven, "fields_rejected")).isEqualTo("not_launch");
        assertThat(field("done", maven, "rc")).isEqualTo("18");
    }
}
