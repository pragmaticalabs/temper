package org.pragmatica.temper.controller.t2;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.store.api.DetectorBatch;
import org.pragmatica.temper.store.api.DetectorInput;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.GoBasis;
import org.pragmatica.temper.store.api.GoInvalidation;
import org.pragmatica.temper.store.api.MergeFacts;
import org.pragmatica.temper.store.api.MergeKey;
import org.pragmatica.temper.store.api.MergeRow;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.OpenGo;
import org.pragmatica.temper.store.api.QueueEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [T2Plan]: the cursor and the gap, the catch-up guard (R3-2.f), the `pr-state` merged rule with its dedupe,
/// gating and fallbacks (R3-2.a, b, e, g), the queue removal (Q.c), and the C8 payloads.
class T2PlanTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final Instant STARTED = Instant.parse("2026-11-03T11:00:00Z");
    private static final String SHA = "a".repeat(40);
    private static final String TIP = "c".repeat(40);
    private static final String HEAD = "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"head\":\"" + SHA
                                       + "\",\"base\":\"main\",\"mergedAt\":\"2026-11-03T11:59:00Z\"}";
    private static final MergeFacts NOTHING = MergeFacts.mergeFacts(none(), false, List.of());

    // ---- cursor and gap ----------------------------------------------------------------------------------------
    @Test
    void plan_rows_moveTheCursorToTheLastSeqRead() {
        var batch = plan(events(row(4, "host-answering", "{}"), row(9, "check-cell", "{}")), List.of());

        assertThat(batch.cursor()).isEqualTo(9);
        assertThat(batch.lastOkAt()).isEqualTo(NOW);
        assertThat(batch.events()).isEmpty();
    }

    @Test
    void plan_noRows_keepsTheCursor_andStillRecordsTheSuccess() {
        var batch = T2Plan.plan(DetectorInput.detectorInput(17, some(STARTED), new FleetPending.Events(List.of())), List.of(), NOW);

        assertThat(batch.cursor()).isEqualTo(17);
        assertThat(batch.lastOkAt()).isEqualTo(NOW);
    }

    /// `cursor < floor` → cursor = floor and `gap t2:detector {from,to}`; the rows missed are a declared loss.
    @Test
    void plan_gap_movesTheCursorToTheFloor_andAnnouncesTheGap() {
        var input = DetectorInput.detectorInput(3, some(STARTED), new FleetPending.Gap(4, 40));
        var batch = T2Plan.plan(input, List.of(), NOW);

        assertThat(T2Plan.sightings(input)).isEmpty();
        assertThat(batch.cursor()).isEqualTo(40);
        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("gap");
            assertThat(event.subject()).isEqualTo("t2:detector");
            assertThat(event.source()).isEqualTo("controller");
            assertThat(event.payload()).isEqualTo("{\"from\":4,\"to\":40}");
            assertThat(event.catchUp()).isFalse();
        });
    }

    // ---- which rows are merges ---------------------------------------------------------------------------------
    @Test
    void sightings_prStateMerged_isOne_withTheHeadBaseAndKilnFields() {
        var sightings = T2Plan.sightings(events(row(5, "pr-state", HEAD.replace("}", ",\"mergedBy\":\"octocat\"}"))));

        assertThat(sightings).singleElement().satisfies(sighting -> {
            assertThat(sighting.seq()).isEqualTo(5);
            assertThat(sighting.key()).isEqualTo(MergeKey.mergeKey("o/r", 7, SHA));
            assertThat(sighting.base()).isEqualTo("main");
            assertThat(sighting.mergedBy()).isEqualTo(some("octocat"));
            assertThat(sighting.mergedAt()).isEqualTo(some(Instant.parse("2026-11-03T11:59:00Z")));
        });
    }

    @Test
    void sightings_otherStatesAndOtherKinds_areNone_theDetectorsOwnKindsIncluded() {
        var payload = HEAD.replace("\"after\":\"merged\"", "\"after\":\"closed\"");

        assertThat(T2Plan.sightings(events(row(1, "pr-state", payload),
                                           row(2, "ungated-merge", HEAD),
                                           row(3, "merged", HEAD),
                                           row(4, "pr-head-moved", HEAD)))).isEmpty();
    }

    /// A row that cannot be attributed (no repo, no positive PR) is skipped: a `merges` row needs both.
    @Test
    void sightings_withoutRepoOrPositivePr_areNone() {
        assertThat(T2Plan.sightings(events(row(1, "pr-state", HEAD.replace("\"repo\":\"o/r\",", "")),
                                           row(2, "pr-state", HEAD.replace("\"pr\":7", "\"pr\":0")),
                                           row(3, "pr-state", HEAD.replace("\"pr\":7", "\"pr\":\"7\"")),
                                           row(4, "pr-state", HEAD.replace("\"repo\":\"o/r\"", "\"repo\":\"\""))))).isEmpty();
    }

    /// R3-2.g: no `head` (a kiln without WP-0K) → `sha=unknown`; so is a head that is not 40 lower-case hex (the
    /// `merges` CHECK would refuse it and stall the detector on that row).
    @Test
    void sightings_withoutAUsableHead_haveTheShaUnknown_andTheBaseUnknownWhenAbsent() {
        var sightings = T2Plan.sightings(events(row(1, "pr-state", "{\"repo\":\"o/r\",\"pr\":7,\"after\":\"merged\"}"),
                                                row(2, "pr-state", HEAD.replace(SHA, "ABC")),
                                                row(3, "pr-state", HEAD.replace(SHA, SHA.toUpperCase()))));

        assertThat(sightings).extracting(sighting -> sighting.key().sha()).containsOnly("unknown");
        assertThat(sightings.getFirst().base()).isEqualTo("unknown");
        assertThat(sightings.getFirst().mergedBy()).isEqualTo(none());
        assertThat(sightings.getFirst().mergedAt()).isEqualTo(none());
    }

    @Test
    void sightings_aMergedAtThatIsNotIso_isDropped_theMergeStays() {
        assertThat(T2Plan.sightings(events(row(1, "pr-state", HEAD.replace("2026-11-03T11:59:00Z", "yesterday")))))
            .singleElement()
            .satisfies(sighting -> assertThat(sighting.mergedAt()).isEqualTo(none()));
    }

    /// R3-2.f: a catch-up row observed before `started_at` is skipped (no flood on a first pull or a kiln rewind); a
    /// catch-up row observed after it, and a live row observed before it, are processed.
    @Test
    void sightings_catchUpObservedBeforeStartedAt_isSkipped_everyOtherRowIsProcessed() {
        var sightings = T2Plan.sightings(events(row(1, "pr-state", HEAD, true, STARTED.minusSeconds(1)),
                                                row(2, "pr-state", HEAD, true, STARTED),
                                                row(3, "pr-state", HEAD, true, STARTED.plusSeconds(1)),
                                                row(4, "pr-state", HEAD, false, STARTED.minusSeconds(1))));

        assertThat(sightings).extracting(T2Plan.Sighting::seq).containsExactly(2L, 3L, 4L);
    }

    @Test
    void sightings_withoutStartedAt_processCatchUp() {
        var input = DetectorInput.detectorInput(0, none(), new FleetPending.Events(List.of(row(1, "pr-state", HEAD, true, STARTED))));

        assertThat(T2Plan.sightings(input)).hasSize(1);
    }

    // ---- the merged rule ---------------------------------------------------------------------------------------
    /// R3-2.a: no GO → one `ungated-merge` (C8 payload, in order) and a `merges` row on route kiln without a GO.
    @Test
    void plan_mergeWithoutAGatingGo_isOneUngatedMerge_andAMergesRow() {
        var batch = planned(List.of(row(5, "pr-state", HEAD)), NOTHING);

        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("ungated-merge");
            assertThat(event.subject()).isEqualTo("o/r#7");
            assertThat(event.source()).isEqualTo("controller");
            assertThat(event.catchUp()).isFalse();
            assertThat(event.observedAt()).isEqualTo(NOW);
            assertThat(event.payload()).isEqualTo("{\"sha\":\"" + SHA + "\",\"by\":\"unknown\",\"base\":\"main\",\"mergedAt\":\"2026-11-03T11:59:00Z\",\"prStateSeq\":5}");
        });
        assertThat(batch.merges()).containsExactly(MergeRow.mergeRow(MergeKey.mergeKey("o/r", 7, SHA),
                                                                     "main",
                                                                     none(),
                                                                     "kiln",
                                                                     none(),
                                                                     some(Instant.parse("2026-11-03T11:59:00Z")),
                                                                     some(5L)));
        assertThat(batch.cursor()).isEqualTo(5);
    }

    @Test
    void plan_ungatedMerge_withKilnsMergedBy_andNoMergedAt_namesTheLogin_andOmitsTheTime() {
        var payload = "{\"repo\":\"o/r\",\"pr\":7,\"after\":\"merged\",\"mergedBy\":\"octocat\"}";

        assertThat(planned(List.of(row(5, "pr-state", payload)), NOTHING).events())
            .singleElement()
            .satisfies(event -> assertThat(event.payload()).isEqualTo("{\"sha\":\"unknown\",\"by\":\"octocat\",\"base\":\"unknown\",\"prStateSeq\":5}"));
    }

    /// R3-2.b: a gating GO → `merged` route kiln (C8 payload, in order), the row carries the GO; no `ungated-merge`.
    @Test
    void plan_mergeWithAGatingGo_isMergedRouteKiln() {
        var gated = MergeFacts.mergeFacts(some(MergeFacts.GatingGo.gatingGo("g-1", some(TIP))), false, List.of());
        var batch = planned(List.of(row(5, "pr-state", HEAD)), gated);

        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.kind()).isEqualTo("merged");
            assertThat(event.subject()).isEqualTo("o/r#7");
            assertThat(event.payload()).isEqualTo("{\"go\":\"g-1\",\"sha\":\"" + SHA + "\",\"base\":\"main\",\"baseTipBefore\":\"" + TIP + "\",\"route\":\"kiln\"}");
        });
        assertThat(batch.merges()).singleElement().satisfies(merge -> assertThat(merge.goId()).isEqualTo(some("g-1")));
    }

    @Test
    void plan_gatingGoWithoutABaseTip_omitsBaseTipBefore() {
        var gated = MergeFacts.mergeFacts(some(MergeFacts.GatingGo.gatingGo("g-1", none())), false, List.of());

        assertThat(planned(List.of(row(5, "pr-state", HEAD)), gated).events().getFirst().payload())
            .isEqualTo("{\"go\":\"g-1\",\"sha\":\"" + SHA + "\",\"base\":\"main\",\"route\":\"kiln\"}");
    }

    /// R3-2.e: a merge recorded already (the `temper merge` route, or an earlier batch) does nothing more, queue
    /// included; a merge seen twice in one batch (a kiln rewind's replay) is announced once.
    @Test
    void plan_mergeRecordedAlready_doesNothing() {
        var recorded = MergeFacts.mergeFacts(none(), true, List.of(QueueEntry.queueEntry("o/r", "main", 1, 7)));
        var batch = planned(List.of(row(5, "pr-state", HEAD)), recorded);

        assertThat(batch.events()).isEmpty();
        assertThat(batch.merges()).isEmpty();
        assertThat(batch.dequeued()).isEmpty();
        assertThat(batch.cursor()).isEqualTo(5);
    }

    @Test
    void plan_sameMergeTwiceInOneBatch_isAnnouncedOnce() {
        var batch = planned(List.of(row(5, "pr-state", HEAD), row(6, "pr-state", HEAD)), NOTHING);

        assertThat(batch.events()).extracting(NewFleetEvent::kind).containsExactly("ungated-merge");
        assertThat(batch.merges()).extracting(MergeRow::eventSeq).containsExactly(some(5L));
    }

    @Test
    void plan_twoMergesOfTheSamePrAtDifferentShas_areAnnouncedOnce() {
        var batch = planned(List.of(row(5, "pr-state", HEAD), row(6, "pr-state", HEAD.replace(SHA, "b".repeat(40)))), NOTHING);

        assertThat(batch.events()).extracting(NewFleetEvent::kind).containsExactly("ungated-merge");
        assertThat(batch.merges()).hasSize(1);
    }

    // ---- the queue ---------------------------------------------------------------------------------------------
    /// Q.c: the merged PR leaves its queue, the later positions move down, `queue-advanced` names the new head.
    @Test
    void plan_mergedHeadOfTheQueue_leavesIt_andQueueAdvancedNamesTheNext() {
        var facts = MergeFacts.mergeFacts(none(), false, queue(7, 9, 11));
        var batch = planned(List.of(row(5, "pr-state", HEAD)), facts);

        assertThat(batch.dequeued()).containsExactly(QueueEntry.queueEntry("o/r", "main", 1, 7));
        assertThat(batch.events()).extracting(NewFleetEvent::kind).containsExactly("ungated-merge", "queue-advanced");
        assertThat(batch.events().getLast()).satisfies(event -> {
            assertThat(event.subject()).isEqualTo("main");
            assertThat(event.source()).isEqualTo("controller");
            assertThat(event.payload()).isEqualTo("{\"repo\":\"o/r\",\"base\":\"main\",\"merged\":\"o/r#7\",\"next\":\"o/r#9\"}");
        });
    }

    @Test
    void plan_mergedLastOfTheQueue_emptiesIt_andNextIsAbsent() {
        var batch = planned(List.of(row(5, "pr-state", HEAD)), MergeFacts.mergeFacts(none(), false, queue(7)));

        assertThat(batch.events().getLast().payload()).isEqualTo("{\"repo\":\"o/r\",\"base\":\"main\",\"merged\":\"o/r#7\"}");
    }

    @Test
    void plan_mergedPrBehindTheHead_leaves_andNextIsStillTheHead() {
        var batch = planned(List.of(row(5, "pr-state", HEAD)), MergeFacts.mergeFacts(none(), false, queue(3, 7, 9)));

        assertThat(batch.dequeued()).containsExactly(QueueEntry.queueEntry("o/r", "main", 2, 7));
        assertThat(batch.events().getLast().payload()).contains("\"next\":\"o/r#3\"");
    }

    /// Two PRs of one queue merged in one batch: the second removal sees the queue the first left.
    @Test
    void plan_twoMergesFromOneQueue_shiftTwice() {
        var read = queue(7, 9, 11);
        var nine = HEAD.replace("\"pr\":7", "\"pr\":9");
        var observed = List.of(T2Plan.Observed.observed(T2Plan.sightings(events(row(5, "pr-state", HEAD))).getFirst(),
                                                   MergeFacts.mergeFacts(none(), false, read)),
                               T2Plan.Observed.observed(T2Plan.sightings(events(row(6, "pr-state", nine))).getFirst(),
                                                   MergeFacts.mergeFacts(none(), false, read)));
        var batch = T2Plan.plan(events(row(5, "pr-state", HEAD), row(6, "pr-state", nine)), observed, NOW);

        assertThat(batch.dequeued()).containsExactly(QueueEntry.queueEntry("o/r", "main", 1, 7), QueueEntry.queueEntry("o/r", "main", 1, 9));
        assertThat(batch.events()).filteredOn(event -> event.kind().equals("queue-advanced"))
                                  .extracting(NewFleetEvent::payload)
                                  .containsExactly("{\"repo\":\"o/r\",\"base\":\"main\",\"merged\":\"o/r#7\",\"next\":\"o/r#9\"}",
                                                   "{\"repo\":\"o/r\",\"base\":\"main\",\"merged\":\"o/r#9\",\"next\":\"o/r#11\"}");
    }

    /// The same PR at another sha later in the batch is the same merge (R3-2.i): it leaves the queue once, announced once.
    @Test
    void plan_aPrMergedAtTwoShasInThisBatch_isRemovedAndAnnouncedOnce() {
        var read = queue(7, 9);
        var other = HEAD.replace(SHA, "b".repeat(40));
        var observed = List.of(T2Plan.Observed.observed(T2Plan.sightings(events(row(5, "pr-state", HEAD))).getFirst(),
                                                   MergeFacts.mergeFacts(none(), false, read)),
                               T2Plan.Observed.observed(T2Plan.sightings(events(row(6, "pr-state", other))).getFirst(),
                                                   MergeFacts.mergeFacts(none(), false, read)));
        var batch = T2Plan.plan(events(row(5, "pr-state", HEAD), row(6, "pr-state", other)), observed, NOW);

        assertThat(batch.dequeued()).hasSize(1);
        assertThat(batch.events()).extracting(NewFleetEvent::kind).containsExactly("ungated-merge", "queue-advanced");
    }

    /// Two queues losing a PR each in one batch: each removal reads its own queue, not the other's.
    @Test
    void plan_mergesFromTwoQueues_eachLeavesItsOwn() {
        var dev = List.of(QueueEntry.queueEntry("o/r", "dev", 1, 9), QueueEntry.queueEntry("o/r", "dev", 2, 3));
        var nine = HEAD.replace("\"pr\":7", "\"pr\":9");
        var observed = List.of(T2Plan.Observed.observed(T2Plan.sightings(events(row(5, "pr-state", HEAD))).getFirst(),
                                                        MergeFacts.mergeFacts(none(), false, queue(7, 11))),
                               T2Plan.Observed.observed(T2Plan.sightings(events(row(6, "pr-state", nine))).getFirst(),
                                                        MergeFacts.mergeFacts(none(), false, dev)));
        var batch = T2Plan.plan(events(row(5, "pr-state", HEAD), row(6, "pr-state", nine)), observed, NOW);

        assertThat(batch.dequeued()).containsExactly(QueueEntry.queueEntry("o/r", "main", 1, 7), QueueEntry.queueEntry("o/r", "dev", 1, 9));
        assertThat(batch.events()).filteredOn(event -> event.kind().equals("queue-advanced"))
                                  .extracting(NewFleetEvent::payload)
                                  .containsExactly("{\"repo\":\"o/r\",\"base\":\"main\",\"merged\":\"o/r#7\",\"next\":\"o/r#11\"}",
                                                   "{\"repo\":\"o/r\",\"base\":\"dev\",\"merged\":\"o/r#9\",\"next\":\"o/r#3\"}");
    }

    // ---- GO invalidation (WP-A2) -----------------------------------------------------------------------------
    /// GO.d, R3-3.b: an outstanding GO a later row matches is invalidated with that row's seq as its cause, and
    /// `go-invalidated` carries `go`, `sha`, `base`, `reason`, `causeSeq`; a GO no row matches stays.
    @Test
    void plan_matchingRows_invalidateTheirGos_withTheCauseSeq() {
        var byBase = OpenGo.openGo("g1", GoBasis.goBasis("o/r", 7, SHA, "main", TIP, 3));
        var byCheck = OpenGo.openGo("g2", GoBasis.goBasis("o/r", 8, SHA, "dev", TIP, 3));
        var untouched = OpenGo.openGo("g3", GoBasis.goBasis("o/r", 9, SHA, "dev", TIP, 3));
        var input = DetectorInput.detectorInput(0,
                                                some(STARTED),
                                                new FleetPending.Events(List.of(row(4, "check-concluded", "{\"repo\":\"o/r\",\"pr\":8,\"head\":\"" + SHA + "\"}"),
                                                                                row(5, "base-moved", "{\"base\":\"main\",\"treeChanged\":true,\"repo\":\"o/r\",\"after\":\"" + "d".repeat(40) + "\"}"),
                                                                                row(6, "check-cell", "{\"repo\":\"o/r\",\"pr\":8}"))),
                                                List.of(byBase, byCheck, untouched));

        var batch = plan(input, List.of());

        assertThat(batch.invalidated()).containsExactly(GoInvalidation.goInvalidation(byCheck, "checks-changed", 4),
                                                        GoInvalidation.goInvalidation(byBase, "base-moved", 5));
        assertThat(batch.events()).extracting(NewFleetEvent::kind, NewFleetEvent::subject, NewFleetEvent::payload, NewFleetEvent::source)
                                  .containsExactly(tuple("go-invalidated", "o/r#8",
                                                         "{\"go\":\"g2\",\"sha\":\"" + SHA + "\",\"base\":\"dev\",\"reason\":\"checks-changed\",\"causeSeq\":4}", "controller"),
                                                   tuple("go-invalidated", "o/r#7",
                                                         "{\"go\":\"g1\",\"sha\":\"" + SHA + "\",\"base\":\"main\",\"reason\":\"base-moved\",\"causeSeq\":5}", "controller"));
        assertThat(batch.cursor()).isEqualTo(6);
    }

    /// GO.e, R3-3.d at the plan: an empty move and a row at or below the GO's fleet seq invalidate nothing.
    @Test
    void plan_emptyMove_orRowAtFleetSeq_invalidatesNothing() {
        var go = OpenGo.openGo("g1", GoBasis.goBasis("o/r", 7, SHA, "main", TIP, 5));
        var input = DetectorInput.detectorInput(0,
                                                some(STARTED),
                                                new FleetPending.Events(List.of(row(5, "check-cell", "{\"repo\":\"o/r\",\"pr\":7}"),
                                                                                row(6, "base-moved", "{\"base\":\"main\",\"treeChanged\":false,\"repo\":\"o/r\",\"after\":\"" + "d".repeat(40) + "\"}"))),
                                                List.of(go));

        var batch = plan(input, List.of());

        assertThat(batch.invalidated()).isEmpty();
        assertThat(batch.events()).isEmpty();
    }

    /// A gap invalidates nothing (the rows retention deleted are a declared loss).
    @Test
    void plan_gap_invalidatesNothing() {
        var go = OpenGo.openGo("g1", GoBasis.goBasis("o/r", 7, SHA, "main", TIP, 0));

        assertThat(plan(DetectorInput.detectorInput(3, some(STARTED), new FleetPending.Gap(4, 40), List.of(go)), List.of()).invalidated()).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------
    /// The plan over `rows` with the same `facts` for every sighting.
    private static DetectorBatch planned(List<FleetEventRow> rows, MergeFacts facts) {
        var input = DetectorInput.detectorInput(0, some(STARTED), new FleetPending.Events(rows));

        return T2Plan.plan(input,
                           T2Plan.sightings(input)
                                 .stream()
                                 .map(sighting -> T2Plan.Observed.observed(sighting, facts))
                                 .toList(),
                           NOW);
    }

    private static DetectorBatch plan(DetectorInput input, List<T2Plan.Observed> observed) {
        return T2Plan.plan(input, observed, NOW);
    }

    private static List<QueueEntry> queue(long... prs) {
        return Stream.iterate(0, index -> index + 1)
                     .limit(prs.length)
                     .map(index -> QueueEntry.queueEntry("o/r", "main", index + 1, prs[index]))
                     .toList();
    }

    private static DetectorInput events(FleetEventRow... rows) {
        return DetectorInput.detectorInput(0, some(STARTED), new FleetPending.Events(List.of(rows)));
    }

    private static FleetEventRow row(long seq, String kind, String payload) {
        return row(seq, kind, payload, false, NOW);
    }

    private static FleetEventRow row(long seq, String kind, String payload, boolean catchUp, Instant observedAt) {
        return FleetEventRow.fleetEventRow("E", seq, NOW, observedAt, kind, "7", catchUp, "kiln", payload);
    }
}
