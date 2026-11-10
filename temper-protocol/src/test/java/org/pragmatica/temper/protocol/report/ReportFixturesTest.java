package org.pragmatica.temper.protocol.report;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.verdict.Counts;
import org.pragmatica.temper.domain.verdict.Evaluation;
import org.pragmatica.temper.domain.verdict.Manifest;
import org.pragmatica.temper.domain.verdict.UnitOutcome;
import org.pragmatica.temper.domain.verdict.Verdict;
import org.pragmatica.temper.domain.verdict.VerdictInput;
import org.pragmatica.temper.domain.verdict.Verdicts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// A2 on fixture report sets (`src/test/resources/reports/`): each case parses real surefire XML and
/// evaluates the verdict, so parser and verdict rules are pinned together. The ordering rules themselves
/// are pinned with in-code rows in temper-domain's `VerdictsTest`; stale-XML and copied-marker selection in
/// its `ReportSelectionTest`.
class ReportFixturesTest {
    private static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    static byte[] report(String name) {
        return ok(Result.lift(Causes::fromThrowable,
                              () -> ReportFixturesTest.class.getResourceAsStream("/reports/" + name)
                                                            .readAllBytes()));
    }

    private static final UnitName A = ok(UnitName.unitName("a"));
    private static final UnitName B = ok(UnitName.unitName("b"));
    private static final UnitName MAIN = UnitName.MAIN;

    private static ReportFile parsed(String fixture) {
        return ok(ReportParser.parse(fixture, report(fixture)));
    }

    private static List<TestcaseRow> rows(UnitName unit, String fixture) {
        return parsed(fixture).testcases()
                              .stream()
                              .map(testcase -> TestcaseRow.testcaseRow(unit, testcase))
                              .toList();
    }

    private static UnitOutcome finished(UnitName name, int rc, List<TestcaseRow> rows) {
        return UnitOutcome.unitOutcome(name, true, false, Option.some(rc), Option.none(), Option.none(), Option.none(), rows, rows.size());
    }

    private static UnitOutcome fromFixture(UnitName name, int rc, String fixture) {
        var file = parsed(fixture);

        return UnitOutcome.unitOutcome(name,
                                       true,
                                       false,
                                       Option.some(rc),
                                       Option.none(),
                                       Option.none(),
                                       Option.none(),
                                       rows(name, fixture),
                                       file.testsAttr());
    }

    private static Evaluation evaluate(UnitOutcome... units) {
        return Verdicts.evaluate(VerdictInput.verdictInput(List.of(units), false, false, Option.none()));
    }

    @Test
    void evaluate_isEmpty_whenUnitRequestedTestsExitedZeroAndReportedNone() {
        var evaluation = evaluate(fromFixture(MAIN, 0, "TEST-empty-suite.xml"));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Empty(List.of(MAIN)));
        assertThat(evaluation.counts()
                             .testcases()).isZero();
    }

    @Test
    void evaluate_isFailedMissingClasses_whenManifestClassIsNotReported() {
        var manifest = Manifest.manifest(Set.of("org.example.CalcTest", "org.example.CrashedForkTest"), Set.of());
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(fromFixture(MAIN, 0, "TEST-passing.xml")),
                                                                     false,
                                                                     false,
                                                                     Option.some(manifest)));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("missing-classes"));
        assertThat(evaluation.counts()
                             .manifest()).isEqualTo(Option.some(2));
        assertThat(evaluation.counts()
                             .missing()).isEqualTo(Option.some(1));
    }

    @Test
    void evaluate_recordsDrift_forReportedClassesOutsideManifest() {
        var manifest = Manifest.manifest(Set.of("org.example.Other"), Set.of());
        var evaluation = Verdicts.evaluate(VerdictInput.verdictInput(List.of(fromFixture(MAIN, 0, "TEST-passing.xml")),
                                                                     false,
                                                                     false,
                                                                     Option.some(manifest)));

        assertThat(evaluation.drift()).containsExactly("org.example.CalcTest");
    }

    @Test
    void evaluate_isErrorPlan_whenOneClassIsReportedByTwoUnits() {
        var evaluation = evaluate(fromFixture(A, 0, "TEST-passing.xml"), fromFixture(B, 0, "TEST-passing.xml"));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Error("plan"));
    }

    @Test
    void evaluate_countsTestcaseRowsNotTestsAttr_forNestedUndercount() {
        var evaluation = evaluate(fromFixture(MAIN, 0, "TEST-nested-undercount.xml"));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Passed());
        assertThat(evaluation.counts()
                             .testcases()).isEqualTo(5);
        assertThat(evaluation.counts()
                             .testsAttr()).isEqualTo(3);
        assertThat(evaluation.counts()
                             .classes()).isEqualTo(3);
    }

    @Test
    void evaluate_isFailedTests_andCountsEveryOutcome_forMixedReport() {
        var evaluation = evaluate(fromFixture(MAIN, 1, "TEST-mixed-outcomes.xml"));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Failed("tests"));
        assertThat(evaluation.counts()).isEqualTo(Counts.counts(9, 3, 2, 3, 1, 9, 1, Option.none(), Option.none(), Option.none()));
        assertThat(evaluation.observedFailures()).isEqualTo(5);
    }
    
    @Test
    void evaluate_isEmpty_perUnit_whenOtherUnitReportedTests() {
        var evaluation = evaluate(fromFixture(A, 0, "TEST-testsuites-wrapper.xml"), fromFixture(B, 0, "TEST-empty-suite.xml"));

        assertThat(evaluation.verdict()).isEqualTo(new Verdict.Empty(List.of(B)));
    }
}
