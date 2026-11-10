package org.pragmatica.kiln.service.conflict;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.conflict.Announced;
import org.pragmatica.kiln.core.conflict.Changes;
import org.pragmatica.kiln.core.conflict.ConflictEvent;
import org.pragmatica.kiln.core.conflict.Overlap;
import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.conflict.ConflictBoard.Cost;
import org.pragmatica.kiln.service.conflict.ConflictGit.Merge;
import org.pragmatica.kiln.service.conflict.ConflictGit.ObjectFiles;
import org.pragmatica.kiln.service.flow.SingleFlight;
import org.pragmatica.kiln.service.flow.Steps;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The merge-tree conflict early warning (temper #100), a kiln source that reads kiln's own bare mirror and PR table
/// (`prs`, the stored PRs, open heads included, as the GitHub poller keeps them):
/// no GitHub call, no write to GitHub, no write to the mirror ([GitConflicts]).
///
/// It steps after every mirror fetch that succeeded (the mirror step's `fetched` hook, so every pushed head is seen
/// within one fetch, at most [org.pragmatica.kiln.service.mirror.MirrorStep#PERIOD]). A step:
/// 1. reads the release tip (`[conflicts] branch`); when it moved, every cached answer is dropped, so every open PR is
///    checked against the new tip;
/// 2. for each open PR on that branch whose head is in the mirror: the changed files (`diff tip...head`) and
///    `merge-tree tip head`, each cached by (tip, head), so only a changed head costs a check;
/// 3. for each pair of PRs that are both clean against the tip and whose changes meet ([Overlap#pairs]), in BOTH orders
///    (PR #108 review F4: squash order is not symmetric): squash-merge one onto the tip (`commit-tree` of its merged
///    tree, parent the tip), then `merge-tree` the other onto that commit: what the SECOND merge hits, cached by (tip,
///    both heads). A conflict in either order is a prediction naming the order(s) that conflict;
/// 4. diffs the predictions against those events.log has announced ([Announced#step]) and appends one line per new or
///    cleared prediction.
///
/// Memory only (no migration, maintainer ruling while #105 holds V7): every answer is recomputable from git. The announced set
/// is seeded from the events.log tail before the first step, so a restart appends only what changed while kiln was down.
/// A failed git call never reads as clean: the PR or pair is undetermined, and its earlier announcement is carried.
public final class ConflictWatch {
    private static final Logger log = LoggerFactory.getLogger(ConflictWatch.class);
    /// A step still running this long fails, and frees the next.
    public static final Duration DEADLINE = Duration.ofMinutes(10);
    /// The most pair checks one step runs that are not cached; the rest are skipped, shown as skipped, carried, and run
    /// by the next steps (a cached answer costs nothing, so each step admits the next ones). Measured on bigboy (PR #108,
    /// 40 clean PR heads of pragmatica, load ~65 on 16 cores): 45 ms per order, two orders per pair, so all 780 pairs of 40
    /// PRs took 73 s; 300 pairs are ~27 s plus the tip checks (59 ms each).
    public static final int PAIR_CHECKS = 300;
    /// The longest a failing pair waits, in steps, before its next attempt (the wait doubles per failure: 1, 2, 4, ...).
    public static final int BACKOFF_CAP = 64;

    private final ConflictGit git;
    private final Supplier<Promise<List<PullRequest>>> prs;
    private final BranchName branch;
    private final EventsLog events;
    private final Supplier<Instant> clock;
    private final Duration deadline;
    private final int pairCap;
    private final SingleFlight flight = SingleFlight.singleFlight();
    private final AtomicReference<Option<Cache>> cache = new AtomicReference<>(none());
    /// Per pair (by head pair): the step it was last attempted at, and its failure streak. It outlives the cache: a tip
    /// move drops the answers, not the order in which pairs are owed a check.
    private final Map<String, Slot> schedule = new ConcurrentHashMap<>();
    private final AtomicLong steps = new AtomicLong();
    private final AtomicReference<Option<Announced>> announced = new AtomicReference<>(none());
    private final AtomicReference<Option<Long>> fetchMillis = new AtomicReference<>(none());
    private final AtomicReference<Option<ObjectFiles>> mirrorObjects = new AtomicReference<>(none());
    private final AtomicReference<ConflictBoard> board;

    private ConflictWatch(ConflictGit git,
                          Supplier<Promise<List<PullRequest>>> prs,
                          BranchName branch,
                          EventsLog events,
                          Supplier<Instant> clock,
                          Duration deadline,
                          int pairCap) {
        this.git = git;
        this.prs = prs;
        this.branch = branch;
        this.events = events;
        this.clock = clock;
        this.deadline = deadline;
        this.pairCap = pairCap;
        this.board = new AtomicReference<>(ConflictBoard.empty(branch));
    }

    public static ConflictWatch conflictWatch(ConflictGit git,
                                              Supplier<Promise<List<PullRequest>>> prs,
                                              BranchName branch,
                                              EventsLog events,
                                              Supplier<Instant> clock,
                                              Duration deadline) {
        return conflictWatch(git, prs, branch, events, clock, deadline, PAIR_CHECKS);
    }

    /// With `pairCap` uncached pair checks per step instead of [#PAIR_CHECKS].
    public static ConflictWatch conflictWatch(ConflictGit git,
                                              Supplier<Promise<List<PullRequest>>> prs,
                                              BranchName branch,
                                              EventsLog events,
                                              Supplier<Instant> clock,
                                              Duration deadline,
                                              int pairCap) {
        return new ConflictWatch(git, prs, branch, events, clock, deadline, pairCap);
    }

    /// The mirror step's hook: `startedAt` is when the fetch that just succeeded began, so the time since is its cost.
    /// Starts a step and answers it without waiting; a step while one runs is skipped (the next fetch starts another).
    public Promise<Option<ConflictBoard>> fetched(Instant startedAt) {
        fetchMillis.set(some(Duration.between(startedAt, clock.get()).toMillis()));

        return step();
    }

    /// One step: the board after it, or none when a step was still running.
    public Promise<Option<ConflictBoard>> step() {
        return flight.run(this::stepNow, deadline);
    }

    /// The board of the last step (empty before the first).
    public ConflictBoard board() {
        return board.get();
    }

    private Promise<ConflictBoard> stepNow() {
        var started = clock.get();
        var fetch = fetchMillis.getAndSet(none());

        steps.incrementAndGet();

        return Promise.all(seeded(),
                           git.tip(branch))
                      .flatMap((known, tip) -> cacheAt(tip).flatMap(at -> checked(at, known, fetch, started)))
                      .fold(result -> Promise.success(result.fold(this::failed, ok -> ok)));
    }

    private ConflictBoard failed(Cause cause) {
        log.warn("conflict step failed: {}", cause.message());

        return board.updateAndGet(last -> new ConflictBoard(last.branch(),
                                                            last.tip(),
                                                            last.checkedAt(),
                                                            last.open(),
                                                            last.unknown(),
                                                            last.skipped(),
                                                            last.predictions(),
                                                            last.carried(),
                                                            last.costs(),
                                                            some(cause.message())));
    }

    /// The announced set: events.log's tail before the first step, then what each step left. An unreadable log fails
    /// the step rather than start from nothing, which would announce every prediction again.
    private Promise<Announced> seeded() {
        return announced.get()
                        .map(Promise::success)
                        .or(() -> events.tail()
                                        .map(Announced::replayed));
    }

    /// The cache for `tip`: the current one, or a fresh one (and an emptied scratch) when the tip moved.
    private Promise<Cache> cacheAt(Sha tip) {
        return cache.get()
                    .filter(current -> current.tip()
                                              .equals(tip))
                    .map(Promise::success)
                    .or(() -> git.resetScratch()
                                 .map(_ -> fresh(tip)));
    }

    private Cache fresh(Sha tip) {
        var created = Cache.cache(tip);

        cache.set(some(created));

        return created;
    }

    private Promise<ConflictBoard> checked(Cache at, Announced known, Option<Long> fetch, Instant started) {
        return prs.get()
                  .map(this::openOnBranch)
                  .flatMap(open -> Steps.sequence(open,
                                                  pr -> evaluated(at, pr)))
                  .flatMap(checks -> Steps.sequence(admitted(at,
                                                             pairsOf(checks)),
                                                    pair -> paired(at, pair)).flatMap(pairs -> grown().flatMap(bytes -> concluded(at,
                                                                                                                                  known,
                                                                                                                                  checks,
                                                                                                                                  pairs,
                                                                                                                                  new Spent(fetch,
                                                                                                                                            bytes,
                                                                                                                                            started)))));
    }

    private List<PullRequest> openOnBranch(List<PullRequest> all) {
        return all.stream()
                  .filter(pr -> pr.state() == PrState.OPEN && pr.base()
                                                                .equals(branch))
                  .toList();
    }

    // ---- tip checks ----------------------------------------------------------------------------------------------
    private Promise<PrCheck> evaluated(Cache at, PullRequest pr) {
        var head = pr.head();
        var ran = !at.merges().containsKey(head);

        return git.present(head)
                  .flatMap(present -> present
                                      ? Promise.all(changedOf(at, head),
                                                    mergedOf(at, head)).map((changed, merge) -> new PrCheck(pr,
                                                                                                            some(changed),
                                                                                                            some(merge),
                                                                                                            ran))
                                      : Promise.success(PrCheck.unknown(pr)))
                  .fold(result -> Promise.success(result.fold(_ -> PrCheck.unknown(pr),
                                                              check -> check)));
    }

    private Promise<Changes> changedOf(Cache at, Sha head) {
        return Option.option(at.changed().get(head))
                     .map(Promise::success)
                     .or(() -> git.changed(at.tip(),
                                           head)
                                  .map(changed -> remembered(at.changed(),
                                                             head,
                                                             changed)));
    }

    private Promise<Merge> mergedOf(Cache at, Sha head) {
        return Option.option(at.merges().get(head))
                     .map(Promise::success)
                     .or(() -> git.merge(at.tip(),
                                         head)
                                  .map(merge -> remembered(at.merges(),
                                                           head,
                                                           merge)));
    }

    // ---- pair checks ---------------------------------------------------------------------------------------------
    /// The overlapping pairs among the PRs that merge cleanly onto the tip; a PR that conflicts with the tip must be
    /// synced first, and its pairs are undetermined until then.
    private static List<PairOf> pairsOf(List<PrCheck> checks) {
        var clean = checks.stream()
                          .filter(PrCheck::clean)
                          .collect(Collectors.toMap(check -> check.pr()
                                                                  .number(),
                                                    check -> check));
        var changed = clean.values()
                           .stream()
                           .collect(Collectors.toMap(check -> check.pr()
                                                                   .number(),
                                                     check -> check.changed()
                                                                   .or(Changes.edits(Set.of()))));

        return Overlap.pairs(changed)
                      .stream()
                      .map(pair -> new PairOf(pair,
                                              clean.get(pair.first()),
                                              clean.get(pair.second()),
                                              false,
                                              false))
                      .toList();
    }

    /// Each cached pair is admitted (it costs nothing). Of the uncached ones, those not backing off after a failure are
    /// ordered oldest-unchecked first (never checked, then by the step they were last attempted at, then by number) and
    /// the first `pairCap` admitted, so under a tip that moves every step the pairs a step skipped are the first ones
    /// the next step takes. The rest are marked skipped; a pair still backing off is marked `waiting` and does not take
    /// budget. Pairs gone from the overlap forget their schedule.
    private List<PairOf> admitted(Cache at, List<PairOf> pairs) {
        var now = steps.get();

        schedule.keySet().retainAll(pairs.stream().map(PairOf::key).collect(Collectors.toSet()));
        var chosen = pairs.stream()
                          .filter(pair -> !at.pairs()
                                             .containsKey(pair.key()) && slotOf(pair).due(now))
                          .sorted(Comparator.comparingLong((PairOf pair) -> slotOf(pair).checkedAt())
                                            .thenComparingLong(pair -> pair.pair()
                                                                           .first()
                                                                           .value())
                                            .thenComparingLong(pair -> pair.pair()
                                                                           .second()
                                                                           .value()))
                          .limit(pairCap)
                          .map(PairOf::key)
                          .collect(Collectors.toSet());

        return pairs.stream()
                    .map(pair -> at.pairs()
                                   .containsKey(pair.key()) || chosen.contains(pair.key())
                                 ? pair
                                 : slotOf(pair).due(now)
                                   ? pair.skip()
                                   : pair.backOff())
                    .toList();
    }

    /// How many pairs hold a schedule slot (a seam for the pruning test).
    int scheduled() {
        return schedule.size();
    }

    private Slot slotOf(PairOf pair) {
        return schedule.getOrDefault(pair.key(), Slot.NEVER);
    }

    private void attempted(String key, boolean failed) {
        var now = steps.get();

        schedule.compute(key,
                         (_, slot) -> (slot == null
                                       ? Slot.NEVER
                                       : slot).after(now, failed));
    }

    private Promise<PairCheck> paired(Cache at, PairOf pair) {
        var key = pair.key();
        var ran = !at.pairs().containsKey(key);

        if (pair.skipped()) {
            return Promise.success(new PairCheck(pair.pair(), none(), false, true));
        }

        if (pair.waiting()) {
            return Promise.success(new PairCheck(pair.pair(), none(), false, false));
        }

        return Option.option(at.pairs().get(key))
                     .map(Promise::success)
                     .or(() -> bothOrders(at, pair).map(merges -> remembered(at.pairs(),
                                                                             key,
                                                                             merges)))
                     .fold(result -> {
                               if (ran) {
                               attempted(key,
                                         result.isFailure());
                           }

                               return Promise.success(result.fold(_ -> new PairCheck(pair.pair(),
                                                                                     none(),
                                                                                     ran,
                                                                                     false),
                                                                  merges -> new PairCheck(pair.pair(),
                                                                                          some(merges),
                                                                                          ran,
                                                                                          false)));
                           });
    }

    /// Both landing orders, lower number first, then the reverse. Either failing leaves the pair undetermined.
    private Promise<Orders> bothOrders(Cache at, PairOf pair) {
        return squashedThenMerged(at, pair.first(), pair.second()).flatMap(forward -> squashedThenMerged(at,
                                                                                                         pair.second(),
                                                                                                         pair.first()).map(reverse -> new Orders(forward,
                                                                                                                                                 reverse)));
    }

    /// `first` squash-merged onto the tip, then `second` merged onto that: the second merge's conflicts. A squash commit
    /// has the tip as its only parent, so the merge base for `second` is its base with the tip, as on the branch after
    /// `first` lands.
    private Promise<Merge> squashedThenMerged(Cache at, PrCheck first, PrCheck second) {
        return first.tree()
                    .map(tree -> git.squash(tree,
                                            at.tip())
                                    .flatMap(squash -> git.merge(squash,
                                                                 second.pr().head())))
                    .or(() -> Causes.cause("no clean tree for #" + first.pr().number().value()).promise());
    }

    // ---- conclusion ----------------------------------------------------------------------------------------------
    private Promise<ConflictBoard> concluded(Cache at,
                                             Announced known,
                                             List<PrCheck> checks,
                                             List<PairCheck> pairs,
                                             Spent spent) {
        var now = clock.get();
        var current = Stream.concat(checks.stream()
                                          .flatMap(check -> check.conflicted()
                                                                 .map(paths -> Prediction.tip(check.pr().number(),
                                                                                              paths,
                                                                                              at.tip()))
                                                                 .stream()),
                                    pairs.stream()
                                         .flatMap(pair -> pair.merges()
                                                              .flatMap(orders -> orders.prediction(pair.pair(),
                                                                                                   at.tip()))
                                                              .stream()))
                            .toList();
        var undetermined = undetermined(known, checks, pairs);
        var step = known.step(current, undetermined::contains, at.tip(), now);
        var currentKeys = current.stream().map(Prediction::key).collect(Collectors.toSet());
        var carried = step.announced()
                          .byKey()
                          .values()
                          .stream()
                          .filter(prediction -> undetermined.contains(prediction.key()) && !currentKeys.contains(prediction.key()))
                          .toList();
        var skipped = (int) pairs.stream().filter(PairCheck::skipped).count();
        var cost = spent.cost(now,
                              (int) checks.stream().filter(PrCheck::ran).count(),
                              (int) pairs.stream().filter(PairCheck::ran).count());

        return events.append(step.events().stream().map(ConflictEvent::line).toList())
                     .map(_ -> announcedNow(step))
                     .map(_ -> boardAfter(at,
                                          now,
                                          checks,
                                          skipped,
                                          current,
                                          carried,
                                          cost,
                                          none()))
                     .fold(result -> Promise.success(result.fold(cause -> boardAfter(at,
                                                                                     now,
                                                                                     checks,
                                                                                     skipped,
                                                                                     current,
                                                                                     carried,
                                                                                     cost,
                                                                                     some("events.log append failed: " + cause.message())),
                                                                 ok -> ok)));
    }

    private Announced announcedNow(Announced.Step step) {
        step.events().forEach(event -> log.info("conflict {}", event.line()));
        announced.set(some(step.announced()));

        return step.announced();
    }

    /// The announced keys this step cannot decide: a PR it could not check, and a pair with such a PR, with a PR that
    /// conflicts with the tip, or whose own check failed. A prediction naming a PR that is no longer open is decided
    /// (merged or closed: nothing left to warn about), and everything else that is not predicted now is cleared.
    private static Set<String> undetermined(Announced known, List<PrCheck> checks, List<PairCheck> pairs) {
        var unknown = checks.stream()
                            .filter(check -> check.merge()
                                                  .isEmpty())
                            .map(check -> check.pr()
                                               .number())
                            .collect(Collectors.toSet());
        var blocked = checks.stream()
                            .filter(check -> check.conflicted()
                                                  .isPresent())
                            .map(check -> check.pr()
                                               .number())
                            .collect(Collectors.toSet());
        var failedPairs = pairs.stream()
                               .filter(pair -> pair.merges()
                                                   .isEmpty())
                               .map(pair -> pair.pair()
                                                .key())
                               .collect(Collectors.toSet());
        var open = checks.stream().map(check -> check.pr()
                                                     .number()).collect(Collectors.toSet());

        return known.byKey()
                    .values()
                    .stream()
                    .filter(prediction -> open.containsAll(prediction.prs()))
                    .filter(prediction -> undecided(prediction, unknown, blocked, failedPairs))
                    .map(Prediction::key)
                    .collect(Collectors.toSet());
    }

    private static boolean undecided(Prediction prediction,
                                     Set<PrNumber> unknown,
                                     Set<PrNumber> blocked,
                                     Set<String> failedPairs) {
        return switch (prediction.kind()) {
            case TIP -> unknown.contains(prediction.prs().getFirst());
            case PAIR -> failedPairs.contains(prediction.key()) || prediction.prs().stream().anyMatch(pr -> unknown.contains(pr) || blocked.contains(pr));
        };
    }

    private ConflictBoard boardAfter(Cache at,
                                     Instant now,
                                     List<PrCheck> checks,
                                     int skipped,
                                     List<Prediction> current,
                                     List<Prediction> carried,
                                     Option<Cost> cost,
                                     Option<String> failure) {
        cost.onPresent(spent -> log.info("{}", spent.line()));

        return board.updateAndGet(last -> new ConflictBoard(branch,
                                                            some(at.tip()),
                                                            some(now),
                                                            checks.size(),
                                                            (int) checks.stream()
                                                                        .filter(check -> check.merge()
                                                                                              .isEmpty())
                                                                        .count(),
                                                            skipped,
                                                            current,
                                                            carried,
                                                            Stream.concat(cost.stream(),
                                                                          last.costs().stream())
                                                                  .limit(ConflictBoard.COSTS)
                                                                  .toList(),
                                                            failure));
    }

    /// The mirror's growth since the previous step: the lengths of the object files the fetches in between added. None
    /// on the first step, when the files cannot be read, or when a file went away (a `gc` rewrote the store).
    private Promise<Option<Long>> grown() {
        return git.mirrorObjects()
                  .fold(result -> Promise.success(result.fold(_ -> Option.<Long> none(),
                                                              this::growth)));
    }

    private Option<Long> growth(ObjectFiles now) {
        return mirrorObjects.getAndSet(some(now))
                            .flatMap(now::grownSince);
    }

    private static <K, V> V remembered(Map<K, V> map, K key, V value) {
        map.put(key, value);

        return value;
    }

    // ---- records -------------------------------------------------------------------------------------------------
    /// The answers cached for one tip: changes and tip merges by head, both orders of a pair by `headA:headB`.
    record Cache(Sha tip, Map<Sha, Changes> changed, Map<Sha, Merge> merges, Map<String, Orders> pairs) {
        static Cache cache(Sha tip) {
            return new Cache(tip, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    /// One open PR's tip check: none when its head is not in the mirror or a git call failed. `ran` when it was not cached.
    record PrCheck(PullRequest pr, Option<Changes> changed, Option<Merge> merge, boolean ran) {
        static PrCheck unknown(PullRequest pr) {
            return new PrCheck(pr, none(), none(), false);
        }

        boolean clean() {
            return merge.filter(Merge.Clean.class::isInstance)
                        .isPresent() && changed.isPresent();
        }

        Option<Sha> tree() {
            return merge.flatMap(merged -> merged instanceof Merge.Clean(Sha tree)
                                           ? some(tree)
                                           : none());
        }

        Option<List<String>> conflicted() {
            return merge.flatMap(merged -> merged instanceof Merge.Conflicted(List<String> paths)
                                           ? some(paths)
                                           : none());
        }
    }

    /// A pair to check; `skipped` when the step's [#PAIR_CHECKS] were spent before it, `waiting` while it backs off.
    record PairOf(Overlap.Pair pair, PrCheck first, PrCheck second, boolean skipped, boolean waiting) {
        /// The cache key: both heads, lower number first.
        String key() {
            return first.pr()
                        .head()
                        .value() + ":" + second.pr()
                                               .head()
                                               .value();
        }

        PairOf skip() {
            return new PairOf(pair, first, second, true, false);
        }

        /// Not attempted: its last check failed and its backoff has not run out. Shown as failed, not as skipped.
        PairOf backOff() {
            return new PairOf(pair, first, second, false, true);
        }
    }

    /// A pair's two merges: `forward` with the lower number landing first, `reverse` with the higher.
    record Orders(Merge forward, Merge reverse) {
        /// The prediction when either order conflicts: the paths of both, and which PR's landing first conflicts.
        Option<Prediction> prediction(Overlap.Pair pair, Sha tip) {
            var paths = Stream.concat(paths(forward), paths(reverse)).toList();
            var firsts = Stream.concat(paths(forward).findAny().map(_ -> pair.first()).stream(),
                                       paths(reverse).findAny().map(_ -> pair.second()).stream())
                               .toList();

            return firsts.isEmpty()
                   ? none()
                   : some(Prediction.pair(pair.first(), pair.second(), paths, tip, firsts));
        }

        private static Stream<String> paths(Merge merge) {
            return merge instanceof Merge.Conflicted(List<String> paths)
                   ? paths.stream()
                   : Stream.empty();
        }
    }

    /// One pair's check: none when a git call failed or it was `skipped` ([#PAIR_CHECKS]). `ran` when it was not cached.
    record PairCheck(Overlap.Pair pair, Option<Orders> merges, boolean ran, boolean skipped) {}

    /// A pair's schedule: the step it was last attempted at (0 never), the failures in a row since, and the first step it
    /// may be attempted at again. Failure n waits 2^(n-1) steps, up to [#BACKOFF_CAP]: attempts at steps 1, 2, 4, 8, ...
    record Slot(long checkedAt, int failures, long retryAt) {
        static final Slot NEVER = new Slot(0, 0, 0);

        boolean due(long step) {
            return retryAt <= step;
        }

        Slot after(long step, boolean failed) {
            if (!failed) {
                return new Slot(step, 0, 0);
            }

            var streak = failures + 1;
            var wait = (int) Math.min(BACKOFF_CAP, 1L<< Math.min(streak - 1, 30));

            return new Slot(step, streak, step + wait);
        }
    }

    /// What the fetch cost, and when the step began.
    record Spent(Option<Long> fetchMillis, Option<Long> fetchedBytes, Instant started) {
        /// A cost only when the step ran a check: a step that found every answer cached cost nothing worth a row.
        Option<Cost> cost(Instant now, int tipChecks, int pairChecks) {
            return tipChecks + pairChecks == 0
                   ? none()
                   : some(new Cost(now,
                                   fetchMillis,
                                   fetchedBytes,
                                   Duration.between(started, now).toMillis(),
                                   tipChecks,
                                   pairChecks));
        }
    }
}
