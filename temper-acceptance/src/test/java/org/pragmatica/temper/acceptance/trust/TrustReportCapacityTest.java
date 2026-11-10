package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// Option A in the overall verdict: a NOT COMPARABLE (capacity) SHA is left out of the count and has nothing to
/// explain, so it neither fails T1 by itself nor makes it INCOMPLETE; with no comparable SHA left, T1 fails.
class TrustReportCapacityTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 16);
    private static final String PLANTED = TrustReportCrashTest.PLANTED;

    @Test
    void overall_passes_withThreeComparablePassingShasIncludingThePlantedRed_besideACapacityNc() {
        var runs = List.of(TrustReportCrashTest.run("a1", List.of(), ""), TrustReportCrashTest.run("a2", List.of(), ""),
                           TrustReportPlantedRedTest.redOnly("r1", PLANTED), starved("c1", 41));

        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("PASS");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("**Overall: PASS** (4 SHAs, 3 passed")
                                                             .contains("1 not comparable (capacity, not counted)")
                                                             .doesNotContain("unwritten");
    }

    @Test
    void overall_fails_withOnlyCapacityNcs() {
        var runs = List.of(starved("c1", 41), starved("c2", 42), starved("c3", 43));

        assertThat(TrustReport.overall(PLANTED, runs)).isEqualTo("FAIL");
        assertThat(TrustReport.t1(DATE, PLANTED, runs, false)).contains("**Overall: FAIL**")
                                                             .contains("3 not comparable (capacity, not counted)");
    }

    private static ShaRun starved(String sha, long job) {
        return ShaRun.shaRun(sha, Path.of("/snap"), none(), job, TrustError.StillWaiting.FACTORY.apply(job, "QUEUED", 24L));
    }
}
