package org.pragmatica.temper.protocol.report;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportError;
import org.pragmatica.temper.domain.report.ReportFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.temper.protocol.report.ReportFixturesTest.report;

/// Spec §5.1 / m0-design §3.3 parser rules on fixture XML.
class ReportParserTest {
    private static <T> T ok(Result<T> result) {
        return result.fold(cause -> fail("expected success, got: " + cause.message()), value -> value);
    }

    private static <T> Cause failure(Result<T> result) {
        return result.fold(cause -> cause, value -> fail("expected failure, got: " + value));
    }

    private static ReportFile parse(String fixture) {
        return ok(ReportParser.parse(fixture, report(fixture)));
    }

    @Test
    void parse_readsEveryTestcase_andIgnoresElementLookalikesInText() {
        var file = parse("TEST-passing.xml");

        assertThat(file.testsAttr()).isEqualTo(2);
        assertThat(file.testcases()).containsExactly(ParsedTestcase.parsedTestcase("org.example.CalcTest", "adds", Outcome.PASSED, 0.004),
                                                     ParsedTestcase.parsedTestcase("org.example.CalcTest",
                                                                                   "subtracts",
                                                                                   Outcome.PASSED,
                                                                                   0.008));
    }

    @Test
    void parse_appliesOutcomeRules() {
        var outcomes = parse("TEST-mixed-outcomes.xml").testcases()
                                                       .stream()
                                                       .map(ParsedTestcase::outcome)
                                                       .toList();

        assertThat(outcomes).containsExactly(Outcome.PASSED,
                                             Outcome.FAILED,
                                             Outcome.ERROR,
                                             Outcome.SKIPPED,
                                             Outcome.PASSED,
                                             Outcome.FAILED,
                                             Outcome.ERROR,
                                             Outcome.ERROR,
                                             Outcome.PASSED);
    }

    @Test
    void parse_fallsBackToSuiteNameAndZeroTime_andDropsThousandsSeparators() {
        var testcases = parse("TEST-mixed-outcomes.xml").testcases();

        assertThat(testcases.getFirst()
                            .timeSeconds()).isEqualTo(1234.5);
        assertThat(testcases.getLast()).isEqualTo(ParsedTestcase.parsedTestcase("org.example.MixedTest",
                                                                                "noClassNoTime",
                                                                                Outcome.PASSED,
                                                                                0.0));
    }

    @Test
    void parse_sumsTestsAttrAcrossTestsuitesWrapper() {
        var file = parse("TEST-testsuites-wrapper.xml");

        assertThat(file.testsAttr()).isEqualTo(3);
        assertThat(file.testcases()).hasSize(3);
    }

    @Test
    void parse_keepsTestsAttrUndercount_forNestedClasses() {
        var file = parse("TEST-nested-undercount.xml");

        assertThat(file.testsAttr()).isEqualTo(3);
        assertThat(file.testcases()).hasSize(5);
    }

    @Test
    void parse_givesNoRows_forEmptySuite() {
        assertThat(parse("TEST-empty-suite.xml").testcases()).isEmpty();
    }

    @Test
    void parse_failsMalformed_forTruncatedXml() {
        assertThat(failure(ReportParser.parse("TEST-truncated.xml", report("TEST-truncated.xml")))).isInstanceOfSatisfying(ReportError.Malformed.class,
                                                                                                                         malformed -> assertThat(malformed.fileName()).isEqualTo("TEST-truncated.xml"));
    }

    @Test
    void parse_failsMalformed_forUnparseableTime() {
        assertThat(failure(ReportParser.parse("TEST-bad-time.xml", report("TEST-bad-time.xml")))).isInstanceOf(ReportError.Malformed.class);
    }

    @Test
    void parse_failsMalformed_forExternalEntity() {
        assertThat(failure(ReportParser.parse("TEST-xxe.xml", report("TEST-xxe.xml")))).isInstanceOf(ReportError.Malformed.class);
    }

    @Test
    void parse_failsMalformed_forDeclaredNonUtf8Encoding() {
        var latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><testsuite name=\"x\" tests=\"0\"/>".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(failure(ReportParser.parse("latin1-declared", latin1))).isInstanceOf(ReportError.Malformed.class);
    }

    @Test
    void parse_failsMalformed_forDecimalCommaTime() {
        var decimalComma = "<testsuite name=\"x\" tests=\"1\"><testcase classname=\"C\" name=\"n\" time=\"0,123\"/></testsuite>".getBytes(StandardCharsets.UTF_8);

        assertThat(failure(ReportParser.parse("comma", decimalComma))).isInstanceOf(ReportError.Malformed.class);
    }

    @Test
    void parse_failsMalformed_forNonXmlAndInvalidUtf8() {
        var invalidUtf8 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"ÿ\"/>".getBytes(StandardCharsets.ISO_8859_1);

        assertThat(failure(ReportParser.parse("binary", new byte[]{0, 1, 2, 3}))).isInstanceOf(ReportError.Malformed.class);
        assertThat(failure(ReportParser.parse("latin1", invalidUtf8))).isInstanceOf(ReportError.Malformed.class);
    }
}
