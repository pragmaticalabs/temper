package org.pragmatica.kiln.core.checkout;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.Preservation.HostBareOnly;
import org.pragmatica.kiln.core.checkout.Preservation.Preserved;
import org.pragmatica.kiln.core.checkout.Preservation.Unpreserved;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.NOW;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.STALE_AFTER;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.ancestry;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.atBase;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.every;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.index;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.number;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.offBase;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.onBranch;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.pr;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.sha;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.withActivity;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.withProcesses;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.withTree;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §5.3 status table, rules 1–7 in order, plus UNSCANNED (Q-5). Each rule has a positive case and a negative one
/// that differs in the one fact the rule reads. Rule 1 is the ACTIVE leg of K-14.
class DerivationTest {
    private static final Sha HEAD = sha('a');
    private static final PrIndex NO_PRS = index();

    private static Derived derive(CheckoutFacts facts, Map<Sha, Reach> reach, Ancestry ancestry, PrIndex index) {
        return Derivation.derive(some(facts), reach, ancestry, index, STALE_AFTER, NOW);
    }

    private static Derived derive(CheckoutFacts facts, Ancestry ancestry, PrIndex index) {
        return derive(facts, every(facts, Reach.MIRROR), ancestry, index);
    }

    private static PrIndex onFeat(PrState state) {
        return index(pr(1, state, "feat", HEAD, 1));
    }

    // rule 1: ACTIVE

    /// A process in the checkout outranks every other rule: an open PR, a dirty tree, a base.
    @Test
    void derive_isActive_whenAnyProcessIsInTheCheckout() {
        var busy = withProcesses(withTree(onBranch("feat", HEAD), 1, 0, 0, none()), List.of(4242L));

        assertThat(derive(busy, offBase(), onFeat(PrState.OPEN)).status()).isEqualTo(CheckoutStatus.ACTIVE);
        assertThat(derive(withProcesses(onBranch("main", HEAD), List.of(1L)), atBase(), NO_PRS).status())
            .isEqualTo(CheckoutStatus.ACTIVE);
    }

    @Test
    void derive_isNotActive_withoutProcesses() {
        assertThat(derive(onBranch("feat", HEAD), offBase(), onFeat(PrState.OPEN)).status())
            .isEqualTo(CheckoutStatus.IN_USE);
    }

    // rule 2: IN-USE

    /// A stacked chain with one PR merged and one open is in use: the link counts as open if any linked PR is.
    @Test
    void derive_isInUse_whenAnyLinkedPrIsOpen() {
        var index = index(pr(1, PrState.MERGED, "s1", sha('b'), 1), pr(2, PrState.OPEN, "s2", sha('c'), 1));
        var stacked = ancestry(false, Set.of(number(1), number(2)), Set.of());
        var dirty = withTree(onBranch("feat", HEAD), 2, 0, 0, none());

        assertThat(derive(dirty, stacked, index).status()).isEqualTo(CheckoutStatus.IN_USE);
    }

    @Test
    void derive_isNotInUse_whenEveryLinkedPrIsMergedOrClosed() {
        var index = index(pr(1, PrState.MERGED, "s1", sha('b'), 1), pr(2, PrState.CLOSED, "s2", sha('c'), 1));
        var stacked = ancestry(false, Set.of(number(1), number(2)), Set.of());

        assertThat(derive(onBranch("feat", HEAD), stacked, index).status()).isEqualTo(CheckoutStatus.REMOVABLE);
    }

    // rule 3: REMOVABLE

    @Test
    void derive_isRemovable_whenEveryLinkedPrIsFinishedAndThePreservedCheckout() {
        var derived = derive(onBranch("feat", HEAD), offBase(), onFeat(PrState.MERGED));

        assertThat(derived.status()).isEqualTo(CheckoutStatus.REMOVABLE);
        assertThat(derived.preservation()).isEqualTo(new Preserved());
    }

    /// HOST-BARE-ONLY is not PRESERVED: a merged PR's checkout with a tip only in the host bare needs salvage.
    @Test
    void derive_isNotRemovable_whenOnlyHostBareOnly() {
        var facts = onBranch("feat", HEAD);
        var derived = derive(facts, every(facts, Reach.HOST_BARE), offBase(), onFeat(PrState.MERGED));

        assertThat(derived.status()).isEqualTo(CheckoutStatus.NEEDS_SALVAGE);
        assertThat(derived.preservation()).isEqualTo(new HostBareOnly());
    }

