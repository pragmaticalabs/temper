package org.pragmatica.temper.controller.it;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.protocol.message.KilledBy;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.it.ControllerHarness.jobOf;
import static org.pragmatica.temper.controller.it.ControllerHarness.mvnJob;

/// HTTP → controller → Postgres (it-pg tier), with one host behind a fake worker that completes every unit.
@Tag("pg")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControllerIT {
    private static final String BY_MAIN = "{\"pushed\":{\"ref\":\"main\"}}";
    private ControllerHarness harness;

    @BeforeAll
    void start() throws Exception {
        harness = new ControllerHarness(true, TimeSpan.timeSpan(500).millis());
    }

    @AfterAll
    void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void everyRoute_answers401_withoutToken_overHttp() throws Exception {
        for (var route : new String[][] {{"POST", "/jobs"}, {"GET", "/jobs"}, {"GET", "/jobs/1"}, {"GET", "/jobs/1/result"},
                                         {"GET", "/jobs/1/testcases"}, {"POST", "/jobs/1/cancel"}, {"GET", "/jobs/1/log"},
                                         {"GET", "/hosts"}, {"GET", "/digest"}, {"GET", "/subscribers/x/next"}, {"POST", "/subscribers/x/ack"}}) {
            var reply = harness.call(route[0], route[1], route[0].equals("POST") ? mvnJob(BY_MAIN, "verify") : "", false);

            assertThat(reply.status()).as(route[0] + " " + route[1]).isEqualTo(401);
            assertThat(reply.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        }
        assertThat(harness.get("/jobs?submittedBy=v2174&limit=1000").body()).doesNotContain("\"submittedBy\":\"anon\"");
    }

    /// D-6: every answer names the controller's build version, the 401 without a token and the 404 of an unknown path
    /// included, so the CLI can refuse a skewed controller before it decodes anything.
    @Test
    void everyAnswer_carriesTheBuildVersionHeader() throws Exception {
        assertThat(harness.call("GET", "/jobs", "", false)
                          .temperVersion()).as("401").isEqualTo(ControllerHarness.VERSION);
        assertThat(harness.get("/no-such-route")
                          .temperVersion()).as("404").isEqualTo(ControllerHarness.VERSION);
        assertThat(harness.get("/hosts")
                          .temperVersion()).as("200").isEqualTo(ControllerHarness.VERSION);
    }

    @Test
    void submit_runsToPassed_withResultRecordAndTestcases() throws Exception {
        var submitted = harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "lifecycle", "verify"));

        assertThat(submitted.status()).isEqualTo(200);
        assertThat(submitted.body()).contains("\"state\":\"QUEUED\"", "\"deduplicated\":false");
        var job = jobOf(submitted);

        var view = harness.waitForState(job, "PASSED");
        assertThat(view).contains("\"verdict\":\"PASSED\"", "\"host\":\"bb\"", "\"containerId\":\"c" + job + "\"");

        var result = harness.get("/jobs/" + job + "/result");
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).contains("\"verdict\":\"PASSED\"", "\"testcases\":1", "\"stored\":true",
                                           "\"path\":\"" + harness.evidenceRoot.resolve("jobs/" + job) + "\"");
        assertThat(harness.evidenceRoot.resolve("jobs/" + job + "/units/main/a1/evidence.tar.gz")).exists();
        var resultJson = harness.evidenceRoot.resolve("jobs/" + job + "/result.json");
        waitFor(() -> Files.exists(resultJson));
        assertThat(Files.readString(resultJson)).as("result.json is the stored record, byte for byte")
                                                .isEqualTo(harness.store.results()
                                                                        .recordJson(JobId.jobId(job).unwrap())
                                                                        .await()
                                                                        .unwrap()
                                                                        .unwrap());
        assertThat(result.body()).contains("\"tcLeftovers\":{\"containers\":[{\"id\":\"tc-left\",\"session\":\"s-9\"}],\"unverified\":null}");

        var testcases = harness.get("/jobs/" + job + "/testcases");
        assertThat(testcases.body()).contains("\"class\":\"org.x.FooTest\"", "\"outcome\":\"passed\"");
        assertThat(harness.get("/jobs/" + job + "/testcases?outcome=failed").body()).isEqualTo("[]");

        var start = harness.worker.all.stream().map(envelope -> envelope.msg())
                                      .filter(Message.StartUnit.class::isInstance)
                                      .map(Message.StartUnit.class::cast)
                                      .filter(message -> message.job() == job)
                                      .findFirst()
                                      .orElseThrow();
        assertThat(start.steps().getFirst()).containsExactly("mvn", "-B", "-pl", "lifecycle", "verify",
                                                             "-Dmaven.repo.local=${ws}/.m2-local");
        waitFor(() -> harness.worker.count(Message.UnitReportAck.class) >= 1);
        assertThat(harness.worker.count(Message.UnitReportAck.class)).isGreaterThanOrEqualTo(1);
    }

    /// Ruling R-2 end to end: the worker's optional classes cross the wire, the store and the view. The reporting
    /// optional class is not drift, the silent one is not missing, so the job passes without `manifest-drift`.
    @Test
    void submit_manifestWithOptionalClasses_passesWithoutMissingOrDrift() throws Exception {
        var submitted = harness.post("/jobs", "{\"submittedBy\":\"v2174\",\"source\":" + BY_MAIN
                                              + ",\"recipe\":{\"moduleTest\":{\"pl\":[\"core\"]}}}");

        assertThat(submitted.status()).as(submitted.body()).isEqualTo(200);
        var job = jobOf(submitted);

        harness.waitForState(job, "PASSED");
        var manifest = harness.get("/jobs/" + job + "/manifest");
        assertThat(manifest.status()).isEqualTo(200);
        assertThat(manifest.body()).contains("\"computed\":true",
                                             "\"pruned\":false",
                                             "\"entries\":[{\"module\":\"core\",\"class\":\"org.x.FooTest\",\"runner\":\"surefire\"}]",
                                             "\"class\":\"org.x.BarTest\"",
                                             "\"class\":\"org.x.SilentTest\"",
                                             "\"missing\":[]",
                                             "\"drift\":[]");
        var result = harness.get("/jobs/" + job + "/result").body();
        assertThat(result).contains("\"verdict\":\"PASSED\"", "\"testcases\":2")
                          .doesNotContain("manifest-drift");
    }

    @Test
    void resubmit_deduplicates_samePassedJob() throws Exception {
        var first = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "dedup", "verify")));
        harness.waitForState(first, "PASSED");

        var again = harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "dedup", "verify"));

        assertThat(again.body()).isEqualTo("{\"job\":" + first + ",\"state\":\"PASSED\",\"deduplicated\":true}");
        var fresh = harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "dedup", "verify").replace("\"source\"", "\"fresh\":true,\"source\""));
        assertThat(jobOf(fresh)).isNotEqualTo(first);
    }

    @Test
    void refusedSubmit_persistsRefusedJob_withIdIn422() throws Exception {
        var refused = harness.post("/jobs", mvnJob(BY_MAIN, "verify", "-Dmaven.repo.local=/tmp/x"));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body()).contains("\"refused\":\"R-M2\"");
        var job = jobOf(refused);
        var view = harness.get("/jobs/" + job);
        assertThat(view.body()).contains("\"state\":\"REFUSED\"", "\"reason\":\"R-M2\"", "\"sha\":\"" + ControllerHarness.SHA_A + "\"");
        assertThat(harness.get("/jobs/" + job + "/result").status()).isEqualTo(404);
    }

    /// m4-design §5.3, maintainer ruling Q-M4-11: an argv off the build-only allowlist is refused at submit through the real
    /// store and recorded `REFUSED(R-EFFECT)`.
    @ParameterizedTest
    @ValueSource(strings = {"gpg:sign-and-deploy-file",
                            "nexus-staging:deploy",
                            "nexus-staging:release",
                            "central-publishing:publish",
                            "scm-publish:publish-scm",
                            "dockerfile:push",
                            "docker:build -DpushImage",
                            "jreleaser:full-release",
                            "jreleaser:deploy",
                            "cargo:deploy",
                            "k8s:apply",
                            "invoker:run",
                            "site:deploy@x",
                            "jib:build@x",
                            "clean deploy",
                            "-Pevil verify",
                            "-Drelease=true install",
                            "-Dsurefire.reportsDirectory=/x verify"})
    void refusedSubmit_rEffect_forArgvOffTheAllowlist(String args) throws Exception {
        var refused = harness.post("/jobs", mvnJob(BY_MAIN, args.split(" ")));

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.body()).contains("\"refused\":\"R-EFFECT\"");
        var view = harness.get("/jobs/" + jobOf(refused));
        assertThat(view.body()).contains("\"state\":\"REFUSED\"", "\"reason\":\"R-EFFECT\"");
    }

    @Test
    void cancel_runningJob_killsUnitAndEndsCancelled() throws Exception {
        harness.worker.respondWith(request -> request instanceof Message.CancelUnit cancel
                                              ? List.of(new Message.Ack(some("CANCELLING")),
                                                        cancelledExit(cancel.job()),
                                                        emptyReport(cancel.job()))
                                              : harness.respond(request));
        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "held", "verify")));
        harness.waitForState(job, "RUNNING");

        var cancel = harness.post("/jobs/" + job + "/cancel", "");

        assertThat(cancel.status()).isEqualTo(200);
        assertThat(cancel.body()).isEqualTo("{\"state\":\"CANCELLING\"}");
        harness.waitForState(job, "CANCELLED");
        assertThat(harness.get("/jobs/" + job + "/result").body()).contains("\"verdict\":\"CANCELLED\"");
    }

    /// Ruling R-13, amended: a start that cannot be recorded (here a start row that does not parse, dropped at once)
    /// leaves a unit that runs; it and its job show UNKNOWN, never QUEUED, until the exit facts complete it normally.
    @Test
    void droppedStart_jobAndUnitShowUnknown_thenTheExitCompletesIt() throws Exception {
        harness.worker.respondWith(this::startUnrecordable);
        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "lost-start", "verify")));

        var view = harness.waitForState(job, "UNKNOWN");

        assertThat(view).contains("\"name\":\"main\",\"state\":\"UNKNOWN\"");
        assertThat(harness.get("/jobs?state=QUEUED").body()).doesNotContain("\"job\":" + job + ",");
        harness.worker.send(ControllerHarness.exited(job));
        harness.worker.send(ControllerHarness.report(job));
        harness.waitForState(job, "PASSED");
        assertThat(harness.get("/jobs/" + job + "/result").body()).contains("\"verdict\":\"PASSED\"");
    }

    @Test
    void cancel_unknownStateJob_killsTheUnit_endsCancelled() throws Exception {
        harness.worker.respondWith(request -> request instanceof Message.CancelUnit cancel
                                              ? List.of(new Message.Ack(some("CANCELLING")),
                                                        cancelledExit(cancel.job()),
                                                        emptyReport(cancel.job()))
                                              : startUnrecordable(request));
        var job = jobOf(harness.post("/jobs", mvnJob(BY_MAIN, "-pl", "lost-start", "-q", "verify")));
        harness.waitForState(job, "UNKNOWN");

        var cancel = harness.post("/jobs/" + job + "/cancel", "");

        assertThat(cancel.body()).isEqualTo("{\"state\":\"CANCELLING\"}");
        harness.waitForState(job, "CANCELLED");
    }

    /// A unit whose args contain `lost-start` starts with marker epoch 0, which does not parse, and keeps running.
    private List<Message> startUnrecordable(Message request) {
        return request instanceof Message.StartUnit start && start.steps().stream().anyMatch(step -> step.contains("lost-start"))
               ? List.of(new Message.UnitStarted(start.job(), start.unit(), 1, "c" + start.job(), "sha256:img", 0L, Instant.now(),
                                                 ControllerHarness.facts(), none()))
               : harness.respond(request);
    }

    @Test
    void cancel_unknownJob_answers404() throws Exception {
        assertThat(harness.post("/jobs/999999/cancel", "").status()).isEqualTo(404);
        assertThat(harness.get("/jobs/999999").status()).isEqualTo(404);
        assertThat(harness.get("/jobs/abc").status()).isEqualTo(400);
    }

    @Test
    void hosts_showsReactorsAsForeignPlusActive() throws Exception {
        var hosts = harness.get("/hosts");

        assertThat(hosts.status()).isEqualTo(200);
        assertThat(hosts.body()).contains("\"name\":\"bb\"", "\"state\":\"UP\"", "\"cap\":5", "\"foreign\":0");
    }

    @Test
    void list_filtersByState() throws Exception {
        harness.post("/jobs", mvnJob(BY_MAIN, "verify", "-Dmaven.repo.local=/y"));

        var refused = harness.get("/jobs?state=REFUSED");

        assertThat(refused.status()).isEqualTo(200);
        assertThat(refused.body()).contains("\"state\":\"REFUSED\"").doesNotContain("\"state\":\"PASSED\"");
        assertThat(harness.get("/jobs?limit=0").status()).isEqualTo(400);
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static Message cancelledExit(long job) {
        return new Message.UnitExited(job, "main", 1, 137, some(KilledBy.CANCEL), Instant.now(),
                                      new UnitEndFactsDto(ControllerHarness.facts(), none(), Instant.now(), Map.of(), ControllerHarness.LEFTOVERS, none()),
                                      0, 0, 0, none(), none());
    }

    private static Message emptyReport(long job) {
        return new Message.UnitReport(job, "main", 1, 0, true, List.of());
    }
}
