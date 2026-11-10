package org.pragmatica.temper.acceptance.trust;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.api.ManifestView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.report.ReportParser;

import static org.assertj.core.api.Assertions.assertThat;

/// The T1 criterion (m1-design §8.1 step 4, §9.3) on the S1 XML: bb's side is the real bb-report output captured on
/// it, temper's side the same cases as the controller would answer them. Mutation probes (§9.3), each must turn a
/// test here red: compare `testsAttr` instead of `<testcase>` (177 vs 293 on this @Nested XML); ignore the failed
/// set; ignore drift; accept `missing`; count a SHA with a fork crash on either side as comparable (R-14).
class TrustComparisonTest {
    private static final String SHA = "0cf563192ad5e4bb66c2af16c49598fbf20976aa";
    private static final String CRASH_LOG = """
        [INFO] Running org.pragmatica.postgres.PipelineTest
        [ERROR] ExecutionException The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
        [INFO] BUILD FAILURE
        """;

    @Test
    void compare_passes_whenEveryCheckAgrees_onNestedXml() {
        var rows = Captures.s1Rows(false);
        var comparison = compare("green", rows, result("PASSED", rows), manifest(rows, List.of(), List.of()), "");

        assertThat(comparison.countsEqual()).as("293 <testcase> on both sides, although tests= sums to 177").isTrue();
        assertThat(comparison.failedEqual()).isTrue();
        assertThat(comparison.emptyEqual()).isTrue();
        assertThat(comparison.manifestAgrees()).isTrue();
        assertThat(comparison.caseLevelDiff()).isEmpty();
        assertThat(comparison.passed()).isTrue();
    }

    @Test
    void compare_fails_onOneTestcaseLess() {
        var rows = Captures.s1Rows(false);
        var fewer = rows.subList(1, rows.size());
        var comparison = compare("green", fewer, result("PASSED", fewer), manifest(rows, List.of(), List.of()), "");

        assertThat(comparison.countsEqual()).isFalse();
        assertThat(comparison.caseLevelDiff()).containsExactly("only bb: " + key(rows.getFirst()));
        assertThat(comparison.passed()).isFalse();
    }

    @Test
    void compare_passes_onARedRun_whoseFailedClassesAgree_aNestedErrorByItsOutermostClass() {
        var rows = Captures.s1Rows(true);
        var comparison = compare("red", rows, result("FAILED", rows), manifest(rows, List.of(), List.of()), "");

        assertThat(TrustComparison.failedClasses(Captures.temperRows(rows), Captures.s1Reports(true))).containsExactlyInAnyOrder("org.pragmatica.postgres.PipelineTest",
                                                                                                    "org.pragmatica.postgres.CrossTypeConversionTest");
        assertThat(comparison.failedEqual()).isTrue();
        assertThat(comparison.passed()).isTrue();
    }

    /// T1 2026-10-14: surefire writes a display-named class's cases under its `@DisplayName` as `classname`, into the
    /// outermost class's file, which is what bb-report's `FAILED:` names; temper's failed set names the same file
    /// (pragmatica `VerifyTest`'s `@Nested @DisplayName("Verify class tests")`, a `$` inside a display name, and a
    /// display-named top-level class). A failed row bb's XML lacks falls back to its classname up to the first `$`.
    @Test
    void failedClasses_nameTheReportFile_ofADisplayNamedClass() {
        var verify = report("TEST-org.pragmatica.lang.VerifyTest.xml", """
            <testsuite name="org.pragmatica.lang.VerifyTest">
              <testcase name="outer" classname="org.pragmatica.lang.VerifyTest" time="0.1"/>
              <testcase name="ensure" classname="Verify class tests" time="0.1"><failure message="x"/></testcase>
              <testcase name="odd" classname="a.b$c d" time="0.1"><error message="x"/></testcase>
            </testsuite>
            """);
        var top = report("TEST-org.x.TopTest.xml", """
            <testsuite name="org.x.TopTest">
              <testcase name="top" classname="Top level shown" time="0.1"><failure message="x"/></testcase>
            </testsuite>
            """);
        var rows = Captures.temperRows(Stream.of(verify, top).flatMap(report -> report.testcases().stream()).toList());
        var onlyTemper = List.of(new TestcaseDto("org.y.Gone$In", "t", "failed", 0.1, "gate", false));

        assertThat(TrustComparison.failedClasses(rows, List.of(verify, top))).containsExactlyInAnyOrder("org.pragmatica.lang.VerifyTest",
                                                                                                       "org.x.TopTest");
        assertThat(TrustComparison.failedClasses(onlyTemper, List.of(verify, top))).containsExactly("org.y.Gone");
        assertThat(TrustComparison.reportClass("TEST-org.pragmatica.lang.VerifyTest.xml")).isEqualTo("org.pragmatica.lang.VerifyTest");
    }

