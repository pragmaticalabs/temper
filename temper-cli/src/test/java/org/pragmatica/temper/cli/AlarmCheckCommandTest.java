package org.pragmatica.temper.cli;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.cli.alarm.AlarmCode;
import org.pragmatica.temper.cli.alarm.FakeGithub;
import org.pragmatica.temper.cli.alarm.GithubApi;
import org.pragmatica.temper.cli.alarm.GithubIssueSink;
import org.pragmatica.temper.cli.command.AlarmCommand;
import org.pragmatica.temper.cli.config.AlarmConfig;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;

/// `temper alarm-check` as a whole invocation: the fake controller, the local disk, the `[alarm]` config and a sink.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AlarmCheckCommandTest {
    private static final String GITHUB_TOKEN = "ghp_alarmCheckToken";
    private static final String REPO = "pragmaticalabs/temper";

    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        controller.onJson("GET /fleet/alarms", Fixtures.noAlarms());
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    /// A good dump 6.5 h before the fake clock's now, the one host UP, and a disk threshold of 1 GiB.
    private void healthy() {
        controller.onJson("GET /maintenance", Fixtures.maintenance(Fixtures.AT.minus(Duration.ofMinutes(390)), true,
                                                                   some(Fixtures.AT.minus(Duration.ofMinutes(390)))))
                  .onJson("GET /hosts", Fixtures.hosts("UP"));
    }

    private void alarm(String keys) throws IOException {
        Files.writeString(home.resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(home) + "host = \"bigboy\"\nmin-free-gib = 1\npaths = [\"" + home + "\"]\n" + keys);
    }

    @Test
    void stdoutSink_allClear_exits0_oneLinePerCondition() throws IOException {
        healthy();
        alarm("sink = \"stdout\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(run.out()).containsExactly("alarm controller-down CLEAR printed",
                                              "alarm dump-failed CLEAR printed",
                                              "alarm dump-stale CLEAR printed",
                                              "alarm worker-lost CLEAR printed",
                                              "alarm host-alarms CLEAR printed",
                                              "alarm disk-low CLEAR printed",
                                              "alarm dump-file-stale CLEAR printed",
                                              "alarm check-blind CLEAR printed");
        assertThat(run.err()).isEmpty();
    }

    @Test
    void stdoutSink_failedDumpAndLostWorker_exits3() throws IOException {
        controller.onJson("GET /maintenance", Fixtures.maintenance(Fixtures.AT.minus(Duration.ofHours(1)), false,
                                                                   some(Fixtures.AT.minus(Duration.ofHours(25)))))
                  .onJson("GET /hosts", Fixtures.hosts("DOWN"));
        alarm("sink = \"stdout\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).as(run.err()).isEqualTo(3);
        assertThat(run.out()).contains("alarm dump-failed FIRING printed: pg_dump that ended 2026-10-13T09:00:00Z failed: pg_dump exited 1",
                                       "alarm dump-stale CLEAR printed",
                                       "alarm worker-lost FIRING printed: link not up: bigboy DOWN");
    }

    /// The controller is down at all three reads: only `controller-down` is delivered for it, the local disk is still
    /// checked, exit 3.
    @Test
    void controllerDown_onlyControllerDownFires_exits3() throws IOException {
        alarm("sink = \"stdout\"\n");

        var run = cli.env("TEMPER_URL", "http://127.0.0.1:" + closedPort()).run("alarm-check");

        assertThat(run.exit()).as(run.err()).isEqualTo(3);
        assertThat(run.out()).hasSize(4);
        assertThat(run.out().getFirst()).startsWith("alarm controller-down FIRING printed: Controller unreachable at http://127.0.0.1:");
        assertThat(run.out().subList(1, 4)).containsExactly("alarm disk-low CLEAR printed",
                                              "alarm dump-file-stale CLEAR printed", "alarm check-blind CLEAR printed");
        assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(15), Duration.ofSeconds(15));
    }

    /// A controller restart: 503 at the first read, answering at the second. Nothing fires.
    @Test
    void controllerDownThenUp_withinTheRetries_exits0() throws IOException {
        healthy();
        controller.on("GET /hosts",
                      FakeController.Reply.json(503, "{\"error\":\"starting\"}"),
                      FakeController.Reply.json(200, controller.write(Fixtures.hosts("UP"))));
        alarm("sink = \"stdout\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(run.out()).contains("alarm controller-down CLEAR printed", "alarm worker-lost CLEAR printed");
    }

    @Test
    void controllerAnswering5xx_isDown() throws IOException {
        healthy();
        controller.on("GET /hosts", FakeController.Reply.json(500, "{\"error\":\"boom\"}"));
        alarm("sink = \"stdout\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(3);
        assertThat(run.out()).containsExactly("alarm controller-down FIRING printed: Unexpected HTTP 500 from /hosts",
                                              "alarm disk-low CLEAR printed",
                                              "alarm dump-file-stale CLEAR printed",
                                              "alarm check-blind CLEAR printed");
    }

    /// A path that cannot be measured: reported on stderr, `disk-low` not delivered, exit 64.
    @Test
    void unmeasurablePath_isReported_exits64() throws IOException {
        healthy();
        Files.writeString(home.resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(home) + "sink = \"stdout\"\nhost = \"bigboy\"\npaths = [\"" + home.resolve("gone") + "\"]\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("alarm-check: not evaluated: Cannot read " + home.resolve("gone"));
        assertThat(run.out()).noneMatch(line -> line.startsWith("alarm disk-low"));
        assertThat(run.out()).anyMatch(line -> line.startsWith("alarm check-blind FIRING printed: not evaluated: Cannot read "));
    }

    /// Precedence: an undetermined condition (64) wins over a firing one (3).
    @Test
    void undeterminedAndFiring_exits64() throws IOException {
        controller.onJson("GET /maintenance", Fixtures.maintenance(Fixtures.AT.minus(Duration.ofHours(1)), false,
                                                                   some(Fixtures.AT.minus(Duration.ofHours(1)))))
                  .onJson("GET /hosts", Fixtures.hosts("UP"));
        Files.writeString(home.resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(home) + "sink = \"stdout\"\nhost = \"bigboy\"\npaths = [\"" + home.resolve("gone") + "\"]\n");

        var run = cli.run("alarm-check");

        assertThat(run.out()).anyMatch(line -> line.startsWith("alarm dump-failed FIRING"));
        assertThat(run.exit()).isEqualTo(64);
    }

    /// Precedence: a sink failure (2) wins over undetermined (64) and firing (3): nobody was told.
    @Test
    void sinkFailure_withUndeterminedAndFiring_exits2() throws IOException {
        controller.onJson("GET /maintenance", Fixtures.maintenance(Fixtures.AT.minus(Duration.ofHours(1)), false,
                                                                   some(Fixtures.AT.minus(Duration.ofHours(1)))))
                  .onJson("GET /hosts", Fixtures.hosts("UP"));
        Files.writeString(home.resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(home) + "repo = \"" + REPO + "\"\ngithub-api = \"http://127.0.0.1:" + closedPort()
                          + "\"\nhost = \"bigboy\"\npaths = [\"" + home.resolve("gone") + "\"]\n");
        token(GITHUB_TOKEN);

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(2);
    }

    /// A rotated controller token makes the alarm blind; `check-blind` opens an issue, is not repeated within the
    /// interval, and is closed once the controller answers again.
    @Test
    void checkBlind_firesRepeatsAndClears_throughTheGithubSink() throws IOException {
        healthy();
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            alarm("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);
            Files.writeString(home.resolve(".config/temper/token"), "rotated-token\n");

            var blind = cli.run("alarm-check");

            assertThat(blind.exit()).isEqualTo(64);
            assertThat(blind.out()).contains("alarm check-blind FIRING #11 opened: not evaluated: "
                                             + "Controller rejected the bearer token (unauthorized)");
            assertThat(github.issues()).extracting(issue -> issue.title).containsExactly("temper alarm: check-blind (bigboy)");

            var again = cli.run("alarm-check");

            assertThat(again.exit()).isEqualTo(64);
            assertThat(again.out()).anyMatch(line -> line.startsWith("alarm check-blind FIRING #11 open, repeat not due"));
            assertThat(github.issues()).hasSize(1);

            Files.writeString(home.resolve(".config/temper/token"), CliHarness.TOKEN + "\n");
            var seeing = cli.run("alarm-check");

            assertThat(seeing.exit()).as(seeing.err()).isZero();
            assertThat(seeing.out()).contains("alarm check-blind CLEAR #11 closed");
            assertThat(github.issues().getFirst().state).isEqualTo("closed");
            assertThat(String.join("\n", blind.out()) + blind.err()).doesNotContain("rotated-token", GITHUB_TOKEN);
        }
    }

    @Test
    void configError_exits64_withTheUsage() throws IOException {
        alarm("sink = \"file\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("alarm.sink must be github-issue or stdout: file", "alarm-check reads [alarm]");
    }

    /// End to end: the token comes from its file, travels as `Bearer`, and appears in no output.
    @Test
    void githubSink_opensTheIssue_withTheTokenFromItsFile() throws IOException {
        healthy();
        controller.onJson("GET /hosts", Fixtures.hosts("CONNECTING"));
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            alarm("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var run = cli.run("alarm-check");

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(run.out()).contains("alarm worker-lost FIRING #11 opened: link not up: bigboy CONNECTING",
                                           "alarm dump-stale CLEAR no open issue");
            assertThat(github.issues()).extracting(issue -> issue.title).containsExactly("temper alarm: worker-lost (bigboy)");
            assertThat(github.received()).allSatisfy(request -> assertThat(request.authorization()).isEqualTo("Bearer " + GITHUB_TOKEN));
            assertThat(String.join("\n", run.out()) + run.err()).doesNotContain(GITHUB_TOKEN);
        }
    }

    /// #103: an open `worker-lost` issue for a host that is then retired (past `retireAt`, no active unit, its link
    /// stopped and DOWN) is closed by the next check: the condition is a level, and a drained retired host is not part of
    /// it (m4-design §10, Q-M4-10), so the check finds it clear. Mutation: count a retired host as lost → red.
    @Test
    void githubSink_openWorkerLost_closes_onceTheHostIsRetired() throws IOException {
        healthy();
        controller.onJson("GET /hosts", Fixtures.hosts("CONNECTING"));
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            alarm("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var lost = cli.run("alarm-check");

            assertThat(lost.exit()).as(lost.err()).isEqualTo(3);
            assertThat(lost.out()).contains("alarm worker-lost FIRING #11 opened: link not up: bigboy CONNECTING");

            controller.onJson("GET /hosts",
                              List.of(HostView.hostView("bigboy", "DOWN", Option.none(), 5, 0, 0, 0, Option.none(), false,
                                                        some(Fixtures.AT.minusSeconds(60)), true)));
            var retired = cli.run("alarm-check");

            assertThat(retired.exit()).as(retired.err()).isZero();
            assertThat(retired.out()).contains("alarm worker-lost CLEAR #11 closed");
            assertThat(github.issues().getFirst().state).isEqualTo("closed");
        }
    }

    /// Duplicates of a firing condition's issue: the check still fires (exit 3), its line lists them for a person to
    /// close, and no request names them.
    @Test
    void githubSink_duplicates_areListed_untouched_exits3() throws IOException {
        healthy();
        controller.onJson("GET /hosts", Fixtures.hosts("CONNECTING"));
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            var original = github.issue("temper alarm: worker-lost (bigboy)", Fixtures.AT.minus(Duration.ofHours(1)));
            var duplicate = github.issue("temper alarm: worker-lost (bigboy)", Fixtures.AT);
            alarm("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var run = cli.run("alarm-check");

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(run.out()).anySatisfy(line -> assertThat(line).startsWith("alarm worker-lost FIRING #" + original.number() + " ")
                                                                     .contains("; duplicates for a person to close: #" + duplicate.number()
                                                                               + ": link not up"));
            assertThat(github.received()).noneMatch(request -> request.target().contains("/issues/" + duplicate.number()));
        }
    }

    /// The [alarm] section with `disk-low` firing (a free-space threshold no disk meets) and `keys`.
    private void alarmDiskLow(String keys) throws IOException {
        Files.writeString(home.resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(home) + "host = \"bigboy\"\nmin-free-gib = 1000000000\npaths = [\"" + home + "\"]\n" + keys);
    }

    private static List<String> undelivered(CliHarness.Run run) {
        return run.err().lines().filter(line -> line.contains("UNDELIVERED")).toList();
    }

    /// GitHub answers 5xx with `disk-low` firing: exit 2, and stderr names the firing condition nobody was told, one line.
    @Test
    void sinkFails_withAFiringCondition_namesItUndelivered_exits2() throws IOException {
        healthy();
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            github.failWith(502);
            alarmDiskLow("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var run = cli.run("alarm-check");

            assertThat(run.exit()).isEqualTo(2);
            assertThat(undelivered(run)).singleElement()
                                        .satisfies(line -> assertThat(line).startsWith("temper: alarm disk-low FIRING UNDELIVERED: ")
                                                                           .doesNotContain("\n"));
            assertThat(run.err()).contains("Alarm sink answered HTTP 502");
        }
    }

    /// Unreachable GitHub (connection refused), the same.
    @Test
    void sinkUnreachable_withAFiringCondition_namesItUndelivered_exits2() throws IOException {
        healthy();
        alarmDiskLow("repo = \"" + REPO + "\"\ngithub-api = \"http://127.0.0.1:" + closedPort() + "\"\n");
        token(GITHUB_TOKEN);

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(undelivered(run)).singleElement().satisfies(line -> assertThat(line).startsWith("temper: alarm disk-low FIRING UNDELIVERED: "));
    }

    /// The delivery cut by its deadline: exit 2, and the firing condition is named undelivered.
    @Test
    void deliveryDeadlineCut_withAFiringCondition_namesItUndelivered_exits2() throws IOException {
        healthy();
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            github.delay(2000);
            alarmDiskLow("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});
            var context = CliContext.cliContext(_ -> Option.none(), home, cli.time, cli.time, out.console());
            var alarm = AlarmConfig.alarmConfig(context, Fixtures.AT).fold(cause -> fail(cause.message()), config -> config);
            var sink = GithubIssueSink.githubIssueSink(GithubApi.githubApi(HttpTransport.httpClient(), URI.create(github.url()), REPO, GITHUB_TOKEN),
                                                       "bigboy",
                                                       Duration.ofHours(6),
                                                       Duration.ofMillis(300));
            var api = Controller.controller(HttpTransport.jdkHttpTransport(HttpTransport.httpClient(), URI.create(controller.url() + "/api/v1"),
                                                                           CliHarness.TOKEN, Duration.ofSeconds(10)),
                                            TemperJson.mapper());

            var cause = AlarmCommand.checked(api, AlarmCommand.Setup.setup(alarm, sink), context, CliHarness.TOKEN, controller.url() + "/api/v1", Fixtures.AT)
                                    .await()
                                    .fold(failed -> failed, outcome -> fail("delivered " + outcome));

            assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.ALARM_UNDELIVERED);
            assertThat(Console.oneLine(cause)).contains("delivery within 300 ms");
            assertThat(out.stderr().text().lines().filter(line -> line.contains("UNDELIVERED")))
                .singleElement()
                .satisfies(line -> assertThat(line).startsWith("temper: alarm disk-low FIRING UNDELIVERED: "));
        }
    }

    /// The sink fails with every condition clear: exit 2, and no condition is named undelivered.
    @Test
    void sinkFails_withOnlyClearConditions_namesNothingUndelivered() throws IOException {
        healthy();
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            github.failWith(502);
            alarm("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var run = cli.run("alarm-check");

            assertThat(run.exit()).isEqualTo(2);
            assertThat(undelivered(run)).isEmpty();
        }
    }

    /// A delivered firing condition: exit 3, and nothing is named undelivered.
    @Test
    void deliveredFiringCondition_namesNothingUndelivered() throws IOException {
        healthy();
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            alarmDiskLow("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var run = cli.run("alarm-check");

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(undelivered(run)).isEmpty();
        }
    }

    /// GitHub refuses the token: exit 2 (the systemd unit fails), and the token is not printed.
    @Test
    void githubSink_rejected_exits2_withoutTheToken() throws IOException {
        healthy();
        try (var github = FakeGithub.start("another-token", REPO)) {
            alarm("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var run = cli.run("alarm-check");

            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.err()).contains("Alarm sink answered HTTP 401 to GET /repos/pragmaticalabs/temper/issues")
                                 .doesNotContain(GITHUB_TOKEN);
            assertThat(run.out()).isEmpty();
        }
    }

    @Test
    void githubSink_unreachable_exits2() throws IOException {
        healthy();
        alarm("repo = \"" + REPO + "\"\ngithub-api = \"http://127.0.0.1:" + closedPort() + "\"\n");
        token(GITHUB_TOKEN);

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err()).contains("Alarm sink request GET /repos/pragmaticalabs/temper/issues failed")
                             .doesNotContain(GITHUB_TOKEN);
    }

    @Test
    void githubSink_withoutTheTokenFile_exits64_beforeAskingTheController() throws IOException {
        alarm("repo = \"" + REPO + "\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("Alarm token file missing: " + home.resolve(".config/temper/alarm-token"));
        assertThat(controller.received()).isEmpty();
    }

    /// Through the real wiring: the controller token, read from its file, never reaches the `check-blind` detail or
    /// stderr, even when a controller answer quotes it.
    @Test
    void checkBlind_redactsTheControllerToken_readFromItsFile() throws IOException {
        healthy();
        controller.on("GET /maintenance", FakeController.Reply.json(400, "{\"error\":\"bad token " + CliHarness.TOKEN + "\"}"));
        alarm("sink = \"stdout\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.out()).contains("alarm check-blind FIRING printed: not evaluated: Controller rejected the request: bad token [redacted]");
        assertThat(String.join("\n", run.out()) + run.err()).doesNotContain(CliHarness.TOKEN);
        assertThat(run.err()).contains("bad token [redacted]");
    }

    /// The delivery's time is read after the controller retries: two 15 s pauses put the `cleared` comment at 10:00:30.
    @Test
    void deliveryTime_isReadAfterTheControllerRetries() throws IOException {
        healthy();
        controller.on("GET /hosts",
                      FakeController.Reply.json(503, "{\"error\":\"starting\"}"),
                      FakeController.Reply.json(503, "{\"error\":\"starting\"}"),
                      FakeController.Reply.json(200, controller.write(Fixtures.hosts("UP"))));
        try (var github = FakeGithub.start(GITHUB_TOKEN, REPO)) {
            var issue = github.issue("temper alarm: worker-lost (bigboy)", Fixtures.AT.minus(Duration.ofDays(1)));
            alarm("repo = \"" + REPO + "\"\ngithub-api = \"" + github.url() + "\"\n");
            token(GITHUB_TOKEN);

            var run = cli.run("alarm-check");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(issue.comments).extracting(FakeGithub.Comment::body).containsExactly("cleared at 2026-10-13T10:00:30Z\n\n"
                                                                                            + GithubIssueSink.marker(AlarmCode.WORKER_LOST, "cleared"));
        }
    }

    /// A controller without `GET /maintenance` (the fake answers 404) and the dump codes skipped: no `/maintenance`
    /// request, nothing blind, exit 0, and the skip is printed first.
    @Test
    void skippedDumpCodes_printTheSkip_evenAllClear_andNeverAskMaintenance() throws IOException {
        controller.onJson("GET /hosts", Fixtures.hosts("UP"));
        alarm("sink = \"stdout\"\nskip = [\"dump-failed\", \"dump-stale\"]\nskip-until = \"2026-10-20\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(run.out()).containsExactly("SKIPPED: dump-failed, dump-stale until 2026-10-20",
                                              "alarm controller-down CLEAR printed",
                                              "alarm worker-lost CLEAR printed",
                                              "alarm host-alarms CLEAR printed",
                                              "alarm disk-low CLEAR printed",
                                              "alarm dump-file-stale CLEAR printed",
                                              "alarm check-blind CLEAR printed");
        assertThat(controller.received()).noneMatch(request -> request.target().contains("/maintenance"));
    }

    /// Past `skip-until` the skip is printed as expired, the dump codes are read again, and `check-blind` fires.
    @Test
    void expiredSkip_firesCheckBlind_exits64() throws IOException {
        controller.onJson("GET /hosts", Fixtures.hosts("UP"));
        alarm("sink = \"stdout\"\nskip = [\"dump-failed\", \"dump-stale\"]\nskip-until = \"2026-10-12\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.out().getFirst()).startsWith("SKIP EXPIRED: dump-failed, dump-stale on 2026-10-12");
        assertThat(run.out()).anyMatch(line -> line.startsWith("alarm check-blind FIRING printed: not evaluated: ")
                                               && line.contains("skip of dump-failed, dump-stale expired on 2026-10-12"));
        assertThat(controller.received()).anyMatch(request -> request.target().contains("/maintenance"));
    }

    /// A skip further out than 7 days is refused before anything is checked.
    @Test
    void skipBeyondSevenDays_exits64() throws IOException {
        alarm("sink = \"stdout\"\nskip = [\"dump-stale\"]\nskip-until = \"2026-10-21\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }

    /// Refused skip codes are named on stderr end to end, each of them, with the allowed set; never a bare "Composite:".
    @Test
    void refusedSkipCodes_areNamedOnStderr() throws IOException {
        alarm("sink = \"stdout\"\nskip = [\"controller-down\", \"disk-low\"]\nskip-until = \"2026-10-15\"\nstale-after = \"soon\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("alarm.skip may name only dump-failed and dump-stale, not: controller-down, disk-low"
                                       + " (controller-down, disk-low, worker-lost, dump-file-stale and check-blind are never skipped)",
                                       "Invalid alarm.stale-after: soon");
        assertThat(run.err().lines()).noneMatch(line -> line.strip().equals("temper: Composite:"));
        assertThat(controller.received()).isEmpty();
    }

    /// A sink failure (exit 2) still prints the skip line.
    @Test
    void skipLine_isPrinted_onASinkFailure() throws IOException {
        controller.onJson("GET /hosts", Fixtures.hosts("UP"));
        alarm("repo = \"" + REPO + "\"\ngithub-api = \"http://127.0.0.1:" + closedPort() + "\"\n"
              + "skip = [\"dump-failed\", \"dump-stale\"]\nskip-until = \"2026-10-20\"\n");
        token(GITHUB_TOKEN);

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).containsExactly("SKIPPED: dump-failed, dump-stale until 2026-10-20");
    }

    /// A configuration that parsed but cannot be used (no GitHub token): exit 64, the skip line printed.
    @Test
    void skipLine_isPrinted_onAParsedConfigThatFailsLater() throws IOException {
        alarm("repo = \"" + REPO + "\"\nskip = [\"dump-stale\"]\nskip-until = \"2026-10-20\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.out()).containsExactly("SKIPPED: dump-stale until 2026-10-20");
    }

    /// The run starts at 23:59:59 UTC on the skip's last day and its later reads are past midnight: one instant decides
    /// the skip, so it holds and its line agrees (no `/maintenance`, no expiry, nothing blind).
    @Test
    void skip_isDecidedAtOneInstant_acrossUtcMidnight() throws IOException {
        controller.onJson("GET /hosts", Fixtures.hosts("UP"));
        alarm("sink = \"stdout\"\nskip = [\"dump-failed\", \"dump-stale\"]\nskip-until = \"2026-10-13\"\n");
        var reads = new AtomicInteger();
        InstantSource midnight = () -> reads.getAndIncrement() == 0
                                       ? Instant.parse("2026-10-13T23:59:59Z")
                                       : Instant.parse("2026-10-14T00:00:01Z");

        var run = cli.runAt(midnight, "alarm-check");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(run.out().getFirst()).isEqualTo("SKIPPED: dump-failed, dump-stale until 2026-10-13");
        assertThat(run.out()).contains("alarm check-blind CLEAR printed");
        assertThat(controller.received()).noneMatch(request -> request.target().contains("/maintenance"));
    }

    /// A skip of the dump codes leaves every other undetermined result alone: an unmeasurable disk path is still blind.
    @Test
    void skip_keepsOtherUndeterminedResults_blind() throws IOException {
        controller.onJson("GET /hosts", Fixtures.hosts("UP"));
        Files.writeString(home.resolve(".config/temper/config"),
                          "[alarm]\n" + Fixtures.dumpDir(home) + "sink = \"stdout\"\nhost = \"bigboy\"\npaths = [\""
                          + home.resolve("gone") + "\"]\nskip = [\"dump-failed\", \"dump-stale\"]\nskip-until = \"2026-10-20\"\n");

        var run = cli.run("alarm-check");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.out()).anyMatch(line -> line.startsWith("alarm check-blind FIRING printed: not evaluated: Cannot read "));
    }

    private void token(String value) throws IOException {
        var file = Files.writeString(home.resolve(".config/temper/alarm-token"), value + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
