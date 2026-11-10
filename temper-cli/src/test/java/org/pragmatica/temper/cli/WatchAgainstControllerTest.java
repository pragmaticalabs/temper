package org.pragmatica.temper.cli;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.command.WatchCommand;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.cli.transport.Subscription;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;

/// A5, client side, end to end: `temper watch` over the real JDK HttpClient against the fake controller's
/// long-poll routes (`subscribers/{name}/next` and `/ack`, m0-design §6.5), whose cursor advances only on `ack`.
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WatchAgainstControllerTest {
    private static final Duration SHORT_POLL = Duration.ofMillis(100);

    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN)
                                   .event(Fixtures.finished(5, 4711, "FAILED"))
                                   .event(Fixtures.finished(6, 4712, "PASSED"));
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    @Test
    void killBetweenPrintAndAck_replaysTheSameSeq() {
        controller.subscriber("cto", 4);

        var first = cli.run(CapturedOutput.killedAfter(1), "watch", "--terminal", "--subscriber", "cto");

        assertThat(first.out()).containsExactly(Fixtures.finished(5, 4711, "FAILED")
                                                        .line());
        assertThat(first.exit()).isEqualTo(5);
        assertThat(controller.acks()).isEmpty();
        assertThat(controller.cursor("cto")).contains(4L);

        var second = cli.run(CapturedOutput.killedAfter(2), "watch", "--terminal", "--subscriber", "cto");

        assertThat(second.out()).containsExactly(Fixtures.finished(5, 4711, "FAILED")
                                                         .line(),
                                                 Fixtures.finished(6, 4712, "PASSED")
                                                         .line());
        assertThat(second.exit()).isEqualTo(5);
        assertThat(controller.acks()).containsExactly(5L);
        assertThat(controller.cursor("cto")).contains(5L);
    }

    @Test
    void polls_carryTokenSubscriberAndTerminal_andAcksCarryTheSeq() {
        controller.subscriber("cto", 4);

        cli.run(CapturedOutput.killedAfter(2), "watch", "--terminal", "--subscriber", "cto");

        assertThat(controller.received()).containsExactly(new FakeController.Received("GET",
                                                                                      "/api/v1/subscribers/cto/next?terminal=true",
                                                                                      "",
                                                                                      "Bearer " + CliHarness.TOKEN),
                                                          new FakeController.Received("POST",
                                                                                      "/api/v1/subscribers/cto/ack",
                                                                                      "{\"ack\":{\"seq\":5}}",
                                                                                      "Bearer " + CliHarness.TOKEN),
                                                          new FakeController.Received("GET",
                                                                                      "/api/v1/subscribers/cto/next?terminal=true",
                                                                                      "",
                                                                                      "Bearer " + CliHarness.TOKEN));
    }

    @Test
    void withoutTerminal_pollsAskForEveryEvent() {
        controller.subscriber("all", 4);

        cli.run(CapturedOutput.killedAfter(1), "watch", "--subscriber", "all");

        assertThat(controller.received()
                             .getFirst()
                             .target()).isEqualTo("/api/v1/subscribers/all/next?terminal=false");
    }

    /// The controller answers the gap until the cursor passes it: the CLI prints it once and acks it at `to`.
    @Test
    void gap_isPrintedAsItsOwnLine_andAcknowledged() {
        controller.subscriber("cto", 0);

        var run = cli.run(CapturedOutput.killedAfter(2), "watch", "--terminal", "--subscriber", "cto");

        assertThat(run.out()).containsExactly("gap from=1 to=4",
                                              Fixtures.finished(5, 4711, "FAILED")
                                                      .line());
        assertThat(controller.acks()).containsExactly(4L);
        assertThat(controller.cursor("cto")).contains(4L);
    }

    /// Q-14: a subscriber the controller has not seen starts at `max(seq)`, so only later events reach it.
    @Test
    void newSubscriber_startsAfterExistingEvents() throws InterruptedException {
        controller.pollWait(SHORT_POLL);

        var out = TestSupport.journaled(1, _ -> {});
        var watching = watch(CliHarness.TOKEN, "fresh", out, neverResumes());

        awaitPolls(2);
        controller.event(Fixtures.finished(7, 4713, "PASSED"));

        assertThat(stoppedBy(watching)).isEqualTo(CliError.General.OUTPUT_CLOSED);
        assertThat(out.lines()).containsExactly(Fixtures.finished(7, 4713, "PASSED")
                                                        .line());
        assertThat(controller.cursor("fresh")).contains(6L);
    }

    /// An empty poll (`204` after the controller's wait) is the idle state, not a failure: the CLI asks again at once,
    /// without a pause and without a word on stderr. The pacer never resumes, so a `204` taken for a failure would
    /// stall the watch after the first poll.
    @Test
    void emptyPoll_isAskedAgain_withoutPauseOrNoise() throws InterruptedException {
        controller.subscriber("cto", 6)
                  .pollWait(SHORT_POLL);
        var out = TestSupport.journaled(1, _ -> {});
        var watching = watch(CliHarness.TOKEN, "cto", out, neverResumes());

        awaitPolls(4);
        controller.event(Fixtures.finished(7, 4713, "PASSED"));

        assertThat(stoppedBy(watching)).isEqualTo(CliError.General.OUTPUT_CLOSED);
        assertThat(out.lines()).containsExactly(Fixtures.finished(7, 4713, "PASSED")
                                                        .line());
        assertThat(out.errors()).isEmpty();
    }

    /// With the controller's real 30 s window, an event committed during the wait answers the waiting poll at once.
    @Test
    void newEvent_answersTheWaitingPoll() throws InterruptedException {
        controller.subscriber("cto", 6);
        var out = TestSupport.journaled(1, _ -> {});
        var watching = watch(CliHarness.TOKEN, "cto", out, neverResumes());

        awaitPolls(1);
        controller.event(Fixtures.finished(7, 4713, "PASSED"));

        Cause stopped = watching.await(TimeSpan.fromDuration(Duration.ofSeconds(5)))
                                .fold(cause -> cause, _ -> Causes.cause("watch ended successfully"));

        assertThat(stopped).isEqualTo(CliError.General.OUTPUT_CLOSED);
        assertThat(controller.polls()).isEqualTo(1);
    }

    @Test
    void badToken_exits77() throws IOException {
        var stranger = CliHarness.withToken(home.resolve("other"), "not-the-token")
                                 .env("TEMPER_URL", controller.url());

        var run = stranger.run("watch", "--terminal", "--subscriber", "cto");

        assertThat(run.exit()).isEqualTo(77);
        assertThat(run.out()).isEmpty();
        assertThat(run.err().lines()).hasSize(1);
        assertThat(controller.acks()).isEmpty();
    }

    /// A subscriber name the controller refuses (`400`) stops the watch with exit 64; retrying cannot fix it.
    /// D-6: a poll answered by a controller of another judged build is the CLI's own refusal: exit 13 at once, no
    /// retry, nothing printed, nothing acked.
    @Test
    void skewedController_onThePoll_exits13_withoutRetrying() {
        controller.subscriber("cto", 4)
                  .temperVersion(Optional.of("0.1.0-SNAPSHOT+222222222222"));

        var run = cli.version("0.1.0-SNAPSHOT+111111111111")
                     .run("watch", "--terminal", "--subscriber", "cto");

        assertThat(run.exit()).isEqualTo(ExitCode.VERSION_SKEW.code());
        assertThat(run.out()).isEmpty();
        assertThat(run.err()).doesNotContain("retrying");
        assertThat(controller.polls()).isEqualTo(1);
        assertThat(controller.acks()).isEmpty();
    }

    /// D-6: an ack refused with 412 (the controller's check on the write; the answer carries the CLI's own version, so
    /// only the status can say skew) is exit 13 at once, no retry.
    @Test
    void ackRefused412_exits13_withoutRetrying() {
        controller.subscriber("cto", 4)
                  .refuseAcks();

        var run = cli.run("watch", "--terminal", "--subscriber", "cto");

        assertThat(run.exit()).isEqualTo(ExitCode.VERSION_SKEW.code());
        assertThat(run.err()).doesNotContain("retrying");
        assertThat(controller.polls()).isEqualTo(1);
        assertThat(controller.acks()).isEmpty();
    }

    @Test
    void refusedSubscriberName_exits64() {
        var run = cli.run("watch", "--subscriber", "no spaces");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(controller.received()
                             .getFirst()
                             .target()).isEqualTo("/api/v1/subscribers/no%20spaces/next?terminal=false");
    }

    /// A controller without the long-poll routes answers `404` on `next`: a version mismatch, not a missing job.
    @Test
    void controllerWithoutLongPoll_exits69() {
        controller.withoutLongPoll();

        var run = cli.run("watch", "--subscriber", "cto");

        assertThat(run.exit()).isEqualTo(69);
        assertThat(run.err()).contains("Unexpected HTTP 404 from /subscribers/cto/next?terminal=false");
        assertThat(controller.received()).hasSize(1);
    }

    private Promise<Unit> watch(String token, String subscriber, TestSupport.Out out, Pacer pacer) {
        var api = Controller.controller(HttpTransport.jdkHttpTransport(HttpTransport.httpClient(),
                                                                       URI.create(controller.url() + "/api/v1"),
                                                                       token),
                                        TemperJson.mapper());

        return WatchCommand.watch(Subscription.polled(api, subscriber, true), out.console(), pacer);
    }

    private static Pacer neverResumes() {
        return _ -> Promise.promise();
    }

    private static Cause stoppedBy(Promise<Unit> watching) {
        return watching.await()
                       .fold(cause -> cause, _ -> Causes.cause("watch ended successfully"));
    }

    private void awaitPolls(int count) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (controller.polls() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(controller.polls()).isGreaterThanOrEqualTo(count);
    }
}
