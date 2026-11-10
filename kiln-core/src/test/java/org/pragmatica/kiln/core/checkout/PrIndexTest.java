package org.pragmatica.kiln.core.checkout;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.REPO;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.pr;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.sha;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §5.3: the full PR table, forks excluded (K-13 keeps the table whole).
class PrIndexTest {
    @Test
    void prIndex_keepsEveryPrOfTheBaseRepository_whateverItsStateOrAge() {
        var open = pr(1, PrState.OPEN, "a", sha('a'), 1);
        var merged = pr(2, PrState.MERGED, "b", sha('b'), 24 * 365);
        var closed = pr(3, PrState.CLOSED, "c", sha('c'), 24 * 365 * 3);

        assertThat(PrIndex.prIndex(List.of(open, merged, closed), REPO).prs()).containsExactly(open, merged, closed);
    }

    @Test
    void prIndex_excludesForksAndPrsWhoseHeadRepositoryIsGone() {
        var own = pr(1, PrState.OPEN, "a", sha('a'), 1);
        var fork = pr(2, PrState.OPEN, "a", sha('a'), 1, some("someone/pragmatica"));
        var gone = pr(3, PrState.CLOSED, "a", sha('a'), 1, none());

        assertThat(PrIndex.prIndex(List.of(fork, own, gone), REPO).prs()).containsExactly(own);
    }

    /// A head repository cased unlike the configured one is still the base repository's; a fork in any case is not.
    @Test
    void prIndex_keepsAPrWhoseHeadRepositoryDiffersOnlyInCase() {
        var mixed = pr(1, PrState.OPEN, "a", sha('a'), 1, some("PragmaticaLabs/Pragmatica"));
        var fork = pr(2, PrState.OPEN, "a", sha('a'), 1, some("Someone/Pragmatica"));

        assertThat(PrIndex.prIndex(List.of(mixed, fork), REPO).prs()).containsExactly(mixed);
        assertThat(PrIndex.prIndex(List.of(mixed, fork), "PRAGMATICALABS/pragmatica").prs()).containsExactly(mixed);
    }
}
