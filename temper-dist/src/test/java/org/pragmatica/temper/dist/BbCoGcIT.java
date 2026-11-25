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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    /// The fake /proc trees live here, outside `home`, so a row that snapshots `home` (dry_changesNothing) sees only what the tool did.
    @TempDir
    Path procRoot;

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
        var proc = procRoot.resolve("fakeproc");
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
        var proc = procRoot.resolve("fakeproc-none");

        Files.createDirectories(proc.resolve("self"));

        return proc;
    }

    /// A fake /proc with `self` and, for each pid the test itself started, a `cwd` symlink to the directory that process really works in: the
    /// first thing guard 3 reads (the tool then also reads exe and fd/*, absent here, which it tolerates). The mapping is the test's own claim
    /// about its child; the assert on `isAlive` and the process's real cwd are in the row.
    private Path fakeProcWithCwd(Map<Long, Path> cwdOf) throws IOException {
        var proc = procRoot.resolve("fakeproc-cwd");

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

    /// Each of the three activity signals alone keeps a checkout: .git/index fresh while .git/HEAD, .bb/end and .bb/rc are 10 days old (the
    /// control is the same shape with index old too, removed by assertKeptOnlyFor).
    @Test
    void reason4_aFreshGitIndexAlone_keepsTheCheckout() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);

        Files.setLastModifiedTime(victim.resolve(".git/index"), FileTime.from(Instant.now().minus(Duration.ofHours(5))));
        assertKeptOnlyFor(victim, "recent");
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

    /// A .bb/keep that is a dangling symlink is still the owner's opt-out: the marker is the name, not what it points at.
    @Test
    void reason5_aDanglingKeepSymlink_isKept() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);
        var link = Files.createSymbolicLink(victim.resolve(".bb/keep"), home.resolve("no-such-target"));

        assertThat(Files.exists(link)).as("control: the link dangles (exists() follows it)").isFalse();
        assertThat(Files.isSymbolicLink(link)).isTrue();
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

    /// --days decides the window, in both spellings and for N other than 3: checkouts of 2, 5 and 12 days; each run shrinks the window and
    /// removes exactly the checkouts it has now outlived (a run that ignored N would use 3 and remove 5 and 12 days at the first).
    @Test
    void days_setsTheWindow_inBothSpellings() throws Exception {
        skeleton();
        var d2 = checkout("age2", 2);
        var d5 = checkout("age5", 5);
        var d12 = checkout("age12", 12);
        var first = gc("--live", "--days=10", "--pace", "0");

        assertThat(first.rc()).as(first.stdout() + first.stderr()).isZero();
        assertThat(d12).as(first.stdout()).doesNotExist();
        assertThat(d5).exists();
        assertThat(d2).exists();
        var second = gc("--live", "--days", "4", "--pace", "0");

        assertThat(second.rc()).as(second.stdout() + second.stderr()).isZero();
        assertThat(d5).as(second.stdout()).doesNotExist();
        assertThat(d2).exists();
        var third = gc("--live", "--days=1", "--pace=0");

        assertThat(third.rc()).as(third.stdout() + third.stderr()).isZero();
        assertThat(d2).as(third.stdout()).doesNotExist();
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

    /// The allowlist is the whole difference: the same unreadable process named `sshd` (a login's session monitor), `systemd` (the user manager) or
    /// `(sd-pam)` keeps nothing. Mutations n20 and n21 (a name dropped from the allowlist): each name has its own row of the loop.
    @Test
    void aBlindProcessOnTheAllowlist_keepsNothing() throws Exception {
        skeleton();

        for (var comm : List.of("sshd", "systemd", "(sd-pam)")) {
            var control = checkout("control-" + comm.replaceAll("\\W", ""), 10);
            var run = liveWith(fakeProcWithBlindProcess(comm));

            assertThat(control).as(comm + "\n" + run.stdout()).doesNotExist();
            assertThat(run.stdout()).as(comm).contains("removed control-", "blind=1 unlisted-blind=0");
        }
    }

    /// A blind process that exits while the guard reads its name is no longer blind (the existence check after the read): `cat` of its comm
    /// prints `rogue` and removes the process, the way a real one exits between the reads. A DRY run has one scan, so its candidate line is the
    /// scan's own verdict: not `blind`, though the process was counted (blind=1) and named `rogue` (not on the allowlist). Mutation n18 (that
    /// check dropped: the name read before the exit counts, unlisted-blind=1, the candidate is kept `blind`). n17 (the check BEFORE the read) is
    /// equivalent: a pid gone before the read gives an empty name and the check after it skips the pid just the same.
    @Test
    void aBlindProcessThatExitsWhileItsNameIsRead_isNotCounted() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);

        fakeProcWithBlindProcess("rogue");
        stub("cat", """
                    for a; do :; done
                    case $a in @PROC@/4242/comm) cat -- "$a"; rm -rf @PROC@/4242; exit 0 ;; esac
                    exec cat "$@"
                    """);
        Files.move(procRoot.resolve("fakeproc/4242"), hookProc().resolve("4242"));
        var run = gcWith(stubbed(), "--days", "3");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("candidate victim ", "blind=1 unlisted-blind=0").doesNotContain("reasons=blind");
        assertThat(victim.resolve("module/target/payload.bin")).exists();
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

    // ---- pace (G6)
    private Path recordSleeps() throws IOException {
        var calls = procRoot.resolve("sleep.calls");

        stub("sleep", "echo \"$*\" >> " + calls);

        return calls;
    }

    /// --pace S is read in both spellings, defaults to 2, and a leading zero is a decimal (08 -> 8, never an octal error): the recorded `sleep`
    /// gets exactly the number, after the one removal, and the RUN log line names it. Mutations n02 (--pace= ignored), n04 (08 left as typed),
    /// n06 (default 0: no sleep call), n31 (sleep 1 whatever S is), n47 (RUN line without pace).
    @Test
    void pace_isReadInBothSpellings_defaultsToTwo_andLeadingZerosAreDecimal() throws Exception {
        skeleton();
        var calls = recordSleeps();
        var cases = List.of(Map.entry(List.of("--pace", "3"), "3"),
                            Map.entry(List.of("--pace=4"), "4"),
                            Map.entry(List.of("--pace", "08"), "8"),
                            Map.entry(List.<String>of(), "2"));
        var i = 0;

        for (var c : cases) {
            var victim = checkout("p" + i++, 10);
            var args = new ArrayList<>(List.of("--live", "--days", "3"));

            args.addAll(c.getKey());
            var run = gcWith(stubbed(), args.toArray(String[]::new));

            assertThat(run.rc()).as(c + "\n" + run.stdout() + run.stderr()).isZero();
            assertThat(victim).as(c.toString()).doesNotExist();
            assertThat(Files.readAllLines(calls)).as(c.toString()).containsExactly(c.getValue());
            assertLogHas("RUN live days=3 pace=" + c.getValue());
            Files.delete(calls);
        }
    }

    /// --pace is an integer 0..3600: 3601, a sign, a fraction, trailing text, an empty value and a missing value are usage errors (rc 2, the usage
    /// line names --pace) before any write; 3600 is accepted. Mutations n03 (no digit check: -1 passes), n05 (upper bound 36000), n79 (usage without --pace).
    @Test
    void pace_outsideZeroToThreeThousandSixHundredOrNotANumber_isRefusedBeforeAnyWrite() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);

        recordSleeps();
        for (var bad : List.of("3601", "-1", "1.5", "1x", "")) {
            var run = gcWith(stubbed(), "--live", "--days", "3", "--pace", bad);

            assertThat(run.rc()).as("[" + bad + "]\n" + run.stdout() + run.stderr()).isEqualTo(2);
            assertThat(run.stderr()).contains("usage: bb-co-gc", "[--pace S]");
            assertThat(idle.resolve("module/target/payload.bin")).as(bad).exists();
        }

        var missing = gcWith(stubbed(), "--live", "--pace");

        assertThat(missing.rc()).as(missing.stderr()).isEqualTo(2);
        assertThat(home.resolve("work/co-gc.log")).doesNotExist();
        assertThat(gcWith(stubbed(), "--pace", "3600").rc()).isZero();
    }

    /// A removed leftover is followed by the pace like a removed checkout. Mutation n33 (no pace after a leftover).
    @Test
    void aRemovedLeftover_isFollowedByThePace() throws Exception {
        skeleton();
        var leftover = co().resolve(".gc-1700000000-crashed");
        var calls = recordSleeps();

        Files.createDirectories(leftover.resolve("module"));
        Files.writeString(leftover.resolve("module/payload.bin"), "x");
        var run = gcWith(stubbed(), "--live", "--days", "3", "--pace", "5");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(leftover).as(run.stdout()).doesNotExist();
        assertThat(Files.readAllLines(calls)).containsExactly("5");
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

    // ---- command stubs: state that changes at an exact step of the run (#113 test round)
    /// Writes `command` into a stub directory outside `home`; the stub removes its own directory from PATH, so a plain `mv`/`flock`/`du`/`rm`
    /// inside `body` is the real command. `@PROC@`, `@CO@` (the resolved ~/work/co) and `@Q@` (the queue) are filled in.
    private void stub(String command, String body) throws IOException {
        var proc = hookProc();
        var text = body.replace("@PROC@", proc.toString())
                       .replace("@CO@", co().toRealPath().toString())
                       .replace("@Q@", home.resolve("work/queue").toString());

        BbFixtures.executable(procRoot.resolve("stubbin").resolve(command), "#!/bin/sh\nS=${0%/*}\nPATH=${PATH#\"$S:\"}\n" + text);
    }

    /// The env that makes the stubs first on PATH and points the tool at the hook-driven fake /proc (`self` only until a stub adds to it).
    private Map<String, String> stubbed() throws IOException {
        return Map.of("PATH", procRoot.resolve("stubbin") + ":" + System.getenv("PATH"), "BB_CO_GC_PROC", hookProc().toString());
    }

    private Path hookProc() throws IOException {
        return Files.createDirectories(procRoot.resolve("fakeproc-hook/self")).getParent();
    }

    private List<String> logLines() throws IOException {
        return Files.readAllLines(home.resolve("work/co-gc.log"));
    }

    private void assertLogHas(String suffix) throws IOException {
        assertThat(logLines()).as(suffix).anyMatch(l -> l.endsWith(suffix));
    }

    private void assertNoGcDirectories() throws IOException {
        try (var names = Files.list(co())) {
            assertThat(names.map(x -> x.getFileName().toString()).filter(x -> x.startsWith(".gc-")).toList()).isEmpty();
        }
    }

    /// The victim is back under its name with its payload, nothing is left as .gc-*, the log says RESTORED with exactly `reasons`.
    private void assertRestored(Path victim, BbFixtures.Outcome run, String reasons) throws IOException {
        var name = victim.getFileName().toString();

        assertThat(victim.resolve("module/target/payload.bin")).as(name + "\n" + run.stdout()).exists();
        assertThat(run.stdout()).contains("keep " + name + " reasons=changed-after-move (restored)").doesNotContain("removed " + name + " ");
        assertLogHas("RESTORED path=" + co().toRealPath() + "/" + name + " reasons=" + reasons);
        assertNoGcDirectories();
    }

    /// The re-check after the move is the ONLY guard that can see these: each change happens in a stub `mv`, right after the real rename and
    /// before the tool's scan of the moved directory, so the classification (outside and under the queue lock) saw a removable checkout.
    /// Mutations: recheck off (p1) or its process / blind / unverifiable arms dropped (p2, n26, p6a), the scan after the move dropped (n71),
    /// rm after RESTORED (p8): each victim is removed, or its directory is gone, and a row below goes red.
    @Test
    void aChangeBetweenTheMoveAndTheRecheck_sendsTheCheckoutBackUnderItsName() throws Exception {
        skeleton();
        var late = new java.util.LinkedHashMap<String, String>();

        late.put("late-inflight", "in-flight");
        late.put("late-keep", "keep-file");
        late.put("late-process", "process");
        late.put("late-queued", "in-flight");
        late.put("late-ticket", "ticket");
        var victims = new java.util.LinkedHashMap<String, Path>();

        for (var n : late.keySet()) {
            victims.put(n, checkout(n, 10));
        }

        var control = checkout("control", 10);

        stub("mv", """
                   mv "$@" || exit $?
                   for a; do dst=$a; done
                   case ${dst##*/} in
                     .gc-*-late-inflight) rm -f "$dst/.bb/rc" ;;
                     .gc-*-late-keep) : > "$dst/.bb/keep" ;;
                     .gc-*-late-process) mkdir -p @PROC@/9001; ln -s "$dst" @PROC@/9001/cwd ;;
                     .gc-*-late-queued) : > "$dst/.bb/queued" ;;
                     .gc-*-late-ticket) printf 'id=late\\nowner=x\\nco=@CO@/late-ticket\\nsha=a\\n' > @Q@/waiting/late ;;
                   esac
                   """);
        var run = liveWith(stubbed());

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(run.stdout()).contains("removed control ");
        for (var e : late.entrySet()) {
            assertRestored(victims.get(e.getKey()), run, e.getValue());
        }
    }

    /// A process of this user that cannot be read and is not allowlisted appears after the move: every candidate is kept, the moved one goes back.
    /// Mutation n26: the blind arm of the re-check dropped, the victim is removed.
    @Test
    void aBlindProcessAppearingAfterTheMove_sendsTheCheckoutBack() throws Exception {
        skeleton();
        var victim = checkout("late-blind", 10);
        var control = checkout("control", 10);

        stub("mv", """
                   mv "$@" || exit $?
                   for a; do dst=$a; done
                   case ${dst##*/} in
                     .gc-*-late-blind) mkdir -p @PROC@/4242; echo evil > @PROC@/4242/comm; echo '4242 (evil) S 1 4242 4242 0 -1' > @PROC@/4242/stat ;;
                   esac
                   """);
        var run = liveWith(stubbed());

        assertThat(control).as(run.stdout()).doesNotExist();
        assertRestored(victim, run, "blind");
    }

    /// A re-check that cannot run restores too (fail closed): an unreadable ticket appears after the move, so the ticket read fails with 70.
    /// Mutation p6a: `|| why=unverifiable` -> `|| why=`, the victim is removed unchecked.
    @Test
    void aRecheckThatCannotRun_sendsTheCheckoutBack() throws Exception {
        skeleton();
        var victim = checkout("zz-unreadable", 10);
        var control = checkout("control", 10);

        stub("mv", """
                   mv "$@" || exit $?
                   for a; do dst=$a; done
                   case ${dst##*/} in
                     .gc-*-zz-unreadable) printf 'id=bad\\n' > @Q@/waiting/bad; chmod 000 @Q@/waiting/bad ;;
                   esac
                   """);
        var run = liveWith(stubbed());

        assumeTrue(!Files.isReadable(home.resolve("work/queue/waiting/bad")), "a user that can read a mode-000 file (root) cannot make a ticket unreadable");
        assertThat(control).as(run.stdout()).doesNotExist();
        assertRestored(victim, run, "unverifiable");
    }

    /// The same fail-closed rule for a leftover: its re-check cannot run (a stub `flock` makes a ticket unreadable once the queue lock is
    /// taken), so it goes back to its name instead of being removed. Mutation p6b: `|| why=unverifiable` -> `|| why=`: removed.
    @Test
    void aLeftoverWhoseRecheckCannotRun_isRestoredNotRemoved() throws Exception {
        skeleton();
        var leftover = Files.move(checkout("lv", 10), co().resolve(".gc-1700000000-lv"));

        Files.writeString(leftover.resolve(".bb/keep"), "");   // only so the main loop does not look at lv again (and need the queue lock)
        stub("flock", """
                      flock "$@" || exit $?
                      case "$1" in -w) printf 'id=bad\\n' > @Q@/waiting/bad; chmod 000 @Q@/waiting/bad ;; esac
                      """);
        var run = liveWith(stubbed());

        assumeTrue(!Files.isReadable(home.resolve("work/queue/waiting/bad")), "a user that can read a mode-000 file (root) cannot make a ticket unreadable");
        assertThat(co().resolve("lv/module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).contains("leftover-restored .gc-1700000000-lv to lv reasons=unverifiable");
        assertNoGcDirectories();
        assertLogHas("LEFTOVER-RESTORED path=" + co().toRealPath() + "/lv from=" + co().toRealPath() + "/.gc-1700000000-lv reasons=unverifiable");
    }

    /// A process takes the leftover as its cwd AFTER the run's first scan and before the leftover's turn (a stub `flock -n`, the run lock, is the
    /// step between them): only the scan in the leftover loop sees it. The leftover is recent, so the main loop keeps it once it is back.
    /// Mutation g5: that scan dropped, the leftover is removed with a process in it.
    @Test
    void aProcessEnteringALeftoverAfterTheFirstScan_keepsIt() throws Exception {
        skeleton();
        Files.move(checkout("held", 1), co().resolve(".gc-1700000000-held"));
        stub("flock", """
                      flock "$@" || exit $?
                      case "$1" in -n) mkdir -p @PROC@/9100; ln -s @CO@/.gc-1700000000-held @PROC@/9100/cwd ;; esac
                      """);
        var run = liveWith(stubbed());

        assertThat(co().resolve("held/module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).contains("leftover-restored .gc-1700000000-held to held reasons=process").doesNotContain("leftover-removed");
        assertNoGcDirectories();
    }

    /// The main loop reads the process table AGAIN for each candidate (a stub `flock -n` adds a process in the checkout after the run's first
    /// reads; the stub `mv` moves the fake process with its directory, as the kernel does). The guard, not the re-check, must decide: the text
    /// says `(changed during the run)`, not `changed-after-move`. Mutation n63: the scan before the queue lock dropped, the re-check sends it back.
    /// (One late change per run: the first candidate's re-reads would hand the second one the news early.)
    @Test
    void aProcessThatAppearsAfterTheFirstReads_isMetByTheGuardNotTheRecheck() throws Exception {
        skeleton();
        var process = checkout("a-process", 10);
        var control = checkout("control", 10);

        stub("flock", """
                      flock "$@" || exit $?
                      case "$1" in -n) mkdir -p @PROC@/9200; ln -s @CO@/a-process @PROC@/9200/cwd ;; esac
                      """);
        stub("mv", """
                   mv "$@" || exit $?
                   for a; do src=$dst; dst=$a; done
                   for l in @PROC@/*/cwd; do [ "$(readlink "$l")" = "$src" ] && ln -sfn "$dst" "$l"; done
                   exit 0
                   """);
        var run = liveWith(stubbed());

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(process.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("keep a-process reasons=process (changed during the run)").doesNotContain("changed-after-move");
        assertLogHas("KEPT path=" + co().toRealPath() + "/a-process reasons=process");
    }

    /// The same for a ticket: it is written after the run's first reads (stub `flock -n`) and the checkout is kept by the ticket read under the
    /// queue lock (`changed during the run`). Mutation n67: that re-read dropped, the re-check sends it back instead (`changed-after-move`).
    @Test
    void aTicketThatAppearsAfterTheFirstReads_isMetByTheGuardNotTheRecheck() throws Exception {
        skeleton();
        var ticket = checkout("a-ticket", 10);
        var control = checkout("control", 10);

        stub("flock", """
                      flock "$@" || exit $?
                      case "$1" in -n) printf 'id=late\\nowner=x\\nco=@CO@/a-ticket\\nsha=a\\n' > @Q@/waiting/late ;; esac
                      """);
        var run = liveWith(stubbed());

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(ticket.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("keep a-ticket reasons=ticket (changed during the run)").doesNotContain("changed-after-move");
        assertLogHas("KEPT path=" + co().toRealPath() + "/a-ticket reasons=ticket");
    }

    /// A keep file appears while the run holds the queue lock for the candidate (a stub `flock -w`, once, after the lock is taken): the
    /// re-classification under the lock keeps it and logs KEPT. Mutation p7: that re-classification dropped, the re-check sends it back instead
    /// (`changed-after-move`): the directory survives either way, so the row pins the reason text and the log line.
    @Test
    void aKeepFileAppearingUnderTheQueueLock_isMetByTheReclassification() throws Exception {
        skeleton();
        var victim = checkout("a-keep", 10);
        var control = checkout("control", 10);
        var probes = probeTheQueueLockAtEveryScan();

        stub("flock", """
                      flock "$@" || exit $?
                      case "$1" in -w) [ -e @Q@/hooked ] || { : > @Q@/hooked; : > @CO@/a-keep/.bb/keep; } ;; esac
                      """);
        var run = liveWith(stubbed());

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(victim.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("keep a-keep reasons=keep-file (changed during the run)").doesNotContain("changed-after-move");
        assertLogHas("KEPT path=" + co().toRealPath() + "/a-keep reasons=keep-file");
        assertEveryProbeFree(probes, 3);   // mutation n68: no release after the keep, the next candidate's scan meets a held lock
    }

    // ---- guard inputs (G2): what each one reads decides what is removed
    private void touch(Path file, double ageDays) throws IOException {
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(Duration.ofSeconds((long)(ageDays * 86400)))));
    }

    /// Each activity signal alone keeps a checkout: .bb/end fresh, or .git/HEAD fresh, while every other signal is 10 days old.
    /// Mutations k4d (.bb/end no longer read) and k4e (.git/HEAD no longer read): the matching victim is removed.
    @Test
    void reason4_aFreshEndFileAlone_orAFreshHeadAlone_keepsTheCheckout() throws Exception {
        skeleton();
        var end = checkout("victim-end", 10);
        var head = checkout("victim-head", 10);
        var control = checkout("control", 10);

        touch(end.resolve(".bb/end"), 0.2);
        touch(head.resolve(".git/HEAD"), 0.2);
        var run = live();

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(end.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(head.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).contains("keep victim-end reasons=recent ", "keep victim-head reasons=recent ");
    }

    /// The window is 3 days when --days is not given, on both sides: a checkout of 2.5 days is kept, one of 3.5 removed (a default of 2 would
    /// list 2.5 d, a default of 4 or 7 would keep 3.5 d); DRY first (candidate / keep lines, `days=3`, `age_days` of the line: floor of the age
    /// in days), then --live with --pace only.
    @Test
    void withoutDays_theWindowIsThreeDays_andTheCandidateLineNamesTheAgeInDays() throws Exception {
        skeleton();
        var young = checkout("age2h", 2.5);
        var old = checkout("age3h", 3.5);
        var older = checkout("age10", 10);
        var dry = gc();

        assertThat(dry.rc()).as(dry.stdout() + dry.stderr()).isZero();
        assertThat(dry.stdout()).contains("keep age2h reasons=recent ", "candidate age3h ", "age_days=3 ", "candidate age10 ", "age_days=10 ", "days=3;");
        assertThat(dry.stdout()).doesNotContain("candidate age2h ");
        var run = gc("--live", "--pace", "0");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(old).as(run.stdout()).doesNotExist();
        assertThat(older).doesNotExist();
        assertThat(young.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("days=3,");
        assertLogHas("RUN live days=3 pace=0");
    }

    /// --days is an integer from 1 to 3650: 0 (a zero window would make every checkout removable) and 3651 are usage errors (rc 2) before any
    /// write, 3650 is accepted. Mutation d7: the lower bound 0, the run goes ahead and removes the idle checkout.
    @Test
    void daysOutsideOneToThreeThousandSixHundredFifty_isRefusedBeforeAnyWrite() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);

        for (var bad : List.of("0", "3651", "00")) {
            var run = gc("--live", "--days", bad, "--pace", "0");

            assertThat(run.rc()).as(bad + "\n" + run.stdout() + run.stderr()).isEqualTo(2);
            assertThat(run.stderr()).contains("usage: bb-co-gc");
            assertThat(idle.resolve("module/target/payload.bin")).as(bad).exists();
        }

        assertThat(home.resolve("work/co-gc.log")).doesNotExist();
        assertThat(gc("--days", "3650").rc()).isZero();
    }

    /// Guard 3 reads the executable and the open files of a process as well as its cwd: one process works outside ~/work/co with its exe in a
    /// checkout, another with an open file in a second checkout. Mutation t4: only the cwd is read, both are removed.
    @Test
    void reason3_aProcessWithOnlyItsExeOrOnlyAnOpenFileInTheCheckout_keepsIt() throws Exception {
        skeleton();
        var exe = checkout("victim-exe", 10);
        var fd = checkout("victim-fd", 10);
        var control = checkout("control", 10);
        var proc = fakeProc();
        var p1 = Files.createDirectories(proc.resolve("7001"));
        var p2 = Files.createDirectories(proc.resolve("7002/fd"));

        Files.createSymbolicLink(p1.resolve("cwd"), home.toRealPath());
        Files.createSymbolicLink(p1.resolve("exe"), exe.toRealPath().resolve("module/target/payload.bin"));
        Files.createSymbolicLink(p2.getParent().resolve("cwd"), home.toRealPath());
        Files.createSymbolicLink(p2.resolve("3"), fd.toRealPath().resolve("module/target/payload.bin"));
        var run = liveWith(Map.of("BB_CO_GC_PROC", proc.toString()));

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(exe.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(fd.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).contains("keep victim-exe reasons=process ", "keep victim-fd reasons=process ");
    }

    /// A ticket that cannot be read means the ticket guard cannot run: the run stops with 70 before it removes or logs anything (a ticket that
    /// names the idle checkout is the one it could not read). Mutation t6: the unreadable ticket is skipped, the checkout is removed.
    @Test
    void anUnreadableTicket_stopsTheRunWith70_andNothingIsRemoved() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);

        ticket("waiting", "w1", "idle");
        var ticket = home.resolve("work/queue/waiting/w1");

        Files.setPosixFilePermissions(ticket, java.util.Set.of());
        assumeTrue(!Files.isReadable(ticket), "a user that can read a mode-000 file (root) cannot make a ticket unreadable");

        for (var args : List.of(List.of("--live", "--pace", "0"), List.<String>of())) {
            var run = gc(args.toArray(String[]::new));

            assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(70);
            assertThat(run.stderr()).contains("cannot read ticket");
            assertThat(idle.resolve("module/target/payload.bin")).exists();
            assertThat(run.stdout()).doesNotContain("candidate", "removed");
        }

        assertThat(home.resolve("work/co-gc.log")).doesNotExist();
    }

    /// No usable process table means the process guard cannot run: rc 65 before any write, DRY and --live alike (the seam names a directory
    /// without `self`; the real /proc has one, so a check that looked at /proc instead would go ahead and remove). Mutation n11.
    @Test
    void anUnusableProcessTable_refusesWith65_andNothingIsRemoved() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);
        var empty = Files.createDirectories(procRoot.resolve("no-self"));

        for (var args : List.of(List.of("--live", "--pace", "0"), List.<String>of())) {
            var run = gcWith(Map.of("BB_CO_GC_PROC", empty.toString()), args.toArray(String[]::new));

            assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(65);
            assertThat(run.stderr()).contains("no " + empty + ": the process guard cannot run");
            assertThat(idle.resolve("module/target/payload.bin")).exists();
        }

        assertThat(home.resolve("work/co-gc.log")).doesNotExist();
    }

    /// A name outside [A-Za-z0-9._-] is kept as `unsafe-name` (a newline would corrupt every list the tool builds), never moved or removed;
    /// a plain file in ~/work/co is `not-direct-child` and survives. Mutation s5: the space name is removed as a checkout.
    @Test
    void anUnsafeName_andAPlainFile_areKept() throws Exception {
        skeleton();
        var spaced = Files.move(checkout("spaced", 10), co().resolve("bad name"));
        var newline = Files.move(checkout("newline", 10), co().resolve("bad\nname"));
        var file = Files.writeString(co().resolve("afile"), "x");
        var control = checkout("control", 10);
        var run = live();

        assertThat(control).as(run.stdout()).doesNotExist();
        assertThat(spaced.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(newline.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(file).exists();
        assertThat(run.stdout()).contains("keep bad name reasons=unsafe-name", "keep afile reasons=not-direct-child").doesNotContain("removed bad", "removed afile");
        assertThat(run.stdout().lines().filter(l -> l.startsWith("keep bad")).count()).as(run.stdout()).isGreaterThanOrEqualTo(2);
    }

    /// ~/work/co may itself be a symlink to a directory named co (hosts keep it on a data disk): checkouts are judged and moved by their
    /// resolved path, so idle ones are removed and a ticketed one is kept. Mutation s6: the unresolved path is compared with the resolved one,
    /// every checkout is `not-direct-child` and nothing is ever freed.
    @Test
    void aSymlinkedCoDirectory_isStillWorked() throws Exception {
        var real = Files.createDirectories(home.resolve("data/co"));

        Files.createDirectories(home.resolve("work/queue/waiting"));
        Files.createDirectories(home.resolve("work/queue/running"));
        Files.createSymbolicLink(home.resolve("work/co"), real);
        var held = checkout("held", 10);
        var idle = checkout("idle", 10);

        ticket("waiting", "w1", "held");
        var run = live();

        assertThat(idle).as(run.stdout()).doesNotExist();
        assertThat(held.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("removed idle ", "keep held reasons=ticket ");
        assertLogHas("REMOVED path=" + real.toRealPath() + "/idle size_mb=0 last_activity=" + run.stdout().lines()
                                                                                              .filter(l -> l.startsWith("removed idle "))
                                                                                              .map(l -> l.replaceFirst(".* newest=(\\S+) .*", "$1"))
                                                                                              .findFirst().orElseThrow() + " owner=- days=3");
    }

    // ---- leftovers (G3)
    /// An `id` stub that records, at every call, whether the queue lock is free. The tool calls `id -u` at the start of each /proc scan and
    /// documents that it never holds the queue lock across a scan: every probe must say `free`. Returns the probe log.
    private Path probeTheQueueLockAtEveryScan() throws IOException {
        var probes = procRoot.resolve("probes.log");

        stub("id", "if flock -n @Q@/.lock true 2> /dev/null; then echo free; else echo busy; fi >> " + probes + "\nexec id \"$@\"\n");

        return probes;
    }

    private void assertEveryProbeFree(Path probes, int atLeast) throws IOException {
        var seen = Files.exists(probes) ? Files.readAllLines(probes) : List.<String>of();

        assertThat(seen).as("the queue lock at each scan").hasSizeGreaterThanOrEqualTo(atLeast).containsOnly("free");
    }

    /// A leftover that is a symlink, a dangling symlink or a plain file is skipped, with its name, and never followed, restored or removed.
    /// Mutation g3: the check dropped, `rm -rf` takes the link and the file away.
    @Test
    void aLeftoverThatIsNotADirectory_isSkippedAndNeverTouched() throws Exception {
        skeleton();
        var target = Files.move(checkout("target", 10), home.resolve("elsewhere"));
        var link = Files.createSymbolicLink(co().resolve(".gc-1700000000-lnk"), target);
        var dangling = Files.createSymbolicLink(co().resolve(".gc-1700000000-dangling"), home.resolve("nothing"));
        var file = Files.writeString(co().resolve(".gc-1700000000-file"), "x");
        var before = snapshot(target);
        var run = live();

        assertThat(Files.isSymbolicLink(link)).as(run.stdout()).isTrue();
        assertThat(Files.isSymbolicLink(dangling)).as(run.stdout()).isTrue();
        assertThat(file).exists();
        assertThat(snapshot(target)).isEqualTo(before);
        assertThat(run.stdout()).contains("leftover-skipped .gc-1700000000-lnk reason=not-a-directory",
                                          "leftover-skipped .gc-1700000000-dangling reason=not-a-directory",
                                          "leftover-skipped .gc-1700000000-file reason=not-a-directory")
                  .doesNotContain("leftover-removed");
    }

    /// Only `.gc-<digits...>-<name>` is a leftover: a directory that merely starts with `.gc-` is somebody's and stays, and so does one whose
    /// name has a character outside [A-Za-z0-9._-]. Mutations g4 (glob `.gc-*`) and g6 (the name-class check dropped): both are removed.
    @Test
    void aDirectoryThatOnlyLooksLikeALeftover_isLeftAlone() throws Exception {
        skeleton();
        var notes = Files.createDirectories(co().resolve(".gc-notes/module"));
        var odd = Files.createDirectories(co().resolve(".gc-1700000000-has space/module"));

        Files.writeString(notes.resolve("payload.bin"), "x");
        Files.writeString(odd.resolve("payload.bin"), "x");
        var run = live();

        assertThat(notes.resolve("payload.bin")).as(run.stdout()).exists();
        assertThat(odd.resolve("payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).doesNotContain("leftover");
        assertThat(Files.readString(home.resolve("work/co-gc.log"))).doesNotContain("LEFTOVER");
    }

    /// A leftover whose name has nothing after `.gc-<epoch>-` has no original name to go back to and is not checked against any ticket: kept in
    /// place, logged, never removed. Mutation n52: the reason dropped, it is removed.
    @Test
    void aLeftoverWithoutAnOriginalName_staysInPlace() throws Exception {
        skeleton();
        var nameless = Files.createDirectories(co().resolve(".gc-1700000000-/module"));

        Files.writeString(nameless.resolve("payload.bin"), "x");
        var run = live();

        assertThat(nameless.resolve("payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).contains("leftover-kept .gc-1700000000- reasons=no-original-name (stays in place; never removed)");
        assertLogHas("LEFTOVER-KEPT path=" + co().toRealPath() + "/.gc-1700000000- reasons=no-original-name");
    }

    /// A held leftover whose name is a DANGLING symlink cannot go back either (`-e` is false for it, `-L` is not): it stays, rc 0, logged.
    /// Mutation n56: only `-e` is tested, the move-back is tried onto the link, fails, and the run ends 74 with RESTORE-FAILED.
    @Test
    void aHeldLeftoverWhoseNameIsADanglingSymlink_staysInPlace() throws Exception {
        skeleton();
        var leftover = Files.move(checkout("dup", 10), co().resolve(".gc-1700000000-dup"));

        Files.writeString(leftover.resolve(".bb/keep"), "");
        var link = Files.createSymbolicLink(co().resolve("dup"), home.resolve("nothing"));
        var run = live();

        assertThat(leftover.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(run.stdout()).contains("leftover-kept .gc-1700000000-dup reasons=keep-file (stays in place; never removed)")
                  .doesNotContain("restore failed");
        assertLogHas("LEFTOVER-KEPT path=" + co().toRealPath() + "/.gc-1700000000-dup reasons=keep-file");
    }

    /// A move-back that fails is a failure: logged LEFTOVER-RESTORE-FAILED, said as such, counted in the total, and the run ends 74. The leftover
    /// stays where it is. (A stub `mv` refuses every move that does not make a .gc-* directory.) Mutation n59: the failure is not counted, rc 0.
    @Test
    void aFailedMoveBackOfAHeldLeftover_isAFailure() throws Exception {
        skeleton();
        var leftover = Files.move(checkout("held", 10), co().resolve(".gc-1700000000-held"));

        Files.writeString(leftover.resolve(".bb/keep"), "");
        stub("mv", """
                   for a; do dst=$a; done
                   case ${dst##*/} in .gc-*) exec mv "$@" ;; esac
                   exit 1
                   """);
        var run = gcWith(stubbed(), "--live", "--days", "3", "--pace", "0");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(74);
        assertThat(leftover.resolve("module/target/payload.bin")).exists();
        assertThat(co().resolve("held")).doesNotExist();
        assertThat(run.stdout()).contains("leftover-kept .gc-1700000000-held reasons=keep-file (restore failed)", "failed 1,");
        assertLogHas("LEFTOVER-RESTORE-FAILED path=" + co().toRealPath() + "/.gc-1700000000-held to=" + co().toRealPath() + "/held reasons=keep-file");
    }

    /// A leftover whose turn finds the queue lock busy is SKIPPED, not removed and not a failure: said, logged and counted. (A stub `flock -w`
    /// answers busy at once, the wait itself is the row of aQueueLockHeldPastTheWait.) Mutations n49 (the lock is optional: the leftover is
    /// removed unlocked) and n50 (the skip is not counted).
    @Test
    void aLeftoverWhoseQueueLockIsBusy_isSkippedAndCounted() throws Exception {
        skeleton();
        var leftover = Files.move(checkout("busy", 10), co().resolve(".gc-1700000000-busy"));

        stub("flock", """
                      case "$1" in -w) exit 1 ;; esac
                      exec flock "$@"
                      """);
        var run = liveWith(stubbed());

        assertThat(leftover.resolve("module/target/payload.bin")).as(run.stdout()).exists();
        assertThat(run.stdout()).contains("leftover-skipped .gc-1700000000-busy reason=queue-lock-busy", "skipped 1,");
        assertLogHas("SKIP path=" + co().toRealPath() + "/.gc-1700000000-busy reason=queue-lock-busy");
        assertLogHas("skipped=1 kept=0 blind=0 unlisted_blind=0");
    }

    /// DRY says where a held leftover would go or stay but writes nothing: no log, no lock file, not even for a leftover that stays.
    /// Mutation n61: the LEFTOVER-KEPT log line is written in DRY too.
    @Test
    void dry_aHeldLeftoverWhoseNameIsTaken_isSaidButNotLogged() throws Exception {
        skeleton();
        var leftover = Files.move(checkout("dup", 10), co().resolve(".gc-1700000000-dup"));

        Files.writeString(leftover.resolve(".bb/keep"), "");
        checkout("dup", 1);
        var run = gc();

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("leftover-kept .gc-1700000000-dup reasons=keep-file (stays in place; never removed)");
        assertThat(home.resolve("work/co-gc.log")).doesNotExist();
        assertThat(home.resolve("work/.co-gc.lock")).doesNotExist();
    }

    /// The queue lock is let go at the end of every leftover's turn, whatever came of it: a held leftover that goes back, a removed leftover, then
    /// a candidate, each starts its /proc scan with the lock free. Mutations n54 (no release after a removable leftover) and n62 (none at the end
    /// of a held one) leave it held across the next scan.
    @Test
    void theQueueLock_isFreeAtEveryScan_afterLeftovers() throws Exception {
        skeleton();
        var held = Files.move(checkout("a-held", 10), co().resolve(".gc-1700000000-a-held"));
        var plain = Files.move(checkout("b-plain", 10), co().resolve(".gc-1700000000-b-plain"));

        Files.writeString(held.resolve(".bb/keep"), "");
        var control = checkout("control", 10);
        var probes = probeTheQueueLockAtEveryScan();
        var run = liveWith(stubbed());

        assertThat(plain).as(run.stdout()).doesNotExist();
        assertThat(control).doesNotExist();
        assertThat(co().resolve("a-held/module/target/payload.bin")).exists();
        assertEveryProbeFree(probes, 4);
    }

    /// The queue lock is let go on every way out of a candidate's turn: a target name that is taken (SKIP), a move that fails (MOVE-FAILED) and a
    /// removal, each followed by a /proc scan (the next candidate's, the post-move one) that finds it free. Mutations n72 (no release on the
    /// skip), n73 (none on the failed move), n70 (none after the move: the post-move scan runs under the lock), n29 (qunlock does nothing).
    /// The clash is made by the `flock -w` stub once, under the lock: .gc-<now..now+2>-a-skip exist, so whichever second the tool reads is taken.
    @Test
    void theQueueLock_isFreeAfterASkipAFailedMoveAndARemoval() throws Exception {
        skeleton();
        var skipped = checkout("a-skip", 10);
        var stuck = checkout("b-mvfail", 10);
        var removed = checkout("c-remove", 10);
        var last = checkout("d-last", 10);
        var probes = probeTheQueueLockAtEveryScan();

        stub("flock", """
                      flock "$@" || exit $?
                      case "$1" in -w) [ -e @Q@/hooked ] || { : > @Q@/hooked; t=$(date +%s); for e in $t $((t + 1)) $((t + 2)); do mkdir @CO@/.gc-$e-a-skip; done; } ;; esac
                      """);
        stub("mv", """
                   case "$*" in "-T -- "*"/b-mvfail "*"/.gc-"*) echo "mv: stubbed failure" >&2; exit 1 ;; esac
                   exec mv "$@"
                   """);
        var run = gcWith(stubbed(), "--live", "--days", "3", "--pace", "0");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(74);
        assertThat(skipped.resolve("module/target/payload.bin")).exists();
        assertThat(stuck.resolve("module/target/payload.bin")).exists();
        assertThat(removed).as(run.stdout()).doesNotExist();
        assertThat(last).doesNotExist();
        var real = co().toRealPath();

        assertThat(logLines()).anyMatch(l -> l.contains("SKIP path=" + real + "/a-skip reason="))
                              .anyMatch(l -> l.endsWith("MOVE-FAILED path=" + real + "/b-mvfail"));
        assertEveryProbeFree(probes, 6);
    }

    // ---- what a run says and does (G7)
    /// One removal, end to end: the size, owner (the FIRST of two ticket-log rows that end together), days and the totals reach the stdout and the log;
    /// the move goes to `.gc-<epoch>-<name>` exactly and every rm is limited to one file system. Mutations o3, o12 (size 0), o13 (days fixed at 3),
    /// t5 (the later of two equal rows owns it), n77 (the RUN end line without its counts), o5 (the move target gets a suffix) and o8 (rm without
    /// --one-file-system: the flag is pinned on the call, a bind mount cannot be made without root).
    @Test
    void aRemoval_isReportedAndLogged_withSizeOwnerDaysAndTotals() throws Exception {
        skeleton();
        var big = checkout("big", 10);
        var neighbour = checkout("neighbour", 1);
        var ended = Instant.now().minus(Duration.ofDays(9));

        var data = new byte[5 * 1024 * 1024];

        new Random(7).nextBytes(data);   // incompressible: zeros measure 0 MB on a compressing file system (bigboy)
        Files.write(big.resolve("module/target/big.bin"), data);
        ticketLog("big", "first", ended);
        ticketLog("big", "second", ended);
        var mvs = procRoot.resolve("mv.calls");
        var rms = procRoot.resolve("rm.calls");

        stub("mv", "echo \"$*\" >> " + mvs + "\nexec mv \"$@\"\n");
        stub("rm", "echo \"$*\" >> " + rms + "\nexec rm \"$@\"\n");
        var run = gcWith(stubbed(), "--live", "--days", "5", "--pace", "0");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(big).doesNotExist();
        assertThat(neighbour.resolve("module/target/payload.bin")).exists();
        assertThat(run.stdout()).contains("removed big size_mb=5 newest=", " owner=first", "total: removed 1 of 1 candidates, freed 5 MB, failed 0, skipped 0, kept 1 (recent=1), days=5, blind=0 unlisted-blind=0");
        var real = co().toRealPath();

        assertThat(logLines()).anyMatch(l -> l.contains(" REMOVED path=" + real + "/big size_mb=5 last_activity=") && l.endsWith(" owner=first days=5"));
        assertLogHas("RUN end removed=1 candidates=1 freed_mb=5 failed=0 skipped=0 kept=1 blind=0 unlisted_blind=0");
        assertThat(Files.readAllLines(mvs)).anyMatch(l -> l.matches("-T -- \\S+/big \\S+/\\.gc-\\d+-big"));
        assertThat(Files.readAllLines(rms)).isNotEmpty().allMatch(l -> l.contains("--one-file-system"));
    }

    /// A removal that fails (the move is done, the rm is not) is logged RM-FAILED with the moved path, counted, left in place for the next run to
    /// take as a leftover, and the run ends with 74 although nothing was skipped; the checkout that can be removed still is. Mutations o9 (exit 0)
    /// and n80 (74 only when a skip came with the failure).
    @Test
    void aFailedRemoval_isLoggedCounted_andTheRunExitsWith74() throws Exception {
        skeleton();
        var bad = checkout("bad", 10);
        var good = checkout("good", 10);

        stub("rm", "case \"$*\" in *-bad) exit 1 ;; esac\nexec rm \"$@\"\n");
        var run = gcWith(stubbed(), "--live", "--days", "3", "--pace", "0");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(74);
        assertThat(good).as(run.stdout()).doesNotExist();
        assertThat(bad).doesNotExist();
        var real = co().toRealPath();

        assertThat(logLines()).anyMatch(l -> l.contains(" RM-FAILED path=" + real + "/bad moved=" + real + "/.gc-"));
        assertThat(run.stdout()).contains("failed bad ", "total: removed 1 of 2 candidates", "failed 1, skipped 0");
        assertLogHas("failed=1 skipped=0 kept=0 blind=0 unlisted_blind=0");
        try (var names = Files.list(co())) {
            assertThat(names.map(x -> x.getFileName().toString()).toList()).hasSize(1).allMatch(x -> x.startsWith(".gc-") && x.endsWith("-bad"));
        }
    }

    /// A directory without write permission blocks the removal of its contents: the tool makes the tree writable and removes again. Mutation o7
    /// (no chmod: RM-FAILED, exit 74). Not run as root, where permissions do not bind.
    @Test
    void aReadOnlyDirectoryInACheckout_isMadeWritableAndRemoved() throws Exception {
        skeleton();
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root ignores directory permissions");
        var ro = checkout("ro", 10);
        var locked = Files.createDirectories(ro.resolve("module/locked"));

        Files.writeString(locked.resolve("f"), "x");
        Files.setPosixFilePermissions(locked, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        var run = live();

        assertThat(ro).as(run.stdout()).doesNotExist();
        assertThat(run.stdout()).contains("removed ro ", "failed 0");
    }

    /// BB_CO_GC_LOCK_WAIT is an integer: anything else is refused with 65 before any write. Mutation n08 (the check dropped: bash fails on the
    /// arithmetic instead, rc is not 65). n09 (10#) is equivalent: flock reads the number as decimal, 08 and 8 wait the same.
    @Test
    void aLockWaitThatIsNotAnInteger_isRefusedWith65_beforeAnyWrite() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);

        for (var bad : List.of("abc", "1.5", "-1", "1x")) {
            var run = gcWith(Map.of("BB_CO_GC_LOCK_WAIT", bad), "--live", "--days", "3", "--pace", "0");

            assertThat(run.rc()).as(bad + "\n" + run.stdout() + run.stderr()).isEqualTo(65);
            assertThat(run.stderr()).contains("BB_CO_GC_LOCK_WAIT is not an integer");
            assertThat(idle.resolve("module/target/payload.bin")).as(bad).exists();
        }

        assertThat(home.resolve("work/co-gc.log")).doesNotExist();
    }

    /// The host cap is a positive integer on its own line: `0` is no cap (the default 3 applies, one running ticket does not defer); with no
    /// ticket in running/ nothing counts as running even under cap 1. Mutations n44 (0 accepted as a cap: 1 running defers) and n46 (the empty
    /// glob counted as a ticket: running=1 under cap 1).
    @Test
    void theHostCap_isPositive_andAnEmptyRunningDirectoryCountsNothing() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);

        Files.writeString(home.resolve("work/max-reactors"), "1\n");
        var empty = live();

        assertThat(idle).as(empty.stdout()).doesNotExist();
        assertThat(empty.stdout()).doesNotContain("deferred");
        var again = checkout("again", 10);

        ticket("running", "r1", "busy1");
        Files.writeString(home.resolve("work/max-reactors"), "0\n");
        var zero = live();

        assertThat(again).as(zero.stdout()).doesNotExist();
        assertThat(zero.stdout()).doesNotContain("deferred").contains("removed again ");
    }

    /// The DRY total names the unreadable processes too (an unlisted one among them), and still removes nothing. Mutation n76.
    @Test
    void dry_totalNamesTheBlindProcesses() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);
        var run = gcWith(fakeProcWithBlindProcess("rogue"), "--days", "3");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("keep idle reasons=blind", "blind=1 unlisted-blind=1; nothing removed");
        assertThat(idle.resolve("module/target/payload.bin")).exists();
    }

    // ---- run exclusion (G4)
    /// Starts a child that holds `flock` on `lock` until `release` exists; returns once it holds it (polled, 5 s bound, no sleep as synchronisation).
    private Process holdFlock(Path lock, Path release) throws Exception {
        var held = lock.resolveSibling(lock.getFileName() + ".held");
        var holder = BbFixtures.process("flock", lock.toString(), "sh", "-c", "touch " + held + "; while [ ! -e " + release + " ]; do sleep 0.1; done").start();

        for (var i = 0; i < 100 && !Files.exists(held); i++) {
            Thread.sleep(50);
        }

        assertThat(held).as("control: the flock child holds " + lock).exists();

        return holder;
    }

    /// Two --live runs never overlap: while another run holds ~/work/.co-gc.lock a live run exits 75 at once, before the deferral check, before
    /// its log line, and removes nothing; a DRY run takes no lock and still reads; once the holder lets go the same run removes.
    /// Mutations k-lock-off and l3 (no lock taken: the run removes, or is deferred with 0 instead of 75), k-lock-rc (exit 1).
    @Test
    void aSecondLiveRun_exitsWith75_whileTheRunLockIsHeld() throws Exception {
        skeleton();
        var idle = checkout("idle", 10);
        var release = home.resolve("release");
        var holder = holdFlock(home.resolve("work/.co-gc.lock"), release);

        try {
            var run = gc("--live", "--days", "3", "--pace", "0");

            assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(75);
            assertThat(run.stderr()).contains("another run holds");
            assertThat(idle.resolve("module/target/payload.bin")).exists();
            assertThat(home.resolve("work/co-gc.log")).doesNotExist();
            ticket("running", "r1", "busy", "group=1");
            var deferred = gc("--live", "--days", "3", "--pace", "0");

            assertThat(deferred.rc()).as("the lock comes before the deferral check\n" + deferred.stdout() + deferred.stderr()).isEqualTo(75);
            assertThat(deferred.stdout()).doesNotContain("deferred");
            assertThat(gc("--days", "3").rc()).as("DRY takes no lock").isZero();
            Files.delete(home.resolve("work/queue/running/r1"));
        } finally {
            Files.writeString(release, "go");
            // the holder leaves by itself: a forcibly killed flock child leaves its `sleep` holding the lock for up to 0.1 s
            if (!holder.waitFor(10, TimeUnit.SECONDS)) {
                stop(holder);
            }
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
