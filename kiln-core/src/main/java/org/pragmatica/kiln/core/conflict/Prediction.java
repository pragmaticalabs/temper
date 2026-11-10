package org.pragmatica.kiln.core.conflict;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;


/// One predicted textual conflict (temper #100): `git merge-tree --write-tree` reports `paths` as conflicted when `prs`
/// merge onto the release tip `tip`. A `TIP` prediction names one PR (its head against the tip); a `PAIR` prediction names
/// two PRs, lower number first, and means the second one's merge conflicts after the first is squash-merged.
///
/// The identity is `key()` (the kind and the PRs), not the paths or the heads: a new push that still conflicts at the
/// same tip is the same prediction. A prediction is "new" when its key was not announced at this tip (temper #100
/// ruling: NEW is a (PR set, tip) pair not seen before).
///
/// A pair is checked in both orders (PR #108 review F4: squash order is not symmetric, the second PR's merge base is its
/// own branch point). `firsts` names the PRs whose landing FIRST makes the other's merge conflict: one of the two, or
/// both; empty for a `TIP` prediction. It is not part of the identity: the same pair conflicting in another order at the
/// same tip is the same prediction.
///
/// A textual prediction only: a clean merge-tree can still break compilation (a new caller of a changed signature).
public record Prediction(Kind kind, List<PrNumber> prs, List<String> paths, Sha tip, List<PrNumber> firsts) {
    /// The two checks: a PR head against the tip, and a pair of PRs whose changed files overlap.
    public enum Kind {
        TIP,
        PAIR;
        /// The events.log spelling.
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /// Files that conflict again and again across rc work (temper #100); a path whose last component is one of these is
    /// marked hot.
    public static final Set<String> HOT = Set.of("AetherValue.java",
                                                 "AetherKey.java",
                                                 "SystemTags.java",
                                                 "ReplicaFilter.java",
                                                 "wire-assignment-baseline.txt",
                                                 "transport-outcome-allowlist.txt",
                                                 "OperatorWarningCode.java");

    public Prediction {
        prs = prs.stream().sorted(Comparator.comparingLong(PrNumber::value)).toList();
        paths = paths.stream().distinct().sorted().toList();
        firsts = firsts.stream().distinct().sorted(Comparator.comparingLong(PrNumber::value)).toList();
    }

    /// Without an order: a `TIP` prediction, or a `PAIR` read from a line that names none.
    public Prediction(Kind kind, List<PrNumber> prs, List<String> paths, Sha tip) {
        this(kind, prs, paths, tip, List.of());
    }

    public static Prediction tip(PrNumber pr, List<String> paths, Sha tip) {
        return new Prediction(Kind.TIP, List.of(pr), paths, tip);
    }

    /// `first` squash-merged onto the tip, then `second`'s merge conflicts on `paths`.
    public static Prediction pair(PrNumber first, PrNumber second, List<String> paths, Sha tip) {
        return pair(first, second, paths, tip, List.of(first));
    }

    /// The pair conflicts on `paths` when one of `firsts` lands first.
    public static Prediction pair(PrNumber first, PrNumber second, List<String> paths, Sha tip, List<PrNumber> firsts) {
        return new Prediction(Kind.PAIR, List.of(first, second), paths, tip, firsts);
    }

    /// The orders that conflict, as events.log and the tab spell them: `2711>2770` is "#2711 lands first, then #2770
    /// conflicts", comma-separated; empty for a `TIP` prediction or a pair whose order is unknown.
    public String orders() {
        return firsts.stream()
                     .map(first -> first.value()
                                  + ">" + prs.stream()
                                             .filter(pr -> !pr.equals(first))
                                             .map(pr -> Long.toString(pr.value()))
                                             .findFirst()
                                             .orElse("?"))
                     .collect(Collectors.joining(","));
    }

    /// The identity across tips and heads: `tip:2713` or `pair:2711+2770`.
    public String key() {
        return key(kind, prs);
    }

    public static String key(Kind kind, List<PrNumber> prs) {
        return kind.word() + ":" + prs(prs);
    }

    /// The PRs as events.log spells them: `2713`, or `2711+2770`.
    public static String prs(List<PrNumber> prs) {
        return prs.stream()
                  .sorted(Comparator.comparingLong(PrNumber::value))
                  .map(pr -> Long.toString(pr.value()))
                  .collect(Collectors.joining("+"));
    }

    /// Whether `path` is one of the [#HOT] files.
    public static boolean hot(String path) {
        return HOT.contains(path.substring(path.lastIndexOf('/') + 1));
    }

    /// The hot paths among this prediction's.
    public List<String> hotPaths() {
        return paths.stream()
                    .filter(Prediction::hot)
                    .toList();
    }
}
