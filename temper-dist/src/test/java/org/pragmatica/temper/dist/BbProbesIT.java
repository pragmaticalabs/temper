package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// The per-host probe repo (`ops/bb/probes-init.sh`, `bb-probe-reap`, the probes-aware `bb-checkout`, `install-probes.sh`) run for
/// real. The "host" is a temp HOME with `~/work/pragmatica.git` (a `--mirror` clone of a local bare repo that stands in for GitHub)
/// and `~/tools`; a lane's tree pushes to `~/work/probes.git` by local path, which runs the same pre-receive hook as ssh.
///
/// Probes (each: break it, the named test goes red, revert): loosen the hook regex ->
/// [#theHook_rejectsEveryRefOutsideProbesOwnerName]; age by commit date instead of reflog creation ->
/// [#theReaper_agesByReflogCreation_notByCommitDate]; age by the first reflog entry -> [#theReaper_ageIsTheLastUpdate_aRePushRenewsIt]; prune=now ->
/// [#theReaper_deletesARefWhoseReflogIsOlderThan7Days_andItsObjects]; resolve probes/ refs mirror-first -> [#aProbesRef_isNotShadowedByAMirrorBranchOfTheSameName]; let the reaper walk every ref instead of refs/probes ->
/// [#theReaper_touchesOnlyRefsProbes]; drop the reaper's path guard -> [#theReaper_refusesAnyPathButProbesGit]; make the checkout
/// resolve only in the mirror -> [#aShaOnlyInProbes_isCheckedOut_bySha_andByRefName]; fetch probes into refs/heads instead of
/// refs/remotes -> [#bbMutatePreflight_holds_forAProbesCommit]; keep a half-made dir -> [#anUnknownRef_fails_andLeavesNoCheckoutDir];
/// drop the install pin check -> [#anInstalledBbCheckoutOffThePin_isRefused_andNothingIsWritten].
class BbProbesIT {
    private static final Path OPS_BB = BbFixtures.OPS_BB;
    private static final Path INIT = OPS_BB.resolve("probes-init.sh");
    private static final Path INSTALL = OPS_BB.resolve("install-probes.sh");
    private static final String OLD_DATE = "2001-01-01T00:00:00Z";
    private static final long DAY = 86_400L;

    @TempDir
    Path dir;

    private Path home;
    private Path github;
    private Path lane;
    private Path probes;
    private String base;
    private int counter;

    @BeforeEach
    void fixture() throws Exception {
        home = dir.resolve("home");
        github = dir.resolve("github.git");
        lane = dir.resolve("lane");
        probes = home.resolve("work/probes.git");
        Files.createDirectories(home.resolve("tools"));
        Files.createDirectories(home.resolve("work"));
        ok(git(dir, "init", "-q", "--bare", "-b", "main", github.toString()));
        ok(git(dir, "init", "-q", "-b", "main", lane.toString()));
        ok(git(lane, "remote", "add", "origin", github.toString()));
        Files.createDirectories(lane.resolve(".mvn"));
        Files.writeString(lane.resolve(".mvn/maven.config"), "-T 1\n");
        base = commit("base", OLD_DATE);
        ok(git(lane, "push", "-q", "origin", "main"));
        ok(git(dir, "clone", "-q", "--mirror", github.toString(), home.resolve("work/pragmatica.git").toString()));
        for (var tool : List.of("bb-checkout", "bb-probe-reap")) {
            Files.copy(OPS_BB.resolve(tool), home.resolve("tools").resolve(tool));
            BbFixtures.executable(home.resolve("tools").resolve(tool), Files.readString(home.resolve("tools").resolve(tool)));
        }
    }

    // ---- helpers

    private Map<String, String> env(Map<String, String> extra) {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_AUTHOR_NAME", "t");
        env.put("GIT_AUTHOR_EMAIL", "t@example.invalid");
        env.put("GIT_COMMITTER_NAME", "t");
        env.put("GIT_COMMITTER_EMAIL", "t@example.invalid");
        env.putAll(extra);

        return env;
    }

