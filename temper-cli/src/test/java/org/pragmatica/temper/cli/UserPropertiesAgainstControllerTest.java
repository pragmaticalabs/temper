package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// maintainer ruling Q-M4-11c as whole CLI invocations against the fake controller: a green job submitted with `-DskipTests` or
/// `-Dtest=…` shows those properties, marked, on `result`, `wait`, a not-yet-terminal `result` and the `jobs` listing, and
/// in `--json`; a job without a user property shows none.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class UserPropertiesAgainstControllerTest {
    private static final List<String> SKIPPED = List.of("-DskipTests", "-pl", "core", "-Dtest=FooTest", "-DfailIfNoSpecifiedTests=false",
                                                        "install");
    /// Each value is always quoted (`UserPropertyDto.token`); properties and the narrowing `-pl` in argv order (Q-M4-11d).
    private static final String SHOWN = " tests-skipped=\"-DskipTests=true\" tests-narrowed=\"-pl core\""
                                        + " tests-narrowed=\"-Dtest=FooTest\" property=\"-DfailIfNoSpecifiedTests=false\"";

    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    @Test
    void result_and_wait_showTheSkipAndSelectProperties() {
        controller.onJson("GET /jobs/41", job(41, JobState.PASSED, SKIPPED))
                  .onJson("GET /jobs/41/result", record(41, SKIPPED));

        var result = cli.run("result", "41");
        var waited = cli.run("wait", "41");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).singleElement().asString().startsWith("result 41 PASSED ").endsWith(SHOWN);
        assertThat(waited.exit()).as(waited.err()).isZero();
        assertThat(waited.out()).singleElement().asString().startsWith("wait 41 PASSED ").endsWith(SHOWN);
    }

    @Test
    void result_and_wait_showNoProperty_withoutOne() {
        controller.onJson("GET /jobs/42", job(42, JobState.PASSED, List.of("install")))
                  .onJson("GET /jobs/42/result", record(42, List.of("install")));

        var result = cli.run("result", "42");
        var waited = cli.run("wait", "42");

        assertThat(result.out()).singleElement().asString().startsWith("result 42 PASSED ").doesNotContain("tests-").doesNotContain("property=");
        assertThat(waited.out()).singleElement().asString().startsWith("wait 42 PASSED ").doesNotContain("tests-").doesNotContain("property=");
    }

    @Test
    void result_json_carriesTheMarkedProperties() {
        controller.onJson("GET /jobs/41/result", record(41, SKIPPED));

        var result = cli.run("result", "41", "--json");
        var json = String.join("\n", result.out());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(json).contains("\"userProperties\":[{\"define\":\"-DskipTests=true\",\"tests\":\"skipped\"},"
                                  + "{\"define\":\"-pl core\",\"tests\":\"narrowed\"},"
                                  + "{\"define\":\"-Dtest=FooTest\",\"tests\":\"narrowed\"},"
                                  + "{\"define\":\"-DfailIfNoSpecifiedTests=false\",\"tests\":null}]");
        assertThat(TemperJson.mapper().readString(json, ResultRecordDto.class).unwrap().userProperties()).hasSize(4);
    }

    /// Verify4 blocker 1: a stored recipe whose value holds a newline (a job stored before R-EFFECT refused it) prints
    /// one escaped line, never a forged second one.
    @Test
    void result_escapesAStoredValue_thatWouldBreakTheLine() {
        var forged = List.of("-Dtest=Foo\nresult 9 PASSED", "install");

        controller.onJson("GET /jobs/44", job(44, JobState.PASSED, forged))
                  .onJson("GET /jobs/44/result", record(44, forged));

        var result = cli.run("result", "44");

        assertThat(result.out()).singleElement().asString().endsWith(" tests-narrowed=\"-Dtest=Foo\\nresult 9 PASSED\"");
    }

    @Test
    void pendingResult_and_jobsListing_showTheProperties() {
        controller.onJson("GET /jobs/43", job(43, JobState.RUNNING, SKIPPED))
                  .onJson("GET /jobs", (Object) new JobView[]{job(43, JobState.RUNNING, SKIPPED)});

        var pending = cli.run("result", "43");
        var jobs = cli.run("jobs", "--sha", Fixtures.SHA);

        assertThat(pending.out()).containsExactly("result 43 RUNNING" + SHOWN);
        assertThat(jobs.out()).containsExactly("job 43 state=RUNNING recipe=mvn sha=0cf5631" + SHOWN);
    }

    private static JobView job(long id, JobState state, List<String> args) {
        return JobView.jobView(id,
                               state,
                               state.isTerminal()
                               ? some(state.name())
                               : none(),
                               none(),
                               "tester",
                               Fixtures.AT,
                               -20,
                               false,
                               false,
                               false,
                               new SourceView(some("main"), some(Fixtures.SHA)),
                               new RecipeDto.Mvn(args),
                               List.of(),
                               List.of());
    }

    private static ResultRecordDto record(long id, List<String> args) {
        return ResultRecordDto.resultRecordDto(id,
                                               "PASSED",
                                               none(),
                                               none(),
                                               new SourceView(some("main"), some(Fixtures.SHA)),
                                               new RecipeDto.Mvn(args),
                                               List.of(),
                                               new ResultRecordDto.CountsDto(0, 0, 0, 0, 0, 0, 0, none(), none(), none()),
                                               List.of(),
                                               0,
                                               List.of(),
                                               none(),
                                               ResultRecordDto.EvidenceDto.NOT_STORED,
                                               ResultRecordDto.TimingsDto.timingsDto(none(), none()),
                                               none());
    }
}
