package org.pragmatica.temper.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.CancelResponse;
import org.pragmatica.temper.protocol.json.TemperJson;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.cli.FakeController.Reply;

/// `--json` (spec §8.1): stdout is exactly one line holding the controller's DTO, parsed here as JSON, so a
/// renamed key, a dropped key or a command that ignores `--json` fails; the exit code is the one the line form
/// gives.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class JsonOutputTest {
    private static final String MODULE_TEST_SHA = "submit module-test --pl core --test Foo* --sha " + Fixtures.SHA + " --json";
    private static final List<String> JOB_VIEW_KEYS = List.of("job",
                                                              "state",
                                                              "verdict",
                                                              "reason",
                                                              "submittedBy",
                                                              "submittedAt",
                                                              "priority",
                                                              "keep",
                                                              "fresh",
                                                              "superseded",
                                                              "source",
                                                              "recipe",
                                                              "units",
                                                              "reruns",
                                                              "userProperties",
                                                              "dedicatedCpu");
    private static final List<String> RESULT_KEYS = List.of("job",
                                                            "verdict",
                                                            "reason",
                                                            "reasonDetail",
                                                            "source",
                                                            "recipe",
                                                            "annotations",
                                                            "counts",
                                                            "failed",
                                                            "observedFailures",
                                                            "units",
                                                            "manifest",
                                                            "evidence",
                                                            "timings",
                                                            "judge",
                                                            "userProperties");

    private final JsonMapper mapper = TemperJson.mapper();

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
    void submit_json_printsSubmitResponse_whenAccepted_withoutReadingTheJobBack() {
        controller.onJson("POST /jobs", Fixtures.submitted(41, JobState.QUEUED, false));

        var result = cli.run(MODULE_TEST_SHA.split(" "));

        assertThat(result.exit()).isZero();
        assertThat(only(result)).isEqualTo(tree("{\"job\":41,\"state\":\"QUEUED\",\"deduplicated\":false}"));
        assertThat(controller.received()).extracting(FakeController.Received::method)
                                         .containsExactly("POST");
    }

    /// m4-design §9.2: `--dedicated-cpu` goes out as `dedicatedCpu`, true; without it, false.
    @Test
    void submit_dedicatedCpu_sendsTheFlag() {
        controller.onJson("POST /jobs", Fixtures.submitted(41, JobState.QUEUED, false));

        assertThat(cli.run((MODULE_TEST_SHA + " --dedicated-cpu").split(" ")).exit()).isZero();
        assertThat(cli.run(MODULE_TEST_SHA.split(" ")).exit()).isZero();

        assertThat(controller.received()).extracting(FakeController.Received::body)
                                         .satisfiesExactly(body -> assertThat(body).contains("\"dedicatedCpu\":true"),
                                                           body -> assertThat(body).contains("\"dedicatedCpu\":false"));
    }

    @Test
    void submit_json_printsSubmitResponse_whenDeduplicated_exitTen() {
        controller.onJson("POST /jobs", Fixtures.submitted(40, JobState.PASSED, true));

        var result = cli.run(MODULE_TEST_SHA.split(" "));

        assertThat(result.exit()).isEqualTo(10);
        assertThat(only(result)).isEqualTo(tree("{\"job\":40,\"state\":\"PASSED\",\"deduplicated\":true}"));
    }

    @Test
    void submit_json_printsRefusedResponse_whenRefused_exitTwo() {
        controller.on("POST /jobs",
                      Reply.json(422,
                                 controller.write(Fixtures.refused("R-M2", "-Dmaven.repo.local is not allowed", some(42L)))));

        var result = cli.run(MODULE_TEST_SHA.split(" "));

        assertThat(result.exit()).isEqualTo(2);
        assertThat(only(result)).isEqualTo(tree("{\"refused\":\"R-M2\",\"detail\":\"-Dmaven.repo.local is not allowed\",\"job\":42}"));
    }

    @Test
    void wait_json_printsResultRecord_whenTerminal() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.FAILED))
                  .onJson("GET /jobs/41/result", Fixtures.result(41, "FAILED", some("tests"), 1, 1, 2));

        var result = cli.run("wait", "41", "--json");
        var record = only(result);

        assertThat(result.exit()).isEqualTo(1);
        assertThat(keys(record)).containsExactlyElementsOf(RESULT_KEYS);
        assertThat(record.get("job").asLong()).isEqualTo(41);
        assertThat(record.get("verdict").asString()).isEqualTo("FAILED");
        assertThat(record.get("reason").asString()).isEqualTo("tests");
        assertThat(record.get("observedFailures").asInt()).isEqualTo(2);
        assertThat(record.get("counts").get("failed").asInt()).isEqualTo(1);
    }

    @Test
    void wait_json_printsJobView_onTimeout_exitSeven() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.RUNNING));

        var result = cli.run("wait", "41", "--timeout", "10s", "--json");
        var view = only(result);

        assertThat(result.exit()).isEqualTo(7);
        assertThat(keys(view)).containsExactlyElementsOf(JOB_VIEW_KEYS);
        assertThat(view.get("job").asLong()).isEqualTo(41);
        assertThat(view.get("state").asString()).isEqualTo("RUNNING");
    }

    @Test
    void cancel_json_printsCancelResponse_whenAccepted() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.RUNNING))
                  .onJson("POST /jobs/41/cancel", CancelResponse.cancelResponse(JobState.CANCELLING));

        var result = cli.run("cancel", "41", "--json");

        assertThat(result.exit()).isZero();
        assertThat(only(result)).isEqualTo(tree("{\"state\":\"CANCELLING\"}"));
    }

    @Test
    void cancel_json_printsJobView_whenAlreadyTerminal() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.FAILED));

        var result = cli.run("cancel", "41", "--json");
        var view = only(result);

        assertThat(result.exit()).isEqualTo(1);
        assertThat(keys(view)).containsExactlyElementsOf(JOB_VIEW_KEYS);
        assertThat(view.get("state").asString()).isEqualTo("FAILED");
        assertThat(view.get("verdict").asString()).isEqualTo("FAILED");
    }

    @Test
    void result_json_printsResultRecord_withUnchangedExitCode() {
        controller.onJson("GET /jobs/41/result", Fixtures.result(41, "EMPTY", some("build"), 0, 0, 0));

        var result = cli.run("result", "41", "--json");
        var record = only(result);

        assertThat(result.exit()).isEqualTo(3);
        assertThat(keys(record)).containsExactlyElementsOf(RESULT_KEYS);
        assertThat(record.get("job").asLong()).isEqualTo(41);
        assertThat(record.get("verdict").asString()).isEqualTo("EMPTY");
        assertThat(record.get("reason").asString()).isEqualTo("build");
        assertThat(record.get("observedFailures").asInt()).isZero();
        assertThat(record.get("counts").get("testcases").asInt()).isEqualTo(12);
    }

    @Test
    void result_json_printsJobView_beforeTerminal_exitNine() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.RUNNING));

        var result = cli.run("result", "41", "--json");
        var view = only(result);

        assertThat(result.exit()).isEqualTo(9);
        assertThat(keys(view)).containsExactlyElementsOf(JOB_VIEW_KEYS);
        assertThat(view.get("state").asString()).isEqualTo("RUNNING");
        assertThat(view.get("verdict").isNull()).isTrue();
    }

    /// The single stdout line, parsed; anything that is not one JSON document fails the test.
    private JsonNode only(CliHarness.Run result) {
        assertThat(result.out()).as(result.err())
                                .hasSize(1);

        return tree(result.out()
                          .getFirst());
    }

    private JsonNode tree(String text) {
        return mapper.readTree(text)
                     .onFailure(cause -> fail("not JSON: " + text + " (" + cause.message() + ")"))
                     .unwrap();
    }

    private static List<String> keys(JsonNode node) {
        return List.copyOf(node.propertyNames());
    }
}
