package org.pragmatica.temper.cli.command;

import java.time.Duration;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.Loop;
import org.pragmatica.temper.cli.Loop.Turn;
import org.pragmatica.temper.cli.Pacer;
import org.pragmatica.temper.cli.transport.Subscription;
import org.pragmatica.temper.protocol.api.WsServerMessage;
import org.pragmatica.temper.protocol.api.WsServerMessage.WsError;
import org.pragmatica.temper.protocol.api.WsServerMessage.WsEvent;
import org.pragmatica.temper.protocol.api.WsServerMessage.WsGap;

import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.utils.Causes.cause;


/// `watch [--terminal] --subscriber <name>` (spec §8.3, m0-design §6.5 and §8), over the controller's long-poll
/// [Subscription].
///
/// **Order per event: print the line, flush, then acknowledge.** The controller advances the cursor only on
/// the ack, so a process killed between print and ack gets the same `seq` again on its next poll, or the next
/// watcher's (at-least-once; the line carries `seq=` so a reader can drop the repeat). A line that cannot be
/// flushed is never acknowledged: the watch stops with exit 5 and the event is redelivered to the next watcher.
///
/// **Dedup within one process:** an event whose `seq` this process already printed (redelivered because its ack
/// failed) is acknowledged without printing it again.
///
/// **Gaps:** `gap from=<seq> to=<seq>` is printed as its own line when retention passed the cursor, then
/// acknowledged at `to`, since the controller answers the same gap until the cursor passes it. A redelivered gap
/// is acknowledged without printing it again, exactly like an event.
///
/// **An empty poll is not a failure:** a `204` (nothing within the controller's ~30 s window) is asked again at
/// once, backoff unchanged.
///
/// **Dedup and backoff are loop state** ([Watching]), carried from one poll to the next: no field changes.
///
/// **Retries** run forever with backoff 1 s doubling to 60 s. The backoff resets only after an acknowledgement
/// succeeded, so a controller that answers polls but fails every ack (or keeps failing) is retried ever more
/// slowly, not every second. Recovery strategy: FER (retry forward): the subscription holds no client state the
/// controller cannot rebuild from the cursor.
///
/// **Stopping causes:** `401` (exit 77, Q-15); `403` (exit 77: the read token, which may not create or advance a
/// subscriber, D3); a closed stdout (exit 5); an answer outside the route's contract
/// (exit 69: a body that does not decode, an `error` message, or a `4xx` such as the `404` of a controller without
/// the long-poll routes; the next poll would only repeat it, since nothing is acknowledged); and a rejected request
/// (`400`, exit 64: the controller refuses the subscriber name, which no retry changes). Everything else is retried:
/// unreachable, `5xx`, and the `404` of an ack whose subscriber vanished (the next poll recreates it).
///
/// The poll loop is a [Loop]: a watch that runs for days uses constant stack and memory.
public record WatchCommand(Subscription subscription, Console console, Pacer pacer) {
    public static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    public static final Duration MAX_BACKOFF = Duration.ofSeconds(60);
    private static final int CLIENT_ERRORS = 400;
    private static final int SERVER_ERRORS = 500;

    /// The loop state: the pause before the next retry, and the highest `seq` this process printed (dedup).
    record Watching(Duration backoff, long printed) {
        static Watching watching() {
            return new Watching(FIRST_BACKOFF, Long.MIN_VALUE);
        }

        Watching printedUpTo(long seq) {
            return new Watching(backoff, Math.max(printed, seq));
        }

        /// An acknowledgement succeeded: the next failure starts the backoff over.
        Watching progressed() {
            return new Watching(FIRST_BACKOFF, printed);
        }

        Watching backedOff() {
            return new Watching(longer(backoff), printed);
        }
    }

    /// Runs until stopped; the returned promise only ever fails, with the cause that stopped it.
    public static Promise<Unit> watch(Subscription subscription, Console console, Pacer pacer) {
        var watching = watchCommand(subscription, console, pacer);

        return Loop.loop(Watching.watching(), watching::poll);
    }

