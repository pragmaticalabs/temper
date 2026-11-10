package org.pragmatica.temper.store.pg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventStore;
import org.pragmatica.temper.store.api.Pending;
import org.pragmatica.temper.store.api.RetentionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Unit.unit;

/// The event floor (m1-design §4.4, F7): retention deletes events and raises the floor in one transaction, and a
/// reader reads the floor and the events in one statement, so a lagging subscriber is always told about deleted
/// events (`Gap`) and never skips one silently. Each test gets its own database: the floor is global.
@Tag("pg")
class EventFloorIT {
    private static final EventPayload PAYLOAD = EventPayload.eventPayload(none(), none(), none(), none(), none(), none(), none(), none());
    private StoreHarness harness;
    private EventStore events;
    private RetentionStore retention;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        events = harness.store.events();
        retention = harness.store.retention();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void pending_freshDatabase_floorZero_noGap() {
        assertThat(events.floor().await(PgFixture.WAIT).unwrap()).isZero();
        assertThat(pending(0)).isEqualTo(new Pending.Events(List.of()));
    }

    @Test
    void pending_afterGcDeletedEverything_laggingSubscriberGetsGap() {
        var seqs = append(3);
        var cursor = seqs.getFirst() - 1;

        assertThat(retention.deleteEventsUpTo(seqs.getLast()).await(PgFixture.WAIT).unwrap()).isEqualTo(3);
        assertThat(events.floor().await(PgFixture.WAIT).unwrap()).isEqualTo(seqs.getLast());
        assertThat(pending(cursor)).isEqualTo(new Pending.Gap(cursor + 1, seqs.getLast()));
    }

    @Test
    void pending_atTheFloor_readsTheEventsAfterIt() {
        var seqs = append(4);

        retention.deleteEventsUpTo(seqs.get(1)).await(PgFixture.WAIT).unwrap();

        assertThat(pending(seqs.getFirst() - 1)).isEqualTo(new Pending.Gap(seqs.getFirst(), seqs.get(1)));
        assertThat(seqsOf(pending(seqs.get(1)))).containsExactly(seqs.get(2), seqs.get(3));
    }

    @Test
    void subscriber_createdAfterGcEmptiedTheTable_startsAtTheFloor_noSpuriousGap() {
        var seqs = append(3);

        retention.deleteEventsUpTo(seqs.getLast()).await(PgFixture.WAIT).unwrap();
        var subscriber = events.subscriber("after.gc").await(PgFixture.WAIT).unwrap();

        assertThat(subscriber.cursor()).isEqualTo(seqs.getLast());
        assertThat(pending(subscriber.cursor())).isEqualTo(new Pending.Events(List.of()));
        var next = append(1).getFirst();

        assertThat(seqsOf(pending(subscriber.cursor()))).containsExactly(next);
    }

    @Test
    void deleteEventsUpTo_neverLowersTheFloor() {
        var seqs = append(3);

        retention.deleteEventsUpTo(seqs.getLast()).await(PgFixture.WAIT).unwrap();
        retention.deleteEventsUpTo(seqs.getFirst()).await(PgFixture.WAIT).unwrap();

        assertThat(events.floor().await(PgFixture.WAIT).unwrap()).isEqualTo(seqs.getLast());
    }

    /// A floor above the last allocated `seq` would hide the next event below it: the floor stops at that `seq`.
    @Test
    void deleteEventsUpTo_beyondTheLastSeq_floorStopsThere_nextEventIsRead() {
        var last = append(2).getLast();

        retention.deleteEventsUpTo(last + 1_000).await(PgFixture.WAIT).unwrap();
        assertThat(events.floor().await(PgFixture.WAIT).unwrap()).isEqualTo(last);
        var next = append(1).getFirst();

        assertThat(seqsOf(pending(last))).containsExactly(next);
    }

    @Test
    void pending_filtersKinds_andLimits() {
        var first = events.append(none(), EventRow.Kind.QUEUED, PAYLOAD).await(PgFixture.WAIT).unwrap();

        events.append(none(), EventRow.Kind.FINISHED, PAYLOAD).await(PgFixture.WAIT).unwrap();
        events.append(none(), EventRow.Kind.CANCELLED, PAYLOAD).await(PgFixture.WAIT).unwrap();

        var terminal = events.pending(first - 1, Set.of("finished", "cancelled"), 10).await(PgFixture.WAIT).unwrap();
        var limited = events.pending(first - 1, Set.of(), 2).await(PgFixture.WAIT).unwrap();

        assertThat(rowsOf(terminal).stream().map(EventRow::kind).toList()).containsExactly(EventRow.Kind.FINISHED, EventRow.Kind.CANCELLED);
        assertThat(seqsOf(limited)).containsExactly(first, first + 1);
        assertThat(rowsOf(limited).getFirst().payload()).isEqualTo(PAYLOAD);
    }

