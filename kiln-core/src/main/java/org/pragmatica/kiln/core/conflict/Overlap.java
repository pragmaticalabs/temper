package org.pragmatica.kiln.core.conflict;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.pragmatica.kiln.core.model.PrNumber;


/// The pair filter (temper #100 ruling, widened by PR #108 review F3): a pair check runs only for two open PRs whose
/// changes can meet in a merge, which bounds the pair checks per step by those pairs instead of n(n-1)/2. Two PRs meet
/// when
/// - they touch a common path (both sides of a rename count);
/// - one adds a path the other puts a directory at: `a` against `a/b` (a directory/file conflict);
/// - one moves files out of a directory the other adds into: `d/one` to `e/one` against a new `d/new` (git's directory
///   rename detection, a Java package move with a new class in the old package).
///
/// Two PRs that meet in none of these ways have disjoint trees apart from new files in directories neither moves, and
/// merge-tree cannot report a textual conflict for them. The filter's limit is git's own rename detection: a rename git
/// does not detect between base and head (below `-M`'s similarity threshold) reads as a delete and an add.
public sealed interface Overlap {
    /// Every pair, lower number first, whose changes meet ([#meet]), ordered by (first, second).
    static List<Pair> pairs(Map<PrNumber, Changes> changed) {
        var prs = changed.keySet().stream().sorted(Comparator.comparingLong(PrNumber::value)).toList();

        return IntStream.range(0,
                               prs.size())
                        .boxed()
                        .flatMap(i -> IntStream.range(i + 1,
                                                      prs.size())
                                               .mapToObj(j -> new Pair(prs.get(i),
                                                                       prs.get(j))))
                        .filter(pair -> meet(changed.get(pair.first()),
                                             changed.get(pair.second())))
                        .toList();
    }

    /// Whether a merge of `a` and `b` can conflict: a shared path, a path one adds where the other has a directory, or a
    /// directory one moves that the other adds into.
    static boolean meet(Changes a, Changes b) {
        var underA = a.addedUnder();
        var underB = b.addedUnder();

        return ! Collections.disjoint(a.paths(), b.paths()) || !Collections.disjoint(a.added(), underB) || !Collections.disjoint(b.added(),
                                                                                                                                 underA) || !Collections.disjoint(a.movedFrom(),
                                                                                                                                                                  underB) || !Collections.disjoint(b.movedFrom(),
                                                                                                                                                                                                   underA);
    }

    /// Two open PRs, `first` the lower number: the pair check squash-merges `first` onto the tip, then merges `second`.
    record Pair(PrNumber first, PrNumber second) {
        public String key() {
            return Prediction.key(Prediction.Kind.PAIR, List.of(first, second));
        }
    }

    record unused() implements Overlap {}
}
