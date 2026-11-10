package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.NewReview;
import org.pragmatica.temper.store.api.NewVerdict;
import org.pragmatica.temper.store.api.PrAuthor;
import org.pragmatica.temper.store.api.ReviewListing;
import org.pragmatica.temper.store.api.ReviewStore;
import org.pragmatica.temper.store.api.StoreError;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// V16 and the review store: the tables' CHECKs, the case-insensitive append-only
/// author set, the `sending` upsert (`attempts` + 1 on the one row of the key), the `posted` transaction with its
/// events, and the per-PR session lock that a second caller does not get while the first holds it.
@Tag("pg")
class ReviewStoreIT {
    private static final String SHA = "a".repeat(40);
    private static final String TIP = "b".repeat(40);
    private static final String DIGEST = "d".repeat(64);
    private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");

    private StoreHarness harness;
    private ReviewStore reviews;
    private long verdict;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        reviews = harness.store.reviews();
        verdict = harness.store.t2()
                               .register(NewVerdict.newVerdict("o/r",
                                                               7,
                                                               SHA,
                                                               "main",
                                                               "ACCEPT",
                                                               "v-a",
                                                               new NewVerdict.Basis.Report("/r/report.md", DIGEST)),
                                         _ -> List.of())
                               .await(PgFixture.WAIT)
                               .unwrap()
                               .value()
                               .id();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void v16_createsTheFourTables() {
        assertThat(text("SELECT string_agg(table_name, ',' ORDER BY table_name) AS v FROM information_schema.tables "
                       + "WHERE table_schema = 'public' AND table_name IN ('pr_authors', 'reviews', 'review_exemptions', 'gh_budget')")).isEqualTo("gh_budget,pr_authors,review_exemptions,reviews");
    }

    @Test
    void authors_areAppendOnly_andUniqueIgnoringCase() {
        var first = reviews.addAuthors("o/r", 7, List.of("c-x", "c-y"), "c-x").await(PgFixture.WAIT).unwrap();
        var again = reviews.addAuthors("o/r", 7, List.of("C-X", "c-z"), "cto").await(PgFixture.WAIT).unwrap();

        assertThat(first).extracting(PrAuthor::lane).containsExactly("c-x", "c-y");
        assertThat(again).extracting(PrAuthor::lane).containsExactly("c-x", "c-y", "c-z");
        assertThat(again.getFirst().addedBy()).as("the first row stays as it was").isEqualTo("c-x");
        assertThat(reviews.authors("o/r", 8).await(PgFixture.WAIT).unwrap()).isEmpty();
    }

