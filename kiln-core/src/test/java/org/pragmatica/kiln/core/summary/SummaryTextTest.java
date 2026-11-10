package org.pragmatica.kiln.core.summary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.kiln.core.model.CheckContext;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §7, Q-6: at most 25 lines; every change with `since < recordedAt ≤ asOf` is listed across successive calls, each
/// passing the last `asOf` back as `since`, and shown at its `observedAt`; a `gap` line first when `since` is older than
/// the oldest kept change. `asOf` is the store's committed watermark.
class SummaryTextTest {
    private static final Instant SINCE = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-30T01:00:00Z");
    private static final Counts COUNTS = new Counts(2, 0);
    private static final String COUNTS_LINE = "checkouts: NEEDS-SALVAGE 2, STALE-UNOWNED 0";

    private static Change change(long pr, Instant at) {
        return recorded(pr, at, at);
    }

    private static Change recorded(long pr, Instant observedAt, Instant recordedAt) {
        return new Change(observedAt, ok(PrNumber.prNumber(pr)), ChangeKind.CHECK_CELL, "build", "pending", "success", recordedAt);
    }

    private static Summary render(Instant since, List<Change> changes) {
        return SummaryText.render(since, AS_OF, List.of(), changes, COUNTS, none());
    }

    @Test
    void render_listsTheCountsAndTheCursor_whenNothingChanged() {
        var summary = render(SINCE, List.of());

        assertThat(summary.lines()).containsExactly(COUNTS_LINE, "next: since=" + AS_OF);
        assertThat(summary.asOf()).isEqualTo(AS_OF);
    }

    @Test
    void render_listsTheChangesAfterSince_oldestFirst() {
        var later = change(2, SINCE.plusSeconds(120));
        var earlier = new Change(SINCE.plusSeconds(60), ok(PrNumber.prNumber(1)), ChangeKind.PR_STATE, "#1", "open", "merged",
                                  SINCE.plusSeconds(60));
        var summary = render(SINCE, List.of(later, earlier, change(3, SINCE), change(4, SINCE.minusSeconds(1))));

        assertThat(summary.lines()).containsExactly(COUNTS_LINE,
                                                    "2026-10-30T00:01:00Z #1 state: open -> merged",
                                                    "2026-10-30T00:02:00Z #2 check build: pending -> success",
                                                    "next: since=" + AS_OF);
    }

    /// The cursor is `recordedAt`, never `observedAt` (lead ruling, 2026-10-30). A change observed before `since` but
    /// recorded after it is listed, shown at its observation; one observed after `since` but recorded at or before it was
    /// listed by an earlier call; one recorded after `asOf` waits for the next. The order is by `recordedAt`.
    @Test
    void render_selectsAndOrdersByRecordedAt_andShowsObservedAt() {
        var staleCaller = recorded(1, SINCE.minusSeconds(30), SINCE.plusSeconds(120));
        var recordedFirst = recorded(2, SINCE.plusSeconds(600), SINCE.plusSeconds(60));
        var alreadyListed = recorded(3, SINCE.plusSeconds(60), SINCE);
        var notYet = recorded(4, SINCE.plusSeconds(60), AS_OF.plusNanos(1000));
        var summary = render(SINCE, List.of(staleCaller, recordedFirst, alreadyListed, notYet));

        assertThat(summary.lines()).containsExactly(COUNTS_LINE,
                                                    "2026-10-30T00:10:00Z #2 check build: pending -> success",
                                                    "2026-10-29T23:59:30Z #1 check build: pending -> success",
                                                    "next: since=" + AS_OF);
    }

    /// A truncated page's cursor is the last listed `recordedAt`, so the next call (reading `recordedAt > since`) resumes
    /// right after it, whatever the observation times.
    @Test
    void render_truncatedCursor_isTheLastListedRecordedAt() {
        var changes = IntStream.range(0, 30)
                               .mapToObj(i -> recorded(i + 1, SINCE.minusSeconds(3600 + i), SINCE.plusSeconds(i + 1)))
                               .toList();
        var first = render(SINCE, changes);

        assertThat(first.asOf()).isEqualTo(SINCE.plusSeconds(23));
        assertThat(first.lines()
                        .getLast()).isEqualTo("more: since=" + SINCE.plusSeconds(23));
        assertThat(render(first.asOf(), changes).lines()).filteredOn(line -> line.contains(" check build: "))
                                                         .hasSize(7);
    }

