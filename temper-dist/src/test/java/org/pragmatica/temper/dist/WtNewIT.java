package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/dev/wt-new` (owner decision D-2): one way to make a tree. The seed is copied, never hard-linked (a cp -al seed
/// let Maven write into ~/.m2 through the links), the state file and logs are excluded from git, and the tree runs the
/// hooks of its own checkout. Every refusal creates nothing: no directory, no branch; every failure after the tree is
/// made removes it again, and the branch wt-new made. Only a complete `.m2-local` (`.seed-complete`, not a symlink) is
/// a seed.
/// Probes: `cp -a` → `cp -al` → [#createsATree_seededByCopy_excluded_hooked] red (rc 4, the copy is hard-linked); the
/// post-copy check removed → [#aCopyWithHardLinks_isRolledBack] red; the seed check removed →
/// [#refusesAHardLinkedSeed] red; the disk check removed → [#refusesLowDisk] red; the exclude loop or the core.hooksPath
/// line removed → [#createsATree_seededByCopy_excluded_hooked] red; the hooksPath set made unconditional →
/// [#anExistingHooksPath_isLeftAlone_andNamed] red; `--no-track` dropped → the same test red; the
/// rollback trap removed → [#aFailingCopy_isRolledBack] red; the `.seed-complete` or symlink filter dropped →
/// [#onlyACompleteRealM2_isASeed] red; the TEMPER_M2_SEED symlink refusal removed → [#refusesASymlinkedSeed] red; the
/// held-lock skip removed → [#aTreeBuildingNow_isNoSeed] red.
class WtNewIT {
    @TempDir
    Path dir;
    private DevSandbox sandbox;

    @BeforeEach
    void sandbox() throws Exception {
        sandbox = new DevSandbox(dir);
        Files.writeString(sandbox.m2(sandbox.main.resolve(".m2-local"), "main seed").resolve(".seed-complete"), "x\n");
    }

    @Test
    void createsATree_seededByCopy_excluded_hooked() throws Exception {
        var outcome = sandbox.run("wt-new", "alpha");
        var tree = sandbox.root.resolve("alpha");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output().strip()).endsWith("\n" + tree);
        assertThat(sandbox.git(tree, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("alpha");
        assertThat(sandbox.git(tree, "rev-parse", "HEAD")).isEqualTo(sandbox.git(sandbox.main, "rev-parse", "origin/main"));
        assertThat(tree.resolve(".m2-local/org/example/a/1/a-1.pom")).hasContent("main seed");
        assertThat(DevSandbox.linked(tree.resolve(".m2-local"))).as("the copy").isEmpty();
        assertThat(DevSandbox.linked(sandbox.main.resolve(".m2-local"))).as("the seed").isEmpty();
        assertThat(tree.resolve(".m2-local/.seed-complete")).isRegularFile();
        assertThat(tree.resolve(".m2-local.partial")).doesNotExist();
        assertThat(sandbox.ran(List.of("git", "config", "branch.alpha.merge"), tree).rc()).as("no upstream (--no-track)").isNotZero();
        assertThat(tree.resolve(".wp-logs")).isDirectory();
        assertThat(Files.readAllLines(sandbox.main.resolve(".git/info/exclude"))).contains(".wp-state.md", ".wp-logs/");
        assertThat(sandbox.git(tree, "config", "core.hooksPath")).isEqualTo("ops/dev/hooks");

        Files.writeString(tree.resolve(".wp-state.md"), "state\n");
        Files.writeString(tree.resolve(".wp-logs/build.log"), "log\n");
        assertThat(sandbox.git(tree, "status", "--porcelain")).as("excluded").isEmpty();

        Files.writeString(tree.resolve("change"), "x\n");
        sandbox.git(tree, "add", "change");
        var styled = sandbox.ran(List.of("git", "commit", "-q", "-m", "style: x"), tree);
        assertThat(styled.rc()).as("the tree's own commit-msg hook runs").isEqualTo(1);
        assertThat(styled.output()).contains("commit-msg: rejected");
        sandbox.git(tree, "commit", "-q", "-m", "feat: x");
    }

    @Test
    void anExistingHooksPath_isLeftAlone_andNamed() throws Exception {
        sandbox.git(sandbox.main, "config", "core.hooksPath", "/elsewhere/hooks-remote");

        var outcome = sandbox.run("wt-new", "alpha");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("core.hooksPath is already set to /elsewhere/hooks-remote");
        assertThat(sandbox.git(sandbox.main, "config", "core.hooksPath")).isEqualTo("/elsewhere/hooks-remote");
        assertThat(sandbox.git(sandbox.root.resolve("alpha"), "config", "core.hooksPath")).isEqualTo("/elsewhere/hooks-remote");
    }

    @Test
    void noHooksPath_getsTheTreesOwn() throws Exception {
        assertThat(sandbox.ran(List.of("git", "config", "--get", "core.hooksPath"), sandbox.main).rc()).as("none preset").isNotZero();

        var outcome = sandbox.run("wt-new", "alpha");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).doesNotContain("core.hooksPath is already set");
        assertThat(sandbox.git(sandbox.root.resolve("alpha"), "config", "core.hooksPath")).isEqualTo("ops/dev/hooks");
    }

    @Test
    void secondRun_doesNotDuplicateTheExcludes() throws Exception {
        assertThat(sandbox.run("wt-new", "alpha").rc()).isZero();
        assertThat(sandbox.run("wt-new", "beta").rc()).isZero();

        assertThat(Files.readAllLines(sandbox.main.resolve(".git/info/exclude"))).filteredOn(".wp-logs/"::equals).hasSize(1);
    }

    @Test
    void seedIsTheNewestM2_amongTheWorktrees() throws Exception {
        assertThat(sandbox.run("wt-new", "alpha").rc()).isZero();
        var alphaM2 = sandbox.root.resolve("alpha/.m2-local");
        Files.writeString(alphaM2.resolve("org/example/a/1/a-1.pom"), "alpha seed");
        Files.setLastModifiedTime(sandbox.main.resolve(".m2-local"), FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        Files.setLastModifiedTime(alphaM2, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        var outcome = sandbox.run("wt-new", "beta");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("from " + alphaM2);
        assertThat(sandbox.root.resolve("beta/.m2-local/org/example/a/1/a-1.pom")).hasContent("alpha seed");
    }

    @Test
    void seedFromTheEnvironment_wins() throws Exception {
        var seed = sandbox.m2(dir.resolve("given"), "given seed");
        sandbox.env.put("TEMPER_M2_SEED", seed.toString());

        assertThat(sandbox.run("wt-new", "alpha").rc()).isZero();
        assertThat(sandbox.root.resolve("alpha/.m2-local/org/example/a/1/a-1.pom")).hasContent("given seed");
    }

    /// A sibling .m2-local without `.seed-complete` (a copy cut short, or a tree from before the marker) or that is a
    /// symlink is never picked, however new.
    @Test
    void onlyACompleteRealM2_isASeed() throws Exception {
        var half = sandbox.root.resolve("half");
        sandbox.git(sandbox.main, "worktree", "add", "-q", "--detach", half.toString(), "origin/main");
        sandbox.m2(half.resolve(".m2-local"), "half seed");
        var linked = sandbox.root.resolve("linked");
        sandbox.git(sandbox.main, "worktree", "add", "-q", "--detach", linked.toString(), "origin/main");
        var target = sandbox.m2(dir.resolve("target-m2"), "linked seed");
        Files.writeString(target.resolve(".seed-complete"), "x\n");
        Files.createSymbolicLink(linked.resolve(".m2-local"), target);
        Files.setLastModifiedTime(sandbox.main.resolve(".m2-local"), FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        Files.setLastModifiedTime(half.resolve(".m2-local"), FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        Files.setLastModifiedTime(target, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        var outcome = sandbox.run("wt-new", "alpha");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(sandbox.root.resolve("alpha/.m2-local/org/example/a/1/a-1.pom")).hasContent("main seed");
    }

    /// A tree whose build lock is held is writing into its .m2-local: not a seed, however new.
    @Test
    void aTreeBuildingNow_isNoSeed() throws Exception {
        assertThat(sandbox.run("wt-new", "alpha").rc()).isZero();
        var alphaM2 = sandbox.root.resolve("alpha/.m2-local");
        Files.writeString(alphaM2.resolve("org/example/a/1/a-1.pom"), "alpha seed");
        Files.setLastModifiedTime(sandbox.main.resolve(".m2-local"), FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        Files.setLastModifiedTime(alphaM2, FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        Files.writeString(Files.createDirectories(sandbox.root.resolve("alpha/.wp-logs/build.lock")).resolve("pid"),
                          ProcessHandle.current().pid() + "\n");
        var outcome = sandbox.run("wt-new", "beta");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(sandbox.root.resolve("beta/.m2-local/org/example/a/1/a-1.pom")).hasContent("main seed");
    }

    @Test
    void refusesASymlinkedSeed() throws Exception {
        var link = Files.createSymbolicLink(dir.resolve("link-m2"), sandbox.main.resolve(".m2-local"));
        for (var seed : List.of(link.toString(), link + "/", link + "//")) {
            sandbox.env.put("TEMPER_M2_SEED", seed);

            assertRefusedCreatingNothing(sandbox.run("wt-new", "alpha"), "is a symlink", "alpha", false);
        }
    }

    /// The copy ending up hard-linked (a `cp` that runs `cp -al`; BSD `cp -a` never links, so only a fake reaches
    /// this check): exit 4, the partial copy, the tree, its registration and its branch are gone; the seed keeps one
    /// link per file. A symlinked seed skips the seed-side check, so this one is the last guard.
    @Test
    void aCopyWithHardLinks_isRolledBack() throws Exception {
        sandbox.fake("cp", "exec /bin/cp -al \"$2\" \"$3\"");
        var outcome = sandbox.run("wt-new", "alpha");

        assertRolledBack(outcome, "had hard-linked files", "alpha");
        assertThat(sandbox.root.resolve("alpha/.m2-local.partial")).doesNotExist();
    }

    @Test
    void aFailingCopy_isRolledBack() throws Exception {
        sandbox.fake("cp", "mkdir -p \"$3/org\"; echo 'cp: No space left on device' >&2; exit 1");
        var outcome = sandbox.run("wt-new", "alpha");

        assertRolledBack(outcome, "FAILED: cp -a", "alpha");
    }

    /// An interrupt mid-copy rolls back too: the TERM is handled once cp returns.
    @Test
    void anInterruptedCopy_isRolledBack() throws Exception {
        sandbox.fake("cp", "mkdir -p \"$3/org\"; kill -TERM $PPID; sleep 1");
        var outcome = sandbox.run("wt-new", "alpha");

        assertRolledBack(outcome, "rolled back", "alpha");
    }

    /// With --detach, wt-new makes no branch, so a rollback deletes none, even the one it was based on.
    @Test
    void aRollback_neverDeletesABranchItDidNotMake() throws Exception {
        sandbox.git(sandbox.main, "branch", "existing", "origin/main");
        sandbox.fake("cp", "exit 1");
        var outcome = sandbox.run("wt-new", "alpha", "--detach", "--base", "existing");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(4);
        assertThat(sandbox.root.resolve("alpha")).doesNotExist();
        assertThat(sandbox.git(sandbox.main, "show-ref", "--verify", "refs/heads/existing")).isNotEmpty();
    }

    @Test
    void branchAndBase_andDetach() throws Exception {
        var first = sandbox.git(sandbox.main, "rev-parse", "HEAD");
        Files.writeString(sandbox.main.resolve("README"), "two\n");
        sandbox.git(sandbox.main, "commit", "-q", "-am", "chore: two");
        sandbox.git(sandbox.main, "push", "-q", "origin", "HEAD:main");

        assertThat(sandbox.run("wt-new", "alpha", "--branch", "feature-a", "--base", first).rc()).isZero();
        var alpha = sandbox.root.resolve("alpha");
        assertThat(sandbox.git(alpha, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature-a");
        assertThat(sandbox.git(alpha, "rev-parse", "HEAD")).isEqualTo(first);

        assertThat(sandbox.run("wt-new", "review", "--detach", "--base", "origin/main").rc()).isZero();
        var review = sandbox.root.resolve("review");
        assertThat(sandbox.git(review, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("HEAD");
        assertThat(sandbox.git(review, "rev-parse", "HEAD")).isEqualTo(sandbox.git(sandbox.main, "rev-parse", "origin/main"));
        assertThat(sandbox.ran(List.of("git", "show-ref", "--verify", "refs/heads/review"), sandbox.main).rc()).isNotZero();
    }

    @Test
    void refusesAnExistingName() throws Exception {
        Files.createDirectories(sandbox.root.resolve("alpha"));
        var outcome = sandbox.run("wt-new", "alpha");

        assertRefusedCreatingNothing(outcome, "exists", "alpha", true);
    }

    @Test
    void refusesARegisteredWorktreeMissingOnDisk() throws Exception {
        assertThat(sandbox.run("wt-new", "alpha").rc()).isZero();
        deleteTree(sandbox.root.resolve("alpha"));
        var outcome = sandbox.run("wt-new", "alpha", "--branch", "other");

        assertRefusedCreatingNothing(outcome, "is a registered worktree", "other", false);
    }

    @Test
    void refusesAnExistingBranch() throws Exception {
        sandbox.git(sandbox.main, "branch", "alpha");
        var outcome = sandbox.run("wt-new", "alpha");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("branch alpha exists");
        assertThat(sandbox.root.resolve("alpha")).doesNotExist();
    }

    @Test
    void refusesLowDisk() throws Exception {
        sandbox.env.put("TEMPER_MIN_FREE_GIB", "100000000");

        assertRefusedCreatingNothing(sandbox.run("wt-new", "alpha"), "too little disk", "alpha", false);
    }

    @Test
    void refusesWithoutASeed() throws Exception {
        deleteTree(sandbox.main.resolve(".m2-local"));

        assertRefusedCreatingNothing(sandbox.run("wt-new", "alpha"), "no seed", "alpha", false);
    }

    /// A tree made before wt-new wrote `.seed-complete`: not picked by itself, and the refusal says how to name it.
    @Test
    void refusesAnUnmarkedSeed_namingTemperM2Seed() throws Exception {
        Files.delete(sandbox.main.resolve(".m2-local/.seed-complete"));
        var outcome = sandbox.run("wt-new", "alpha");

        assertRefusedCreatingNothing(outcome, "no worktree has a complete .m2-local", "alpha", false);
        assertThat(outcome.output()).contains(".seed-complete").contains("TEMPER_M2_SEED");
        sandbox.env.put("TEMPER_M2_SEED", sandbox.main.resolve(".m2-local").toString());
        assertThat(sandbox.run("wt-new", "alpha").rc()).isZero();
    }

    @Test
    void refusesAHardLinkedSeed() throws Exception {
        Files.createLink(dir.resolve("elsewhere.pom"), sandbox.main.resolve(".m2-local/org/example/a/1/a-1.pom"));

        assertRefusedCreatingNothing(sandbox.run("wt-new", "alpha"), "has hard-linked files", "alpha", false);
    }

    @Test
    void refusesABaseThatIsNotACommit() throws Exception {
        assertRefusedCreatingNothing(sandbox.run("wt-new", "alpha", "--base", "origin/absent"), "is not a commit", "alpha", false);
    }

    @Test
    void usage() throws Exception {
        assertThat(sandbox.run("wt-new").rc()).isEqualTo(2);
        assertThat(sandbox.run("wt-new", "../escape").rc()).isEqualTo(2);
        assertThat(sandbox.run("wt-new", "alpha", "--branch", "b", "--detach").rc()).isEqualTo(2);
        assertThat(sandbox.run("wt-new", "alpha", "--base").rc()).isEqualTo(2);
        assertThat(sandbox.run("wt-new", "alpha", "--bogus").rc()).isEqualTo(2);
        assertThat(sandbox.root.resolve("alpha")).doesNotExist();
    }

    private void assertRefusedCreatingNothing(DevSandbox.Outcome outcome, String reason, String branch, boolean dirExisted)
            throws Exception {
        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("refused: ").contains(reason);
        if (!dirExisted) {
            assertThat(sandbox.root.resolve("alpha")).doesNotExist();
        }
        assertThat(sandbox.ran(List.of("git", "show-ref", "--verify", "refs/heads/" + branch), sandbox.main).rc())
            .as("no branch " + branch).isNotZero();
    }

    private void assertRolledBack(DevSandbox.Outcome outcome, String reason, String branch) throws Exception {
        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(4);
        assertThat(outcome.output()).contains(reason).contains("rolled back");
        assertThat(sandbox.root.resolve("alpha")).doesNotExist();
        assertThat(sandbox.git(sandbox.main, "worktree", "list")).doesNotContain(sandbox.root.resolve("alpha").toString());
        assertThat(sandbox.ran(List.of("git", "show-ref", "--verify", "refs/heads/" + branch), sandbox.main).rc())
            .as("no branch " + branch).isNotZero();
        assertThat(sandbox.main.resolve(".m2-local/org/example/a/1/a-1.pom")).hasContent("main seed");
        assertThat(DevSandbox.linked(sandbox.main.resolve(".m2-local"))).as("the seed").isEmpty();
    }

    private static void deleteTree(Path path) throws Exception {
        try (var walk = Files.walk(path)) {
            for (var each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }
}
