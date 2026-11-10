package org.pragmatica.kiln.core.checkout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.lang.Option;

import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Facts, PRs and ancestry for the checkout rules: a clean checkout with no processes, active an hour ago, and PRs of
/// the base repository unless a test says otherwise.
sealed interface CheckoutFixtures {
    String REPO = "pragmaticalabs/pragmatica";
    Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    Duration STALE_AFTER = Duration.ofHours(48);

    /// A full SHA of one repeated hex digit.
    static Sha sha(char digit) {
        return ok(Sha.sha(String.valueOf(digit)
                                .repeat(40)));
    }

    static PrNumber number(long value) {
        return ok(PrNumber.prNumber(value));
    }

    static BranchName branch(String name) {
        return ok(BranchName.branchName(name));
    }

    static BranchTip tip(String name, Sha tip) {
        return new BranchTip(branch(name), tip);
    }

    /// A clean checkout on `branch` (none: detached) at `head`, with these local branches.
    static CheckoutFacts facts(Option<String> branch, Sha head, List<BranchTip> branches) {
        return new CheckoutFacts(ok(CheckoutPath.checkoutPath("/w/co/x")),
                                 some(head),
                                 branch.map(CheckoutFixtures::branch),
                                 some("https://github.com/" + REPO + ".git"),
                                 branches,
                                 0,
                                 0,
                                 none(),
                                 NOW.minus(Duration.ofHours(1)),
                                 List.of(),
                                 false,
                                 "/w/co/x/.git",
                                 0,
                                 none());
    }

    /// A clean checkout on `name`, whose only branch has HEAD as its tip.
    static CheckoutFacts onBranch(String name, Sha head) {
        return facts(some(name), head, List.of(tip(name, head)));
    }

    static CheckoutFacts withTree(CheckoutFacts facts, int modified, int untracked, int stash, Option<String> op) {
        return new CheckoutFacts(facts.path(), facts.head(), facts.branch(), facts.remoteUrl(), facts.branches(),
                                 modified, untracked, op, facts.activityAt(), facts.processes(), facts.maven(),
                                 facts.commonDir(), stash, facts.salvageRefs());
    }

    static CheckoutFacts withProcesses(CheckoutFacts facts, List<Long> processes) {
        return new CheckoutFacts(facts.path(), facts.head(), facts.branch(), facts.remoteUrl(), facts.branches(),
                                 facts.modified(), facts.untracked(), facts.op(), facts.activityAt(), processes,
                                 facts.maven(), facts.commonDir(), facts.stash(), facts.salvageRefs());
    }

    static CheckoutFacts withActivity(CheckoutFacts facts, Instant activityAt) {
        return new CheckoutFacts(facts.path(), facts.head(), facts.branch(), facts.remoteUrl(), facts.branches(),
                                 facts.modified(), facts.untracked(), facts.op(), activityAt, facts.processes(),
                                 facts.maven(), facts.commonDir(), facts.stash(), facts.salvageRefs());
    }

    /// A PR of the base repository, updated `hoursAgo` before [#NOW].
    static PullRequest pr(long number, PrState state, String headRef, Sha head, long hoursAgo) {
        return pr(number, state, headRef, head, hoursAgo, some(REPO));
    }

    static PullRequest pr(long number, PrState state, String headRef, Sha head, long hoursAgo, Option<String> repo) {
        return new PullRequest(number(number),
                               "pr " + number,
                               state,
                               false,
                               branch("main"),
                               branch(headRef),
                               repo,
                               head,
                               state == PrState.MERGED
                               ? some(NOW.minus(Duration.ofHours(hoursAgo)))
                               : none(),
                               state == PrState.OPEN
                               ? none()
                               : some(NOW.minus(Duration.ofHours(hoursAgo))),
                               NOW.minus(Duration.ofHours(hoursAgo)),
                               List.of(),
                               List.of(),
                               List.of());
    }

    static PrIndex index(PullRequest... prs) {
        return PrIndex.prIndex(List.of(prs), REPO);
    }

    static Ancestry ancestry(boolean atBase, Set<PrNumber> headsContainingHead, Set<Sha> offBaseCommitsOfHead) {
        return new Ancestry(true, atBase, headsContainingHead, offBaseCommitsOfHead);
    }

    static Ancestry offBase() {
        return ancestry(false, Set.of(), Set.of());
    }

    static Ancestry atBase() {
        return ancestry(true, Set.of(), Set.of());
    }

    /// Every tip of `facts` (and HEAD) reachable as `reach`.
    static Map<Sha, Reach> every(CheckoutFacts facts, Reach reach) {
        return Stream.concat(facts.branches()
                                  .stream()
                                  .map(BranchTip::tip),
                             facts.head()
                                  .stream())
                     .distinct()
                     .collect(Collectors.toMap(tip -> tip, tip -> reach));
    }

    record unused() implements CheckoutFixtures {}
}
