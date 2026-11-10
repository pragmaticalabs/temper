package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Derivation;
import org.pragmatica.kiln.core.checkout.PrIndex;
import org.pragmatica.kiln.core.checkout.Reach;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.REPO;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.pr;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// K-13: PRs are kept forever, so a checkout whose PR closed long ago still links. GitHub serves 1,001 PRs over 11
/// pages, newest first, `#1` closed and the oldest (alone on page 11); the backfill pages to the end into Postgres, and
/// [Derivation#derive] over the full table makes a preserved checkout on `#1`'s branch REMOVABLE. Negative control: the
/// same derivation over the first 1,000 (a recent-N window) reads UNOWNED.
///
/// Probe (WP6 state file): cap the backfill at 10 pages → `#1` is never stored and the checkout reads UNOWNED.
@Tag("pg")
class FullTableLinkIT {
    private static final int PRS = 1001;
    private static final Instant NOW = T0.plusSeconds(60);

    private KilnStore store;
    private GitHubFake fake;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        var newestFirst = LongStream.iterate(PRS, number -> number - 1)
                                    .limit(PRS)
                                    .mapToObj(FullTableLinkIT::prJson)
                                    .toList();

        fake.pages(PULLS,
                   IntStream.range(0, 11)
                            .mapToObj(page -> array(newestFirst.subList(page * 100, Math.min(PRS, page * 100 + 100))))
                            .toList());
        quiet(fake, List.of(), List.of());
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    @Test
    void backfill_keepsTheOldestClosedPr_andItsCheckoutIsRemovable() {
        var poller = GitHubFixtures.poller(fake, store, new AtomicReference<>(NOW), none());

        assertThat(await(poller.cycle())).isEqualTo(new SourceState.Ok(NOW));

        assertThat(fake.requests()).filteredOn(request -> request.path().startsWith(PULLS)).hasSize(11);
        var all = await(store.prs().all());

        assertThat(all).hasSize(PRS);
        assertThat(all).filteredOn(pr -> pr.number().value() == 1).singleElement()
                       .extracting(PullRequest::state).isEqualTo(PrState.CLOSED);
        assertThat(await(store.prs().backfillDone())).isEqualTo(some(NOW));
        assertThat(derive(all)).isEqualTo(CheckoutStatus.REMOVABLE);

        var window = all.stream().filter(pr -> pr.number().value() != 1).toList();

        assertThat(derive(window)).as("negative control: a window without #1").isEqualTo(CheckoutStatus.UNOWNED);
    }

    /// `#n` on branch `b<n>`, updated `PRS - n` minutes before T0; `#1` closed, every other merged.
    private static String prJson(long number) {
        return pr(number,
                  number == 1 ? "closed" : "merged",
                  "main",
                  "b" + number,
                  sha(number),
                  T0.minus(Duration.ofMinutes(PRS - number)));
    }

    /// A clean checkout on `b1` at `#1`'s head, every tip in the mirror, off every base.
    private static CheckoutStatus derive(List<PullRequest> table) {
        var branch = BranchName.branchName("b1").unwrap();
        var facts = new CheckoutFacts(CheckoutPath.checkoutPath("/w/co/one").unwrap(),
                                      some(sha(1)),
                                      some(branch),
                                      some("https://github.com/" + REPO + ".git"),
                                      List.of(new BranchTip(branch, sha(1))),
                                      0,
                                      0,
                                      none(),
                                      NOW.minus(Duration.ofHours(1)),
                                      List.of(),
                                      false,
                                      "/w/co/one/.git",
                                      0,
                                      none());

        return Derivation.derive(some(facts),
                                 Map.of(sha(1), Reach.MIRROR),
                                 new Ancestry(true, false, Set.of(), Set.of()),
                                 PrIndex.prIndex(table, REPO),
                                 Duration.ofHours(48),
                                 NOW)
                         .status();
    }
}