    @Test
    void sending_isOneRowPerKey_andCountsAttempts() {
        var first = reviews.sending(newReview()).await(PgFixture.WAIT).unwrap();
        var second = reviews.sending(newReview()).await(PgFixture.WAIT).unwrap();

        assertThat(first.state()).isEqualTo("sending");
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.attempts()).isEqualTo(2);
        assertThat(text("SELECT count(*)::text AS v FROM reviews")).isEqualTo("1");
    }

    @Test
    void posted_recordsTheGitHubId_andInsertsTheEventsInTheSameTransaction() {
        var sending = reviews.sending(newReview()).await(PgFixture.WAIT).unwrap();
        var committed = reviews.posted(sending.id(),
                                       99L,
                                       some(4000),
                                       row -> List.of(NewFleetEvent.newFleetEvent(T0,
                                                                                  "controller",
                                                                                  "review-posted",
                                                                                  "o/r#7",
                                                                                  "{}")))
                               .await(PgFixture.WAIT)
                               .unwrap();

        assertThat(committed.value().state()).isEqualTo("posted");
        assertThat(committed.value().githubReviewId()).isEqualTo(some(99L));
        assertThat(committed.seqs().isPresent()).isTrue();
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events WHERE kind = 'review-posted'")).isEqualTo("1");
    }

    @Test
    void failed_keepsTheRow_andARetryReusesIt() {
        var sending = reviews.sending(newReview()).await(PgFixture.WAIT).unwrap();
        var failed = reviews.failed(sending.id(),
                                    "GITHUB_REJECTED nope",
                                    some(422),
                                    none())
                            .await(PgFixture.WAIT)
                            .unwrap();
        var retried = reviews.sending(newReview()).await(PgFixture.WAIT).unwrap();

        assertThat(failed.state()).isEqualTo("failed");
        assertThat(retried.id()).isEqualTo(sending.id());
        assertThat(retried.attempts()).isEqualTo(2);
        assertThat(text("SELECT coalesce(failure, '-') AS v FROM reviews")).isEqualTo("-");
    }

    @Test
    void checks_refuseAPostedRowWithoutAnId_andABadEvent() {
        assertThat(execute("INSERT INTO reviews (repo, pr, sha, event, state, verdict_id, declared_as) VALUES ('o/r', 7, '" + SHA
                          + "', 'APPROVE', 'posted', " + verdict
                          + ", 'v-a')").isFailure()).isTrue();
        assertThat(execute("INSERT INTO reviews (repo, pr, sha, event, state, verdict_id, declared_as) VALUES ('o/r', 7, '" + SHA
                          + "', 'COMMENT', 'sending', " + verdict
                          + ", 'v-a')").isFailure()).isTrue();
    }

    @Test
    void prLock_isRefusedToASecondCaller_whileTheFirstHoldsIt_andFreeAfter() {
        var inner = new AtomicBoolean(true);
        var other = new AtomicBoolean(true);
        // Composed, not awaited: the work runs on an event-loop thread, which a blocking wait would hold.
        var outcome = reviews.tryWithPrLock("o/r",
                                            7,
                                            () -> reviews.tryWithPrLock("o/r",
                                                                        7,
                                                                        () -> Promise.success(Unit.unit()))
                                                         .flatMap(sameIn -> reviews.tryWithPrLock("o/r",
                                                                                                  8,
                                                                                                  () -> Promise.success(Unit.unit()))
                                                                                   .map(otherIn -> {
                                                                                            inner.set(sameIn.isPresent());
                                                                                            other.set(otherIn.isPresent());

                                                                                            return "held";
                                                                                        })))
                             .await(PgFixture.WAIT)
                             .unwrap();

        assertThat(outcome).isEqualTo(some("held"));
        assertThat(inner.get()).as("the same PR is refused while held").isFalse();
        assertThat(other.get()).as("another PR is not delayed").isTrue();
        assertThat(reviews.tryWithPrLock("o/r",
                                         7,
                                         () -> Promise.success("again")).await(PgFixture.WAIT).unwrap()).as("released afterwards")
                  .isEqualTo(some("again"));
    }

    @Test
    void prLock_isReleasedWhenTheWorkFails_asSeenFromAnotherSession() {
        var failed = reviews.tryWithPrLock("o/r",
                                           7,
                                           () -> org.pragmatica.lang.utils.Causes.cause("boom")
                                                                                 .<String> promise())
                            .await(PgFixture.WAIT);

        assertThat(failed.isFailure()).isTrue();
        assertThat(heldLocks()).as("pg_locks, any session: no advisory lock of the review key survives").isEqualTo("0");
        assertThat(reviews.tryWithPrLock("o/r", 7, () -> Promise.success("free")).await(PgFixture.WAIT).unwrap()).isEqualTo(some("free"));
    }

    @Test
    void prLock_isReleasedAfterASuccessfulCall_asSeenFromAnotherSession() {
        reviews.tryWithPrLock("o/r", 7, () -> Promise.success("ok")).await(PgFixture.WAIT).unwrap();
        assertThat(heldLocks()).isEqualTo("0");
    }

    @Test
    void prLock_isHeldDuringTheWork_byADedicatedSession_thatEndsAfterTheCall() {
        var during = new AtomicReference<String>();
        var holder = new AtomicReference<String>();

        reviews.tryWithPrLock("o/r",
                              7,
                              () -> {
                                  during.set(heldLocks());
                                  holder.set(text("SELECT pid::text AS v FROM pg_locks WHERE locktype = 'advisory' AND granted AND database = (SELECT oid FROM pg_database WHERE datname = current_database()) AND classid = " + PgReviewStore.REVIEW_LOCK));

                                  return Promise.success("ok");
                              })
               .await(PgFixture.WAIT)
               .unwrap();
        assertThat(during.get()).isEqualTo("1");
        // The backend exits on Terminate asynchronously: allow it a few seconds to leave pg_stat_activity.
        var sessionQuery = "SELECT count(*)::text AS v FROM pg_stat_activity WHERE pid = " + holder.get();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (!text(sessionQuery).equals("0") && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(text(sessionQuery)).as("the lock's session is closed, not returned to a pool").isEqualTo("0");
    }

    @Test
    void prLock_aKilledLockSession_endsTheLock_andTheCallFailsLost() {
        var outcome = reviews.tryWithPrLock("o/r",
                                            7,
                                            () -> {
                                                text("SELECT pg_terminate_backend(pid)::text AS v FROM pg_locks WHERE locktype = 'advisory' AND granted AND database = (SELECT oid FROM pg_database WHERE datname = current_database()) AND classid = " + PgReviewStore.REVIEW_LOCK);

                                                return Promise.success("done");
                                            })
                             .await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(StoreError.PrLock.LOST));
        assertThat(heldLocks()).as("the terminated session took the lock with it").isEqualTo("0");
        assertThat(reviews.tryWithPrLock("o/r", 7, () -> Promise.success("free")).await(PgFixture.WAIT).unwrap()).isEqualTo(some("free"));
    }

    /// B1: the lock sessions are not the main pool's connections. On a store whose main pool has only as many
    /// connections as there are lock sessions, four reviews hanging under their locks leave the pool free: a fifth
    /// review is refused at once (BUSY, not queued), and the T2 read and a transaction still complete.
    @Test
    void prLock_capFilledByFourHangingCalls_aFifthIsBusyAtOnce_andOtherStoreWorkStillCompletes() {
        var c = harness.config;
        var small = Postgres.connect(DbConfig.dbConfig(c.host(),
                                                       c.port(),
                                                       c.database(),
                                                       c.user(),
                                                       c.password(),
                                                       LockSessions.SLOTS)
                                             .unwrap(),
                                     org.pragmatica.temper.domain.gate.Judge.UNJUDGED)
                            .await(PgFixture.WAIT)
                            .unwrap();

        try {
            var gates = new ArrayList<Promise<String>>();
            var calls = new ArrayList<Promise<Option<String>>>();

            for (long pr = 1; pr <= LockSessions.SLOTS; pr++) {
                var gate = Promise.<String> promise();

                gates.add(gate);
                calls.add(small.reviews().tryWithPrLock("o/r", pr, () -> gate));
            }

            awaitLocks(String.valueOf(LockSessions.SLOTS));
            var started = System.nanoTime();
            var fifth = small.reviews().tryWithPrLock("o/r", 99, () -> Promise.success("ran")).await(PgFixture.WAIT);

            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("refused at once")
                      .isLessThan(2000);
            assertThat(fifth.isFailure()).isTrue();
            fifth.onFailure(cause -> assertThat(cause).isEqualTo(StoreError.PrLock.BUSY));
            assertThat(small.t2()
                            .latestVerdict("o/r", 7, SHA)
                            .await(TimeSpan.timeSpan(5).seconds())
                            .unwrap()
                            .isPresent()).as("a T2 read is not starved by the held locks")
                      .isTrue();
            assertThat(small.reviews()
                            .addAuthors("o/r",
                                        7,
                                        List.of("c-y"),
                                        "t")
                            .await(TimeSpan.timeSpan(5).seconds())
                            .unwrap()).as("a transaction is not starved by the held locks")
                      .hasSize(1);
            gates.forEach(gate -> gate.succeed("done"));
            calls.forEach(call -> assertThat(call.await(PgFixture.WAIT).unwrap()).isEqualTo(some("done")));
            assertThat(heldLocks()).isEqualTo("0");
            assertThat(small.reviews()
                            .tryWithPrLock("o/r",
                                           99,
                                           () -> Promise.success("ran"))
                            .await(PgFixture.WAIT)
                            .unwrap()).as("the slots are free again")
                      .isEqualTo(some("ran"));
        } finally {
            small.close();
        }
    }

    /// The slot accounting: every outcome (lock refused, work failed, work threw) returns exactly its one slot. More
    /// than SLOTS of each leave all SLOTS free (four hanging calls fill them, a fifth is BUSY), and after the hanging
    /// calls finish the count is back at SLOTS, not above it (no double release).
    @Test
    void prLock_everyOutcomeReturnsExactlyItsSlot_refusedFailedAndThrowingCalls() {
        var db = PgFixture.db(harness.config);
        var boom = org.pragmatica.lang.utils.Causes.cause("boom");

        try {
            var locks = db.locks();
            // Held by a pooled session of the harness, which stays alive until the harness closes.
            text("SELECT '1' AS v FROM (SELECT pg_advisory_lock(" + PgReviewStore.REVIEW_LOCK
                + ", hashtext('o/r#50'))) held");
            for (var i = 0; i < LockSessions.SLOTS + 2; i++) {
                assertThat(locks.tryWithLock(PgReviewStore.REVIEW_LOCK,
                                             "o/r#50",
                                             () -> Promise.success("never"))
                                .await(PgFixture.WAIT)
                                .unwrap()).as("refused: the key is held by another session")
                          .isEqualTo(none());
            }

            for (var i = 0; i < LockSessions.SLOTS + 2; i++) {
                var failed = locks.tryWithLock(PgReviewStore.REVIEW_LOCK,
                                               "o/r#51",
                                               () -> boom.<String> promise())
                                  .await(PgFixture.WAIT);

                assertThat(failed.isFailure()).isTrue();
                failed.onFailure(cause -> assertThat(cause).as("the work's own failure, not BUSY")
                                                    .isEqualTo(boom));
            }

            for (var i = 0; i < LockSessions.SLOTS + 2; i++) {
                var thrown = locks.<String> tryWithLock(PgReviewStore.REVIEW_LOCK,
                                                        "o/r#52",
                                                        () -> {
                                                            throw new IllegalStateException("sync");
                                                        })
                                  .await(PgFixture.WAIT);

                assertThat(thrown.isFailure()).isTrue();
                thrown.onFailure(cause -> assertThat(cause).as("not BUSY: the slot came back")
                                                    .isNotEqualTo(StoreError.PrLock.BUSY));
            }

            assertThat(locks.slots().availablePermits()).isEqualTo(LockSessions.SLOTS);
            var gates = new ArrayList<Promise<String>>();
            var calls = new ArrayList<Promise<Option<String>>>();

            for (var i = 0; i < LockSessions.SLOTS; i++) {
                var gate = Promise.<String> promise();

                gates.add(gate);
                calls.add(locks.tryWithLock(PgReviewStore.REVIEW_LOCK, "o/r#6" + i, () -> gate));
            }

            assertThat(locks.tryWithLock(PgReviewStore.REVIEW_LOCK,
                                         "o/r#99",
                                         () -> Promise.success("ran"))
                            .await(PgFixture.WAIT)
                            .isFailure()).as("four hanging calls fill the cap; the fifth is BUSY")
                      .isTrue();
            gates.forEach(gate -> gate.succeed("done"));
            calls.forEach(call -> assertThat(call.await(PgFixture.WAIT).unwrap()).isEqualTo(some("done")));
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            while (locks.slots().availablePermits() < LockSessions.SLOTS && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }

            assertThat(locks.slots().availablePermits()).as("no double release").isEqualTo(LockSessions.SLOTS);
            assertThat(locks.tryWithLock(PgReviewStore.REVIEW_LOCK,
                                         "o/r#99",
                                         () -> Promise.success("ran"))
                            .await(PgFixture.WAIT)
                            .unwrap()).isEqualTo(some("ran"));
        } finally {
            db.close();
        }
    }

    /// B3: the lock is released only after the work's promise completed. The work writes its row 2 s in; from another
    /// session, the first moment the lock is seen gone the row is already there, and the call answers only after it.
    @Test
    void prLock_slowWork_isNeverReleasedEarly_nothingIsWrittenAfterTheRelease() throws Exception {
        var observedAfterRelease = new AtomicReference<String>();
        var watcher = new Thread(() -> {
            var seenHeld = false;
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

            while (System.nanoTime() < deadline) {
                var state = text("SELECT (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted AND database = (SELECT oid FROM pg_database WHERE datname = current_database()) AND classid = " + PgReviewStore.REVIEW_LOCK
                                + ")::text || ' ' || (SELECT count(*) FROM reviews)::text AS v");

                if (state.startsWith("1 ")) {
                    seenHeld = true;
                } else if (seenHeld) {
                    observedAfterRelease.set(state);

                    return;
                }
            }
        });

        watcher.start();
        var outcome = reviews.tryWithPrLock("o/r",
                                            7,
                                            () -> delayed(2000).flatMap(_ -> reviews.sending(newReview())))
                             .await(PgFixture.WAIT);

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(text("SELECT count(*)::text AS v FROM reviews")).as("written before the call answered")
                  .isEqualTo("1");
        watcher.join(TimeUnit.SECONDS.toMillis(40));
        assertThat(observedAfterRelease.get()).as("lock count and row count when the lock was first seen released")
                  .isEqualTo("0 1");
    }

    @Test
    void listing_isScopedToTheRepo_aSamePrNumberInAnotherRepoIsNotReturned() {
        reviews.sending(newReview()).await(PgFixture.WAIT).unwrap();
        reviews.sending(NewReview.newReview("other/x",
                                            7,
                                            SHA,
                                            "APPROVE",
                                            verdict,
                                            "v-a",
                                            none(),
                                            "fresh",
                                            DIGEST))
               .await(PgFixture.WAIT)
               .unwrap();
        assertThat(reviews.reviews("o/r", 7).await(PgFixture.WAIT).unwrap()).hasSize(1);
        assertThat(reviews.reviews("other/x", 7).await(PgFixture.WAIT).unwrap()).hasSize(1);
        assertThat(reviews.reviews("third/y", 7).await(PgFixture.WAIT).unwrap()).isEmpty();
    }

    @Test
    void listing_isOrderedBySendingAt_notByInsertion() {
        reviews.sending(newReview()).await(PgFixture.WAIT).unwrap();
        reviews.sending(NewReview.newReview("o/r",
                                            7,
                                            SHA,
                                            "REQUEST_CHANGES",
                                            verdict,
                                            "v-a",
                                            none(),
                                            "fresh",
                                            DIGEST))
               .await(PgFixture.WAIT)
               .unwrap();
        // the first-inserted row is the later one; updated in this order the heap order is also first-inserted first
        execute("UPDATE reviews SET sending_at = '2026-11-05T13:00:00Z' WHERE event = 'APPROVE'").unwrap();
        execute("UPDATE reviews SET sending_at = '2026-11-05T12:00:00Z' WHERE event = 'REQUEST_CHANGES'").unwrap();
        var listing = reviews.reviews("o/r", 7).await(PgFixture.WAIT).unwrap();

        assertThat(listing).extracting(ReviewListing::sendingAt)
                  .containsExactly(Instant.parse("2026-11-05T12:00:00Z"),
                                   Instant.parse("2026-11-05T13:00:00Z"));
    }

    private static Promise<Unit> delayed(long millis) {
        var later = Promise.<Unit> promise();

        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            later.succeed(Unit.unit());
        });

        return later;
    }

    private void awaitLocks(String count) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        while (!heldLocks().equals(count) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(heldLocks()).isEqualTo(count);
    }

    /// Advisory locks of the review key held by ANY session, read from `pg_locks` (not by trying to take the lock,
    /// which a re-entrant session or a pooled connection would answer differently).
    private String heldLocks() {
        return text("SELECT count(*)::text AS v FROM pg_locks WHERE locktype = 'advisory' AND granted AND database = (SELECT oid FROM pg_database WHERE datname = current_database()) AND classid = " + PgReviewStore.REVIEW_LOCK);
    }

    private NewReview newReview() {
        return NewReview.newReview("o/r",
                                   7,
                                   SHA,
                                   "APPROVE",
                                   verdict,
                                   "v-a",
                                   some(new NewReview.Carry(TIP, "main")),
                                   "carry-proven",
                                   DIGEST);
    }

    private Result<Unit> execute(String sql) {
        return Sql.execute(harness.raw.executor(),
                           sql)
                  .await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return Sql.query(harness.raw.executor(),
                         sql)
                  .map(resultSet -> Sql.first(resultSet)
                                       .unwrap()
                                       .getString("v"))
                  .await(PgFixture.WAIT)
                  .unwrap();
    }
}
