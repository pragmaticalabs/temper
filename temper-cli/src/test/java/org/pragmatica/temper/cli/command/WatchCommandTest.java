package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.TestSupport;
import org.pragmatica.temper.cli.transport.Subscription;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.protocol.api.WsServerMessage.WsError;
import org.pragmatica.temper.protocol.api.WsServerMessage.WsGap;

import static org.assertj.core.api.Assertions.assertThat;

/// A5, client side (m0-design §9 `WatchOrderTest`): a scripted long-poll subscription journals every print and
/// ack, so the order "print, flush, then ack" is asserted directly, as is "no ack when the print failed".
@Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WatchCommandTest {
    /// A `204`: nothing within the controller's poll window.
    private static final Object EMPTY = new Object();
    private static final Cause STOP = CliError.General.UNAUTHORIZED;
    private static final Cause UNREACHABLE = CliError.Unreachable.FACTORY.apply("http://fake/api/v1", Causes.cause("refused"));
    private static final Cause OVERLOADED = CliError.UnexpectedStatus.FACTORY.apply(503, "/subscribers/cto/next?terminal=true");

    private static final int FAILED_POLLS = 20_000;
    private static final int EMPTY_POLLS = 20_000;
    private static final int LONG_RUN_EVENTS = 10_000;

    private final List<String> journal = new CopyOnWriteArrayList<>();
    private final TestSupport.Time time = TestSupport.time();

    /// The answers `next()` gives in order: a message (`200`), [#EMPTY] (`204`), or a cause the poll fails with.
    /// Each entry of `failingAcks` fails one acknowledgement of that seq (the ack was lost).
    private final class ScriptedSubscription implements Subscription {
        private final Deque<Object> script;
        private final List<Long> failingAcks;
        private final Cause ackFailure;

        ScriptedSubscription(List<Long> failingAcks, Object... script) {
            this(OVERLOADED, failingAcks, script);
        }

        ScriptedSubscription(Cause ackFailure, List<Long> failingAcks, Object... script) {
            this.script = new ArrayDeque<>(List.of(script));
            this.failingAcks = new ArrayList<>(failingAcks);
            this.ackFailure = ackFailure;
        }

        @Override
        public Promise<Option<WsServerMessage>> next() {
            var item = script.poll();

            if (item == null) {
                throw new AssertionError("script exhausted");
            }
            return item instanceof Cause cause
                   ? cause.promise()
                   : Promise.success(item == EMPTY
                                     ? Option.none()
                                     : Option.some((WsServerMessage) item));
        }

        @Override
        public Promise<Unit> ack(long seq) {
            journal.add("ack " + seq);
            return failingAcks.remove(Long.valueOf(seq))
                   ? ackFailure.promise()
                   : Promise.success(Unit.unit());
        }
    }

    private ScriptedSubscription answers(Object... script) {
        return new ScriptedSubscription(List.of(), script);
    }

    private Cause watch(Console console, Subscription subscription) {
        return WatchCommand.watch(subscription, console, time.pacer())
                           .await()
                           .fold(cause -> cause, _ -> {
                               throw new AssertionError("watch ended successfully");
                           });
    }

    private static WsServerMessage.WsEvent event(long seq) {
        return TestSupport.finished(seq);
    }

    @Test
    void watch_printsAndFlushesBeforeAck_forEveryEvent() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, line -> journal.add("print " + line));

        var stopped = watch(out.console(), answers(event(1), event(2), STOP));

        assertThat(stopped).isEqualTo(CliError.General.UNAUTHORIZED);
        assertThat(journal).containsExactly("print " + event(1).line(),
                                            "ack 1",
                                            "print " + event(2).line(),
                                            "ack 2");
    }

    @Test
    void watch_neverAcks_whenThePrintFails() {
        var out = TestSupport.journaled(1, line -> journal.add("print " + line));

        var stopped = watch(out.console(), answers(event(7), event(8)));

        assertThat(stopped).isEqualTo(CliError.General.OUTPUT_CLOSED);
        assertThat(CliError.exitCodeOf(stopped)).isEqualTo(ExitCode.ERROR);
        assertThat(journal).containsExactly("print " + event(7).line());
    }

    /// The ack of 5 is lost, so the controller answers 5 again: acknowledged again, printed once.
    @Test
    void watch_printsRedeliveredSeqOnce_andAcksItAgain() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, line -> journal.add("print " + line));

        watch(out.console(), new ScriptedSubscription(List.of(5L), event(5), event(5), event(6), STOP));

        assertThat(journal).containsExactly("print " + event(5).line(),
                                            "ack 5",
                                            "ack 5",
                                            "print " + event(6).line(),
                                            "ack 6");
        assertThat(time.pauses()).containsExactly(seconds(1));
    }

    /// The controller answers the same gap until the cursor passes it, so the gap is acknowledged at `to`.
    @Test
    void watch_printsGapLine_andAcksIt_beforeTheNextEvent() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, line -> journal.add("print " + line));

        watch(out.console(), answers(new WsGap(3, 9), event(10), STOP));

        assertThat(out.lines()).containsExactly("gap from=3 to=9", event(10).line());
        assertThat(journal).containsExactly("print gap from=3 to=9",
                                            "ack 9",
                                            "print " + event(10).line(),
                                            "ack 10");
    }

    @Test
    void watch_printsRedeliveredGapOnce() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, line -> journal.add("print " + line));

        watch(out.console(), new ScriptedSubscription(List.of(9L), new WsGap(3, 9), new WsGap(3, 9), event(10), STOP));

        assertThat(out.lines()).containsExactly("gap from=3 to=9", event(10).line());
        assertThat(journal).containsExactly("print gap from=3 to=9",
                                            "ack 9",
                                            "ack 9",
                                            "print " + event(10).line(),
                                            "ack 10");
        assertThat(time.pauses()).containsExactly(seconds(1));
    }

    /// An answered poll is not progress; only a successful ack is. A redelivery whose ack keeps failing backs off.
    @Test
    void watch_resetsBackoff_onlyAfterASuccessfulAck() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        watch(out.console(),
              new ScriptedSubscription(List.of(5L, 5L, 5L), event(5), event(5), event(5), event(5), OVERLOADED, STOP));

        assertThat(out.lines()).containsExactly(event(5).line());
        assertThat(time.pauses()).containsExactly(seconds(1), seconds(2), seconds(4), seconds(1));
    }

    /// The `404` of an ack (the subscriber vanished; the next poll recreates it) is retried.
    @Test
    void watch_retries_whenTheAckedSubscriberVanished() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});
        var vanished = CliError.NotFound.FACTORY.apply("/subscribers/cto/ack");

        var stopped = watch(out.console(), new ScriptedSubscription(vanished, List.of(5L), event(5), event(5), STOP));

        assertThat(stopped).isEqualTo(STOP);
        assertThat(journal).containsExactly("ack 5", "ack 5");
        assertThat(time.pauses()).containsExactly(seconds(1));
    }

    /// A `204` is the long-poll's normal idle answer: asked again at once, no pause, nothing on stderr.
    @Test
    void watch_pollsAgain_onEmptyAnswer() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        var stopped = watch(out.console(), answers(EMPTY, EMPTY, EMPTY, event(1), EMPTY, STOP));

        assertThat(stopped).isEqualTo(CliError.General.UNAUTHORIZED);
        assertThat(out.lines()).containsExactly(event(1).line());
        assertThat(time.pauses()).isEmpty();
        assertThat(out.errors()).isEmpty();
    }

    @Test
    void watch_retriesWithBackoff_whileControllerUnreachable() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        var stopped = watch(out.console(),
                            answers(UNREACHABLE,
                                    UNREACHABLE,
                                    UNREACHABLE,
                                    UNREACHABLE,
                                    UNREACHABLE,
                                    UNREACHABLE,
                                    UNREACHABLE,
                                    UNREACHABLE,
                                    event(1),
                                    STOP));

        assertThat(stopped).isEqualTo(CliError.General.UNAUTHORIZED);
        assertThat(time.pauses()).containsExactly(seconds(1),
                                                  seconds(2),
                                                  seconds(4),
                                                  seconds(8),
                                                  seconds(16),
                                                  seconds(32),
                                                  seconds(60),
                                                  seconds(60));
        assertThat(out.lines()).containsExactly(event(1).line());
        assertThat(out.errors()).contains("Controller unreachable at http://fake/api/v1 (refused); retrying in 1s");
        assertThat(out.errors().lines()).hasSize(8)
                                        .allMatch(line -> line.contains("; retrying in "));
    }

    @Test
    void watch_resetsBackoff_afterAnAcknowledgedEvent() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        watch(out.console(), answers(UNREACHABLE, UNREACHABLE, event(1), OVERLOADED, UNREACHABLE, STOP));

        assertThat(time.pauses()).containsExactly(seconds(1), seconds(2), seconds(1), seconds(2));
    }

    /// Empty polls between failures are no progress: the backoff keeps growing.
    @Test
    void watch_keepsBackingOff_whilePollsMakeNoProgress() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        watch(out.console(), answers(OVERLOADED, EMPTY, OVERLOADED, EMPTY, OVERLOADED, STOP));

        assertThat(time.pauses()).containsExactly(seconds(1), seconds(2), seconds(4));
    }

    /// `403`: the read token may not create or advance a subscriber (D3); a retry would be refused forever (exit 77).
    @Test
    void watch_stops_onForbidden() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        var stopped = watch(out.console(), answers(CliError.General.FORBIDDEN, event(1)));

        assertThat(stopped).isEqualTo(CliError.General.FORBIDDEN);
        assertThat(CliError.exitCodeOf(stopped)).isEqualTo(ExitCode.UNAUTHORIZED);
        assertThat(out.lines()).isEmpty();
        assertThat(time.pauses()).isEmpty();
    }

    /// An answer that does not decode would be redelivered forever (it is never acked): stop with exit 69.
    @Test
    void watch_stops_onUndecodableAnswer() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});
        var undecodable = CliError.BadPayload.FACTORY.apply("/subscribers/cto/next?terminal=true",
                                                            Causes.cause("unknown field"));

        var stopped = watch(out.console(), answers(undecodable));

        assertThat(CliError.exitCodeOf(stopped)).isEqualTo(ExitCode.UNREACHABLE);
        assertThat(stopped).isEqualTo(undecodable);
        assertThat(time.pauses()).isEmpty();
    }

    /// `400`: the controller refuses the subscriber name; no retry can change that (exit 64).
    @Test
    void watch_stops_onRejectedSubscriber() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        var stopped = watch(out.console(), answers(CliError.Rejected.FACTORY.apply("Invalid subscriber: a b")));

        assertThat(CliError.exitCodeOf(stopped)).isEqualTo(ExitCode.USAGE);
        assertThat(time.pauses()).isEmpty();
    }

    /// The long-poll routes never answer an `error` message (a bad token is HTTP 401): outside the contract, exit 69.
    @Test
    void watch_stops_onErrorAnswer() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});

        var stopped = watch(out.console(), answers(new WsError("overloaded"), event(2)));

        assertThat(CliError.exitCodeOf(stopped)).isEqualTo(ExitCode.UNREACHABLE);
        assertThat(out.lines()).isEmpty();
        assertThat(time.pauses()).isEmpty();
    }

    /// A `4xx` on a poll (a controller without the long-poll routes answers `404`) only repeats: exit 69.
    @Test
    void watch_stops_onClientErrorStatus() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});
        var missingRoute = CliError.UnexpectedStatus.FACTORY.apply(404, "/subscribers/cto/next?terminal=true");

        var stopped = watch(out.console(), answers(missingRoute));

        assertThat(stopped).isEqualTo(missingRoute);
        assertThat(CliError.exitCodeOf(stopped)).isEqualTo(ExitCode.UNREACHABLE);
        assertThat(time.pauses()).isEmpty();
    }

    /// A watch runs for days: the poll loop is a loop, not a recursion. One watch sees 20 000 consecutive failed
    /// polls, 20 000 empty polls, 10 000 events, a failure, several more failed polls and a last few events. Every
    /// step here resolves at once, which is what nests a recursion on one stack: written as
    /// `step().flatMap(_ -> again())`, the loop overflows the stack (or hangs into the timeout).
    @Test
    void watch_survivesThousandsOfEventsAndReconnects() {
        var out = TestSupport.journaled(Integer.MAX_VALUE, _ -> {});
        var script = new ArrayList<Object>(Collections.nCopies(FAILED_POLLS, UNREACHABLE));

        script.addAll(Collections.nCopies(EMPTY_POLLS, EMPTY));
        script.addAll(events(1, LONG_RUN_EVENTS));
        script.add(OVERLOADED);
        script.addAll(Collections.nCopies(3, UNREACHABLE));
        script.addAll(events(LONG_RUN_EVENTS + 1, 5));
        script.add(STOP);

        var stopped = watch(out.console(), answers(script.toArray()));
        var events = LONG_RUN_EVENTS + 5;

        assertThat(stopped).isEqualTo(CliError.General.UNAUTHORIZED);
        assertThat(out.lines()).hasSize(events)
                               .endsWith(event(events).line());
        assertThat(journal.stream()
                          .filter(entry -> entry.startsWith("ack "))
                          .count()).isEqualTo(events);
        assertThat(time.pauses()).hasSize(FAILED_POLLS + 1 + 3);
    }

    /// Events `first … first + count - 1`.
    private static List<Object> events(long first, int count) {
        var script = new ArrayList<>();

        for (long seq = first; seq < first + count; seq++) {
            script.add(event(seq));
        }
        return script;
    }

    private static Duration seconds(long value) {
        return Duration.ofSeconds(value);
    }
}