    private BbFixtures.Outcome git(Path cwd, String... args) throws Exception {
        var command = new ArrayList<String>();

        command.add("git");
        command.addAll(List.of(args));

        return BbFixtures.run(command, env(Map.of()), cwd, null);
    }

    private static BbFixtures.Outcome ok(BbFixtures.Outcome run) {
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();

        return run;
    }

    /// a commit on top of the current HEAD of the lane tree, committer and author dated `date`
    private String commit(String message, String date) throws Exception {
        Files.writeString(lane.resolve("f"), message + " " + (counter++) + "\n");
        ok(git(lane, "add", "-A"));
        var command = List.of("git", "commit", "-q", "-m", message);
        var env = env(Map.of("GIT_AUTHOR_DATE", date, "GIT_COMMITTER_DATE", date));

        ok(BbFixtures.run(command, env, lane, null));

        return ok(git(lane, "rev-parse", "HEAD")).stdout().strip();
    }

    /// a new commit on top of the base, dated `date`; the lane tree is left on it
    private String probeCommit(String date) throws Exception {
        ok(git(lane, "checkout", "-q", "--detach", base));

        return commit("probe", date);
    }

    private BbFixtures.Outcome init() throws Exception {
        return BbFixtures.run(List.of("bash", INIT.toString()), env(Map.of()), null, null);
    }

    private BbFixtures.Outcome push(String sha, String ref) throws Exception {
        return git(lane, "push", "-q", probes.toString(), sha + ":" + ref);
    }

    private BbFixtures.Outcome checkout(String name, String ref) throws Exception {
        return BbFixtures.run(List.of("bash", home.resolve("tools/bb-checkout").toString(), name, ref),
                              env(Map.of("BB_OWNER", "s1")),
                              null,
                              null);
    }

    private BbFixtures.Outcome reap(String... args) throws Exception {
        var command = new ArrayList<String>(List.of("bash", home.resolve("tools/bb-probe-reap").toString()));

        command.addAll(List.of(args));

        return BbFixtures.run(command, env(Map.of()), null, null);
    }

    private Path co(String name) {
        return home.resolve("work/co").resolve(name);
    }

    private String refSha(Path repo, String ref) throws Exception {
        var run = git(repo, "rev-parse", "--verify", "-q", ref);

        return run.rc() == 0 ? run.stdout().strip() : null;
    }

    /// rewrites the creation time (first reflog line) of a probes ref
    private void ageReflog(String ref, long ageSeconds) throws IOException {
        var log = probes.resolve("logs").resolve(ref);
        var lines = new ArrayList<>(Files.readAllLines(log));
        var stamp = Instant.now().getEpochSecond() - ageSeconds;

        lines.set(0, lines.get(0).replaceFirst("> \\d+ ", "> " + stamp + " "));
        Files.write(log, lines);
    }

    private String pushNew(String ref, String date) throws Exception {
        var sha = probeCommit(date);

        ok(push(sha, ref));

        return sha;
    }

    // ---- probes-init.sh

    @Test
    void init_createsABareNonMirrorRepo_withReflogsAndTheHook() throws Exception {
        var run = ok(init());

        assertThat(run.stdout()).contains("probes-init: created ");
        assertThat(ok(git(probes, "rev-parse", "--is-bare-repository")).stdout().strip()).isEqualTo("true");
        assertThat(ok(git(probes, "config", "--get", "core.logAllRefUpdates")).stdout().strip()).isEqualTo("always");
        assertThat(git(probes, "config", "--get-regexp", "^remote\\.").stdout()).isEmpty();
        assertThat(Files.isExecutable(probes.resolve("hooks/pre-receive"))).isTrue();
    }

    @Test
    void init_isIdempotent_changesNothing_andKeepsRefsAndReflogs() throws Exception {
        ok(init());
        var sha = pushNew("refs/probes/s1/x", OLD_DATE);
        var before = snapshot();
        var again = ok(init());

        assertThat(again.stdout()).contains("probes-init: unchanged ");
        assertThat(snapshot()).isEqualTo(before);
        assertThat(refSha(probes, "refs/probes/s1/x")).isEqualTo(sha);
        assertThat(Files.exists(probes.resolve("logs/refs/probes/s1/x"))).isTrue();
    }

