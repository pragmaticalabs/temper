package org.pragmatica.kiln.core.summary;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The facts a `PR_STATE` row carries ([Changes#prStatePayload]) and the first-sighting merge
/// ([Changes#firstMerged]): a PR first seen merged after the backfill, merged at or after it, is `none` → `merged`;
/// the backfill itself (no `backfill_done`), an older merge, a stored PR and a first sight not merged give none.
class PrStateFactsTest {
    private static final Instant DONE = Instant.parse("2026-11-03T10:00:00Z");
    private static final Instant AT = DONE.plusSeconds(300);

    private static PrNumber pr(long n) {
        return ok(PrNumber.prNumber(n));
    }

    private static Sha sha(char digit) {
        return ok(Sha.sha(String.valueOf(digit).repeat(40)));
    }

    private static BranchName branch(String name) {
        return ok(BranchName.branchName(name));
    }

    private static PullRequest listed(long n, PrState state, char head, Option<Instant> mergedAt) {
        return new PullRequest(pr(n),
                               "PR " + n,
                               state,
                               false,
                               branch("release-1.0.0-rc5"),
                               branch("feature-" + n),
                               some("pragmaticalabs/pragmatica"),
                               sha(head),
                               mergedAt,
                               mergedAt,
                               DONE,
                               List.of(),
                               List.of(),
                               List.of());
    }

    private static PullRequest merged(long n, Instant at) {
        return listed(n, PrState.MERGED, 'a', some(at));
    }

    private static Change firstMerge(long n) {
        return new Change(AT, pr(n), ChangeKind.PR_STATE, "#" + n, "none", "merged", AT);
    }

    @Test
    void prStatePayload_carriesHeadBaseHeadRefAndMergedAt_inOrder() {
        var payload = Changes.prStatePayload(listed(7, PrState.MERGED, 'b', some(DONE)));

        assertThat(payload).containsExactly(Map.entry("head", "b".repeat(40)),
                                            Map.entry("base", "release-1.0.0-rc5"),
                                            Map.entry("headRef", "feature-7"),
                                            Map.entry("mergedAt", "2026-11-03T10:00:00Z"));
    }

    @Test
    void prStatePayload_leavesMergedAtOut_whenNotMerged() {
        assertThat(Changes.prStatePayload(listed(7, PrState.OPEN, 'c', none()))).containsOnlyKeys("head", "base", "headRef");
    }

    /// ISO-8601 UTC at second precision (contract "ISO"), whatever precision the instant carries.
    @Test
    void prStatePayload_writesMergedAtToTheSecond() {
        var payload = Changes.prStatePayload(listed(7, PrState.MERGED, 'b', some(DONE.plusMillis(1_250))));

        assertThat(payload).containsEntry("mergedAt", "2026-11-03T10:00:01Z");
    }

    @Test
    void firstMerged_recordsNoneToMerged_forAPrFirstSeenMergedAfterTheBackfill() {
        assertThat(Changes.firstMerged(List.of(merged(9, DONE.plusSeconds(10))), Set.of(), some(DONE), AT)).containsExactly(firstMerge(9));
    }

    /// `merged_at >= backfill_done`: a merge at the very instant counts.
    @Test
    void firstMerged_recordsAMergeAtTheBackfillInstant() {
        assertThat(Changes.firstMerged(List.of(merged(9, DONE)), Set.of(), some(DONE), AT)).containsExactly(firstMerge(9));
    }

    @Test
    void firstMerged_recordsNothing_duringTheBackfill() {
        assertThat(Changes.firstMerged(List.of(merged(9, DONE.plusSeconds(10))), Set.of(), none(), AT)).isEmpty();
    }

    @Test
    void firstMerged_recordsNothing_forAMergeBeforeTheBackfill() {
        assertThat(Changes.firstMerged(List.of(merged(9, DONE.minusSeconds(1))), Set.of(), some(DONE), AT)).isEmpty();
    }

    /// A stored PR's merge is the change rule's (`open` → `merged`), never a first sighting too.
    @Test
    void firstMerged_recordsNothing_forAStoredPr() {
        assertThat(Changes.firstMerged(List.of(merged(9, DONE.plusSeconds(10))), Set.of(pr(9)), some(DONE), AT)).isEmpty();
    }

    @Test
    void firstMerged_recordsNothing_forAFirstSightNotMerged() {
        var open = listed(9, PrState.OPEN, 'a', none());
        var closedWithADate = listed(10, PrState.CLOSED, 'a', some(DONE.plusSeconds(10)));

        assertThat(Changes.firstMerged(List.of(open, closedWithADate), Set.of(), some(DONE), AT)).isEmpty();
    }

    /// The last copy of a PR listed twice decides, as the upsert's last write does; the rest sort as the rule's.
    @Test
    void firstMerged_judgesTheLastCopy_andSortsByPr() {
        var listed = List.of(merged(12, DONE.plusSeconds(10)),
                             merged(11, DONE.plusSeconds(10)),
                             listed(12, PrState.OPEN, 'b', none()),
                             listed(13, PrState.OPEN, 'b', none()),
                             merged(13, DONE.plusSeconds(20)));

        assertThat(Changes.firstMerged(listed, Set.of(), some(DONE), AT)).containsExactly(firstMerge(11), firstMerge(13));
    }
}
