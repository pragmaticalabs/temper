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
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T0;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T1;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.T2;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.await;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.branch;
import static org.pragmatica.kiln.service.store.pg.StoreFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The WP-0K follow-ups (lead ruling 2026-11-04): the verifier's ordering test, adopted as written
/// (it pins `.sorted(Changes.ORDER)` in `PgPrStore`), and `backfill_done` compared truncated to the second.
@Tag("pg")
class PrStateOrderIT {
    private static final TypeToken<Map<String, Object>> OBJECT = new TypeToken<>() {};
    private static final Instant T3 = T0.plusSeconds(180);

    private KilnStore store;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
    }

    @AfterEach
    void stop() {
        await(store.close());
    }

    /// One cycle: a stored open PR (#9) merges and an unseen PR (#6) is first seen merged. Both rows, in PR order, each
    /// with its own facts; ids follow that order.
    @Test
    void storedMergeAndFirstSighting_inOneCycle_bothRecorded_inPrOrder() {
        await(store.prs().backfillDone(T0));
        await(store.prs().upsert(List.of(pr(9, PrState.OPEN, 'a', none())), T1));

        await(store.prs().upsert(List.of(pr(9, PrState.MERGED, 'b', some(T2)), pr(6, PrState.MERGED, 'd', some(T2))), T3));

        var states = prStates();
        assertThat(states).extracting(ChangeFeed.Row::subject).containsExactly("#6", "#9");
        assertThat(states).extracting(row -> row.before() + ">" + row.after()).containsExactly("none>merged", "open>merged");
        assertThat(payload(states.get(0))).containsEntry("head", sha('d').value());
        assertThat(payload(states.get(1))).containsEntry("head", sha('b').value());
    }

    /// `backfill_done` is the cycle instant (sub-second), `merged_at` GitHub's (whole seconds): compared truncated to the
    /// second, a merge in the backfill's own second is recorded.
    @Test
    void mergeInTheBackfillsOwnSecond_isRecorded() {
        await(store.prs().backfillDone(T0.plusMillis(500)));
        await(store.prs().upsert(List.of(pr(5, PrState.MERGED, 'f', some(T0))), T2));

        assertThat(prStates()).singleElement().satisfies(row -> assertThat(row.before() + ">" + row.after()).isEqualTo("none>merged"));
    }

    /// A merge before the backfill's second stays unrecorded.
    @Test
    void mergeBeforeTheBackfillsSecond_recordsNothing() {
        await(store.prs().backfillDone(T0.plusMillis(500)));
        await(store.prs().upsert(List.of(pr(5, PrState.MERGED, 'f', some(T0.minusSeconds(1)))), T2));

        assertThat(prStates()).isEmpty();
    }

    private static PullRequest pr(long number, PrState state, char head, Option<Instant> mergedAt) {
        return new PullRequest(StoreFixtures.pr(number),
                               "PR " + number,
                               state,
                               false,
                               branch("main"),
                               branch("feature-" + number),
                               some("pragmaticalabs/pragmatica"),
                               sha(head),
                               mergedAt,
                               state == PrState.OPEN ? none() : some(mergedAt.or(T0)),
                               T0,
                               List.of(),
                               List.of(),
                               List.of());
    }

    private List<ChangeFeed.Row> prStates() {
        return await(store.changes().feed(0, 500)).changes()
                                                  .stream()
                                                  .filter(row -> row.kind().equals("PR_STATE"))
                                                  .toList();
    }

    private static Map<String, Object> payload(ChangeFeed.Row row) {
        return Json.read(row.payload().or("{}"), OBJECT).unwrap();
    }
}
