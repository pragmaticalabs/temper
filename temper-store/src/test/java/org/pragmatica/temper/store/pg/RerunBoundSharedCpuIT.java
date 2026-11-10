package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;

import static org.assertj.core.api.Assertions.assertThat;


/// R-M4-15 (m4-design §9.4, probe P18): the rerun bound's history excludes rows a shared-CPU host produced. The filter is
/// the unit's per-attempt snapshot `units.shared_cpu`, written by the real `GRANT` from `hosts.shared_cpu`: a "cloud" job
/// here is one granted while the host is marked shared. Every test uses its own class names.
///
/// Probe P18: drop `u.shared_cpu IS NOT TRUE` from `PgResultStore.CLASS_DURATION` → C reads 40 min, D reads its cloud
/// time, and the cloud jobs fill the window (red).
@Tag("pg")
class RerunBoundSharedCpuIT {
    private static StoreHarness harness;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    private static String className(String simple) {
        return "p" + TestJobs.randomHex(8) + "." + simple;
    }

    private static TestcaseRow row(String className, double seconds) {
        return TestcaseRow.testcaseRow(UnitName.MAIN, className, "case" + TestJobs.randomHex(4), Outcome.PASSED, seconds, false);
    }

    /// A terminal Gate run on a dedicated host.
    private static JobId dedicated(String className, double seconds) {
        return harness.run(TestJobs.gateBySha(TestJobs.sha()), TestJobs.exited(0), List.of(row(className, seconds)));
    }

    /// A terminal Gate run granted while the host is shared, so its unit's snapshot says `shared_cpu`.
    private static JobId cloud(String className, double seconds) {
        harness.execute("UPDATE hosts SET shared_cpu = true WHERE name = $1", TestJobs.HOST.value());
        try {
            return dedicated(className, seconds);
        } finally {
            harness.execute("UPDATE hosts SET shared_cpu = false WHERE name = $1", TestJobs.HOST.value());
        }
    }

    private static Map<String, Duration> durations(int window, String... classNames) {
        return harness.store.results().classDurations(List.of(classNames), window).await(PgFixture.WAIT).unwrap();
    }

    /// C: dedicated runs at 10 min, a cloud run at 40 min → 10 min.
    @Test
    void classDuration_excludesSharedCpuRows() {
        var c = className("CTest");

        dedicated(c, 600);
        var slowCloud = cloud(c, 2400);

        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND shared_cpu", slowCloud.value()))
            .as("the cloud run's unit snapshot").isEqualTo(1);
        assertThat(durations(5, c)).isEqualTo(Map.of(c, Duration.ofMinutes(10)));
    }

    /// D: cloud history only → no history (the existing no-history path, NULL).
    @Test
    void classDuration_onlySharedCpuHistory_isNoHistory() {
        var d = className("DTest");

        cloud(d, 300);

        assertThat(durations(5, d)).isEmpty();
    }

    /// With a window of 2 and three newer cloud runs, the two dedicated runs still form the window.
    @Test
    void classDuration_windowCountsOnlyDedicatedJobs() {
        var c = className("CTest");

        dedicated(c, 600);
        dedicated(c, 300);
        IntStream.range(0, 3).forEach(_ -> cloud(c, 10));

        assertThat(durations(2, c)).isEqualTo(Map.of(c, Duration.ofMinutes(10)));
    }
}
