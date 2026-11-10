package org.pragmatica.kiln.service.conflict;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.pragmatica.kiln.core.conflict.Changes;
import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.assertj.core.api.Assertions.assertThat;


/// temper #112 on a scripted git: which pairs a step checks when more are owed than the cap, and when a failing pair
/// is tried again. Every PR edits the same file, so every pair overlaps; heads are `1000 + number`, the tip moves on demand.
class ConflictWatchScheduleTest {
    private static final BranchName BRANCH = BranchName.branchName("release-1.0.0-rc5").unwrap();
    private static final Instant T0 = Instant.parse("2026-11-08T10:00:00Z");

    /// A cap below the pairs owed and a tip that moves before every step (so no answer is ever cached): every pair is
    /// checked within ceil(pairs / cap) + 1 steps. Checked in number order, the lowest `cap` pairs repeat forever.
    @Test
    void tipMovesEveryStep_capBelowThePairs_everyPairIsCheckedWithinTheBound() {
        var git = new ScriptedGit();
        var watch = watch(git, new MemoryLog(), 5, prs(8));
        var pairs = 8 * 7 / 2;
        var bound = (pairs + 5 - 1) / 5 + 1;
        var seen = new HashSet<String>();

        for (var step = 1; step <= bound; step++) {
            git.moveTip();
            watch.step().await().unwrap();
            seen.addAll(git.attempts);
            git.attempts.clear();
        }

        assertThat(seen).as("pairs checked within " + bound + " steps").hasSize(pairs);
    }

    /// A pair whose git call fails is attempted on steps 1, 2, 4, 8 after its first failure, not on every step; it stays
    /// on the board as carried (its announcement kept, nothing cleared), never as skipped.
    @Test
    void failingPair_backsOff_staysCarried_andKeepsItsAnnouncement() {
        var git = new ScriptedGit();
        var log = new MemoryLog();
        var watch = watch(git, log, 300, prs(3));

        git.conflicting.add(pairKey(1, 2));
        var first = watch.step().await().unwrap().unwrap();

        assertThat(first.predictions()).extracting(Prediction::key).containsExactly("pair:1+2");
        git.conflicting.clear();
        git.failing.add(pairKey(1, 2));
        var attemptedAt = new ArrayList<Integer>();

        for (var step = 2; step <= 10; step++) {
            git.moveTip();
            var board = watch.step().await().unwrap().unwrap();

            if (git.attempts.contains(pairKey(1, 2))) {
                attemptedAt.add(step);
            }

            git.attempts.clear();
            assertThat(board.carried()).as("step " + step).extracting(Prediction::key).containsExactly("pair:1+2");
            assertThat(board.skipped()).as("a backing-off pair is failed, not skipped").isZero();
        }

        assertThat(attemptedAt).as("failures at 2, then waits of 1, 2, 4 steps").containsExactly(2, 3, 5, 9);
        assertThat(log.lines).hasSize(1);
    }

    /// PIT gap (#112): without the in-memory announced set every step would replay the events.log tail.
    @Test
    void eventsLogTail_isReadOnceAtStart_notEveryStep() {
        var log = new MemoryLog();
        var watch = watch(new ScriptedGit(), log, 300, prs(3));

        for (var step = 0; step < 3; step++) {
            watch.step().await().unwrap();
        }

        assertThat(log.tails.get()).isEqualTo(1);
    }

    /// PIT gap (#112): the step's own answer is the board it just built, not nothing.
    @Test
    void step_resolvesToTheBoardItBuilt() {
        var git = new ScriptedGit();
        var watch = watch(git, new MemoryLog(), 300, prs(3));

        git.conflicting.add(pairKey(1, 2));
        var answered = watch.step().await().unwrap();

        assertThat(answered.isPresent()).isTrue();
        assertThat(answered.unwrap()).isEqualTo(watch.board());
        assertThat(answered.unwrap().tip().unwrap()).isEqualTo(git.tip.get());
        assertThat(answered.unwrap().predictions()).extracting(Prediction::key).containsExactly("pair:1+2");
    }

    /// One success ends the streak: the next failure waits one step, not the doubled wait the earlier failures earned.
    @Test
    void successResetsTheStreak_theNextFailureWaitsOneStep() {
        var git = new ScriptedGit();
        var watch = watch(git, new MemoryLog(), 300, prs(2));
        var attemptedAt = new ArrayList<Integer>();

        for (var step = 1; step <= 6; step++) {
            git.moveTip();
            git.failing.clear();
            if (step <= 2 || step >= 5) {
                git.failing.add(pairKey(1, 2));
            }

            watch.step().await().unwrap();
            if (git.attempts.contains(pairKey(1, 2))) {
                attemptedAt.add(step);
            }

            git.attempts.clear();
        }

        assertThat(attemptedAt).as("fail 1, fail 2 (wait 2), success at 4, fail 5, retried at 6")
                  .containsExactly(1, 2, 4, 5, 6);
    }

