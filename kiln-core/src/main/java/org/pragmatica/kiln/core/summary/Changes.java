package org.pragmatica.kiln.core.summary;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.feed.FeedChange;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// The change diff between two snapshots (spec §7): a PR whose state differs, a cell whose value differs, and an open
/// PR whose head SHA differs (`HEAD_MOVED`, CI interface T1: before/after the old and new SHA, subject the base the
/// PR targets now), each observed at `observedAt`. A change needs two observations, so a PR or a cell absent from
/// `before` (a first sight) and one absent from `after` give none. Sorted by PR, kind and subject. The one first sight
/// that records a change is a merge first seen after the backfill ([#firstMerged]).
public sealed interface Changes {
    Comparator<Change> ORDER = Comparator.comparingLong((Change change) -> change.pr()
                                                                                 .value())
                                         .thenComparing(Change::kind)
                                         .thenComparing(Change::subject);

    static List<Change> between(PrSnapshot before, PrSnapshot after, Instant observedAt) {
        return Stream.of(states(before, after, observedAt),
                         cells(before, after, observedAt),
                         heads(before, after, observedAt))
                     .flatMap(changes -> changes)
                     .sorted(ORDER)
                     .toList();
    }

    private static Stream<Change> states(PrSnapshot before, PrSnapshot after, Instant at) {
        return after.states()
                    .keySet()
                    .stream()
                    .flatMap(pr -> state(pr,
                                         before.states(),
                                         after.states(),
                                         at));
    }

    private static Stream<Change> state(PrNumber pr,
                                        Map<PrNumber, PrState> was,
                                        Map<PrNumber, PrState> now,
                                        Instant at) {
        return changed(was, now, pr).map(old -> new Change(at,
                                                           pr,
                                                           ChangeKind.PR_STATE,
                                                           pr.toString(),
                                                           name(old),
                                                           name(now.get(pr)),
                                                           at))
                      .stream();
    }

    private static Stream<Change> cells(PrSnapshot before, PrSnapshot after, Instant at) {
        return after.cells()
                    .keySet()
                    .stream()
                    .flatMap(pr -> prCells(pr,
                                           cellsOf(before, pr),
                                           after.cells().get(pr),
                                           at));
    }

    private static Map<String, String> cellsOf(PrSnapshot snapshot, PrNumber pr) {
        return snapshot.cells()
                       .getOrDefault(pr,
                                     Map.of());
    }

    private static Stream<Change> prCells(PrNumber pr, Map<String, String> was, Map<String, String> now, Instant at) {
        return now.keySet()
                  .stream()
                  .flatMap(context -> cell(pr, context, was, now, at));
    }

    private static Stream<Change> cell(PrNumber pr,
                                       String context,
                                       Map<String, String> was,
                                       Map<String, String> now,
                                       Instant at) {
        return changed(was, now, context).map(old -> new Change(at,
                                                                pr,
                                                                ChangeKind.CHECK_CELL,
                                                                context,
                                                                old,
                                                                now.get(context),
                                                                at))
                      .stream();
    }

    private static Stream<Change> heads(PrSnapshot before, PrSnapshot after, Instant at) {
        return after.heads()
                    .keySet()
                    .stream()
                    .filter(pr -> after.states()
                                       .get(pr) == PrState.OPEN)
                    .flatMap(pr -> head(pr,
                                        before.heads(),
                                        after.heads().get(pr),
                                        at));
    }

    /// Only the SHA decides: a retargeted PR with the same head moved nothing.
    private static Stream<Change> head(PrNumber pr,
                                       Map<PrNumber, PrSnapshot.Head> was,
                                       PrSnapshot.Head now,
                                       Instant at) {
        return option(was.get(pr)).map(PrSnapshot.Head::sha)
                     .filter(old -> !old.equals(now.sha()))
                     .map(old -> new Change(at,
                                            pr,
                                            ChangeKind.HEAD_MOVED,
                                            now.base().value(),
                                            old.value(),
                                            now.sha().value(),
                                            at))
                     .stream();
    }

