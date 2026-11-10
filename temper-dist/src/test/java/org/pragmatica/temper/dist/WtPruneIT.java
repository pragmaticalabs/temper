package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/dev/wt-prune` (owner decision D-2): only a tree whose work has landed on origin/main, that is clean and idle,
/// goes, and only after what it holds outside git is archived. A dry run removes nothing; a tree with a live build, an
/// open (or unknown) PR, its own unmerged commits, uncommitted work (unless --include-dirty) or a file modified within
/// the idle threshold stays; branches are never deleted. "No commit of its own" is no keep reason any more: once main
/// moves, a fresh tree is an ancestor too (verifier finding 1), so cleanliness and activity guard it instead.
/// Probes: the dry-run guard removed → [#dryRun_removesNothing] red; the archive step skipped →
/// [#apply_includeDirty_archivesThenRemoves_keepingBranches] red; `--is-ancestor` dropped → [#keeps_withReasons] red
/// (unmerged removed); the gh-unknown keep turned into a remove → [#withoutGh_keepsEveryBranchTree] red; the dirt
/// check removed → [#dirty_isKept_unlessIncludeDirty] red; the activity check removed →
/// [#active_isKept_byItsNewestFile] red; each re-check before the removal removed → its row of
/// [#changesAfterTheChecks_keepTheTree] red; `|| true` after the archive → [#archiveFailing_keepsTheTree] and
/// [#anUnwritableArchive_keepsTreeAndBranch] red; the partial-archive cleanup removed →
/// [#aFailedCopy_removesItsPartialArchive] red; `--force` on every removal →
/// [#cleanRemoval_isWithoutForce_soGitRefusesWhatItSeesAsUntracked] red; module target/ dirs pruned from the
/// activity scan again → [#active_isKept_byItsNewestFile] and [#aBuildWritingTarget_afterTheChecks_keepsTheTree] red;
/// the .m2-local.partial exclusion dropped → [#apply_includeDirty_archivesThenRemoves_keepingBranches] red.
class WtPruneIT {
    @TempDir
    Path dir;
    private DevSandbox sandbox;

    @BeforeEach
    void sandbox() throws Exception {
        sandbox = new DevSandbox(dir);
        sandbox.m2(sandbox.main.resolve(".m2-local"), "seed");
        // gh pr list --head <branch> ...; runs $HOME/during-gh first (in the tree) to change it after the checks
        sandbox.fake("gh", "[ ! -f \"$HOME/during-gh\" ] || sh \"$HOME/during-gh\"\ncat \"$HOME/open-$4\" 2> /dev/null || echo 0");
        Files.writeString(sandbox.main.resolve(".git/info/exclude"), ".wp-state.md\n.wp-logs/\n");  // as wt-new sets it
    }

    @Test
    void dryRun_removesNothing() throws Exception {
        var merged = merged("done");
        var outcome = sandbox.run("wt-prune");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).containsPattern("done +would remove +merged into origin/main");
        assertThat(merged).isDirectory();
        assertThat(sandbox.root.resolve("_archive")).doesNotExist();
        assertThat(sandbox.git(sandbox.main, "worktree", "list")).contains(merged.toString());
    }

    @Test
    void apply_includeDirty_archivesThenRemoves_keepingBranches() throws Exception {
        var merged = merged("done");
        Files.writeString(merged.resolve("notes.txt"), "untracked\n");
        Files.writeString(merged.resolve("README"), "modified\n");
        Files.writeString(merged.resolve(".wp-state.md"), "state\n");  // ignored, and still archived
        Files.writeString(Files.createDirectories(merged.resolve(".m2-local/org")).resolve("a.pom"), "a");
        Files.writeString(Files.createDirectories(merged.resolve(".m2-local.partial/org")).resolve("a.pom"), "cut short");
        Files.writeString(Files.createDirectories(merged.resolve("mod/target")).resolve("out.class"), "built");
        Files.writeString(merged.resolve("mod/pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(merged.resolve("data/target")).resolve("keep.txt"), "not a module's");
        Files.writeString(Files.createDirectories(merged.resolve("mod/src")).resolve("New.java"), "class New {}");
        idle(merged);
        var outcome = sandbox.run("wt-prune", "--apply", "--include-dirty");
        var archive = sandbox.root.resolve("_archive/done");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).containsPattern("done +removed +merged into origin/main, idle, no open PR; archived to " + archive);
        assertThat(merged).doesNotExist();
        assertThat(sandbox.git(sandbox.main, "worktree", "list")).doesNotContain(merged.toString());
        assertThat(sandbox.git(sandbox.main, "show-ref", "--verify", "refs/heads/done")).as("branch kept").isNotEmpty();
        assertThat(archive.resolve("notes.txt")).hasContent("untracked");
        assertThat(archive.resolve("README")).hasContent("modified");
        assertThat(archive.resolve(".wp-state.md")).hasContent("state");
        assertThat(archive.resolve("mod/src/New.java")).hasContent("class New {}");
        assertThat(archive.resolve("mod/target")).as("a Maven module's target/").doesNotExist();
        assertThat(archive.resolve("data/target/keep.txt")).as("a target/ beside no pom.xml").hasContent("not a module's");
        assertThat(archive.resolve(".m2-local")).doesNotExist();
        assertThat(archive.resolve(".m2-local.partial")).as("a wt-new copy cut short").doesNotExist();
        assertThat(archive.resolve("tracked.diff")).content().contains("-one").contains("+modified");
        assertThat(archive.resolve("HEAD")).content().startsWith(sandbox.git(sandbox.main, "rev-parse", "done")).contains(" done");
    }

    /// Uncommitted work keeps a tree, unless --include-dirty; .m2-local/, .wp-logs/ and a module's target/ are not work.
    @Test
    void dirty_isKept_unlessIncludeDirty() throws Exception {
        var tracked = merged("tracked");
        Files.writeString(tracked.resolve("README"), "modified\n");
        var untracked = merged("untracked");
        Files.writeString(untracked.resolve("notes.txt"), "mine\n");
        var ignored = merged("ignored");
        Files.writeString(ignored.resolve(".wp-state.md"), "state\n");
        var stray = merged("stray");
        Files.writeString(Files.createDirectories(stray.resolve("target")).resolve("x"), "x");  // beside no pom.xml
        var clean = merged("clean");
        Files.writeString(Files.createDirectories(clean.resolve(".m2-local/org")).resolve("a.pom"), "a");
        Files.writeString(Files.createDirectories(clean.resolve(".wp-logs")).resolve("build.log"), "log");
        Files.writeString(Files.createDirectories(clean.resolve("target")).resolve("x"), "x");  // beside the root pom
        Files.writeString(clean.resolve("pom.xml"), "<project/>");
        sandbox.git(clean, "add", "pom.xml");
        sandbox.git(clean, "commit", "-q", "-m", "chore: pom");
        sandbox.git(sandbox.main, "merge", "-q", "--no-ff", "-m", "chore: merge pom", "clean");
        sandbox.git(sandbox.main, "push", "-q", "origin", "HEAD:main");
        for (var tree : List.of(tracked, untracked, ignored, stray, clean)) {
            idle(tree);
        }
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).containsPattern("tracked +kept +dirty: tracked change README \\(--include-dirty")
                                    .containsPattern("untracked +kept +dirty: untracked notes.txt")
                                    .containsPattern("ignored +kept +dirty: untracked .wp-state.md")
                                    .containsPattern("stray +kept +dirty: untracked target/")
                                    .containsPattern("clean +removed");
        assertThat(clean).doesNotExist();

        var forced = sandbox.run("wt-prune", "--apply", "--include-dirty");
        assertThat(forced.rc()).as(forced.output()).isZero();
        for (var tree : List.of(tracked, untracked, ignored, stray)) {
            assertThat(tree).as(forced.output()).doesNotExist();
        }
    }

    /// A file modified within TEMPER_PRUNE_IDLE_HOURS (default 2), .wp-state.md and .wp-logs/ included, means an agent
    /// is at work: kept. A module's target/ counts too: a build run without a lock writes nothing else. Writes into
    /// .m2-local/ alone are not activity (a cache any build of any tree may refresh).
    @Test
    void active_isKept_byItsNewestFile() throws Exception {
        var state = merged("state");
        idle(state);
        Files.writeString(state.resolve(".wp-state.md"), "state\n");
        var logs = merged("logs");
        Files.writeString(Files.createDirectories(logs.resolve(".wp-logs")).resolve("build.log"), "log");
        idle(logs);
        Files.writeString(logs.resolve(".wp-logs/build.log"), "more");
        var older = merged("older");
        Files.writeString(older.resolve(".wp-state.md"), "state\n");
        idle(older);
        Files.setLastModifiedTime(older.resolve(".wp-state.md"), FileTime.from(Instant.now().minus(Duration.ofMinutes(90))));
        var cache = merged("cache");
        Files.writeString(Files.createDirectories(cache.resolve(".m2-local/org")).resolve("a.pom"), "a");
        Files.writeString(Files.createDirectories(cache.resolve("mod/target")).resolve("out.class"), "built");
        Files.writeString(cache.resolve("mod/pom.xml"), "<project/>");
        sandbox.git(cache, "add", "mod/pom.xml");
        sandbox.git(cache, "commit", "-q", "-m", "chore: mod");
        sandbox.git(sandbox.main, "merge", "-q", "--no-ff", "-m", "chore: merge mod", "cache");
        sandbox.git(sandbox.main, "push", "-q", "origin", "HEAD:main");
        idle(cache);
        Files.writeString(cache.resolve(".m2-local/org/a.pom"), "b");
        var compiling = merged("compiling");
        Files.writeString(Files.createDirectories(compiling.resolve("mod/target")).resolve("old.class"), "built");
        Files.writeString(compiling.resolve("mod/pom.xml"), "<project/>");
        idle(compiling);
        Files.writeString(compiling.resolve("mod/target/out.class"), "being built");
        var outcome = sandbox.run("wt-prune", "--apply", "--include-dirty");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).containsPattern("state +kept +active: .wp-state.md modified within 2h")
                                    .containsPattern("logs +kept +active: .wp-logs")
                                    .containsPattern("older +kept +active: .wp-state.md")
                                    .containsPattern("compiling +kept +active: mod/target")
                                    .containsPattern("cache +removed");
        assertThat(state).isDirectory();
        assertThat(logs).isDirectory();

        sandbox.env.put("TEMPER_PRUNE_IDLE_HOURS", "1");
        var shorter = sandbox.run("wt-prune", "--apply", "--include-dirty");
        assertThat(shorter.output()).containsPattern("older +removed").containsPattern("state +kept +active: .wp-state.md modified within 1h");
    }

    /// The original finding: a tree made from origin/main with no commit yet is an ancestor once main moves on. Its
    /// fresh checkout is activity; its uncommitted work, once idle, is dirt.
    @Test
    void aTreeWithoutCommits_afterMainMoved_isGuardedByActivityAndDirt() throws Exception {
        var fresh = tree("fresh", "origin/main");
        var working = tree("working", "origin/main");
        Files.writeString(working.resolve("README"), "edited\n");
        idle(working);
        merged("other");
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.output()).containsPattern("fresh +kept +active: ").containsPattern("working +kept +dirty: tracked change README");
        assertThat(fresh).isDirectory();
        assertThat(working).isDirectory();
    }

    /// What happens between the checks and `git worktree remove` is checked again: a build lock taken, the tree turned
    /// dirty, any file changed (with --include-dirty, where dirt is no reason). The fake gh, called after the checks,
    /// makes the change.
    @Test
    void changesAfterTheChecks_keepTheTree() throws Exception {
        var during = sandbox.home.resolve("during-gh");
        var self = ProcessHandle.current().pid();
        record Row(String name, String change, String flag, String reason) {}
        for (var row : List.of(new Row("locking", "mkdir -p .wp-logs/build.lock; echo " + self + " > .wp-logs/build.lock/pid",
                                       "--apply", "build lock taken since checked: by live pid " + self),
                               new Row("dirtying", "echo x > new.txt", "--apply", "dirty since checked: untracked new.txt"),
                               new Row("changing", "echo y > README", "--include-dirty", "changed since checked: README"))) {
            var tree = merged(row.name());
            Files.writeString(during, row.change() + "\n");
            var outcome = sandbox.run("wt-prune", "--apply", row.flag());

            assertThat(outcome.output()).as(row.change()).containsPattern(row.name() + " +kept +" + row.reason());
            assertThat(tree).as(row.change()).isDirectory();
            Files.delete(during);
            sandbox.git(sandbox.main, "worktree", "remove", "--force", tree.toString());
        }
    }

    /// A build without a lock writing a module's target/ after the checks: the re-check before the removal sees it.
    @Test
    void aBuildWritingTarget_afterTheChecks_keepsTheTree() throws Exception {
        var tree = merged("building");
        Files.writeString(Files.createDirectories(tree.resolve("mod/target")).resolve("out.class"), "built");
        Files.writeString(tree.resolve("mod/pom.xml"), "<project/>");
        idle(tree);
        Files.writeString(sandbox.home.resolve("during-gh"), "echo rebuilt > mod/target/out.class\n");
        var outcome = sandbox.run("wt-prune", "--apply", "--include-dirty");

        assertThat(outcome.output()).containsPattern("building +kept +changed since checked: mod/target/out.class");
        assertThat(tree).isDirectory();
    }

    /// An `_archive` that cannot be written (chmod 555): kept with rc 4, tree and branch intact.
    @Test
    void anUnwritableArchive_keepsTreeAndBranch() throws Exception {
        var merged = merged("done");
        var archives = Files.createDirectories(sandbox.root.resolve("_archive"));
        Files.setPosixFilePermissions(archives, java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var outcome = sandbox.run("wt-prune", "--apply");

            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(4);
            assertThat(outcome.output()).containsPattern("done +kept +FAILED to archive");
            assertThat(merged).isDirectory();
            assertThat(sandbox.git(sandbox.main, "show-ref", "--verify", "refs/heads/done")).isNotEmpty();
        } finally {
            Files.setPosixFilePermissions(archives, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /// A copy that fails halfway (an unreadable file) keeps the tree and leaves no partial `_archive/<name>`, so the
    /// next run archives to `<name>` again rather than to `<name>.<epoch>`.
    @Test
    void aFailedCopy_removesItsPartialArchive() throws Exception {
        var merged = merged("done");
        var secret = Files.writeString(merged.resolve("secret.txt"), "unreadable\n");
        idle(merged);  // before the chmod: the times of an unreadable file cannot be set
        Files.setPosixFilePermissions(secret, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        var outcome = sandbox.run("wt-prune", "--apply", "--include-dirty");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(4);
        assertThat(outcome.output()).containsPattern("done +kept +FAILED to archive");
        assertThat(merged).isDirectory();
        assertThat(sandbox.root.resolve("_archive/done")).doesNotExist();

        Files.setPosixFilePermissions(secret, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        idle(merged);
        var again = sandbox.run("wt-prune", "--apply", "--include-dirty");

        assertThat(again.rc()).as(again.output()).isZero();
        assertThat(sandbox.root.resolve("_archive/done/secret.txt")).hasContent("unreadable");
    }

    /// A clean tree is removed without --force, so git's own check is the last guard after the re-checks. Here
    /// .wp-logs/ is not ignored (a repository wt-new never set up): wt-prune counts it clean, git does not, and the
    /// tree stays. This is also the dependency: removal of a clean tree needs .wp-logs/ ignored, as wt-new sets it.
    @Test
    void cleanRemoval_isWithoutForce_soGitRefusesWhatItSeesAsUntracked() throws Exception {
        Files.writeString(sandbox.main.resolve(".git/info/exclude"), "");
        var merged = merged("done");
        Files.writeString(Files.createDirectories(merged.resolve(".wp-logs")).resolve("build.log"), "log\n");
        idle(merged);
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(4);
        assertThat(outcome.output()).containsPattern("done +kept +FAILED: git worktree remove");
        assertThat(merged.resolve(".wp-logs/build.log")).hasContent("log\n");
    }

    @Test
    void archiveFailing_keepsTheTree() throws Exception {
        var merged = merged("done");
        Files.createDirectories(sandbox.root);
        Files.writeString(sandbox.root.resolve("_archive"), "a file where the archive directory goes\n");
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(4);
        assertThat(outcome.output()).containsPattern("done +kept +FAILED to archive");
        assertThat(merged).isDirectory();
    }

    @Test
    void apply_aSecondArchiveOfTheSameName_doesNotOverwriteTheFirst() throws Exception {
        var merged = merged("done");
        Files.writeString(Files.createDirectories(sandbox.root.resolve("_archive/done")).resolve("old"), "old\n");
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(merged).doesNotExist();
        assertThat(sandbox.root.resolve("_archive/done/old")).hasContent("old");
        try (var archives = Files.list(sandbox.root.resolve("_archive"))) {
            assertThat(archives.map(path -> path.getFileName().toString())).anyMatch(name -> name.matches("done\\.[0-9]+"));
        }
    }

    @Test
    void keeps_withReasons() throws Exception {
        var unmerged = tree("unmerged", "origin/main");
        commit(unmerged, "work");
        var building = merged("building");
        Files.writeString(Files.createDirectories(building.resolve(".wp-logs")).resolve("build.lock"),
                          ProcessHandle.current().pid() + "\n");
        var reviewed = merged("reviewed");
        Files.writeString(sandbox.home.resolve("open-reviewed"), "1\n");
        var named = merged("named");
        var locked = merged("locked");
        sandbox.git(sandbox.main, "worktree", "lock", locked.toString());
        var detached = merged("detached");
        sandbox.git(detached, "checkout", "-q", "--detach");
        var outcome = sandbox.run("wt-prune", "--apply", "--keep", "x,named");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).containsPattern("unmerged +kept +not merged into origin/main")
                                    .containsPattern("building +kept +build lock held by live pid " + ProcessHandle.current().pid())
                                    .containsPattern("reviewed +kept +branch reviewed has 1 open PR")
                                    .containsPattern("named +kept +--keep")
                                    .containsPattern("locked +kept +locked")
                                    .containsPattern("detached +removed");
        for (var kept : List.of(unmerged, building, reviewed, named, locked)) {
            assertThat(kept).isDirectory();
        }
        assertThat(detached).doesNotExist();
        assertThat(sandbox.main).as("the main checkout is never a candidate").isDirectory();
        assertThat(outcome.output()).doesNotContain(" main ");
    }

    /// A lock directory whose pid is not written yet is a build starting: kept.
    @Test
    void aLockBeingTaken_keepsTheTree() throws Exception {
        var merged = merged("starting");
        Files.createDirectories(merged.resolve(".wp-logs/build.lock"));
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.output()).containsPattern("starting +kept +build lock held being taken \\(no pid yet");
        assertThat(merged).isDirectory();
    }

    @Test
    void guardAndNewFiles_doNotBlockPrune() throws Exception {
        var merged = merged("done");
        var logs = Files.createDirectories(merged.resolve(".wp-logs"));
        Files.writeString(logs.resolve("build.guard"), ProcessHandle.current().pid() + "\n");
        Files.writeString(Files.createDirectories(logs.resolve("build.new-123")).resolve("pid"), ProcessHandle.current().pid() + "\n");
        idle(merged);
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.output()).containsPattern("done +removed");
        assertThat(merged).doesNotExist();
    }

    @Test
    void withoutGh_keepsEveryBranchTree() throws Exception {
        Files.delete(sandbox.bin.resolve("gh"));
        var merged = merged("done");
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("gh is not on PATH").containsPattern("done +kept +open PR unknown");
        assertThat(merged).isDirectory();
    }

    @Test
    void ghFailing_keepsTheTree() throws Exception {
        sandbox.fake("gh", "echo 'gh: not logged in' >&2; exit 4");
        var merged = merged("done");
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.output()).containsPattern("done +kept +open PR unknown");
        assertThat(merged).isDirectory();
    }

    @Test
    void aTreeMissingOnDisk_isLeftToGitWorktreePrune() throws Exception {
        var merged = merged("gone");
        deleteTree(merged);
        var outcome = sandbox.run("wt-prune", "--apply");

        assertThat(outcome.output()).containsPattern("gone +kept +directory missing");
        assertThat(sandbox.git(sandbox.main, "worktree", "list")).doesNotContain(merged.toString());
    }

    @Test
    void usage() throws Exception {
        assertThat(sandbox.run("wt-prune", "--bogus").rc()).isEqualTo(2);
        assertThat(sandbox.run("wt-prune", "--keep").rc()).isEqualTo(2);
        sandbox.env.put("TEMPER_PRUNE_IDLE_HOURS", "2h");
        assertThat(sandbox.run("wt-prune").rc()).isEqualTo(2);
    }

    /// A worktree on branch `name` with one commit, merged into origin/main by a merge commit (as PRs land), and idle.
    private Path merged(String name) throws Exception {
        var tree = tree(name, "origin/main");
        commit(tree, name);
        sandbox.git(sandbox.main, "merge", "-q", "--no-ff", "-m", "chore: merge " + name, name);
        sandbox.git(sandbox.main, "push", "-q", "origin", "HEAD:main");
        return idle(tree);
    }

    /// Every file and directory of `tree` last modified three hours ago: past the default idle threshold of two.
    private static Path idle(Path tree) throws Exception {
        var past = FileTime.from(Instant.now().minus(Duration.ofHours(3)));
        try (var walk = Files.walk(tree)) {
            for (var each : walk.toList()) {
                Files.setLastModifiedTime(each, past);
            }
        }
        return tree;
    }

    private static void deleteTree(Path path) throws Exception {
        try (var walk = Files.walk(path)) {
            for (var each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(each);
            }
        }
    }

    private Path tree(String name, String base) throws Exception {
        var tree = sandbox.root.resolve(name);
        sandbox.git(sandbox.main, "fetch", "-q", "origin");
        sandbox.git(sandbox.main, "worktree", "add", "-q", "-b", name, tree.toString(), base);
        return tree;
    }

    private void commit(Path tree, String file) throws Exception {
        Files.writeString(tree.resolve(file + ".txt"), file + "\n");
        sandbox.git(tree, "add", ".");
        sandbox.git(tree, "commit", "-q", "-m", "feat: " + file);
    }
}
