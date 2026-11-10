package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.WorkCard;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.store.WorkPhase;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [PgWorkStore] against real Postgres (V5): an event is appended and its item upserted, `submitted_at` is the earliest
/// event's time, a title given replaces the stored one and an absent one keeps it, the current state is the latest
/// event by (at, id), the time in phase starts at the latest run of one phase, and the CHECKs refuse what the route
/// would refuse, so a write that bypassed the route cannot store it either.
@Tag("pg")
class WorkStoreIT {
    private static final Instant T1 = T0.plusSeconds(3_600);
    private static final Instant T2 = T0.plusSeconds(7_200);
    private static final Instant T3 = T0.plusSeconds(10_800);

    private KilnStore store;
    private Db raw;

    @BeforeEach
    void store() {
        var config = KilnPgFixture.freshDatabase();

        store = PgKilnStore.connect(config, StoreFixtures.REPO, StoreFixtures.highWater(), StoreFixtures.DIFF, KilnPgFixture.ticking())
                           .await(KilnPgFixture.WAIT)
                           .unwrap();
        raw = KilnPgFixture.db(config);
    }

    @AfterEach
    void close() {
        if (store != null) {
            await(store.close());
        }
        if (raw != null) {
            await(raw.close());
        }
    }

    private static WorkEvent event(String id, Option<String> title, WorkPhase phase, int round, Instant at) {
        return new WorkEvent(id, id.split("-", 2)[0], title, phase, round, some("coder" + round), none(), some("note " + phase.label()), at);
    }

    private long record(WorkEvent event) {
        return await(store.work().record(event));
    }

    private List<WorkCard> board() {
        return await(store.work().board()).cards();
    }

    @Test
    void anEmptyLedger_isAnEmptyBoard_withNoLastEvent() {
        var board = await(store.work().board());

        assertThat(board.cards()).isEmpty();
        assertThat(board.lastEventAt()).isEqualTo(none());
    }

    @Test
    void record_appendsEveryEvent_andUpsertsOneItem() {
        var first = record(event("T1-WP5", some("Work tab"), WorkPhase.CODING, 1, T0));
        var second = record(event("T1-WP5", none(), WorkPhase.VERIFY, 1, T1));

        assertThat(second).isGreaterThan(first);
        assertThat(StoreFixtures.dump(store, "work_event")).hasSize(2);
        assertThat(StoreFixtures.dump(store, "work_item")).hasSize(1);
        assertThat(board()).singleElement()
                           .isEqualTo(new WorkCard("T1-WP5", "T1", some("Work tab"), T0, WorkPhase.VERIFY, 1, some("coder1"), none(),
                                                   some("note verify"), T1, T1));
    }

    /// The current state is the latest event by `at`, not the last appended: an event recorded late with an earlier
    /// `at` takes its place in the history; it moves `submitted_at` back when it is the earliest.
    @Test
    void currentState_isTheLatestEventByAt_andSubmittedAt_theEarliest() {
        record(event("T1-WP5", none(), WorkPhase.VERIFY, 2, T2));
        record(event("T1-WP5", none(), WorkPhase.CODING, 1, T1));
        record(event("T1-WP5", none(), WorkPhase.DESIGN, 0, T0));

        var card = board().getFirst();

        assertThat(card.phase()).isEqualTo(WorkPhase.VERIFY);
        assertThat(card.round()).isEqualTo(2);
        assertThat(card.at()).isEqualTo(T2);
        assertThat(card.submittedAt()).isEqualTo(T0);
    }

    /// Two events at one `at`: the later appended is the latest (the id breaks the tie).
    @Test
    void aTie_inAt_isBrokenByTheEventId() {
        record(event("T1-WP5", none(), WorkPhase.VERIFY, 1, T1));
        record(event("T1-WP5", none(), WorkPhase.FIXING, 2, T1));

        assertThat(board().getFirst().phase()).isEqualTo(WorkPhase.FIXING);
    }

    @Test
    void title_givenReplaces_absentKeeps() {
        record(event("T1-WP5", none(), WorkPhase.DESIGN, 0, T0));
        assertThat(board().getFirst().title()).isEqualTo(none());

        record(event("T1-WP5", some("first"), WorkPhase.CODING, 1, T1));
        record(event("T1-WP5", none(), WorkPhase.VERIFY, 1, T2));
        assertThat(board().getFirst().title()).isEqualTo(some("first"));

        record(event("T1-WP5", some("second"), WorkPhase.FIXING, 2, T3));
        assertThat(board().getFirst().title()).isEqualTo(some("second"));
    }

    /// Fix 3: a backfilled event (an earlier `at` than a recorded one) never overwrites a newer title; it still gives
    /// the item its first title.
    @Test
    void title_ofABackfilledEvent_neverOverwritesANewerOne() {
        record(event("T1-WP5", some("newer"), WorkPhase.VERIFY, 1, T2));
        record(event("T1-WP5", some("backfilled"), WorkPhase.CODING, 1, T1));
        assertThat(board().getFirst().title()).isEqualTo(some("newer"));

        record(event("T2-WP1", none(), WorkPhase.VERIFY, 1, T2));
        record(event("T2-WP1", some("first title"), WorkPhase.CODING, 1, T1));
        assertThat(board().get(1).title()).isEqualTo(some("first title"));

        record(event("T1-WP5", some("latest"), WorkPhase.FIXING, 2, T2));
        assertThat(board().getFirst().title()).isEqualTo(some("latest"));
    }

