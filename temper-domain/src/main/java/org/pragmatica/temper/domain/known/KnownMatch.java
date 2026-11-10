package org.pragmatica.temper.domain.known;

import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.report.TestcaseRow;

import static org.pragmatica.lang.Option.option;


/// Which registry issue covers a failed testcase (spec §9, m2-design §4.2, §4.3), pure. `issues` maps each active
/// entry's `testId` to its issue; the registry holds one active entry per `testId`.
///
/// Matching is exact string equality [P]: a row of class `C` and name `N` is covered by the entry `C#N`, else by the
/// entry `C`. Nothing else matches: not a prefix (`a.Foo` does not cover `a.FooBar`, `a.Outer` does not cover
/// `a.Outer$Inner`), not a simple name (`FooTest` does not cover `a.FooTest`). When both a case entry and a class entry
/// are active for one row, the case entry's issue is the row's [G]: it is the more specific registration, and a failure
/// maps to one issue.
///
/// The marks never change a verdict: `Verdicts` never sees the registry.
public record KnownMatch(Map<String, Integer> issues) {
    public static final KnownMatch NONE = new KnownMatch(Map.of());

    public KnownMatch {
        issues = Map.copyOf(issues);
    }

    public static KnownMatch knownMatch(Map<String, Integer> issues) {
        return new KnownMatch(issues);
    }

    /// The issue covering `row`: its case entry's, else its class entry's.
    public Option<Integer> issue(TestcaseRow row) {
        return option(issues.get(row.className() + "#" + row.name())).orElse(() -> option(issues.get(row.className())));
    }

    /// The result record's `failed[].known` of `row`: `#<issue>`.
    public Option<String> mark(TestcaseRow row) {
        return issue(row).map(issue -> "#" + issue);
    }

    /// One `KNOWN(#n)` per distinct issue covering a row of `failed`, sorted by issue number.
    public List<String> annotations(List<TestcaseRow> failed) {
        return coveringIssues(failed).stream()
                             .map(issue -> "KNOWN(#" + issue + ")")
                             .toList();
    }

    /// The distinct issues covering a row of `failed`, ascending.
    private SortedSet<Integer> coveringIssues(List<TestcaseRow> failed) {
        return failed.stream()
                     .flatMap(row -> issue(row).stream())
                     .collect(Collectors.toCollection(TreeSet::new));
    }
}
