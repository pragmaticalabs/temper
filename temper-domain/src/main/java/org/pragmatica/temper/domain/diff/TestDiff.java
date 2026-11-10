package org.pragmatica.temper.domain.diff;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;

import static org.pragmatica.lang.Option.option;


/// What changed between side A and side B of `temper diff` (m2-design §3.2; rulings R-M2-9 on Q-M2-8 and R-M2-13 on
/// Q-M2-30), pure set algebra over the testcase rows of each side.
///
/// The identity key is `(class, name)` as the report names them. Per side, the rows of each key are folded into one
/// [KeyOutcome]. Then:
///
/// | Section | Rule |
/// |---|---|
/// | `added` | key in B, not in A; each entry carries B's outcome, so a new failing test is never hidden among additions |
/// | `removed` | key in A, not in B; each entry carries A's outcome |
/// | `newlyFailing` | in both; FAILED in B; not FAILED in A, so SKIPPED → FAILED is newly failing (R-M2-13) |
/// | `newlyPassing` | in both; FAILED in A; PASSED in B |
/// | `skipChanged` | in both; PASSED → SKIPPED, SKIPPED → PASSED or FAILED → SKIPPED: in no section above, and never in the exit code. FAILED → SKIPPED is not a fix |
///
/// `duplicates` is the number of keys with more than one row on either side, each key counted once. `testcasesB` is
/// side B's row count: a B that ran no tests is never a clean diff (R-M2-13, `ExitCode.forDiff`). Every list is in
/// class, then name order.
public record TestDiff(List<DiffEntry> added,
                       List<DiffEntry> removed,
                       List<DiffEntry> newlyFailing,
                       List<DiffEntry> newlyPassing,
                       List<SkipChange> skipChanged,
                       int duplicates,
                       int testcasesB) {
    private static final Comparator<Key> KEY_ORDER = Comparator.comparing(Key::className).thenComparing(Key::name);

    public TestDiff {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        newlyFailing = List.copyOf(newlyFailing);
        newlyPassing = List.copyOf(newlyPassing);
        skipChanged = List.copyOf(skipChanged);
    }

    public static TestDiff testDiff(List<DiffEntry> added,
                                    List<DiffEntry> removed,
                                    List<DiffEntry> newlyFailing,
                                    List<DiffEntry> newlyPassing,
                                    List<SkipChange> skipChanged,
                                    int duplicates,
                                    int testcasesB) {
        return new TestDiff(added, removed, newlyFailing, newlyPassing, skipChanged, duplicates, testcasesB);
    }

    /// Pure; the rows may come in any order.
    public static TestDiff compare(List<TestcaseRow> a, List<TestcaseRow> b) {
        var before = folded(a);
        var after = folded(b);

        return testDiff(only(after, before),
                        only(before, after),
                        changed(before, after, TestDiff::isNewlyFailing),
                        changed(before, after, TestDiff::isNewlyPassing),
                        skipChanged(before, after),
                        duplicates(a, b),
                        b.size());
    }

    /// The added tests that fail in B.
    public int addedFailing() {
        return (int) added.stream()
                          .filter(item -> item.outcome() == KeyOutcome.FAILED)
                          .count();
    }

    /// The identity of a test on either side.
    record Key(String className, String name) {
        static Key key(TestcaseRow row) {
            return new Key(row.className(), row.name());
        }
    }

    private static boolean isNewlyFailing(KeyOutcome a, KeyOutcome b) {
        return a != KeyOutcome.FAILED && b == KeyOutcome.FAILED;
    }

    private static boolean isNewlyPassing(KeyOutcome a, KeyOutcome b) {
        return a == KeyOutcome.FAILED && b == KeyOutcome.PASSED;
    }

    private static boolean isSkipChange(KeyOutcome a, KeyOutcome b) {
        return a != b
               && (b == KeyOutcome.SKIPPED || a == KeyOutcome.SKIPPED && b == KeyOutcome.PASSED);
    }

    private static SortedMap<Key, KeyOutcome> folded(List<TestcaseRow> rows) {
        return grouped(rows).entrySet()
                      .stream()
                      .collect(Collectors.toMap(Map.Entry::getKey,
                                                item -> KeyOutcome.fold(item.getValue()),
                                                (first, _) -> first,
                                                () -> new TreeMap<>(KEY_ORDER)));
    }

    private static Map<Key, List<Outcome>> grouped(List<TestcaseRow> rows) {
        return rows.stream()
                   .collect(Collectors.groupingBy(Key::key,
                                                  Collectors.mapping(TestcaseRow::outcome,
                                                                     Collectors.toList())));
    }

    /// The keys of `side` that `other` does not have, with `side`'s outcome.
    private static List<DiffEntry> only(SortedMap<Key, KeyOutcome> side, SortedMap<Key, KeyOutcome> other) {
        return side.entrySet()
                   .stream()
                   .filter(item -> !other.containsKey(item.getKey()))
                   .map(TestDiff::entry)
                   .toList();
    }

    /// The keys on both sides whose move from A to B satisfies `rule`, with B's outcome.
    private static List<DiffEntry> changed(SortedMap<Key, KeyOutcome> before,
                                           SortedMap<Key, KeyOutcome> after,
                                           Fn2<Boolean, KeyOutcome, KeyOutcome> rule) {
        return after.entrySet()
                    .stream()
                    .filter(item -> moved(before, item, rule))
                    .map(TestDiff::entry)
                    .toList();
    }

    private static List<SkipChange> skipChanged(SortedMap<Key, KeyOutcome> before, SortedMap<Key, KeyOutcome> after) {
        return after.entrySet()
                    .stream()
                    .filter(item -> moved(before, item, TestDiff::isSkipChange))
                    .map(item -> skipChange(before, item))
                    .toList();
    }

    private static boolean moved(SortedMap<Key, KeyOutcome> before,
                                 Map.Entry<Key, KeyOutcome> after,
                                 Fn2<Boolean, KeyOutcome, KeyOutcome> rule) {
        return option(before.get(after.getKey())).filter(was -> rule.apply(was,
                                                                           after.getValue()))
                     .isPresent();
    }

    /// Only for a key `before` has ([#moved] filtered on it).
    private static SkipChange skipChange(SortedMap<Key, KeyOutcome> before, Map.Entry<Key, KeyOutcome> after) {
        return SkipChange.skipChange(after.getKey().className(),
                                     after.getKey().name(),
                                     before.get(after.getKey()),
                                     after.getValue());
    }

    private static DiffEntry entry(Map.Entry<Key, KeyOutcome> entry) {
        return DiffEntry.diffEntry(entry.getKey().className(),
                                   entry.getKey().name(),
                                   entry.getValue());
    }

    private static int duplicates(List<TestcaseRow> a, List<TestcaseRow> b) {
        return (int) Stream.concat(repeated(a),
                                   repeated(b))
                           .distinct()
                           .count();
    }

    private static Stream<Key> repeated(List<TestcaseRow> rows) {
        return grouped(rows).entrySet()
                      .stream()
                      .filter(item -> item.getValue()
                                          .size() > 1)
                      .map(Map.Entry::getKey);
    }
}
