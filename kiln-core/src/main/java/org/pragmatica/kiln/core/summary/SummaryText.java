package org.pragmatica.kiln.core.summary;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.lang.Option;


/// Renders the `summary` text (spec §7, Q-6), at most [#MAX_LINES] lines but for the two exceptions below: a `gap` line
/// when `since` is older than the oldest kept change, the checkout counts, one line per source not OK, every change
/// with `since < recordedAt ≤ asOf` (oldest recorded first, each shown at its `observedAt`), and last the cursor:
/// `next: since=<asOf>`, or `more: since=<the last listed recordedAt>` when the changes do not fit.
///
/// The cursor is [Change#recordedAt], the store's stamp, never the caller's `observedAt`: a caller that observed a fact
/// long before writing it would otherwise stamp it behind a cursor already handed out (lead ruling, 2026-10-30). `asOf`
/// is the store's committed watermark, never a bare `now`: every change with `recordedAt ≤ asOf` must already be
/// readable, or a change committed later with an earlier stamp falls behind the cursor and is never listed. A change
/// recorded after `asOf` waits for the next call, so it is listed once.
///
/// The caller reads changes with `recordedAt > since`, and one write records many changes at one instant. So a
/// truncated list ends only at an instant boundary, and `asOf` is the last listed instant: nothing after it is lost. When
/// the oldest instant alone holds more changes than fit, the page lists that whole instant past the cap: no change is
/// ever dropped, and the guarantee beats the line cap (lead ruling, 2026-10-30). Every source not OK is listed too, past
/// the cap if need be: completeness beats the cap. So is every expected source with no recorded state at all (WP9 open
/// item 12): a source never read is not OK, and the counts it would feed are not complete without it.
public sealed interface SummaryText {
    int MAX_LINES = 25;
    Comparator<Change> OLDEST_FIRST = Comparator.comparing(Change::recordedAt);

    static Summary render(Instant since,
                          Instant asOf,
                          List<SourceStatus> sources,
                          List<Change> changes,
                          Counts counts,
                          Option<Instant> oldestKept) {
        return render(since, asOf, List.of(), sources, changes, counts, oldestKept);
    }

    /// `expected`: the sources kiln reads (GitHub, the mirror, temper, each configured host, the Mac); one with no status
    /// in `sources` gets its own line.
    static Summary render(Instant since,
                          Instant asOf,
                          List<Source> expected,
                          List<SourceStatus> sources,
                          List<Change> changes,
                          Counts counts,
                          Option<Instant> oldestKept) {
        return paged(fixed(since, expected, sources, counts, oldestKept),
                     changes.stream().filter(change -> within(change, since, asOf)).sorted(OLDEST_FIRST).toList(),
                     asOf);
    }

    private static boolean within(Change change, Instant since, Instant asOf) {
        return change.recordedAt()
                     .isAfter(since) && !change.recordedAt()
                                               .isAfter(asOf);
    }

    /// The lines every page carries, never capped: a source not OK is never dropped.
    private static List<String> fixed(Instant since,
                                      List<Source> expected,
                                      List<SourceStatus> sources,
                                      Counts counts,
                                      Option<Instant> oldestKept) {
        return Stream.of(gap(since, oldestKept).stream(),
                         Stream.of(counts(counts)),
                         sources.stream()
                                .filter(source -> !(source.state() instanceof SourceState.Ok))
                                .map(SummaryText::source),
                         unrecorded(expected, sources))
                     .flatMap(Function.identity())
                     .toList();
    }

    private static Stream<String> unrecorded(List<Source> expected, List<SourceStatus> sources) {
        return expected.stream()
                       .distinct()
                       .filter(source -> sources.stream()
                                                .noneMatch(status -> status.source()
                                                                           .equals(source)))
                       .map(source -> "source " + source.key() + ": no state recorded, last success never");
    }

