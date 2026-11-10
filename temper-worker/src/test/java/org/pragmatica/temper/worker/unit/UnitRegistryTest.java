package org.pragmatica.temper.worker.unit;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.Fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

class UnitRegistryTest {
    private final org.pragmatica.temper.domain.model.UnitRef ref = Fixtures.ref(1, "main");

    @Test
    void collection_isShared_andAFailureIsForgottenSoTheNextCollectRetries() throws Exception {
        var registry = UnitRegistry.unitRegistry();
        var attempts = new AtomicInteger();

        Fixtures.failure(registry.collection(ref, 1, () -> {
                                     attempts.incrementAndGet();
                                     return Causes.cause("boom").promise();
                                 })
                                 .await());
        for (var wait = 0; wait < 100 && registry.collections()
                                                 .containsKey(key(1)); wait++) {
            Thread.sleep(10);
        }

        var collected = Collected.collected(new Message.UnitExited(1, "main", 1, 0, Fixtures.none(), null, null, 0, 0, 0, Fixtures.none(), Fixtures.none()),
                                            java.util.List.of(),
                                            java.util.List.of());
        var first = registry.collection(ref, 1, () -> {
            attempts.incrementAndGet();
            return Promise.success(collected);
        });
        var second = registry.collection(ref, 1, () -> {
            attempts.incrementAndGet();
            return Promise.success(collected);
        });

        assertThat(ok(first.await())).isSameAs(ok(second.await()));
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void start_isShared_whileInFlight_andForgottenWhenDone() throws Exception {
        var registry = UnitRegistry.unitRegistry();
        var pending = Promise.<StartReply> promise();
        var attempts = new AtomicInteger();

        var first = registry.start(ref, 1, () -> {
            attempts.incrementAndGet();
            return pending;
        });
        var second = registry.start(ref, 1, () -> {
            attempts.incrementAndGet();
            return Promise.success(StartReply.startReply(new Message.Shutdown()));
        });

        assertThat(registry.isStarting(ref)).isTrue();
        pending.succeed(StartReply.startReply(new Message.FactsRequest()));
        assertThat(ok(second.await())).isEqualTo(ok(first.await()));
        assertThat(attempts.get()).isEqualTo(1);
        for (var wait = 0; wait < 100 && registry.isStarting(ref); wait++) {
            Thread.sleep(10);
        }
        assertThat(registry.isStarting(ref)).isFalse();
    }

    /// One `GrantWatcher` per unit: a second hold of the unit does not start a second watcher.
    @Test
    void startHolding_isTrueOnlyForTheFirstHold() {
        var registry = UnitRegistry.unitRegistry();

        assertThat(registry.startHolding(ref, 1)).isTrue();
        assertThat(registry.startHolding(ref, 1)).isFalse();
        assertThat(registry.startHolding(ref, 2)).isFalse();
    }

    @Test
    void forget_cancelsTheTimer_andDropsEverything() {
        var registry = UnitRegistry.unitRegistry();
        var timers = new Fixtures.ManualTimers();

        registry.replaceTimer(ref, timers.schedule(() -> {}, org.pragmatica.lang.io.TimeSpan.timeSpan(1).hours()));
        registry.startWatching(ref, 1, "c");
        registry.cancel(ref, 1);
        registry.forget(ref, 1);

        assertThat(timers.scheduled()
                         .getFirst()
                         .cancelled()[0]).isTrue();
        assertThat(registry.running()).isEmpty();
        assertThat(registry.isCancelled(ref, 1)).isFalse();
    }

    /// A start is no longer in flight when its promise completes, so a retry made after it starts afresh (here,
    /// chained on the first start, it must not join the failed one).
    @Test
    void start_aRetryAfterAFailedStart_startsAfresh() {
        var registry = UnitRegistry.unitRegistry();
        var failing = Promise.<StartReply> promise();
        var retried = registry.start(ref, 1, () -> failing)
                              .fold(_ -> registry.start(ref, 1, () -> Promise.success(StartReply.startReply(new Message.Shutdown()))));

        failing.fail(Causes.cause("start failed"));

        assertThat(ok(retried.await())).isEqualTo(StartReply.startReply(new Message.Shutdown()));
    }

    @Test
    void isStarting_isFalse_onceTheStartCompletes() {
        var registry = UnitRegistry.unitRegistry();
        var pending = Promise.<StartReply> promise();
        var startingAtDone = registry.start(ref, 1, () -> pending)
                                     .map(_ -> registry.isStarting(ref));

        pending.succeed(StartReply.startReply(new Message.Shutdown()));

        assertThat(ok(startingAtDone.await())).isFalse();
    }

    /// Protocol v3: a `StartUnit` of another attempt never joins the in-flight start of the unit, so it never gets
    /// that attempt's reply; the same attempt still joins.
    @Test
    void start_ofAnotherAttempt_neverJoinsTheInFlightStart() {
        var registry = UnitRegistry.unitRegistry();
        var first = Promise.<StartReply> promise();
        var second = StartReply.startReply(new Message.Shutdown());

        registry.start(ref, 1, () -> first);
        var other = registry.start(ref, 2, () -> Promise.success(second));
        var retried = registry.start(ref, 1, () -> Promise.success(second));

        assertThat(ok(other.await(org.pragmatica.lang.io.TimeSpan.timeSpan(2).seconds()))).as("attempt 2 never waits for attempt 1's start")
                                                                                 .isEqualTo(second);
        assertThat(registry.isStarting(ref, 1)).isTrue();
        assertThat(registry.isStarting(ref)).isTrue();
        assertThat(registry.isStarting(ref, 2)).isFalse();
        first.succeed(StartReply.startReply(new Message.FactsRequest()));
        assertThat(ok(retried.await())).as("the same attempt joins").isEqualTo(StartReply.startReply(new Message.FactsRequest()));
    }

    /// A turn is dropped once it resolves, whatever its outcome: the turn map never grows with the units served.
    @Test
    void inTurn_leavesNoTurnBehind_afterASucceededOrAFailedTurn() {
        var registry = UnitRegistry.unitRegistry();

        assertThat(ok(registry.inTurn(ref, () -> Promise.success(1))
                              .await())).isEqualTo(1);
        assertThat(registry.turns()).isEmpty();

        Fixtures.failure(registry.inTurn(ref, () -> Causes.cause("start failed").<Integer> promise())
                                 .await());
        assertThat(registry.turns()).isEmpty();
    }

    /// The watcher's exit processing needs the watch to be its own: with the unit directory unchanged but the watch
    /// dropped (a forget, an ack), none is started.
    @Test
    void watchedCollection_isNone_onceTheWatchIsDropped() {
        var registry = UnitRegistry.unitRegistry();
        var calls = new AtomicInteger();

        registry.startWatching(ref, 1, "c");
        registry.stopWatching(ref, "c");

        assertThat(registry.watchedCollection(ref, 1, "c", () -> {
            calls.incrementAndGet();
            return Promise.<Collected> promise();
        }).isPresent()).isFalse();
        assertThat(calls.get()).isZero();
        assertThat(registry.collections()).isEmpty();
    }

    /// A forget of an earlier attempt (a late ack, a stale discard) leaves the timer, watch, collection and held
    /// container of the attempt that owns them now.
    @Test
    void forget_ofAnEarlierAttempt_keepsWhatALaterAttemptOwns() {
        var registry = UnitRegistry.unitRegistry();
        var timers = new Fixtures.ManualTimers();

        registry.replaceTimer(ref, timers.schedule(() -> {}, org.pragmatica.lang.io.TimeSpan.timeSpan(1).hours()));
        registry.startWatching(ref, 2, "c2");
        registry.hold(ref, "c2");
        registry.collection(ref, 2, Promise::promise);

        registry.forget(ref, 1);
        assertThat(timers.scheduled()
                         .getFirst()
                         .cancelled()[0]).isFalse();
        assertThat(registry.watches(ref, "c2")).isTrue();
        assertThat(registry.held()).hasSize(1);
        assertThat(registry.collections()).containsKey(key(2));

        var forgotten = registry.forget(ref, 2);

        assertThat(forgotten.isResolved()).as("waits for attempt 2's processing").isFalse();
        assertThat(registry.watches(ref, "c2")).isFalse();
        assertThat(registry.collections()).isEmpty();
        assertThat(registry.owners()).isEmpty();
    }

    /// A collection started for an attempt makes that attempt the owner, as a watch does.
    @Test
    void forget_ofAnEarlierAttempt_keepsALaterAttemptsCollection_withNoWatch() {
        var registry = UnitRegistry.unitRegistry();

        registry.collection(ref, 2, Promise::promise);

        assertThat(registry.forget(ref, 1)
                           .await(org.pragmatica.lang.io.TimeSpan.timeSpan(1).seconds())
                           .isSuccess()).as("nothing of attempt 1 to wait for").isTrue();
        assertThat(registry.collections()).containsKey(key(2));
    }

    /// A late `Collect(n)` after n was forgotten leaves n's processing behind; attempt n+1's watcher never joins it,
    /// it runs its own. The next forget of n drops n's and leaves n+1's.
    @Test
    void collection_ofALaterAttempt_neverJoinsAnEarlierAttemptsCollection() {
        var registry = UnitRegistry.unitRegistry();
        var stale = Promise.<Collected> promise();
        var calls = new AtomicInteger();

        registry.collection(ref, 1, () -> stale);
        registry.startWatching(ref, 2, "c2");
        var own = registry.watchedCollection(ref, 2, "c2", () -> {
            calls.incrementAndGet();
            return Promise.<Collected> promise();
        });

        assertThat(own.isPresent()).isTrue();
        assertThat(calls.get()).as("attempt 2 runs its own processing").isEqualTo(1);
        assertThat(own.map(promise -> promise != stale).or(false)).isTrue();

        registry.forget(ref, 1);

        assertThat(registry.collections()).containsOnlyKeys(key(2));
        assertThat(registry.watches(ref, "c2")).isTrue();
    }

    /// A late `Collect(1)` after attempt 2's watch started leaves attempt 2 the owner, so a forget of attempt 1 keeps
    /// attempt 2's timer and watch.
    @Test
    void collection_ofAnEarlierAttempt_afterALaterAttemptsWatch_leavesTheLaterAttemptTheOwner() {
        var registry = UnitRegistry.unitRegistry();
        var timers = new Fixtures.ManualTimers();

        registry.replaceTimer(ref, timers.schedule(() -> {}, org.pragmatica.lang.io.TimeSpan.timeSpan(1).hours()));
        registry.startWatching(ref, 2, "c2");
        registry.collection(ref, 1, Promise::promise);

        assertThat(registry.owners()).containsEntry(ref, 2);

        registry.forget(ref, 1);

        assertThat(registry.watches(ref, "c2")).isTrue();
        assertThat(timers.scheduled()
                         .getFirst()
                         .cancelled()[0]).isFalse();
    }

    /// Attempts only grow: a forget of attempt 2 also drops a stale processing of attempt 1 (a late `Collect(1)`)
    /// and waits for it.
    @Test
    void forget_dropsTheCollectionOfAnEarlierAttempt_too() {
        var registry = UnitRegistry.unitRegistry();
        var stale = Promise.<Collected> promise();

        registry.collection(ref, 1, () -> stale);

        var forgotten = registry.forget(ref, 2);

        assertThat(registry.collections()).isEmpty();
        assertThat(forgotten.isResolved()).as("waits for attempt 1's processing").isFalse();
        stale.fail(Causes.cause("collect failed"));
        assertThat(forgotten.await(org.pragmatica.lang.io.TimeSpan.timeSpan(2).seconds())
                            .isSuccess()).isTrue();
    }

    /// A failed collection leaves its owner tag behind (the next `Collect` retries it); the job GC drops the tags of
    /// a job none of whose units is known any more, and only of that job.
    @Test
    void released_dropsTheOwnerTagLeftByAFailedCollection() throws Exception {
        var registry = UnitRegistry.unitRegistry();
        var other = Fixtures.ref(2, "main");

        Fixtures.failure(registry.collection(ref, 1, () -> Causes.cause("boom").promise())
                                 .await());
        registry.startWatching(other, 1, "c");
        for (var wait = 0; wait < 100 && registry.collections()
                                                 .containsKey(key(1)); wait++) {
            Thread.sleep(10);
        }

        assertThat(registry.owners()).containsEntry(ref, 1);
        assertThat(registry.released(ref.job())).isTrue();
        assertThat(registry.owners()).containsOnlyKeys(other);
    }

    /// A job with a unit still known here is not released, and keeps its owner tags.
    @Test
    void released_isFalse_andKeepsTheTags_whileAUnitOfTheJobIsKnown() {
        var registry = UnitRegistry.unitRegistry();

        registry.startWatching(ref, 1, "c");

        assertThat(registry.released(ref.job())).isFalse();
        assertThat(registry.owners()).containsEntry(ref, 1);
    }

    /// A start of another unit is not this unit's start, whatever its attempt.
    @Test
    void isStarting_isFalse_whileOnlyAnotherUnitIsStarting() {
        var registry = UnitRegistry.unitRegistry();

        registry.start(Fixtures.ref(2, "main"), 1, Promise::promise);

        assertThat(registry.isStarting(ref)).isFalse();
        assertThat(registry.isStarting(Fixtures.ref(2, "main"))).isTrue();
    }

    /// The job GC keeps a job whose unit is starting or being collected, whatever the attempt.
    @Test
    void knows_aJobByItsStartingOrCollectedUnit() {
        var starting = UnitRegistry.unitRegistry();
        var collecting = UnitRegistry.unitRegistry();

        starting.start(ref, 2, Promise::promise);
        collecting.collection(ref, 2, Promise::promise);

        assertThat(starting.knows(ref.job())).isTrue();
        assertThat(collecting.knows(ref.job())).isTrue();
        assertThat(UnitRegistry.unitRegistry()
                               .knows(ref.job())).isFalse();
    }

    private UnitRegistry.AttemptKey key(int attempt) {
        return new UnitRegistry.AttemptKey(ref, attempt);
    }
}