    @Test
    void init_refusesARepoWithARemote_andDoesNotTouchIt() throws Exception {
        ok(git(dir, "init", "-q", "--bare", probes.toString()));
        ok(git(probes, "remote", "add", "origin", github.toString()));
        var run = init();

        assertThat(run.rc()).isNotZero();
        assertThat(Files.exists(probes.resolve("hooks/pre-receive"))).isFalse();
    }

    private List<String> snapshot() throws IOException {
        try (Stream<Path> walk = Files.walk(probes)) {
            return walk.filter(Files::isRegularFile)
                       .filter(path -> !path.getFileName().toString().endsWith(".sample"))
                       .sorted()
                       .map(path -> {
                                try {
                                return probes.relativize(path) + " " + BbFixtures.md5(path) + " " + Files.getLastModifiedTime(path);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                            })
                       .collect(Collectors.toList());
        }
    }

    // ---- the pre-receive hook

    @Test
    void theHook_acceptsAGoodRef_andAllowsItsDelete() throws Exception {
        ok(init());
        var sha = probeCommit(OLD_DATE);

        ok(push(sha, "refs/probes/s1/mutation-1.a_b"));
        assertThat(refSha(probes, "refs/probes/s1/mutation-1.a_b")).isEqualTo(sha);
        ok(push("", "refs/probes/s1/mutation-1.a_b"));
        assertThat(refSha(probes, "refs/probes/s1/mutation-1.a_b")).isNull();
    }

    @Test
    void theHook_rejectsEveryRefOutsideProbesOwnerName() throws Exception {
        ok(init());
        var sha = probeCommit(OLD_DATE);

        for (var ref : List.of("refs/probes/x", "refs/probes/bad-owner/x", "refs/probes/s.1/x", "refs/probes/s1/a/b", "refs/heads/x",
                               "refs/tags/t", "refs/other/s1/x", "refs/probesx/s1/x", "refs/probes/s1/naéme")) {
            var run = push(sha, ref);

            assertThat(run.rc()).as(ref + " " + run.stderr()).isNotZero();
            assertThat(refSha(probes, ref)).as(ref).isNull();
        }
        assertThat(git(probes, "for-each-ref").stdout()).as("nothing was created by any rejected push").isEmpty();
        assertThat(push(sha, "refs/heads/x").stderr()).contains("probes.git: rejected refs/heads/x");
    }

    @Test
    void theHook_rejectsDeletingARefOutsideTheNamespace() throws Exception {
        ok(init());
        var sha = probeCommit(OLD_DATE);

        ok(push(sha, "refs/probes/s1/x"));
        ok(git(probes, "update-ref", "refs/heads/keep", sha));
        var run = push("", "refs/heads/keep");

        assertThat(run.rc()).isNotZero();
        assertThat(refSha(probes, "refs/heads/keep")).isEqualTo(sha);
    }

    // ---- bb-checkout

    @Test
    void aShaOnlyInProbes_isCheckedOut_bySha_andByRefName() throws Exception {
        ok(init());
        var sha = pushNew("refs/probes/s1/x", "2026-11-11T00:00:00Z");

        assertThat(git(home.resolve("work/pragmatica.git"), "cat-file", "-e", sha + "^{commit}").rc()).as("not in the mirror").isNotZero();
        var bySha = ok(checkout("s1-a", sha));
        var byRef = ok(checkout("s1-b", "probes/s1/x"));

        assertThat(bySha.stdout()).contains("sha=" + sha);
        assertThat(byRef.stdout()).contains("sha=" + sha);
        assertThat(ok(git(co("s1-a"), "rev-parse", "HEAD")).stdout().strip()).isEqualTo(sha);
        assertThat(ok(git(co("s1-b"), "rev-parse", "HEAD")).stdout().strip()).isEqualTo(sha);
        assertThat(Files.readString(co("s1-a").resolve("f"))).startsWith("probe");
    }

    @Test
    void aProbesRef_isNotShadowedByAMirrorBranchOfTheSameName() throws Exception {
        ok(init());
        var y = pushNew("refs/probes/a/b", "2026-11-11T00:00:00Z");

        ok(git(lane, "checkout", "-q", "--detach", base));
        var x = commit("public", "2026-11-11T00:00:00Z");

        ok(git(lane, "push", "-q", "origin", x + ":refs/heads/probes/a/b"));
        assertThat(x).isNotEqualTo(y);
        var run = ok(checkout("s1-a", "probes/a/b"));

        assertThat(run.stdout()).contains("sha=" + y);
        assertThat(ok(git(co("s1-a"), "rev-parse", "HEAD")).stdout().strip()).isEqualTo(y);
    }

    @Test
    void theProbeRef_survivesFurtherCheckouts_whichPruneTheMirror() throws Exception {
        ok(init());
        var sha = pushNew("refs/probes/s1/x", "2026-11-11T00:00:00Z");

        ok(git(lane, "checkout", "-q", "main"));
        var next = commit("later", "2026-11-11T00:00:00Z");

        ok(git(lane, "push", "-q", "origin", "main"));
        ok(checkout("s1-a", next));
        assertThat(refSha(probes, "refs/probes/s1/x")).isEqualTo(sha);
        ok(checkout("s1-b", sha));
        assertThat(ok(git(co("s1-b"), "rev-parse", "HEAD")).stdout().strip()).isEqualTo(sha);
    }

    /// the control: the very failure probes.git exists for, shown on the mirror itself (a ref pushed there is pruned by the next checkout)
    @Test
    void aRefPushedToTheMirror_isPrunedByTheNextCheckout() throws Exception {
        var mirror = home.resolve("work/pragmatica.git");
        var sha = probeCommit("2026-11-11T00:00:00Z");

        ok(git(lane, "push", "-q", mirror.toString(), sha + ":refs/probes/s1/x"));
        assertThat(refSha(mirror, "refs/probes/s1/x")).isEqualTo(sha);
        ok(checkout("s1-a", "main"));
        assertThat(refSha(mirror, "refs/probes/s1/x")).isNull();
    }

    @Test
    void bbMutatePreflight_holds_forAProbesCommit() throws Exception {
        ok(init());
        var sha = pushNew("refs/probes/s1/x", "2026-11-11T00:00:00Z");

        ok(checkout("s1-a", sha));
        var contains = ok(git(co("s1-a"), "for-each-ref", "--contains", sha, "refs/remotes/")).stdout();

        assertThat(contains).contains("refs/remotes/probes/s1/x");
    }

    @Test
    void anUnknownRef_fails_andLeavesNoCheckoutDir() throws Exception {
        ok(init());
        pushNew("refs/probes/s1/x", OLD_DATE);
        var unknown = "0123456789abcdef0123456789abcdef01234567";

        for (var ref : List.of(unknown, "probes/s1/nope", "no-such-branch")) {
            var run = checkout("s1-a", ref);

            assertThat(run.rc()).as(ref).isEqualTo(5);
            assertThat(run.stderr()).contains("refusing: " + ref);
            assertThat(Files.exists(co("s1-a"))).as(ref).isFalse();
        }
        Files.createDirectories(co("s1-keep"));
        Files.writeString(co("s1-keep/mine"), "x");
        assertThat(checkout("s1-keep", unknown).rc()).as("an existing dir is refused and left alone").isEqualTo(3);
        assertThat(Files.readString(co("s1-keep/mine"))).isEqualTo("x");
    }

    @Test
    void anUnknownRef_withoutAProbesRepo_failsBeforeAnyClone() throws Exception {
        var run = checkout("s1-a", "no-such-branch");

        assertThat(run.rc()).isEqualTo(5);
        assertThat(Files.exists(co("s1-a"))).isFalse();
    }

    @Test
    void aMirrorRef_stillWorks_withAndWithoutAProbesRepo() throws Exception {
        var without = ok(checkout("s1-a", "main"));

        assertThat(without.stdout()).contains("sha=" + base);
        assertThat(git(co("s1-a"), "remote").stdout().strip()).isEqualTo("origin");
        ok(init());
        var with = ok(checkout("s1-b", base));

        assertThat(with.stdout()).contains("sha=" + base);
        assertThat(git(co("s1-b"), "remote").stdout().lines().toList()).containsExactlyInAnyOrder("origin", "probes");
        assertThat(Files.readString(co("s1-b/.mvn/maven.config"))).contains("-Dmaven.repo.local=" + co("s1-b") + "/.m2-local");
    }

    @Test
    void theOwnerGuard_isUnchanged() throws Exception {
        var run = BbFixtures.run(List.of("bash", home.resolve("tools/bb-checkout").toString(), "other-a", "main"),
                                 env(Map.of("BB_OWNER", "s1")),
                                 null,
                                 null);

        assertThat(run.rc()).isEqualTo(4);
    }

    // ---- bb-probe-reap

    @Test
    void theReaper_deletesARefWhoseReflogIsOlderThan7Days_andItsObjects() throws Exception {
        ok(init());
        var sha = pushNew("refs/probes/s1/old", "2026-11-11T00:00:00Z");

        ageReflog("refs/probes/s1/old", 8 * DAY);
        var run = ok(reap());

        assertThat(run.stdout()).contains("deleted refs/probes/s1/old " + sha);
        assertThat(refSha(probes, "refs/probes/s1/old")).isNull();
        assertThat(git(probes, "cat-file", "-e", sha + "^{commit}").rc()).as("gc --prune=1.hour.ago keeps a fresh object").isZero();
        ageObjects(2 * 3600L);
        ok(reap());
        assertThat(git(probes, "cat-file", "-e", sha + "^{commit}").rc()).as("an object older than the margin is pruned").isNotZero();
        ok(git(probes, "fsck", "--no-dangling"));
    }

    /// back-dates every object file of probes.git, so that gc sees it as older than its prune margin
    private void ageObjects(long ageSeconds) throws IOException {
        var when = java.nio.file.attribute.FileTime.from(Instant.now().minusSeconds(ageSeconds));

        try (var files = Files.walk(probes.resolve("objects"))) {
            for (var path : files.filter(Files::isRegularFile).toList()) {
                Files.setLastModifiedTime(path, when);
            }
        }
    }

    @Test
    void theReaper_agesByReflogCreation_notByCommitDate() throws Exception {
        ok(init());
        var oldBase = pushNew("refs/probes/s1/mutation", OLD_DATE);
        var freshRef = pushNew("refs/probes/s1/fresh", "2026-11-11T00:00:00Z");
        var sixDays = pushNew("refs/probes/s1/sixdays", "2026-11-11T00:00:00Z");

        ageReflog("refs/probes/s1/sixdays", 6 * DAY);
        var run = ok(reap());

        assertThat(run.stdout()).doesNotContain("deleted refs/");
        assertThat(refSha(probes, "refs/probes/s1/mutation")).as("a 2001 commit on a fresh ref survives").isEqualTo(oldBase);
        assertThat(refSha(probes, "refs/probes/s1/fresh")).isEqualTo(freshRef);
        assertThat(refSha(probes, "refs/probes/s1/sixdays")).isEqualTo(sixDays);
        assertThat(git(probes, "cat-file", "-e", oldBase + "^{commit}").rc()).isZero();
    }

    @Test
    void theReaper_ageIsTheLastUpdate_aRePushRenewsIt() throws Exception {
        ok(init());
        pushNew("refs/probes/s1/x", OLD_DATE);
        ageReflog("refs/probes/s1/x", 8 * DAY);
        var newer = probeCommit("2026-11-11T00:00:00Z");

        ok(git(lane, "push", "-q", "-f", probes.toString(), newer + ":refs/probes/s1/x"));
        var log = probes.resolve("logs/refs/probes/s1/x");

        assertThat(Files.readAllLines(log)).as("a first (old) entry and a later (fresh) one").hasSize(2);
        var run = ok(reap());

        assertThat(run.stdout()).doesNotContain("deleted refs/");
        assertThat(refSha(probes, "refs/probes/s1/x")).isEqualTo(newer);
    }

    @Test
    void theReaper_anOldLastUpdate_isReaped_evenWithSeveralEntries() throws Exception {
        ok(init());
        pushNew("refs/probes/s1/x", OLD_DATE);
        var newer = probeCommit("2026-11-11T00:00:00Z");

        ok(git(lane, "push", "-q", "-f", probes.toString(), newer + ":refs/probes/s1/x"));
        var log = probes.resolve("logs/refs/probes/s1/x");
        var stamp = Instant.now().getEpochSecond() - 8 * DAY;
        var lines = Files.readAllLines(log).stream().map(l -> l.replaceFirst("> \\d+ ", "> " + stamp + " ")).toList();

        Files.write(log, lines);
        var run = ok(reap());

        assertThat(run.stdout()).contains("deleted refs/probes/s1/x " + newer);
    }

    @Test
    void aRefWithoutAReflog_isKept_withAWarning() throws Exception {
        ok(init());
        var sha = pushNew("refs/probes/s1/x", OLD_DATE);

        Files.delete(probes.resolve("logs/refs/probes/s1/x"));
        var run = ok(reap());

        assertThat(run.stderr()).contains("kept refs/probes/s1/x: no reflog");
        assertThat(refSha(probes, "refs/probes/s1/x")).isEqualTo(sha);
    }

    @Test
    void theReaper_touchesOnlyRefsProbes() throws Exception {
        ok(init());
        var sha = probeCommit("2026-11-11T00:00:00Z");

        ok(push(sha, "refs/probes/s1/x"));
        ok(git(probes, "update-ref", "refs/heads/keep", sha));
        ageReflog("refs/probes/s1/x", 8 * DAY);
        var other = probes.resolve("logs/refs/heads/keep");

        ok(git(probes, "reflog", "show", "refs/heads/keep"));
        Files.write(other, Files.readString(other).replaceFirst("> \\d+ ", "> 1000000000 ").getBytes());
        ok(reap());

        assertThat(refSha(probes, "refs/probes/s1/x")).isNull();
        assertThat(refSha(probes, "refs/heads/keep")).as("a branch with a 25-year-old reflog is not a probe").isEqualTo(sha);
    }

    @Test
    void theReaper_refusesAnyPathButProbesGit() throws Exception {
        ok(init());
        var sha = probeCommit("2026-11-11T00:00:00Z");
        var mirror = home.resolve("work/pragmatica.git");
        var copy = home.resolve("work/probes-copy.git");

        ok(push(sha, "refs/probes/s1/x"));
        ok(git(dir, "clone", "-q", "--mirror", probes.toString(), copy.toString()));
        ok(git(lane, "push", "-q", mirror.toString(), sha + ":refs/probes/s1/x"));
        ageReflog("refs/probes/s1/x", 8 * DAY);
        var mirrorRefs = git(mirror, "for-each-ref").stdout();

        for (var path : List.of(mirror, copy, dir, Path.of("/"), github)) {
            var run = reap(path.toString());

            assertThat(run.rc()).as(path.toString()).isEqualTo(4);
            assertThat(run.stderr()).contains("refusing");
        }
        assertThat(git(mirror, "for-each-ref").stdout()).isEqualTo(mirrorRefs);
        assertThat(refSha(copy, "refs/probes/s1/x")).isEqualTo(sha);
        assertThat(refSha(probes, "refs/probes/s1/x")).as("the one real path is not reaped by the refusals").isEqualTo(sha);
    }

    @Test
    void theReaper_refusesASymlinkedProbesGit() throws Exception {
        ok(init());
        var target = home.resolve("work/elsewhere.git");

        Files.move(probes, target);
        Files.createSymbolicLink(probes, target);

        assertThat(reap().rc()).isEqualTo(4);
    }

    // ---- the reaper call from bb-checkout

    @Test
    void checkout_reapsAtMostOncePer24h_andAReaperFailureNeverFailsIt() throws Exception {
        ok(init());
        var stamp = home.resolve("work/.probes-reap.stamp");

        pushNew("refs/probes/s1/a", OLD_DATE);
        ageReflog("refs/probes/s1/a", 8 * DAY);
        ok(checkout("s1-1", "main"));
        assertThat(refSha(probes, "refs/probes/s1/a")).as("first checkout on this host reaps").isNull();
        assertThat(Files.exists(stamp)).isTrue();

        pushNew("refs/probes/s1/b", OLD_DATE);
        ageReflog("refs/probes/s1/b", 8 * DAY);
        ok(checkout("s1-2", "main"));
        assertThat(refSha(probes, "refs/probes/s1/b")).as("a second checkout within 24 h does not").isNotNull();

        Files.setLastModifiedTime(stamp, FileTime.from(Instant.now().minusSeconds(25 * 3600)));
        ok(checkout("s1-3", "main"));
        assertThat(refSha(probes, "refs/probes/s1/b")).as("after 24 h it does").isNull();

        BbFixtures.executable(home.resolve("tools/bb-probe-reap"), "#!/bin/bash\necho boom >&2\nexit 1\n");
        Files.setLastModifiedTime(stamp, FileTime.from(Instant.now().minusSeconds(25 * 3600)));
        var failing = ok(checkout("s1-4", "main"));

        assertThat(failing.stderr()).contains("bb-probe-reap failed (ignored)");
        assertThat(Files.exists(co("s1-4"))).isTrue();
    }

    // ---- install-probes.sh

    private Path shim;

    private void installShim() throws IOException {
        assumeTrue(BbFixtures.linux(), "the remote side needs md5sum and base64 -d");
        shim = BbFixtures.executable(dir.resolve("shim"),
                                     """
            #!/bin/bash
            target=$1; shift
            echo "$target $*" >> "%s"
            HOME="$HOME_BASE/${target#*@}" exec bash -c "$*"
            """.formatted(dir.resolve("ssh.log")));
    }

    private Path host(String name, byte[] bbCheckout) throws IOException {
        var hostHome = Files.createDirectories(dir.resolve("homes").resolve(name));
        var tools = Files.createDirectories(hostHome.resolve("tools"));

        Files.write(tools.resolve("bb-checkout"), bbCheckout);

        return hostHome;
    }

    private BbFixtures.Outcome install(Path script, String... targets) throws Exception {
        var env = new HashMap<String, String>();
        var command = new ArrayList<>(List.of("bash", script.toString()));

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.resolve("mac-home").toString());
        env.put("BB_INSTALL_SSH", shim.toString());
        env.put("HOME_BASE", dir.resolve("homes").toString());
        command.addAll(List.of(targets));

        return BbFixtures.run(command, env, null, null);
    }

