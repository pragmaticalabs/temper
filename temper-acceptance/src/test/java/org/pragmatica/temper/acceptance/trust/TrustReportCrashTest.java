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

import static org.assertj.core.api.Assertions.assertThat;

/// Ruling R-14 in the T1 report (adapted from the M1 WP7 verifier's `VerifierTrustReportCrashTest`): a SHA with a fork
/// crash is NOT COMPARABLE, distinct from ✔ and ✘, lists its crash lines and missing classes with a prompt for their
/// explanation, and does not count toward the three SHAs T1 needs. With one, the report is INCOMPLETE while its
/// explanation is unwritten, never PASS (F3); and T1 needs a comparable red SHA, judged (F7).
class TrustReportCrashTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);
    /// One of the two classes the captured red SHA fails.
    static final String PLANTED = "org.pragmatica.postgres.PipelineTest";
    private static final String CRASH_LOG = """
        [INFO] Running org.pragmatica.postgres.PipelineTest
        [ERROR] ExecutionException The forked VM terminated without properly saying goodbye. VM crash or System.exit called?
        [INFO] BUILD FAILURE
        """;

    @Test
    void crashedSha_isNotComparable_listsTheCrashAndTheMissingClass_andAsksForTheExplanation() {
        var crashed = run("c0ffee", List.of("org.x.CrashedTest"), CRASH_LOG);
        var report = TrustReport.t1(DATE, PLANTED, List.of(crashed, run("a1", List.of(), ""), run("a2", List.of(), "")), true);

        assertThat(crashed.crashed()).isTrue();
        assertThat(crashed.passed()).isFalse();
        assertThat(report).contains("## c0ffee " + TrustReport.NOT_COMPARABLE)
                          .doesNotContain("## c0ffee " + TrustReport.FAIL)
                          .contains("- missing: org.x.CrashedTest")
                          .contains("**NOT COMPARABLE (fork crash)** (R-14): a fork crash in temper's log tail")
                          .contains("- **explain** the crash below and each missing class (org.x.CrashedTest)")
                          .contains("fork crash in temper's log tail:")
                          .contains("The forked VM terminated without properly saying goodbye")
                          .contains("(3 SHAs, 2 passed, 1 not comparable (fork crash, not counted), 0 not comparable (capacity, not counted), 0 not the planted red (not counted); T1 needs >= 3 comparable SHAs");
    }

    /// Three SHAs, one crashed: only two are comparable, so T1 fails although no comparable SHA failed.
    @Test
    void overall_fails_withFewerThanThreeComparableShas_whenOneIsCrashed() {
        var runs = List.of(run("c0ffee", List.of("org.x.CrashedTest"), CRASH_LOG), run("a1", List.of(), ""), run("a2", List.of(), ""));

        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, true)).contains("**Overall: FAIL**");
    }

    /// The crashed SHA is not a permanent T1 failure: with three other comparable SHAs that pass, one of them red, the
    /// criterion holds. But its explanation is unwritten, so the report is INCOMPLETE, never PASS, and the harness
    /// exits non-zero (F3).
    @Test
    void overall_isIncomplete_whileACrashExplanationIsUnwritten_besidesThreeComparablePassingShas() {
        var runs = List.of(run("c0ffee", List.of("org.x.CrashedTest"), CRASH_LOG), run("a1", List.of(), ""), run("a2", List.of(), ""),
                           TrustReportPlantedRedTest.redOnly("r1", PLANTED));
        var report = TrustReport.t1(DATE, PLANTED, runs, false);

        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("INCOMPLETE");
        assertThat(report).contains("**Overall: INCOMPLETE** (4 SHAs, 3 passed, 1 not comparable")
                          .contains("- **1 crash explanation(s) unwritten:** T1 is never PASS")
                          .doesNotContain("Overall: PASS")
                          .doesNotContain("T1 needs a comparable red SHA");
        assertThat(report.split(Pattern.quote(TrustReport.UNWRITTEN), -1)).as("the placeholder only in the one explanation").hasSize(2);
        assertThat(T1Harness.exitFor("INCOMPLETE")).isEqualTo(T1Harness.EXIT_INCOMPLETE).isNotZero();
    }

    /// Three comparable SHAs that pass, one of them red from the planted class only, nothing crashed: PASS, exit 0.
    @Test
    void overall_passes_withThreeComparablePassingShas_oneOfThemRed_andNoCrash() {
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), TrustReportPlantedRedTest.redOnly("r1", PLANTED));

        assertThat(runs.getLast().plantedRed(PLANTED)).isTrue();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("PASS");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("**Overall: PASS**").doesNotContain("unwritten");
        assertThat(T1Harness.exitFor("PASS")).isEqualTo(T1Harness.EXIT_PASS);
    }

    /// F7: every comparable SHA green fails T1 ("including ≥ 1 red"), and the report says what is needed.
    @Test
    void overall_fails_whenNoComparableShaIsRed() {
        var runs = List.of(run("a1", List.of(), ""), run("a2", List.of(), ""), run("a3", List.of(), ""));

        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("**Overall: FAIL**").contains("- **T1 needs a comparable red SHA**");
    }

    /// F7: the only red SHA crashed, so it is not comparable and does not count as T1's red SHA.
    @Test
    void overall_fails_whenTheOnlyRedShaIsNotComparable() {
        var crashedRed = crashedRed("c0ffee");
        var runs = List.of(crashedRed, run("a1", List.of(), ""), run("a2", List.of(), ""), run("a3", List.of(), ""));

        assertThat(crashedRed.crashed()).isTrue();
        assertThat(crashedRed.judged().map(judged -> judged.bb().failedClasses()).or(Set.of())).isNotEmpty();
        assertThat(crashedRed.plantedRed(PLANTED)).isFalse();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("**Overall: FAIL**").contains("- **T1 needs a comparable red SHA**");
    }

    /// A comparable SHA that disagrees is still ✘ and still fails T1.
    @Test
    void overall_fails_whenAComparableShaDisagrees() {
        var runs = List.of(run("b0", List.of("org.x.GoneTest"), ""), run("a1", List.of(), ""), red("r1"));

        assertThat(runs.getFirst().crashed()).isFalse();
        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, true)).contains("## b0 " + TrustReport.FAIL);
    }

    static ShaRun run(String sha, List<String> missing, String log) {
        return run(sha, missing, log, false);
    }

    /// bb red (two failed classes) and temper agreeing: comparable, passed, red.
    static ShaRun red(String sha) {
        return run(sha, List.of(), "", true);
    }

    private static ShaRun crashedRed(String sha) {
        return run(sha, List.of("org.x.CrashedTest"), CRASH_LOG, true);
    }

    private static ShaRun run(String sha, List<String> missing, String log, boolean red) {
        var reports = Captures.s1Reports(red);
        var rows = Captures.s1Rows(red);
        Set<String> classes = rows.stream().map(ParsedTestcase::className).collect(Collectors.toSet());
        var temper = TemperSide.temperSide(Captures.result(missing.isEmpty() && !red ? "PASSED" : "FAILED", rows.size(), 177), Captures.temperRows(rows),
                                           Captures.manifest(classes, missing, List.of()), log);
        var bb = Captures.report(red ? "red" : "green");
        var comparison = TrustComparison.compare(sha, bb, Option.none(), temper, reports);

        return new ShaRun(sha, Path.of("/snap"), Option.none(), Option.some(5L),
                          Option.some(ShaRun.Judged.judged(bb, temper, comparison, "{}", "{}")), Option.none(), Option.none());
    }
}
