package org.pragmatica.kiln.core.checkout;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.scan.BranchTip;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.some;


/// Whether a checkout's work survives its deletion (spec §5.3, W4). GitHub is the only durability authority.
public sealed interface Preservation {
    /// Every tip is in the mirror and the tree, untracked files and stash are all clean.
    record Preserved() implements Preservation {}

    /// Every tip GitHub lacks is in the host's own bare and the tree is clean: not lost, not durable.
    record HostBareOnly() implements Preservation {}

    /// Otherwise, with reasons such as `unpushed:<branch>`, `dirty:N`, `untracked:N`, `stash:N`, `op:<name>`.
    record Unpreserved(List<String> reasons) implements Preservation {
        public Unpreserved {
            reasons = List.copyOf(reasons);
        }
    }

    /// The tips are every local branch's tip and HEAD; HEAD counts on its own (as `HEAD`) only when no branch has it
    /// as its tip, a detached HEAD. A tip with no `reach` answer reads [Reach#UNKNOWN], never preserved. Every tip the
    /// mirror lacks is an `unpushed:` reason of an [Unpreserved], the ones the host's bare holds included: they are not
    /// on GitHub.
    static Preservation of(CheckoutFacts facts, Map<Sha, Reach> reach) {
        var unpushed = tips(facts).filter(tip -> reachOf(tip, reach) != Reach.MIRROR).toList();
        var tree = tree(facts);

        return unpushed.isEmpty() && tree.isEmpty()
               ? new Preserved()
               : notDurable(unpushed, tree, reach);
    }

    private static Preservation notDurable(List<Entry<String, Sha>> unpushed,
                                           List<String> tree,
                                           Map<Sha, Reach> reach) {
        return tree.isEmpty() && unpushed.stream()
                                         .allMatch(tip -> reachOf(tip, reach) == Reach.HOST_BARE)
               ? new HostBareOnly()
               : new Unpreserved(Stream.concat(unpushed.stream().map(tip -> "unpushed:" + tip.getKey()),
                                               tree.stream())
                                       .toList());
    }

    /// Each tip labelled by its branch, or `HEAD` for a detached HEAD.
    private static Stream<Entry<String, Sha>> tips(CheckoutFacts facts) {
        var branches = facts.branches().stream().map(Preservation::labelled);
        var detached = facts.head()
                            .filter(head -> !isBranchTip(facts, head))
                            .map(head -> Map.entry("HEAD", head))
                            .stream();

        return Stream.concat(branches, detached);
    }

    private static Entry<String, Sha> labelled(BranchTip branch) {
        return Map.entry(branch.name().value(),
                         branch.tip());
    }

    private static boolean isBranchTip(CheckoutFacts facts, Sha head) {
        return facts.branches()
                    .stream()
                    .anyMatch(branch -> branch.tip()
                                              .equals(head));
    }

    private static Reach reachOf(Entry<String, Sha> tip, Map<Sha, Reach> reach) {
        return reach.getOrDefault(tip.getValue(), Reach.UNKNOWN);
    }

    /// The working-tree reasons, in the spec's order: modified, untracked, stash, operation.
    private static List<String> tree(CheckoutFacts facts) {
        return Stream.of(counted("dirty",
                                 facts.modified()),
                         counted("untracked",
                                 facts.untracked()),
                         counted("stash",
                                 facts.stash()),
                         facts.op().map(op -> "op:" + op))
                     .flatMap(Option::stream)
                     .toList();
    }

    private static Option<String> counted(String reason, int count) {
        return some(count).filter(Verify.Is::positive)
                   .map(positive -> reason + ":" + positive);
    }
}
