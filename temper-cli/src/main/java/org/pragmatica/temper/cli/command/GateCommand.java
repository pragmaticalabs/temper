package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.Loop;
import org.pragmatica.temper.cli.Loop.Turn;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.Pacer;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.protocol.api.GateView;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `gate <sha> [--wait] [--timeout 6h] [--json]` (m2-design §1.5): one `GET /gate/{sha}`, printed as its one line
/// ([Lines#gate]) or, with `--json`, as the `GateView`; the exit code is the answer's ([ExitCode#forGate]): PASSED 0,
/// FAILED 1, EMPTY 3, NONE 8, RUNNING 9, CONFLICT 11.
///
/// With `--wait` it polls as `wait` does ([WaitCommand]: every 2 s, doubling to at most 10 s, in a [Loop]) while the
/// answer is RUNNING, and prints the first other answer; NONE and CONFLICT return at once, since there is nothing to
/// wait for. At the deadline (`--timeout`, default 6 h) it prints the last answer it saw and exits 7; no pause runs past
/// the deadline. Recovery as `wait`'s, through the same rule ([WaitCommand#isTolerable]): up to
/// [WaitCommand#TOLERATED_FAILURES] consecutive transient failures (the controller unreachable, or a 5xx) are absorbed (FER: retry forward through a controller restart; the read is
/// idempotent); one more is exit 69, unless the deadline passed first and an answer was seen (exit 7 with it). Without
/// `--wait` nothing is retried.
public record GateCommand(Controller api,
                          JsonMapper json,
                          CliCommand.Gate command,
                          InstantSource clock,
                          Pacer pacer,
                          Instant deadline) {
    /// One iteration's knowledge: the next poll interval, consecutive transient failures, the last answer seen.
    record Poll(Duration interval, int failures, Option<GateView> last) {
        static Poll poll(Duration interval, int failures, Option<GateView> last) {
            return new Poll(interval, failures, last);
        }
    }

    public static Promise<Outcome> gate(Controller api,
                                        JsonMapper json,
                                        CliCommand.Gate command,
                                        InstantSource clock,
                                        Pacer pacer) {
        var gating = gateCommand(api,
                                 json,
                                 command,
                                 clock,
                                 pacer,
                                 clock.instant().plus(command.timeout()));

        return command.waiting()
               ? Loop.loop(Poll.poll(WaitCommand.FIRST_INTERVAL, 0, none()), gating::step)
               : gating.once();
    }

    static GateCommand gateCommand(Controller api,
                                   JsonMapper json,
                                   CliCommand.Gate command,
                                   InstantSource clock,
                                   Pacer pacer,
                                   Instant deadline) {
        return new GateCommand(api, json, command, clock, pacer, deadline);
    }

    private Promise<Outcome> once() {
        return api.gate(command.sha())
                  .flatMap(view -> answered(view).async());
    }

    private Promise<Turn<Poll, Outcome>> step(Poll poll) {
        return api.gate(command.sha())
                  .fold(read -> read.fold(cause -> afterFailure(cause, poll),
                                          view -> afterRead(view, poll)));
    }

    private Promise<Turn<Poll, Outcome>> afterRead(GateView view, Poll poll) {
        return view.answer() == GateAnswer.RUNNING
               ? notYet(view, poll)
               : done(answered(view));
    }

    private Promise<Turn<Poll, Outcome>> notYet(GateView view, Poll poll) {
        return remaining().isPositive()
               ? pollAgain(Poll.poll(WaitCommand.longer(poll.interval()),
                                     0,
                                     some(view)),
                           poll.interval())
               : done(timedOut(view));
    }

    private Promise<Turn<Poll, Outcome>> afterFailure(Cause cause, Poll poll) {
        return WaitCommand.isTolerable(cause, poll.failures())
               ? retryOrTimeOut(cause, poll)
               : cause.promise();
    }

    /// A tolerated failure past the deadline is the deadline: exit 7 with the last answer seen (69 if none was).
    private Promise<Turn<Poll, Outcome>> retryOrTimeOut(Cause cause, Poll poll) {
        return remaining().isPositive()
               ? pollAgain(Poll.poll(poll.interval(), poll.failures() + 1, poll.last()),
                           poll.interval())
               : poll.last()
                     .map(view -> done(timedOut(view)))
                     .or(cause::promise);
    }

    private Promise<Turn<Poll, Outcome>> pollAgain(Poll next, Duration pause) {
        return pacer.pause(WaitCommand.shorter(pause,
                                               remaining()))
                    .map(_ -> Turn.again(next));
    }

    private Result<Outcome> answered(GateView view) {
        return rendered(view,
                        ExitCode.forGate(view.answer()));
    }

    private Result<Outcome> timedOut(GateView view) {
        return rendered(view, ExitCode.WAIT_TIMEOUT);
    }

    private Result<Outcome> rendered(GateView view, ExitCode exit) {
        return Rendered.text(view,
                             Lines.gate(view),
                             command.json(),
                             json)
                       .map(text -> Outcome.outcome(text, exit));
    }

    private static Promise<Turn<Poll, Outcome>> done(Result<Outcome> outcome) {
        return outcome.map(Turn::<Poll, Outcome> done)
                      .async();
    }

    private Duration remaining() {
        return Duration.between(clock.instant(), deadline);
    }
}