    /// T1 2026-10-14 run 2: surefire 3.5.2 (pragmatica `jbct`) writes one file per `@Nested` class, binary names only,
    /// and files the outer class's own cases under one of its `@Nested` classes' files: the XML below is surefire
    /// 3.5.2's for `RedOuterTest` (failing `outer`, failing `@Nested @DisplayName("Red inner") Inner`, passing `Last`),
    /// trimmed. bb-report's `FAILED:` names `r.RedOuterTest$Last` and `r.RedOuterTest$Inner`, and so does temper's
    /// failed set, through the file of bb's XML holding each case; neither `$` nor the classname would recover it.
    @Test
    void failedClasses_nameTheReportFile_underSurefire352_whereANestedClassFileHoldsTheOuterCase() {
        var outer = report("TEST-r.RedOuterTest.xml", """
            <testsuite name="r.RedOuterTest" tests="0" errors="0" skipped="0" failures="0"></testsuite>
            """);
        var inner = report("TEST-r.RedOuterTest$Inner.xml", """
            <testsuite name="r.RedOuterTest$Inner" tests="1" errors="0" skipped="0" failures="1">
              <testcase name="inner" classname="r.RedOuterTest$Inner" time="0.001"><failure message="inner"/></testcase>
            </testsuite>
            """);
        var last = report("TEST-r.RedOuterTest$Last.xml", """
            <testsuite name="r.RedOuterTest$Last" tests="2" errors="0" skipped="0" failures="1">
              <testcase name="outer" classname="r.RedOuterTest" time="0.018"><failure message="outer"/></testcase>
              <testcase name="last" classname="r.RedOuterTest$Last" time="0.001"/>
            </testsuite>
            """);
        var reports = List.of(outer, inner, last);
        var rows = Captures.temperRows(reports.stream().flatMap(report -> report.testcases().stream()).toList());

        assertThat(TrustComparison.failedClasses(rows, reports)).containsExactlyInAnyOrder("r.RedOuterTest$Last", "r.RedOuterTest$Inner");
    }

    /// Verification F-B (2026-10-14): two report files hold one `classname#name` (two outer classes whose `@Nested`
    /// classes share a `@DisplayName` and a method). temper's row carries no file, so one failure is named after the
    /// file bb failed it in, never after both; with no such match it is one explicit ambiguous case.
    @Test
    void failedClasses_nameOneFile_whenTwoReportFilesHoldTheCase() {
        var failedInA = List.of(report("TEST-o.ATest.xml", shared("o.ATest", true)), report("TEST-o.BTest.xml", shared("o.BTest", false)));
        var failedInBoth = List.of(report("TEST-o.ATest.xml", shared("o.ATest", true)), report("TEST-o.BTest.xml", shared("o.BTest", true)));
        var passedInBoth = List.of(report("TEST-o.ATest.xml", shared("o.ATest", false)), report("TEST-o.BTest.xml", shared("o.BTest", false)));
        var failed = new TestcaseDto("Shared", "works", "failed", 0.1, "gate", false);
        var passed = new TestcaseDto("Shared", "works", "passed", 0.1, "gate", false);

        assertThat(TrustComparison.failedClasses(List.of(failed, passed), failedInA)).containsExactly("o.ATest");
        assertThat(TrustComparison.failedClasses(List.of(failed, failed), failedInBoth)).containsExactlyInAnyOrder("o.ATest", "o.BTest");
        assertThat(TrustComparison.failedClasses(List.of(failed, passed), failedInBoth)).containsExactly("ambiguous: Shared#works in o.ATest, o.BTest");
        assertThat(TrustComparison.failedClasses(List.of(failed, passed), passedInBoth)).containsExactly("ambiguous: Shared#works in o.ATest, o.BTest");
    }

