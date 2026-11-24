package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-co-gc` (issue #113) under a temporary HOME: a checkout of ~/work/co is removed only when ALL six keep reasons are absent.
/// Every keep row builds an otherwise removable `victim` that fails ONLY that reason, next to a clean `control` that must be removed in the
/// same run: the control proves the run could remove, the exact `reasons=` text proves the victim failed that one condition and no other.
///
/// Mutations (one per condition; drop its test in classify, or for 6 the symlink/direct-child check): the matching row goes red.
class BbCoGcIT {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private static final String TICKET_LOG_HEADER = "host\tticket\towner\tcheckout\tport_group\tgroup_id\tenqueued\tgranted\tended\twait_s\trun_s\trc\tstate\targs\n";

    @TempDir
    Path home;

    private Path co() {
        return home.resolve("work/co");
    }

    private void skeleton() throws IOException {
        Files.createDirectories(co());
        Files.createDirectories(home.resolve("work/queue/waiting"));
        Files.createDirectories(home.resolve("work/queue/running"));
    }

    /// A checkout whose every activity signal is `ageDays` old, whose last run ended (rc newer than start), no marker, no ticket.
    private Path checkout(String name, double ageDays) throws IOException {
        var dir = co().resolve(name);
        var at = FileTime.from(Instant.now().minus(Duration.ofSeconds((long)(ageDays * 86400))));
        var started = FileTime.from(at.toInstant().minusSeconds(60));

        Files.createDirectories(dir.resolve(".git"));
        Files.createDirectories(dir.resolve(".bb"));
        Files.createDirectories(dir.resolve("module/target"));
        Files.writeString(dir.resolve("module/target/payload.bin"), "x".repeat(4096));
        Files.writeString(dir.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        Files.writeString(dir.resolve(".git/index"), "idx");
        Files.writeString(dir.resolve(".bb/start"), "1");
        Files.writeString(dir.resolve(".bb/rc"), "0");
        Files.writeString(dir.resolve(".bb/end"), "1");
        Files.setLastModifiedTime(dir.resolve(".bb/start"), started);
        for (var f : List.of(".git/HEAD", ".git/index", ".bb/rc", ".bb/end")) {
            Files.setLastModifiedTime(dir.resolve(f), at);
        }

        return dir;
    }

    private void ticket(String state, String id, String checkoutName) throws IOException {
        Files.writeString(home.resolve("work/queue/" + state + "/" + id),
                          "id=" + id + "\nowner=someone\nco=" + home.resolve("work/co/" + checkoutName) + "\nsha=abc\n");
    }

    private void ticket(String state, String id, String checkoutName, String extraLine) throws IOException {
        Files.writeString(home.resolve("work/queue/" + state + "/" + id),
                          "id=" + id
                         + "\nowner=someone\nco=" + home.resolve("work/co/" + checkoutName)
                         + "\nsha=abc\n" + extraLine
                         + "\n");
    }

    /// A fake /proc (BB_CO_GC_PROC) holding one process of this user whose cwd cannot be read, named `comm`: no cwd entry, as for a dumpable=0
    /// process. A REAL unreadable process would be seen by every concurrent run on the host (the first version of these rows failed that way).
    private Map<String, String> fakeProcWithBlindProcess(String comm) throws IOException {
        var proc = home.resolve("fakeproc");
        var pid = proc.resolve("4242");

        Files.createDirectories(proc.resolve("self"));
        Files.createDirectories(pid);
        Files.writeString(pid.resolve("comm"), comm + "\n");
        Files.writeString(pid.resolve("stat"), "4242 (" + comm + ") S 1 4242 4242 0 -1\n");

        return Map.of("BB_CO_GC_PROC", proc.toString());
    }

    /// A fake /proc holding `self` (the tool's precondition) and nothing else, so no process of the host this test runs on (a gpg-agent whose cwd
    /// is unreadable made every candidate `blind` on bigboy, 2026-11-25) reaches the guard. Every row gets this unless it names its own tree.
    private Path fakeProc() throws IOException {
        var proc = home.resolve("fakeproc-none");

        Files.createDirectories(proc.resolve("self"));

        return proc;
    }

    /// A fake /proc with `self` and, for each pid the test itself started, a `cwd` symlink to the directory that process really works in: the
    /// first thing guard 3 reads (the tool then also reads exe and fd/*, absent here, which it tolerates). The mapping is the test's own claim
    /// about its child; the assert on `isAlive` and the process's real cwd are in the row.
    private Path fakeProcWithCwd(Map<Long, Path> cwdOf) throws IOException {
        var proc = home.resolve("fakeproc-cwd");

        Files.createDirectories(proc.resolve("self"));

        for (var e : cwdOf.entrySet()) {
            var pid = Files.createDirectories(proc.resolve(Long.toString(e.getKey())));

            Files.createSymbolicLink(pid.resolve("cwd"), e.getValue().toRealPath());
        }

        return proc;
    }

    private BbFixtures.Outcome liveWith(Map<String, String> env) throws Exception {
        var run = gcWith(env, "--live", "--days", "3", "--pace", "0");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();

        return run;
    }

    private static void stop(Process p) throws InterruptedException {
        p.destroyForcibly();
        p.waitFor(10, TimeUnit.SECONDS);
    }

    private void ticketLog(String checkoutName, String owner, Instant ended) throws IOException {
        var log = home.resolve("work/bb-ticket-log.tsv");

        if (!Files.exists(log)) {
            Files.writeString(log, TICKET_LOG_HEADER);
        }

        var row = String.join("\t",
                              "h",
                              "t1",
                              owner,
                              home.resolve("work/co/" + checkoutName).toString(),
                              "0",
                              "-",
                              ISO.format(ended),
                              ISO.format(ended),
                              ISO.format(ended),
                              "1",
                              "1",
                              "0",
                              "done",
                              "args")
                + "\n";

        Files.writeString(log, row, java.nio.file.StandardOpenOption.APPEND);
    }

    private BbFixtures.Outcome gc(String... args) throws Exception {
        return gcWith(Map.of(), args);
    }

    private BbFixtures.Outcome gcWith(Map<String, String> extraEnv, String... args) throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-co-gc reads /proc and uses GNU stat, date, mv, flock");
        var env = new HashMap<String, String>(extraEnv);

        env.putIfAbsent("PATH", System.getenv("PATH"));
        env.putIfAbsent("BB_CO_GC_PROC", fakeProc().toString());
        env.put("HOME", home.toString());
        env.put("USER", System.getProperty("user.name"));
        var command = new java.util.ArrayList<String>();

        command.add(BbFixtures.OPS_BB.resolve("bb-co-gc").toString());
        command.addAll(List.of(args));

        return BbFixtures.run(command, env, home, null);
    }

    private BbFixtures.Outcome live() throws Exception {
        var run = gc("--live", "--days", "3", "--pace", "0");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();

        return run;
    }

    /// Runs live with a clean `control`, then asserts the victim is kept for exactly `reason` and untouched, and the control is gone.
    private void assertKeptOnlyFor(Path victim, String reason) throws Exception {
        assertKeptOnlyFor(victim, reason, Map.of());
    }

    private void assertKeptOnlyFor(Path victim, String reason, Map<String, String> env) throws Exception {
        var control = checkout("control", 10);
        var run = liveWith(env);

        assertThat(control).doesNotExist();
        assertThat(run.stdout()).contains("removed control ");
        assertThat(victim.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout().lines().filter(l -> l.startsWith("keep " + victim.getFileName() + " ")).toList()).as(run.stdout())
                  .hasSize(1)
                  .allMatch(l -> l.contains(" reasons=" + reason + " ") || l.endsWith(" reasons=" + reason));
        assertThat(run.stdout()).doesNotContain("removed " + victim.getFileName() + " ");
    }

    // ---- keep reasons 1..6
    /// Reason 1: a ticket in queue/waiting or queue/running names the checkout.
    @Test
    void reason1_aCheckoutNamedByAWaitingOrRunningTicket_isKept() throws Exception {
        skeleton();
        var waiting = checkout("victim-waiting", 10);
        var running = checkout("victim-running", 10);

        ticket("waiting", "w1", "victim-waiting");
        ticket("running", "r1", "victim-running");
        var control = checkout("control", 10);
        var run = live();

        assertThat(control).doesNotExist();
        assertThat(waiting.resolve("module/target/payload.bin")).exists();
        assertThat(running.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("keep victim-waiting reasons=ticket ", "keep victim-running reasons=ticket ");
    }

    /// Reason 2: a queued marker, or a start with no rc (bb-build removes rc when a run starts; a run in flight), keeps the checkout.
    @Test
    void reason2_aRunInFlight_isKept() throws Exception {
        skeleton();
        var queued = checkout("victim-queued", 10);
        var startOnly = checkout("victim-startonly", 10);

        Files.writeString(queued.resolve(".bb/queued"), "1");
        Files.delete(startOnly.resolve(".bb/rc"));
        var control = checkout("control", 10);
        var run = live();

        assertThat(control).doesNotExist();
        for (var v : List.of(queued, startOnly)) {
            assertThat(v.resolve("module/target/payload.bin")).as(v.toString()).exists();
            assertThat(run.stdout()).contains("keep " + v.getFileName() + " reasons=in-flight ");
        }
    }

    /// Reason 3: a process with its cwd under the checkout keeps it. The fake /proc holds exactly the sleeper: a cwd link to the checkout.
    @Test
    void reason3_aProcessWorkingInTheCheckout_keepsIt() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);
        var sleeper = BbFixtures.process("sleep", "120").directory(victim.resolve("module").toFile()).start();

        try {
            assertThat(sleeper.isAlive()).as("control: the sleeper runs in the checkout").isTrue();
            var proc = fakeProcWithCwd(Map.of(sleeper.pid(), victim.resolve("module")));

            assertKeptOnlyFor(victim, "process", Map.of("BB_CO_GC_PROC", proc.toString()));
        } finally {
            stop(sleeper);
        }
    }

    /// The same keep against the REAL /proc: the one row that reads the host's own process table (the seam's default), so the scan of real
    /// cwd/exe/fd entries is exercised once. It asserts only that `process` is among the reasons: a host's unrelated unreadable process
    /// (a gpg-agent, 2026-11-25) adds `blind`, which this row must not depend on. With the process guard removed the reasons lack `process`.
    @Test
    void reason3_aRealProcessInTheRealProc_isSeenByTheScan() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);
        var sleeper = BbFixtures.process("sleep", "120").directory(victim.resolve("module").toFile()).start();

        try {
            assertThat(sleeper.isAlive()).as("control: the sleeper runs in the checkout").isTrue();
            var run = liveWith(Map.of("BB_CO_GC_PROC", "/proc"));

            assertThat(victim.resolve("module/target/payload.bin")).as(run.stdout()).exists();
            assertThat(run.stdout().lines().filter(l -> l.startsWith("keep victim ")).toList()).as(run.stdout())
                      .hasSize(1)
                      .allMatch(l -> List.of(l.replaceFirst(".* reasons=(\\S+).*", "$1").split(",")).contains("process"));
        } finally {
            stop(sleeper);
        }
    }

    /// Reason 4: activity inside the window keeps the checkout, from whichever signal: its own files, or the ticket log's last end.
    @Test
    void reason4_recentActivity_isKept_fromTheFilesAndFromTheTicketLog() throws Exception {
        skeleton();
        var byFiles = checkout("victim-files", 1);
        var byLog = checkout("victim-log", 10);

        ticketLog("victim-log",
                  "lane9",
                  Instant.now().minus(Duration.ofHours(5)));
        var control = checkout("control", 10);
        var run = live();

        assertThat(control).doesNotExist();
        assertThat(byFiles.resolve("module/target/payload.bin")).exists();
        assertThat(byLog.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("keep victim-files reasons=recent ", "keep victim-log reasons=recent ");
    }

    /// A checkout with no activity signal at all has an unknown age: kept, never guessed.
    @Test
    void anUnknownAge_isKept() throws Exception {
        skeleton();
        var victim = co().resolve("victim");

        Files.createDirectories(victim.resolve("module"));
        Files.writeString(victim.resolve("module/payload.bin"), "x");
        var control = checkout("control", 10);
        var run = live();

        assertThat(control).doesNotExist();
        assertThat(victim.resolve("module/payload.bin")).exists();
        assertThat(run.stdout()).contains("keep victim reasons=activity-unknown");
    }

    /// Reason 5: .bb/keep is the owner's opt-out.
    @Test
    void reason5_aKeepFile_isKept() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);

        Files.writeString(victim.resolve(".bb/keep"), "");
        assertKeptOnlyFor(victim, "keep-file");
    }

    /// Reason 6: only a real directory that is a direct child of ~/work/co is a checkout; a symlink (to a stale-looking checkout outside, and
    /// to one inside, which is itself kept by a ticket) is kept and never followed: the targets and the links survive, nothing behind them is touched.
    @Test
    void reason6_aSymlink_isKeptAndNotFollowed() throws Exception {
        skeleton();
        var outside = checkout("outside", 10);
        var outsideDir = Files.move(outside, home.resolve("elsewhere"));
        var inside = checkout("real", 10);

        ticket("waiting", "w2", "real");
        var viaOutside = Files.createSymbolicLink(co().resolve("link-out"), outsideDir);
        var viaInside = Files.createSymbolicLink(co().resolve("link-in"), inside);
        var control = checkout("control", 10);
        var before = snapshot(outsideDir);
        var run = live();

        assertThat(control).doesNotExist();
        assertThat(Files.isSymbolicLink(viaOutside)).as(run.stdout()).isTrue();
        assertThat(Files.isSymbolicLink(viaInside)).as(run.stdout()).isTrue();
        assertThat(snapshot(outsideDir)).isEqualTo(before);
        assertThat(inside.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("keep real reasons=ticket ");
        assertThat(run.stdout().lines().filter(l -> l.startsWith("keep link-")).toList()).hasSize(2)
                  .allMatch(l -> l.contains("reasons=not-direct-child"));
        assertThat(run.stdout()).doesNotContain("removed link-");
    }

    // ---- removal, crash leftover, DRY
    /// The removal path: the idle checkout is gone with everything in it, no .gc-* is left, the log names path, size and owner label, a
    /// neighbour that is merely recent stays.
    @Test
    void anIdleCheckout_isRemoved_andLogged() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);
        var neighbour = checkout("neighbour", 1);

        ticketLog("idle",
                  "lane77",
                  Instant.now().minus(Duration.ofDays(9)));
        var run = live();
        var log = Files.readString(home.resolve("work/co-gc.log"));

        assertThat(idle).doesNotExist();
        assertThat(neighbour.resolve("module/target/payload.bin")).exists();
        try (var names = Files.list(co())) {
            assertThat(names.map(p -> p.getFileName()
                                       .toString()).toList()).containsExactly("neighbour");
        }

        assertThat(run.stdout()).contains("removed idle ").contains("total: removed 1 of 1 candidates");
        assertThat(log).contains("REMOVED path=" + co().toRealPath() + "/idle ", " owner=lane77 ", "RUN end removed=1");
    }

    /// A crash between the mv and the rm leaves only a .gc-<epoch>-<name> directory: the next run removes it; a DRY run lists it and leaves it.
    @Test
    void aLeftoverGcDirectoryOfACrash_isRemovedByTheNextRun() throws Exception {
        skeleton();
        var leftover = co().resolve(".gc-1700000000-crashed");

        Files.createDirectories(leftover.resolve("module"));
        Files.writeString(leftover.resolve("module/payload.bin"), "x".repeat(2048));
        var dry = gc();

        assertThat(dry.rc()).isZero();
        assertThat(dry.stdout()).contains("leftover .gc-1700000000-crashed ");
        assertThat(leftover.resolve("module/payload.bin")).exists();
        var run = live();

        assertThat(leftover).doesNotExist();
        assertThat(run.stdout()).contains("leftover-removed .gc-1700000000-crashed ");
        assertThat(Files.readString(home.resolve("work/co-gc.log"))).contains("LEFTOVER-REMOVED path=" + co().toRealPath()
                                                                             + "/.gc-1700000000-crashed ");
    }

    /// DRY changes nothing: the whole home (paths, types, sizes, mtimes, link targets) is equal before and after, and no log or lock file appears.
    @Test
    void dry_changesNothing() throws Exception {
        skeleton();
        checkout("idle-a", 10);
        checkout("idle-b", 20);
        checkout("recent", 1);
        var held = checkout("held", 10);

        Files.writeString(held.resolve(".bb/keep"), "");
        Files.createDirectories(co().resolve(".gc-1700000000-crashed/module"));
        Files.writeString(co().resolve(".gc-1700000000-crashed/module/payload.bin"), "x");
        Files.createSymbolicLink(co().resolve("link"), held);
        ticket("waiting", "w1", "recent");
        ticketLog("idle-b",
                  "lane1",
                  Instant.now().minus(Duration.ofDays(19)));
        var before = snapshot(home);
        var run = gc();

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(snapshot(home)).isEqualTo(before);
        assertThat(home.resolve("work/co-gc.log")).doesNotExist();
        assertThat(home.resolve("work/.co-gc.lock")).doesNotExist();
        assertThat(run.stdout()).contains("candidate idle-a ",
                                          "candidate idle-b ",
                                          "keep recent reasons=ticket,recent",
                                          "keep held reasons=keep-file",
                                          "keep link reasons=not-direct-child",
                                          "leftover .gc-1700000000-crashed ",
                                          "nothing removed");
        assertThat(run.stdout()).contains("total: DRY 2 candidates");
    }

    // ---- #114 review findings
    /// Finding 1: a process this guard cannot read, named like nothing on the allowlist, may sit in any checkout: every candidate is kept as `blind`.
    @Test
    void aBlindProcessNotOnTheAllowlist_keepsEveryCandidate() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);
        var control = checkout("control", 10);
        var run = liveWith(fakeProcWithBlindProcess("rogue"));

        assertThat(victim.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(control.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).contains("keep victim reasons=blind ",
                                          "keep control reasons=blind ",
                                          "blind=1 unlisted-blind=1")
                  .doesNotContain("\nremoved ", "removed control ", "removed victim ");
    }

    /// The allowlist is the whole difference: the same unreadable process named `sshd` (a login's session monitor) keeps nothing.
    @Test
    void aBlindProcessOnTheAllowlist_keepsNothing() throws Exception {
        skeleton();
        var control = checkout("control", 10);
        var run = liveWith(fakeProcWithBlindProcess("sshd"));

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(run.stdout()).contains("removed control ", "blind=1 unlisted-blind=0");
    }

    /// Finding 2: a leftover .gc dir gets the second look of a fresh move: keep, queued and a ticket each send it back to its name; none is removed.
    @Test
    void aLeftoverHeldByKeepQueuedOrATicket_isRestoredNotRemoved() throws Exception {
        skeleton();
        var names = List.of("held-keep", "held-queued", "held-ticket", "plain");

        for (var n : names) {
            Files.move(checkout(n, 10), co().resolve(".gc-1700000000-" + n));
        }

        Files.writeString(co().resolve(".gc-1700000000-held-keep/.bb/keep"), "");
        Files.writeString(co().resolve(".gc-1700000000-held-queued/.bb/queued"), "1");
        ticket("waiting", "w1", "held-ticket");
        var dry = gc("--days", "3");

        assertThat(dry.stdout()).contains("leftover-kept .gc-1700000000-held-keep reasons=keep-file (would be moved back to held-keep)",
                                          "leftover-kept .gc-1700000000-held-queued reasons=in-flight (would be moved back to held-queued)",
                                          "leftover-kept .gc-1700000000-held-ticket reasons=ticket (would be moved back to held-ticket)",
                                          "leftover .gc-1700000000-plain ");
        var run = live();

        for (var n : names.subList(0, 3)) {
            assertThat(co().resolve(n).resolve("module/target/payload.bin")).as(n + "\n" + run.stdout()).exists();
            assertThat(co().resolve(".gc-1700000000-" + n)).doesNotExist();
        }

        assertThat(co().resolve(".gc-1700000000-plain")).doesNotExist();
        assertThat(run.stdout()).contains("leftover-restored .gc-1700000000-held-keep to held-keep reasons=keep-file",
                                          "leftover-restored .gc-1700000000-held-queued to held-queued reasons=in-flight",
                                          "leftover-restored .gc-1700000000-held-ticket to held-ticket reasons=ticket",
                                          "leftover-removed .gc-1700000000-plain ");
        assertThat(Files.readString(home.resolve("work/co-gc.log"))).contains("LEFTOVER-RESTORED path=" + co().toRealPath()
                                                                             + "/held-keep ")
                  .contains("LEFTOVER-REMOVED path=" + co().toRealPath() + "/.gc-1700000000-plain ")
                  .doesNotContain("LEFTOVER-REMOVED path=" + co().toRealPath() + "/.gc-1700000000-held");
    }

    /// A held leftover whose name is taken again cannot go back: it stays where it is, logged on each run, and is never removed.
    @Test
    void aHeldLeftoverWhoseNameIsTaken_staysInPlaceForEver() throws Exception {
        skeleton();
        var leftover = Files.move(checkout("dup", 10), co().resolve(".gc-1700000000-dup"));

        Files.writeString(leftover.resolve(".bb/keep"), "");
        checkout("dup", 1);
        for (var i = 0; i < 2; i++) {
            var run = live();

            assertThat(leftover.resolve("module/target/payload.bin")).as(run.stdout()).exists();
            assertThat(run.stdout()).contains("leftover-kept .gc-1700000000-dup reasons=keep-file (stays in place; never removed)");
        }

        assertThat(Files.readString(home.resolve("work/co-gc.log"))).contains("LEFTOVER-KEPT path=" + co().toRealPath()
                                                                             + "/.gc-1700000000-dup reasons=keep-file");
    }

    /// Finding 4: a removal is followed by a pause of --pace seconds. No wall clock (a loaded host takes seconds anyway): a recording `sleep`
    /// stub first on PATH logs each call's argument and how many checkouts were left at that moment, so the pause is proved to follow each
    /// removal and to be exactly --pace. (bb-co-gc paces after the LAST removal too: 3 calls for 3 removals.)
    /// Mutation: pace() { :; } -> no calls; pace() { sleep 2; } -> argument 2; both red.
    @Test
    void removals_areSeparatedByThePace() throws Exception {
        skeleton();
        for (var n : List.of("a", "b", "c")) {
            checkout(n, 10);
        }

        var stubs = Files.createDirectories(home.resolve("stubbin"));
        var stub = stubs.resolve("sleep");

        Files.writeString(stub, "#!/bin/sh\necho \"$* co=$(ls \"" + co() + "\" | wc -l)\" >> \"" + home.resolve("sleep.calls") + "\"\n");
        assertThat(stub.toFile().setExecutable(true)).isTrue();

        var run = gcWith(Map.of("PATH", stubs + ":" + System.getenv("PATH")), "--live", "--days", "3", "--pace", "1");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("total: removed 3 of 3 candidates");
        for (var n : List.of("a", "b", "c")) {
            assertThat(co().resolve(n)).as("control: removed").doesNotExist();
        }
        assertThat(Files.readAllLines(home.resolve("sleep.calls"))).as(run.stdout()).containsExactly("1 co=2", "1 co=1", "1 co=0");
    }

    /// Finding 4: a running port-group ticket defers the whole live pass; nothing is read for removal, exit 0, one log line.
    @Test
    void aRunningPortGroupTicket_defersTheLivePass() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);

        ticket("running", "r1", "busy", "group=1");
        var run = live();

        assertThat(run.stdout()).contains("deferred: builds running (running=1 group=1 cap=3)");
        assertThat(idle.resolve("module/target/payload.bin")).exists();
        assertThat(Files.readString(home.resolve("work/co-gc.log"))).contains("DEFERRED builds running running=1 group=1 cap=3")
                  .doesNotContain("RUN live");
    }

    /// Finding 4: more than half the host cap running defers it (2 of cap 3); exactly half does not (2 of cap 4).
    @Test
    void moreThanHalfTheHostCapRunning_defers_exactlyHalfDoesNot() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);

        ticket("running", "r1", "busy1");
        ticket("running", "r2", "busy2");
        Files.writeString(home.resolve("work/max-reactors"), "3\n");
        var deferred = live();

        assertThat(deferred.stdout()).contains("deferred: builds running (running=2 group=0 cap=3)");
        assertThat(idle.resolve("module/target/payload.bin")).exists();
        Files.writeString(home.resolve("work/max-reactors"), "4\n");
        var run = live();

        assertThat(idle).as(run.stdout()).doesNotExist();
        assertThat(run.stdout()).contains("removed idle ");
    }

    /// Finding 5: the run takes the queue's lock before it checks tickets and moves. While a holder keeps $Q/.lock the run WAITS (the checkout
    /// stays, the run is not done); once the holder lets go it removes. No wall-clock bound: the holder is released by the test, so a slow host
    /// only delays, never reds; the 15 s poll only ends early, when a run that ignores the lock has removed.
    /// Mutation: qlock() { return 0; } -> the checkout is gone during the dwell -> red.
    @Test
    void aHolderOfTheQueueLock_makesTheRunWaitThenItRemoves() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);
        var held = home.resolve("held");
        var release = home.resolve("release");
        var holder = BbFixtures.process("flock",
                                           home.resolve("work/queue/.lock").toString(),
                                           "sh",
                                           "-c",
                                           "touch " + held + "; while [ ! -e " + release + " ]; do sleep 0.1; done").start();

        try {
            for (var i = 0; i < 100 && !Files.exists(held); i++) {
                Thread.sleep(50);
            }

            assertThat(held).as("control: flock holds the queue lock").exists();

            var run = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return live();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            var log = home.resolve("work/co-gc.log");

            for (var i = 0; i < 600 && !(Files.exists(log) && Files.readString(log).contains("RUN live")); i++) {
                Thread.sleep(50);
            }

            assertThat(Files.exists(log) && Files.readString(log).contains("RUN live")).as("control: the run started").isTrue();
            // A run that ignores the lock removes as soon as its /proc scan is done (seconds on a loaded host): wait for that, up to 15 s.
            for (var i = 0; i < 300 && !run.isDone() && Files.exists(idle.resolve("module/target/payload.bin")); i++) {
                Thread.sleep(50);
            }

            assertThat(idle.resolve("module/target/payload.bin")).as("kept while the holder holds the lock").exists();
            assertThat(run.isDone()).as("the run waits for the holder").isFalse();
            Files.writeString(release, "go");

            var done = run.get(120, TimeUnit.SECONDS);

            assertThat(idle).as(done.stdout()).doesNotExist();
            assertThat(done.stdout()).contains("removed idle ");
        } finally {
            Files.writeString(release, "go");
            stop(holder);
        }
    }

    /// Past BB_CO_GC_LOCK_WAIT the candidate is SKIPPED (not removed, not a failure, logged), and the next run takes it.
    @Test
    void aQueueLockHeldPastTheWait_skipsTheCandidate() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);
        var held = home.resolve("held");
        var holder = BbFixtures.process("flock",
                                           home.resolve("work/queue/.lock").toString(),
                                           "sh",
                                           "-c",
                                           "touch " + held + "; sleep 8").start();

        try {
            for (var i = 0; i < 100 && !Files.exists(held); i++) {
                Thread.sleep(50);
            }

            assertThat(held).as("control: flock holds the queue lock").exists();
            var run = gcWith(Map.of("BB_CO_GC_LOCK_WAIT", "1"), "--live", "--days", "3", "--pace", "0");

            assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
            assertThat(idle.resolve("module/target/payload.bin")).as(run.stdout()).exists();
            assertThat(run.stdout()).contains("skip idle reason=queue-lock-busy", "skipped 1");
            assertThat(Files.readString(home.resolve("work/co-gc.log"))).contains("SKIP path=" + co().toRealPath()
                                                                                 + "/idle reason=queue-lock-busy");
        } finally {
            stop(holder);
        }

        var later = live();

        assertThat(idle).as(later.stdout()).doesNotExist();
    }

    // ---- snapshot
    /// path -> type, size, mtime, link target; directories included (a removed or created entry changes its parent's mtime too).
    private static TreeMap<String, String> snapshot(Path root) throws IOException {
        var out = new TreeMap<String, String>();

        Files.walkFileTree(root,
                           new SimpleFileVisitor<>() {
                               @Override
                               public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                   out.put(root.relativize(dir).toString(),
                                           "d " + Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS).toMillis());

                                   return FileVisitResult.CONTINUE;
                               }

                               @Override
                               public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                   var kind = attrs.isSymbolicLink()
                                              ? "l " + Files.readSymbolicLink(file)
                                              : "f " + attrs.size();

                                   out.put(root.relativize(file).toString(),
                                           kind + " " + attrs.lastModifiedTime().toMillis());

                                   return FileVisitResult.CONTINUE;
                               }
                           });

        return out;
    }
}
