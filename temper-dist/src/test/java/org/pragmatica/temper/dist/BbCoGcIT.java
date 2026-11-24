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
        assumeTrue(BbFixtures.linux(), "bb-co-gc reads /proc and uses GNU stat, date, mv, flock");
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("USER", System.getProperty("user.name"));
        var command = new java.util.ArrayList<String>();

        command.add(BbFixtures.OPS_BB.resolve("bb-co-gc").toString());
        command.addAll(List.of(args));

        return BbFixtures.run(command, env, home, null);
    }

    private BbFixtures.Outcome live() throws Exception {
        var run = gc("--live", "--days", "3");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();

        return run;
    }

    /// Runs live with a clean `control`, then asserts the victim is kept for exactly `reason` and untouched, and the control is gone.
    private void assertKeptOnlyFor(Path victim, String reason) throws Exception {
        var control = checkout("control", 10);
        var run = live();

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

    /// Reason 3: a process with its cwd under the checkout keeps it.
    @Test
    void reason3_aProcessWorkingInTheCheckout_keepsIt() throws Exception {
        skeleton();
        var victim = checkout("victim", 10);
        var sleeper = new ProcessBuilder("sleep", "120").directory(victim.resolve("module").toFile()).start();

        try {
            assumeTrue(sleeper.isAlive());
            assertKeptOnlyFor(victim, "process");
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor(10, TimeUnit.SECONDS);
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