    private static byte[] originalBbCheckout() throws IOException {
        try (var in = BbProbesIT.class.getResourceAsStream("/bb/bb-checkout.original")) {
            assertThat(in).as("test resource bb/bb-checkout.original").isNotNull();
            var bytes = in.readAllBytes();

            assertThat(BbFixtures.md5(bytes)).as("the vendored fixture holds the live file verbatim").isEqualTo(pin("BB_CHECKOUT_OLD"));

            return bytes;
        }
    }

    private static String pin(String name) throws IOException {
        var matcher = Pattern.compile("readonly " + name + "=([0-9a-f]{32})").matcher(Files.readString(INSTALL));

        assertThat(matcher.find()).as(name + " is pinned to an md5 in install-probes.sh").isTrue();

        return matcher.group(1);
    }

    @Test
    void pins_areTheReviewedFiles_andTheOldOneIsTheLiveBbCheckout() throws Exception {
        assertThat(pin("BB_CHECKOUT_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-checkout")));
        assertThat(pin("BB_REAP_NEW")).isEqualTo(BbFixtures.md5(OPS_BB.resolve("bb-probe-reap")));
        assertThat(pin("PROBES_INIT_NEW")).isEqualTo(BbFixtures.md5(INIT));
        assertThat(pin("BB_CHECKOUT_OLD")).isEqualTo("7098ea8dee142600771968559e15e4db").isEqualTo(BbFixtures.md5(originalBbCheckout()));
    }

    @Test
    void install_putsTheToolsAndTheRepoOnTheHost_keepsABackup_andASecondRunChangesNothing() throws Exception {
        installShim();
        var hostHome = host("h1", originalBbCheckout());
        var first = install(INSTALL, "builder@h1");

        assertThat(first.rc()).as(first.stdout() + first.stderr()).isZero();
        assertThat(first.stdout().lines().toList()).contains("installed builder@h1 bb-probe-reap before=absent after=" + pin("BB_REAP_NEW"),
                                                             "installed builder@h1 bb-checkout before=" + pin("BB_CHECKOUT_OLD")
                                                            + " after=" + pin("BB_CHECKOUT_NEW"));
        assertThat(hostHome.resolve("tools/bb-checkout")).hasBinaryContent(Files.readAllBytes(OPS_BB.resolve("bb-checkout")));
        assertThat(hostHome.resolve("tools/bb-checkout.bak-" + pin("BB_CHECKOUT_OLD"))).hasBinaryContent(originalBbCheckout());
        assertThat(Files.isExecutable(hostHome.resolve("tools/bb-probe-reap"))).isTrue();
        assertThat(Files.isDirectory(hostHome.resolve("work/probes.git"))).isTrue();
        var before = listing(hostHome.resolve("tools"));
        var second = install(INSTALL, "builder@h1");

        assertThat(second.rc()).as(second.stdout() + second.stderr()).isZero();
        assertThat(second.stdout()).contains("unchanged builder@h1 bb-checkout ", "unchanged builder@h1 bb-probe-reap ", "probes-init: unchanged ");
        assertThat(listing(hostHome.resolve("tools"))).isEqualTo(before);
    }

    @Test
    void anInstalledBbCheckoutOffThePin_isRefused_andNothingIsWritten() throws Exception {
        installShim();
        var good = host("h1", originalBbCheckout());
        var odd = host("h2", "#!/bin/bash\n# a diverged copy\n".getBytes());
        var run = install(INSTALL, "builder@h1", "builder@h2");

        assertThat(run.rc()).isEqualTo(65);
        assertThat(run.stdout()).contains("refused builder@h2 bb-checkout installed=" + BbFixtures.md5("#!/bin/bash\n# a diverged copy\n".getBytes()));
        assertThat(listing(good.resolve("tools"))).as("h1 was fine but nothing is written on any target").hasSize(1);
        assertThat(Files.exists(good.resolve("work"))).isFalse();
        assertThat(Files.readString(odd.resolve("tools/bb-checkout"))).isEqualTo("#!/bin/bash\n# a diverged copy\n");
        assertThat(Files.exists(odd.resolve("tools/bb-probe-reap"))).isFalse();
    }

    @Test
    void aLocalFileNotAtThePin_isRefused_withoutAnySshCall() throws Exception {
        installShim();
        var scripts = Files.createDirectories(dir.resolve("tampered"));

        for (var f : List.of("bb-checkout", "bb-probe-reap", "probes-init.sh", "install-probes.sh")) {
            Files.copy(OPS_BB.resolve(f), scripts.resolve(f));
        }
        Files.writeString(scripts.resolve("bb-probe-reap"), Files.readString(scripts.resolve("bb-probe-reap")) + "# edited\n");
        host("h1", originalBbCheckout());
        var run = install(scripts.resolve("install-probes.sh"), "builder@h1");

        assertThat(run.rc()).isEqualTo(65);
        assertThat(Files.exists(dir.resolve("ssh.log"))).isFalse();
    }

    private static List<String> listing(Path tools) throws IOException {
        try (Stream<Path> walk = Files.list(tools)) {
            return walk.sorted()
                       .map(path -> {
                                try {
                                return path.getFileName() + " " + Files.size(path) + " " + BbFixtures.md5(path);
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                            })
                       .collect(Collectors.toList());
        }
    }
}