    private static String shared(String suite, boolean failed) {
        return "<testsuite name=\"" + suite + "\"><testcase name=\"works\" classname=\"Shared\" time=\"0.1\">"
               + (failed ? "<failure message=\"x\"/>" : "") + "</testcase></testsuite>";
    }

    private static ReportFile report(String name, String xml) {
        return ReportParser.parse(name, xml.getBytes(StandardCharsets.UTF_8)).expect("report");
    }

    @Test
    void compare_fails_whenTemperMissesBbsFailures() {
        var rows = Captures.s1Rows(false);
        var comparison = compare("red", rows, result("PASSED", rows), manifest(rows, List.of(), List.of()), "");

        assertThat(comparison.failedOnlyBb()).containsExactlyInAnyOrder("org.pragmatica.postgres.PipelineTest",
                                                                        "org.pragmatica.postgres.CrossTypeConversionTest");
        assertThat(comparison.failedOnlyTemper()).isEmpty();
        assertThat(comparison.passed()).isFalse();
        assertThat(comparison.caseLevelDiff()).hasSize(4)
                                              .anyMatch(line -> line.startsWith("only bb: org.pragmatica.postgres.PipelineTest#") && line.endsWith(" failed"))
                                              .anyMatch(line -> line.startsWith("only temper: org.pragmatica.postgres.PipelineTest#") && line.endsWith(" passed"));
    }

    @Test
    void compare_fails_whenOnlyTemperFailed() {
        var temperRows = Captures.s1Rows(true);
        var comparison = TrustComparison.compare(SHA,
                                                 Captures.report("green"),
                                                 Option.none(),
                                                 TemperSide.temperSide(result("FAILED", temperRows),
                                                                       Captures.temperRows(temperRows),
                                                                       manifest(temperRows, List.of(), List.of()),
                                                                       ""),
                                                 Captures.s1Reports(false));

        assertThat(comparison.failedOnlyTemper()).hasSize(2);
        assertThat(comparison.passed()).isFalse();
    }

    @Test
    void compare_emptyAgrees_onlyWhenBothAreEmpty() {
        var none = List.<ParsedTestcase> of();

        assertThat(compare("empty", none, result("EMPTY", none), manifest(none, List.of(), List.of()), "").emptyEqual()).isTrue();
        assertThat(compare("empty", none, result("PASSED", none), manifest(none, List.of(), List.of()), "").emptyEqual()).isFalse();

        var rows = Captures.s1Rows(false);

        assertThat(compare("green", rows, result("EMPTY", rows), manifest(rows, List.of(), List.of()), "").emptyEqual()).isFalse();
    }

    @Test
    void compare_fails_onDrift() {
        var rows = Captures.s1Rows(false);
        var comparison = compare("green", rows, result("PASSED", rows), manifest(rows, List.of(), List.of("org.pragmatica.postgres.PipelineTest")), "");

        assertThat(comparison.drift()).containsExactly("org.pragmatica.postgres.PipelineTest");
        assertThat(comparison.manifestAgrees()).isFalse();
        assertThat(comparison.passed()).isFalse();
    }

    @Test
    void compare_fails_onMissing_withoutAForkCrash() {
        var rows = Captures.s1Rows(false);
        var comparison = compare("green", rows, result("PASSED", rows), manifest(rows, List.of("org.x.GoneTest"), List.of()), "[INFO] BUILD SUCCESS\n");

        assertThat(comparison.crashEvidence().isEmpty()).isTrue();
        assertThat(comparison.passed()).isFalse();
    }

