package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.sha;
import static org.pragmatica.lang.Option.some;

/// `HEAD_MOVED` with the production change rule: an open PR whose head SHA differs from the stored
/// `pr.head_sha` gives exactly one change, inserted by the same upsert transaction (before/after the old and new SHA,
/// subject the base); the same head and a first sighting give none. Probe: the observed heads left out of the store's
/// after-snapshot (`PgPrStore.snapshot`) → no row and `upsert_recordsOneHeadMove_whenAnOpenPrsHeadChanges` goes red.
@Tag("pg")
class HeadMovedIT {
    private KilnStore store;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
    }

    @AfterEach
    void stop() {
        await(store.close());
    }

    @Test
    void upsert_recordsOneHeadMove_whenAnOpenPrsHeadChanges() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));

        var moved = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'b')), T1));

        assertThat(moved).singleElement()
                         .satisfies(change -> assertThat(change.kind()).isEqualTo(ChangeKind.HEAD_MOVED))
                         .satisfies(change -> assertThat(change.subject()).isEqualTo("main"))
                         .satisfies(change -> assertThat(change.before()).isEqualTo(sha('a').value()))
                         .satisfies(change -> assertThat(change.after()).isEqualTo(sha('b').value()))
                         .satisfies(change -> assertThat(change.observedAt()).isEqualTo(T1));
        assertThat(feed().changes()).singleElement()
                                    .satisfies(row -> assertThat(row.kind()).isEqualTo("HEAD_MOVED"))
                                    .satisfies(row -> assertThat(row.repo()).isEqualTo(StoreFixtures.REPO))
                                    .satisfies(row -> assertThat(row.pr()).isEqualTo(some(StoreFixtures.pr(1))))
                                    .satisfies(row -> assertThat(row.before() + ">" + row.after()).isEqualTo(sha('a').value() + ">" + sha('b').value()));
    }

    @Test
    void upsert_recordsNothing_forTheSameHead_orAFirstSighting() {
        var first = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));
        var same = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T1));

        assertThat(first).isEmpty();
        assertThat(same).isEmpty();
        assertThat(feed().changes()).isEmpty();
    }

    /// One upsert listing a PR twice: the last copy decides, as the row's last write does.
    @Test
    void upsert_judgesTheLastCopy_ofAPrListedTwice() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));

        var moved = await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'c'), pullRequest(1, PrState.OPEN, 'b')), T1));

        assertThat(moved).singleElement()
                         .satisfies(change -> assertThat(change.before() + ">" + change.after()).isEqualTo(sha('a').value() + ">" + sha('b').value()));
    }

    /// A PR seen closed with a new head moved no open head: only its state change is recorded.
    @Test
    void upsert_recordsNoHeadMove_forAPrNoLongerOpen() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));

        var closed = await(store.prs().upsert(List.of(pullRequest(1, PrState.CLOSED, 'b')), T2));

        assertThat(closed).extracting(Change::kind).containsExactly(ChangeKind.PR_STATE);
    }

    private ChangeFeed feed() {
        return await(store.changes().feed(0, 500));
    }
}
