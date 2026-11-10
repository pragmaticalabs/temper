package org.pragmatica.temper.domain.known;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m2-design §4.5 `KnownMatchTest`: a class entry covers every case of its class, a case entry exactly its case, a
/// nested class is its own class, and nothing matches by prefix or by simple name.
class KnownMatchTest {
    private static TestcaseRow failed(String className, String name) {
        return TestcaseRow.testcaseRow(UnitName.MAIN, className, name, Outcome.FAILED, 0.1, false);
    }

    @Test
    void classEntry_coversEveryCaseOfItsClass() {
        var match = KnownMatch.knownMatch(Map.of("org.x.FooTest", 1679));

        assertThat(match.issue(failed("org.x.FooTest", "one"))).isEqualTo(some(1679));
        assertThat(match.issue(failed("org.x.FooTest", "two()[1]"))).isEqualTo(some(1679));
        assertThat(match.mark(failed("org.x.FooTest", "one"))).isEqualTo(some("#1679"));
    }

    /// An ERROR testcase is a red as a FAILED one is (spec §5.3): the registry covers both alike.
    @Test
    void errorOutcome_isCoveredAsAFailure() {
        var match = KnownMatch.knownMatch(Map.of("org.x.FooTest", 1679, "org.x.BarTest#x", 21));
        var errored = TestcaseRow.testcaseRow(UnitName.MAIN, "org.x.FooTest", "one", Outcome.ERROR, 0.1, false);
        var caseErrored = TestcaseRow.testcaseRow(UnitName.MAIN, "org.x.BarTest", "x", Outcome.ERROR, 0.1, false);

        assertThat(match.mark(errored)).isEqualTo(some("#1679"));
        assertThat(match.mark(caseErrored)).isEqualTo(some("#21"));
        assertThat(match.annotations(List.of(errored, caseErrored))).containsExactly("KNOWN(#21)", "KNOWN(#1679)");
    }

    @Test
    void caseEntry_coversExactlyThatCase() {
        var match = KnownMatch.knownMatch(Map.of("org.x.FooTest#one", 7));

        assertThat(match.issue(failed("org.x.FooTest", "one"))).isEqualTo(some(7));
        assertThat(match.issue(failed("org.x.FooTest", "one2"))).isEqualTo(none());
        assertThat(match.issue(failed("org.x.FooTest", "on"))).isEqualTo(none());
        assertThat(match.issue(failed("org.x.FooTest", "two"))).isEqualTo(none());
    }

    @Test
    void nestedClass_isItsOwnClass() {
        var nested = KnownMatch.knownMatch(Map.of("org.x.Outer$Inner", 3));
        var outer = KnownMatch.knownMatch(Map.of("org.x.Outer", 4));

        assertThat(nested.issue(failed("org.x.Outer$Inner", "one"))).isEqualTo(some(3));
        assertThat(nested.issue(failed("org.x.Outer", "one"))).isEqualTo(none());
        assertThat(outer.issue(failed("org.x.Outer$Inner", "one"))).isEqualTo(none());
    }

    @Test
    void noPrefixMatching() {
        var match = KnownMatch.knownMatch(Map.of("a.Foo", 1, "a.Bar#case", 2));

        assertThat(match.issue(failed("a.FooBar", "one"))).isEqualTo(none());
        assertThat(match.issue(failed("a.Foo2", "one"))).isEqualTo(none());
        assertThat(match.issue(failed("a.Fo", "one"))).isEqualTo(none());
        assertThat(match.issue(failed("a.Bar", "case2"))).isEqualTo(none());
        assertThat(match.issue(failed("a.Foo.Inner", "one"))).isEqualTo(none());
    }

    @Test
    void noSimpleNameMatching() {
        var simple = KnownMatch.knownMatch(Map.of("FooTest", 1, "FooTest#one", 2));

        assertThat(simple.issue(failed("a.b.FooTest", "one"))).isEqualTo(none());
        assertThat(KnownMatch.knownMatch(Map.of("a.b.FooTest", 1)).issue(failed("FooTest", "one"))).isEqualTo(none());
    }

    @Test
    void caseEntry_winsOverClassEntry() {
        var match = KnownMatch.knownMatch(Map.of("a.FooTest", 1, "a.FooTest#one", 2));

        assertThat(match.issue(failed("a.FooTest", "one"))).isEqualTo(some(2));
        assertThat(match.issue(failed("a.FooTest", "two"))).isEqualTo(some(1));
    }

    @Test
    void caseName_withAHash_matchesWhole() {
        var match = KnownMatch.knownMatch(Map.of("a.FooTest#run#2", 5));

        assertThat(match.issue(failed("a.FooTest", "run#2"))).isEqualTo(some(5));
        assertThat(match.issue(failed("a.FooTest", "run"))).isEqualTo(none());
    }

    @Test
    void annotations_onePerDistinctIssue_sortedNumerically() {
        var match = KnownMatch.knownMatch(Map.of("a.ATest", 1679, "a.BTest#x", 21, "a.CTest", 21));
        var failed = List.of(failed("a.ATest", "one"),
                             failed("a.ATest", "two"),
                             failed("a.BTest", "x"),
                             failed("a.CTest", "y"),
                             failed("a.DTest", "z"));

        assertThat(match.annotations(failed)).containsExactly("KNOWN(#21)", "KNOWN(#1679)");
    }

    @Test
    void annotations_noneWhenNothingMatches() {
        assertThat(KnownMatch.NONE.annotations(List.of(failed("a.ATest", "one")))).isEmpty();
        assertThat(KnownMatch.NONE.mark(failed("a.ATest", "one"))).isEqualTo(none());
    }
}
