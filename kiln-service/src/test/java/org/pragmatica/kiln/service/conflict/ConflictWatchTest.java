package org.pragmatica.kiln.service.conflict;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.conflict.Changes;
import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.conflict.ConflictGit.ObjectFiles;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.assertj.core.api.Assertions.assertThat;


/// temper #100 against real git, no network: a bare "mirror" built here whose history reconstructs the real conflicts
/// of 2026-11-06/07 on pragmatica. #2711 and #2713 both took the next free serialization tags at the tail of
/// `SystemTags.java` (live mirror: `merge-tree refs/pull/2711/head refs/pull/2713/head` exits 1 on that path), while
/// #2711 and #2770 merge cleanly (exit 0). The fixture keeps the real path and the real kind of edit: both PRs rewrite
/// the `RESERVED` line after adding their pins.
///
/// - positive control (pair): #2711 + #2713 are flagged, with `SystemTags.java` marked hot;
/// - negative control: #2711 + #2770 overlap (both touch README.md) but merge cleanly, so nothing is flagged; #2801
///   overlaps nobody and is never paired;
/// - positive control (tip): after #2711 squash-merges, #2713 is flagged against the new tip;
/// - the mirror is never written; a restart appends nothing; a failed check carries, never clears.
class ConflictWatchTest {
    private static final BranchName TIP_BRANCH = BranchName.branchName("release-1.0.0-rc5").unwrap();

    private static final String TAGS = "integrations/serialization/api/src/main/java/org/pragmatica/serialization/SystemTags.java";

    private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");

    @TempDir
    Path dir;

    private Path work;
    private Path mirror;
    private Path log;
    private final AtomicReference<List<PullRequest>> open = new AtomicReference<>(List.of());
    private Sha base;
    private Sha h2711;
    private Sha h2713;
    private Sha h2770;
    private Sha h2801;

    @BeforeEach
    void history() throws Exception {
        work = dir.resolve("work");
        mirror = dir.resolve("mirror.git");
        log = dir.resolve("conflicts").resolve("events.log");
        Files.createDirectories(work);
        git(work, "init", "-q", "-b", "release-1.0.0-rc5");
        write(TAGS, tags("        // ---- 2225..16383 RESERVED ----"));
        write("README.md", "top\nmiddle 1\nmiddle 2\nmiddle 3\nmiddle 4\nbottom\n");
        write("Other.java", "class Other {}\n");
        base = commit("base");
        h2711 = branch("pr2711",
                       () -> {
                           write(TAGS,
                                 tags("        pin(table, 2225, \"org.pragmatica.aether.AutoHealStateValue\");\n"
                                     + "        // ---- 2226..16383 RESERVED ----"));
                           write("README.md", "top 2711\nmiddle 1\nmiddle 2\nmiddle 3\nmiddle 4\nbottom\n");
                       });
        h2713 = branch("pr2713",
                       () -> write(TAGS,
                                   tags("        pin(table, 2225, \"org.pragmatica.aether.slice.kvstore.AetherKey.AbTestBaseKey\");\n"
                                       + "        pin(table, 2226, \"org.pragmatica.aether.slice.kvstore.AetherValue.AbTestBaseValue\");\n"
                                       + "        // ---- 2227..16383 RESERVED ----")));
        h2770 = branch("pr2770",
                       () -> {
                           write("README.md", "top\nmiddle 1\nmiddle 2\nmiddle 3\nmiddle 4\nbottom 2770\n");
                           write("Other.java", "class Other { int x; }\n");
                       });
        h2801 = branch("pr2801", () -> write("docs/new.txt", "new\n"));
        git(dir, "clone", "-q", "--bare", work.toString(), mirror.toString());
        pull(2711, h2711);
        pull(2713, h2713);
        pull(2770, h2770);
        pull(2801, h2801);
        open.set(List.of(pr(2711, h2711), pr(2713, h2713), pr(2770, h2770), pr(2801, h2801)));
    }

    @Test
    void pair_positiveAndNegativeControls_onlyTheRealConflictIsFlagged() throws Exception {
        var looseBefore = loose(mirror);

        assertThat(looseBefore).isNotEmpty();
        var board = step(watch());

        assertThat(board.predictions()).hasSize(1);
        var pair = board.predictions().getFirst();

        assertThat(pair.kind()).isEqualTo(Prediction.Kind.PAIR);
        assertThat(pair.key()).isEqualTo("pair:2711+2713");
        assertThat(pair.paths()).containsExactly(TAGS);
        assertThat(pair.hotPaths()).containsExactly(TAGS);
        assertThat(pair.tip()).isEqualTo(base);
        assertThat(lines()).hasSize(1);
        assertThat(lines().getFirst()).endsWith("\tpair\tnew\t2711+2713\t*" + TAGS
                                               + "\t" + base.value()
                                               + "\t2711>2713,2713>2711")
                  .startsWith(T0.toString());
        assertThat(board.costs()).hasSize(1);
        assertThat(board.costs().getFirst().tipChecks()).isEqualTo(4);
        assertThat(board.costs().getFirst().pairChecks()).as("2711+2713 and 2711+2770 overlap; 2713+2770 and 2801 do not")
                  .isEqualTo(2);
        assertThat(loose(mirror)).as("merge-tree and commit-tree write to scratch, never into the mirror")
                  .isEqualTo(looseBefore);
    }

