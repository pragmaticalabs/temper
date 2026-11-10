package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.Loop;
import org.pragmatica.temper.cli.Loop.Turn;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.Pacer;
import org.pragmatica.temper.cli.transport.FleetApi;
import org.pragmatica.temper.cli.transport.FleetLine;
import org.pragmatica.temper.cli.transport.FleetReply;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.pragmatica.lang.Result.unitResult;


/// `events --subscriber <name> --follow [--format text]` (design §2.4): the long-running form, for
/// a reader that wakes per stdout line (the dispatcher, the Monitor tool), over the subscriber's server-side cursor.
///
/// **Order per event: print the line, flush, then ack its seq.** The cursor moves only on the ack, so a process killed
/// between print and ack gets the same seq again on the next poll: delivery is **at-least-once to this process's
/// stdout, in seq order** — not to an agent: a reader that dies has lost what was printed and acked. A line that cannot
/// be flushed is never acked: the follow stops with exit 5. A line this process already printed (redelivered after its
/// ack failed) is acked without printing it again; "already printed" is by (epoch, seq), so after a restore the new
/// epoch's lines, `epoch-changed` first, are printed although their seqs start again below.
///
/// **An ack names the epoch its line was read in** (the epoch of the heartbeat that ended the reply; lead ruling F2).
/// The controller refuses it with 409 once the epoch has changed (a restore between print and ack), the cursor
/// unchanged: the rest of the old batch is dropped and the next poll reads again from the cursor, so the new epoch's
/// lines, `epoch-changed` first, are delivered and none is skipped by an old seq. A reply with events but no heartbeat
/// names no epoch: it is not delivered, and is retried.
///
/// Each poll is `GET /fleet/events` held up to 55 s; the reply's lines are delivered one by one, the next poll waits
/// until all are acked. The heartbeat restarts the heartbeat clock ([FleetPacing]), and so does each answered ack while a batch
/// is delivered; 110 s without either is exit 3. It does
/// not end the follow on a stale source or a failed self-check: the stream itself carries `source-stale` and
/// `self-check-failed` lines for its reader.
///
/// Stopping causes are those [FleetApi#isFinal] names: 401 and 403 (77), a closed stdout (5), an unknown subscriber (404,
/// 64), another controller build (13). Everything else is retried with backoff 1 s doubling to 30 s, reset by an ack.
/// Recovery strategy: FER (retry forward): the cursor is the controller's, so nothing here needs rebuilding.
///
/// The loop is a [Loop]: a follow that runs for days uses constant stack and memory.
public record FollowCommand(FleetApi api,
                            CliCommand.EventsFollow command,
                            Console console,
                            InstantSource clock,
                            Pacer pacer) {
    /// How long one ack may take.
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);
    /// How long `--create` may take.
    static final Duration CREATE_TIMEOUT = Duration.ofSeconds(30);

    /// The loop state: the reply's events not yet acked and the epoch they were read in (its heartbeat's), the epoch and
    /// the highest seq in it this process printed (dedup: a seq means something only within its epoch),
    /// when the clock was last armed, and the pause before the next retry.
    record Following(List<FleetLine.Event> pending,
                     String readIn,
                     String epoch,
                     long printed,
                     Instant beat,
                     Duration backoff) {
        static Following following(Instant started) {
            return new Following(List.of(), "", "", Long.MIN_VALUE, started, FleetPacing.FIRST_BACKOFF);
        }

        /// A reply's events to deliver, read in `readIn`; its heartbeat arrived at `at`.
        Following received(List<FleetLine.Event> events, String readIn, Instant at) {
            return new Following(events, readIn, epoch, printed, at, backoff);
        }

        /// This process already printed `event`: the same epoch, at or below the seq printed in it. A line of another
        /// epoch (after a restore its seqs start again below) is never taken for a repeat.
        boolean isPrinted(FleetLine.Event event) {
            return epoch.equals(event.epoch()) && event.seq() <= printed;
        }

        /// `event` printed: within the same epoch the mark only rises; a new epoch starts the mark over at its seq.
        Following printed(FleetLine.Event event) {
            return new Following(pending,
                                 readIn,
                                 event.epoch(),
                                 epoch.equals(event.epoch())
                                 ? Math.max(printed, event.seq())
                                 : event.seq(),
                                 beat,
                                 backoff);
        }

        /// The head was acked at `at`: the rest remain, the next failure starts the backoff over, and the clock is armed
        /// again (the controller answered; a long batch or a slow reader must not read as silence).
        Following delivered(Instant at) {
            return new Following(pending.subList(1, pending.size()),
                                 readIn,
                                 epoch,
                                 printed,
                                 at,
                                 FleetPacing.FIRST_BACKOFF);
        }

        /// The ack was refused at `at` because the epoch changed: the rest of the batch is dropped, the next poll reads
        /// again from the cursor; the printed mark stays (dedup by epoch keeps the new epoch's lines printable).
        Following dropped(Instant at) {
            return new Following(List.of(), readIn, epoch, printed, at, FleetPacing.FIRST_BACKOFF);
        }

        Following backedOff() {
            return new Following(pending, readIn, epoch, printed, beat, FleetPacing.longer(backoff));
        }
    }

    /// Runs until stopped; the returned promise only ever fails, with the cause that stopped it.
    public static Promise<Unit> follow(FleetApi api,
                                       CliCommand.EventsFollow command,
                                       Console console,
                                       InstantSource clock,
                                       Pacer pacer) {
        var following = new FollowCommand(api, command, console, clock, pacer);

        return Loop.loop(Following.following(clock.instant()),
                         following::step);
    }

    /// `events --create <name> [--start …]`: one `POST /fleet/subscribers`, then one line.
    public static Promise<Outcome> create(FleetApi api, CliCommand.EventsCreate create) {
        return api.create(create.name(),
                          create.start(),
                          create.bindTo(),
                          CREATE_TIMEOUT)
                  .map(_ -> Outcome.outcome("events subscriber " + create.name()
                                           + " created start=" + create.start()
                                           + " bind-to=" + create.bindTo(),
                                            ExitCode.ACCEPTED));
    }

    private Promise<Turn<Following, Unit>> step(Following state) {
        var now = clock.instant();

        return FleetPacing.isSilent(state.beat(), now)
               ? CliError.NoHeartbeat.FACTORY.apply(FleetPacing.SILENCE.toSeconds()).promise()
               : next(now, state);
    }

    private Promise<Turn<Following, Unit>> next(Instant now, Following state) {
        return state.pending()
                    .isEmpty()
               ? poll(now, state)
               : deliver(state.pending().getFirst(),
                         state);
    }

    private Promise<Turn<Following, Unit>> poll(Instant now, Following state) {
        return api.events(command.subscriber(),
                          command.text(),
                          FleetPacing.LONG_POLL_SECONDS,
                          FleetPacing.requestTimeout(FleetPacing.LONG_POLL_SECONDS,
                                                     FleetPacing.silenceLeft(state.beat(),
                                                                             now)))
                  .fold(answer -> polled(answer, state));
    }

    private Promise<Turn<Following, Unit>> polled(Result<FleetReply> answer, Following state) {
        return answer.fold(cause -> retryOrStop(cause, state), reply -> received(reply, state, clock.instant()));
    }

    /// A reply without its heartbeat names no epoch to ack in: retried, nothing delivered, the clock not restarted.
    private Promise<Turn<Following, Unit>> received(FleetReply reply, Following state, Instant at) {
        return reply.heartbeat()
                    .map(beat -> beaten(reply, beat, state, at))
                    .or(() -> retryAfter(FleetApi.missingHeartbeat(FleetApi.EVENTS_PATH),
                                         state));
    }

    /// Events are delivered at once, read in the heartbeat's epoch; a reply with none waits [FleetPacing#IDLE]. Either
    /// way the heartbeat restarts the clock.
    private Promise<Turn<Following, Unit>> beaten(FleetReply reply,
                                                  FleetLine.Heartbeat beat,
                                                  Following state,
                                                  Instant at) {
        var next = state.received(reply.events(), beat.epoch(), at);

        return reply.events()
                    .isEmpty()
               ? pacer.pause(FleetPacing.IDLE)
                      .map(_ -> Turn.again(next))
               : Promise.success(Turn.again(next));
    }

    /// Prints the head (unless this process already printed up to its seq), then acks it.
    private Promise<Turn<Following, Unit>> deliver(FleetLine.Event event, Following state) {
        return print(event, state).fold(cause -> retryOrStop(cause, state),
                                        _ -> acknowledge(event, state.printed(event)));
    }

    private Result<Unit> print(FleetLine.Event event, Following state) {
        return state.isPrinted(event)
               ? unitResult()
               : console.line(event.line());
    }

    private Promise<Turn<Following, Unit>> acknowledge(FleetLine.Event event, Following state) {
        return api.ack(command.subscriber(),
                       state.readIn(),
                       event.seq(),
                       ACK_TIMEOUT)
                  .fold(acked -> acked(acked, state));
    }

    private Promise<Turn<Following, Unit>> acked(Result<Unit> acked, Following state) {
        return acked.fold(cause -> refused(cause, state),
                          _ -> Promise.success(Turn.again(state.delivered(clock.instant()))));
    }

    /// An ack refused for its epoch is an epoch boundary, not a stop (lead ruling F2); anything else as any failure.
    private Promise<Turn<Following, Unit>> refused(Cause cause, Following state) {
        return cause instanceof CliError.AckEpochChanged
               ? reread(cause, state)
               : retryOrStop(cause, state);
    }

    /// FER (degrade forward): the old batch is dropped and read again from the controller's cursor, which the refused
    /// ack left where it was, so nothing after it is lost; a line already printed is acked, not printed twice.
    private Promise<Turn<Following, Unit>> reread(Cause cause, Following state) {
        console.problem(Console.oneLine(cause) + "; the rest of its batch is dropped and read again");

        return Promise.success(Turn.again(state.dropped(clock.instant())));
    }

    private Promise<Turn<Following, Unit>> retryOrStop(Cause cause, Following state) {
        return FleetApi.isFinal(cause)
               ? cause.promise()
               : retryAfter(cause, state);
    }

    private Promise<Turn<Following, Unit>> retryAfter(Cause cause, Following state) {
        var pause = FleetPacing.shortest(state.backoff(),
                                         FleetPacing.silenceLeft(state.beat(), clock.instant()));

        console.problem(Console.oneLine(cause) + "; retrying in " + pause.toSeconds() + "s");

        return pacer.pause(pause)
                    .map(_ -> Turn.again(state.backedOff()));
    }
}
