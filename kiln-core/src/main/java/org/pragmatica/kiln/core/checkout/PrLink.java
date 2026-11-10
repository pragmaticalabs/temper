package org.pragmatica.kiln.core.checkout;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The PRs a checkout links to and the rule that linked them (spec §5.3): branch name > SHA > ancestry, the first rule
/// that yields at least one PR wins. A stacked chain links every PR of it.
public record PrLink(List<PrNumber> prs, LinkRule rule) {
    public static final PrLink NONE = new PrLink(List.of(), LinkRule.NONE);

    public PrLink {
        prs = List.copyOf(prs);
    }

    /// Rule 1 links the one PR whose head ref is the checkout's branch, choosing among PRs that reused the name: the
    /// open one, then one whose head SHA equals or contains HEAD, then the newest by `updated_at` (then the higher
    /// number, so the choice is total). Rule 2 links every PR whose head SHA is HEAD. Rule 3, only when HEAD is not at
    /// a base, links every PR whose head contains HEAD or lies in HEAD's off-base commits ([Ancestry]). The PRs of
    /// rules 2 and 3 are listed by number.
    public static PrLink link(CheckoutFacts facts, Ancestry ancestry, PrIndex index) {
        return byBranch(facts, ancestry, index).orElse(() -> bySha(facts, index))
                       .orElse(() -> byAncestry(ancestry, index))
                       .or(NONE);
    }

    private static Option<PrLink> byBranch(CheckoutFacts facts, Ancestry ancestry, PrIndex index) {
        return facts.branch()
                    .flatMap(branch -> reusing(branch, facts, ancestry, index))
                    .map(pr -> new PrLink(List.of(pr.number()),
                                          LinkRule.BRANCH));
    }

    private static Option<PullRequest> reusing(BranchName branch,
                                               CheckoutFacts facts,
                                               Ancestry ancestry,
                                               PrIndex index) {
        return Option.from(index.prs()
                                .stream()
                                .filter(pr -> pr.headRef()
                                                .equals(branch))
                                .min(reuseOrder(facts, ancestry)));
    }

    /// Smallest first: open, then holding HEAD, then newest `updated_at`, then the higher number.
    private static Comparator<PullRequest> reuseOrder(CheckoutFacts facts, Ancestry ancestry) {
        return Comparator.comparing((PullRequest pr) -> pr.state() != PrState.OPEN)
                         .thenComparing(pr -> !holdsHead(pr, facts, ancestry))
                         .thenComparing(PullRequest::updatedAt,
                                        Comparator.reverseOrder())
                         .thenComparing(pr -> pr.number()
                                                .value(),
                                        Comparator.reverseOrder());
    }

    private static boolean holdsHead(PullRequest pr, CheckoutFacts facts, Ancestry ancestry) {
        return facts.head()
                    .map(pr.head()::equals)
                    .or(false) || ancestry.headsContainingHead()
                                          .contains(pr.number());
    }

    private static Option<PrLink> bySha(CheckoutFacts facts, PrIndex index) {
        return facts.head()
                    .flatMap(head -> linked(index,
                                            headAt(head),
                                            LinkRule.SHA));
    }

    private static Predicate<PullRequest> headAt(Sha head) {
        return pr -> pr.head()
                       .equals(head);
    }

    /// A checkout at or behind a base links to no PR through ancestry (spec §5.3).
    private static Option<PrLink> byAncestry(Ancestry ancestry, PrIndex index) {
        return ancestry.atBase()
               ? none()
               : linked(index, pr -> isRelated(pr, ancestry), LinkRule.ANCESTRY);
    }

    private static boolean isRelated(PullRequest pr, Ancestry ancestry) {
        return ancestry.headsContainingHead()
                       .contains(pr.number()) || ancestry.offBaseCommitsOfHead()
                                                         .contains(pr.head());
    }

    private static Option<PrLink> linked(PrIndex index, Predicate<PullRequest> matches, LinkRule rule) {
        return some(index.prs()
                         .stream()
                         .filter(matches)
                         .map(PullRequest::number)
                         .sorted(Comparator.comparingLong(PrNumber::value))
                         .toList()).filter(Predicate.not(List::isEmpty))
                   .map(prs -> new PrLink(prs, rule));
    }
}