    /// The lines left for changes; none when the fixed lines alone fill the page.
    private static Summary paged(List<String> fixed, List<Change> changes, Instant asOf) {
        var budget = Math.max(0, MAX_LINES - 1 - fixed.size());

        return changes.size() <= budget
               ? page(fixed, changes, "next: since=" + asOf, asOf)
               : truncated(fixed, changes, budget);
    }

    private static Summary truncated(List<String> fixed, List<Change> changes, int budget) {
        var cut = boundary(changes, budget);

        return cut > 0
               ? page(fixed,
                      changes.subList(0, cut),
                      "more: since=" + changes.get(cut - 1).recordedAt(),
                      changes.get(cut - 1).recordedAt())
               : oversized(fixed, changes);
    }

    /// The oldest instant alone does not fit: it is listed whole, past the cap, since a cut inside it would lose the rest.
    private static Summary oversized(List<String> fixed, List<Change> changes) {
        var at = changes.getFirst().recordedAt();

        return page(fixed,
                    changes.stream().takeWhile(change -> change.recordedAt()
                                                               .equals(at)).toList(),
                    "more: since=" + at,
                    at);
    }

    /// The largest count ≤ `budget` of leading changes that ends where the instant changes; 0 when none does.
    private static int boundary(List<Change> changes, int budget) {
        return IntStream.iterate(budget, count -> count > 0, count -> count - 1)
                        .filter(count -> endsAnInstant(changes, count))
                        .findFirst()
                        .orElse(0);
    }

    private static boolean endsAnInstant(List<Change> changes, int count) {
        return ! changes.get(count - 1)
                        .recordedAt()
                        .equals(changes.get(count).recordedAt());
    }

    private static Summary page(List<String> fixed, List<Change> listed, String cursor, Instant asOf) {
        return new Summary(Stream.of(fixed.stream(),
                                     listed.stream().map(SummaryText::change),
                                     Stream.of(cursor))
                                 .flatMap(Function.identity())
                                 .toList(),
                           asOf);
    }

    private static Option<String> gap(Instant since, Option<Instant> oldestKept) {
        return oldestKept.filter(since::isBefore)
                         .map(oldest -> "gap: since=" + since
                                       + " is older than the oldest kept change (" + oldest
                                       + "); earlier changes may be pruned");
    }

    private static String source(SourceStatus status) {
        return "source " + status.source()
                                 .key()
             + ": " + state(status.state())
             + ", last success " + lastSuccess(status);
    }

    private static String lastSuccess(SourceStatus status) {
        return status.lastSuccess()
                     .map(Instant::toString)
                     .or("never");
    }

    private static String state(SourceState state) {
        return switch (state) {
            case SourceState.Ok ok -> "OK at " + ok.at();
            case SourceState.Unreachable unreachable -> "UNREACHABLE(" + unreachable.reason() + ") since " + unreachable.since();
            case SourceState.Incomplete incomplete -> "INCOMPLETE(" + incomplete.reason() + ") at " + incomplete.at();
            case SourceState.RateLimited limited -> "RATE-LIMITED until " + limited.reset() + ", since " + limited.since();
            case SourceState.GithubDown down -> "GITHUB-DOWN(" + down.status() + ") since " + down.since();
            case SourceState.TemperDown down -> "TEMPER-DOWN(" + down.reason() + ") since " + down.since();
            case SourceState.TemperSchema schema -> "TEMPER-SCHEMA(" + schema.field() + ") since " + schema.since();
        };
    }

    private static String counts(Counts counts) {
        return "checkouts: NEEDS-SALVAGE " + counts.needsSalvage() + ", STALE-UNOWNED " + counts.staleUnowned();
    }

    private static String change(Change change) {
        return change.observedAt()
             + " " + change.pr()
             + " " + subject(change)
             + ": " + change.before()
             + " -> " + change.after();
    }

    private static String subject(Change change) {
        return switch (change.kind()) {
            case CHECK_CELL -> "check " + RequiredCheck.label(change.subject());
            case PR_STATE -> "state";
            case HEAD_MOVED -> "head on " + change.subject();
        };
    }

    record unused() implements SummaryText {}
}
