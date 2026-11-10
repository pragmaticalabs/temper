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
import org.pragmatica.temper.protocol.api.RerunRequest;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.SourceView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// `rerun <job>` (m2-design §5.5) as whole CLI invocations against the fake controller: 0 accepted, 10 deduplicated, 2
/// refused (`REFUSED(rerun)`), 64 for usage or an unknown job, 69 unreachable. The body is `RerunRequest` as the CLI's
/// own mapper writes it; the accepted line reads the rerun's classes and SHA back from its job view.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RerunAgainstControllerTest {
    static final String ACCEPTED_LINE = "rerun 4740 QUEUED of=4711 classes=org.x.BarTest,org.x.FooTest sha=0cf5631 deduplicated=false";

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

    /// The rerun job 4740 of 4711 as `GET /jobs/4740` shows it.
    static JobView rerunJob(long id, JobState state) {
        return JobView.jobView(id,
                               state,
                               none(),
                               none(),
                               "tester",
                               Fixtures.AT,
                               -20,
                               false,
                               false,
                               false,
                               new SourceView(none(), some(Fixtures.SHA)),
                               new RecipeDto.Rerun(4711,
                                                   List.of(new RecipeDto.Rerun.TargetDto("org.x.BarTest", "core", "surefire"),
                                                           new RecipeDto.Rerun.TargetDto("org.x.FooTest", "core", "surefire")),
                                                   none(),
                                                   none()),
                               List.of(),
                               List.of());
    }

    @Test
    void rerun_accepted_printsTheLine_postsTheOwner_exits0() {
        controller.onJson("POST /jobs/4711/rerun", Fixtures.submitted(4740, JobState.QUEUED, false))
                  .onJson("GET /jobs/4740", rerunJob(4740, JobState.QUEUED));

        var result = cli.run("rerun", "4711");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).containsExactly(ACCEPTED_LINE);
        assertThat(controller.received().getFirst().target()).isEqualTo("/api/v1/jobs/4711/rerun");
        assertThat(sentRequest()).isEqualTo(RerunRequest.rerunRequest("tester", none(), some(false), none(), some(false)));
    }

    @Test
    void rerun_withPrioFreshDedicatedCpuUnitTimeoutAndOwner_sendsThem() {
        controller.onJson("POST /jobs/4711/rerun", Fixtures.submitted(4740, JobState.QUEUED, false))
                  .onJson("GET /jobs/4740", rerunJob(4740, JobState.QUEUED));

        var result = cli.run("rerun", "4711", "--prio", "7", "--fresh", "--dedicated-cpu", "--unit-timeout", "PT90M", "--owner", "cto");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sentRequest()).isEqualTo(RerunRequest.rerunRequest("cto", some(7), some(true), some("PT90M"), some(true)));
    }

    /// A unit timeout the domain rejects (under 1 min) is exit 64 without a call.
    @Test
    void rerun_withABadUnitTimeout_exits64_withoutACall() {
        var result = cli.run("rerun", "4711", "--unit-timeout", "PT30S");

        assertThat(result.exit()).as(result.err()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void rerun_deduplicated_printsTheFirstRerun_exits10() {
        controller.onJson("POST /jobs/4711/rerun", Fixtures.submitted(4740, JobState.PASSED, true))
                  .onJson("GET /jobs/4740", rerunJob(4740, JobState.PASSED));

        var result = cli.run("rerun", "4711");

        assertThat(result.exit()).isEqualTo(10);
        assertThat(result.out()).containsExactly("rerun 4740 PASSED of=4711 classes=org.x.BarTest,org.x.FooTest sha=0cf5631 deduplicated=true");
    }

    @Test
    void rerun_refused_printsTheDetailAndTheJob_exits2() {
        controller.on("POST /jobs/4711/rerun",
                      Reply.json(422, controller.write(Fixtures.refused("rerun", "4 failing classes (max 3)", some(4741L)))));

        var result = cli.run("rerun", "4711");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.out()).containsExactly("rerun - REFUSED code=rerun detail=\"4 failing classes (max 3)\" job=4741");
    }

    @Test
    void rerun_ofAnUnknownJob_exits64() {
        var result = cli.run("rerun", "999");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Not found: /jobs/999/rerun");
    }

    /// The rerun took; a failed read-back degrades the line (FER), never the exit code.
    @Test
    void rerun_readBackFails_printsUnknownClassesAndSha_exits0() {
        controller.onJson("POST /jobs/4711/rerun", Fixtures.submitted(4740, JobState.QUEUED, false));

        var result = cli.run("rerun", "4711");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("rerun 4740 QUEUED of=4711 classes=? sha=? deduplicated=false");
    }

    @Test
    void rerun_badArguments_exit64_withoutACall() {
        assertThat(cli.run("rerun").exit()).isEqualTo(64);
        assertThat(cli.run("rerun", "0").exit()).isEqualTo(64);
        assertThat(cli.run("rerun", "4711", "--prio", "x").exit()).isEqualTo(64);
        assertThat(cli.run("rerun", "4711", "--prio", "5000").exit()).as("out of the domain's range").isEqualTo(64);
        assertThat(cli.run("rerun", "4711", "--owner", "no spaces").exit()).isEqualTo(64);
        assertThat(cli.run("rerun", "4711", "--keep").exit()).as("not a rerun option").isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void rerun_withoutAnOwner_exits64() {
        var result = cli.env("TEMPER_OWNER", "").run("rerun", "4711");

        assertThat(result.exit()).isEqualTo(64);
    }

    @Test
    void rerun_unreachableController_exits69() throws IOException {
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort()).run("rerun", "4711");

        assertThat(result.exit()).isEqualTo(69);
    }

    /// `wait` on a rerun prints its own annotations (m2-design §5.5), the unit's bound and its derivation included.
    @Test
    void wait_onARerun_printsRerunOfTheBoundAndThePerClassOutcomes() {
        var record = Fixtures.result(4740, "PASSED", none(), 0, 0, 0);
        var rerun = withAnnotations(record,
                                    List.of("rerun-of=4711",
                                            "rerun-bound=48m (Σ 25.1m ×1.5 + install 5m + slack 5m)",
                                            "rerun(org.x.BarTest)=PASSED co-run in one fork with: org.x.FooTest",
                                            "rerun(org.x.FooTest)=NOT-RUN(rc)",
                                            "extra-rows=3",
                                            "evidence=missing"));

        controller.onJson("GET /jobs/4740", rerunJob(4740, JobState.PASSED)).onJson("GET /jobs/4740/result", rerun);

        var result = cli.run("wait", "4740");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("wait 4740 PASSED testcases=12 failed=0 observed-failures=0 evidence=missing rerun-of=4711 "
                                                 + "\"rerun-bound=48m (Σ 25.1m ×1.5 + install 5m + slack 5m)\" "
                                                 + "\"rerun(org.x.BarTest)=PASSED co-run in one fork with: org.x.FooTest\" "
                                                 + "rerun(org.x.FooTest)=NOT-RUN(rc) extra-rows=3");
    }

    /// `result` on a FAILED(tests) parent adds its reruns, read from its view; on any other record it reads nothing more.
    @Test
    void result_ofAFailedParent_listsItsReruns() {
        var parent = Fixtures.result(4711, "FAILED", some("tests"), 1, 0, 1);
        var view = Fixtures.job(4711, JobState.FAILED);
        var withReruns = JobView.jobView(view.job(), view.state(), view.verdict(), view.reason(), view.submittedBy(), view.submittedAt(),
                                         view.priority(), view.keep(), view.fresh(), view.superseded(), view.source(), view.recipe(),
                                         view.units(),
                                         List.of(RerunView.rerunView(4740, JobState.PASSED, List.of("rerun(org.x.FooTest)=PASSED")),
                                                 RerunView.rerunView(4745, JobState.RUNNING, List.of())));

        controller.onJson("GET /jobs/4711/result", parent).onJson("GET /jobs/4711", withReruns);

        var result = cli.run("result", "4711");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).containsExactly("result 4711 FAILED reason=tests testcases=12 failed=1 skipped=2 units=1 "
                                                 + "observed-failures=1 evidence=missing rerun=4740:PASSED,4745:RUNNING");
    }

    /// The record decides the line and the exit; a failed read of the reruns shows `rerun=?` (FER).
    @Test
    void result_ofAFailedParent_rerunsUnreadable_showsUnknown() {
        controller.onJson("GET /jobs/4711/result", Fixtures.result(4711, "FAILED", some("tests"), 1, 0, 1));

        var result = cli.run("result", "4711");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out().getFirst()).endsWith(" evidence=missing rerun=?");
    }

    private static ResultRecordDto withAnnotations(ResultRecordDto record, List<String> annotations) {
        return ResultRecordDto.resultRecordDto(record.job(), record.verdict(), record.reason(), record.reasonDetail(), record.source(),
                                               record.recipe(), annotations, record.counts(), record.failed(), record.observedFailures(),
                                               record.units(), record.manifest(), record.evidence(), record.timings(), record.judge());
    }

    private RerunRequest sentRequest() {
        return TemperJson.mapper()
                         .readString(controller.received()
                                               .getFirst()
                                               .body(),
                                     RerunRequest.class)
                         .fold(cause -> fail(cause.message()), request -> request);
    }
}
