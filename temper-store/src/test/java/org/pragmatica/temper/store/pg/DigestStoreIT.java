package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.store.api.DigestQuery;
import org.pragmatica.temper.store.api.DigestSnapshot;
import org.pragmatica.temper.store.api.DigestStore;
import org.pragmatica.temper.store.api.EventCursor;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventWindow;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.Pending;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredHost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The digest's store read (dashboard design §2): one `REPEATABLE READ, READ ONLY` transaction, and the cursor
/// guarantee "every event with `seq <= cursor` was shown or reported as a gap". Each test gets its own database: the
/// event floor is global.
@Tag("pg")
class DigestStoreIT {
    private static final EventPayload PAYLOAD = EventPayload.eventPayload(none(), none(), none(), none(), none(), none(), none(), none());
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

    /// Six writers append through the production [EventWriter] and hold their transaction open 0-20 ms after the
    /// insert, the window in which, without the order lock, a later `seq` commits before an earlier one. A reader
    /// follows the digest cursor with a small `max`, so it truncates (`more`) as well as catching up. Every committed
    /// event must be shown exactly once.
    @Test
    void snapshot_underConcurrentAppends_cursorNeverSkipsAnEvent() {
        IntStream.range(0, 6).forEach(_ -> writers.add(PgFixture.db(harness.config)));
        var done = new AtomicBoolean();
        var shown = new ArrayList<Long>();
        var reader = Thread.ofVirtual().start(() -> follow(shown, done));
        var writes = writers.stream().map(DigestStoreIT::writeMany).toList();

        Promise.allOf(writes).await(PgFixture.WAIT).unwrap().forEach(result -> result.unwrap());
        done.set(true);
        join(reader);

        assertThat(allSeqs()).hasSize(6 * 40);
        assertThat(shown).as("each event shown once, in seq order, none skipped").containsExactlyElementsOf(allSeqs());
    }

    @Test
    void snapshot_atMax_truncates_setsMore_andHandsBackTheLastShownSeq() {
        var seqs = append(5);
        var since = seqs.getFirst() - 1;

        var first = window(some(cursorAt(since)), 2);
        var second = window(some(first.cursor()), 2);
        var third = window(some(second.cursor()), 2);
        var idle = window(some(third.cursor()), 2);

        assertThat(seqsOf(first)).containsExactly(seqs.get(0), seqs.get(1));
        assertThat(first.more()).isTrue();
        assertThat(first.cursor().seq()).isEqualTo(seqs.get(1));
        assertThat(seqsOf(second)).containsExactly(seqs.get(2), seqs.get(3));
        assertThat(second.more()).isTrue();
        assertThat(seqsOf(third)).containsExactly(seqs.get(4));
        assertThat(third.more()).as("exactly the rest: no more").isFalse();
        assertThat(third.cursor().seq()).isEqualTo(seqs.get(4));
        assertThat(seqsOf(idle)).isEmpty();
        assertThat(idle.more()).isFalse();
        assertThat(idle.cursor()).as("nothing new: the cursor stays").isEqualTo(third.cursor());
    }

    @Test
    void snapshot_belowTheFloor_reportsTheGap_thenShowsTheEventsAfterIt() {
        var seqs = append(4);

        harness.store.retention().deleteEventsUpTo(seqs.get(1)).await(PgFixture.WAIT).unwrap();
        var window = window(some(cursorAt(seqs.getFirst() - 1)), 10);

        assertThat(window.gap()).isEqualTo(some(new Pending.Gap(seqs.getFirst(), seqs.get(1))));
        assertThat(seqsOf(window)).containsExactly(seqs.get(2), seqs.get(3));
        assertThat(window.cursor().seq()).isEqualTo(seqs.get(3));
        assertThat(window(some(cursorAt(seqs.get(1))), 10).gap()).as("at the floor: no gap").isEqualTo(none());
    }

    /// Retention emptied the table: the cursor moves to the floor, past the gap, with nothing shown.
    @Test
    void snapshot_belowTheFloorOfAnEmptyTable_reportsTheGap_cursorAtTheFloor() {
        var seqs = append(3);

        harness.store.retention().deleteEventsUpTo(seqs.getLast()).await(PgFixture.WAIT).unwrap();
        var window = window(some(cursorAt(seqs.getFirst() - 1)), 10);

        assertThat(window.gap()).isEqualTo(some(new Pending.Gap(seqs.getFirst(), seqs.getLast())));
        assertThat(seqsOf(window)).isEmpty();
        assertThat(window.cursor()).isEqualTo(EventCursor.noEvent(seqs.getLast()));
    }