    static WatchCommand watchCommand(Subscription subscription, Console console, Pacer pacer) {
        return new WatchCommand(subscription, console, pacer);
    }

    /// One long-poll.
    private Promise<Turn<Watching, Unit>> poll(Watching state) {
        return subscription.next()
                           .fold(polled -> polled(polled, state));
    }

    private Promise<Turn<Watching, Unit>> polled(Result<Option<WsServerMessage>> polled, Watching state) {
        return polled.fold(cause -> retryOrStop(cause, state), answer -> answered(answer, state));
    }

    /// An empty poll (`204`) changes nothing and asks again.
    private Promise<Turn<Watching, Unit>> answered(Option<WsServerMessage> answer, Watching state) {
        return answer.map(message -> react(message, state))
                     .or(() -> Promise.success(again(state)));
    }

    private Promise<Turn<Watching, Unit>> react(WsServerMessage message, Watching state) {
        return switch (message) {
            case WsEvent event -> deliver(event.seq(), event.line(), state);
            case WsGap gap -> deliver(gap.to(), Lines.gap(gap), state);
            case WsError error -> unexpected(error);
        };
    }

    /// Prints the line (unless this process already printed up to `seq`), then acknowledges `seq`. A printed line
    /// stays printed when its ack fails, so the redelivery is acknowledged without printing it again.
    private Promise<Turn<Watching, Unit>> deliver(long seq, String line, Watching state) {
        return print(seq, line, state.printed()).fold(cause -> retryOrStop(cause, state),
                                                      _ -> acknowledge(seq, state.printedUpTo(seq)));
    }

    private Result<Unit> print(long seq, String line, long printed) {
        return seq <= printed
               ? unitResult()
               : console.line(line);
    }

    private Promise<Turn<Watching, Unit>> acknowledge(long seq, Watching state) {
        return subscription.ack(seq)
                           .fold(acked -> acked(acked, state));
    }

    private Promise<Turn<Watching, Unit>> acked(Result<Unit> acked, Watching state) {
        return acked.fold(cause -> retryOrStop(cause, state),
                          _ -> Promise.success(again(state.progressed())));
    }

    private static Turn<Watching, Unit> again(Watching state) {
        return Turn.again(state);
    }

    private Promise<Turn<Watching, Unit>> retryOrStop(Cause cause, Watching state) {
        return isFatal(cause)
               ? cause.promise()
               : retryAfter(cause, state);
    }

    private Promise<Turn<Watching, Unit>> retryAfter(Cause cause, Watching state) {
        console.problem(Console.oneLine(cause) + "; retrying in " + state.backoff().toSeconds() + "s");

        return pacer.pause(state.backoff())
                    .map(_ -> Turn.again(state.backedOff()));
    }

    private static boolean isFatal(Cause cause) {
        return switch (cause) {
            case CliError.General.UNAUTHORIZED, CliError.General.FORBIDDEN, CliError.General.OUTPUT_CLOSED -> true;
            case CliError.BadPayload _, CliError.Rejected _ -> true;
            // D-6: a controller of another build is refused for good (exit 13), from the poll's answer or a 412 ack.
            case CliError.VersionSkew _ -> true;
            case CliError.UnexpectedStatus status -> isClientError(status.status());
            default -> false;
        };
    }

    private static boolean isClientError(int status) {
        return status >= CLIENT_ERRORS && status < SERVER_ERRORS;
    }

    /// The long-poll routes answer an event, a gap or nothing (a bad token is HTTP `401`); an `error` message is
    /// outside their contract, like a body that does not decode.
    private static Promise<Turn<Watching, Unit>> unexpected(WsError error) {
        return CliError.BadPayload.FACTORY.apply("event poll",
                                                 cause("unexpected error answer: " + error.error()))
                                          .promise();
    }

    private static Duration longer(Duration backoff) {
        var doubled = backoff.multipliedBy(2);

        return doubled.compareTo(MAX_BACKOFF) <= 0
               ? doubled
               : MAX_BACKOFF;
    }
}
