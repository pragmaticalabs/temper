package org.pragmatica.kiln.service.github;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.ROOT;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.checkRuns;
import static org.pragmatica.kiln.service.github.GitHubFixtures.open;
import static org.pragmatica.kiln.service.github.GitHubFixtures.pr;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.run;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.lang.Option.none;

/// K-10: a cycle in which nothing changed fetches no body. Every URL is asked with its stored ETag, GitHub answers 304,
/// and the stored body is replayed (counted at the [GitHubFake], which serves a 304 only for a matching
/// `If-None-Match`). Positive control: the first cycle fetches bodies. Negative control: one changed answer is fetched
/// again, alone.
///
/// Probe (WP6 state file): drop the `If-None-Match` header in `JdkGitHubHttp` → the second cycle's 200 count is > 0.
@Tag("pg")
class EtagCycleIT {
    private KilnStore store;
    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private GitHubPoller poller;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        fake.pages(PULLS,
                   List.of(array(open(2, "feat", sha(2), T0),
                                 pr(1, "merged", "main", "old", sha(1), T0.minusSeconds(3600)))));
        quiet(fake, List.of(2L), List.of(sha(2)));
        poller = GitHubFixtures.poller(fake, store, now, none());
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    @Test
    void cycle_fetchesNoBody_whenNothingChanged() {
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0));
        assertThat(fake.fulls()).as("positive control: the first cycle fetches every body").isGreaterThanOrEqualTo(10);
        var fulls = fake.fulls();
        var from = fake.requests().size();

        now.set(T0.plusSeconds(60));
        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(T0.plusSeconds(60)));

        assertThat(fake.fulls() - fulls).as("bodies fetched by the quiet cycle").isZero();
        assertThat(fake.notModifieds()).isEqualTo(fake.requests().size() - from);
        assertThat(fake.requests().subList(from, fake.requests().size())).allMatch(request -> request.ifNoneMatch() != null);
        assertThat(cellValues()).as("the replayed bodies still decode to the same cells").containsExactly("success");
    }

    @Test
    void cycle_fetchesAChangedAnswerAgain_andOnlyIt() {
        await(poller.cycle());
        var fulls = fake.fulls();

        fake.answer(ROOT + "commits/" + sha(2).value() + "/check-runs?filter=all&per_page=100",
                    checkRuns(run(20, "ci", "completed", "failure", T0)));
        now.set(T0.plusSeconds(60));
        await(poller.cycle());

        assertThat(fake.fulls() - fulls).isEqualTo(1);
        assertThat(cellValues()).containsExactly("failure");
    }

    private List<String> cellValues() {
        return await(store.views().openPrs()).getFirst()
                                              .cells()
                                              .stream()
                                              .map(Observed::value)
                                              .map(Cell::value)
                                              .toList();
    }
}
