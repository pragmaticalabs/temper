package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventStore;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SubscriberRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `EventStore.selectors` (m2-design §2.3) on Postgres: `(selectors ∪ add) − remove`, sorted by wire form and
/// distinct, on a subscriber created at the long-poll's cursor rule when absent; the typed form reads back.
@Tag("pg")
class SubscriberSelectorsIT {
    private static final String SHA = "0cf563192a0cf563192a0cf563192a0cf563192a";
    private static final EventPayload PAYLOAD = EventPayload.eventPayload(some(SHA), none(), some("mvn"), none(), none(), none(),
                                                                          none(), none());
    private StoreHarness harness;
    private EventStore events;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        events = harness.store.events();
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    private static List<Selector> selectors(String... wire) {
        return List.of(wire).stream().map(raw -> Selector.selector(raw).unwrap()).toList();
    }

    private static List<String> wire(SubscriberRow row) {
        return row.selectors().stream().map(Selector::wire).toList();
    }

    private SubscriberRow change(String name, List<Selector> add, List<Selector> remove) {
        return events.selectors(name, add, remove).await(PgFixture.WAIT).unwrap();
    }

    @Test
    void selectors_createAnAbsentSubscriber_atTheLongPollCursorRule() {
        var last = IntStream.range(0, 3)
                            .mapToObj(_ -> events.append(none(), EventRow.Kind.QUEUED, PAYLOAD).await(PgFixture.WAIT).unwrap())
                            .toList()
                            .getLast();

        var created = change("before.watch", selectors("job:5"), List.of());

        assertThat(created.cursor()).as("GREATEST(max(seq), floor), as the first poll would create it").isEqualTo(last);
        assertThat(wire(created)).containsExactly("job:5");
        assertThat(events.subscriber("before.watch").await(PgFixture.WAIT).unwrap()).as("the poll finds it, unchanged")
                                                                                    .isEqualTo(created);
    }

    @Test
    void selectors_unionMinusRemove_sortedByWireForm_distinct() {
        var first = change("cto", selectors("sha:" + SHA, "job:7", "ref:main", "job:7"), List.of());

        assertThat(wire(first)).containsExactly("job:7", "ref:main", "sha:" + SHA);

        var second = change("cto", selectors("job:10", "ref:main"), selectors("job:7", "ref:absent"));

        assertThat(wire(second)).as("code-point order: job:10 sorts before job:7").containsExactly("job:10", "ref:main", "sha:" + SHA);

        var both = change("cto", selectors("ref:x"), selectors("ref:x"));

        assertThat(wire(both)).as("a selector in both lists ends removed").containsExactly("job:10", "ref:main", "sha:" + SHA);
        assertThat(both.cursor()).as("a change never moves the cursor").isEqualTo(first.cursor());
    }

    /// Code-point order (`COLLATE "C"`), not the database's collation, which would put `ref:a` before `ref:B`.
    @Test
    void selectors_sortByCodePoint_notByTheDatabaseCollation() {
        var stored = change("cto", selectors("ref:a", "ref:_x", "ref:B", "ref:.y"), List.of());

        assertThat(wire(stored)).containsExactly("ref:.y", "ref:B", "ref:_x", "ref:a");
        assertThat(wire(stored)).isSortedAccordingTo(String::compareTo);
    }

    @Test
    void selectors_removingTheLastOne_leavesTheEmptySet_everyJob() {
        change("cto", selectors("job:7"), List.of());

        var widened = change("cto", List.of(), selectors("job:7"));

        assertThat(widened.selectors()).isEmpty();
        assertThat(harness.count("SELECT count(*) AS n FROM subscribers WHERE name = 'cto' AND selectors = '[]'::jsonb")).isEqualTo(1);
    }

    /// R-M2-12: every change bumps the version; `skipTo` moves the cursor only under the version it names, and only
    /// forward.
    @Test
    void skipTo_advancesOnlyUnderTheCurrentVersion_andOnlyForward() {
        var first = change("cto", selectors("job:7"), List.of());
        var second = change("cto", selectors("job:8"), List.of());

        assertThat(second.version()).isEqualTo(first.version() + 1);

        events.skipTo("cto", first.version(), 10).await(PgFixture.WAIT).unwrap();
        assertThat(events.subscriber("cto").await(PgFixture.WAIT).unwrap().cursor()).as("stale version").isEqualTo(second.cursor());

        events.skipTo("cto", second.version(), 10).await(PgFixture.WAIT).unwrap();
        assertThat(events.subscriber("cto").await(PgFixture.WAIT).unwrap().cursor()).isEqualTo(10);

        events.skipTo("cto", second.version(), 4).await(PgFixture.WAIT).unwrap();
        assertThat(events.subscriber("cto").await(PgFixture.WAIT).unwrap().cursor()).as("never backwards").isEqualTo(10);
        assertThat(events.skipTo("nobody", 0, 4).await(PgFixture.WAIT).isSuccess()).isTrue();
    }

    @Test
    void selectors_badName_isRefusedByTheTable() {
        assertThat(events.selectors("bad name", selectors("job:7"), List.of()).await(PgFixture.WAIT).isFailure()).isTrue();
    }

    /// An untyped stored selector (what V7 resets, R-12) does not read as a guess: the row is corrupt.
    @Test
    void subscriber_withAnUntypedStoredSelector_isACorruptRow() {
        harness.execute("INSERT INTO subscribers (name, cursor, selectors) VALUES ('old', 0, '[\"4711\"]')");

        events.subscriber("old")
              .await(PgFixture.WAIT)
              .onSuccess(row -> fail("expected CorruptRow, read " + row))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.CorruptRow.class));
    }
}