    @Test
    void render_listsTheSourcesNotOk_withTheirAge() {
        var host = Source.host(ok(HostName.hostName("bigboy")));
        var t = Instant.parse("2026-10-29T23:00:00Z");
        var sources = List.of(new SourceStatus(Source.MIRROR, new SourceState.Ok(t), some(t)),
                              new SourceStatus(host, new SourceState.Unreachable("ssh rc 255", t), some(t)),
                              new SourceStatus(host, new SourceState.Incomplete("count 3 of 4", t), some(t)),
                              new SourceStatus(Source.GITHUB, new SourceState.RateLimited(AS_OF, t), some(t)),
                              new SourceStatus(Source.GITHUB, new SourceState.GithubDown("502", t), none()),
                              new SourceStatus(Source.TEMPER, new SourceState.TemperDown("connect refused", t), some(t)),
                              new SourceStatus(Source.TEMPER, new SourceState.TemperSchema("answer", t), some(t)));
        var summary = SummaryText.render(SINCE, AS_OF, sources, List.of(), COUNTS, none());

        assertThat(summary.lines()).containsExactly(COUNTS_LINE,
                                                    "source host:bigboy: UNREACHABLE(ssh rc 255) since " + t + ", last success " + t,
                                                    "source host:bigboy: INCOMPLETE(count 3 of 4) at " + t + ", last success " + t,
                                                    "source github: RATE-LIMITED until " + AS_OF + ", since " + t + ", last success " + t,
                                                    "source github: GITHUB-DOWN(502) since " + t + ", last success never",
                                                    "source temper: TEMPER-DOWN(connect refused) since " + t + ", last success " + t,
                                                    "source temper: TEMPER-SCHEMA(answer) since " + t + ", last success " + t,
                                                    "next: since=" + AS_OF);
    }

    /// WP9 open item 12: an expected source with no recorded state is listed as such, after the states not OK, never
    /// silently absent (its counts would read as complete); a recorded one, OK or not, gets no such line.
    @Test
    void render_listsEveryExpectedSourceWithNoState() {
        var bigboy = Source.host(ok(HostName.hostName("bigboy")));
        var t = Instant.parse("2026-10-29T23:00:00Z");
        var sources = List.of(new SourceStatus(Source.GITHUB, new SourceState.Ok(t), some(t)),
                              new SourceStatus(Source.TEMPER, new SourceState.TemperDown("connect refused", t), none()));
        var summary = SummaryText.render(SINCE,
                                         AS_OF,
                                         List.of(Source.GITHUB, Source.MIRROR, Source.TEMPER, bigboy, Source.MAC, Source.MAC),
                                         sources,
                                         List.of(),
                                         COUNTS,
                                         none());

        assertThat(summary.lines()).containsExactly(COUNTS_LINE,
                                                    "source temper: TEMPER-DOWN(connect refused) since " + t + ", last success never",
                                                    "source mirror: no state recorded, last success never",
                                                    "source host:bigboy: no state recorded, last success never",
                                                    "source mac: no state recorded, last success never",
                                                    "next: since=" + AS_OF);
    }

    @Test
    void render_putsAGapLineFirst_onlyWhenSinceIsOlderThanTheOldestKeptChange() {
        var oldest = SINCE.plusSeconds(1);

        assertThat(SummaryText.render(SINCE, AS_OF, List.of(), List.of(), COUNTS, some(oldest))
                              .lines()
                              .getFirst()).startsWith("gap: since=" + SINCE)
                                          .contains(oldest.toString());
        assertThat(SummaryText.render(oldest, AS_OF, List.of(), List.of(), COUNTS, some(oldest))
                              .lines()).noneMatch(line -> line.startsWith("gap"));
        assertThat(SummaryText.render(SINCE, AS_OF, List.of(), List.of(), COUNTS, some(SINCE.minusSeconds(1)))
                              .lines()).noneMatch(line -> line.startsWith("gap"));
        assertThat(SummaryText.render(SINCE, AS_OF, List.of(), List.of(), COUNTS, none())
                              .lines()).noneMatch(line -> line.startsWith("gap"));
    }

