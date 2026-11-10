package org.pragmatica.kiln.core.feed;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.github.Compare;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// `BASE_MOVED`: a configured base's tip moved from `old` to `now`. Whether the move changed the
/// base's tree is judged from one `compare/{old}...{now}` read:
/// - a compare whose `status` is not `ahead` (`behind`, `diverged`): the base was rewound; `rewound`, `treeChanged`;
/// - `ahead`: from `now` along each commit's first parent until `old`, a commit whose tree differs from its first
///   parent's marks a change (`old`'s tree is the stored one). `commits` counts the commits walked, `firstChanged` is
///   the oldest changed one;
/// - fail-safe: a parent that is neither `old` nor listed (GitHub lists at most 250 commits, or `old` is not on the
///   first-parent line), and a failed compare read, are `complete=false, treeChanged=true`, with no `firstChanged`: a
///   walk that did not reach `old` cannot name the oldest change.
public sealed interface BaseFeed {
    /// A base's tip as stored: its commit and that commit's tree.
    record Tip(Sha sha, Sha tree) {}

    /// The judged move.
    record Moved(boolean treeChanged, boolean rewound, int commits, Option<Sha> firstChanged, boolean complete) {}

    /// The move from `old` to `now`, judged on `compare`.
    static Moved moved(Tip old, Sha now, Result<Compare> compare) {
        return compare.map(read -> judged(old, now, read))
                      .or(incomplete(0));
    }

    private static Moved judged(Tip old, Sha now, Compare compare) {
        return Compare.AHEAD.equals(compare.status())
               ? walk(old, index(compare), now, 0, none())
               : new Moved(true, true, 0, none(), true);
    }

    private static Map<Sha, Compare.Commit> index(Compare compare) {
        return compare.commits()
                      .stream()
                      .collect(Collectors.toMap(Compare.Commit::sha,
                                                Function.identity(),
                                                (_, last) -> last));
    }

    /// One step of the first-parent walk at `at`, `walked` commits in. A walk longer than the listing is a cycle in a
    /// malformed answer, read as incomplete.
    private static Moved walk(Tip old, Map<Sha, Compare.Commit> index, Sha at, int walked, Option<Sha> first) {
        if (at.equals(old.sha())) {
            return new Moved(first.isPresent(), false, walked, first, true);
        }

        if (walked > index.size()) {
            return incomplete(walked);
        }

        return option(index.get(at)).map(commit -> stepped(old, index, commit, walked, first))
                     .or(() -> incomplete(walked));
    }

    private static Moved stepped(Tip old,
                                 Map<Sha, Compare.Commit> index,
                                 Compare.Commit commit,
                                 int walked,
                                 Option<Sha> first) {
        return commit.parent()
                     .flatMap(parent -> treeOf(old, index, parent).map(tree -> walk(old,
                                                                                    index,
                                                                                    parent,
                                                                                    walked + 1,
                                                                                    changed(commit, tree, first))))
                     .or(() -> incomplete(walked + 1));
    }

    /// The tree of `parent`: the stored one for `old`, else the listed commit's; none when it is neither.
    private static Option<Sha> treeOf(Tip old, Map<Sha, Compare.Commit> index, Sha parent) {
        return parent.equals(old.sha())
               ? some(old.tree())
               : option(index.get(parent)).map(Compare.Commit::tree);
    }

    /// Walking back from the new tip, the last changed commit met is the oldest.
    private static Option<Sha> changed(Compare.Commit commit, Sha parentTree, Option<Sha> first) {
        return commit.tree()
                     .equals(parentTree)
               ? first
               : some(commit.sha());
    }

    private static Moved incomplete(int walked) {
        return new Moved(true, false, walked, none(), false);
    }

    /// The `BASE_MOVED` row: subject the base, before and after the old and new tips, no PR, and the payload in the
    /// contract's order.
    static FeedChange change(BranchName base, Sha old, Sha now, Moved moved, Instant at) {
        return new FeedChange(FeedKind.BASE_MOVED,
                              none(),
                              base.value(),
                              old.value(),
                              now.value(),
                              at,
                              FeedChange.payload("base",
                                                 base.value(),
                                                 "treeChanged",
                                                 moved.treeChanged(),
                                                 "rewound",
                                                 moved.rewound(),
                                                 "commits",
                                                 moved.commits(),
                                                 "firstChanged",
                                                 moved.firstChanged().map(Sha::value),
                                                 "complete",
                                                 moved.complete()));
    }

    record unused() implements BaseFeed {}
}