    /// The first-sighting merge: a listed PR absent from `stored`, listed `merged`, read once
    /// the backfill finished (`backfillDone` present) and merged at or after it, gives `PR_STATE` before
    /// [FeedChange#NONE], after `merged`; so a PR created and merged inside one cycle is still seen merged. Before
    /// `backfillDone` (the backfill itself) none, so a rebuilt kiln does not record its whole history. The last copy of
    /// a PR listed twice decides, as the upsert's last write does. Sorted as [#between].
    static List<Change> firstMerged(List<PullRequest> listed,
                                    Set<PrNumber> stored,
                                    Option<Instant> backfillDone,
                                    Instant observedAt) {
        return backfillDone.map(done -> firstMergedAfter(listed, stored, done, observedAt))
                           .or(List.of());
    }

    private static List<Change> firstMergedAfter(List<PullRequest> listed,
                                                 Set<PrNumber> stored,
                                                 Instant backfillDone,
                                                 Instant observedAt) {
        return lastCopies(listed).stream()
                         .filter(pr -> firstSeenMerged(pr, stored, backfillDone))
                         .map(pr -> firstMerge(pr, observedAt))
                         .sorted(ORDER)
                         .toList();
    }

    private static boolean firstSeenMerged(PullRequest pr, Set<PrNumber> stored, Instant backfillDone) {
        return ! stored.contains(pr.number()) && mergedSince(pr, backfillDone);
    }

    /// The facts a `PR_STATE` row carries for temper, from the listed PR, in this order: `head`
    /// (a merged PR's last head), `base` and `headRef` (the ref names), and `mergedAt` (ISO-8601 UTC, seconds; left
    /// out when the PR is not merged).
    static Map<String, Object> prStatePayload(PullRequest pr) {
        return prStatePayload(pr, none());
    }

    /// As [#prStatePayload(PullRequest)], then `mergedBy`: the `merged_by.login` the poller read for a PR it saw
    /// merge, left out when none was read.
    static Map<String, Object> prStatePayload(PullRequest pr, Option<String> mergedBy) {
        return FeedChange.payload("head",
                                  pr.head().value(),
                                  "base",
                                  pr.base().value(),
                                  "headRef",
                                  pr.headRef().value(),
                                  "mergedAt",
                                  pr.mergedAt().map(Changes::iso),
                                  "mergedBy",
                                  mergedBy);
    }

    /// One PR per number, its last listing.
    static Collection<PullRequest> lastCopies(List<PullRequest> listed) {
        return listed.stream()
                     .collect(Collectors.toMap(PullRequest::number,
                                               Function.identity(),
                                               (_, last) -> last,
                                               LinkedHashMap::new))
                     .values();
    }

    /// `merged_at` is GitHub's, in whole seconds, so `backfillDone` is compared truncated to the second
    /// (lead ruling 2026-11-04): a merge in the backfill's own second is seen. Residual: a kiln clock ahead of
    /// GitHub's still misses a merge inside the skew, at most one window per backfill, and never records one twice.
    private static boolean mergedSince(PullRequest pr, Instant backfillDone) {
        var since = backfillDone.truncatedTo(ChronoUnit.SECONDS);

        return pr.state() == PrState.MERGED && pr.mergedAt()
                                                 .filter(at -> !at.isBefore(since))
                                                 .isPresent();
    }

    private static Change firstMerge(PullRequest pr, Instant at) {
        return new Change(at,
                          pr.number(),
                          ChangeKind.PR_STATE,
                          pr.number().toString(),
                          FeedChange.NONE,
                          name(PrState.MERGED),
                          at);
    }

    private static String iso(Instant at) {
        return at.truncatedTo(ChronoUnit.SECONDS)
                 .toString();
    }

    /// The earlier value of `key` when both snapshots hold one and the two differ.
    private static <K, V> Option<V> changed(Map<K, V> was, Map<K, V> now, K key) {
        return option(was.get(key)).filter(old -> !old.equals(now.get(key)));
    }

    /// As the `pr.state` column holds it: `open`, `closed`, `merged`.
    private static String name(PrState state) {
        return state.name()
                    .toLowerCase(Locale.ROOT);
    }

    record unused() implements Changes {}
}
