package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.Pacer;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.TestSupport;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.HttpReply;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;

/// `wait` survives a controller restart: up to five consecutive unreachable polls are tolerated (m0-design §8),
/// the sixth is exit 69. Driven through a scripted [HttpTransport].
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WaitCommandTest {
    private static final String RUNNING = """
        {"job":41,"state":"RUNNING","submittedBy":"tester","submittedAt":"2026-10-13T10:00:00Z","priority":-20,
         "keep":false,"fresh":false,"superseded":false,"source":{"ref":"main","sha":null},
         "recipe":{"mvn":{"args":["verify"]}},"units":[]}""";
    private static final String CANCELLED = RUNNING.replace("RUNNING", "CANCELLED");

    private final TestSupport.Time time = TestSupport.time();
    private final AtomicInteger calls = new AtomicInteger();

    /// Answers the scripted replies in order: a string is a 200 body, `null` is a connection failure. Any
    /// `/result` call answers 404 so the line comes from the job view.
    private HttpTransport scripted(String... replies) {
        var remaining = new LinkedList<>(Arrays.asList(replies));

        return call -> {
            calls.incrementAndGet();
            if (call.path().endsWith("/result")) {
                return Promise.success(HttpReply.httpReply(404, "{\"error\":\"not terminal\"}"));
            }
            var next = remaining.poll();

            return next == null
                   ? CliError.Unreachable.FACTORY.apply("http://fake", Causes.cause("Connection refused")).promise()
                   : Promise.success(HttpReply.httpReply(200, next));
        };
    }

    private Outcome await(HttpTransport transport) {
        return WaitCommand.await(Controller.controller(transport, TemperJson.mapper()),
                                 TemperJson.mapper(),
                                 new CliCommand.Wait(41, Duration.ofHours(6), false),
                                 time.clock(),
                                 time.pacer())
                          .await()
                          .fold(cause -> {
                              throw new AssertionError(cause.message());
                          }, outcome -> outcome);
    }

    @Test
    void wait_toleratesFiveConsecutiveConnectionFailures() {
        var outcome = await(scripted(RUNNING, null, null, null, null, null, CANCELLED));

        assertThat(outcome.exit()).isEqualTo(ExitCode.CANCELLED);
        assertThat(outcome.output()
                          .or("")).isEqualTo("wait 41 CANCELLED");
    }

    @Test
    void wait_exits69_onTheSixthConsecutiveConnectionFailure() {
        var failure = WaitCommand.await(Controller.controller(scripted(RUNNING, null, null, null, null, null, null, CANCELLED),
                                                              TemperJson.mapper()),
                                         TemperJson.mapper(),
                                         new CliCommand.Wait(41, Duration.ofHours(6), false),
                                         time.clock(),
                                         time.pacer())
                                  .await();

        assertThat(failure.isFailure()).isTrue();
        failure.onFailure(cause -> assertThat(CliError.exitCodeOf(cause)).isEqualTo(ExitCode.UNREACHABLE));
        assertThat(calls.get()).isEqualTo(7);
    }

    /// A long wait is a loop, not a recursion: 20 000 polls through an asynchronous pacer must neither overflow
    /// the stack when the job finally ends nor hang.
    @Test
    void wait_survivesTwentyThousandPolls() {
        var polls = new AtomicInteger();
        HttpTransport transport = call -> call.path().endsWith("/result")
                                          ? Promise.success(HttpReply.httpReply(404, "{}"))
                                          : Promise.success(HttpReply.httpReply(200,
                                                                                polls.incrementAndGet() < 20_000
                                                                                ? RUNNING
                                                                                : CANCELLED));
        Pacer asyncPacer = duration -> Promise.promise(Result::unitResult);

        var outcome = WaitCommand.await(Controller.controller(transport, TemperJson.mapper()),
                                        TemperJson.mapper(),
                                        new CliCommand.Wait(41, Duration.ofDays(365), false),
                                        time.clock(),
                                        asyncPacer)
                                 .await();

        assertThat(outcome.map(Outcome::exit)).isEqualTo(Result.success(ExitCode.CANCELLED));
        assertThat(polls.get()).isEqualTo(20_000);
    }

    /// The deadline passes while the controller is down: exit 7 with the last state seen, not 69, and no
    /// pause runs past the deadline.
    @Test
    void wait_timesOutWithLastState_whenTheDeadlinePassesDuringAnOutage() {
        var outcome = WaitCommand.await(Controller.controller(scripted(RUNNING, null, null, null, null), TemperJson.mapper()),
                                        TemperJson.mapper(),
                                        new CliCommand.Wait(41, Duration.ofSeconds(5), false),
                                        time.clock(),
                                        time.pacer())
                                 .await();

        assertThat(outcome.map(Outcome::exit)).isEqualTo(Result.success(ExitCode.WAIT_TIMEOUT));
        assertThat(outcome.map(result -> result.output().or(""))).isEqualTo(Result.success("wait 41 RUNNING"));
        assertThat(time.pauses()).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    /// The result read after a terminal state gets the same tolerance as the job read.
    @Test
    void wait_toleratesAnUnreachableResultRead() {
        var resultReads = new AtomicInteger();
        HttpTransport transport = call -> !call.path().endsWith("/result")
                                          ? Promise.success(HttpReply.httpReply(200, CANCELLED))
                                          : resultReads.incrementAndGet() == 1
                                            ? CliError.Unreachable.FACTORY.apply("http://fake", Causes.cause("reset")).promise()
                                            : Promise.success(HttpReply.httpReply(404, "{}"));

        var outcome = await(transport);

        assertThat(outcome.exit()).isEqualTo(ExitCode.CANCELLED);
        assertThat(resultReads.get()).isEqualTo(2);
    }

    @Test
    void wait_resetsFailureCount_afterASuccessfulPoll() {
        var outcome = await(scripted(null, null, null, null, null, RUNNING, null, null, null, null, null, CANCELLED));

        assertThat(outcome.exit()).isEqualTo(ExitCode.CANCELLED);
        assertThat(List.copyOf(time.pauses())).hasSize(11);
    }
}
