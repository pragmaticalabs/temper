package org.pragmatica.kiln.core.checkout;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.model.Sha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.ancestry;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.atBase;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.facts;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.index;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.number;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.offBase;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.onBranch;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.pr;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.sha;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.tip;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// K-9 (kiln-f1-design §7.2): spec §5.3 PR link. Branch name > SHA > ancestry; ancestry only off every base; a stacked
/// chain links all; a reused branch name links the open PR, then the one holding HEAD, then the newest.
class PrLinkTest {
    private static final Sha HEAD = sha('a');
    private static final Sha OTHER = sha('b');
    private static final Sha BELOW = sha('c');

    private static PrLink link(List<Long> prs, LinkRule rule) {
        return new PrLink(prs.stream()
                             .map(CheckoutFixtures::number)
                             .toList(),
                          rule);
    }

    @Test
    void link_byBranch_beatsBySha() {
        var index = index(pr(1, PrState.OPEN, "feat", OTHER, 1), pr(2, PrState.OPEN, "other", HEAD, 1));

        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index)).isEqualTo(link(List.of(1L), LinkRule.BRANCH));
    }

    @Test
    void link_bySha_whenNoPrHasTheBranch_andBeatsAncestry() {
        var index = index(pr(2, PrState.OPEN, "other", HEAD, 1), pr(3, PrState.OPEN, "third", OTHER, 1));
        var ancestry = ancestry(false, Set.of(number(3)), Set.of());

        assertThat(PrLink.link(onBranch("feat", HEAD), ancestry, index)).isEqualTo(link(List.of(2L), LinkRule.SHA));
        assertThat(PrLink.link(facts(none(), HEAD, List.of()), ancestry, index))
            .isEqualTo(link(List.of(2L), LinkRule.SHA));
    }

    /// Several PRs at the same head SHA are all linked, by number.
    @Test
    void link_bySha_linksEveryPrAtHead() {
        var index = index(pr(9, PrState.OPEN, "b9", HEAD, 1), pr(4, PrState.CLOSED, "b4", HEAD, 1));

        assertThat(PrLink.link(facts(none(), HEAD, List.of()), offBase(), index))
            .isEqualTo(link(List.of(4L, 9L), LinkRule.SHA));
    }

    @Test
    void link_byAncestry_whenNeitherBranchNorShaMatches() {
        var index = index(pr(3, PrState.OPEN, "third", OTHER, 1));

        assertThat(PrLink.link(onBranch("feat", HEAD), ancestry(false, Set.of(number(3)), Set.of()), index))
            .isEqualTo(link(List.of(3L), LinkRule.ANCESTRY));
    }

    /// A PR head that is an ancestor of HEAD, off every base, links too.
    @Test
    void link_byAncestry_whenAPrHeadIsAnOffBaseCommitOfHead() {
        var index = index(pr(3, PrState.OPEN, "third", BELOW, 1));

        assertThat(PrLink.link(onBranch("feat", HEAD), ancestry(false, Set.of(), Set.of(BELOW)), index))
            .isEqualTo(link(List.of(3L), LinkRule.ANCESTRY));
    }

    /// A stacked chain: one PR below HEAD and two above it are all linked, by number.
    @Test
    void link_byAncestry_linksAStackedChain() {
        var index = index(pr(6, PrState.OPEN, "s1", BELOW, 1),
                          pr(5, PrState.OPEN, "s3", sha('e'), 1),
                          pr(4, PrState.OPEN, "s2", OTHER, 1),
                          pr(7, PrState.OPEN, "unrelated", sha('f'), 1));
        var ancestry = ancestry(false, Set.of(number(4), number(5)), Set.of(BELOW));

        assertThat(PrLink.link(facts(none(), HEAD, List.of()), ancestry, index))
            .isEqualTo(link(List.of(4L, 5L, 6L), LinkRule.ANCESTRY));
    }

    /// A checkout at the base tip is contained by every PR head stacked on the base: it links to no PR.
    @Test
    void link_isNone_atTheBaseTip_evenWhenPrHeadsContainHead() {
        var index = index(pr(3, PrState.OPEN, "third", OTHER, 1));
        var contained = Set.of(number(3));

        assertThat(PrLink.link(onBranch("main", HEAD), ancestry(true, contained, Set.of()), index))
            .isEqualTo(PrLink.NONE);
        assertThat(PrLink.link(onBranch("main", HEAD), ancestry(false, contained, Set.of()), index))
            .isEqualTo(link(List.of(3L), LinkRule.ANCESTRY));
    }

    /// `atBase` limits rule 3 only: a merged PR's branch checked out at the base still links by branch and by SHA.
    @Test
    void link_atBase_stillLinksByBranchAndBySha() {
        var index = index(pr(8, PrState.MERGED, "feat", HEAD, 1));

        assertThat(PrLink.link(onBranch("feat", HEAD), atBase(), index)).isEqualTo(link(List.of(8L), LinkRule.BRANCH));
        assertThat(PrLink.link(facts(none(), HEAD, List.of()), atBase(), index))
            .isEqualTo(link(List.of(8L), LinkRule.SHA));
    }

    @Test
    void link_isNone_whenNothingMatches() {
        var index = index(pr(3, PrState.OPEN, "third", OTHER, 1));

        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index)).isEqualTo(PrLink.NONE);
        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index())).isEqualTo(PrLink.NONE);
    }

    /// A fork PR on the same branch name and head never links (spec §5.3); the index excludes it.
    @Test
    void link_ignoresForks() {
        var index = index(pr(3, PrState.OPEN, "feat", HEAD, 1, some("someone/pragmatica")),
                          pr(4, PrState.OPEN, "feat", HEAD, 1, none()));

        assertThat(PrLink.link(onBranch("feat", HEAD), ancestry(false, Set.of(number(3)), Set.of()), index))
            .isEqualTo(PrLink.NONE);
    }

    @Test
    void link_reusedBranch_linksTheOpenPr_overANewerClosedOne() {
        var index = index(pr(1, PrState.CLOSED, "feat", HEAD, 1), pr(2, PrState.OPEN, "feat", OTHER, 100));

        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index)).isEqualTo(link(List.of(2L), LinkRule.BRANCH));
    }

    @Test
    void link_reusedBranch_linksThePrWhoseHeadIsHead_overANewerOne() {
        var index = index(pr(1, PrState.MERGED, "feat", HEAD, 100), pr(2, PrState.CLOSED, "feat", OTHER, 1));

        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index)).isEqualTo(link(List.of(1L), LinkRule.BRANCH));
    }

    @Test
    void link_reusedBranch_linksThePrWhoseHeadContainsHead_overANewerOne() {
        var index = index(pr(1, PrState.MERGED, "feat", OTHER, 100), pr(2, PrState.CLOSED, "feat", BELOW, 1));

        assertThat(PrLink.link(onBranch("feat", HEAD), ancestry(false, Set.of(number(1)), Set.of()), index))
            .isEqualTo(link(List.of(1L), LinkRule.BRANCH));
    }

    @Test
    void link_reusedBranch_linksTheNewest_whenNoneIsOpenOrHoldsHead() {
        var index = index(pr(1, PrState.MERGED, "feat", OTHER, 1), pr(2, PrState.CLOSED, "feat", BELOW, 100));

        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index)).isEqualTo(link(List.of(1L), LinkRule.BRANCH));
    }

    /// Equal `updated_at`: the higher number, so the choice never depends on the table's order.
    @Test
    void link_reusedBranch_linksTheHigherNumber_onEqualUpdatedAt() {
        var first = pr(1, PrState.CLOSED, "feat", OTHER, 5);
        var second = pr(2, PrState.CLOSED, "feat", BELOW, 5);

        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index(first, second)))
            .isEqualTo(link(List.of(2L), LinkRule.BRANCH));
        assertThat(PrLink.link(onBranch("feat", HEAD), offBase(), index(second, first)))
            .isEqualTo(link(List.of(2L), LinkRule.BRANCH));
    }

    /// The branch rule needs a branch: a detached HEAD whose branch list names a PR's ref does not link by branch.
    @Test
    void link_detachedHead_neverLinksByBranch() {
        var index = index(pr(1, PrState.OPEN, "feat", OTHER, 1));

        assertThat(PrLink.link(facts(none(), HEAD, List.of(tip("feat", HEAD))), offBase(), index))
            .isEqualTo(PrLink.NONE);
    }
}
