package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.branch;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pr;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.pullRequest;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The first-sighting rule (positive control R3-2.h) with the production change rule: every `PR_STATE` row carries
/// `{"head","base","headRef","mergedAt"?}` from the listed PR (a merged PR's last head), other kinds keep a NULL
/// payload, and a PR first seen merged after `backfill_done` with `merged_at >= backfill_done` records `none` →
/// `merged`; without `backfill_done` (the backfill itself) and for an older merge, nothing.
///
/// Probes (WP-0K state file): insert `PR_STATE` rows without a payload → [#prStateRow_carriesTheListedPrsFacts] red;
/// take a PR's first listing for its facts → [#prStateRow_carriesTheLastListing_ofAPrListedTwice] red;
/// drop [org.pragmatica.kiln.core.summary.Changes#firstMerged] from `PgPrStore.changes` →
/// [#firstSightingMerged_afterTheBackfill_recordsNoneToMerged] red; read `backfill_done` as always set →
/// [#firstSightingMerged_beforeTheBackfillIsDone_recordsNothing] red.
@Tag("pg")
class PrStatePayloadIT {
    private static final TypeToken<Map<String, Object>> OBJECT = new TypeToken<>() {};

    private KilnStore store;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
    }

    @AfterEach
    void stop() {
        await(store.close());
    }

    /// R3-2.h: open → merged with the head moved on the way: the row names the merged PR's last head, its base and
    /// head ref, and the merge instant.
    @Test
    void prStateRow_carriesTheListedPrsFacts() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));

        await(store.prs().upsert(List.of(merged(1, 'b', T1)), T2));

        assertThat(feed().changes()).singleElement()
                                    .satisfies(row -> assertThat(row.kind()).isEqualTo("PR_STATE"))
                                    .satisfies(row -> assertThat(row.before() + ">" + row.after()).isEqualTo("open>merged"))
                                    .satisfies(row -> assertThat(payload(row)).containsExactlyInAnyOrderEntriesOf(Map.of("head",
                                                                                                                        sha('b').value(),
                                                                                                                        "base",
                                                                                                                        "release-1.0.0-rc5",
                                                                                                                        "headRef",
                                                                                                                        "feature-1",
                                                                                                                        "mergedAt",
                                                                                                                        "2026-10-30T10:01:00Z")));
    }

    /// A PR listed twice in one upsert (overlapping pages): the row's facts are the last listing's, as the stored row is.
    @Test
    void prStateRow_carriesTheLastListing_ofAPrListedTwice() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a')), T0));

        await(store.prs().upsert(List.of(merged(1, 'c', T1), merged(1, 'b', T1)), T2));

        assertThat(feed().changes()).singleElement()
                                    .satisfies(row -> assertThat(payload(row)).containsEntry("head", sha('b').value()));
        assertThat(await(store.prs().all()).getFirst().head()).isEqualTo(sha('b'));
    }

    /// A PR closed unmerged has no `mergedAt` key; the head move of an open PR keeps its NULL payload.
    @Test
    void prStateRow_leavesMergedAtOut_andOtherKindsKeepNoPayload() {
        await(store.prs().upsert(List.of(pullRequest(1, PrState.OPEN, 'a'), pullRequest(2, PrState.OPEN, 'a')), T0));

        await(store.prs().upsert(List.of(pullRequest(1, PrState.CLOSED, 'a'), pullRequest(2, PrState.OPEN, 'c')), T1));

        var rows = feed().changes();
        assertThat(rows).extracting(ChangeFeed.Row::kind).containsExactly("PR_STATE", "HEAD_MOVED");
        assertThat(payload(rows.getFirst())).containsOnlyKeys("head", "base", "headRef")
                                            .containsEntry("head", sha('a').value());
        assertThat(rows.get(1).payload()).isEqualTo(none());
    }

    @Test
    void firstSightingMerged_afterTheBackfill_recordsNoneToMerged() {
        await(store.prs().backfillDone(T0));

        var changes = await(store.prs().upsert(List.of(merged(3, 'd', T1)), T2));

        assertThat(changes).singleElement()
                           .satisfies(change -> assertThat(change.kind()).isEqualTo(ChangeKind.PR_STATE))
                           .satisfies(change -> assertThat(change.pr()).isEqualTo(pr(3)))
                           .satisfies(change -> assertThat(change.before() + ">" + change.after()).isEqualTo("none>merged"))
                           .satisfies(change -> assertThat(change.observedAt()).isEqualTo(T2));
        assertThat(feed().changes()).singleElement()
                                    .satisfies(row -> assertThat(row.subject()).isEqualTo("#3"))
                                    .satisfies(row -> assertThat(payload(row)).containsEntry("head", sha('d').value())
                                                                              .containsEntry("mergedAt", "2026-10-30T10:01:00Z"));
    }

    /// The backfill (no `backfill_done` yet) records nothing for a PR first seen merged, so a rebuilt kiln does not
    /// flood; nor does a merge older than `backfill_done`, or a second sighting of a stored merged PR.
    @Test
    void firstSightingMerged_beforeTheBackfillIsDone_recordsNothing() {
        var backfill = await(store.prs().upsert(List.of(merged(3, 'd', T1)), T2));
        await(store.prs().backfillDone(T2));
        var older = await(store.prs().upsert(List.of(merged(4, 'e', T1)), T2));
        var again = await(store.prs().upsert(List.of(merged(3, 'd', T1)), T2.plusSeconds(60)));

        assertThat(backfill).isEmpty();
        assertThat(older).isEmpty();
        assertThat(again).isEmpty();
        assertThat(feed().changes()).isEmpty();
    }

    private static PullRequest merged(long number, char head, Instant mergedAt) {
        return new PullRequest(pr(number),
                               "PR " + number,
                               PrState.MERGED,
                               false,
                               branch("release-1.0.0-rc5"),
                               branch("feature-" + number),
                               some("pragmaticalabs/pragmatica"),
                               sha(head),
                               some(mergedAt),
                               some(mergedAt),
                               mergedAt,
                               List.of(),
                               List.of(),
                               List.of());
    }

    private static Map<String, Object> payload(ChangeFeed.Row row) {
        return Json.read(row.payload().or("{}"), OBJECT).unwrap();
    }

    private ChangeFeed feed() {
        return await(store.changes().feed(0, 500));
    }
}