    @Test
    void secondStep_sameHeadsAndTip_runsNoCheck_andAppendsNothing() throws Exception {
        var watch = watch();

        step(watch);
        var again = step(watch);

        assertThat(lines()).hasSize(1);
        assertThat(again.costs()).as("a step with every answer cached costs no row").hasSize(1);
        assertThat(again.predictions()).hasSize(1);
    }

    @Test
    void tipMoves_afterSquashMerge_thePairClears_andTheTipCheckPredictsDirty() throws Exception {
        var watch = watch();

        step(watch);
        var tip = squashOntoTip(h2711);

        open.set(List.of(pr(2713, h2713), pr(2770, h2770), pr(2801, h2801)));
        var board = step(watch);

        assertThat(board.tip()).isEqualTo(Option.some(tip));
        assertThat(board.predictions()).extracting(Prediction::key).containsExactly("tip:2713");
        assertThat(board.predictions().getFirst().paths()).containsExactly(TAGS);
        assertThat(lines()).hasSize(3);
        assertThat(lines().get(1)).contains("\tpair\tcleared\t2711+2713\t").contains("\t" + tip.value() + "\t");
        assertThat(lines().get(2)).contains("\ttip\tnew\t2713\t*" + TAGS + "\t" + tip.value());
        assertThat(board.costs().getFirst().tipChecks()).as("the tip moved: every open PR is checked again")
                  .isEqualTo(3);
    }

    @Test
    void fetchedBytes_measureTheMirror_notTheScratchTheChecksWriteTo() throws Exception {
        var watch = watch();

        open.set(List.of(pr(2711, h2711), pr(2713, h2713), pr(2801, h2801)));
        step(watch);
        open.set(List.of(pr(2711, h2711), pr(2713, h2713), pr(2770, h2770), pr(2801, h2801)));
        var board = step(watch);
        var cost = board.costs().getFirst();

        assertThat(cost.pairChecks()).as("2711+2770 squash-merges: a commit and a tree written to scratch").isEqualTo(1);
        assertThat(cost.fetchedBytes()).as("the mirror did not change between the steps").isEqualTo(Option.some(0L));
    }

    /// temper #136: a loose object's size is its length, never the blocks the filesystem has allocated so far. On ZFS a
    /// fresh file reads 1 KiB of blocks until its transaction group commits, so a block count moved between two steps with
    /// nothing fetched. A sparse file is the deterministic stand-in: 64 KiB long, no block allocated.
    @Test
    void fetchedBytes_areTheLengthsOfTheAddedObjects_notTheBlocksAllocatedSoFar() throws Exception {
        var watch = watch();

        open.set(List.of(pr(2711, h2711), pr(2713, h2713), pr(2801, h2801)));
        step(watch);
        try (var file = new java.io.RandomAccessFile(Files.createDirectories(mirror.resolve("objects").resolve("fe"))
                                                          .resolve("e".repeat(38))
                                                          .toFile(),
                                                     "rw")) {
            file.setLength(64 * 1024);
        }

        open.set(List.of(pr(2711, h2711), pr(2713, h2713), pr(2770, h2770), pr(2801, h2801)));
        var board = step(watch);

        assertThat(board.costs()).as("2711+2770 is checked: the step costs a row").hasSize(2);
        assertThat(board.costs().getFirst().fetchedBytes()).isEqualTo(Option.some(64L * 1024));
    }

    @Test
    void restart_seedsFromTheLogTail_andAppendsNothingForWhatWasAnnounced() throws Exception {
        step(watch());
        step(watch());
        assertThat(lines()).hasSize(1);
    }

    @Test
    void headNotInTheMirror_isUnknown_neverAPrediction() throws Exception {
        var absent = Sha.sha("1111111111111111111111111111111111111111").unwrap();

        open.set(List.of(pr(2900, absent)));
        var board = step(watch());

        assertThat(board.unknown()).isEqualTo(1);
        assertThat(board.predictions()).isEmpty();
        assertThat(lines()).isEmpty();
    }

    @Test
    void failedMerge_carriesTheAnnouncement_neverClearsIt() throws Exception {
        step(watch());
        var failing = new AtomicReference<>(Set.<Sha> of(h2713));
        var watch = ConflictWatch.conflictWatch(failingMerges(failing),
                                                () -> Promise.success(open.get()),
                                                TIP_BRANCH,
                                                EventsLog.file(log),
                                                () -> T0,
                                                Duration.ofMinutes(1));
        var board = step(watch);

        assertThat(lines()).as("the pair is undetermined while #2713's check fails").hasSize(1);
        assertThat(board.carried()).extracting(Prediction::key).containsExactly("pair:2711+2713");
        assertThat(board.unknown()).isEqualTo(1);
    }

