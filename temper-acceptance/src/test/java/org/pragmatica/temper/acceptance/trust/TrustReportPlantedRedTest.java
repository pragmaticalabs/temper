package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.report.ParsedTestcase;
import org.pragmatica.temper.domain.report.ReportFile;
import org.pragmatica.temper.protocol.api.TestcaseDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.acceptance.trust.TrustReportCrashTest.PLANTED;
import static org.pragmatica.temper.acceptance.trust.TrustReportCrashTest.red;
import static org.pragmatica.temper.acceptance.trust.TrustReportCrashTest.run;

/// maintainer ruling "T1 red" (m1-design §14): a comparable SHA is T1's red only when bb's `FAILED:` set contains the planted
/// class, the test the red SHA deliberately breaks. A red from another class on which temper and bb agree, instead of
/// the planted one or beside it, is NOT THE PLANTED RED: listed, its explanation asked for (INCOMPLETE while unwritten),
/// and not counted. A SHA on which they disagree always fails T1. rc ≠ 0 without a `FAILED:` class never counts.
class TrustReportPlantedRedTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);
    private static final String OTHER = "org.pragmatica.postgres.CrossTypeConversionTest";

    @Test
    void plantedRed_counts_andTheReportNamesThePlantedClass() {
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), redOnly("r1", PLANTED));

        assertThat(runs.getLast().plantedRed(PLANTED)).isTrue();
        assertThat(runs.getLast().otherRed(PLANTED)).isFalse();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("PASS");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("- planted red class: `" + PLANTED + "`")
                                                              .doesNotContain(TrustReport.NOT_PLANTED);
    }

    /// The same red SHA judged against a planted class it does not fail: not counted, listed, explanation asked for.
    @Test
    void redFromAnotherClass_isNotThePlantedRed_listedAndExplained_andNotCounted() {
        var planted = "org.x.PlantedTest";
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), red("r1"));
        var report = TrustReport.t1(DATE, planted, runs, false);

        assertThat(runs.getLast().passed()).as("the comparison itself still passes").isTrue();
        assertThat(runs.getLast().plantedRed(planted)).isFalse();
        assertThat(runs.getLast().otherRed(planted)).isTrue();
        assertThat(runs.getLast().counted(planted)).isFalse();
        assertThat(TrustReport.overall(planted, runs)).isEqualTo("FAIL");
        assertThat(report).contains("## r1 " + TrustReport.PASS + " " + TrustReport.NOT_PLANTED + " (not counted)")
                          .contains("3 SHAs, 3 passed, 0 not comparable (fork crash, not counted), 0 not comparable (capacity, not counted), 1 not the planted red (not counted)")
                          .contains("- **" + TrustReport.NOT_PLANTED + "**: bb's FAILED classes other than the planted `"
                                    + planted + "` (" + OTHER + ", " + PLANTED + "); this SHA is not T1's red and does not"
                                    + " count toward the 3 SHAs.")
                          .contains("- **explain** why each of these classes failed: " + TrustReport.UNWRITTEN)
                          .contains("- **1 red explanation(s) unwritten:**")
                          .contains("- **T1 needs a comparable red SHA**");
    }

    /// The planted red is there, and another SHA is red from another class only, so it is not counted: three other
    /// SHAs reach the count and the criterion holds, but that red's explanation is unwritten, so the report is
    /// INCOMPLETE, never PASS.
    @Test
    void overall_isIncomplete_whileAnotherClassRedIsUnexplained_besideThePlantedRed() {
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), redOnly("r1", PLANTED),
                           redOnly("r2", OTHER));
        var report = TrustReport.t1(DATE, PLANTED, runs, false);

        assertThat(runs.getLast().passed()).isTrue();
        assertThat(runs.getLast().otherRed(PLANTED)).isTrue();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("INCOMPLETE");
        assertThat(report).contains("**Overall: INCOMPLETE**")
                          .contains("bb's FAILED classes other than the planted `" + PLANTED + "` (" + OTHER + ")")
                          .doesNotContain("T1 needs a comparable red SHA");
        assertThat(report.split(Pattern.quote(TrustReport.UNWRITTEN), -1)).as("the placeholder only in the one explanation").hasSize(2);
    }

    /// A SHA red from another class only is "explained, and not counted" (maintainer ruling): two green SHAs, the planted red
    /// and such a red are only three SHAs judged, one short of the count, so T1 FAILs.
    @Test
    void redFromAnotherClassOnly_isLeftOutOfTheCount() {
        var runs = List.of(run("a1", List.of(), ""), redOnly("r1", PLANTED), redOnly("r2", OTHER));

        assertThat(runs.getLast().passed()).as("the comparison itself passes").isTrue();
        assertThat(runs.getLast().notPlantedRed(PLANTED)).isTrue();
        assertThat(runs.getLast().counted(PLANTED)).isFalse();
        assertThat(runs.get(1).counted(PLANTED)).isTrue();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("**Overall: FAIL**")
                                                              .contains("## r2 " + TrustReport.PASS + " " + TrustReport.NOT_PLANTED + " (not counted)");
    }

    /// The planted class failed, and so did another: the planted red counts, but the other class is a red from another
    /// class, so its explanation is asked for and the report is INCOMPLETE until it is written.
    @Test
    void plantedRedBesideAnotherClass_counts_butTheOtherClassIsExplained() {
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), red("r1"));
        var report = TrustReport.t1(DATE, PLANTED, runs, false);

        assertThat(runs.getLast().plantedRed(PLANTED)).isTrue();
        assertThat(runs.getLast().otherRed(PLANTED)).isTrue();
        assertThat(runs.getLast().notPlantedRed(PLANTED)).isFalse();
        assertThat(runs.getLast().counted(PLANTED)).isTrue();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("INCOMPLETE");
        assertThat(report).contains("## r1 " + TrustReport.PASS)
                          .contains("bb's FAILED classes other than the planted `" + PLANTED + "` (" + OTHER
                                    + "); the planted red still counts")
                          .doesNotContain("T1 needs a comparable red SHA");
    }

    /// Main-session reading of the ruling, "T1 disagreement always fails" (m1-design §14): bb red from another class
    /// only, temper reporting every row passed, is a missed red. It is not "not the planted red" (that needs both sides
    /// to agree): it is ✘, counted, and T1 FAILs although three green SHAs and the planted red reach the count.
    @Test
    void missedRedFromAnotherClass_temperGreen_isCounted_andFailsT1() {
        var missed = redFrom("x1", Set.of(OTHER), Set.of());
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), run("a3", List.of(), ""),
                           redOnly("r1", PLANTED), missed);
        var report = TrustReport.t1(DATE, PLANTED, runs, false);

        assertThat(missed.crashed()).as("comparable").isFalse();
        assertThat(missed.passed()).as("temper disagrees with bb").isFalse();
        assertThat(missed.notPlantedRed(PLANTED)).isFalse();
        assertThat(missed.otherRed(PLANTED)).isFalse();
        assertThat(missed.counted(PLANTED)).isTrue();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(report).contains("**Overall: FAIL**")
                          .contains("## x1 " + TrustReport.FAIL + "\n")
                          .contains("- failed only in bb: " + OTHER)
                          .contains("0 not the planted red (not counted)")
                          .doesNotContain(TrustReport.NOT_PLANTED + "**")
                          .doesNotContain("red explanation(s) unwritten");
    }

    /// The planted class failed on both sides, but temper missed the other class bb failed: the sides disagree, so the
    /// SHA is ✘ and T1 FAILs; the planted class does not excuse the missed one.
    @Test
    void plantedRedBesideAMissedOtherClass_failsT1() {
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), redFrom("r1", Set.of(PLANTED, OTHER), Set.of(PLANTED)));

        assertThat(runs.getLast().passed()).isFalse();
        assertThat(runs.getLast().plantedRed(PLANTED)).isTrue();
        assertThat(runs.getLast().otherRed(PLANTED)).isFalse();
        assertThat(runs.getLast().counted(PLANTED)).isTrue();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("## r1 " + TrustReport.FAIL + "\n");
    }

    /// rc ≠ 0 alone (a compile error say): no `FAILED:` class, so neither the planted red nor another red.
    @Test
    void rcNonZeroWithoutAFailedClass_neverCounts() {
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), failedBuild("f1"));

        assertThat(runs.getLast().judged().map(judged -> judged.bb().rc()).or(0)).isNotZero();
        assertThat(runs.getLast().plantedRed(PLANTED)).isFalse();
        assertThat(runs.getLast().otherRed(PLANTED)).isFalse();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("- **T1 needs a comparable red SHA**");
    }

    /// bb red from `failed` only, temper agreeing: the captured red SHA with every other doctored class passing.
    static ShaRun redOnly(String sha, String failed) {
        return redFrom(sha, Set.of(failed), Set.of(failed));
    }

    /// bb red from `bbFailed`, temper reporting only the classes in `temperFailed` failed: the captured red SHA, its
    /// failed set doctored on each side.
    private static ShaRun redFrom(String sha, Set<String> bbFailed, Set<String> temperFailed) {
        var rows = Captures.s1Rows(true);
        var bb = Captures.report("red");
        var doctored = BbReport.bbReport(bb.rc(), bb.testcases(), bb.failures(), bb.errors(), bb.skipped(), bb.testsAttr(),
                                         bbFailed, bb.empty(), bb.dependencyResolutionWarning(), bb.argsWords());
        var temperRows = Captures.temperRows(rows).stream().map(row -> passedUnless(row, temperFailed)).toList();

        return judged(sha, doctored, Captures.s1Reports(true), temperRows, "FAILED");
    }

    /// bb rc 1 with no failed class, temper green.
    private static ShaRun failedBuild(String sha) {
        var rows = Captures.s1Rows(false);
        var bb = Captures.report("green");
        var broken = BbReport.bbReport(1, bb.testcases(), bb.failures(), bb.errors(), bb.skipped(), bb.testsAttr(),
                                       bb.failedClasses(), bb.empty(), bb.dependencyResolutionWarning(), bb.argsWords());

        return judged(sha, broken, Captures.s1Reports(false), Captures.temperRows(rows), "PASSED");
    }

    private static TestcaseDto passedUnless(TestcaseDto row, Set<String> failed) {
        return failed.stream().anyMatch(name -> row.className().equals(name) || row.className().startsWith(name + "$"))
               ? row
               : new TestcaseDto(row.className(), row.name(), "passed", row.timeSeconds(), row.unit(), row.rerun());
    }

    private static ShaRun judged(String sha, BbReport bb, List<ReportFile> reports, List<TestcaseDto> temperRows, String verdict) {
        var rows = reports.stream().flatMap(report -> report.testcases().stream()).toList();
        Set<String> classes = rows.stream().map(ParsedTestcase::className).collect(Collectors.toSet());
        var temper = TemperSide.temperSide(Captures.result(verdict, rows.size(), 177), temperRows,
                                           Captures.manifest(classes, List.of(), List.of()), "");
        var comparison = TrustComparison.compare(sha, bb, Option.none(), temper, reports);

        return new ShaRun(sha, Path.of("/snap"), Option.none(), Option.some(5L),
                          Option.some(ShaRun.Judged.judged(bb, temper, comparison, "{}", "{}")), Option.none(), Option.none());
    }
}
