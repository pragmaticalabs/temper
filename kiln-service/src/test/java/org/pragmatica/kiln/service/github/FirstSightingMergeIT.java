package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.pr;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.lang.Option.none;

/// The first-sighting merge through the poller, against a [GitHubFake] and real Postgres: the backfill stores the PRs it
/// finds merged and records no change (it sets `backfill_done` only after its upsert), and a PR first seen merged in a
/// later cycle, merged after the backfill, records `PR_STATE` `none` → `merged` carrying its head, base and head ref.
/// Probe (WP-0K state file): the poller sets `backfill_done` before the backfill's upsert →
/// [#backfillRecordsNothing_thenAFirstSeenMergeRecordsNoneToMerged] red.
@Tag("pg")
class FirstSightingMergeIT {
    private KilnStore store;
    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private GitHubPoller poller;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        quiet(fake, List.of(), List.of());
        poller = GitHubFixtures.poller(fake, store, now, none());
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    @Test
    void backfillRecordsNothing_thenAFirstSeenMergeRecordsNoneToMerged() {
        // #2 merged at the backfill's own instant: only the order (backfill_done set after the upsert) keeps it quiet
        fake.pages(PULLS, List.of(array(merged(2, 0), merged(1, 2))));
        await(poller.cycle());

        assertThat(await(store.prs().all())).hasSize(2);
        assertThat(prStates()).isEmpty();

        fake.pages(PULLS, List.of(array(merged(3, -1), merged(2, 0))));
        now.set(T0.plusSeconds(120));
        await(poller.cycle());

        assertThat(prStates()).singleElement()
                              .satisfies(row -> assertThat(row.subject()).isEqualTo("#3"))
                              .satisfies(row -> assertThat(row.before() + ">" + row.after()).isEqualTo("none>merged"))
                              .satisfies(row -> assertThat(row.payload().or("")).contains("\"head\": \"" + sha(3).value() + "\"",
                                                                                         "\"base\": \"main\"",
                                                                                         "\"headRef\": \"b3\"",
                                                                                         "\"mergedAt\": \"2026-"));
    }

    /// `#n` merged, merged and updated `minutesAgo` minutes before T0 (negative: after).
    private static String merged(long number, long minutesAgo) {
        return pr(number, "merged", "main", "b" + number, sha(number), T0.minus(Duration.ofMinutes(minutesAgo)));
    }

    private List<ChangeFeed.Row> prStates() {
        return await(store.changes().feed(0, 500)).changes()
                                                  .stream()
                                                  .filter(row -> row.kind().equals("PR_STATE"))
                                                  .toList();
    }
}