    @Test
    void render_fitsExactly25Lines_withoutTruncating() {
        var changes = IntStream.range(0, 23)
                               .mapToObj(i -> change(i + 1, SINCE.plusSeconds(i + 1)))
                               .toList();
        var summary = render(SINCE, changes);

        assertThat(summary.lines()).hasSize(25)
                                   .endsWith("next: since=" + AS_OF);
        assertThat(summary.asOf()).isEqualTo(AS_OF);
    }

    /// One poll cycle observes many changes at one instant. The cut falls only between instants, so passing `asOf`
    /// back as `since` (the store reads `observedAt > since`) lists every change exactly once across the calls.
    @Test
    void render_truncatesAtAnInstantBoundary_andLosesNothingAcrossCalls() {
        var changes = IntStream.range(0, 30)
                               .mapToObj(i -> change(i + 1, SINCE.plusSeconds(60L * (i / 3 + 1))))
                               .toList();
        var first = render(SINCE, changes);

        assertThat(first.lines()).hasSize(23);
        assertThat(first.asOf()).isEqualTo(SINCE.plusSeconds(60 * 7));
        assertThat(first.lines()
                        .getLast()).isEqualTo("more: since=" + first.asOf());

        var second = render(first.asOf(), changes);
        var listed = new ArrayList<String>();
        listed.addAll(first.lines());
        listed.addAll(second.lines());

        assertThat(listed).filteredOn(line -> line.contains(" check build: "))
                          .hasSize(30)
                          .doesNotHaveDuplicates();
        assertThat(second.lines()
                         .getLast()).isEqualTo("next: since=" + AS_OF);
    }

    /// One instant holding more changes than fit: the whole instant is listed past the cap, never dropped.
    @Test
    void render_listsAnOverflowingInstantWhole_pastTheCap() {
        var at = SINCE.plusSeconds(60);
        var changes = IntStream.range(0, 30)
                               .mapToObj(i -> change(i + 1, at))
                               .toList();
        var summary = render(SINCE, changes);

        assertThat(summary.lines()).hasSize(32)
                                   .endsWith("more: since=" + at);
        assertThat(summary.lines()).filteredOn(line -> line.contains(" check build: "))
                                   .hasSize(30);
        assertThat(summary.asOf()).isEqualTo(at);
    }

    /// The overflowing instant is listed alone; a later instant comes on the next page, nothing lost across the calls.
    @Test
    void render_listsOnlyTheOverflowingInstant_andTheNextPageTheRest() {
        var at = SINCE.plusSeconds(60);
        var later = at.plusSeconds(60);
        var changes = IntStream.range(0, 35)
                               .mapToObj(i -> change(i + 1, i < 30 ? at : later))
                               .toList();
        var first = render(SINCE, changes);
        var second = render(first.asOf(), changes);

        assertThat(first.lines()).hasSize(32)
                                 .endsWith("more: since=" + at)
                                 .noneMatch(line -> line.startsWith(later.toString()));
        assertThat(second.lines()).filteredOn(line -> line.startsWith(later.toString()))
                                  .hasSize(5);
        assertThat(second.lines()
                         .getLast()).isEqualTo("next: since=" + AS_OF);
    }

    /// The cap holds whenever the cut can fall between instants.
    @Test
    void render_staysWithin25Lines_whenTheCutFallsBetweenInstants() {
        var changes = IntStream.range(0, 40)
                               .mapToObj(i -> change(i + 1, SINCE.plusSeconds(i + 1)))
                               .toList();

        assertThat(render(SINCE, changes).lines()).hasSize(25);
    }

    /// Sources fill the page: every source and the only change are still listed (past the cap), with the counts line.
    @Test
    void render_neverDropsAChange_evenWhenSourcesFillThePage() {
        var t = Instant.parse("2026-10-29T23:00:00Z");
        var sources = IntStream.range(0, 40)
                               .mapToObj(i -> new SourceStatus(Source.host(ok(HostName.hostName("h" + i))),
                                                               new SourceState.Unreachable("down", t),
                                                               none()))
                               .toList();
        var summary = SummaryText.render(SINCE, AS_OF, sources, List.of(change(1, AS_OF)), COUNTS, none());

        assertThat(summary.lines()).hasSize(43)
                                   .contains(COUNTS_LINE)
                                   .contains(AS_OF + " #1 check build: pending -> success")
                                   .endsWith("more: since=" + AS_OF);
    }

