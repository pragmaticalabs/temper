package org.pragmatica.temper.acceptance.rerun;

import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.temper.acceptance.stack.AcceptanceStack;
import org.pragmatica.temper.acceptance.stack.DockerAvailable;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.store.api.JobRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// m2-design §5.6 `RerunIT`, docker tier, through the real controller, the real worker and the CLI's shaded jar: the
/// fixture's `rerun` overlay adds to `includes` a class A red on every run (`RerunRedTest`) and a class B red only on its
/// first run (`RerunFlakyTest`, which passes once `-Dtest` selects it). Its Gate is `FAILED(tests)` with both; `temper
/// rerun` of it runs both in a fresh checkout of the same SHA, in the rerun job's one unit:
///
/// - `rerun(A)=FAILED`, `rerun(B)=PASSED`; the rerun record's `original` is the parent's FAILED(tests) with both classes;
/// - the rerun's rows are exactly A's and B's (a rerun selects its classes, never the whole module);
/// - the parent's stored record bytes, its `finished_at` and `temper gate` (FAILED, with the parent) are unchanged; the
///   gate line lists the rerun as information only.
///
/// Ruling R-M2-16's one unit is run for real four more times: `rerun-wide` (two surefire classes in `includes`, one
/// still red, and a failsafe class in `failsafe`), `rerun-it` (a failsafe class alone, with no surefire class run), and
/// two ways a rerun breaks: `rerun-crash` (a target's fork dies) and `rerun-nocompile` (the rerun's build does not
/// compile).
///
/// **Mutation probe** (design): run the rerun without `-Dtest` (the whole module): `Planner` drops the selection →
/// [#rerunRows_areExactlyTheTwoClasses] goes red (every class of `includes` reports), and so does B's PASSED.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RerunIT {
    private static final String RED = "fx.includes.RerunRedTest";
    private static final String FLAKY = "fx.includes.RerunFlakyTest";
    private static final String FLAKY_IT = "fx.failsafe.RerunFlakyIT";
    private static final String EARLY = "fx.includes.RerunEarlyTest";
    private static final String CRASH = "fx.includes.RerunZCrashTest";
    private static final String USES_HELPER = "fx.includes.RerunUsesHelperTest";
    private AcceptanceStack stack;
    private long parent;
    private String parentRecordBefore;
    private JobRow parentRowBefore;
    private String rerunLine;
    private long rerun;
    private String waitLine;

    @BeforeAll
    void rerunTheGate() throws Exception {
        stack = AcceptanceStack.docker();
        parent = stack.gate("rerun");
        parentRecordBefore = recordJson(parent);
        parentRowBefore = row(parent);

        var submitted = stack.temper("rerun", String.valueOf(parent));

        assertThat(submitted.rc()).as(submitted.stdoutText() + submitted.stderrText()).isZero();
        rerunLine = submitted.stdoutText().strip();
        rerun = Long.parseLong(rerunLine.split(" ")[1]);
        waitLine = stack.awaited(rerun, Duration.ofMinutes(30));
    }

    @Test
    void parentGate_isFailedTests_withBothClasses() {
        var record = stack.api.result(parent).await().expect("parent record");

        assertThat(record.verdict()).as(record.toString()).isEqualTo("FAILED");
        assertThat(record.reason().or("")).isEqualTo("tests");
        assertThat(classesOf(record)).containsExactly(FLAKY, RED);
    }

    @Test
    void rerun_redStaysFailed_flakyPasses_onTheRerunsOwnRecord() {
        var record = stack.api.result(rerun).await().expect("rerun record");

        assertThat(rerunLine).as(rerunLine).startsWith("rerun " + rerun + " QUEUED of=" + parent + " classes=" + FLAKY + "," + RED);
        assertThat(record.annotations()).as(record.annotations().toString())
                                        .containsExactly("rerun-of=" + parent,
                                                         floorBound(record),
                                                         "rerun(" + FLAKY + ")=PASSED co-run in one fork with: " + RED,
                                                         "rerun(" + RED + ")=FAILED co-run in one fork with: " + FLAKY);
        assertThat(record.verdict()).as("the red class still fails the rerun job").isEqualTo("FAILED");
        assertThat(waitLine).contains("rerun(" + RED + ")=FAILED", "rerun(" + FLAKY + ")=PASSED");
        assertThat(record.recipe()).isInstanceOf(RecipeDto.Rerun.class);

        var original = ((RecipeDto.Rerun) record.recipe()).original().fold(() -> fail("expected a value"), present -> present);

        assertThat(original.job()).isEqualTo(parent);
        assertThat(original.verdict()).isEqualTo("FAILED");
        assertThat(original.reason().or("")).isEqualTo("tests");
        assertThat(sorted(original.failed().stream().map(ResultRecordDto.FailedDto::className)))
            .containsExactly(FLAKY, RED);
    }

    /// The rows-per-class assertion of the design's probe: the rerun ran exactly its two classes, in its one unit.
    @Test
    void rerunRows_areExactlyTheTwoClasses() {
        var rows = stack.api.testcases(rerun).await().expect("rerun rows");

        assertThat(rows).as("the rerun's rows").isNotEmpty();
        assertThat(sorted(rows.stream().map(TestcaseDto::className))).containsExactly(FLAKY, RED);
        assertThat(sorted(rows.stream().map(TestcaseDto::unit))).containsExactly("rerun");
    }

    @Test
    void parent_recordFinishedTimeAndGateAnswer_unchanged() {
        assertThat(recordJson(parent)).as("the stored record's bytes").isEqualTo(parentRecordBefore);
        assertThat(row(parent).finishedAt()).isEqualTo(parentRowBefore.finishedAt());

        var gate = stack.temper("gate", stack.fixture.sha("rerun"));
        var line = gate.stdoutText().strip();

        assertThat(gate.rc()).as(line).isEqualTo(1);
        assertThat(line).as(line).contains(" FAILED job=" + parent + " ", " rerun=" + rerun + ":FAILED");
    }

    /// Ruling R-M2-16: two modules and both plugins in one unit, each class judged on its own rows (never "No tests
    /// matching" from the module that lacks it). The red `includes` class stays FAILED and stops nothing: the rerun step
    /// ignores test failures, so the failsafe class of the later module still runs and passes (without that flag the
    /// build stops after `includes` and it reads NOT-RUN(rc)). The parent reached the later module because the overlay's
    /// profile ignores `includes`' failures in a run without `-Dtest`, which a rerun is not.
    @Test
    void twoModulesBothPlugins_aStillRedClassStopsNoOther() {
        var wide = rerunOf("rerun-wide");
        var record = stack.api.result(wide.rerun()).await().expect("wide rerun record");

        assertThat(classesOf(stack.api.result(wide.parent()).await().expect("wide parent"))).containsExactly(FLAKY_IT, FLAKY, RED);
        assertThat(record.verdict()).as(record.toString()).isEqualTo("FAILED");
        assertThat(record.annotations()).containsExactly("rerun-of=" + wide.parent(),
                                                         floorBound(record),
                                                         "rerun(" + FLAKY_IT + ")=PASSED",
                                                         "rerun(" + FLAKY + ")=PASSED co-run in one fork with: " + RED,
                                                         "rerun(" + RED + ")=FAILED co-run in one fork with: " + FLAKY);
        assertThat(rowClasses(wide.rerun())).containsExactly(FLAKY_IT, FLAKY, RED);
    }

    /// Ruling R-M2-16: a failsafe class alone runs no surefire class (`-Dtest=!*`), so the module's unit tests
    /// (`fx.failsafe.PlainTest`) report nothing: neither plugin runs its whole suite.
    @Test
    void failsafeOnly_runsNoSurefireClass() {
        var itOnly = rerunOf("rerun-it");
        var record = stack.api.result(itOnly.rerun()).await().expect("it rerun record");

        assertThat(record.verdict()).as(record.toString()).isEqualTo("PASSED");
        assertThat(record.annotations()).containsExactly("rerun-of=" + itOnly.parent(), floorBound(record), "rerun(" + FLAKY_IT + ")=PASSED");
        assertThat(rowClasses(itOnly.rerun())).containsExactly(FLAKY_IT);
        assertThat(rowClasses(itOnly.parent())).as("control: the parent ran the module's unit tests").contains("fx.failsafe.PlainTest");
    }

    /// A target's fork dies in the rerun (`Runtime.halt`): it leaves no report, so it is NOT-RUN, never PASSED. Observed:
    /// surefire fails the build on a dead fork even with `-Dmaven.test.failure.ignore=true`, so the step's rc is 1 and the
    /// class is NOT-RUN(rc); the rerun job is FAILED(missing-classes). The other target of that fork ran first
    /// (alphabetical order) and its report was written before the crash, so it is judged on its rows: PASSED.
    @Test
    void forkCrash_theCrashedTargetIsNotRun_neverPassed() {
        var crash = rerunOf("rerun-crash");
        var record = stack.api.result(crash.rerun()).await().expect("crash rerun record");
        var annotations = record.annotations();

        assertThat(classesOf(stack.api.result(crash.parent()).await().expect("crash parent"))).containsExactly(EARLY, CRASH);
        assertThat(annotations).as(record.toString()).contains("rerun(" + EARLY + ")=PASSED co-run in one fork with: " + CRASH);
        assertThat(annotations).as(record.toString()).contains("rerun(" + CRASH + ")=NOT-RUN(rc)");
        assertThat(record.verdict() + "(" + record.reason().or("") + ")").as("the dead fork's class is missing")
                                                                   .isEqualTo("FAILED(missing-classes)");
        assertThat(rowClasses(crash.rerun())).containsExactly(EARLY);
    }

    /// The rerun's install step does not compile: the overlay makes javac refuse a raw type in a `-DskipTests` build,
    /// which the Gate never runs. No class ran, so every target is NOT-RUN(rc).
    @Test
    void compileFailure_everyTargetIsNotRunRc() {
        var broken = rerunOf("rerun-nocompile");
        var record = stack.api.result(broken.rerun()).await().expect("nocompile rerun record");

        assertThat(classesOf(stack.api.result(broken.parent()).await().expect("nocompile parent"))).containsExactly(USES_HELPER);
        assertThat(record.annotations()).as(record.toString())
                                        .containsExactly("rerun-of=" + broken.parent(),
                                                         floorBound(record),
                                                         "rerun(" + USES_HELPER + ")=NOT-RUN(rc)");
        assertThat(rowClasses(broken.rerun())).isEmpty();
    }

    /// maintainer ruling on the rerun bound: the fixture's classes take seconds, so every rerun here derives `Σ × 1.5 + install
    /// 5m + slack 5m` just over 10 min from the parents' stored rows and keeps the 30 min floor. The second annotation,
    /// checked against that shape, and returned for the exact list.
    private static String floorBound(ResultRecordDto record) {
        var bound = record.annotations().get(1);

        assertThat(bound).as(record.annotations().toString()).matches("rerun-bound=30m \\(floor; derived 10\\.[0-9]m\\)");
        return bound;
    }

    /// A parent Gate of `branch` and its rerun, awaited.
    record Pair(long parent, long rerun) {}

    private Pair rerunOf(String branch) {
        var gate = stack.gate(branch);
        var submitted = stack.temper("rerun", String.valueOf(gate));

        assertThat(submitted.rc()).as(submitted.stdoutText() + submitted.stderrText()).isZero();
        var job = Long.parseLong(submitted.stdoutText().strip().split(" ")[1]);

        stack.awaited(job, Duration.ofMinutes(30));
        return new Pair(gate, job);
    }

    private Set<String> rowClasses(long job) {
        return sorted(stack.api.testcases(job).await().expect("rows").stream().map(TestcaseDto::className));
    }

    /// Control: the instrument counts rows; a class set of the parent is not empty, so an empty rerun cannot pass.
    @Test
    void control_theParentReportedTheWholeModule() {
        Set<String> classes = stack.api.testcases(parent)
                                       .await()
                                       .expect("parent rows")
                                       .stream()
                                       .map(TestcaseDto::className)
                                       .collect(Collectors.toCollection(TreeSet::new));

        assertThat(classes).contains(FLAKY, RED, "fx.includes.BetaTest");
    }

    private static Set<String> sorted(Stream<String> names) {
        return names.collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> classesOf(ResultRecordDto record) {
        return sorted(record.failed().stream().map(ResultRecordDto.FailedDto::className));
    }

    private String recordJson(long job) {
        return stack.store().results().recordJson(JobId.jobId(job).unwrap()).await().expect("record json").or("");
    }

    private JobRow row(long job) {
        return stack.store().jobs().job(JobId.jobId(job).unwrap()).await().expect("job row").fold(() -> fail("expected a value"), found -> found);
    }
}
