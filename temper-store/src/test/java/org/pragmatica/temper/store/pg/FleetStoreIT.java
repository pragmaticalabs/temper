package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetAcked;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.FleetSeqs;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.KilnBatch;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.SourceSeen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The fleet stream's store half (design §2.2): the F10 duplicate-pull guard (T1.3a), the F9
/// ordered commit across producers (T1.3b), retention's gap, subscribers bound to a token, and the heartbeat log.
@Tag("pg")
class FleetStoreIT {
    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private StoreHarness harness;
    private FleetStore fleet;
    private final List<Db> extra = new ArrayList<>();

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        fleet = harness.store.fleet();
    }

    @AfterEach
    void tearDown() {
        extra.forEach(db -> db.close().await(PgFixture.WAIT));
        if (harness != null) {
            harness.close();
        }
    }

    /// T1.3a (F10): a pull that repeats changes already stored inserts nothing for them, still inserts the new one, and
    /// its cursor advances. A plain INSERT would abort the batch on the duplicate and leave the cursor where it was.
    @Test
    void pulled_duplicateChanges_insertNothingAndCursorAdvances() {
        pull(List.of(change(1), change(2)), 2);
        assertThat(pull(List.of(change(1), change(2)), 2)).as("only duplicates: nothing inserted").isEqualTo(none());
        pull(List.of(change(1), change(2), change(3)), 3);

        assertThat(rows(0).stream().map(FleetEventRow::subject).toList()).containsExactly("o/r#1", "o/r#2", "o/r#3");
        assertThat(kiln().cursor()).isEqualTo(3);
        assertThat(fleet.source("kiln:github").await(PgFixture.WAIT).unwrap().flatMap(FleetSourceRow::lastOkAt))
            .isEqualTo(some(T0));
    }

    /// T1.3b (F9): producer A inserts and holds its transaction open; producer B inserts later and commits first. A
    /// reader that advances its cursor to the highest seq it saw must still see A's row: B's insert waits for A's
    /// order lock, so B's seq is never visible before A's.
    @Test
    void append_twoProducers_neitherIsSkippedByAReaderBetweenTheirCommits() throws Exception {
        var holder = PgFixture.db(harness.config);
        extra.add(holder);
        var held = CompletableFuture.runAsync(() -> holder.inTransaction(tx -> PgFleetStore.insertAll(tx, List.of(event("hostlink", "host-answering")))
                                                                                       .flatMap(_ -> Sql.execute(tx, "SELECT pg_sleep(1.5)")))
                                                       .await(PgFixture.WAIT)
                                                       .unwrap());
        Thread.sleep(300);
        var second = CompletableFuture.runAsync(() -> fleet.append(List.of(event("controller", "self-check-ok")))
                                                         .await(PgFixture.WAIT)
                                                         .unwrap());
        Thread.sleep(600);
        var seen = new ArrayList<Long>(read(0));
        held.get();
        second.get();
        seen.addAll(read(seen.isEmpty() ? 0 : seen.getLast()));

        assertThat(seen).as("a reader between the two commits skipped a row").hasSize(2);
    }

    /// Retention deletes up to the newest event before the cut and raises the floor in one statement; a cursor below
    /// it reads a gap, never a silent skip.
    @Test
    void prune_raisesTheFloor_andACursorBelowItReadsAGap() {
        fleet.append(List.of(event("controller", "self-check-ok"), event("controller", "self-check-failed"))).await(PgFixture.WAIT).unwrap();
        fleet.prune(Instant.now().plus(1, ChronoUnit.MINUTES)).await(PgFixture.WAIT).unwrap();

        assertThat(fleet.pending(0, 10).await(PgFixture.WAIT).unwrap()).isEqualTo(new FleetPending.Gap(1, 2));
        assertThat(fleet.head().await(PgFixture.WAIT).unwrap().seq()).isEqualTo(2);
    }

    @Test
    void create_existingName_isRefused_andTheCursorStartsAtTheHead() {
        fleet.append(List.of(event("controller", "self-check-ok"))).await(PgFixture.WAIT).unwrap();

        assertThat(fleet.create("agent.1", none(), "tok-a").await(PgFixture.WAIT).unwrap()).isTrue();
        assertThat(fleet.create("agent.1", some(0L), "tok-b").await(PgFixture.WAIT).unwrap()).isFalse();
        var row = fleet.subscriber("agent.1").await(PgFixture.WAIT).unwrap().unwrap();
        assertThat(row.cursor()).isEqualTo(1);
        assertThat(row.tokenId()).isEqualTo("tok-a");

        fleet.ack("agent.1", fleet.head().await(PgFixture.WAIT).unwrap().epoch(), 0).await(PgFixture.WAIT).unwrap();
        assertThat(fleet.subscriber("agent.1").await(PgFixture.WAIT).unwrap().unwrap().cursor()).isEqualTo(1);
        assertThat(fleet.reset("agent.1", some(0L)).await(PgFixture.WAIT).unwrap()).isTrue();
        assertThat(fleet.subscriber("agent.1").await(PgFixture.WAIT).unwrap().unwrap().cursor()).isZero();
        assertThat(fleet.reset("nobody", none()).await(PgFixture.WAIT).unwrap()).isFalse();
    }

    /// Lead ruling F2: the ack's checks and its update are one statement; it moves the cursor only in the current epoch
    /// and at or below the head, and answers what it found. Probe: drop the epoch condition → the old epoch's ack moves
    /// the cursor → red.
    @Test
    void ack_movesTheCursorOnlyInTheCurrentEpoch_andAtOrBelowTheHead() {
        fleet.append(List.of(event("controller", "self-check-ok"), event("controller", "self-check-ok"))).await(PgFixture.WAIT).unwrap();
        fleet.create("agent.2", some(0L), "tok-a").await(PgFixture.WAIT).unwrap();
        var old = fleet.head().await(PgFixture.WAIT).unwrap().epoch();

        assertThat(fleet.ack("agent.2", old, 3).await(PgFixture.WAIT).unwrap()).isEqualTo(FleetAcked.fleetAcked(old, 2, false));
        assertThat(fleet.ack("agent.2", old, 1).await(PgFixture.WAIT).unwrap()).isEqualTo(FleetAcked.fleetAcked(old, 2, true));
        var renewed = fleet.renewEpoch().await(PgFixture.WAIT).unwrap();

        assertThat(fleet.ack("agent.2", old, 2).await(PgFixture.WAIT).unwrap()).isEqualTo(FleetAcked.fleetAcked(renewed, 2, false));
        assertThat(fleet.subscriber("agent.2").await(PgFixture.WAIT).unwrap().unwrap().cursor()).as("the old epoch's ack moved nothing").isEqualTo(1);
        assertThat(fleet.ack("agent.2", renewed, 2).await(PgFixture.WAIT).unwrap().moved()).isTrue();
        assertThat(fleet.ack("nobody", renewed, 1).await(PgFixture.WAIT).unwrap().moved()).isFalse();
    }

    @Test
    void staleness_isAnnouncedOncePerTransition() {
        fleet.expect(List.of(SourceSeen.sourceSeen("hostlink:bigboy", none(), 60))).await(PgFixture.WAIT).unwrap();
        var stale = event("controller", "source-stale");

        assertThat(fleet.staleness("hostlink:bigboy", true, none(), stale).await(PgFixture.WAIT).unwrap().isPresent()).isTrue();
        assertThat(fleet.staleness("hostlink:bigboy", true, none(), stale).await(PgFixture.WAIT).unwrap().isEmpty()).isTrue();
        assertThat(fleet.staleness("hostlink:bigboy", false, none(), event("controller", "source-recovered")).await(PgFixture.WAIT).unwrap().isPresent()).isTrue();
        assertThat(read(0)).hasSize(2);
    }

    /// NOTE-4: a verdict computed from a `last_ok_at` that a success has since replaced is not written, nor announced.
    @Test
    void staleness_aSuccessWrittenInBetween_wins() {
        fleet.seen(List.of(SourceSeen.sourceSeen("kiln", some(T0), 5))).await(PgFixture.WAIT).unwrap();
        fleet.seen(List.of(SourceSeen.sourceSeen("kiln", some(T0.plusSeconds(60)), 5))).await(PgFixture.WAIT).unwrap();

        assertThat(fleet.staleness("kiln", true, some(T0), event("controller", "source-stale")).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
        assertThat(fleet.source("kiln").await(PgFixture.WAIT).unwrap().unwrap().stale()).isFalse();
        assertThat(fleet.staleness("kiln", true, some(T0.plusSeconds(60)), event("controller", "source-stale")).await(PgFixture.WAIT).unwrap()).isNotEqualTo(none());
    }

    /// A fresh stream reports nothing used (the sequence's `is_called` is false); after inserts the sequence is
    /// the last value handed out, and `used` follows it.
    @Test
    void head_sequence_isZeroWhenFresh_andTheLastValueHandedOut() {
        var fresh = fleet.head().await(PgFixture.WAIT).unwrap();
        assertThat(fresh.seq()).isZero();
        assertThat(fresh.sequence()).isZero();
        assertThat(fresh.used()).isFalse();

        assertThat(fleet.append(List.of(event("controller", "self-check-ok"), event("controller", "self-check-failed"))).await(PgFixture.WAIT).unwrap())
            .as("the lowest and highest seq inserted")
            .satisfies(seqs -> {
                assertThat(seqs.map(FleetSeqs::first)).isEqualTo(some(1L));
                assertThat(seqs.map(FleetSeqs::last)).isEqualTo(some(2L));
            });
        var written = fleet.head().await(PgFixture.WAIT).unwrap();

        assertThat(written.seq()).isEqualTo(2);
        assertThat(written.sequence()).isEqualTo(2);
        assertThat(written.reached()).isEqualTo(2);
        assertThat(written.used()).isTrue();
    }

    /// `seen` creates the row and stores the last success written (an absent one keeps the stored one); `sources` lists
    /// every row by name; `polled` answers once written.
    @Test
    void seen_keepsTheLatestSuccess_andSourcesListByName() {
        fleet.seen(List.of(SourceSeen.sourceSeen("selfcheck", some(T0.plusSeconds(60)), 60),
                           SourceSeen.sourceSeen("hostlink:a", none(), 30)))
             .await(PgFixture.WAIT)
             .unwrap();
        fleet.seen(List.of(SourceSeen.sourceSeen("selfcheck", some(T0), 60))).await(PgFixture.WAIT).unwrap();
        fleet.seen(List.of(SourceSeen.sourceSeen("selfcheck", none(), 90))).await(PgFixture.WAIT).unwrap();

        var rows = fleet.sources().await(PgFixture.WAIT).unwrap();
        assertThat(rows).as("t2:detector is seeded by V14").extracting(FleetSourceRow::name).containsExactly("hostlink:a", "selfcheck", "t2:detector");
        assertThat(rows.get(1).lastOkAt()).as("the last value written, not the greatest (C4.2)").isEqualTo(some(T0));
        assertThat(rows.get(1).periodS()).isEqualTo(90);
        assertThat(rows.get(0).lastOkAt()).isEqualTo(none());
        fleet.create("p", none(), "tok").await(PgFixture.WAIT).unwrap();
        assertThat(fleet.polled("p", T0).await(PgFixture.WAIT).unwrap()).isNotNull();
    }

    @Test
    void renewEpoch_changesTheEpochOfLaterRows() {
        var before = fleet.head().await(PgFixture.WAIT).unwrap().epoch();
        var after = fleet.renewEpoch().await(PgFixture.WAIT).unwrap();
        fleet.append(List.of(event("controller", "epoch-changed"))).await(PgFixture.WAIT).unwrap();

        assertThat(after).isNotEqualTo(before);
        assertThat(rows(0).getFirst().epoch()).isEqualTo(after);
        assertThat(fleet.head().await(PgFixture.WAIT).unwrap().epoch()).isEqualTo(after);
    }

    @Test
    void tick_keepsOneRowPerMinute_andDropsOldOnes() {
        fleet.tick(T0, T0.minus(30, ChronoUnit.DAYS)).await(PgFixture.WAIT).unwrap();
        fleet.tick(T0, T0.minus(30, ChronoUnit.DAYS)).await(PgFixture.WAIT).unwrap();
        fleet.tick(T0.plusSeconds(60), T0.plusSeconds(30)).await(PgFixture.WAIT).unwrap();

        assertThat(fleet.ticks(T0.minusSeconds(3600)).await(PgFixture.WAIT).unwrap()).containsExactly(T0.plusSeconds(60));
    }

    private Option<FleetSeqs> pull(List<NewFleetEvent> changes, long cursor) {
        var kiln = FleetSourceRow.fleetSourceRow("kiln", some("ep-1"), cursor, some(T0), 5, 0, 0, cursor, false);

        return fleet.pulled(KilnBatch.kilnBatch(kiln, List.of(SourceSeen.sourceSeen("kiln:github", some(T0), 60)), changes))
                    .await(PgFixture.WAIT)
                    .unwrap();
    }

    private FleetSourceRow kiln() {
        return fleet.source("kiln").await(PgFixture.WAIT).unwrap().unwrap();
    }

    private List<Long> read(long cursor) {
        return rows(cursor).stream().map(FleetEventRow::seq).toList();
    }

    private List<FleetEventRow> rows(long cursor) {
        return switch (fleet.pending(cursor, 100).await(PgFixture.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows();
            case FleetPending.Gap gap -> throw new AssertionError("unexpected gap " + gap);
        };
    }

    private static NewFleetEvent change(long id) {
        return NewFleetEvent.kilnRow(T0, "ep-1", id, "pr-state", "o/r#" + id, "{\"id\":" + id + "}", false);
    }

    private static NewFleetEvent event(String source, String kind) {
        return NewFleetEvent.newFleetEvent(T0, source, kind, "s", "{}");
    }

}
