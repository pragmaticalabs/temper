package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.Counts;
import org.pragmatica.kiln.core.summary.SummaryText;
import org.pragmatica.kiln.core.feed.FeedChange;
import org.pragmatica.kiln.core.feed.FeedKind;
import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.KilnStoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [ChangeStore][org.pragmatica.kiln.service.store.ChangeStore] against real Postgres: `since` is exclusive, oldest
/// recorded first and limited; `oldest` is the first kept stamp; `prune` drops only the changes recorded before its bound.
/// Every cursor is the store's `recordedAt`, never the caller's `observedAt` (lead ruling, 2026-10-30): the changes are
/// written with `observedAt` running backwards, so a cursor or an order on `observedAt` reads them reversed.
@Tag("pg")
class ChangeStoreIT {
    private KilnStore store;

    @BeforeEach
    void store() {
        store = KilnPgFixture.freshStore(StoreFixtures.DIFF);
    }

    @AfterEach
    void close() {
        if (store != null) {
            await(store.close());
        }
    }

    @Test
    void since_isExclusive_oldestRecordedFirst_andLimited() {
        var changes = written();

        assertThat(await(store.changes().since(Instant.EPOCH, 10))).containsExactlyElementsOf(changes);
        assertThat(await(store.changes().since(changes.get(0).recordedAt(), 10))).containsExactlyElementsOf(changes.subList(1, 3));
        assertThat(await(store.changes().since(Instant.EPOCH, 2))).containsExactlyElementsOf(changes.subList(0, 2));
        assertThat(await(store.changes().since(changes.get(2).recordedAt(), 10))).isEmpty();
    }

    /// CI interface T1 WP2: the feed's kinds, and rows about no PR, are written beside the summary's; the summary lists
    /// only its own kinds about a PR, and is never broken by the others (WP1 open item).
    @Test
    void since_listsOnlyTheSummaryKinds_whenFeedRowsAndRowsWithoutAPrAreStored() {
        var issue = new FeedChange(FeedKind.BOT_ISSUE_OPENED, none(), "9", FeedChange.NONE, "open", Instant.EPOCH.plusSeconds(5),
                                   FeedChange.payload("number", 9L));

        assertThat(await(store.feed().issues("o/r",
                                             List.of(new BotIssue(9, "t", "u", Instant.EPOCH, some("open"), some(Instant.EPOCH))),
                                             Instant.EPOCH.plusSeconds(5)))).extracting(FeedChange::kind)
                                                                           .containsExactly(FeedKind.BOT_ISSUE_OPENED);
        var changes = written();

        assertThat(await(store.changes().since(Instant.EPOCH, 10))).containsExactlyElementsOf(changes);
        assertThat(await(store.changes().feed(0, 10)).changes()).extracting(ChangeFeed.Row::kind)
                                                               .contains(issue.kind().name());
    }

    /// One write of 30 PRs records 30 changes at one `recordedAt`. A page never ends inside that instant (lead ruling,
    /// WP5 verification): a summary loop of `since(cursor, 26)` → render → `cursor = asOf` lists all 30, none twice.
    @Test
    void since_neverEndsInsideAnInstant_soASummaryLoopListsEveryChangeOnce() {
        var batch = IntStream.rangeClosed(1, 30)
                             .mapToObj(number -> pullRequest(number, PrState.OPEN, 'a'))
                             .toList();
        await(store.prs().upsert(batch, T0));
        var listed = new ArrayList<String>();
        var cursor = Instant.EPOCH;

        for (var call = 0; call < 5; call++) {
            var asOf = store.changes().watermark();
            var page = await(store.changes().since(cursor, SummaryText.MAX_LINES + 1));
            var summary = SummaryText.render(cursor, asOf, List.of(), page, new Counts(0, 0), none());
            summary.lines().stream().filter(line -> line.contains(" state: ")).forEach(listed::add);
            cursor = summary.asOf();
        }

        assertThat(await(store.changes().since(Instant.EPOCH, 26))).hasSize(30);
        assertThat(listed).hasSize(30)
                          .doesNotHaveDuplicates();
    }

    /// A page limit below 1 is refused (lead ruling, WP5 verification), never answered with an empty page whose cursor
    /// would stall the caller; a limit of 1 still pages.
    @Test
    void since_refusesALimitBelowOne() {
        written();

        for (var limit : List.of(0, -1)) {
            store.changes()
                 .since(Instant.EPOCH, limit)
                 .await(KilnPgFixture.WAIT)
                 .onSuccess(page -> fail("expected limit " + limit + " to be refused, got " + page))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(KilnStoreError.InvalidLimit.class));
        }
        assertThat(await(store.changes().since(Instant.EPOCH, 1))).hasSize(1);
    }

    /// The stamp is the store's clock at the write, and `observedAt` is kept as the caller gave it.
    @Test
    void since_answersTheCallersObservedAt_andTheStoresRecordedAt() {
        var changes = written();

        assertThat(changes).extracting(Change::observedAt).containsExactly(T2, T1, T0);
        assertThat(changes).extracting(Change::recordedAt).allMatch(at -> !at.isBefore(StoreFixtures.RECORDED)).isSorted();
    }

    @Test
    void oldest_isAbsentWhenEmpty_thenTheFirstRecorded() {
        assertThat(await(store.changes().oldest())).isEqualTo(none());

        var changes = written();

        assertThat(await(store.changes().oldest())).isEqualTo(some(changes.getFirst().recordedAt()));
    }

    @Test
    void prune_dropsOnlyTheChangesRecordedBeforeItsBound() {
        var changes = written();

        await(store.changes().prune(changes.get(1).recordedAt()));

        assertThat(await(store.changes().since(Instant.EPOCH, 10))).containsExactlyElementsOf(changes.subList(1, 3));
        assertThat(await(store.changes().oldest())).isEqualTo(some(changes.get(1).recordedAt()));
    }

    /// Three writes, each one change, observed at T2, T1 and T0 in that order (callers lagging ever more).
    private List<Change> written() {
        var first = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T2));
        var second = await(store.prs().upsert(List.of(pullRequest(1, PrState.CLOSED, 'a')), T1));
        var third = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));

        return List.of(first.getFirst(), second.getFirst(), third.getFirst());
    }
}
