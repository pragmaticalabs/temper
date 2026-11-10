package org.pragmatica.kiln.service.store.pg;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.feed.FeedChange;
import org.pragmatica.kiln.core.feed.FeedKind;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.lang.Unit.unit;

/// Verifier NOTE-3: every transaction inserting into `change` takes [PgChangeStore#CHANGE_LOCK], so change transactions
/// commit in id order even with two writers (a second kiln process). The later transaction, ready to commit first,
/// waits for the earlier one. Probe: the lock dropped from `PgChangeStore.guardedWrite` → the later commits first, red.
@Tag("pg")
class ChangeLockIT {
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
    void twoInserters_commitInIdOrder_whenTheLaterIsReadyFirst() throws InterruptedException {
        var db = ((PgKilnStore) store).db();
        Promise<Unit> firstInserted = Promise.promise();
        Promise<Unit> releaseFirst = Promise.promise();
        Promise<Unit> releaseSecond = Promise.promise();

        var first = db.inTransaction(tx -> PgChangeStore.insert(tx, mark(), StoreFixtures.REPO, List.of(change(1)))
                                                        .withSuccess(_ -> firstInserted.succeed(unit()))
                                                        .flatMap(_ -> releaseFirst));
        await(firstInserted);
        var second = db.inTransaction(tx -> PgChangeStore.insert(tx, mark(), StoreFixtures.REPO, List.of(change(2)))
                                                         .flatMap(_ -> releaseSecond));
        releaseSecond.succeed(unit());
        Thread.sleep(500);

        assertThat(second.isResolved()).as("the later transaction waits for the earlier").isFalse();
        assertThat(await(store.changes().feed(0, 500)).changes()).as("nothing committed out of order").isEmpty();

        releaseFirst.succeed(unit());
        await(first);
        await(second);

        assertThat(await(store.changes().feed(0, 500)).changes()).extracting(row -> row.id() + " " + row.subject())
                                                                 .containsExactly("1 #1", "2 #2");
    }

    /// The feed's insert path takes the same lock ([PgChangeStore#guardedWrite]): a feed inserter racing a cell inserter,
    /// ready first with the higher id, still waits for it. Probe: the lock dropped from the feed path → red.
    @Test
    void aFeedInsert_waitsForAnEarlierChangeTransaction() throws InterruptedException {
        var db = ((PgKilnStore) store).db();
        Promise<Unit> firstInserted = Promise.promise();
        Promise<Unit> releaseFirst = Promise.promise();

        var first = db.inTransaction(tx -> PgChangeStore.insert(tx, mark(), StoreFixtures.REPO, List.of(change(1)))
                                                        .withSuccess(_ -> firstInserted.succeed(unit()))
                                                        .flatMap(_ -> releaseFirst));
        await(firstInserted);
        var second = db.inTransaction(tx -> PgChangeStore.insertFeed(tx, mark(), StoreFixtures.REPO, List.of(feedChange()), T0));
        Thread.sleep(500);

        assertThat(second.isResolved()).as("the feed transaction waits for the earlier").isFalse();
        assertThat(await(store.changes().feed(0, 500)).changes()).as("nothing committed out of order").isEmpty();

        releaseFirst.succeed(unit());
        await(first);
        await(second);

        assertThat(await(store.changes().feed(0, 500)).changes()).extracting(row -> row.id() + " " + row.subject())
                                                                 .containsExactly("1 #1", "2 main");
    }

    private HighWater mark() {
        return ((PgChangeStore) store.changes()).feedMark();
    }

    private static FeedChange feedChange() {
        return new FeedChange(FeedKind.REQUIRED_LIST_CHANGED, Option.none(), "main", FeedChange.NONE, "ci", T0, Map.of());
    }

    private static Change change(long pr) {
        return StoreFixtures.change(T0, pr, ChangeKind.PR_STATE, "#" + pr, "open", "closed");
    }
}
