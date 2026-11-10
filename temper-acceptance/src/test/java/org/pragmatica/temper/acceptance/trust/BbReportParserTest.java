package org.pragmatica.temper.acceptance.trust;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The bb-report parser on outputs captured from the real script (m1-design §9.3): normal, red with `FAILED:`
/// lines, EMPTY exit 3, the dependency-resolution WARNING, "no rc yet", and shapes the script never prints.
class BbReportParserTest {
    @Test
    void parse_green_countsTestcaseElementsNotTheTestsSum() {
        var report = Captures.report("green");

        assertThat(report.rc()).isZero();
        assertThat(report.testcases()).isEqualTo(293);
        assertThat(report.testsAttr()).as("the @Nested undercount, kept only as a diagnostic").isEqualTo(177);
        assertThat(report.failures()).isZero();
        assertThat(report.errors()).isZero();
        assertThat(report.skipped()).isZero();
        assertThat(report.failedClasses()).isEmpty();
        assertThat(report.empty()).isFalse();
        assertThat(report.dependencyResolutionWarning()).isFalse();
        assertThat(report.argsWords()).isEmpty();
    }

    @Test
    void parse_red_readsRcCountsAndFailedFileClasses() {
        var report = Captures.report("red");

        assertThat(report.rc()).isEqualTo(1);
        assertThat(report.testcases()).isEqualTo(293);
        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.errors()).isEqualTo(1);
        assertThat(report.failedClasses()).as("file names: the @Nested error names its outermost class")
                                          .containsExactlyInAnyOrder("org.pragmatica.postgres.PipelineTest",
                                                                     "org.pragmatica.postgres.CrossTypeConversionTest");
        assertThat(report.empty()).isFalse();
    }

    @Test
    void parse_emptyRun_isEmptyWithZeroCountsAndTheArgs() {
        var report = Captures.report("empty");

        assertThat(Captures.exit("empty")).isEqualTo(3);
        assertThat(report.empty()).isTrue();
        assertThat(report.rc()).isZero();
        assertThat(report.testcases()).isZero();
        assertThat(report.argsWords()).isEqualTo(Set.of("clean", "install"));
    }

    @Test
    void parse_dependencyResolutionWarning_isFlagged() {
        var report = Captures.report("dre");

        assertThat(report.dependencyResolutionWarning()).isTrue();
        assertThat(report.testcases()).isEqualTo(11);
        assertThat(report.rc()).isEqualTo(1);
    }

    @Test
    void parse_fails_whenNoRcYet() {
        BbReportParser.parse(Captures.out("running"), Captures.exit("running"))
                      .onSuccess(report -> {
                          throw new AssertionError("parsed " + report);
                      })
                      .onFailure(cause -> assertThat(cause).isEqualTo(TrustError.General.BB_REPORT_NOT_FINISHED));
    }

    @Test
    void parse_fails_onAnExitOutsideTheContract() {
        BbReportParser.parse(Captures.out("green"), 2)
                      .onSuccess(report -> {
                          throw new AssertionError("parsed " + report);
                      })
                      .onFailure(cause -> assertThat(cause).isInstanceOf(TrustError.UnknownBbReportExit.class));
    }

    @Test
    void parse_fails_withoutAnRcLine() {
        BbReportParser.parse(Captures.out("green").replace("rc=0 wall=5640s", "garbage"), 0)
                      .onSuccess(report -> {
                          throw new AssertionError("parsed " + report);
                      })
                      .onFailure(cause -> assertThat(members(cause)).hasOnlyElementsOfType(TrustError.MalformedBbReport.class).isNotEmpty());
    }

    @Test
    void parse_fails_whenTheEmptyLineAndTheExitDisagree() {
        BbReportParser.parse(Captures.out("empty"), 0)
                      .onSuccess(report -> {
                          throw new AssertionError("parsed " + report);
                      })
                      .onFailure(cause -> assertThat(members(cause)).hasOnlyElementsOfType(TrustError.MalformedBbReport.class).isNotEmpty());
        BbReportParser.parse(Captures.out("green"), 3)
                      .onSuccess(report -> {
                          throw new AssertionError("parsed " + report);
                      })
                      .onFailure(cause -> assertThat(members(cause)).hasOnlyElementsOfType(TrustError.MalformedBbReport.class).isNotEmpty());
    }

    /// Result.all gathers its failures into a composite; its members are the parser's own causes.
    private static List<Cause> members(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? composite.stream().toList()
               : List.of(cause);
    }
}