    // rule 4: BASE-CLEAN

    @Test
    void derive_isBaseClean_withNoLinkAtABaseAndPreserved() {
        assertThat(derive(onBranch("main", HEAD), atBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.BASE_CLEAN);
    }

    @Test
    void derive_isNotBaseClean_whenNotPreserved() {
        var dirty = withTree(onBranch("main", HEAD), 0, 3, 0, none());

        assertThat(derive(dirty, atBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.NEEDS_SALVAGE);
    }

    /// Off every base, a clean unlinked checkout is merely unowned.
    @Test
    void derive_isNotBaseClean_offBase() {
        assertThat(derive(onBranch("main", HEAD), offBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.UNOWNED);
    }

    // rule 5: NEEDS-SALVAGE

    @Test
    void derive_needsSalvage_whenALinkedPrIsFinishedAndTheCheckoutIsNotPreserved() {
        var dirty = withTree(onBranch("feat", HEAD), 0, 0, 1, none());
        var derived = derive(dirty, offBase(), onFeat(PrState.CLOSED));

        assertThat(derived.status()).isEqualTo(CheckoutStatus.NEEDS_SALVAGE);
        assertThat(derived.preservation()).isEqualTo(new Unpreserved(List.of("stash:1")));
    }

    @Test
    void derive_needsSalvage_atABaseWithUnpushedWork() {
        var facts = onBranch("main", HEAD);

        assertThat(derive(facts, every(facts, Reach.NOWHERE), atBase(), NO_PRS).status())
            .isEqualTo(CheckoutStatus.NEEDS_SALVAGE);
        assertThat(derive(facts, every(facts, Reach.HOST_BARE), atBase(), NO_PRS).status())
            .isEqualTo(CheckoutStatus.NEEDS_SALVAGE);
    }

    /// Unlinked and off base, unpreserved work does not need salvage by this rule: it is the checkout's own.
    @Test
    void derive_doesNotNeedSalvage_unlinkedOffBase() {
        var dirty = withTree(onBranch("feat", HEAD), 1, 0, 0, none());

        assertThat(derive(dirty, offBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.UNOWNED);
    }

    // rule 6: STALE-UNOWNED

    @Test
    void derive_isStaleUnowned_whenUnlinkedOffBaseAndInactiveLongerThanStaleAfter() {
        var old = withActivity(onBranch("feat", HEAD), NOW.minus(STALE_AFTER).minusSeconds(1));

        assertThat(derive(old, offBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.STALE_UNOWNED);
    }

    /// "Older than" is strict: exactly `staleAfter` ago is not stale yet.
    @Test
    void derive_isNotStale_atExactlyStaleAfter() {
        var edge = withActivity(onBranch("feat", HEAD), NOW.minus(STALE_AFTER));

        assertThat(derive(edge, offBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.UNOWNED);
    }

    /// The window is the caller's: the same activity is stale under a shorter `staleAfter`.
    @Test
    void derive_usesTheGivenStaleAfter() {
        var facts = onBranch("feat", HEAD);
        var derived = Derivation.derive(some(facts),
                                        every(facts, Reach.MIRROR),
                                        offBase(),
                                        NO_PRS,
                                        Duration.ofMinutes(30),
                                        NOW);

        assertThat(derived.status()).isEqualTo(CheckoutStatus.STALE_UNOWNED);
    }

    /// A stale checkout at a base, or linked, is never STALE-UNOWNED.
    @Test
    void derive_isNotStaleUnowned_atABaseOrLinked() {
        var old = withActivity(onBranch("feat", HEAD), NOW.minus(Duration.ofDays(30)));

        assertThat(derive(old, atBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.BASE_CLEAN);
        assertThat(derive(old, offBase(), onFeat(PrState.MERGED)).status()).isEqualTo(CheckoutStatus.REMOVABLE);
    }

    // rule 7: UNOWNED

    @Test
    void derive_isUnowned_otherwise() {
        var derived = derive(onBranch("feat", HEAD), offBase(), NO_PRS);

        assertThat(derived.status()).isEqualTo(CheckoutStatus.UNOWNED);
        assertThat(derived.link()).isEqualTo(PrLink.NONE);
    }

    // UNSCANNED (Q-5)

    @Test
    void derive_isUnscanned_withoutFacts() {
        var derived = Derivation.derive(none(), Map.of(), atBase(), onFeat(PrState.OPEN), STALE_AFTER, NOW);

        assertThat(derived.status()).isEqualTo(CheckoutStatus.UNSCANNED);
        assertThat(derived.preservation()).isInstanceOf(Unpreserved.class);
        assertThat(derived.link()).isEqualTo(PrLink.NONE);
        assertThat(derived.atBase()).isEqualTo(none());
    }

    /// With facts, the derived row carries the link, preservation and `atBase` it was derived from.
    @Test
    void derive_carriesItsInputsVerdicts_withFacts() {
        var derived = derive(onBranch("main", HEAD), atBase(), NO_PRS);

        assertThat(derived.status()).isNotEqualTo(CheckoutStatus.UNSCANNED);
        assertThat(derived.atBase()).isEqualTo(some(true));
        assertThat(derived.preservation()).isEqualTo(new Preserved());
    }

    // HEAD not in the mirror: no ancestry, rules 1 and 2 still link

    private static Ancestry outsideMirror(boolean atBase, Set<PrNumber> containing, Set<Sha> offBase) {
        return new Ancestry(false, atBase, containing, offBase);
    }

    /// An adapter's `atBase` for a HEAD outside the mirror is not believed: the checkout is at no base, so a preserved
    /// `main` reads UNOWNED, not BASE-CLEAN, and `atBase` is reported false. In the mirror the same facts are BASE-CLEAN.
    @Test
    void derive_isAtNoBase_whenHeadIsNotInTheMirror() {
        var derived = derive(onBranch("main", HEAD), outsideMirror(true, Set.of(), Set.of()), NO_PRS);

        assertThat(derived.status()).isEqualTo(CheckoutStatus.UNOWNED);
        assertThat(derived.atBase()).isEqualTo(some(false));
        assertThat(derive(onBranch("main", HEAD), atBase(), NO_PRS).status()).isEqualTo(CheckoutStatus.BASE_CLEAN);
    }

    /// Rule 3 (ancestry) links nothing for a HEAD outside the mirror, by either ancestry set; in the mirror the same sets
    /// link the PR.
    @Test
    void derive_linksByNoAncestry_whenHeadIsNotInTheMirror() {
        var index = index(pr(1, PrState.MERGED, "other", sha('b'), 1));
        var facts = onBranch("feat", HEAD);

        assertThat(derive(facts, outsideMirror(false, Set.of(number(1)), Set.of()), index).link()).isEqualTo(PrLink.NONE);
        assertThat(derive(facts, outsideMirror(false, Set.of(), Set.of(sha('b'))), index).link()).isEqualTo(PrLink.NONE);
        assertThat(derive(facts, outsideMirror(false, Set.of(number(1)), Set.of()), index).status())
            .isEqualTo(CheckoutStatus.UNOWNED);
        assertThat(derive(facts, ancestry(false, Set.of(number(1)), Set.of()), index).link())
            .isEqualTo(new PrLink(List.of(number(1)), LinkRule.ANCESTRY));
        assertThat(derive(facts, ancestry(false, Set.of(), Set.of(sha('b'))), index).link())
            .isEqualTo(new PrLink(List.of(number(1)), LinkRule.ANCESTRY));
    }

    /// Branch name and SHA need no ancestry: both still link a HEAD outside the mirror.
    @Test
    void derive_stillLinksByBranchAndSha_whenHeadIsNotInTheMirror() {
        var outside = outsideMirror(true, Set.of(), Set.of());

        assertThat(derive(onBranch("feat", HEAD), outside, onFeat(PrState.OPEN)).link())
            .isEqualTo(new PrLink(List.of(number(1)), LinkRule.BRANCH));
        assertThat(derive(onBranch("main", HEAD), outside, onFeat(PrState.MERGED)).link())
            .isEqualTo(new PrLink(List.of(number(1)), LinkRule.SHA));
        assertThat(derive(onBranch("main", HEAD), outside, onFeat(PrState.MERGED)).status())
            .isEqualTo(CheckoutStatus.REMOVABLE);
    }
}
