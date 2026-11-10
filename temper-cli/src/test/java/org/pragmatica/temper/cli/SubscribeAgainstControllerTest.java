package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.SelectorChange;
import org.pragmatica.temper.protocol.api.SubscriberView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// `subscribe|unsubscribe <job|sha|ref> --subscriber <name>` (m2-design §2.4) as whole CLI invocations against the fake
/// controller: the classified selector goes out in `PUT …/selectors`, and the line names the operation, a job's state
/// read after the change, and the resulting scope (`selectors=all` for the empty set, F4/Q-M2-9).
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SubscribeAgainstControllerTest {
    private static final String SHA = Fixtures.SHA;
    private static final String ROUTE = "PUT /subscribers/cto/selectors";

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

    private void answers(String... selectors) {
        controller.on(ROUTE, Reply.json(200, controller.write(SubscriberView.subscriberView("cto", List.of(selectors), 88))));
    }

    /// The `PUT` body as the CLI sent it.
    private SelectorChange sent() {
        var put = controller.received()
                            .stream()
                            .filter(request -> request.method().equals("PUT"))
                            .toList();
        assertThat(put).hasSize(1);
        assertThat(put.getFirst().target()).isEqualTo("/api/v1/subscribers/cto/selectors");
        return TemperJson.mapper().readString(put.getFirst().body(), SelectorChange.class).unwrap();
    }

    private List<String> methods() {
        return controller.received().stream().map(request -> request.method() + " " + request.target()).toList();
    }

    @Test
    void subscribe_job_narrowsAnAllJobsSubscriber_andPrintsTheStateReadAfterTheChange() {
        answers("job:4711");
        controller.onJson("GET /jobs/4711", Fixtures.job(4711, JobState.RUNNING));

        var result = cli.run("subscribe", "4711", "--subscriber", "cto");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).containsExactly("subscribe cto ADDED job:4711 state=RUNNING selectors=job:4711");
        assertThat(sent()).isEqualTo(SelectorChange.selectorChange(List.of("job:4711"), List.of()));
        assertThat(methods()).as("the state is read after the change commits")
                             .containsExactly("PUT /api/v1/subscribers/cto/selectors", "GET /api/v1/jobs/4711");
    }

    /// No event will come for a job the cursor already passed: the line says it is terminal.
    @Test
    void subscribe_terminalJob_printsItsVerdictAsTheState() {
        answers("job:4711", "ref:main");
        controller.onJson("GET /jobs/4711", Fixtures.job(4711, JobState.FAILED));

        var result = cli.run("subscribe", "4711", "--subscriber", "cto");

        assertThat(result.out()).containsExactly("subscribe cto ADDED job:4711 state=FAILED selectors=job:4711,ref:main");
    }

    @Test
    void unsubscribe_lastSelector_widensToAll_andReadsNoJob() {
        answers();

        var result = cli.run("unsubscribe", "4711", "--subscriber", "cto");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).containsExactly("unsubscribe cto REMOVED job:4711 selectors=all");
        assertThat(sent()).isEqualTo(SelectorChange.selectorChange(List.of(), List.of("job:4711")));
        assertThat(methods()).containsExactly("PUT /api/v1/subscribers/cto/selectors");
    }

    /// R-M2-12 (c): a SHA selector's reply lists the state of every job it matches now, read after the change.
    @Test
    void subscribe_fullSha_isAShaSelector_andListsTheJobsAtThatSha() {
        answers("sha:" + SHA);
        jobsAnswer(Fixtures.job(42, JobState.RUNNING), Fixtures.job(41, JobState.FAILED));

        var result = cli.run("subscribe", SHA, "--subscriber", "cto");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).containsExactly("subscribe cto ADDED sha:" + SHA + " matching=2 selectors=sha:" + SHA,
                                                 "job 42 state=RUNNING recipe=module-test sha=0cf5631",
                                                 "job 41 state=FAILED recipe=module-test sha=0cf5631");
        assertThat(sent().add()).containsExactly("sha:" + SHA);
        assertThat(methods()).containsExactly("PUT /api/v1/subscribers/cto/selectors", "GET /api/v1/jobs?limit=200&sha=" + SHA);
    }

    /// R-M2-12 (c): more than five matching jobs: the five newest, then the command that lists every one.
    @Test
    void subscribe_ref_listsTheFiveNewest_thenNamesTheCommandForTheRest() {
        answers("ref:main");
        jobsAnswer(LongStream.rangeClosed(1, 7).map(n -> 50 - n).mapToObj(id -> Fixtures.job(id, JobState.PASSED)).toArray(JobView[]::new));

        var result = cli.run("subscribe", "main", "--subscriber", "cto");

        assertThat(result.out()).containsExactly("subscribe cto ADDED ref:main matching=7 selectors=ref:main "
                                                 + "note=\"a job submitted by --sha has no ref and never matches ref:main\"",
                                                 "job 49 state=PASSED recipe=module-test sha=0cf5631",
                                                 "job 48 state=PASSED recipe=module-test sha=0cf5631",
                                                 "job 47 state=PASSED recipe=module-test sha=0cf5631",
                                                 "job 46 state=PASSED recipe=module-test sha=0cf5631",
                                                 "job 45 state=PASSED recipe=module-test sha=0cf5631",
                                                 "+2 more: temper jobs --ref main");
        assertThat(methods()).contains("GET /api/v1/jobs?limit=200&ref=main");
    }

    /// The boundary: exactly five matching jobs print no `+K more` line; six print `+1 more`.
    @Test
    void subscribe_fiveMatching_noMoreLine_sixMatching_plusOne() {
        answers("sha:" + SHA);
        jobsAnswer(LongStream.rangeClosed(1, 5).mapToObj(id -> Fixtures.job(id, JobState.PASSED)).toArray(JobView[]::new));

        var five = cli.run("subscribe", SHA, "--subscriber", "cto").out();

        assertThat(five).hasSize(6).noneMatch(line -> line.startsWith("+"));

        jobsAnswer(LongStream.rangeClosed(1, 6).mapToObj(id -> Fixtures.job(id, JobState.PASSED)).toArray(JobView[]::new));

        var six = cli.run("subscribe", SHA, "--subscriber", "cto").out();

        assertThat(six).hasSize(7).last().isEqualTo("+1 more: temper jobs --sha " + SHA);
    }

    @Test
    void subscribe_sha_withNoMatchingJob_saysMatchingZero() {
        answers("sha:" + SHA);
        jobsAnswer();

        assertThat(cli.run("subscribe", "sha:" + SHA, "--subscriber", "cto").out()).containsExactly("subscribe cto ADDED sha:" + SHA
                                                                                                    + " matching=0 selectors=sha:" + SHA);
    }

    private void jobsAnswer(JobView... views) {
        controller.onJson("GET /jobs", (Object) views);
    }

    /// F3: a numeric ref is reachable only through its prefix, and is never taken for (or looked up as) a job.
    @Test
    void subscribe_refPrefixedNumber_isARef_notJob4711_andNotesBySha() {
        answers("ref:4711");
        jobsAnswer();

        var result = cli.run("subscribe", "ref:4711", "--subscriber", "cto");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).containsExactly("subscribe cto ADDED ref:4711 matching=0 selectors=ref:4711 "
                                                 + "note=\"a job submitted by --sha has no ref and never matches ref:4711\"");
        assertThat(sent().add()).containsExactly("ref:4711");
        assertThat(methods()).as("no GET /jobs/4711").containsExactly("PUT /api/v1/subscribers/cto/selectors",
                                                                      "GET /api/v1/jobs?limit=200&ref=4711");
    }

    @Test
    void subscribe_bareRef_isARef() {
        answers("ref:main");
        jobsAnswer();

        cli.run("subscribe", "main", "--subscriber", "cto");

        assertThat(sent().add()).containsExactly("ref:main");
    }

    /// F3: a short SHA matches nothing, so it is refused before any call.
    @Test
    void subscribe_shortSha_exits64_withoutContactingTheController() {
        var result = cli.run("subscribe", "0cf5631", "--subscriber", "cto");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("sha:<full 40 hex> or ref:<name>");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void subscribe_unknownJob_exits64() {
        controller.on(ROUTE, Reply.json(404, "{\"error\":\"Not found: job 4711\"}"));

        assertThat(cli.run("subscribe", "4711", "--subscriber", "cto").exit()).isEqualTo(64);
    }

    @Test
    void subscribe_badName_rejected400_exits64() {
        controller.on(ROUTE, Reply.json(400, "{\"error\":\"Invalid subscriber: 'cto'\"}"));

        var result = cli.run("subscribe", "4711", "--subscriber", "cto");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Invalid subscriber");
    }

    @Test
    void subscribe_withoutSubscriber_orSelector_exits64() {
        assertThat(cli.run("subscribe", "4711").exit()).isEqualTo(64);
        assertThat(cli.run("unsubscribe", "--subscriber", "cto").exit()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void subscribe_unreachableController_exits69() throws IOException {
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + ExitCodeContractTest.closedPort())
                        .run("subscribe", "4711", "--subscriber", "cto");

        assertThat(result.exit()).isEqualTo(69);
    }
}