    /// R-14 (§8.1 step 4: "recorded and explained, never waved away"): a fork crash in temper's log tail makes the SHA
    /// NOT COMPARABLE, distinct from ✘: it never passes, and the crash lines and missing classes are kept for the
    /// explanation.
    @Test
    void compare_isNotComparable_withAForkCrashInTempersLog_andRecordsTheCrashLines() {
        var rows = Captures.s1Rows(false);
        var comparison = compare("green", rows, result("FAILED", rows), manifest(rows, List.of("org.x.CrashedTest"), List.of()), CRASH_LOG);

        assertThat(comparison.missing()).containsExactly("org.x.CrashedTest");
        assertThat(comparison.crashEvidence().or("")).contains("The forked VM terminated without properly saying goodbye");
        assertThat(comparison.comparable()).isFalse();
        assertThat(comparison.passed()).isFalse();
    }

    /// R-14 "either side": a crash only in bb's build log is as not comparable as one in temper's, even when every
    /// check agrees; with no crash anywhere the same SHA passes.
    @Test
    void compare_isNotComparable_withAForkCrashInBbsBuildLog_evenWhenEveryCheckAgrees() {
        var rows = Captures.s1Rows(false);
        var crashed = TrustComparison.compare(SHA, Captures.report("green"), TrustComparison.crashEvidence(CRASH_LOG),
                                              TemperSide.temperSide(result("PASSED", rows), Captures.temperRows(rows), manifest(rows, List.of(), List.of()), ""),
                                              Captures.s1Reports(false));

        assertThat(crashed.bbCrashEvidence().or("")).contains("The forked VM terminated");
        assertThat(crashed.comparable()).isFalse();
        assertThat(crashed.passed()).isFalse();
        assertThat(compare("green", rows, result("PASSED", rows), manifest(rows, List.of(), List.of()), "").comparable()).isTrue();
    }

    /// bb's side of R-14 is the snapshot's `.bb/build.log`: its fork-crash lines, none without a crash or without a log.
    @Test
    void snapshot_readsTheForkCrashLines_ofBbsBuildLog(@TempDir Path dir) throws Exception {
        var snapshot = Snapshot.snapshot(dir);
        var bb = Files.createDirectories(snapshot.checkout().bb());

        assertThat(snapshot.bbCrashEvidence().expect("snapshot").isEmpty()).as("no log").isTrue();
        Files.writeString(bb.resolve(Snapshot.BUILD_LOG), "[INFO] BUILD SUCCESS\n");
        assertThat(snapshot.bbCrashEvidence().expect("snapshot").isEmpty()).as("no crash").isTrue();
        Files.writeString(bb.resolve(Snapshot.BUILD_LOG), CRASH_LOG);
        assertThat(snapshot.bbCrashEvidence().expect("snapshot").or("")).isEqualTo("[ERROR] ExecutionException The forked VM terminated without properly saying goodbye. VM crash or System.exit called?");
    }

    @Test
    void compare_fails_whenTheManifestWasNotComputed() {
        var rows = Captures.s1Rows(false);
        var computed = manifest(rows, List.of(), List.of());
        var notComputed = new ManifestView(false, false, List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(compare("green", rows, result("PASSED", rows), computed, "").passed()).isTrue();
        assertThat(compare("green", rows, result("PASSED", rows), notComputed, "").passed()).isFalse();
    }

    private static ShaComparison compare(String capture, List<ParsedTestcase> temperRows, ResultRecordDto result, ManifestView manifest, String log) {
        return TrustComparison.compare(SHA,
                                       Captures.report(capture),
                                       Option.none(),
                                       TemperSide.temperSide(result, Captures.temperRows(temperRows), manifest, log),
                                       capture.equals("red")
                                       ? Captures.s1Reports(true)
                                       : capture.equals("empty")
                                         ? List.of()
                                         : Captures.s1Reports(false));
    }

    /// temper's record over `rows`: `counts.testcases` = rows, `counts.testsAttr` = 177 as on the S1 XML.
    private static ResultRecordDto result(String verdict, List<ParsedTestcase> rows) {
        return Captures.result(verdict, rows.size(), 177);
    }

    private static ManifestView manifest(List<ParsedTestcase> rows, List<String> missing, List<String> drift) {
        Set<String> classes = rows.stream().map(ParsedTestcase::className).collect(Collectors.toSet());

        return Captures.manifest(classes, missing, drift);
    }

    private static String key(ParsedTestcase row) {
        return row.className() + "#" + row.name() + " " + row.outcome().wire();
    }
}