    /// Time in phase runs from the first event of the latest run in the current phase: a second `verify` event does not
    /// restart it, a return to `verify` after `fixing` does.
    @Test
    void phaseSince_isTheStartOfTheLatestRunOfThePhase() {
        record(event("T1-WP5", none(), WorkPhase.VERIFY, 1, T0));
        record(event("T1-WP5", none(), WorkPhase.VERIFY, 1, T1));
        assertThat(board().getFirst().phaseSince()).isEqualTo(T0);

        record(event("T1-WP5", none(), WorkPhase.FIXING, 2, T2));
        assertThat(board().getFirst().phaseSince()).isEqualTo(T2);

        record(event("T1-WP5", none(), WorkPhase.VERIFY, 2, T3));
        record(event("T1-WP5", none(), WorkPhase.VERIFY, 2, T3.plusSeconds(60)));
        assertThat(board().getFirst().phaseSince()).isEqualTo(T3);
    }

    @Test
    void board_holdsEveryItem_oldestSubmissionFirst_andTheLastEventTime() {
        record(event("T2-WP-0T", none(), WorkPhase.CODING, 1, T1));
        record(event("T1-WP5", none(), WorkPhase.DESIGN, 0, T0));
        record(event("T1-WP5", none(), WorkPhase.MERGED, 3, T3));

        var board = await(store.work().board());

        assertThat(board.cards()).extracting(WorkCard::id).containsExactly("T1-WP5", "T2-WP-0T");
        assertThat(board.cards()).extracting(WorkCard::item).containsExactly("T1", "T2");
        assertThat(board.lastEventAt()).isEqualTo(some(T3));
    }

    /// Every phase label is stored and read back.
    @Test
    void everyPhase_roundTrips() {
        for (var phase : WorkPhase.values()) {
            record(event("P-" + phase.ordinal(), none(), phase, 0, T0));
        }
        assertThat(board()).extracting(WorkCard::phase).containsExactlyInAnyOrder(WorkPhase.values());
    }

    /// The text columns keep what the route accepted, U+0000 aside ([Sql#text]), and a 300 code-point note fits.
    @Test
    void freeText_isStoredAsGiven() {
        var astral = "😀";
        var note = astral.repeat(300);

        record(new WorkEvent("T1-WP5", "T1", some("<b>it's</b> \"x\""), WorkPhase.CODING, 1, none(), some("v.1"), some(note), T0));

        var card = board().getFirst();

        assertThat(card.title()).isEqualTo(some("<b>it's</b> \"x\""));
        assertThat(card.note()).isEqualTo(some(note));
        assertThat(card.coder()).isEqualTo(none());
        assertThat(card.verifier()).isEqualTo(some("v.1"));
    }

    /// The CHECKs: what the route refuses, the table refuses too.
    @Test
    void theChecks_refuseWhatTheRouteRefuses() {
        await(Sql.execute(raw.executor(), "INSERT INTO work_item (id, item, submitted_at) VALUES ('T1-WP5', 'T1', now())"));

        for (var bad : List.of("INSERT INTO work_item (id, item, submitted_at) VALUES ('T1 WP5', 'T1', now())",
                               "INSERT INTO work_item (id, item, submitted_at) VALUES ('" + "x".repeat(65) + "', 'x', now())",
                               "INSERT INTO work_item (id, item, title, submitted_at) VALUES ('T1-WP6', 'T1', '" + "t".repeat(201) + "', now())",
                               "INSERT INTO work_event (item_id, at, phase, round) VALUES ('T1-WP5', now(), 'reviewing', 0)",
                               "INSERT INTO work_event (item_id, at, phase, round) VALUES ('T1-WP5', now(), 'verify', -1)",
                               "INSERT INTO work_event (item_id, at, phase, round, coder) VALUES ('T1-WP5', now(), 'verify', 0, 'a b')",
                               "INSERT INTO work_event (item_id, at, phase, round, verifier) VALUES ('T1-WP5', now(), 'verify', 0, '-v')",
                               "INSERT INTO work_event (item_id, at, phase, round, note) VALUES ('T1-WP5', now(), 'verify', 0, '" + "n".repeat(301) + "')",
                               "INSERT INTO work_event (item_id, at, phase, round) VALUES ('T9-WP9', now(), 'verify', 0)")) {
            assertThat(Sql.execute(raw.executor(), bad).await(KilnPgFixture.WAIT).isFailure()).as(bad).isTrue();
        }
        await(Sql.execute(raw.executor(), "INSERT INTO work_event (item_id, at, phase, round, note) VALUES ('T1-WP5', now(), 'in-pr', 0, '"
                                          + "n".repeat(300) + "')"));
    }
}
