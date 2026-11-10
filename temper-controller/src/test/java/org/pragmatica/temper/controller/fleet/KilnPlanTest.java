package org.pragmatica.temper.controller.fleet;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.KilnBatch;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.SourceSeen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [KilnPlan]: reset, rewind, gap, catch-up and kind pass-through (design §2.2, H4, R2-6.1).
class KilnPlanTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Instant FROZEN = Instant.parse("2026-11-03T09:00:00Z");

    @Test
    void plan_firstRead_marksEverythingUpToTheHeadCatchUp_andMovesTheCursor() {
        var batch = plan(FleetSourceRow.unseen("kiln", 5), changes("E1", 3, change(1, "PR_STATE"), change(2, "HEAD_MOVED"), change(3, "CHECK_CELL")));

        assertThat(batch.kiln().epoch()).isEqualTo(some("E1"));
        assertThat(batch.kiln().cursor()).isEqualTo(3);
        assertThat(batch.events()).extracting(NewFleetEvent::kind).containsExactly("pr-state", "pr-head-moved", "check-cell");
        assertThat(batch.events()).allMatch(NewFleetEvent::catchUp);
        assertThat(batch.events()).extracting(NewFleetEvent::sourceEpoch).containsOnly(some("E1"));
    }

    @Test
    void plan_sameEpoch_rowsAfterTheCatchUpHeadAreLive() {
        var batch = plan(state("E1", 3, 0, 3), changes("E1", 4, change(4, "PR_STATE")));

        assertThat(batch.events()).singleElement().satisfies(event -> assertThat(event.catchUp()).isFalse());
        assertThat(batch.kiln().cursor()).isEqualTo(4);
        assertThat(batch.kiln().head()).isEqualTo(4);
    }

    /// WP2 kinds arrive before temper knows them: kept, lower-kebab, never refused.
    @Test
    void plan_unknownKind_passesThroughKebabCased() {
        var batch = plan(state("E1", 0, 0, 0), changes("E1", 1, change(1, "BOT_ISSUE_CLOSED")));

        assertThat(batch.events()).extracting(NewFleetEvent::kind).containsExactly("bot-issue-closed");
        assertThat(batch.events().getFirst().payload()).contains("\"repo\":\"o/r\"").contains("\"pr\":7").contains("\"k\":\"v\"");
    }

    /// kiln's own payload fields win; the change's columns fill only what it does not name.
    @Test
    void changePayload_keepsKilnFields_andFillsTheRest() {
        var payload = TemperJson.mapper().readTree("{\"repo\":\"kiln/own\",\"pr\":1,\"before\":\"x\",\"after\":\"y\"}").unwrap();
        var change = new KilnReply.Change(1, "PR_STATE", some("o/r"), some(7L), "s", some("a"), some("b"), NOW, some(NOW), payload);
        var bare = new KilnReply.Change(2, "PR_STATE", some("o/r"), some(7L), "s", some("a"), some("b"), NOW, some(NOW), FleetLines.empty());

        assertThat(KilnPlan.changePayload(change).toString()).isEqualTo("{\"repo\":\"kiln/own\",\"pr\":1,\"before\":\"x\",\"after\":\"y\",\"recordedAt\":\"2026-11-03T12:00:00Z\"}");
        assertThat(KilnPlan.changePayload(bare).toString()).isEqualTo("{\"repo\":\"o/r\",\"pr\":7,\"before\":\"a\",\"after\":\"b\",\"recordedAt\":\"2026-11-03T12:00:00Z\"}");
        var ownRecordedAt = new KilnReply.Change(3, "PR_STATE", none(), none(), "s", none(), none(), NOW, some(NOW),
                                                 TemperJson.mapper().readTree("{\"recordedAt\":\"kiln-own\"}").unwrap());
        assertThat(KilnPlan.changePayload(ownRecordedAt).toString()).as("kiln's recordedAt wins too").isEqualTo("{\"recordedAt\":\"kiln-own\"}");
    }

    /// A gap on the first read ever is no gap for temper (it never held a cursor): nothing is announced, the
    /// cursor continues from kiln's floor (`to - 1`), and everything up to the head is catch-up.
    @Test
    void plan_firstRead_gap_announcesNothing_andContinuesFromTheFloor() {
        var batch = plan(FleetSourceRow.unseen("kiln", 5),
                         new KilnReply.Gap(0, 10, "E1", 10, 40, Map.of()));

        assertThat(batch.events()).isEmpty();
        assertThat(batch.kiln().epoch()).isEqualTo(some("E1"));
        assertThat(batch.kiln().cursor()).isEqualTo(9);
        assertThat(batch.kiln().catchUpTo()).isEqualTo(40);
    }

    /// The cursor equal to the head is the same epoch, not a rewind.
    @Test
    void plan_headEqualToCursor_isNotARewind() {
        assertThat(plan(state("E1", 4, 0, 0), changes("E1", 4)).events()).isEmpty();
    }

    /// H4: a rebuilt kiln (another epoch) is announced and restarts from 0, rows catch-up to its head.
    @Test
    void plan_reset_announcesAndRestartsTheCursor() {
        var batch = plan(state("E1", 40, 0, 0), new KilnReply.Reset("E2", 12, Map.of()));

        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("source-reset");
            assertThat(event.payload()).contains("\"reason\":\"reset\"").contains("\"old\":\"E1\"").contains("\"new\":\"E2\"");
        });
        assertThat(batch.kiln().epoch()).isEqualTo(some("E2"));
        assertThat(batch.kiln().cursor()).isZero();
        assertThat(batch.kiln().catchUpTo()).isEqualTo(12);
    }

    /// R2-6.1: kiln restored from a backup keeps its epoch but its head falls below the cursor. Without the rewind,
    /// its new changes would reuse ids that ON CONFLICT DO NOTHING drops silently.
    @Test
    void plan_rewind_sameEpochHeadBelowCursor_isAResetWithANewGeneration() {
        var batch = plan(state("E1", 40, 0, 0), changes("E1", 25));

        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("source-reset");
            assertThat(event.payload()).contains("\"reason\":\"rewind\"");
        });
        assertThat(batch.kiln().cursor()).isZero();
        assertThat(batch.kiln().generation()).isEqualTo(1);
        assertThat(batch.kiln().catchUpTo()).isEqualTo(25);

        var next = plan(batch.kiln(), changes("E1", 25, change(1, "PR_STATE")));
        assertThat(next.events()).singleElement().satisfies(event -> {
            assertThat(event.sourceEpoch()).isEqualTo(some("E1~1"));
            assertThat(event.catchUp()).isTrue();
        });
    }

    @Test
    void plan_gap_isAnnouncedAndTheCursorContinuesFromTheFloor() {
        var batch = plan(state("E1", 5, 0, 0), new KilnReply.Gap(6, 90, "E1", 90, 120, Map.of()));

        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("gap");
            assertThat(event.payload()).contains("\"from\":6").contains("\"to\":90");
        });
        assertThat(batch.kiln().cursor()).isEqualTo(89);
    }

    /// Contract update 2b35b0de: a fully pruned kiln has floor = head + 1 and answers a gap, never something that looks
    /// rewound; the cursor continues at the head.
    @Test
    void plan_fullyPrunedKiln_isAGapNotARewind() {
        var batch = plan(state("E1", 40, 0, 0), new KilnReply.Gap(41, 51, "E1", 51, 50, Map.of()));

        assertThat(batch.events()).extracting(NewFleetEvent::kind).containsExactly("gap");
        assertThat(batch.kiln().cursor()).isEqualTo(50);
        assertThat(batch.kiln().generation()).isZero();
    }

    /// F13: kiln's per-source lastOkAt is copied as it is, so a frozen github poller ages into staleness although the
    /// pull itself succeeds.
    @Test
    void plan_sources_areCopiedAsKilnPrefixed_withTheirOwnLastOkAt() {
        var batch = plan(state("E1", 0, 0, 0),
                         new KilnReply.Changes("E1", 1, 0, Map.of("github", new KilnReply.SourceInfo(some(FROZEN), 60)), List.of()));

        assertThat(batch.sources()).containsExactly(SourceSeen.sourceSeen("kiln:github", some(FROZEN), 60));
        assertThat(batch.kiln().lastOkAt()).isEqualTo(some(NOW));
        assertThat(Staleness.stale(batch.kiln().lastOkAt(), batch.kiln().periodS(), NOW)).isFalse();
        assertThat(Staleness.stale(some(FROZEN), 60, NOW)).isTrue();
    }

    /// Lead ruling 11-03 (i): kiln sends its high-water source already prefixed; it passes through, never doubled.
    @Test
    void plan_sources_kilnPrefixedKeyPassesThrough_otherKeysArePrefixed() {
        var batch = plan(state("E1", 0, 0, 0),
                         new KilnReply.Changes("E1",
                                               1,
                                               0,
                                               Map.of("github", new KilnReply.SourceInfo(some(FROZEN), 60),
                                                      "kiln:highwater", new KilnReply.SourceInfo(some(NOW), 60)),
                                               List.of()));

        assertThat(batch.sources()).containsExactly(SourceSeen.sourceSeen("kiln:github", some(FROZEN), 60),
                                                    SourceSeen.sourceSeen("kiln:highwater", some(NOW), 60));
        assertThat(KilnPlan.sourceName("github")).isEqualTo("kiln:github");
        assertThat(KilnPlan.sourceName("kiln:highwater")).isEqualTo("kiln:highwater");
        assertThat(KilnPlan.sourceName("host:bigboy")).isEqualTo("kiln:host:bigboy");
    }

    @Test
    void decode_readsTheThreeShapes_andIgnoresUnknownFields() {
        var mapper = TemperJson.mapper();
        var changes = mapper.readTree("""
            {"epoch":"E1","floor":1,"head":2,"asOf":"2026-11-03T12:00:00Z","extra":true,
             "sources":{"github":{"lastOkAt":null,"periodS":60}},
             "changes":[{"id":2,"kind":"PR_STATE","repo":"o/r","pr":3,"subject":"s","before":"a","after":"b",
                         "observedAt":"2026-11-03T11:00:00Z","recordedAt":"2026-11-03T11:00:01Z","payload":{}}]}""").flatMap(KilnReply::decode).unwrap();
        var reset = mapper.readTree("{\"reset\":{\"epoch\":\"E2\"},\"head\":4,\"asOf\":\"x\",\"sources\":{}}").flatMap(KilnReply::decode).unwrap();
        var gap = mapper.readTree("{\"gap\":{\"from\":3,\"to\":9},\"epoch\":\"E1\",\"floor\":9,\"head\":12,\"asOf\":\"x\",\"sources\":{}}")
                        .flatMap(KilnReply::decode)
                        .unwrap();

        assertThat(changes).isInstanceOfSatisfying(KilnReply.Changes.class, value -> {
            assertThat(value.changes()).singleElement().satisfies(change -> assertThat(change.pr()).isEqualTo(some(3L)));
            assertThat(value.sources().get("github").lastOkAt()).isEqualTo(none());
        });
        assertThat(reset).isEqualTo(new KilnReply.Reset("E2", 4, Map.of()));
        assertThat(gap).isEqualTo(new KilnReply.Gap(3, 9, "E1", 9, 12, Map.of()));
    }

    private static KilnBatch plan(FleetSourceRow state, KilnReply reply) {
        return KilnPlan.plan(state, reply, NOW);
    }

    private static FleetSourceRow state(String epoch, long cursor, int generation, long catchUpTo) {
        return FleetSourceRow.fleetSourceRow("kiln", some(epoch), cursor, some(NOW), 5, generation, catchUpTo, cursor, false);
    }

    private static KilnReply.Changes changes(String epoch, long head, KilnReply.Change... changes) {
        return new KilnReply.Changes(epoch, 1, head, Map.of(), List.of(changes));
    }

    private static KilnReply.Change change(long id, String kind) {
        var payload = TemperJson.mapper().readTree("{\"k\":\"v\"}").unwrap();

        return new KilnReply.Change(id, kind, some("o/r"), some(7L), "o/r#7", some("a"), some("b"), NOW, none(), payload);
    }
}
