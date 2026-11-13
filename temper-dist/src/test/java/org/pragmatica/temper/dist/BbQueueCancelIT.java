package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.temper.testsupport.HermeticProcess;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-queue` (`cancel --running`, `release`, `reap`) and the reap/`co_reactor_alive` rules of `ops/bb/bb-queue-lib.sh` run
/// for real against real processes in a temp queue (`BB_TEST_*`). A fake waiter is `bash <dir>/bb-build __run <id>` with children;
/// a checkout process is a `sleep` whose cwd is a subdirectory of the checkout (a surefire fork). The incident this pins: a stopped
/// agent's root build kept the port group for 70-106 min because nothing could cancel a RUNNING ticket.
///
/// Probes (recorded in the PR): drop the descendant walk (tree_of) -> [#cancelRunning_killsExactlyTheWaiterTreeAndTheCheckoutProcesses_freesThePortGroup]
/// red; drop the cwd-below match (equal-only) -> same test red AND [#reap_keepsARunningTicket_whileAForkSitsInASubmodule] red; match
/// by prefix without the slash -> [#cancelRunning_killsExactlyTheWaiterTreeAndTheCheckoutProcesses_freesThePortGroup] red (sibling
/// `<co>-2` killed); drop the owner check -> [#cancelRunning_ofAnotherOwnersTicket_isRefusedNamingTheOwner_andKillsNothing] red; drop
/// the verify-before-rc -> [#cancelRunning_whenAProcessSurvives_leavesTheTicketRunningAndWritesNoRc] red; drop release's running
/// refusal -> [#release_refusesARunningTicket_naming_theTicketAndKill] red; drop reap's vanished-checkout rule ->
/// [#reap_abandonsAWaitingTicketWhoseCheckoutVanished_andStopsItsWaiter] red; drop the start-time identity re-check before a signal ->
/// [#cancelRunning_neverSignalsAPidWhoseIdentityChanged] red; count an identity-changed pid as a survivor -> the same test red; write rc 9 unconditionally -> [#cancelRunning_keepsTheRealRcOfABuildThatAlreadyFinished],
/// [#cancelRunning_keepsTheRealRcWrittenInsideTheKillGrace] red; drop the waiter's `[ -f "$T" ] || exit 0` guard in bb-build ->
/// [#waiterCompletion_afterATooLateCancel_neitherOverwritesTheDoneRecordNorRecreatesTheRunningTicket] red.
class BbQueueCancelIT {
    private static final String ME = "orphtest";
    private static final String RUN = BbFixtures.RUN;

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private Path fake;
    private final List<Process> started = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock, pgrep and procps ps");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        BbFixtures.executable(tools.resolve("bb-queue"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-queue")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        fake = Files.createDirectories(dir.resolve("fake"));
        // the waiter: a subshell with a child, plus a direct child, as `bb-build __run` has while it waits on maven
        Files.writeString(fake.resolve("bb-build"), "#!/bin/bash\n( sleep 600; true ) &\nsleep 601 &\nwait\n");
    }

    @AfterEach
    void cleanUp() {
        for (var process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private Map<String, String> env(String owner, Map<String, String> more) {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", owner);
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.putAll(more);

        return env;
    }

    private BbFixtures.Outcome queue(String owner, Map<String, String> more, String... args) throws Exception {
        var command = new ArrayList<>(List.of("bash",
                                              tools.resolve("bb-queue").toString()));

        command.addAll(List.of(args));

        return BbFixtures.run(command, env(owner, more), null, null);
    }

    private Process spawn(Path cwd, String... command) throws IOException {
        var builder = HermeticProcess.builder(command).directory(cwd.toFile());

        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();

        started.add(process);

        return process;
    }

    private Process waiter(String id, Path cwd) throws Exception {
        var process = spawn(cwd,
                            "bash",
                            fake.resolve("bb-build").toString(),
                            "__run",
                            id);

        for (var i = 0; i < 100 && process.descendants().count() < 3; i++) {
            Thread.sleep(50);
        }

        assertThat(process.descendants().count()).as("waiter children up").isGreaterThanOrEqualTo(3);

        return process;
    }

    private Path checkout(String name) throws IOException {
        return Files.createDirectories(dir.resolve("co").resolve(name).resolve("aether").resolve("ember"));
    }

    private Path ticket(String state, String id, String owner, Path co, int group) throws IOException {
        var root = co;

        while (!root.getParent().getFileName().toString().equals("co")) {
            root = root.getParent();
        }

        return Files.writeString(queue.resolve(state).resolve(id),
                                 "id=" + id
                                + "\nowner=" + owner
                                + "\nco=" + root
                                + "\nsha=abc\nkey=\ncap=5\nlimit=0\ncreated=1\ngroup=" + group
                                + "\nargs=-B test \n" + (state.equals("running")
                                                         ? "started=7\n"
                                                         : ""));
    }

    private static boolean live(long pid) {
        try {
            var stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));

            return stat.charAt(stat.lastIndexOf(')') + 2) != 'Z';
        } catch (IOException e) {
            return false;
        }
    }

    private static Set<Long> tree(Process process) {
        var pids = new TreeSet<Long>();

        pids.add(process.pid());
        process.descendants().forEach(h -> pids.add(h.pid()));

        return pids;
    }

    private String field(String state, String id, String key) throws IOException {
        return Files.readAllLines(queue.resolve(state).resolve(id))
                    .stream()
                    .filter(l -> l.startsWith(key + "="))
                    .reduce((a, b) -> b)
                    .map(l -> l.substring(key.length() + 1))
                    .orElse("");
    }

    private String pick() throws Exception {
        var run = BbFixtures.run(List.of("bash",
                                         "-c",
                                         ". \"$1\"; pick",
                                         "_",
                                         tools.resolve("bb-queue-lib.sh").toString()),
                                 env(ME, Map.of()),
                                 null,
                                 null);

        return run.stdout()
                  .strip();
    }

    @Test
    void cancelRunning_killsExactlyTheWaiterTreeAndTheCheckoutProcesses_freesThePortGroup() throws Exception {
        var co = checkout("co1");
        var root = dir.resolve("co").resolve("co1");
        var id = "100-" + RUN;

        ticket("running", id, ME, co, 1);
        var waiter = waiter(id, dir);
        var waiterTree = tree(waiter);
        var fork = spawn(co, "sleep", "602");  // a surefire fork: cwd in a submodule, no parent link to the waiter
        var forkChild = spawn(co, "bash", "-c", "sleep 603 & wait");  // its own child
        for (var i = 0; i < 100 && forkChild.descendants().count() < 1; i++) {
            Thread.sleep(50);
        }

        var expected = new TreeSet<Long>(waiterTree);

        expected.add(fork.pid());
        expected.addAll(tree(forkChild));
        // unrelated: a similar cmdline (another ticket id), a sibling directory sharing the checkout's name as a prefix, another dir
        var similar = waiter(id + "9", dir);
        var sibling = spawn(Files.createDirectories(dir.resolve("co").resolve("co1-2")),
                            "sleep",
                            "604");
        var elsewhere = spawn(dir, "sleep", "605");

        ticket("waiting", "200-" + RUN, ME, checkout("co2"), 1);
        assertThat(pick()).as("control: the port group is held while the ticket runs").isEmpty();
        var run = queue(ME, Map.of(), "cancel", "--running", id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        Thread.sleep(300);
        for (var pid : expected) {
            assertThat(live(pid)).as("pid " + pid + " is dead").isFalse();
        }

        assertThat(live(similar.pid())).as("similar cmdline").isTrue();
        assertThat(live(sibling.pid())).as("sibling dir with the same prefix").isTrue();
        assertThat(live(elsewhere.pid())).as("unrelated").isTrue();
        assertThat(queue.resolve("running").resolve(id)).doesNotExist();
        assertThat(field("done", id, "state")).isEqualTo("cancelled");
        assertThat(field("done", id, "ended")).isNotEmpty();
        var killed = java.util.Arrays.stream(field("done", id, "killed").split(" "))
                                     .map(Long::parseLong)
                                     .collect(Collectors.toCollection(TreeSet::new));

        assertThat(killed).as("killed= lists the exact pids").isEqualTo(expected);
        assertThat(Files.readString(root.resolve(".bb").resolve("rc")).strip()).isEqualTo("9");
        assertThat(pick()).as("the port group is free right after").isEqualTo("200-" + RUN);
    }

    @Test
    void cancelRunning_ofAnotherOwnersTicket_isRefusedNamingTheOwner_andKillsNothing() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;

        ticket("running", id, "someoneelse", co, 1);
        var waiter = waiter(id, dir);
        var run = queue(ME, Map.of(), "cancel", "--running", id);

        assertThat(run.rc()).isEqualTo(4);
        assertThat(run.stderr()).contains("belongs to someoneelse");
        assertThat(live(waiter.pid())).isTrue();
        assertThat(queue.resolve("running").resolve(id)).exists();
        assertThat(dir.resolve("co").resolve("co1").resolve(".bb").resolve("rc")).doesNotExist();
        // the supported path: a parent runs with the stopped agent's owner label
        var parent = queue("someoneelse", Map.of(), "cancel", "--running", id);

        assertThat(parent.rc()).as(parent.stderr()).isZero();
    }

    @Test
    void cancelRunning_ofAnUnknownOrWaitingId_isRefused() throws Exception {
        ticket("waiting", "300-" + RUN, ME, checkout("co3"), 0);
        var unknown = queue(ME, Map.of(), "cancel", "--running", "nope");
        var waiting = queue(ME, Map.of(), "cancel", "--running", "300-" + RUN);

        assertThat(List.of(unknown.rc(), waiting.rc())).containsOnly(3);
        assertThat(waiting.stderr()).contains("not running");
        assertThat(queue.resolve("waiting").resolve("300-" + RUN)).exists();
    }

    /// `kill` is shadowed by an exported function (bash imports it into the script): the pid in SURVIVOR ignores every signal, as a
    /// process in uninterruptible sleep would. Nothing may then be reported cancelled.
    @Test
    void cancelRunning_whenAProcessSurvives_leavesTheTicketRunningAndWritesNoRc() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;

        ticket("running", id, ME, co, 1);
        var stubborn = spawn(co, "sleep", "606");
        var shadow = "() {  if [ \"${@: -1}\" = \"" + stubborn.pid() + "\" ]; then return 0; fi; builtin kill \"$@\"\n}";
        var run = queue(ME, Map.of("BASH_FUNC_kill%%", shadow), "cancel", "--running", id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(5);
        assertThat(run.stderr()).contains("survived");
        assertThat(queue.resolve("running").resolve(id)).exists();
        assertThat(queue.resolve("done").resolve(id)).doesNotExist();
        assertThat(dir.resolve("co").resolve("co1").resolve(".bb").resolve("rc")).doesNotExist();
        assertThat(live(stubborn.pid())).isTrue();
    }

    private Path bb(String co) throws IOException {
        return Files.createDirectories(dir.resolve("co").resolve(co).resolve(".bb"));
    }

    /// F1: the identity (pid + start time) is re-checked before every signal. The seam BB_TEST_RESTART_PID makes one collected pid read
    /// as a different process at signal time, as a recycled pid would; it must never be signalled nor listed in killed=.
    @Test
    void cancelRunning_neverSignalsAPidWhoseIdentityChanged() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;

        ticket("running", id, ME, co, 1);
        var reused = spawn(co, "sleep", "608");

        Thread.sleep(200);
        var run = queue(ME,
                        Map.of("BB_TEST_RESTART_PID",
                               Long.toString(reused.pid())),
                        "cancel",
                        "--running",
                        id);

        assertThat(live(reused.pid())).as("identity mismatch: not signalled").isTrue();
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(queue.resolve("running").resolve(id)).doesNotExist();
        assertThat(queue.resolve("running")).as("port group released").isEmptyDirectory();
        assertThat(field("done", id, "state")).isEqualTo("cancelled");
        assertThat(field("done", id, "rc")).isEmpty();
        assertThat(field("done", id, "notours")).contains(Long.toString(reused.pid()));
        assertThat(field("done", id, "killed")).doesNotContain(Long.toString(reused.pid()));
        assertThat(Files.readString(dir.resolve("co").resolve("co1").resolve(".bb").resolve("rc")).strip()).isEqualTo("9");
        assertThat(Files.readString(dir.resolve("co").resolve("co1").resolve(".bb").resolve("build.log"))).contains("not ours, not signalled")
                  .contains(Long.toString(reused.pid()));
    }

    /// F3: one identity-changed pid does not excuse a process that is still ours and survives TERM+KILL: exit 5, ticket stays running.
    @Test
    void cancelRunning_withAnIdentityChangedPidAndAStillOursSurvivor_leavesTheTicketRunning() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;

        ticket("running", id, ME, co, 1);
        var reused = spawn(co, "sleep", "610");
        var stubborn = spawn(co, "sleep", "611");
        var shadow = "() {  if [ \"${@: -1}\" = \"" + stubborn.pid() + "\" ]; then return 0; fi; builtin kill \"$@\"\n}";

        Thread.sleep(200);
        var run = queue(ME,
                        Map.of("BB_TEST_RESTART_PID",
                               Long.toString(reused.pid()),
                               "BASH_FUNC_kill%%",
                               shadow),
                        "cancel",
                        "--running",
                        id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(5);
        assertThat(queue.resolve("running").resolve(id)).exists();
        assertThat(queue.resolve("done").resolve(id)).doesNotExist();
        assertThat(live(reused.pid())).isTrue();
        assertThat(live(stubborn.pid())).isTrue();
    }

    /// F2 (vt42 finding 1): the build wrote .bb/rc for THIS run (.bb/start == started) before the cancel: nothing killed, recorded as too late,
    /// but the file is a copy a lane can rewrite, so the ticket is ORPHANED with no rc (bb-wait exits 70), never `rc=<file>`.
    @Test
    void cancelRunning_tooLate_isOrphanedWithNoRc_neverTheFilesValue() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;

        ticket("running", id, ME, co, 1);
        var b = bb("co1");

        Files.writeString(b.resolve("start"), "7\n");
        Files.writeString(b.resolve("rc"), "0\n");
        var bystander = spawn(co, "sleep", "609");

        Thread.sleep(200);
        var run = queue(ME, Map.of(), "cancel", "--running", id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("too late").doesNotContain("rc=");
        assertThat(Files.readString(b.resolve("rc")).strip()).isEqualTo("0");
        assertThat(b.resolve("end")).doesNotExist();
        assertThat(live(bystander.pid())).as("already finished: nothing is killed").isTrue();
        assertThat(field("done", id, "rc")).as("the file's 0 is not a verdict").isEmpty();
        assertThat(field("done", id, "state")).isEqualTo("orphaned");
        assertThat(field("done", id, "cancel")).startsWith("too-late");
        assertThat(queue.resolve("running").resolve(id)).doesNotExist();
    }

    /// First writer wins: the REAL `bb-build __run` waiter, whose build already wrote rc 0, is held (by a flock shim) at its final
    /// lock; a too-late cancel finalizes the ticket meanwhile. When the waiter then gets the lock it must neither recreate
    /// running/<id> nor overwrite done/<id>.
    @Test
    void waiterCompletion_afterATooLateCancel_neitherOverwritesTheDoneRecordNorRecreatesTheRunningTicket() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;
        var b = bb("co1");

        BbFixtures.executable(tools.resolve("bb-build"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        BbFixtures.executable(dir.resolve("stubmvn"), "#!/bin/sh\nexit 0\n");
        var shim = dir.resolve("shim");

        BbFixtures.executable(shim.resolve("flock"),
                              "#!/bin/bash\nif [ \"$1\" = 8 ] && [ -f \"$SHIM_RC\" ]; then touch \"$SHIM_RC.blocked\";"
                             + " until [ -f \"$SHIM_GO\" ]; do sleep 0.1; done; fi\nexec \"$SHIM_FLOCK\" \"$@\"\n");
        var flock = Stream.of(System.getenv("PATH").split(":"))
                          .map(d -> Path.of(d, "flock"))
                          .filter(Files::isExecutable)
                          .findFirst()
                          .orElseThrow();
        var go = dir.resolve("go");

        ticket("waiting", id, ME, co, 0);
        var more = new HashMap<String, String>();

        more.put("BB_TEST_MVN",
                 dir.resolve("stubmvn").toString());
        more.put("BB_PORT_GROUP", "0");
        more.put("BB_MAX_REACTORS", "0");
        more.put("SHIM_RC",
                 b.resolve("rc").toString());
        more.put("SHIM_GO", go.toString());
        more.put("SHIM_FLOCK", flock.toString());
        var env = env(ME, more);

        env.put("PATH", shim + ":" + env.get("PATH"));
        var builder = HermeticProcess.builder("bash",
                                              tools.resolve("bb-build").toString(),
                                              "__run",
                                              id);

        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var waiter = builder.start();

        started.add(waiter);
        for (var i = 0; i < 200 && !Files.exists(b.resolve("rc.blocked")); i++) {
            Thread.sleep(50);
        }

        assertThat(b.resolve("rc.blocked")).as("waiter parked at its final lock").exists();
        assertThat(Files.readString(b.resolve("rc")).strip()).isEqualTo("0");
        assertThat(queue.resolve("running").resolve(id)).exists();
        var run = queue(ME, Map.of(), "cancel", "--running", id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(field("done", id, "cancel")).startsWith("too-late");
        Files.createFile(go);
        assertThat(waiter.waitFor(20, TimeUnit.SECONDS)).as("waiter exits").isTrue();
        assertThat(waiter.exitValue()).isZero();
        assertThat(field("done", id, "cancel")).as("the cancel's record survives").startsWith("too-late");
        assertThat(field("done", id, "rc")).isEmpty();
        assertThat(Files.readAllLines(queue.resolve("done").resolve(id))).as("no second finished record appended")
                  .doesNotContain("ended=");
        assertThat(queue.resolve("running").resolve(id)).as("no ticket resurrected").doesNotExist();
        assertThat(Files.readString(b.resolve("rc")).strip()).isEqualTo("0");
    }

    /// An rc left by an EARLIER run (.bb/start differs from started) is not this run's result: the cancel proceeds and writes 9.
    @Test
    void cancelRunning_overwritesAStaleRcOfAnEarlierRun() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;

        ticket("running", id, ME, co, 1);
        var b = bb("co1");

        Files.writeString(b.resolve("start"), "3\n");
        Files.writeString(b.resolve("rc"), "0\n");
        var run = queue(ME, Map.of(), "cancel", "--running", id);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(Files.readString(b.resolve("rc")).strip()).isEqualTo("9");
        assertThat(field("done", id, "state")).isEqualTo("cancelled");
    }

    /// The race: the build finishes and writes its rc while the kill grace runs (the process ignores TERM, so the grace lasts seconds).
    @Test
    void cancelRunning_rcWrittenInsideTheKillGrace_isOrphanedWithNoRc() throws Exception {
        var co = checkout("co1");
        var id = "100-" + RUN;

        ticket("running", id, ME, co, 1);
        var b = bb("co1");

        Files.writeString(b.resolve("start"), "7\n");
        var stubborn = spawn(co, "bash", "-c", "trap '' TERM; while :; do sleep 1; done");

        Thread.sleep(300);
        var writer = new Thread(() -> {
            try {
                Thread.sleep(1500);
                Files.writeString(b.resolve("rc"), "3\n");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        writer.start();
        var run = queue(ME, Map.of(), "cancel", "--running", id);

        writer.join();
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("too late").doesNotContain("rc=");
        assertThat(Files.readString(b.resolve("rc")).strip()).isEqualTo("3");
        assertThat(b.resolve("end")).doesNotExist();
        assertThat(field("done", id, "rc")).isEmpty();
        assertThat(field("done", id, "state")).isEqualTo("orphaned");
        assertThat(field("done", id, "cancel")).startsWith("too-late");
        assertThat(field("done", id, "cancel")).as("the stubborn process was killed in the window")
                  .matches("too-late killed=\\d+.*");
        Thread.sleep(300);
        assertThat(live(stubborn.pid())).isFalse();
    }

    @Test
    void release_cancelsWaitingTickets_andRefusesWhileOneIsRunning() throws Exception {
        var co = checkout("co1");
        var root = dir.resolve("co").resolve("co1");

        ticket("waiting", "100-" + RUN, ME, co, 0);
        var waiting = waiter("100-" + RUN, dir);

        ticket("waiting", "101-" + RUN, ME, checkout("co9"), 0);
        var other = waiter("101-" + RUN, dir);
        var clean = queue(ME, Map.of(), "release", root.toString());

        assertThat(clean.rc()).as(clean.stderr()).isZero();
        Thread.sleep(300);
        assertThat(live(waiting.pid())).isFalse();
        assertThat(field("done", "100-" + RUN, "state")).isEqualTo("cancelled");
        assertThat(Files.readString(root.resolve(".bb").resolve("rc")).strip()).isEqualTo("9");
        assertThat(queue.resolve("waiting").resolve("101-" + RUN)).as("another checkout's ticket is untouched").exists();
        assertThat(live(other.pid())).isTrue();
    }

    @Test
    void release_refusesARunningTicket_naming_theTicketAndKill() throws Exception {
        var co = checkout("co1");
        var root = dir.resolve("co").resolve("co1");

        ticket("running", "100-" + RUN, ME, co, 1);
        var waiter = waiter("100-" + RUN, dir);

        ticket("waiting", "102-" + RUN, ME, co, 0);
        var refused = queue(ME, Map.of(), "release", root.toString());

        assertThat(refused.rc()).isEqualTo(3);
        assertThat(refused.stderr()).contains("100-" + RUN).contains("--kill");
        assertThat(live(waiter.pid())).isTrue();
        assertThat(queue.resolve("running").resolve("100-" + RUN)).exists();
        assertThat(queue.resolve("waiting").resolve("102-" + RUN)).as("nothing is cancelled when the release is refused")
                  .exists();
        var killed = queue(ME, Map.of(), "release", root.toString(), "--kill");

        assertThat(killed.rc()).as(killed.stderr()).isZero();
        Thread.sleep(300);
        assertThat(live(waiter.pid())).isFalse();
        assertThat(field("done", "100-" + RUN, "state")).isEqualTo("cancelled");
        assertThat(field("done", "102-" + RUN, "state")).isEqualTo("cancelled");
    }

    @Test
    void release_ofAnotherOwnersTicket_isRefused() throws Exception {
        var co = checkout("co1");

        ticket("waiting", "100-" + RUN, "someoneelse", co, 0);
        var waiter = waiter("100-" + RUN, dir);
        var run = queue(ME,
                        Map.of(),
                        "release",
                        dir.resolve("co").resolve("co1").toString());

        assertThat(run.rc()).isEqualTo(4);
        assertThat(run.stderr()).contains("someoneelse");
        assertThat(live(waiter.pid())).isTrue();
    }

    @Test
    void reap_abandonsAWaitingTicketWhoseCheckoutVanished_andStopsItsWaiter() throws Exception {
        var co = checkout("gone");
        var root = dir.resolve("co").resolve("gone");

        ticket("waiting", "100-" + RUN, ME, co, 0);
        var waiter = waiter("100-" + RUN, dir);
        var keep = checkout("kept");

        ticket("waiting", "101-" + RUN, ME, keep, 0);
        var kept = waiter("101-" + RUN, dir);

        BbFixtures.run(List.of("rm", "-rf", root.toString()),
                       Map.of(),
                       null,
                       null);
        var run = queue(ME, Map.of(), "reap");

        assertThat(run.rc()).as(run.stderr()).isZero();
        Thread.sleep(300);
        assertThat(run.stdout()).contains("reaped 100-" + RUN + ": abandoned");
        assertThat(live(waiter.pid())).as("its waiter was stopped by pid").isFalse();
        assertThat(field("done", "100-" + RUN, "state")).isEqualTo("abandoned");
        assertThat(root).as("reap must not recreate the vanished checkout").doesNotExist();
        assertThat(queue.resolve("waiting").resolve("101-" + RUN)).as("a ticket whose checkout exists stays").exists();
        assertThat(live(kept.pid())).isTrue();
    }

    /// co_reactor_alive matches a reactor whose cwd is the checkout OR BELOW: a surefire fork in a submodule keeps the ticket running.
    @Test
    void reap_keepsARunningTicket_whileAForkSitsInASubmodule() throws Exception {
        var co = checkout("co1");

        ticket("running", "100-" + RUN, ME, co, 1);
        Files.writeString(fake.resolve("classworlds.launcher.Launcher"), "#!/bin/bash\nsleep 607\n");
        var fork = spawn(co,
                         "bash",
                         fake.resolve("classworlds.launcher.Launcher").toString());
        var pattern = Map.of("BB_TEST_REACTOR_PATTERN", "[c]lassworlds.launcher.Launcher");

        Thread.sleep(300);
        var held = queue(ME, pattern, "reap");

        assertThat(held.rc()).isZero();
        assertThat(queue.resolve("running").resolve("100-" + RUN)).as("waiter gone but a fork lives below the checkout")
                  .exists();
        fork.descendants().forEach(ProcessHandle::destroyForcibly);
        fork.destroyForcibly();
        fork.waitFor();
        Thread.sleep(300);
        var freed = queue(ME, pattern, "reap");

        assertThat(freed.stdout()).contains("reaped 100-" + RUN);
        assertThat(queue.resolve("running").resolve("100-" + RUN)).doesNotExist();
    }
}