    /// s4: 30 sources not OK: every one is listed, past the cap; completeness beats the cap.
    @Test
    void render_listsEverySourceNotOk_pastTheCap() {
        var t = Instant.parse("2026-10-29T23:00:00Z");
        var sources = IntStream.range(0, 30)
                               .mapToObj(i -> new SourceStatus(Source.host(ok(HostName.hostName("h" + i))),
                                                               new SourceState.Unreachable("down", t),
                                                               none()))
                               .toList();
        var summary = SummaryText.render(SINCE, AS_OF, sources, List.of(), COUNTS, none());

        assertThat(summary.lines()).hasSize(32)
                                   .startsWith(COUNTS_LINE)
                                   .endsWith("next: since=" + AS_OF);
        assertThat(summary.lines()).filteredOn(line -> line.startsWith("source host:h"))
                                   .hasSize(30)
                                   .doesNotHaveDuplicates();
    }

    /// s5: a change stamped after `asOf` (clock skew, or a write after the read) is not listed now; the cursor
    /// `since=<asOf>` lists it on the next call, so it is listed exactly once.
    @Test
    void render_listsAChangeAfterAsOf_onlyOnTheNextCall() {
        var late = change(2, AS_OF.plusSeconds(5));
        var changes = List.of(change(1, SINCE.plusSeconds(1)), late);
        var first = render(SINCE, changes);
        var second = SummaryText.render(first.asOf(), AS_OF.plusSeconds(60), List.of(), changes, COUNTS, none());
        var listed = new ArrayList<String>();
        listed.addAll(first.lines());
        listed.addAll(second.lines());

        assertThat(first.lines()).containsExactly(COUNTS_LINE,
                                                  SINCE.plusSeconds(1) + " #1 check build: pending -> success",
                                                  "next: since=" + AS_OF);
        assertThat(first.asOf()).isEqualTo(AS_OF);
        assertThat(listed).filteredOn(line -> line.contains(" check build: "))
                          .containsExactly(SINCE.plusSeconds(1) + " #1 check build: pending -> success",
                                           late.observedAt() + " #2 check build: pending -> success");
    }

    /// A change exactly at `asOf` is listed now, and not again under `since=<asOf>`.
    @Test
    void render_listsAChangeAtAsOf_once() {
        var changes = List.of(change(1, AS_OF));
        var first = render(SINCE, changes);
        var second = SummaryText.render(first.asOf(), AS_OF.plusSeconds(60), List.of(), changes, COUNTS, none());

        assertThat(first.lines()).contains(AS_OF + " #1 check build: pending -> success")
                                 .endsWith("next: since=" + AS_OF);
        assertThat(second.lines()).noneMatch(line -> line.contains(" check build: "));
    }

    /// A pinned cell's subject is its key; the text shows its label (`@`), never the U+001F separator.
    @Test
    void render_showsAPinnedCellByItsLabel_neverTheRawKey() {
        var check = new RequiredCheck(ok(CheckContext.checkContext("ci")), some(ok(AppId.appId(15368))));
        var change = new Change(SINCE.plusSeconds(1), ok(PrNumber.prNumber(1)), ChangeKind.CHECK_CELL, check.key(), "pending", "success", SINCE.plusSeconds(1));
        var summary = render(SINCE, List.of(change));

        assertThat(summary.lines()).contains(SINCE.plusSeconds(1) + " #1 check ci@15368: pending -> success")
                                   .noneMatch(line -> line.contains(RequiredCheck.SEPARATOR));
    }

    /// A head move names the base it is on; before/after are the SHAs.
    @Test
    void render_showsAHeadMove_onItsBase() {
        var change = new Change(SINCE.plusSeconds(1), ok(PrNumber.prNumber(1)), ChangeKind.HEAD_MOVED, "main", "a".repeat(40), "b".repeat(40), SINCE.plusSeconds(1));
        var summary = render(SINCE, List.of(change));

        assertThat(summary.lines()).contains(SINCE.plusSeconds(1) + " #1 head on main: " + "a".repeat(40) + " -> " + "b".repeat(40));
    }
}