    @Test
    void snapshot_withoutSince_startsAtTheHead_showsNothingOlder() {
        var seqs = append(3);
        var window = window(none(), 10);

        assertThat(seqsOf(window)).isEmpty();
        assertThat(window.cursor()).isEqualTo(cursorAt(seqs.getLast()));
        assertThat(window.gap()).isEqualTo(none());
        var next = append(1).getFirst();

        assertThat(seqsOf(window(some(window.cursor()), 10))).containsExactly(next);
    }

    /// A cursor this database never committed (another database, or one restored from an older dump) would hide every
    /// event committed below it: refused.
    @Test
    void snapshot_cursorAheadOfTheLog_fails() {
        var last = append(2).getLast();

        harness.store.digest()
               .snapshot(query(some(new EventCursor(last + 1, some(1L))), 10))
               .await(PgFixture.WAIT)
               .onSuccess(_ -> fail("expected CursorAhead"))
               .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.CursorAhead.FACTORY.apply((last + 1) + ".1", last)));
        assertThat(window(some(cursorAt(last)), 10).cursor()).as("at the head itself: accepted").isEqualTo(cursorAt(last));
    }

    /// The read-only part is Postgres's, not a convention: an INSERT, an UPDATE and a DELETE inside the digest's
    /// transaction each fail with SQLSTATE 25006 (read_only_sql_transaction), and nothing lands.
    @Test
    void inSnapshot_aWriteFails_readOnlyEnforcedByPostgres() {
        append(1);
        var events = harness.count("SELECT count(*) AS n FROM events");

        List.of("INSERT INTO events (kind, payload) VALUES ('queued', '{}')",
                "UPDATE event_floor SET floor_seq = floor_seq + 1 WHERE id = 1",
                "DELETE FROM events")
            .forEach(this::writeInsideTheSnapshotFails);
        assertThat(harness.count("SELECT count(*) AS n FROM events")).isEqualTo(events);
        assertThat(harness.count("SELECT floor_seq AS n FROM event_floor WHERE id = 1")).isZero();
    }

    /// The boundary: a reader one below the floor gets a gap of exactly the floor's event.
    @Test
    void snapshot_justBelowTheFloor_gapOfTheFloorEventAlone() {
        var seqs = append(4);

        harness.store.retention().deleteEventsUpTo(seqs.get(1)).await(PgFixture.WAIT).unwrap();
        var window = window(some(cursorAt(seqs.getFirst())), 10);

        assertThat(window.gap()).isEqualTo(some(new Pending.Gap(seqs.get(1), seqs.get(1))));
        assertThat(seqsOf(window)).containsExactly(seqs.get(2), seqs.get(3));
    }

    /// The verifier's restore: a reader holds 5; the database goes back to a dump taken at head 2 (rows and sequence);
    /// events 3..6 commit anew. Before they do, the cursor is ahead (400); once they have, the event at 5 is another
    /// one and the read is refused (CURSOR_FOREIGN), where the cursor alone would have answered 6 with no gap.
    @Test
    void snapshot_afterARestore_cursorFromTheOldLogIsRefused() {
        var seqs = append(5);
        var held = window(some(cursorAt(seqs.getFirst() - 1)), 10).cursor();

        assertThat(held.seq()).isEqualTo(seqs.get(4));
        harness.execute("DELETE FROM events WHERE seq > $1", seqs.get(1));
        harness.execute("SELECT setval(pg_get_serial_sequence('events', 'seq'), $1)", seqs.get(1));
        harness.store.digest()
               .snapshot(query(some(held), 10))
               .await(PgFixture.WAIT)
               .onSuccess(_ -> fail("expected CursorAhead before new events"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.CursorAhead.class));
        append(4);
        harness.store.digest()
               .snapshot(query(some(held), 10))
               .await(PgFixture.WAIT)
               .onSuccess(window -> fail("expected CursorForeign, got cursor=" + window.window().cursor().wire() + " gap="
                                         + window.window().gap() + " shown=" + window.window().events().size()))
               .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.CursorForeign.FACTORY.apply(held.wire())));
    }

    /// Above the floor with no row at the cursor's `seq` (removed outside retention): not this log either.
    @Test
    void snapshot_cursorEventMissingAboveTheFloor_isRefused() {
        var seqs = append(3);
        var held = cursorAt(seqs.get(1));

        harness.execute("DELETE FROM events WHERE seq = $1", seqs.get(1));
        harness.store.digest()
               .snapshot(query(some(held), 10))
               .await(PgFixture.WAIT)
               .onSuccess(_ -> fail("expected CursorForeign"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.CursorForeign.class));
    }

    /// REPEATABLE READ: an event committed between two statements of the digest's transaction is not seen by the
    /// second, so the parts of one digest cannot disagree.
    @Test
    void inSnapshot_aCommitBetweenTwoReads_isNotSeen() {
        append(2);
        var counts = PgDigestStore.inSnapshot(harness.raw,
                                              tx -> Sql.query(tx, "SELECT count(*) AS n FROM events")
                                                       .map(first -> first.index(0).getLong("n"))
                                                       .flatMap(before -> appendThenCount(tx, before)))
                                  .await(PgFixture.WAIT)
                                  .unwrap();

        assertThat(counts.get(1)).as("the second read sees the first one's snapshot").isEqualTo(counts.get(0));
        assertThat(harness.count("SELECT count(*) AS n FROM events")).isEqualTo(counts.get(0) + 1);
    }

    /// The other parts: the jobs the events name, the queue in grant order with its full count, the hosts with their
    /// active units, and the dumps.
    @Test
    void snapshot_readsJobsQueueHostsAndDumps() {
        var low = harness.submit(TestJobs.byRef("low", TestJobs.sha(), false, 2));
        var high = harness.submit(TestJobs.byRef("high", TestJobs.sha(), false, 9));
        var mid = harness.submit(TestJobs.byRef("mid", TestJobs.sha(), false, 5));
        var good = MaintenanceRun.maintenanceRun(MaintenanceKind.PG_DUMP,
                                                 Instant.parse("2026-10-15T03:30:00Z"),
                                                 Instant.parse("2026-10-15T03:31:00Z"),
                                                 true,
                                                 none(),
                                                 none(),
                                                 none());
        var failed = MaintenanceRun.maintenanceRun(MaintenanceKind.PG_DUMP,
                                                   Instant.parse("2026-10-16T03:30:00Z"),
                                                   Instant.parse("2026-10-16T03:31:00Z"),
                                                   false,
                                                   some("pg_dump rc=1"),
                                                   none(),
                                                   none());

        harness.store.maintenance().record(good).await(PgFixture.WAIT).unwrap();
        harness.store.maintenance().record(failed).await(PgFixture.WAIT).unwrap();
        harness.grantHead();
        var snapshot = snapshot(some(EventCursor.noEvent(0)), 10);

        assertThat(snapshot.jobs().keySet()).containsExactlyInAnyOrder(low, high, mid);
        assertThat(snapshot.jobs().get(high).priority()).isEqualTo(9);
        assertThat(snapshot.queue().stream().map(JobRow::id).toList()).as("grant order; the granted job is still QUEUED")
                                                                      .containsExactly(high, mid, low);
        assertThat(snapshot.queued()).isEqualTo(3);
        assertThat(snapshot.queue().getFirst().units()).hasSize(1);
        assertThat(snapshot.candidates().stream().map(QueuedUnit::unitId).toList())
            .as("the grant's candidates: the still-QUEUED units in grant order, the granted one not among them")
            .containsExactly(snapshot.queue().get(1).units().getFirst().id(), snapshot.queue().get(2).units().getFirst().id());
        assertThat(snapshot.hosts()).containsExactly(StoredHost.storedHost(TestJobs.HOST, "DOWN", 1));
        assertThat(snapshot.latestDump()).isEqualTo(some(failed));
        assertThat(snapshot.lastGoodDump()).isEqualTo(some(good));
        assertThat(snapshot.window().gap()).isEqualTo(none());
    }

    /// A RUNNING job with a unit still QUEUED (a sharded run with shards left) is waiting too: the grant's candidates
    /// include its unit, so the digest lists the job (D1). A RUNNING job with nothing queued is not listed.
    @Test
    void snapshot_runningJobWithAQueuedUnit_isListed_aFullyStartedOneIsNot() {
        var started = harness.submit(TestJobs.byRef("started", TestJobs.sha(), false, 1));

        harness.grantHead();
        harness.start(started);
        var sharded = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("s1", false), TestJobs.specUnit("s2", false)), false));

        harness.grantHead();
        harness.store.units().started(TestJobs.HOST, TestJobs.ref(sharded, "s1"), 1, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
        var snapshot = snapshot(none(), 1);

        assertThat(snapshot.queue().stream().map(JobRow::id).toList()).containsExactly(sharded);
        assertThat(snapshot.queue().getFirst().state()).isEqualTo(JobState.RUNNING);
        assertThat(snapshot.queued()).isEqualTo(1);
        assertThat(snapshot.candidates()).hasSize(1);
    }

    @Test
    void snapshot_queueOverTheLimit_listsTheLimit_countsThemAll() {
        IntStream.range(0, DigestStore.QUEUE_LIMIT + 3)
                 .forEach(index -> harness.submit(TestJobs.byRef("q" + index, TestJobs.sha(), false, 5)));
        var snapshot = snapshot(none(), 1);

        assertThat(snapshot.queue()).hasSize(DigestStore.QUEUE_LIMIT);
        assertThat(snapshot.queued()).isEqualTo(DigestStore.QUEUE_LIMIT + 3);
    }

    @Test
    void snapshot_finishedJob_isNamedWithItsVerdict() {
        var job = harness.run(TestJobs.byRef("main", TestJobs.sha(), false, 5),
                              TestJobs.exited(0),
                              List.of(TestJobs.row("a.ATest", "ok", Outcome.PASSED)));
        var finished = snapshot(some(EventCursor.noEvent(0)), 100).window()
                                              .events()
                                              .stream()
                                              .filter(event -> event.kind() == EventRow.Kind.FINISHED)
                                              .toList();

        assertThat(finished).hasSize(1);
        assertThat(finished.getFirst().job()).isEqualTo(some(job));
        assertThat(finished.getFirst().payload().verdict()).isEqualTo(some("PASSED"));
    }

    private void writeInsideTheSnapshotFails(String sql) {
        PgDigestStore.inSnapshot(harness.raw, tx -> Sql.execute(tx, sql))
                     .await(PgFixture.WAIT)
                     .onSuccess(_ -> fail("expected a read-only failure for " + sql))
                     .onFailure(cause -> assertThat(cause.source()
                                                         .map(Object::toString)
                                                         .or("")).as(sql)
                                                                 .contains("25006"));
    }

    /// Commits an event on another connection, then counts again inside the snapshot.
    private Promise<List<Long>> appendThenCount(QueryExecutor tx, long before) {
        return harness.store.events()
                      .append(none(), EventRow.Kind.QUEUED, PAYLOAD)
                      .flatMap(_ -> Sql.query(tx, "SELECT count(*) AS n FROM events"))
                      .map(after -> List.of(before, after.index(0).getLong("n")));
    }

    /// Follows the digest cursor the way an agent does, then drains once the writers are done.
    private void follow(List<Long> shown, AtomicBoolean done) {
        var cursor = EventCursor.noEvent(0);

        while (!done.get()) {
            cursor = read(shown, cursor);
        }
        var drained = window(some(cursor), 7);

        while (drained.more() || !drained.events().isEmpty()) {
            seqsOf(drained).forEach(shown::add);
            drained = window(some(drained.cursor()), 7);
        }
    }

    private EventCursor read(List<Long> shown, EventCursor cursor) {
        var window = window(some(cursor), 7);

        seqsOf(window).forEach(shown::add);
        return window.cursor();
    }

    private static Promise<List<Unit>> writeMany(Db db) {
        return Sql.sequence(IntStream.range(0, 40).boxed().toList(), _ -> writeOne(db));
    }

    private static Promise<Unit> writeOne(Db db) {
        var holdMs = ThreadLocalRandom.current().nextInt(0, 21);

        return db.inTransaction(tx -> EventWriter.append(tx, new PendingEvent(none(), EventRow.Kind.QUEUED, PAYLOAD))
                                                 .flatMap(_ -> Sql.execute(tx, "SELECT pg_sleep($1)", holdMs / 1000.0)));
    }

    private EventWindow window(Option<EventCursor> since, int max) {
        return snapshot(since, max).window();
    }

    private DigestSnapshot snapshot(Option<EventCursor> since, int max) {
        return harness.store.digest().snapshot(query(since, max)).await(PgFixture.WAIT).unwrap();
    }

    private static DigestQuery query(Option<EventCursor> since, int max) {
        return DigestQuery.digestQuery(since, max).unwrap();
    }

    private static List<Long> seqsOf(EventWindow window) {
        return window.events().stream().map(EventRow::seq).toList();
    }

    private List<Long> append(int count) {
        return IntStream.range(0, count)
                        .mapToObj(_ -> harness.store.events().append(none(), EventRow.Kind.QUEUED, PAYLOAD).await(PgFixture.WAIT).unwrap())
                        .toList();
    }

    private List<Long> allSeqs() {
        return Sql.rows(harness.raw.executor().completeQuery("SELECT seq FROM events ORDER BY seq").await(PgFixture.WAIT).unwrap())
                  .stream()
                  .map(row -> row.getLong("seq"))
                  .toList();
    }

    /// The cursor a reader holds at `seq`: naming the event stored there, or no event (0, or deleted at or below the
    /// floor).
    private EventCursor cursorAt(long seq) {
        var rows = Sql.rows(harness.raw.executor().completeQuery("SELECT at FROM events WHERE seq = $1", seq).await(PgFixture.WAIT).unwrap());

        return rows.isEmpty()
               ? EventCursor.noEvent(seq)
               : new EventCursor(seq, some(EventCursor.micros(rows.getFirst().getInstant("at"))));
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
