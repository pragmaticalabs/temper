package org.pragmatica.temper.store.pg;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.StoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Event ordering (m0-design §5.3, A5 store half): writers commit in `seq` order because every event insert takes
/// `pg_advisory_xact_lock(70700001)` first. A reader that advances its cursor to the highest `seq` it saw must
/// therefore never skip an event. The writers hold their transaction open for a random 0-20 ms after inserting,
/// which is exactly the window in which, without the lock, a later `seq` commits before an earlier one.
@Tag("pg")
class EventOrderIT {
    private static final EventPayload PAYLOAD = EventPayload.eventPayload(some("0cf563192a0cf563192a0cf563192a0cf563192a"),
                                                                         some("main"),
                                                                         some("mvn"),
                                                                         none(),
                                                                         none(),
                                                                         none(),
                                                                         none(),
                                                                         none());
    private StoreHarness harness;
    private final List<Db> writers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
    }

    @AfterEach
    void tearDown() {
        writers.forEach(db -> db.close().await(PgFixture.WAIT));
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void after_neverSkipsAnEvent_underConcurrentWriters() {
        IntStream.range(0, 6).forEach(_ -> writers.add(PgFixture.db(harness.config)));
        var done = new AtomicBoolean();
        var seen = new TreeSet<Long>();
        var reader = Thread.ofVirtual().start(() -> read(seen, done));
        var writes = writers.stream().map(this::writeMany).toList();

        Promise.allOf(writes).await(PgFixture.WAIT).unwrap().forEach(result -> result.unwrap());
        done.set(true);
        join(reader);
        drain(seen);

        var all = allSeqs();

        assertThat(all).hasSize(6 * 40);
        assertThat(seen).as("reader missed events committed behind its cursor").containsExactlyElementsOf(all);
    }

    @Test
    void after_filtersKinds_andLimits() {
        var events = harness.store.events();
        var first = events.append(none(), EventRow.Kind.QUEUED, PAYLOAD).await(PgFixture.WAIT).unwrap();

        events.append(none(), EventRow.Kind.FINISHED, PAYLOAD).await(PgFixture.WAIT).unwrap();
        events.append(none(), EventRow.Kind.CANCELLED, PAYLOAD).await(PgFixture.WAIT).unwrap();

        var terminal = events.after(first - 1, Set.of("finished", "cancelled"), 10).await(PgFixture.WAIT).unwrap();
        var all = events.after(first - 1, Set.of(), 2).await(PgFixture.WAIT).unwrap();

        assertThat(terminal.stream().map(EventRow::kind).toList()).containsExactly(EventRow.Kind.FINISHED, EventRow.Kind.CANCELLED);
        assertThat(all.stream().map(EventRow::seq).toList()).containsExactly(first, first + 1);
        assertThat(all.getFirst().payload()).isEqualTo(PAYLOAD);
    }

    @Test
    void subscriber_startsAtMaxSeq_andAckOnlyMovesForward() {
        var events = harness.store.events();

        assertThat(events.floor().await(PgFixture.WAIT).unwrap()).isZero();
        var empty = events.subscriber("fresh.one").await(PgFixture.WAIT).unwrap();

        assertThat(empty.cursor()).isZero();
        var last = IntStream.range(0, 3)
                            .mapToObj(_ -> events.append(none(), EventRow.Kind.QUEUED, PAYLOAD).await(PgFixture.WAIT).unwrap())
                            .toList()
                            .getLast();
        var cto = events.subscriber("cto").await(PgFixture.WAIT).unwrap();

        assertThat(cto.cursor()).isEqualTo(last);
        assertThat(cto.selectors()).isEmpty();

        events.ack("cto", last + 5).await(PgFixture.WAIT).unwrap();
        events.ack("cto", last - 1).await(PgFixture.WAIT).unwrap();
        assertThat(events.subscriber("cto").await(PgFixture.WAIT).unwrap().cursor()).isEqualTo(last + 5);

        events.ack("nobody", 1)
              .await(PgFixture.WAIT)
              .onSuccess(_ -> fail("expected SubscriberNotFound"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.SubscriberNotFound.class));
    }

    private Promise<List<Unit>> writeMany(Db db) {
        return Sql.sequence(IntStream.range(0, 40).boxed().toList(), _ -> writeOne(db));
    }

    private static Promise<Unit> writeOne(Db db) {
        var holdMs = ThreadLocalRandom.current().nextInt(0, 21);

        return db.inTransaction(tx -> EventWriter.append(tx, new PendingEvent(none(), EventRow.Kind.QUEUED, PAYLOAD))
                                                 .flatMap(_ -> Sql.execute(tx, "SELECT pg_sleep($1)", holdMs / 1000.0)));
    }

    private void read(TreeSet<Long> seen, AtomicBoolean done) {
        while (!done.get()) {
            drain(seen);
        }
    }

    /// Advances the cursor to the highest seq seen, the way a subscriber does.
    private void drain(TreeSet<Long> seen) {
        var cursor = seen.isEmpty() ? 0L : seen.last();
        var batch = harness.store.events().after(cursor, Set.of(), 1000).await(PgFixture.WAIT).unwrap();

        batch.forEach(event -> seen.add(event.seq()));
    }

    private List<Long> allSeqs() {
        return Sql.rows(harness.raw.executor().completeQuery("SELECT seq FROM events ORDER BY seq").await(PgFixture.WAIT).unwrap())
                  .stream()
                  .map(row -> row.getLong("seq"))
                  .toList();
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