    /// The GC takes the event order lock: with an event in flight (lock held, row inserted, not committed) it waits,
    /// and once the event commits it is deleted with the rest, so no stored event ever sits at or below the floor.
    @Test
    void deleteEventsUpTo_waitsForAnEventInFlight_noStoredEventBelowTheFloor() throws InterruptedException {
        append(1);
        var release = Promise.<Unit> promise();
        var inserted = Promise.<Long> promise();
        var writer = harness.raw.inTransaction(tx -> Sql.execute(tx, "SELECT pg_advisory_xact_lock($1)", EventWriter.ORDER_LOCK)
                                                        .flatMap(_ -> Sql.query(tx, "INSERT INTO events (kind, payload) VALUES ('queued', '{}') RETURNING seq"))
                                                        .map(resultSet -> resultSet.index(0).getLong("seq"))
                                                        .onSuccess(inserted::succeed)
                                                        .flatMap(seq -> release.map(_ -> seq)));
        var inFlight = inserted.await(PgFixture.WAIT).unwrap();
        var gc = retention.deleteEventsUpTo(inFlight);

        Thread.sleep(500);
        assertThat(gc.isResolved()).as("the GC waits for the event in flight").isFalse();
        release.succeed(unit());
        writer.await(PgFixture.WAIT).unwrap();
        gc.await(PgFixture.WAIT).unwrap();

        var floor = events.floor().await(PgFixture.WAIT).unwrap();

        assertThat(floor).isEqualTo(inFlight);
        assertThat(harness.count("SELECT count(*) AS n FROM events WHERE seq <= $1", floor)).isZero();
    }

    /// 200 rounds of a GC racing readers at jittered offsets. Every read at `cursor = a - 1` must answer either
    /// the gap `a..a` (the GC committed first) or events starting exactly at `a` (it did not); events starting
    /// above `a` with no gap would be a silent skip. Reading the floor in a separate statement lets a GC commit
    /// land between the two reads, which this loop catches.
    @Test
    void pending_concurrentWithGc_neverSkipsWithoutAGap() {
        var violations = new ArrayList<String>();

        for (int round = 0; round < 200; round++) {
            var seqs = append(2);
            var a = seqs.getFirst();
            var gc = CompletableFuture.supplyAsync(() -> retention.deleteEventsUpTo(a).await(PgFixture.WAIT).unwrap());
            var readers = IntStream.range(0, 6)
                                   .mapToObj(_ -> CompletableFuture.supplyAsync(() -> jitteredRead(a - 1)))
                                   .toList();

            gc.join();
            readers.stream()
                   .map(CompletableFuture::join)
                   .filter(read -> skipped(read, a))
                   .forEach(read -> violations.add("cursor " + (a - 1) + " read " + read));
        }
        assertThat(violations).isEmpty();
    }

    private Pending jitteredRead(long cursor) {
        sleepMicros(ThreadLocalRandom.current().nextInt(0, 4_000));
        return pending(cursor);
    }

    private static boolean skipped(Pending read, long a) {
        return switch (read) {
            case Pending.Gap gap -> gap.from() != a;
            case Pending.Events found -> found.rows().isEmpty() || found.rows().getFirst().seq() != a;
        };
    }

    private static void sleepMicros(int micros) {
        try {
            Thread.sleep(micros / 1000, (micros % 1000) * 1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Long> append(int count) {
        return IntStream.range(0, count)
                        .mapToObj(_ -> events.append(none(), EventRow.Kind.QUEUED, PAYLOAD).await(PgFixture.WAIT).unwrap())
                        .toList();
    }

    private Pending pending(long cursor) {
        return events.pending(cursor, Set.of(), 100).await(PgFixture.WAIT).unwrap();
    }

    private static List<EventRow> rowsOf(Pending pending) {
        assertThat(pending).isInstanceOf(Pending.Events.class);
        return ((Pending.Events) pending).rows();
    }

    private static List<Long> seqsOf(Pending pending) {
        return rowsOf(pending).stream().map(EventRow::seq).toList();
    }
}