    /// A slot lives only while its pair (by heads) is in the overlap: a closed PR's and a replaced head's slots go, and
    /// the new head's pair starts never-checked, so it is attempted at once.
    @Test
    void closedPrAndReplacedHead_loseTheirSlots_andTheNewHeadStartsFresh() {
        var git = new ScriptedGit();
        var open = new AtomicReference<>(prs(3));
        var watch = ConflictWatch.conflictWatch(git,
                                                () -> Promise.success(open.get()),
                                                BRANCH,
                                                new MemoryLog(),
                                                () -> T0,
                                                Duration.ofMinutes(1),
                                                300);

        git.failing.add(pairKey(1, 2));
        git.failing.add(pairKey(1, 3));
        git.failing.add(pairKey(2, 3));
        git.moveTip();
        watch.step().await().unwrap();
        assertThat(watch.scheduled()).isEqualTo(3);
        open.set(prs(2));
        git.moveTip();
        watch.step().await().unwrap();
        assertThat(watch.scheduled()).as("#3 closed: its two pairs' slots are gone").isEqualTo(1);
        open.set(List.of(pr(1), pr(2, 2002)));
        git.moveTip();
        git.attempts.clear();
        watch.step().await().unwrap();
        assertThat(watch.scheduled()).as("old head's slot replaced, not added to").isEqualTo(1);
        assertThat(Set.copyOf(git.attempts)).as("the new head is never-checked, so attempted at once").hasSize(1);
    }

    /// The wait stops doubling at BACKOFF_CAP: attempts at 1, 2, 4, ..., 64, then every 64 steps.
    @Test
    void backoff_stopsGrowingAtTheCap() {
        var git = new ScriptedGit();
        var watch = watch(git, new MemoryLog(), 300, prs(2));
        var attemptedAt = new ArrayList<Integer>();

        git.failing.add(pairKey(1, 2));
        for (var step = 1; step <= 200; step++) {
            git.moveTip();
            watch.step().await().unwrap();
            if (!git.attempts.isEmpty()) {
                attemptedAt.add(step);
            }

            git.attempts.clear();
        }

        assertThat(attemptedAt).containsExactly(1, 2, 4, 8, 16, 32, 64, 128, 192);
    }

    // ---- fixture -------------------------------------------------------------------------------------------------
    private static ConflictWatch watch(ScriptedGit git, MemoryLog log, int cap, List<PullRequest> prs) {
        return ConflictWatch.conflictWatch(git,
                                           () -> Promise.success(prs),
                                           BRANCH,
                                           log,
                                           () -> T0,
                                           Duration.ofMinutes(1),
                                           cap);
    }

    private static List<PullRequest> prs(int count) {
        return IntStream.rangeClosed(1, count)
                        .mapToObj(n -> pr(n))
                        .toList();
    }

    private static Sha sha(long n) {
        return Sha.sha(String.format("%040x", n)).unwrap();
    }

    private static String pairKey(int lower, int higher) {
        return sha(1000 + lower).value() + ":" + sha(1000 + higher).value();
    }

    private static PullRequest pr(long number) {
        return pr(number, 1000 + number);
    }

    private static PullRequest pr(long number, long head) {
        return new PullRequest(PrNumber.prNumber(number).unwrap(),
                               "pr",
                               PrState.OPEN,
                               false,
                               BRANCH,
                               BranchName.branchName("b" + number).unwrap(),
                               none(),
                               sha(head),
                               none(),
                               none(),
                               T0,
                               List.of(),
                               List.of(),
                               List.of());
    }

    /// Tree of a head is the head itself, and a squash of a tree is that tree, so a merge whose `ours` is not the tip is
    /// a pair attempt and names both heads.
    private static final class ScriptedGit implements ConflictGit {
        final java.util.concurrent.atomic.AtomicReference<Sha> tip = new java.util.concurrent.atomic.AtomicReference<>(sha(9000));

        final AtomicInteger moves = new AtomicInteger();
        final Set<String> failing = new HashSet<>();
        final Set<String> conflicting = new HashSet<>();
        final List<String> attempts = new CopyOnWriteArrayList<>();

        void moveTip() {
            tip.set(sha(9000 + moves.incrementAndGet()));
        }

        public Promise<Sha> tip(BranchName branch) {
            return Promise.success(tip.get());
        }

        public Promise<Boolean> present(Sha head) {
            return Promise.success(true);
        }

        public Promise<Changes> changed(Sha at, Sha head) {
            return Promise.success(Changes.edits(Set.of("shared.txt")));
        }

        public Promise<Merge> merge(Sha ours, Sha theirs) {
            if (ours.equals(tip.get())) {
                return Promise.success(new Merge.Clean(theirs));
            }

            var key = ours.value().compareTo(theirs.value()) < 0
                      ? ours.value() + ":" + theirs.value()
                      : theirs.value() + ":" + ours.value();

            attempts.add(key);

            return failing.contains(key)
                   ? Causes.cause("git merge-tree failed: 128").promise()
                   : Promise.success(conflicting.contains(key)
                                     ? new Merge.Conflicted(List.of("shared.txt"))
                                     : new Merge.Clean(theirs));
        }

        public Promise<Sha> squash(Sha tree, Sha parent) {
            return Promise.success(tree);
        }

        public Promise<ObjectFiles> mirrorObjects() {
            return Promise.success(new ObjectFiles(Map.of("pack/pack-1.pack", 1L)));
        }

        public Promise<Unit> resetScratch() {
            return Promise.unitPromise();
        }
    }

    private static final class MemoryLog implements EventsLog {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final AtomicInteger tails = new AtomicInteger();

        public Promise<List<String>> tail() {
            tails.incrementAndGet();

            return Promise.success(List.copyOf(lines));
        }

        public Promise<Unit> append(List<String> added) {
            lines.addAll(added);

            return Promise.unitPromise();
        }
    }
}
