package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.manifest.Runner;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.store.api.NewJob;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `ResultStore.classDurations` (maintainer ruling on the rerun bound) over real rows: per class, the largest per-job Σ
/// `time_s` of the class and its nested classes over the last 5 terminal jobs that are not reruns (maintainer ruling (b)). Every
/// test uses its own class names, so the jobs other tests store are not in its history.
@Tag("pg")
class ClassDurationsIT {
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

    private static JobId stored(TestcaseRow... rows) {
        return harness.run(TestJobs.gateBySha(TestJobs.sha()), TestJobs.exited(0), List.of(rows));
    }

    /// A finished rerun of `parent` that stored `seconds` of `className`.
    private static JobId storedRerun(JobId parent, String className, double seconds) {
        var sha = TestJobs.sha();
        var recipe = Recipe.rerun(parent,
                                  List.of(Recipe.Rerun.Target.target(className, "mod", Runner.SUREFIRE).unwrap()),
                                  Recipe.Rerun.Original.original(parent,
                                                                 "FAILED",
                                                                 some("tests"),
                                                                 List.of(Recipe.Rerun.Failure.failure(className, "case", none()))));
        var rerun = NewJob.newJob(OwnerTag.ownerTag("v2174").unwrap(),
                                  Priority.priority(none()).unwrap(),
                                  false,
                                  false,
                                  SourceRequest.inherited(parent, some(sha)),
                                  some(PushedSource.pushedSource(none(), sha)),
                                  recipe,
                                  some(TestJobs.key()),
                                  List.of(TestJobs.unit("main")));

        return harness.run(rerun, TestJobs.exited(0), List.of(row(className, seconds)));
    }

    private static Map<String, Duration> durations(String... classNames) {
        return harness.store.results().classDurations(List.of(classNames), 5).await(PgFixture.WAIT).unwrap();
    }

    /// The class's own rows and its nested classes' rows add up per job.
    @Test
    void classDuration_sumsTheClassAndItsNestedClasses() {
        var target = className("FooTest");

        stored(row(target, 10), row(target, 5), row(target + "$Inner", 2.5));

        assertThat(durations(target)).isEqualTo(Map.of(target, Duration.ofMillis(17_500)));
    }

    /// The slowest of the last 5 jobs with rows of the class; a slower, older one is out of the window.
    @Test
    void classDuration_isTheMaxOverTheLastFiveJobs() {
        var target = className("FooTest");

        stored(row(target, 900));
        IntStream.of(30, 60, 45, 20, 10).forEach(seconds -> stored(row(target, seconds)));

        assertThat(durations(target)).isEqualTo(Map.of(target, Duration.ofSeconds(60)));
    }

    /// The window counts jobs that stored rows of the class, not every job: other jobs in between do not push it out.
    @Test
    void classDuration_windowCountsOnlyJobsWithTheClass() {
        var target = className("FooTest");
        var other = className("OtherTest");

        stored(row(target, 70));
        IntStream.range(0, 6).forEach(_ -> stored(row(other, 1)));

        assertThat(durations(target, other)).isEqualTo(Map.of(target, Duration.ofSeconds(70), other, Duration.ofSeconds(1)));
    }

    /// maintainer ruling (b): a rerun job's rows are not history, so five newer fast reruns of the class leave the slow Gate run
    /// in the window. Probe: drop `j.rerun_of IS NULL` → the reruns fill the window and the class reads 1 min: red.
    @Test
    void classDuration_ignoresRerunJobs_whichNeverPushGateRunsOut() {
        var target = className("FooTest");
        var gate = stored(row(target, 600));

        IntStream.range(0, 6).forEach(_ -> storedRerun(gate, target, 60));

        assertThat(harness.count("SELECT count(*) AS n FROM jobs j JOIN results r ON r.job_id = j.id WHERE j.rerun_of = $1",
                                 gate.value())).as("six terminal reruns of the gate").isEqualTo(6);
        assertThat(durations(target)).isEqualTo(Map.of(target, Duration.ofSeconds(600)));
    }

    /// Rows of a job with no result (not terminal, here RUNNING) are not history.
    @Test
    void classDuration_ignoresJobsWithoutAResult() {
        var target = className("FooTest");

        stored(row(target, 40));
        var running = harness.submit(TestJobs.gateBySha(TestJobs.sha()));

        harness.grantHead();
        harness.start(running);
        harness.execute("INSERT INTO testcases (job_id, unit_id, class, name, outcome, time_s) "
                        + "SELECT job_id, id, $2, 'case', 'passed', 500 FROM units WHERE job_id = $1",
                        running.value(),
                        target);

        assertThat(harness.count("SELECT count(*) AS n FROM results WHERE job_id = $1", running.value())).isZero();
        assertThat(durations(target)).isEqualTo(Map.of(target, Duration.ofSeconds(40)));
        harness.complete(running, TestJobs.exited(0), TestJobs.passing());
    }

    /// `_` and `%` in a name are literal: `A_B` does not take the rows of `AxB$Inner`, nor `A` those of `AB`.
    @Test
    void classDuration_matchesNamesLiterally() {
        var prefix = "p" + TestJobs.randomHex(8) + ".";

        stored(row(prefix + "A_B", 3), row(prefix + "AxB$Inner", 300), row(prefix + "AB", 30));

        assertThat(durations(prefix + "A_B", prefix + "A")).isEqualTo(Map.of(prefix + "A_B", Duration.ofSeconds(3)));
    }

    @Test
    void classDuration_ofAnUnknownClass_isAbsent() {
        assertThat(durations(className("NeverRanTest"))).isEmpty();
        assertThat(durations()).isEmpty();
    }
}
