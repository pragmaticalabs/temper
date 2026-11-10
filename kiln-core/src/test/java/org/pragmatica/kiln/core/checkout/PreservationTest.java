package org.pragmatica.kiln.core.checkout;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.checkout.Preservation.HostBareOnly;
import org.pragmatica.kiln.core.checkout.Preservation.Preserved;
import org.pragmatica.kiln.core.checkout.Preservation.Unpreserved;
import org.pragmatica.kiln.core.model.Sha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.every;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.facts;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.onBranch;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.sha;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.tip;
import static org.pragmatica.kiln.core.checkout.CheckoutFixtures.withTree;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §5.3 preservation (W4): GitHub's mirror is the only durability authority; the host's bare is not durable.
class PreservationTest {
    private static final Sha A = sha('a');
    private static final Sha B = sha('b');

    @Test
    void of_isPreserved_whenEveryTipIsInTheMirrorAndTheTreeIsClean() {
        var facts = facts(some("x"), A, List.of(tip("x", A), tip("y", B)));

        assertThat(Preservation.of(facts, every(facts, Reach.MIRROR))).isEqualTo(new Preserved());
    }

    /// Every branch tip counts, not only HEAD's: an unpushed second branch is lost work.
    @Test
    void of_isUnpreserved_whenAnyBranchTipIsMissingFromTheMirror() {
        var facts = facts(some("x"), A, List.of(tip("x", A), tip("y", B)));

        assertThat(Preservation.of(facts, Map.of(A, Reach.MIRROR, B, Reach.NOWHERE)))
            .isEqualTo(new Unpreserved(List.of("unpushed:y")));
    }

    @Test
    void of_isHostBareOnly_whenTheTipsGitHubLacksAreInTheHostBareAndTheTreeIsClean() {
        var facts = facts(some("x"), A, List.of(tip("x", A), tip("y", B)));

        assertThat(Preservation.of(facts, Map.of(A, Reach.MIRROR, B, Reach.HOST_BARE))).isEqualTo(new HostBareOnly());
    }

    /// Once anything else is unpreserved, the host-bare tips are listed too: they are not on GitHub.
    @Test
    void of_isUnpreserved_withTheHostBareTips_whenTheTreeIsDirty() {
        var facts = withTree(facts(some("x"), A, List.of(tip("x", A), tip("y", B))), 2, 0, 0, none());

        assertThat(Preservation.of(facts, Map.of(A, Reach.MIRROR, B, Reach.HOST_BARE)))
            .isEqualTo(new Unpreserved(List.of("unpushed:y", "dirty:2")));
    }

    @Test
    void of_isUnpreserved_whenOneTipIsHostBareAndAnotherNowhere() {
        var facts = facts(some("x"), A, List.of(tip("x", A), tip("y", B)));

        assertThat(Preservation.of(facts, Map.of(A, Reach.HOST_BARE, B, Reach.NOWHERE)))
            .isEqualTo(new Unpreserved(List.of("unpushed:x", "unpushed:y")));
    }

    /// No `reach` answer (a Mac row, a failed host) and a tip absent from the map both read unpushed, the safe side.
    @Test
    void of_isUnpreserved_forAnUnknownOrUnansweredTip() {
        var facts = onBranch("x", A);

        assertThat(Preservation.of(facts, Map.of(A, Reach.UNKNOWN))).isEqualTo(new Unpreserved(List.of("unpushed:x")));
        assertThat(Preservation.of(facts, Map.of())).isEqualTo(new Unpreserved(List.of("unpushed:x")));
    }

    @Test
    void of_listsEveryTreeReason_inTheSpecOrder() {
        var facts = withTree(onBranch("x", A), 3, 4, 5, some("rebase"));

        assertThat(Preservation.of(facts, every(facts, Reach.MIRROR)))
            .isEqualTo(new Unpreserved(List.of("dirty:3", "untracked:4", "stash:5", "op:rebase")));
    }

    @Test
    void of_isUnpreserved_forEachTreeReasonAlone() {
        var clean = onBranch("x", A);
        var mirror = every(clean, Reach.MIRROR);

        assertThat(Preservation.of(withTree(clean, 1, 0, 0, none()), mirror))
            .isEqualTo(new Unpreserved(List.of("dirty:1")));
        assertThat(Preservation.of(withTree(clean, 0, 1, 0, none()), mirror))
            .isEqualTo(new Unpreserved(List.of("untracked:1")));
        assertThat(Preservation.of(withTree(clean, 0, 0, 1, none()), mirror))
            .isEqualTo(new Unpreserved(List.of("stash:1")));
        assertThat(Preservation.of(withTree(clean, 0, 0, 0, some("merge")), mirror))
            .isEqualTo(new Unpreserved(List.of("op:merge")));
    }

    /// An operation in progress is unpreserved even when every tip is in the mirror and the tree is clean.
    @Test
    void of_isNotHostBareOnly_withAnOperationInProgress() {
        var facts = withTree(onBranch("x", A), 0, 0, 0, some("cherry-pick"));

        assertThat(Preservation.of(facts, Map.of(A, Reach.HOST_BARE)))
            .isEqualTo(new Unpreserved(List.of("unpushed:x", "op:cherry-pick")));
    }

    /// A detached HEAD on no branch is a tip of its own; HEAD on a branch's tip is that branch, never listed twice.
    @Test
    void of_checksADetachedHead_asItsOwnTip() {
        var detached = facts(none(), B, List.of(tip("x", A)));
        var detachedOnTip = facts(none(), A, List.of(tip("x", A)));

        assertThat(Preservation.of(detached, Map.of(A, Reach.MIRROR, B, Reach.NOWHERE)))
            .isEqualTo(new Unpreserved(List.of("unpushed:HEAD")));
        assertThat(Preservation.of(detached, Map.of(A, Reach.MIRROR, B, Reach.MIRROR))).isEqualTo(new Preserved());
        assertThat(Preservation.of(detachedOnTip, Map.of(A, Reach.NOWHERE)))
            .isEqualTo(new Unpreserved(List.of("unpushed:x")));
    }
}
