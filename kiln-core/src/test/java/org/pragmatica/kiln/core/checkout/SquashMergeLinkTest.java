package org.pragmatica.kiln.core.checkout;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.model.Sha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.NOW;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.STALE_AFTER;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.atBase;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.every;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.index;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.offBase;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.onBranch;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.pr;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.sha;
import static org.pragmatica.lang.Option.some;

/// K-2 (kiln-f1-design §7.2): merged and closed come only from GitHub's PR state, never from ancestry against the base
/// (spec §5.3). A squash merge leaves the checkout's HEAD on no base, yet its PR is merged.
class SquashMergeLinkTest {
    private static final Sha HEAD = sha('a');
    private static final Sha SQUASHED = sha('9');

    private static Derived derive(PrState state, Ancestry ancestry) {
        var facts = onBranch("feat", HEAD);

        return Derivation.derive(some(facts),
                                 every(facts, Reach.MIRROR),
                                 ancestry,
                                 index(pr(42, state, "feat", HEAD, 2)),
                                 STALE_AFTER,
                                 NOW);
    }

    /// HEAD off base, PR MERGED, every tip in the mirror: removable, linked by its branch.
    @Test
    void derive_isRemovable_forASquashMergedPr_whoseHeadIsOnNoBase() {
        var derived = derive(PrState.MERGED, offBase());

        assertThat(derived.status()).isEqualTo(CheckoutStatus.REMOVABLE);
        assertThat(derived.link()
                          .rule()).isEqualTo(LinkRule.BRANCH);
        assertThat(derived.atBase()).isEqualTo(some(false));
    }

    /// Negative control: the same facts with the PR still open read IN-USE, so the state, not ancestry, decided.
    @Test
    void derive_isInUse_forTheSameCheckout_whileThePrIsOpen() {
        assertThat(derive(PrState.OPEN, offBase()).status()).isEqualTo(CheckoutStatus.IN_USE);
    }

    /// Being at a base does not merge an open PR either.
    @Test
    void derive_isInUse_forAnOpenPr_evenAtABase() {
        assertThat(derive(PrState.OPEN, atBase()).status()).isEqualTo(CheckoutStatus.IN_USE);
    }

    /// A PR closed without merging is just as finished.
    @Test
    void derive_isRemovable_forAClosedPr_whoseHeadIsOnNoBase() {
        assertThat(derive(PrState.CLOSED, offBase()).status()).isEqualTo(CheckoutStatus.REMOVABLE);
    }

    /// The squash commit on the base is unrelated to HEAD; only its PR record says it merged.
    @Test
    void derive_ignoresTheSquashCommit() {
        var facts = onBranch("feat", HEAD);
        var derived = Derivation.derive(some(facts),
                                        every(facts, Reach.MIRROR),
                                        offBase(),
                                        index(pr(42, PrState.MERGED, "feat", HEAD, 2),
                                              pr(43, PrState.OPEN, "other", SQUASHED, 1)),
                                        STALE_AFTER,
                                        NOW);

        assertThat(derived.status()).isEqualTo(CheckoutStatus.REMOVABLE);
    }
}
