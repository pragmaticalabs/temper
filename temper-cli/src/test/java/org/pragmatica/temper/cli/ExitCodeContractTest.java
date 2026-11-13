package org.pragmatica.temper.cli;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.merge.GhMerge;
import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.diff.KeyOutcome;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.CancelResponse;
import org.pragmatica.temper.protocol.api.ClaimResponse;
import org.pragmatica.temper.protocol.api.DiffAnswer;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.KnownDto;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;
import org.pragmatica.temper.protocol.api.SubmitRequest;
import org.pragmatica.temper.protocol.api.SubscriberView;
import org.pragmatica.temper.protocol.json.TemperJson;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.cli.FakeController.Reply;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;


/// A3 (m0-design §9): a table over every row of spec §8.1 plus the Q-15 rulings, each run as a whole CLI
/// invocation against the in-process fake controller (real JDK HTTP client, real token file).
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ExitCodeContractTest {
    @FunctionalInterface
    interface Scenario {
        CliHarness.Run run(FakeController controller, CliHarness cli) throws IOException;
    }

    record Row(String name, ExitCode exit, Optional<String> line, Scenario scenario) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final String MODULE_TEST_SHA = "submit module-test --pl core --test Foo* --sha " + Fixtures.SHA;

    @TempDir
    Path home;

    /// The controller's answer to the read token on a write route (D3).
    private static final String FORBIDDEN = "{\"error\":\"forbidden: read-only token\"}";

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

    static Stream<Row> rows() {
        return Stream.of(new Row("0 submit accepted",
                                 ExitCode.ACCEPTED,
                                 Optional.of("submit 41 QUEUED sha=0cf5631 recipe=module-test deduplicated=false"),
                                 (controller, cli) -> {
                                     controller.onJson("POST /jobs",
                                                       Fixtures.submitted(41, JobState.QUEUED, false))
                                               .onJson("GET /jobs/41",
                                                       Fixtures.job(41, JobState.QUEUED));

                                     return cli.run(MODULE_TEST_SHA.split(" "));
                                 }),
                         new Row("0 wait PASSED", ExitCode.PASSED, Optional.empty(), waitFor("PASSED")),
                         new Row("1 wait FAILED", ExitCode.FAILED, Optional.empty(), waitFor("FAILED")),
                         new Row("2 submit refused, job id in the line (Q-16)",
                                 ExitCode.REFUSED,
                                 Optional.of("submit - REFUSED code=R-M2 detail=\"-Dmaven.repo.local is not allowed\" job=42"),
                                 (controller, cli) -> {
                                     controller.on("POST /jobs",
                                                   Reply.json(422,
                                                              controller.write(Fixtures.refused("R-M2",
                                                                                                "-Dmaven.repo.local is not allowed",
                                                                                                some(42L)))));

                                     return cli.run(MODULE_TEST_SHA.split(" "));
                                 }),
                         new Row("2 wait on a refused job",
                                 ExitCode.REFUSED,
                                 Optional.of("wait 42 REFUSED reason=R-M2"),
                                 (controller, cli) -> {
                                     controller.onJson("GET /jobs/42", Fixtures.job(42, JobState.REFUSED, some("R-M2")));

                                     return cli.run("wait", "42");
                                 }),
                         new Row("3 wait EMPTY", ExitCode.EMPTY, Optional.empty(), waitFor("EMPTY")),
                         new Row("4 wait VOID", ExitCode.VOID, Optional.empty(), waitFor("VOID")),
                         new Row("5 wait ERROR", ExitCode.ERROR, Optional.empty(), waitFor("ERROR")),
                         new Row("5 log with the host down (503, Q-15)",
                                 ExitCode.ERROR,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /jobs/41/log", Reply.json(503, "{\"error\":\"host down\"}"));

                                     return cli.run("log", "41");
                                 }),
                         new Row("6 wait CANCELLED", ExitCode.CANCELLED, Optional.empty(), waitFor("CANCELLED")),
                         new Row("7 wait timeout shows the current state",
                                 ExitCode.WAIT_TIMEOUT,
                                 Optional.of("wait 41 RUNNING"),
                                 (controller, cli) -> {
                                     controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.RUNNING));

                                     return cli.run("wait", "41", "--timeout", "10s");
                                 }),
                         new Row("9 result before terminal (Q-15)",
                                 ExitCode.PENDING,
                                 Optional.of("result 41 RUNNING"),
                                 (controller, cli) -> {
                                     controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.RUNNING));

                                     return cli.run("result", "41");
                                 }),
                         new Row("10 submit deduplicated, id printed",
                                 ExitCode.DEDUPLICATED,
                                 Optional.of("submit 40 PASSED sha=0cf5631 recipe=mvn deduplicated=true"),
                                 (controller, cli) -> {
                                     controller.onJson("POST /jobs",
                                                       Fixtures.submitted(40, JobState.PASSED, true))
                                               .onJson("GET /jobs/40",
                                                       Fixtures.job(40, JobState.PASSED));

                                     return cli.run("submit", "mvn", "--ref", "main", "--", "-pl", "core", "verify");
                                 }),
                         new Row("64 unknown command",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> cli.run("frobnicate", "41")),
                         new Row("64 submit mutate is not run yet",
                                 ExitCode.USAGE,
                                 Optional.of("submit - USAGE not in M0"),
                                 (controller, cli) -> cli.run("submit", "mutate", "--ref", "main")),
                         new Row("2 refusal add of a code that exists (HTTP 409, m1-design §6)",
                                 ExitCode.REFUSED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("POST /refusals",
                                                   Reply.json(409, "{\"error\":\"Refusal R-9 exists\"}"));

                                     return cli.run("refusal",
                                                    "add",
                                                    "R-9",
                                                    "--matcher",
                                                    matcherFile(controller, cli).toString(),
                                                    "--reason",
                                                    "why");
                                 }),
                         new Row("0 subscribe a job, its state and the resulting scope (m2-design §2.4)",
                                 ExitCode.ACCEPTED,
                                 Optional.of("subscribe cto ADDED job:41 state=RUNNING selectors=job:41"),
                                 (controller, cli) -> {
                                     controller.on("PUT /subscribers/cto/selectors",
                                                   Reply.json(200,
                                                              controller.write(SubscriberView.subscriberView("cto",
                                                                                                             List.of("job:41"),
                                                                                                             7))))
                                               .onJson("GET /jobs/41",
                                                       Fixtures.job(41, JobState.RUNNING));

                                     return cli.run("subscribe", "41", "--subscriber", "cto");
                                 }),
                         new Row("64 subscribe a short SHA, refused before any call (F3)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     // Both routes answer 200, so a short SHA sent anyway would exit 0, not 64.
                                     controller.on("PUT /subscribers/cto/selectors",
                                                   Reply.json(200,
                                                              controller.write(SubscriberView.subscriberView("cto",
                                                                                                             List.of("ref:0cf5631"),
                                                                                                             7))))
                                               .on("GET /jobs",
                                                   Reply.json(200, "[]"));

                                     return cli.run("subscribe", "0cf5631", "--subscriber", "cto");
                                 }),
                         new Row("64 subscribe an unknown job (HTTP 404)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("PUT /subscribers/cto/selectors",
                                                   Reply.json(404, "{\"error\":\"Not found: job 41\"}"));

                                     return cli.run("subscribe", "41", "--subscriber", "cto");
                                 }),
                         new Row("0 known list (m2-design §4.4)",
                                 ExitCode.ACCEPTED,
                                 Optional.of("known 12 ACTIVE test=org.x.FooTest issue=1679 until=\"#1679 closes\" reason=\"flaky until #1679\" by=cto"),
                                 (controller, cli) -> {
                                     controller.onJson("GET /known",
                                                       (Object) new KnownDto[]{Fixtures.known(12,
                                                                                              "org.x.FooTest",
                                                                                              none())});

                                     return cli.run("known", "list");
                                 }),
                         new Row("2 known add of a test with an active entry (HTTP 409)",
                                 ExitCode.REFUSED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("POST /known",
                                                   Reply.json(409,
                                                              "{\"error\":\"An active known entry for org.x.FooTest already exists\"}"));

                                     return cli.run("known",
                                                    "add",
                                                    "org.x.FooTest",
                                                    "--issue",
                                                    "1679",
                                                    "--reason",
                                                    "flaky",
                                                    "--until",
                                                    "#1679 closes");
                                 }),
                         new Row("64 known add rejected (HTTP 400)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("POST /known",
                                                   Reply.json(400, "{\"error\":\"Invalid testId: 'org.x.'\"}"));

                                     return cli.run("known",
                                                    "add",
                                                    "org.x.",
                                                    "--issue",
                                                    "1679",
                                                    "--reason",
                                                    "flaky",
                                                    "--until",
                                                    "#1679 closes");
                                 }),
                         new Row("64 known expire of an unknown id (HTTP 404)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> cli.run("known", "expire", "404")),
                         new Row("64 known list --ledger is not run yet (ruling R-M2-3)",
                                 ExitCode.USAGE,
                                 Optional.of("known - USAGE not in M0"),
                                 (controller, cli) -> cli.run("known", "list", "--ledger")),
                         new Row("0 rerun accepted (m2-design §5.5)",
                                 ExitCode.ACCEPTED,
                                 Optional.of(RerunAgainstControllerTest.ACCEPTED_LINE),
                                 (controller, cli) -> {
                                     controller.onJson("POST /jobs/4711/rerun",
                                                       Fixtures.submitted(4740, JobState.QUEUED, false))
                                               .onJson("GET /jobs/4740",
                                                       RerunAgainstControllerTest.rerunJob(4740, JobState.QUEUED));

                                     return cli.run("rerun", "4711");
                                 }),
                         new Row("10 rerun deduplicated onto the first rerun",
                                 ExitCode.DEDUPLICATED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.onJson("POST /jobs/4711/rerun",
                                                       Fixtures.submitted(4740, JobState.RUNNING, true))
                                               .onJson("GET /jobs/4740",
                                                       RerunAgainstControllerTest.rerunJob(4740, JobState.RUNNING));

                                     return cli.run("rerun", "4711");
                                 }),
                         new Row("2 rerun refused (REFUSED(rerun))",
                                 ExitCode.REFUSED,
                                 Optional.of("rerun - REFUSED code=rerun detail=\"all failing classes are KNOWN\" job=4741"),
                                 (controller, cli) -> {
                                     controller.on("POST /jobs/4711/rerun",
                                                   Reply.json(422,
                                                              controller.write(Fixtures.refused("rerun",
                                                                                                "all failing classes are KNOWN",
                                                                                                some(4741L)))));

                                     return cli.run("rerun", "4711");
                                 }),
                         new Row("64 rerun of an unknown job (HTTP 404)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> cli.run("rerun", "999")),
                         new Row("64 rerun usage: no job",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> cli.run("rerun")),
                         new Row("0 submit gate accepted",
                                 ExitCode.ACCEPTED,
                                 Optional.of("submit 43 QUEUED sha=0cf5631 recipe=gate deduplicated=false"),
                                 (controller, cli) -> {
                                     controller.onJson("POST /jobs",
                                                       Fixtures.submitted(43, JobState.QUEUED, false))
                                               .onJson("GET /jobs/43",
                                                       Fixtures.job(43, JobState.QUEUED));

                                     return cli.run("submit", "gate", "--sha", Fixtures.SHA, "--fresh", "--prio", "5");
                                 }),
                         new Row("64 token file missing",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     Files.delete(cli.home().resolve(".config/temper/token"));

                                     return cli.run("result", "41");
                                 }),
                         new Row("64 controller rejects the request (HTTP 400)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /jobs/41/result", Reply.json(400, "{\"error\":\"bad job id\"}"));

                                     return cli.run("result", "41");
                                 }),
                         new Row("64 token file unreadable",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     unreadable(cli.home().resolve(".config/temper/token"));

                                     return cli.run("result", "41");
                                 }),
                         new Row("69 controller unreachable",
                                 ExitCode.UNREACHABLE,
                                 Optional.empty(),
                                 (controller, cli) -> cli.env("TEMPER_URL",
                                                              "http://127.0.0.1:" + closedPort())
                                                         .run("result", "41")),
                         new Row("0 alarm-check, every condition clear",
                                 ExitCode.ALARM_CLEAR,
                                 Optional.empty(),
                                 (controller, cli) -> alarmCheck(controller, cli, "UP", "sink = \"stdout\"\n")),
                         new Row("3 alarm-check, a condition firing and delivered",
                                 ExitCode.ALARM_FIRING,
                                 Optional.empty(),
                                 (controller, cli) -> alarmCheck(controller, cli, "DOWN", "sink = \"stdout\"\n")),
                         new Row("2 alarm-check, the sink failed to deliver",
                                 ExitCode.ALARM_UNDELIVERED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     Files.writeString(cli.home().resolve(".config/temper/alarm-token"),
                                                       "ghp_token\n");

                                     return alarmCheck(controller,
                                                       cli,
                                                       "UP",
                                                       "repo = \"a/b\"\ngithub-api = \"http://127.0.0.1:" + closedPort()
                                                      + "\"\n");
                                 }),
                         new Row("77 read-only token on a write route (HTTP 403, D3)",
                                 ExitCode.UNAUTHORIZED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.onJson("GET /jobs/41",
                                                       Fixtures.job(41, JobState.RUNNING))
                                               .on("POST /jobs/41/cancel",
                                                   FakeController.Reply.json(403, FORBIDDEN));

                                     return cli.run("cancel", "41");
                                 }),
                         new Row("0 gate PASSED",
                                 ExitCode.PASSED,
                                 Optional.of("gate 0cf5631 PASSED job=4711 judge=1a2b3c4 testcases=12 failed=0"),
                                 gate(Fixtures.gateVerdict(GateAnswer.PASSED, 0, List.of()))),
                         new Row("1 gate FAILED",
                                 ExitCode.FAILED,
                                 Optional.of("gate 0cf5631 FAILED job=4711 judge=1a2b3c4 testcases=12 failed=1 KNOWN(#1679)"),
                                 gate(Fixtures.gateVerdict(GateAnswer.FAILED, 1, List.of("KNOWN(#1679)")))),
                         new Row("3 gate EMPTY",
                                 ExitCode.EMPTY,
                                 Optional.of("gate 0cf5631 EMPTY job=4711 judge=1a2b3c4 testcases=12 failed=0"),
                                 gate(Fixtures.gateVerdict(GateAnswer.EMPTY, 0, List.of()))),
                         new Row("11 gate CONFLICT, never green nor a plain red (R-M2-9)",
                                 ExitCode.CONFLICT,
                                 Optional.of("gate 0cf5631 CONFLICT judge=1a2b3c4 jobs=4711:FAILED,4690:PASSED"),
                                 gate(Fixtures.gateConflict(none()))),
                         new Row("8 gate NONE",
                                 ExitCode.NONE,
                                 Optional.of("gate 0cf5631 NONE unjudged=4702"),
                                 gate(Fixtures.gateNone())),
                         new Row("9 gate RUNNING",
                                 ExitCode.PENDING,
                                 Optional.of("gate 0cf5631 RUNNING job=4720 judge=1a2b3c4 state=QUEUED previous=4711:FAILED"),
                                 gate(Fixtures.gateRunning(JobState.QUEUED))),
                         new Row("7 gate --wait timeout on an answer that stays RUNNING",
                                 ExitCode.WAIT_TIMEOUT,
                                 Optional.of("gate 0cf5631 RUNNING job=4720 judge=1a2b3c4 state=RUNNING previous=4711:FAILED"),
                                 (controller, cli) -> {
                                     controller.onJson("GET /gate/0cf5631", Fixtures.gateRunning(JobState.RUNNING));

                                     return cli.run("gate", "0cf5631", "--wait", "--timeout", "10s");
                                 }),
                         new Row("64 gate prefix of Gates at two SHAs (HTTP 400)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /gate/0cf5631",
                                                   Reply.json(400, "{\"error\":\"SHA prefix 0cf5631 matches a,b\"}"));

                                     return cli.run("gate", "0cf5631");
                                 }),
                         new Row("0 diff with nothing newly failing and no added failing test",
                                 ExitCode.PASSED,
                                 Optional.empty(),
                                 diff(Fixtures.diffView(List.of(Fixtures.test("org.x.NewTest", "ok", KeyOutcome.PASSED)),
                                                        List.of(),
                                                        none()))),
                         new Row("1 diff with an added failing test, nothing newly failing (m2-design §3.1)",
                                 ExitCode.FAILED,
                                 Optional.empty(),
                                 diff(Fixtures.diffView(List.of(Fixtures.test("org.x.FooTest",
                                                                              "newCase",
                                                                              KeyOutcome.FAILED)),
                                                        List.of(),
                                                        none()))),
                         new Row("1 diff with a newly failing test",
                                 ExitCode.FAILED,
                                 Optional.empty(),
                                 diff(Fixtures.diffView(List.of(),
                                                        List.of(Fixtures.test("org.x.BarTest",
                                                                              "flips",
                                                                              KeyOutcome.FAILED)),
                                                        none()))),
                         new Row("1 diff with an EMPTY B, every A test removed (R-M2-13)",
                                 ExitCode.FAILED,
                                 Optional.empty(),
                                 diff(Fixtures.diffEmptyB())),
                         new Row("8 diff with a side not judged (R-M2-13)",
                                 ExitCode.NONE,
                                 Optional.of("diff 4690 4711 NONE not-judged=a:VOID detail=\"side not judged: VOID\""),
                                 diff(Fixtures.diffRefused(DiffObstacle.NOT_JUDGED, DiffOutcome.Side.A, JobState.VOID))),
                         new Row("8 diff with a pruned side (F16)",
                                 ExitCode.NONE,
                                 Optional.of("diff 4690 4711 NONE pruned=a"),
                                 diff(Fixtures.diffRefused(DiffObstacle.PRUNED, DiffOutcome.Side.A, JobState.PASSED))),
                         new Row("8 diff --base on a CONFLICT (Q-M2-26)",
                                 ExitCode.NONE,
                                 Optional.of("diff - 4711 NONE base=conflict sha=0cf5631 jobs=4711:FAILED,4690:PASSED"),
                                 diffBase(Fixtures.diffBaseRefused(DiffObstacle.BASE_CONFLICT))),
                         new Row("8 diff --base with no Gate at the SHA",
                                 ExitCode.NONE,
                                 Optional.of("diff - 4711 NONE base=none sha=0cf5631"),
                                 diffBase(Fixtures.diffBaseRefused(DiffObstacle.NO_BASE))),
                         new Row("9 diff with a side not terminal",
                                 ExitCode.PENDING,
                                 Optional.of("diff 4690 4711 RUNNING not-terminal=b:QUEUED"),
                                 diff(Fixtures.diffRefused(DiffObstacle.NOT_TERMINAL,
                                                           DiffOutcome.Side.B,
                                                           JobState.QUEUED))),
                         new Row("64 diff of an unknown job (HTTP 404)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /diff", Reply.json(404, "{\"error\":\"Not found: job 4799\"}"));

                                     return cli.run("diff", "4690", "4799");
                                 }),
                         new Row("77 bad token (HTTP 401, Q-15)",
                                 ExitCode.UNAUTHORIZED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     Files.writeString(cli.home().resolve(".config/temper/token"),
                                                       "wrong-token");

                                     return cli.run("result", "41");
                                 }),
                         new Row("0 task claim: the claim line; the token only in the lease file (m3-design §11.2)",
                                 ExitCode.ACCEPTED,
                                 Optional.of("task 42 CODE LEASED epoch=7 readPoint=0cf5631 deadline=2026-10-20T16:00:00Z"),
                                 (controller, cli) -> {
                                     controller.on("POST /tasks/42/claim",
                                                   Reply.json(200, controller.write(claimAnswer())));

                                     return cli.run("task", "claim", "42");
                                 }),
                         new Row("2 task create refused (HTTP 422 base-unknown)",
                                 ExitCode.REFUSED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("POST /tasks", Reply.json(422, "{\"error\":\"base-unknown\"}"));

                                     return cli.run("task",
                                                    "create",
                                                    "--slug",
                                                    "s",
                                                    "--title",
                                                    "T",
                                                    "--repo",
                                                    "a/b",
                                                    "--base",
                                                    "nope",
                                                    "--branch",
                                                    "agent/s",
                                                    "--brief",
                                                    "briefs/s.md");
                                 }),
                         new Row("8 task claim --stage with nothing ready (HTTP 204)",
                                 ExitCode.NONE,
                                 Optional.of("task - NONE nothing ready"),
                                 (controller, cli) -> {
                                     controller.on("POST /tasks/claim", Reply.json(204, ""));

                                     return cli.run("task", "claim", "--stage", "VERIFY");
                                 }),
                         new Row("9 task claim of a task not ready (HTTP 409)",
                                 ExitCode.PENDING,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("POST /tasks/42/claim", Reply.json(409, "{\"error\":\"not-ready\"}"));

                                     return cli.run("task", "claim", "42");
                                 }),
                         new Row("9 task rule of a task not held (HTTP 409)",
                                 ExitCode.PENDING,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("POST /tasks/42/rule", Reply.json(409, "{\"error\":\"not-held\"}"));

                                     return cli.run("task", "rule", "42", "--option", "a", "--text", "go");
                                 }),
                         new Row("12 task heartbeat with the lease lost (HTTP 409 lease-lost)",
                                 ExitCode.LEASE_LOST,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     leaseFile(cli, 42, 7);
                                     controller.on("POST /tasks/42/heartbeat",
                                                   Reply.json(409, "{\"error\":\"lease-lost\"}"));

                                     return cli.run("task", "heartbeat", "42");
                                 }),
                         new Row("12 submit --task with the lease lost (HTTP 409 lease-lost)",
                                 ExitCode.LEASE_LOST,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     leaseFile(cli, 42, 7);
                                     controller.on("POST /jobs", Reply.json(409, "{\"error\":\"lease-lost\"}"));

                                     return cli.run((MODULE_TEST_SHA + " --task 42").split(" "));
                                 }),
                         new Row("13 a judged CLI against a controller of another judged build (D-6)",
                                 ExitCode.VERSION_SKEW,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.temperVersion(Optional.of("0.1.0-SNAPSHOT+222222222222"))
                                               .on("GET /refusals",
                                                   Reply.json(200, "[]"));

                                     return cli.version("0.1.0-SNAPSHOT+111111111111")
                                               .run("refusal", "list");
                                 }),
                         new Row("3 events --until with no heartbeat line for 110 s",
                                 ExitCode.NO_HEARTBEAT,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /fleet/events/after",
                                                   FleetFixtures.reply(FleetFixtures.event(43,
                                                                                           "pr-state",
                                                                                           "a/b#1",
                                                                                           false,
                                                                                           "{}")));

                                     return cli.run("events",
                                                    "--until",
                                                    "check-concluded",
                                                    "--after",
                                                    FleetFixtures.EPOCH + ":42");
                                 }),
                         new Row("4 events --until with kiln:github stale",
                                 ExitCode.SOURCE_STALE,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /fleet/events/after",
                                                   FleetFixtures.reply(FleetFixtures.heartbeat(FleetFixtures.EPOCH,
                                                                                               42,
                                                                                               FleetFixtures.staleSource("kiln:github"),
                                                                                               true)));

                                     return cli.run("events",
                                                    "--until",
                                                    "check-concluded",
                                                    "--after",
                                                    FleetFixtures.EPOCH + ":42");
                                 }),
                         new Row("5 events --until --timeout reached",
                                 ExitCode.EVENTS_TIMEOUT,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /fleet/events/after",
                                                   FleetFixtures.reply(FleetFixtures.heartbeat(42)));

                                     return cli.run("events",
                                                    "--until",
                                                    "check-concluded",
                                                    "--after",
                                                    FleetFixtures.EPOCH + ":42",
                                                    "--timeout",
                                                    "3s");
                                 }),
                         new Row("6 events --until with the controller's self-check failed",
                                 ExitCode.SELF_CHECK_FAILED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /fleet/events/after",
                                                   FleetFixtures.reply(FleetFixtures.heartbeat(FleetFixtures.EPOCH,
                                                                                               42,
                                                                                               FleetFixtures.FRESH,
                                                                                               false,
                                                                                               "loopback")));

                                     return cli.run("events",
                                                    "--until",
                                                    "check-concluded",
                                                    "--after",
                                                    FleetFixtures.EPOCH + ":42");
                                 }),
                         new Row("7 events --until --after an epoch the controller no longer has (HTTP 409)",
                                 ExitCode.EPOCH_CHANGED,
                                 Optional.empty(),
                                 (controller, cli) -> {
                                     controller.on("GET /fleet/events/after",
                                                   Reply.json(409,
                                                              "{\"error\":\"EPOCH_CHANGED\",\"epoch\":\"" + FleetFixtures.OTHER_EPOCH
                                                             + "\"}"));

                                     return cli.run("events",
                                                    "--until",
                                                    "check-concluded",
                                                    "--after",
                                                    FleetFixtures.EPOCH + ":42");
                                 }),
                         new Row("3 merge whose gh pr merge failed after every check passed",
                                 ExitCode.MERGE_FAILED,
                                 Optional.empty(),
                                 ExitCodeContractTest::mergeFailed),
                         new Row("64 task heartbeat with no lease held",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> cli.run("task", "heartbeat", "42")),
                         new Row("64 task show of an unknown task (HTTP 404)",
                                 ExitCode.USAGE,
                                 Optional.empty(),
                                 (controller, cli) -> cli.run("task", "show", "404")));
    }

    /// A claim of task 42 at epoch 7, its token `lease-token-42`.
    static ClaimResponse claimAnswer() {
        return new ClaimResponse(42,
                                 "s",
                                 "CODE",
                                 1,
                                 7,
                                 "lease-token-42",
                                 Fixtures.SHA,
                                 0,
                                 Instant.parse("2026-10-20T10:30:00Z"),
                                 Instant.parse("2026-10-20T16:00:00Z"),
                                 "a/b",
                                 "file:///nowhere",
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 null,
                                 List.of());
    }

    /// The lease file of `task` at `epoch` (0600), as `task claim` writes it.
    private static void leaseFile(CliHarness cli, long task, long epoch) throws IOException {
        var dir = Files.createDirectories(cli.home().resolve(".config/temper/leases"));
        var file = Files.writeString(dir.resolve(task + "-" + epoch), "lease-token-" + task + "\n");

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    /// `gate 0cf5631` answered `view`.
    private static Scenario gate(GateView view) {
        return (controller, cli) -> {
            controller.onJson("GET /gate/0cf5631", view);

            return cli.run("gate", "0cf5631");
        };
    }

    /// `diff 4690 4711` answered `answer`.
    private static Scenario diff(DiffAnswer answer) {
        return (controller, cli) -> {
            controller.onJson("GET /diff", answer);

            return cli.run("diff", "4690", "4711");
        };
    }

    /// `diff 4711 --base 0cf5631` answered `answer`.
    private static Scenario diffBase(DiffAnswer answer) {
        return (controller, cli) -> {
            controller.onJson("GET /diff", answer);

            return cli.run("diff", "4711", "--base", "0cf5631");
        };
    }

    /// The job is RUNNING at the first poll and terminal at the second; its result record carries `verdict`.
    private static Scenario waitFor(String verdict) {
        return (controller, cli) -> {
            controller.onJson("GET /jobs/41",
                              Fixtures.job(41, JobState.RUNNING),
                              Fixtures.job(41,
                                           JobState.valueOf(verdict)))
                      .onJson("GET /jobs/41/result",
                              Fixtures.result(41,
                                              verdict,
                                              none(),
                                              0,
                                              0,
                                              0));

            return cli.run("wait", "41");
        };
    }

    /// `alarm-check` against a fresh dump and one host in `hostState`, measuring HOME with a 1 GiB threshold.
    private static CliHarness.Run alarmCheck(FakeController controller, CliHarness cli, String hostState, String keys) throws IOException {
        controller.onJson("GET /maintenance",
                          Fixtures.maintenance(Fixtures.AT.minusSeconds(3600),
                                               true,
                                               some(Fixtures.AT.minusSeconds(3600))))
                  .onJson("GET /hosts",
                          Fixtures.hosts(hostState))
                  .onJson("GET /fleet/alarms",
                          Fixtures.noAlarms());
        Files.writeString(cli.home().resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(cli.home())
                         + "host = \"bigboy\"\nmin-free-gib = 1\npaths = [\"" + cli.home()
                         + "\"]\n" + keys);

        return cli.run("alarm-check");
    }

    /// A valid `--matcher` file in HOME, written from the protocol DTO.
    static Path matcherFile(FakeController controller, CliHarness cli) throws IOException {
        return Files.writeString(cli.home().resolve("matcher.json"),
                                 controller.write(new RefusalMatcherDto.ItTestRequired("forge-tests")));
    }

    /// Mode 000. Root reads it anyway, which would make the row pass for the wrong reason: skip there.
    private static void unreadable(Path file) throws IOException {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(file), "running as root: a mode-000 file is still readable");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void exitCode_matchesSpecTable_forEveryRow(Row row) throws IOException {
        var result = row.scenario().run(controller, cli);

        assertThat(result.exit()).as(result.err()).isEqualTo(row.exit().code());
        row.line().ifPresent(line -> assertThat(result.out()).containsExactly(line));
    }

    /// Every exit code of spec §8.1, Q-15, M2 (`gate`'s NONE 8 and CONFLICT 11, m2-design §1.5) and M3 (the task group's
    /// LEASE_LOST 12, m3-design §11.2), D-6 (VERSION_SKEW 13) and `events --until` (3 to 7) has a row.
    @Test
    void table_coversEveryExitCode() {
        var covered = rows().map(Row::exit).collect(Collectors.toCollection(() -> EnumSet.noneOf(ExitCode.class)));

        assertThat(covered).isEqualTo(EnumSet.allOf(ExitCode.class));
        assertThat(Arrays.stream(ExitCode.values()).map(ExitCode::code).distinct().sorted().toList()).containsExactly(0,
                                                                                                                      1,
                                                                                                                      2,
                                                                                                                      3,
                                                                                                                      4,
                                                                                                                      5,
                                                                                                                      6,
                                                                                                                      7,
                                                                                                                      8,
                                                                                                                      9,
                                                                                                                      10,
                                                                                                                      11,
                                                                                                                      12,
                                                                                                                      13,
                                                                                                                      64,
                                                                                                                      69,
                                                                                                                      77);
    }

    /// `--wait` polls while RUNNING and prints the first other answer, with its exit code; NONE and CONFLICT answer at
    /// once, without a second poll.
    @Test
    void gateWait_pollsWhileRunning_thenAnswers() {
        controller.onJson("GET /gate/0cf5631",
                          Fixtures.gateRunning(JobState.QUEUED),
                          Fixtures.gateRunning(JobState.RUNNING),
                          Fixtures.gateVerdict(GateAnswer.FAILED, 1, List.of()));
        var result = cli.run("gate", "0cf5631", "--wait");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).containsExactly("gate 0cf5631 FAILED job=4711 judge=1a2b3c4 testcases=12 failed=1");
        assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void gateWait_none_answersAtOnce() {
        controller.onJson("GET /gate/0cf5631", Fixtures.gateNone(), Fixtures.gateRunning(JobState.QUEUED));
        var result = cli.run("gate", "0cf5631", "--wait");

        assertThat(result.exit()).isEqualTo(8);
        assertThat(result.out()).containsExactly("gate 0cf5631 NONE unjudged=4702");
        assertThat(cli.time.pauses()).isEmpty();
        assertThat(controller.received()).hasSize(1);
    }

    @Test
    void gateWait_conflict_answersAtOnce() {
        controller.onJson("GET /gate/0cf5631", Fixtures.gateConflict(some(4720L)));
        var result = cli.run("gate", "0cf5631", "--wait");

        assertThat(result.exit()).isEqualTo(11);
        assertThat(result.out()).containsExactly("gate 0cf5631 CONFLICT judge=1a2b3c4 jobs=4711:FAILED,4690:PASSED running=4720");
        assertThat(cli.time.pauses()).isEmpty();
    }

    /// The CLI judges the SHA as the controller does: a bad one is 64 without a call.
    @Test
    void gate_badSha_isUsage_withoutContactingTheController() {
        var result = cli.run("gate", "0cf563");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void gate_json_printsTheGateView_withTheAnswersExitCode() {
        controller.onJson("GET /gate/0cf5631", Fixtures.gateConflict(none()));
        var result = cli.run("gate", "0cf5631", "--json");

        assertThat(result.exit()).isEqualTo(11);
        assertThat(String.join("", result.out())).contains("\"answer\":\"CONFLICT\"").contains("\"conflict\":[");
    }

    @Test
    void wait_printsVerdictLine_withCountsAndObservedFailures() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.FAILED))
                  .onJson("GET /jobs/41/result",
                          Fixtures.result(41,
                                          "FAILED",
                                          some("tests"),
                                          1,
                                          1,
                                          2));
        var result = cli.run("wait", "41");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).containsExactly("wait 41 FAILED reason=tests testcases=12 failed=2 observed-failures=2 evidence=missing");
    }

    @Test
    void wait_pollsEveryTwoSeconds_doublingToTen() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.QUEUED),
                          Fixtures.job(41, JobState.RUNNING),
                          Fixtures.job(41, JobState.RUNNING),
                          Fixtures.job(41, JobState.RUNNING),
                          Fixtures.job(41, JobState.RUNNING),
                          Fixtures.job(41, JobState.PASSED))
                  .onJson("GET /jobs/41/result",
                          Fixtures.result(41,
                                          "PASSED",
                                          none(),
                                          0,
                                          0,
                                          0));
        var result = cli.run("wait", "41");

        assertThat(result.exit()).isZero();
        assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(2),
                                                      Duration.ofSeconds(4),
                                                      Duration.ofSeconds(8),
                                                      Duration.ofSeconds(10),
                                                      Duration.ofSeconds(10));
    }

    @Test
    void waitTimeout_neverPausesPastTheDeadline() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.RUNNING));
        cli.run("wait", "41", "--timeout", "10s");
        assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(2),
                                                      Duration.ofSeconds(4),
                                                      Duration.ofSeconds(4));
    }

    @Test
    void result_printsVerdictLine_whenTerminal() {
        controller.onJson("GET /jobs/41/result", Fixtures.result(41, "PASSED", none(), 0, 0, 0));
        var result = cli.run("result", "41");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("result 41 PASSED testcases=12 failed=0 skipped=2 units=1 observed-failures=0 evidence=missing");
    }

    @Test
    void wait_printsManifestCountsAndStoredEvidence_whenTheJobHasAManifest() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.FAILED))
                  .onJson("GET /jobs/41/result",
                          Fixtures.manifestResult(41,
                                                  "FAILED",
                                                  some("missing-classes"),
                                                  2,
                                                  1,
                                                  Fixtures.STORED));
        var result = cli.run("wait", "41");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).containsExactly("wait 41 FAILED reason=missing-classes testcases=12 failed=0 observed-failures=0 missing=2 drift=1 evidence=stored");
    }

    @Test
    void result_printsManifestCountsAndEvidence_evenWhenZero() {
        controller.onJson("GET /jobs/41/result",
                          Fixtures.manifestResult(41, "PASSED", none(), 0, 0, ResultRecordDto.EvidenceDto.NOT_STORED));
        var result = cli.run("result", "41");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("result 41 PASSED testcases=12 failed=0 skipped=2 units=1 observed-failures=0 missing=0 drift=0 evidence=missing");
    }

    /// T1/T2 submit a Gate (m1-design §8.1): the request carries the gate recipe, the SHA, `fresh` and the priority.
    @Test
    void submit_gate_sendsGateRecipe() {
        controller.onJson("POST /jobs",
                          Fixtures.submitted(43, JobState.QUEUED, false))
                  .onJson("GET /jobs/43",
                          Fixtures.job(43, JobState.QUEUED));
        var result = cli.run("submit", "gate", "--sha", Fixtures.SHA, "--fresh", "--prio", "5");
        var sent = TemperJson.mapper()
                             .readString(controller.received().getFirst().body(),
                                         SubmitRequest.class)
                             .fold(cause -> fail(cause.message()),
                                   request -> request);

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(sent.recipe()).isEqualTo(new RecipeDto.Gate());
        assertThat(sent.source()).isEqualTo(new SourceDto.Pushed(none(), some(Fixtures.SHA)));
        assertThat(sent.fresh()).isEqualTo(some(true));
        assertThat(sent.priority()).isEqualTo(some(5));
    }

    /// D3: the controller's 403 to the read token is its own failure, not an unexpected status (which would be 69).
    @Test
    void cancel_withReadOnlyToken_exits77_andSaysForbidden() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.RUNNING))
                  .on("POST /jobs/41/cancel",
                      FakeController.Reply.json(403, FORBIDDEN));
        var result = cli.run("cancel", "41");

        assertThat(result.exit()).isEqualTo(77);
        assertThat(result.err()).contains(CliError.General.FORBIDDEN.message());
    }

    @Test
    void cancel_exitsZero_whenAccepted() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.RUNNING))
                  .onJson("POST /jobs/41/cancel",
                          CancelResponse.cancelResponse(JobState.CANCELLING));
        var result = cli.run("cancel", "41");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("cancel 41 CANCELLING");
    }

    @Test
    void cancel_printsVerdictAndExitsWithItsCode_whenAlreadyTerminal_withoutCancelling() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.FAILED));
        var result = cli.run("cancel", "41");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).containsExactly("cancel 41 FAILED");
        assertThat(controller.received()).extracting(FakeController.Received::method).containsExactly("GET");
    }

    @Test
    void cancel_ofAnAlreadyCancelledJob_exitsSix() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.CANCELLED));
        assertThat(cli.run("cancel", "41").exit()).isEqualTo(6);
    }

    @Test
    void cancel_ofAJobThatFinishedMeanwhile_exitsWithItsCode() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.RUNNING))
                  .onJson("POST /jobs/41/cancel",
                          CancelResponse.cancelResponse(JobState.PASSED));
        var result = cli.run("cancel", "41");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("cancel 41 PASSED");
    }

    @Test
    void cancel_ignoredByController_exits69() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.QUEUED))
                  .onJson("POST /jobs/41/cancel",
                          CancelResponse.cancelResponse(JobState.QUEUED));
        assertThat(cli.run("cancel", "41").exit()).isEqualTo(69);
    }

    /// R-13, amended: a job whose start was not recorded runs, so every line that shows its state says UNKNOWN.
    @Test
    void result_ofAnUnknownJob_printsUnknown_exitsPending() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.UNKNOWN));
        var result = cli.run("result", "41");

        assertThat(result.exit()).isEqualTo(9);
        assertThat(result.out()).containsExactly("result 41 UNKNOWN");
    }

    @Test
    void wait_timeoutOnAnUnknownJob_showsUnknown() {
        controller.onJson("GET /jobs/41", Fixtures.job(41, JobState.UNKNOWN));
        var result = cli.run("wait", "41", "--timeout", "10s");

        assertThat(result.exit()).isEqualTo(7);
        assertThat(result.out()).containsExactly("wait 41 UNKNOWN");
    }

    @Test
    void cancel_ofAnUnknownJob_exitsZero_whenAccepted() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.UNKNOWN))
                  .onJson("POST /jobs/41/cancel",
                          CancelResponse.cancelResponse(JobState.CANCELLING));
        var result = cli.run("cancel", "41");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("cancel 41 CANCELLING");
    }

    @Test
    void cancel_answeredUnknown_isIgnored_exits69() {
        controller.onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.UNKNOWN))
                  .onJson("POST /jobs/41/cancel",
                          CancelResponse.cancelResponse(JobState.UNKNOWN));
        assertThat(cli.run("cancel", "41").exit()).isEqualTo(69);
    }

    @Test
    void serviceUnavailable_outsideLog_isUnexpected69() {
        controller.on("GET /jobs/41/result", Reply.json(503, "{\"error\":\"busy\"}"));
        assertThat(cli.run("result", "41").exit()).isEqualTo(69);
    }

    @Test
    void wait_ridesOutTransient503s() {
        controller.on("GET /jobs/41",
                      Reply.json(503, "{}"),
                      Reply.json(503, "{}"),
                      Reply.json(200,
                                 controller.write(Fixtures.job(41, JobState.PASSED))))
                  .onJson("GET /jobs/41/result",
                          Fixtures.result(41,
                                          "PASSED",
                                          none(),
                                          0,
                                          0,
                                          0));
        assertThat(cli.run("wait", "41").exit()).isZero();
    }

    @Test
    void log_printsRawText_andPassesUnitAndTail() {
        controller.on("GET /jobs/41/log", Reply.text(200, "first line\nsecond line\n"));
        var result = cli.run("log", "41", "--unit", "build", "--tail", "50");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("first line", "second line");
        assertThat(controller.received().getLast().target()).isEqualTo("/api/v1/jobs/41/log?tail=50&unit=build");
    }

    @Test
    void submit_sendsBearerTokenAndOwner_andParsedRequest() {
        controller.onJson("POST /jobs",
                          Fixtures.submitted(41, JobState.QUEUED, false))
                  .onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.QUEUED));
        cli.run((MODULE_TEST_SHA + " --prio 5 --keep --unit-timeout PT90S").split(" "));
        var submit = controller.received().getFirst();

        assertThat(submit.authorization()).isEqualTo("Bearer " + CliHarness.TOKEN);
        assertThat(submit.body()).contains("\"submittedBy\":\"tester\"",
                                           "\"priority\":5",
                                           "\"keep\":true",
                                           "\"timeout\":\"PT90S\"",
                                           "\"source\":{\"pushed\":{",
                                           "\"sha\":\"" + Fixtures.SHA + "\"",
                                           "\"moduleTest\":{\"pl\":[\"core\"],\"test\":\"Foo*\"");
    }

    @Test
    void submit_shortSha_isUsageError_withoutContactingController() {
        var result = cli.run("submit", "mvn", "--sha", "0cf5631", "--", "verify");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err().lines()).singleElement()
                  .asString()
                  .startsWith("temper: Invalid submit request (")
                  .contains("sha");
        assertThat(controller.received()).isEmpty();
    }

    @Test
    void submit_withoutOwner_isUsageError() {
        cli.env("TEMPER_OWNER", "");
        var result = cli.run("submit", "mvn", "--ref", "main", "--", "verify");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("owner");
    }

    /// R-13, amended: a resubmit deduplicated onto a job whose start was not recorded prints the UNKNOWN the
    /// controller answers (its read side derives it past the start bound), never QUEUED.
    @Test
    void submit_deduplicatedOntoAnUnknownJob_printsUnknown() {
        controller.onJson("POST /jobs",
                          Fixtures.submitted(41, JobState.UNKNOWN, true))
                  .onJson("GET /jobs/41",
                          Fixtures.job(41, JobState.UNKNOWN));
        var result = cli.run("submit", "mvn", "--ref", "main", "--", "verify");

        assertThat(result.exit()).isEqualTo(10);
        assertThat(result.out()).containsExactly("submit 41 UNKNOWN sha=0cf5631 recipe=mvn deduplicated=true");
    }

    @Test
    void submit_printsUnknownSha_whenReadBackFails() {
        controller.onJson("POST /jobs",
                          Fixtures.submitted(41, JobState.QUEUED, false))
                  .on("GET /jobs/41",
                      Reply.json(500, "{}"));
        var result = cli.run("submit", "mvn", "--ref", "main", "--", "verify");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).containsExactly("submit 41 QUEUED sha=? recipe=mvn deduplicated=false");
    }

    @Test
    void unknownJob_isUsageError() {
        var result = cli.run("result", "999");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err()).contains("Not found: /jobs/999");
    }

    @Test
    void unauthorized_isReportedOnStderr() {
        controller.on("GET /jobs/41/result", Reply.json(401, "{\"error\":\"unauthorized\"}"));
        var result = cli.run("result", "41");

        assertThat(result.exit()).isEqualTo(77);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).contains("unauthorized");
    }

    @Test
    void rejected_isReportedOnStderr_withTheControllersReason() {
        controller.on("GET /jobs/41/result", Reply.json(400, "{\"error\":\"bad job id\"}"));
        var result = cli.run("result", "41");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err().lines()).containsExactly("temper: Controller rejected the request: bad job id");
    }

    @Test
    void unreadableTokenFile_isReportedOnOneStderrLine() throws IOException {
        var token = cli.home().resolve(".config/temper/token");

        unreadable(token);
        var result = cli.run("result", "41");

        assertThat(result.exit()).isEqualTo(64);
        assertThat(result.err().lines()).singleElement()
                  .asString()
                  .startsWith("temper: Cannot read " + token + " (java.nio.file.AccessDeniedException");
        assertThat(controller.received()).isEmpty();
    }

    /// The origin of an unreachable controller is the JDK's exception with its whole stack trace; stderr shows
    /// one line naming the target and the exception, never the trace.
    @Test
    void unreachable_isReportedOnOneStderrLine() throws IOException {
        var port = closedPort();
        var result = cli.env("TEMPER_URL", "http://127.0.0.1:" + port).run("result", "41");

        assertThat(result.exit()).isEqualTo(69);
        assertThat(result.err().lines()).singleElement()
                  .asString()
                  .startsWith("temper: Controller unreachable at http://127.0.0.1:" + port + "/api/v1 (")
                  .contains("ConnectException")
                  .endsWith(")");
    }

    /// A failure that escaped every translation is a bug: exit 5 (ERROR), also as the first member of a
    /// composite.
    @Test
    void unknownFailure_exitsFive() {
        var unknown = Causes.cause("not a CliError");

        assertThat(CliError.exitCodeOf(unknown)).isEqualTo(ExitCode.ERROR);
        assertThat(ExitCode.ERROR.code()).isEqualTo(5);
        assertThat(CliError.exitCodeOf(Causes.composite(unknown.result(), CliError.General.UNAUTHORIZED.result()))).isEqualTo(ExitCode.ERROR);
    }

    static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /// `temper merge` through every check against a `file://` origin, then a `gh` that exits 1.
    private static CliHarness.Run mergeFailed(FakeController controller, CliHarness cli) throws IOException {
        var remote = Files.createTempDirectory("t2-exit-code");
        var origin = GitOrigin.create(remote, T2Fixtures.REPO);
        var b0 = origin.commit(null, "base", Map.of("a.txt", "a\n"));
        var p1 = origin.commit(b0, "feature", Map.of("f.txt", "f\n"));
        var report = T2Fixtures.report(remote, "r.md", p1);
        var token = T2Fixtures.tokenFile(remote.resolve("verdict-token"), T2Fixtures.VERDICT_TOKEN);
        var gh = new T2Fixtures.GhSpy();

        origin.push(b0, "refs/heads/main").push(p1, "refs/pull/7/head");
        gh.exit = GhMerge.GhExit.ghExit(1, "refused by GitHub");
        controller.verdictToken(T2Fixtures.VERDICT_TOKEN)
                  .onJson("POST /fleet/go/" + T2Fixtures.GO_ID + "/consume",
                          T2Fixtures.consumed(p1, b0, report))
                  .on("POST /fleet/go/" + T2Fixtures.GO_ID + "/outcome",
                      Reply.json(200, "{}"));

        return cli.ports(T2Fixtures.ports(origin, gh))
                  .run("merge",
                       T2Fixtures.GO_ID,
                       "--token-file",
                       token.toString());
    }
}
