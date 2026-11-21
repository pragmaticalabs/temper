package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// Issue #80: `ops/bb/bb-build` dedup identifies the checkout's current result by the run's TICKET (`.bb/ticket`), not by its start second.
/// A `date` stub first on PATH answers `+%s` with one fixed epoch, so every launch in the test shares a start second on demand; every other
/// `date` call (ticket ids, `-Is`, `-d`) is passed through to the real one.
///
/// Dedup decision for a checkout without `.bb/ticket` (a run launched before this change): NOT current, so no dedup. That is the safe direction:
/// the cost is one redundant build, never a stale answer.
///
/// The same weakness in three more places, each moved to the ticket id: bb-wait's RC-MISMATCH check, bb-result's "is this still the checkout's run"
/// test and bb-build's "rc deleted by hand" refusal. Without `.bb/ticket` (a pre-#80 run) the safe direction is per place: bb-wait skips the
/// RC-MISMATCH comparison (its verdict is the ticket's rc either way, so only the tamper check is lost); bb-result says `unavailable` (counts of an
/// unidentifiable run are worse than none); bb-build keeps the plain running message (never a hand deletion guessed onto a ticket).
///
/// Run identity after a run that never reached maven (never_started: cancel, queue-wait expiry, abandon) and after a BB_QUEUE=0 handoff:
/// the checkout's `.bb/ticket` must not keep naming the previous, finished run while `.bb/rc` and `.bb/start` describe something else.
/// The stale file is SEEDED in the three never_started rows: bb-build refuses a second enqueue while one is queued and clears the file at
/// enqueue, so no ordered real run leaves it there; never_started owns the contract "`.bb` describes a run that never started" on its own.
///
/// Probes (each red, reverted): compare `.bb/start` to the ticket's `started=` again -> [#aSecondBuildInTheSameSecond_doesNotMakeTheFirstOneCurrent];
/// stop writing `.bb/ticket` -> [#aRepeatWithNothingBetween_isStillDeduped];
/// never_started without its rm -> the three never-started rows; enqueue without its rm -> [#enqueue_clearsTheTicketFile_soThePreviousRunIsUnavailableWhileTheNextOneWaits];
/// the BB_QUEUE=0 pre-handoff rm removed -> [#aQueueZeroHandoff_removesTheTicketFile_soTheOlderQueuedRunIsNotCurrent].
class BbSameSecondIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();
    private Path co;
    private Map<String, String> env;

    @BeforeEach
    void linuxOnly() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var tools = dir.resolve("tools");

        BbFixtures.executable(tools.resolve("bb-build"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(BbFixtures.OPS_BB.resolve("bb-wait"), tools.resolve("bb-wait"), StandardCopyOption.REPLACE_EXISTING);
        tools.resolve("bb-wait").toFile().setExecutable(true);
        Files.copy(BbFixtures.OPS_BB.resolve("bb-result"), tools.resolve("bb-result"), StandardCopyOption.REPLACE_EXISTING);
        BbFixtures.executable(tools.resolve("bb-queue"), Files.readString(BbFixtures.OPS_BB.resolve("bb-queue")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-portgroup.py"), tools.resolve("bb-portgroup.py"), StandardCopyOption.REPLACE_EXISTING);
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\ncase \"$*\" in *failing*) exit 3;; esac\n");

        BbFixtures.executable(dir.resolve("fakebin/date"),
                              "#!/bin/sh\nif [ \"$*\" = \"+%s\" ]; then echo 1700000000; else exec /bin/date \"$@\"; fi\n");
        co = dir.resolve("work").resolve("ss-co");
        initRepo(co);
        env = new HashMap<>();
        env.put("PATH", dir.resolve("fakebin") + ":" + System.getenv("PATH"));
        env.put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "ss");
        env.put("BB_TEST_QUEUE_ROOT", dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
    }

    @AfterEach
    void killWaiters() throws Exception {
        for (var id : tickets) {
            BbFixtures.kill(BbFixtures.waiters(id));
        }
    }

    private static void initRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        Files.writeString(repo.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", repo.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }
    }

    private record Build(String ticket, BbFixtures.Outcome outcome) {}

    /// Enqueue and, when it was queued, wait for the build and its ticket to finish.
    private Build build(String... args) throws Exception {
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(), co.toString()));

        command.addAll(List.of(args));
        var run = BbFixtures.run(command, env, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        if (!matcher.find()) {
            return new Build(null, run);
        }

        var id = matcher.group(1);

        tickets.add(id);
        var rc = co.resolve(".bb/rc");

        for (var i = 0; i < 300 && !Files.exists(rc); i++) {
            Thread.sleep(100);
        }

        assertThat(rc).as("the build finished").exists();
        var done = dir.resolve("q/done").resolve(id);

        for (var i = 0; i < 100 && !Files.exists(done); i++) {
            Thread.sleep(100);
        }

        assertThat(done).as("the ticket reached done/").exists();

        return new Build(id, run);
    }

    @Test
    void aRepeatWithNothingBetween_isStillDeduped() throws Exception {
        var first = build("-B", "verify");

        assertThat(first.ticket()).isNotNull();
        var again = build("-B", "verify");

        assertThat(again.outcome().rc()).as(again.outcome().stdout() + again.outcome().stderr()).isEqualTo(8);
        assertThat(again.outcome().stderr()).contains("identical build already finished");
    }

    @Test
    void aSecondBuildInTheSameSecond_doesNotMakeTheFirstOneCurrent() throws Exception {
        var first = build("-B", "verify");
        var between = build("-B", "test");

        assertThat(first.ticket()).isNotNull();
        assertThat(between.ticket()).isNotNull();
        assertThat(Files.readString(co.resolve(".bb/start")).strip()).as("the premise: both runs share one second").isEqualTo("1700000000");
        var again = build("-B", "verify");

        assertThat(again.outcome().rc()).as("deduped onto a result .bb no longer holds: " + again.outcome().stderr()).isZero();
        assertThat(again.ticket()).isNotNull().isNotEqualTo(first.ticket());
        assertThat(Files.readString(co.resolve(".bb/ticket")).strip()).isEqualTo(again.ticket());
    }

    private BbFixtures.Outcome wait(String ticket) throws Exception {
        var e = new HashMap<>(env);

        e.put("BB_WAIT_INTERVAL", "15");

        return BbFixtures.run(List.of(dir.resolve("tools/bb-wait").toString(), ticket), e, null, null);
    }

    private BbFixtures.Outcome result(String ticket) throws Exception {
        return BbFixtures.run(List.of("python3", dir.resolve("tools/bb-result").toString(), ticket), env, null, null);
    }

    private void report() throws Exception {
        var xml = co.resolve("target/surefire-reports/TEST-x.xml");

        Files.createDirectories(xml.getParent());
        Files.writeString(xml, "<testsuite name=\"s\" tests=\"4\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"c\" name=\"n\"/></testsuite>\n");
    }

    private void withoutTicketFile() throws Exception {
        Files.delete(co.resolve(".bb/ticket"));
    }

    @Test
    void bbWait_ofAnEarlierRunSharingTheStartSecond_isNotAMismatchAgainstTheLaterRunsRc() throws Exception {
        var first = build("-B", "verify");
        var between = build("-B", "failing");

        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).as("the premise: the later run's rc is in .bb").isEqualTo("3");
        var waited = wait(first.ticket());

        assertThat(waited.rc()).as(waited.stdout() + waited.stderr()).isZero();
        assertThat(waited.stderr()).doesNotContain("RC-MISMATCH");
        assertThat(wait(between.ticket()).rc()).isEqualTo(3);
    }

    @Test
    void bbWait_withoutBbTicket_skipsTheMismatchCheck_andStillReturnsTheTicketsRc() throws Exception {
        var first = build("-B", "verify");

        withoutTicketFile();
        Files.delete(co.resolve(".bb/rc"));
        Files.writeString(co.resolve(".bb/rc"), "7\n");
        var waited = wait(first.ticket());

        assertThat(waited.rc()).as(waited.stdout() + waited.stderr()).isZero();
        assertThat(waited.stderr()).doesNotContain("RC-MISMATCH");
    }

    @Test
    void bbResult_ofAnEarlierRunSharingTheStartSecond_isUnavailable_neverTheLaterRunsCounts() throws Exception {
        var first = build("-B", "verify");

        build("-B", "failing");
        report();
        var out = result(first.ticket());

        assertThat(out.rc()).as(out.stdout() + out.stderr()).isZero();
        assertThat(out.stdout()).contains("tests: unavailable").doesNotContain("tests: 4");
    }

    @Test
    void bbResult_withoutBbTicket_isUnavailable() throws Exception {
        var first = build("-B", "verify");

        withoutTicketFile();
        report();
        var out = result(first.ticket());

        assertThat(out.stdout()).as(out.stdout() + out.stderr()).contains("tests: unavailable").doesNotContain("tests: 4");
    }

    private BbFixtures.Outcome rerunWithRcGone() throws Exception {
        Files.deleteIfExists(co.resolve(".bb/rc"));
        var e = new HashMap<>(env);

        e.put("BB_DEDUP", "0");

        return BbFixtures.run(List.of(dir.resolve("tools/bb-build").toString(), co.toString(), "-B", "again"), e, null, null);
    }

    @Test
    void aDeletedRc_afterAnEarlierRunSharedItsSecond_isNotBlamedOnTheEarlierRun() throws Exception {
        var first = build("-B", "verify");
        var later = "1700000000000000000-1";

        Files.writeString(dir.resolve("q/done").resolve(later),
                          "id=" + later + "\nowner=ss\nco=" + co + "\ncreated=1\ngroup=0\nargs=-B x \nstarted=1700000000\nstate=orphaned\nended=1700000001\n");
        Files.writeString(co.resolve(".bb/ticket"), later + "\n");
        var refused = rerunWithRcGone();

        assertThat(refused.rc()).as(refused.stdout() + refused.stderr()).isEqualTo(3);
        assertThat(refused.stderr()).contains("a build appears to be running").doesNotContain("deleted by hand").doesNotContain(first.ticket());
    }

    @Test
    void aDeletedRc_withoutBbTicket_keepsTheRunningMessage_neverAGuessedHandDeletion() throws Exception {
        build("-B", "verify");
        withoutTicketFile();
        var refused = rerunWithRcGone();

        assertThat(refused.rc()).as(refused.stdout() + refused.stderr()).isEqualTo(3);
        assertThat(refused.stderr()).contains("a build appears to be running").doesNotContain("deleted by hand");
    }

    @Test
    void aDeletedRc_ofTheRunThatOwnsBb_isStillNamedAsAHandDeletion() throws Exception {
        var first = build("-B", "verify");
        var refused = rerunWithRcGone();

        assertThat(refused.rc()).as(refused.stdout() + refused.stderr()).isEqualTo(3);
        assertThat(refused.stderr()).contains("was deleted by hand after ticket " + first.ticket() + " finished");
    }

    private Build blocked(Map<String, String> more) throws Exception {
        var e = new HashMap<>(env);

        e.put("BB_MAX_REACTORS", "0");
        e.putAll(more);
        var run = BbFixtures.run(List.of(dir.resolve("tools/bb-build").toString(), co.toString(), "-B", "blocked"), e, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        assertThat(matcher.find()).as(run.stdout() + run.stderr()).isTrue();
        tickets.add(matcher.group(1));

        return new Build(matcher.group(1), run);
    }

    private BbFixtures.Outcome queue(String... args) throws Exception {
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-queue").toString()));

        command.addAll(List.of(args));

        return BbFixtures.run(command, env, null, null);
    }

    private enum Never { CANCEL, EXPIRY, ABANDON }

    /// A finished run `old`; then a waiting ticket in the same checkout that never starts (the way `how` says), with `old`'s ticket seeded as the stale file.
    private Build neverStartedAfterAFinishedRun(Never how) throws Exception {
        var old = build("-B", "test");
        var waiting = blocked(how == Never.EXPIRY ? Map.of("BB_CAP_WAIT_SECS", "10") : Map.of());

        Files.writeString(co.resolve(".bb/ticket"), old.ticket() + "\n");
        switch (how) {
            case CANCEL -> assertThat(queue("cancel", waiting.ticket()).rc()).isZero();
            case EXPIRY -> awaitDone(waiting.ticket());
            case ABANDON -> {
                BbFixtures.kill(BbFixtures.waiters(waiting.ticket()));
                for (var i = 0; i < 100 && !BbFixtures.waiters(waiting.ticket()).isEmpty(); i++) {
                    Thread.sleep(100);
                }
                assertThat(queue("reap").stdout()).contains("reaped " + waiting.ticket());
            }
        }
        awaitDone(waiting.ticket());

        return old;
    }

    private void awaitDone(String ticket) throws Exception {
        var done = dir.resolve("q/done").resolve(ticket);

        for (var i = 0; i < 400 && !Files.exists(done); i++) {
            Thread.sleep(100);
        }

        assertThat(done).as("the waiting ticket reached done/").exists();
    }

    private void oldRunIsNoLongerCurrent(Build old) throws Exception {
        assertThat(co.resolve(".bb/ticket")).as("(a) no run owns the rc a never-started ticket wrote").doesNotExist();
        var waited = wait(old.ticket());

        assertThat(waited.rc()).as("(b) " + waited.stdout() + waited.stderr()).isZero();
        assertThat(waited.stderr()).doesNotContain("RC-MISMATCH");
        report();
        var out = result(old.ticket());

        assertThat(out.stdout()).as("(c) " + out.stdout() + out.stderr()).contains("tests: unavailable").doesNotContain("tests: 4");
        var again = build("-B", "test");

        assertThat(again.outcome().rc()).as("(d) deduped onto a result .bb no longer holds: " + again.outcome().stderr()).isZero();
        assertThat(again.ticket()).isNotNull();
    }

    @Test
    void aCancelledWaitingTicket_leavesNoTicketFile_soTheFinishedRunBeforeItIsNotCurrent() throws Exception {
        oldRunIsNoLongerCurrent(neverStartedAfterAFinishedRun(Never.CANCEL));
    }

    @Test
    void anExpiredQueueWait_leavesNoTicketFile_soTheFinishedRunBeforeItIsNotCurrent() throws Exception {
        oldRunIsNoLongerCurrent(neverStartedAfterAFinishedRun(Never.EXPIRY));
    }

    @Test
    void anAbandonedWaitingTicket_leavesNoTicketFile_soTheFinishedRunBeforeItIsNotCurrent() throws Exception {
        oldRunIsNoLongerCurrent(neverStartedAfterAFinishedRun(Never.ABANDON));
    }

    /// What reads `.bb/ticket` between enqueue and grant: bb-result of the previous run. The enqueue replaces `.bb/build.log` and `.bb/start`, so
    /// the previous run's log is gone; its reports are still on disk and would be counted as current if the ticket file were left.
    @Test
    void enqueue_clearsTheTicketFile_soThePreviousRunIsUnavailableWhileTheNextOneWaits() throws Exception {
        var old = build("-B", "verify");

        report();
        blocked(Map.of());
        var out = result(old.ticket());

        assertThat(co.resolve(".bb/ticket")).doesNotExist();
        assertThat(out.stdout()).as(out.stdout() + out.stderr()).contains("tests: unavailable").doesNotContain("tests: 4");
    }

    @Test
    void aQueueZeroHandoff_removesTheTicketFile_soTheOlderQueuedRunIsNotCurrent() throws Exception {
        var old = build("-B", "verify");

        BbFixtures.executable(dir.resolve("tools/bb-build-direct"),
                              "#!/bin/sh\ncd \"$1\" && mkdir -p .bb && echo 1700000001 > .bb/start && rm -f .bb/rc && echo 7 > .bb/rc\n");
        var e = new HashMap<>(env);

        e.put("BB_QUEUE", "0");
        var direct = BbFixtures.run(List.of(dir.resolve("tools/bb-build").toString(), co.toString(), "-B", "direct"), e, null, null);

        assertThat(direct.rc()).as(direct.stdout() + direct.stderr()).isZero();
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).as("the premise: the handoff ran").isEqualTo("7");
        assertThat(co.resolve(".bb/ticket")).doesNotExist();
        var waited = wait(old.ticket());

        assertThat(waited.rc()).as(waited.stdout() + waited.stderr()).isZero();
        assertThat(waited.stderr()).doesNotContain("RC-MISMATCH");
        report();
        assertThat(result(old.ticket()).stdout()).contains("tests: unavailable");
    }

    @Test
    void bbResult_withTheLibMissing_isUnknown_namingTheLib_notALaterBuild() throws Exception {
        var old = build("-B", "verify");

        report();
        Files.delete(dir.resolve("tools/bb-queue-lib.sh"));
        var out = result(old.ticket());

        assertThat(out.stdout()).as(out.stdout() + out.stderr()).contains("tests: unavailable").contains("bb-queue-lib.sh")
                  .doesNotContain("a later build").doesNotContain("tests: 4");
    }

    // ---- THE GREP ROW (CTO ruling, #80, positive form after vdd81): one copy of "is this the checkout's current run?", in bb-queue-lib.sh
    // (run_current / run_ticket). Outside the lib, every code line (comments are not code) that mentions a run-identity file, `/start` or `/ticket`
    // as a path part or a quoted `'start'` / `'ticket'`, may do so ONLY in a form of the allowlist below: the writes bb-build makes, an rm, an
    // existence test, and a few named uses that are not identity (display of a wall time, bb-mutate's own timing bound, XML event names, a JSON key,
    // message text). Anything left after the allowed forms are removed is a finding, reported as file:line.
    private static final Pattern MENTION = Pattern.compile("/(start|ticket)\\b|['\"](start|ticket)['\"]");
    private static final List<Pattern> ANYWHERE = List.of(
            Pattern.compile("echo \"\\$[A-Za-z]+\" > \"\\$B/(start|ticket)\""),
            Pattern.compile("rm -f( \"\\$B/[a-z.]+\")+"),
            Pattern.compile("\\[ (! )?-f \"\\$B/start\" \\]"));
    private static final Map<String, List<Pattern>> PER_FILE = Map.of(
            "bb-build", List.of(Pattern.compile("\\(\\.bb/start without \\.bb/rc\\)")),
            "bb-temper", List.of(Pattern.compile("cat \\.bb/start 2>/dev/null"),
                                 Pattern.compile("/ticket \\\\1/p")),
            "bb-mutate", List.of(Pattern.compile("\\(\\.bb/start"),
                                 Pattern.compile("\\.bb/end - \\.bb/start"),
                                 Pattern.compile("os\\.path\\.join\\(B, \"start\"\\)")),
            "bb-result", List.of(Pattern.compile("events=\\('start', 'end'\\)"),
                                 Pattern.compile("ev == 'start'"),
                                 Pattern.compile("'ticket': tid"),
                                 Pattern.compile("the checkout's \\.bb/ticket is gone")));

    static List<String> runIdentityMentions(Path dir) throws Exception {
        var found = new ArrayList<String>();
        try (var files = Files.list(dir)) {
            for (var f : files.filter(Files::isRegularFile).sorted().toList()) {
                var name = f.getFileName().toString();

                if (name.equals("bb-queue-lib.sh")) {
                    continue;
                }
                var allowed = new ArrayList<>(ANYWHERE);

                allowed.addAll(PER_FILE.getOrDefault(name, List.of()));
                var n = 0;
                for (var line : Files.readAllLines(f)) {
                    n++;
                    var code = line.replaceFirst("(^|\\s)#.*$", "");

                    for (var p : allowed) {
                        code = p.matcher(code).replaceAll(" ");
                    }
                    if (MENTION.matcher(code).find()) {
                        found.add(name + ":" + n + ": " + line.strip());
                    }
                }
            }
        }
        return found;
    }

    @Test
    void noScriptOutsideTheLib_mentionsTheRunIdentityFilesExceptInAnAllowedForm() throws Exception {
        assertThat(runIdentityMentions(BbFixtures.OPS_BB)).isEmpty();
    }

    private Path scriptsCopy(Path copy) throws Exception {
        try (var files = Files.list(BbFixtures.OPS_BB)) {
            for (var f : files.filter(Files::isRegularFile).toList()) {
                Files.copy(f, copy.resolve(f.getFileName()));
            }
        }
        return copy;
    }

    private static final List<String[]> MUTANTS = List.of(
            new String[] {"bb-wait", "[ \"$(cat \"$co/.bb/start\")\" = \"$(field \"$T\" started)\" ]"},
            new String[] {"bb-wait", "[ \"$(cat \"$co/.bb/ticket\" 2>/dev/null)\" = \"$ID\" ]"},
            new String[] {"bb-wait", "S=$(cat \"$co/.bb/start\")"},
            new String[] {"bb-wait", "S=$(cat \"$co/.bb/start\" 2>/dev/null); [ \"$S\" = \"$sv\" ]"},
            new String[] {"bb-wait", "read -r S < \"$co/.bb/ticket\""},
            new String[] {"bb-wait", "head -1 \"$co/.bb/ticket\""},
            new String[] {"bb-wait", "sed -n 1p \"$co/.bb/ticket\""},
            new String[] {"bb-wait", "grep -qx \"$ID\" \"$co/.bb/ticket\""},
            new String[] {"bb-build", "TK=$(head -1 \"$B/ticket\")"},
            new String[] {"bb-queue", "[ \"$(cat \"$B/start\")\" = x ]"},
            new String[] {"bb-result", "    with open(os.path.join(co, '.bb', 'start')) as f: s = f.read()"},
            new String[] {"bb-result", "    with open(os.path.join(co, '.bb', 'ticket')) as f: t = f.read()"},
            new String[] {"bb-result", "    s = open(co + '/.bb/start').read()"});

    @Test
    void theGrepRow_flagsEachKnownFormOfAnInlineCompare_inTheCallerItWasAddedTo(@TempDir Path root) throws Exception {
        var copy = scriptsCopy(Files.createDirectory(root.resolve("c")));

        assertThat(runIdentityMentions(copy)).as("the copy starts clean").isEmpty();
        for (var mutant : MUTANTS) {
            var file = copy.resolve(mutant[0]);
            var original = Files.readString(file);

            Files.writeString(file, original + "\n" + mutant[1] + "\n");
            assertThat(runIdentityMentions(copy)).as(mutant[1]).anyMatch(l -> l.startsWith(mutant[0] + ":"));
            Files.writeString(file, original);
        }
    }

    /// The copies main had before #80 (the `*.pre80.prev` fixtures) must each be flagged: the row catches every original inline compare.
    @Test
    void theGrepRow_flagsEveryPre80Copy(@TempDir Path root) throws Exception {
        var copy = scriptsCopy(Files.createDirectory(root.resolve("c")));

        for (var name : List.of("bb-build", "bb-queue", "bb-wait", "bb-result")) {
            var file = copy.resolve(name);
            var current = Files.readString(file);

            try (var in = BbSameSecondIT.class.getResourceAsStream("/bb/" + name + ".pre80.prev")) {
                assertThat(in).as("test resource bb/" + name + ".pre80.prev").isNotNull();
                Files.write(file, in.readAllBytes());
            }
            assertThat(runIdentityMentions(copy)).as(name + ".pre80.prev").anyMatch(l -> l.startsWith(name + ":"));
            Files.writeString(file, current);
        }
    }
}
