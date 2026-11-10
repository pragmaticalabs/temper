package org.pragmatica.kiln.core.checkout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The spec §5.3 status rules over one checkout's facts, its tips' reach, its ancestry and the full PR table.
public sealed interface Derivation {
    /// Without facts (no full-facts observation from any pass, Q-5) the checkout reads [CheckoutStatus#UNSCANNED],
    /// unpreserved and unlinked, with `atBase` absent. Merged and closed come only from the linked PRs' GitHub state,
    /// never from `atBase`: a squash merge leaves HEAD off every base (K-2). A HEAD not in the mirror has no ancestry:
    /// it is at no base and links by no ancestry; rules 1 and 2 (branch name, SHA) still link it.
    static Derived derive(Option<CheckoutFacts> facts,
                          Map<Sha, Reach> reach,
                          Ancestry ancestry,
                          PrIndex index,
                          Duration staleAfter,
                          Instant now) {
        return facts.map(scanned -> derived(scanned, reach, ancestry, index, staleAfter, now))
                    .or(Derivation::unscanned);
    }

    private static Derived unscanned() {
        return new Derived(CheckoutStatus.UNSCANNED,
                           new Preservation.Unpreserved(List.of("unscanned")),
                           PrLink.NONE,
                           none());
    }

    private static Derived derived(CheckoutFacts facts,
                                   Map<Sha, Reach> reach,
                                   Ancestry ancestry,
                                   PrIndex index,
                                   Duration staleAfter,
                                   Instant now) {
        var known = known(ancestry);
        var preservation = Preservation.of(facts, reach);
        var link = PrLink.link(facts, known, index);
        var stale = facts.activityAt().isBefore(now.minus(staleAfter));

        return new Derived(status(facts,
                                  preservation instanceof Preservation.Preserved,
                                  states(link, index),
                                  known.atBase(),
                                  stale),
                           preservation,
                           link,
                           some(known.atBase()));
    }

    /// What the mirror knows of HEAD's ancestry: nothing when HEAD is not in it, whatever the adapter reported.
    private static Ancestry known(Ancestry ancestry) {
        return ancestry.headInMirror()
               ? ancestry
               : new Ancestry(false, false, Set.of(), Set.of());
    }

    private static List<PrState> states(PrLink link, PrIndex index) {
        return index.prs()
                    .stream()
                    .filter(pr -> link.prs()
                                      .contains(pr.number()))
                    .map(PullRequest::state)
                    .toList();
    }

    /// spec §5.3, the first rule that matches. Each rule relies on the ones before it: past rule 2 no linked PR is
    /// open; past rules 3 and 4 a linked or at-base checkout is not PRESERVED; past rule 5 there is no link and HEAD is
    /// at no base.
    private static CheckoutStatus status(CheckoutFacts facts,
                                         boolean preserved,
                                         List<PrState> linked,
                                         boolean atBase,
                                         boolean stale) {
        if (!facts.processes().isEmpty()) {
            return CheckoutStatus.ACTIVE;
        }

        if (linked.contains(PrState.OPEN)) {
            return CheckoutStatus.IN_USE;
        }

        if (!linked.isEmpty() && preserved) {
            return CheckoutStatus.REMOVABLE;
        }

        if (atBase && preserved) {
            return CheckoutStatus.BASE_CLEAN;
        }

        if (!linked.isEmpty() || atBase) {
            return CheckoutStatus.NEEDS_SALVAGE;
        }

        return stale
               ? CheckoutStatus.STALE_UNOWNED
               : CheckoutStatus.UNOWNED;
    }

    record unused() implements Derivation {}
}