    @Test
    void merged_exitOtherThanZeroOrOne_failsTheCheck_neverReadsClean() {
        var result = GitConflicts.merged(Completed.completed("git", 128, new byte[0], "fatal".getBytes(), false)).await();

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void objectFiles_growthIsTheAddedLengths_andARemovedOrChangedFileIsARewrite_evenWhenTheTotalGrew() {
        var before = new ObjectFiles(Map.of("ab/" + "c".repeat(38), 100L, "pack/pack-1.pack", 5000L));

        assertThat(new ObjectFiles(Map.of("ab/" + "c".repeat(38),
                                          100L,
                                          "pack/pack-1.pack",
                                          5000L,
                                          "pack/pack-2.pack",
                                          700L)).grownSince(before)).isEqualTo(Option.some(700L));
        assertThat(new ObjectFiles(Map.of("pack/pack-1.pack", 5000L, "pack/pack-3.pack", 9000L)).grownSince(before)).as("gc packed the loose object: nothing was fetched")
                  .isEqualTo(Option.none());
        assertThat(new ObjectFiles(Map.of("ab/" + "c".repeat(38), 100L, "pack/pack-1.pack", 6000L)).grownSince(before)).isEqualTo(Option.none());
    }

    @Test
    void resolvedWhileOpen_isCleared_pairAndTip() throws Exception {
        var watch = watch();

        step(watch);
        var h2713b = branch("pr2713b", () -> write("Other2.java", "class Other2 {}\n"));

        git(work, "push", "-q", mirror.toString(), "+pr2713b:refs/pull/2713/head");
        open.set(List.of(pr(2711, h2711), pr(2713, h2713b), pr(2770, h2770), pr(2801, h2801)));
        step(watch);
        assertThat(lines()).hasSize(2);
        assertThat(lines().get(1)).contains("\tpair\tcleared\t2711+2713\t");
        var tip = commitOnTip(() -> write(TAGS,
                                          tags("        pin(table, 2225, \"org.pragmatica.aether.Other\");\n"
                                              + "        // ---- 2226..16383 RESERVED ----")));

        step(watch);
        assertThat(lines().get(2)).contains("\ttip\tnew\t2711\t");
        git(work, "checkout", "-q", "-b", "pr2711b", tip.value());
        write("README.md", "top 2711\nmiddle 1\nmiddle 2\nmiddle 3\nmiddle 4\nbottom\n");
        var h2711b = commit("pr2711b");

        git(work, "checkout", "-q", "release-1.0.0-rc5");
        git(work, "push", "-q", mirror.toString(), "+pr2711b:refs/pull/2711/head");
        open.set(List.of(pr(2711, h2711b), pr(2713, h2713b), pr(2770, h2770), pr(2801, h2801)));
        var board = step(watch);

        assertThat(board.predictions()).isEmpty();
        assertThat(board.carried()).isEmpty();
        assertThat(lines()).hasSize(4);
        assertThat(lines().get(3)).contains("\ttip\tcleared\t2711\t").endsWith(tip.value() + "\t");
    }

    @Test
    void noMirror_everyGitQuestionFails_neverAnswersAbsentOrZero() {
        var missing = GitConflicts.gitConflicts(ProcessRunner.processRunner(), dir.resolve("none.git"), dir.resolve("s"));

        assertThat(missing.present(h2711).await().isFailure()).isTrue();
        assertThat(missing.mirrorObjects().await().isFailure()).isTrue();
        assertThat(new ConflictBoard.Cost(T0, none(), Option.some(2048L), 5, 1, 0).line()).endsWith("fetch n/a, 2048 bytes");
    }

    @Test
    void otherBase_isNeverChecked() throws Exception {
        var onMain = new PullRequest(PrNumber.prNumber(2711).unwrap(),
                                     "pr",
                                     PrState.OPEN,
                                     false,
                                     BranchName.branchName("main").unwrap(),
                                     BranchName.branchName("b2711").unwrap(),
                                     none(),
                                     h2711,
                                     none(),
                                     none(),
                                     T0,
                                     List.of(),
                                     List.of(),
                                     List.of());

        open.set(List.of(onMain, pr(2713, h2713)));
        var board = step(watch());

        assertThat(board.open()).isEqualTo(1);
        assertThat(board.predictions()).isEmpty();
    }

    @Test
    void tipConflictsWithBothPrs_thePairIsCarried_notRun_andNotCleared() throws Exception {
        var watch = watch();

        step(watch);
        var tip = commitOnTip(() -> write(TAGS,
                                          tags("        pin(table, 2225, \"org.pragmatica.aether.Other\");\n"
                                              + "        // ---- 2226..16383 RESERVED ----")));
        var board = step(watch);

        assertThat(board.predictions()).extracting(Prediction::key).containsExactly("tip:2711", "tip:2713");
        assertThat(board.carried()).extracting(Prediction::key).containsExactly("pair:2711+2713");
        assertThat(board.costs().getFirst().pairChecks()).as("a PR that conflicts with the tip is never paired")
                  .isZero();
        assertThat(board.costs().getFirst().fetchedBytes().or(0L)).as("the tip commit grew the mirror").isPositive();
        assertThat(lines()).hasSize(3).noneMatch(line -> line.contains("\tcleared\t"));
        assertThat(lines().get(2)).endsWith(tip.value() + "\t");
    }

    @Test
    void failedPairCheck_carriesThePair() throws Exception {
        step(watch());
        var tip = base;
        var board = step(ConflictWatch.conflictWatch(faulty((ours, _) -> !ours.equals(tip), false),
                                                     () -> Promise.success(open.get()),
                                                     TIP_BRANCH,
                                                     EventsLog.file(log),
                                                     () -> T0,
                                                     Duration.ofMinutes(1)));

        assertThat(board.predictions()).isEmpty();
        assertThat(board.carried()).extracting(Prediction::key).containsExactly("pair:2711+2713");
        assertThat(lines()).hasSize(1);
    }

    @Test
    void failedTipCheck_carriesTheTipPrediction() throws Exception {
        var watch = watch();

        step(watch);
        squashOntoTip(h2711);
        open.set(List.of(pr(2713, h2713)));
        step(watch);
        var board = step(ConflictWatch.conflictWatch(failingMerges(new AtomicReference<>(Set.of(h2713))),
                                                     () -> Promise.success(open.get()),
                                                     TIP_BRANCH,
                                                     EventsLog.file(log),
                                                     () -> T0,
                                                     Duration.ofMinutes(1)));

        assertThat(board.carried()).extracting(Prediction::key).containsExactly("tip:2713");
        assertThat(lines()).hasSize(3);
    }

    @Test
    void fetched_recordsTheFetchWallTime_andTheBoardIsTheLastStep() {
        var watch = watch();
        var board = watch.fetched(T0.minusMillis(1500)).await().unwrap().unwrap();

        assertThat(board.costs().getFirst().fetchMillis()).isEqualTo(Option.some(1500L));
        assertThat(watch.board()).isEqualTo(board);
        assertThat(board.costs().getFirst().line()).isEqualTo("conflict checks: 4 tip, 2 pair in 0 ms; fetch 1500 ms, bytes n/a");
    }

    @Test
    void unreadableTip_failsTheStep_keepingTheLastBoard() throws Exception {
        var watch = watch();
        var good = step(watch);

        git(mirror, "update-ref", "-d", "refs/heads/release-1.0.0-rc5");
        var failed = step(watch);

        assertThat(failed.failure().or("")).contains("rev-parse");
        assertThat(failed.predictions()).isEqualTo(good.predictions());
        assertThat(failed.checkedAt()).isEqualTo(good.checkedAt());
    }

    @Test
    void appendFailure_isShown_andTheLinesAreOfferedAgainNextStep() throws Exception {
        Files.writeString(log.getParent(), "a file where the log's directory belongs");
        var watch = watch();
        var failed = step(watch);

        assertThat(failed.failure().or("")).startsWith("events.log append failed");
        Files.delete(log.getParent());
        step(watch);
        assertThat(lines()).hasSize(1);
    }

    @Test
    void unreadableMirrorSize_leavesTheBytesUnknown() throws Exception {
        var board = step(ConflictWatch.conflictWatch(faulty((_, _) -> false, true),
                                                     () -> Promise.success(open.get()),
                                                     TIP_BRANCH,
                                                     EventsLog.file(log),
                                                     () -> T0,
                                                     Duration.ofMinutes(1)));

        assertThat(board.costs().getFirst().fetchedBytes().isEmpty()).isTrue();
        assertThat(board.predictions()).hasSize(1);
    }

    /// A `gc` is not a fetch: whether the repacked store is smaller or larger (on this fixture's tiny objects the pack and
    /// its index outweigh the loose files), nothing it wrote is counted.
    @Test
    void mirrorRepacked_byGc_theBytesAreUnknown_neverCountedAsFetched() throws Exception {
        var watch = watch();

        open.set(List.of(pr(2711, h2711), pr(2713, h2713), pr(2801, h2801)));
        step(watch);
        git(mirror, "gc", "-q", "--prune=now");
        open.set(List.of(pr(2711, h2711), pr(2713, h2713), pr(2770, h2770), pr(2801, h2801)));
        var board = step(watch);

        assertThat(board.costs().getFirst().fetchedBytes().isEmpty()).isTrue();
    }

    /// The file filter, pinned through the real [GitConflicts] over a hand-built `objects/`: loose `xx/<38|62 hex>` and
    /// `pack/pack-<hex>.pack` count; everything else git keeps there does not (indexes, temporary and quarantined files).
    @Test
    void objectFiles_countOnlyLooseObjectsAndPackData() throws Exception {
        var objects = Files.createDirectories(dir.resolve("filter.git").resolve("objects"));
        var sha1 = "ab/" + "c".repeat(38);
        var sha256 = "cd/" + "e".repeat(62);

        write(objects, sha1, 11);
        write(objects, sha256, 12);
        write(objects, "pack/pack-" + "a".repeat(40) + ".pack", 13);
        write(objects, "pack/pack-" + "a".repeat(40) + ".idx", 14);
        write(objects, "pack/pack-" + "a".repeat(40) + ".rev", 15);
        write(objects, "pack/pack-" + "a".repeat(40) + ".keep", 16);
        write(objects, "pack/tmp_pack_Xy12Ab", 17);
        write(objects, "pack/multi-pack-index", 18);
        write(objects, "ab/tmp_obj_Xy12Ab", 19);
        write(objects, "incoming-Xy12Ab/" + "ef/" + "d".repeat(38), 20);
        write(objects, "info/commit-graph", 21);
        write(objects, "ab/" + "c".repeat(37), 22);
        write(objects, "AB/" + "C".repeat(38), 23);
        var files = GitConflicts.gitConflicts(ProcessRunner.processRunner(),
                                              dir.resolve("filter.git"),
                                              dir.resolve("s"))
                                .mirrorObjects()
                                .await()
                                .unwrap();

        assertThat(files.lengths()).containsOnly(Map.entry(sha1, 11L),
                                                 Map.entry(sha256, 12L),
                                                 Map.entry("pack/pack-" + "a".repeat(40) + ".pack", 13L));
    }

    /// A symlinked `objects/` is followed, never read as an empty store (which reported zero bytes fetched at every step).
    @Test
    void objectFiles_aSymlinkedObjectsDirectory_isFollowed_neverReadAsEmpty() throws Exception {
        var real = Files.createDirectories(dir.resolve("real-objects"));
        var sha1 = "ab/" + "c".repeat(38);

        write(real, sha1, 33);
        var mirrorDir = Files.createDirectories(dir.resolve("linked.git"));

        Files.createSymbolicLink(mirrorDir.resolve("objects"), real);
        var files = GitConflicts.gitConflicts(ProcessRunner.processRunner(),
                                              mirrorDir,
                                              dir.resolve("s"))
                                .mirrorObjects()
                                .await()
                                .unwrap();

        assertThat(files.lengths()).containsOnly(Map.entry(sha1, 33L));
    }

    /// A dangling `objects/` link cannot be read: unknown (a failed read), not an empty store.
    @Test
    void objectFiles_aDanglingObjectsLink_isUnreadable_neverZero() throws Exception {
        var mirrorDir = Files.createDirectories(dir.resolve("dangling.git"));

        Files.createSymbolicLink(mirrorDir.resolve("objects"), dir.resolve("nowhere"));
        assertThat(GitConflicts.gitConflicts(ProcessRunner.processRunner(),
                                             mirrorDir,
                                             dir.resolve("s"))
                               .mirrorObjects()
                               .await()
                               .isFailure()).isTrue();
    }

    private static void write(Path root, String name, int length) throws IOException {
        var file = root.resolve(name);

        Files.createDirectories(file.getParent());
        Files.write(file, new byte[length]);
    }

    @Test
    void tipMoves_theScratchIsEmptied() throws Exception {
        var watch = watch();

        step(watch);
        var before = scratchObjects();

        squashOntoTip(h2711);
        open.set(List.of(pr(2713, h2713)));
        step(watch);
        assertThat(before).isNotEmpty();
        assertThat(scratchObjects()).doesNotContainAnyElementsOf(before);
    }

    private List<Path> scratchObjects() throws IOException {
        try (var files = Files.walk(dir.resolve("scratch"))) {
            return files.filter(Files::isRegularFile)
                        .toList();
        }
    }

    /// A commit straight on the tip (not a PR), pushed to the mirror.
    private Sha commitOnTip(Edit edit) throws Exception {
        edit.apply();
        var tip = commit("on tip");

        git(work, "push", "-q", mirror.toString(), "release-1.0.0-rc5:refs/heads/release-1.0.0-rc5");

        return tip;
    }

    /// PR #108 review F3, real git: #3001 adds the FILE `a`, #3002 adds `a/b`. Their changed paths are disjoint, yet the
    /// merge conflicts (directory/file); the widened filter pairs them and the pair check finds it.
    @Test
    void directoryFileClash_disjointPaths_isPaired_andPredicted() throws Exception {
        var file = branch("pr3001", () -> write("a", "a file\n"));
        var dir = branch("pr3002", () -> write("a/b", "a file under a directory\n"));

        pushed(3001, "pr3001");
        pushed(3002, "pr3002");
        open.set(List.of(pr(3001, file), pr(3002, dir)));
        var board = step(watch());

        assertThat(board.costs().getFirst().pairChecks()).as("the pair is checked").isEqualTo(1);
        assertThat(board.predictions()).extracting(Prediction::key).containsExactly("pair:3001+3002");
    }

    /// PR #108 review F3, real git: #3003 moves the directory `d` to `e` (a package move), #3004 adds `d/new` in the old
    /// directory. Disjoint paths; git's directory rename detection makes the merge conflict, and the pair is checked.
    @Test
    void directoryRename_andAnAddInTheOldDirectory_isPaired_andPredicted() throws Exception {
        base = commitOnTip(() -> {
            write("d/one", "one\n");
            write("d/two", "two\n");
        });
        var moved = branch("pr3003", () -> git(work, "mv", "d", "e"));
        var added = branch("pr3004", () -> write("d/new", "new\n"));

        pushed(3003, "pr3003");
        pushed(3004, "pr3004");
        open.set(List.of(pr(3003, moved), pr(3004, added)));
        var board = step(watch());

        assertThat(board.costs().getFirst().pairChecks()).as("the pair is checked").isEqualTo(1);
        assertThat(board.predictions()).extracting(Prediction::key).containsExactly("pair:3003+3004");
    }

    /// PR #108 review F4, the reviewer's minimal repro (squash order is not symmetric): `f` holds l0..l9; the tip deletes
    /// l8. #4001, branched at the old base, also deletes l8; #4002, branched at the tip, deletes l9. Both are clean
    /// against the tip. #4001 first, then #4002 is clean; #4002 first, then #4001 conflicts on `f`, because #4001's merge
    /// base is its own older branch point. The lower-first order alone reads the pair clean; both orders predict it.
    @Test
    void squashOrderIsNotSymmetric_theReverseOrderConflicts_andTheEventNamesIt() throws Exception {
        var lines = "l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9\n";

        base = commitOnTip(() -> write("f", lines));
        var old = base;
        var b4001 = branch("pr4001", () -> write("f", lines.replace("l8\n", "")));

        base = commitOnTip(() -> write("f", lines.replace("l8\n", "")));
        var a4002 = branch("pr4002",
                           () -> write("f",
                                       lines.replace("l8\n", "").replace("l9\n", "")));

        pushed(4001, "pr4001");
        pushed(4002, "pr4002");
        open.set(List.of(pr(4001, b4001), pr(4002, a4002)));
        var board = step(watch());

        assertThat(old).isNotEqualTo(base);
        assertThat(board.predictions()).hasSize(1);
        var pair = board.predictions().getFirst();

        assertThat(pair.key()).isEqualTo("pair:4001+4002");
        assertThat(pair.firsts()).as("only #4002 landing first conflicts")
                  .containsExactly(PrNumber.prNumber(4002).unwrap());
        assertThat(pair.paths()).containsExactly("f");
        assertThat(lines()).hasSize(1);
        assertThat(lines().getFirst()).endsWith("\tpair\tnew\t4001+4002\tf\t" + base.value() + "\t4002>4001");
    }

    /// PR #108 review F5: a kiln killed inside a multi-byte character leaves a torn tail. The watch still steps (the tail
    /// decodes, replacing the torn bytes), and its next line starts on a line of its own, so a restart replays it.
    @Test
    void tornMultibyteTail_neverWedgesTheWatch_andTheNextEventIsItsOwnLine() throws Exception {
        Files.createDirectories(log.getParent());
        var torn = "2026-11-08T09:00:00Z\ttip\tnew\t2713\tdocs/\u00e9".getBytes(StandardCharsets.UTF_8);

        Files.write(log, java.util.Arrays.copyOf(torn, torn.length - 1));
        var board = step(watch());

        assertThat(board.failure().isPresent()).as("the torn tail fails nothing").isFalse();
        assertThat(board.predictions()).extracting(Prediction::key).containsExactly("pair:2711+2713");
        assertThat(lines()).hasSize(2);
        assertThat(lines().get(1)).startsWith(T0.toString()).contains("\tpair\tnew\t2711+2713\t");
        var restarted = step(watch());

        assertThat(restarted.failure().isPresent()).isFalse();
        assertThat(lines()).as("the restart replays the event appended after the torn line: nothing is announced again")
                  .hasSize(2);
    }

    /// PR #108 review F5: the tail is the last [EventsLog#TAIL] lines, read from the end; a partial first block line is
    /// dropped, never returned as a line.
    @Test
    void tail_readsOnlyTheLastLines_fromTheEnd() throws Exception {
        Files.createDirectories(log.getParent());
        var many = java.util.stream.IntStream.range(0, EventsLog.TAIL + 3000)
                                             .mapToObj(n -> "line " + n + " " + "x".repeat(40))
                                             .toList();

        Files.write(log, many, StandardCharsets.UTF_8);
        var tail = EventsLog.file(log).tail().await().unwrap();

        assertThat(tail).hasSize(EventsLog.TAIL);
        assertThat(tail.getFirst()).startsWith("line 3000 ");
        assertThat(tail.getLast()).startsWith("line " + (EventsLog.TAIL + 2999) + " ");
        assertThat(EventsLog.FileLog.lastLines(log, 2).unwrap()).containsExactly(many.get(many.size() - 2),
                                                                                 many.getLast());
    }

    /// The per-step cap on uncached pair checks (PR #108 cost bound): over it a pair is skipped and shown, its earlier
    /// announcement carried, and the next step, finding the first answers cached, runs it.
    @Test
    void pairCap_skipsTheRest_showsTheCount_andTheNextStepRunsThem() throws Exception {
        var watch = ConflictWatch.conflictWatch(git(),
                                                () -> Promise.success(open.get()),
                                                TIP_BRANCH,
                                                EventsLog.file(log),
                                                () -> T0,
                                                Duration.ofMinutes(1),
                                                1);
        var first = step(watch);

        assertThat(first.skipped()).as("2711+2770 is over the cap of 1").isEqualTo(1);
        assertThat(first.costs().getFirst().pairChecks()).isEqualTo(1);
        assertThat(first.predictions()).extracting(Prediction::key).containsExactly("pair:2711+2713");
        var second = step(watch);

        assertThat(second.skipped()).isZero();
        assertThat(second.costs().getFirst().pairChecks()).as("2711+2713 cached, 2711+2770 admitted").isEqualTo(1);
        assertThat(lines()).hasSize(1);
    }

    /// PR #108 review F5: the tail is read from the end. A 3 GiB sparse prefix (no newline in it) ahead of the lines
    /// would overflow a whole-file read; the backward read stops once the last lines are behind it.
    @Test
    void tail_ofAHugeFile_readsFromTheEnd_neverTheWholeFile() throws Exception {
        Files.createDirectories(log.getParent());
        try (var file = new java.io.RandomAccessFile(log.toFile(), "rw")) {
            file.setLength(3L * 1024 * 1024 * 1024);
            file.seek(file.length());
            file.write("\nfirst\nsecond\nthird\n".getBytes(StandardCharsets.UTF_8));
        }

        assertThat(EventsLog.FileLog.lastLines(log, 2).unwrap()).containsExactly("second", "third");
    }

    /// The backward read stops only once `count` + 1 newlines are behind it: with 40 000-byte lines the last 64 KiB block
    /// holds exactly two newlines, the second one ending a partial line that must never be returned.
    @Test
    void tail_aBlockEndingInsideALine_neverReturnsThePartialLine() throws Exception {
        Files.createDirectories(log.getParent());
        var lines = List.of("a".repeat(40_000), "b".repeat(40_000), "c".repeat(40_000));

        Files.write(log, lines, StandardCharsets.UTF_8);
        assertThat(EventsLog.FileLog.lastLines(log, 2).unwrap()).containsExactly(lines.get(1), lines.get(2));
    }

    /// PR #108 review F3: `--name-status -z` records, a rename carrying its old and new path; a record cut short fails.
    @Test
    void nameStatus_readsRenamesAddsAndEdits_andRefusesACutRecord() {
        var changes = GitConflicts.nameStatus("M\0a.java\0R087\0d/one\0e/one\0A\0d/new\0").unwrap();

        assertThat(changes.paths()).containsExactlyInAnyOrder("a.java", "d/one", "e/one", "d/new");
        assertThat(changes.added()).containsExactlyInAnyOrder("e/one", "d/new");
        assertThat(changes.movedFrom()).containsExactly("d");
        assertThat(GitConflicts.nameStatus("").unwrap().paths()).isEmpty();
        assertThat(GitConflicts.nameStatus("R100\0d/one\0").isFailure()).isTrue();
        assertThat(GitConflicts.nameStatus("M\0").isFailure()).isTrue();
    }

    // ---- fixture -------------------------------------------------------------------------------------------------
    private ConflictWatch watch() {
        return ConflictWatch.conflictWatch(git(),
                                           () -> Promise.success(open.get()),
                                           TIP_BRANCH,
                                           EventsLog.file(log),
                                           () -> T0,
                                           Duration.ofMinutes(1));
    }

    private GitConflicts git() {
        return GitConflicts.gitConflicts(ProcessRunner.processRunner(), mirror, dir.resolve("scratch"));
    }

    private ConflictGit failingMerges(AtomicReference<Set<Sha>> failing) {
        return faulty((_, theirs) -> failing.get()
                                            .contains(theirs),
                      false);
    }

    /// The real git, with the merges `failMerge` names failing, and the mirror's object files unreadable when `bytesFail`.
    private ConflictGit faulty(BiPredicate<Sha, Sha> failMerge, boolean bytesFail) {
        var real = git();

        return new ConflictGit() {
            public Promise<Sha> tip(BranchName branch) {
                return real.tip(branch);
            }

            public Promise<Boolean> present(Sha head) {
                return real.present(head);
            }

            public Promise<Changes> changed(Sha tip, Sha head) {
                return real.changed(tip, head);
            }

            public Promise<Merge> merge(Sha ours, Sha theirs) {
                return failMerge.test(ours, theirs)
                       ? Causes.cause("git merge-tree failed: 128").promise()
                       : real.merge(ours, theirs);
            }

            public Promise<Sha> squash(Sha tree, Sha parent) {
                return real.squash(tree, parent);
            }

            public Promise<ObjectFiles> mirrorObjects() {
                return bytesFail
                       ? Causes.cause("objects read failed").promise()
                       : real.mirrorObjects();
            }

            public Promise<Unit> resetScratch() {
                return real.resetScratch();
            }
        };
    }

    private static ConflictBoard step(ConflictWatch watch) {
        return watch.step()
                    .await()
                    .unwrap()
                    .unwrap();
    }

    private List<String> lines() throws IOException {
        return Files.exists(log)
               ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines()
                                                                            .toList()
               : List.of();
    }

    private static String tags(String tail) {
        return "final class SystemTags {\n    static Map<String, Integer> table() {\n"
             + "        pin(table, 2223, \"org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowClaimKey\");\n"
             + "        pin(table, 2224, \"org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue\");\n" + tail
             + "\n        rejectDuplicateTags(table);\n        return table;\n    }\n}\n";
    }

    private void write(String path, String content) throws IOException {
        var file = work.resolve(path);

        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private Sha commit(String message) throws Exception {
        git(work, "add", "-A");
        git(work, "commit", "-q", "-m", message);

        return Sha.sha(git(work, "rev-parse", "HEAD")).unwrap();
    }

    private Sha branch(String name, Edit edit) throws Exception {
        git(work, "checkout", "-q", "-b", name, base.value());
        edit.apply();
        var head = commit(name);

        git(work, "checkout", "-q", "release-1.0.0-rc5");

        return head;
    }

    /// GitHub's squash merge: one commit on the tip with the PR's merged tree.
    private Sha squashOntoTip(Sha head) throws Exception {
        git(work, "merge", "-q", "--squash", head.value());
        var tip = commit("squash");

        git(work, "push", "-q", mirror.toString(), "release-1.0.0-rc5:refs/heads/release-1.0.0-rc5");

        return tip;
    }

    /// A PR branch made after the mirror was cloned, pushed to the mirror as GitHub's `refs/pull/<n>/head`.
    private void pushed(long number, String branch) throws Exception {
        git(work, "push", "-q", mirror.toString(), "+" + branch + ":refs/pull/" + number + "/head");
    }

    private void pull(long number, Sha head) throws Exception {
        git(mirror, "update-ref", "refs/pull/" + number + "/head", head.value());
    }

    private static PullRequest pr(long number, Sha head) {
        return new PullRequest(PrNumber.prNumber(number).unwrap(),
                               "pr",
                               PrState.OPEN,
                               false,
                               TIP_BRANCH,
                               BranchName.branchName("b" + number).unwrap(),
                               none(),
                               head,
                               none(),
                               none(),
                               T0,
                               List.of(),
                               List.of(),
                               List.of());
    }

    /// Every file under the mirror's `objects/` with its length (not `count-objects`, whose kilobytes are allocated blocks
    /// and move on ZFS with nothing written, temper #136).
    private static Map<String, Long> loose(Path repo) throws IOException {
        var objects = repo.resolve("objects");

        try (var files = Files.walk(objects)) {
            var lengths = new java.util.TreeMap<String, Long>();

            for (var file : files.filter(Files::isRegularFile).toList()) {
                lengths.put(objects.relativize(file).toString(),
                            Files.size(file));
            }

            return lengths;
        }
    }

    @FunctionalInterface
    private interface Edit {
        void apply() throws Exception;
    }

    private static String git(Path in, String... args) throws IOException, InterruptedException {
        var argv = Stream.concat(Stream.of("git", "-C", in.toString()),
                                 Stream.of(args)).toList();
        var builder = HermeticProcess.builder(argv).redirectErrorStream(true);
        var env = builder.environment();

        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", "/dev/null");
        env.put("GIT_AUTHOR_NAME", "kiln");
        env.put("GIT_AUTHOR_EMAIL", "kiln@test");
        env.put("GIT_COMMITTER_NAME", "kiln");
        env.put("GIT_COMMITTER_EMAIL", "kiln@test");
        var process = builder.start();
        var out = new String(process.getInputStream().readAllBytes(),
                             StandardCharsets.UTF_8).trim();

        assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();

        return out;
    }
}
