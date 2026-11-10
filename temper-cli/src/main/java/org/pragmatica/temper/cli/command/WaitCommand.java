package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Loop;
import org.pragmatica.temper.cli.Loop.Turn;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.Pacer;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `wait <job> [--timeout 6h]` (m0-design §8): polls `GET /jobs/{id}` every 2 s, doubling to at most 10 s,
/// until the job is terminal, then prints its verdict line and exits with the verdict's code. At the deadline
/// it prints the last state it saw and exits 7; no pause runs past the deadline. The polls run in a [Loop],
/// so a 6 h wait uses constant stack and memory.
///
/// Recovery: up to [#TOLERATED_FAILURES] consecutive transient failures ([CliError.Unreachable], or a 5xx
/// outside the route's contract) are absorbed, for the job read and the result read alike (FER: retry
/// forward through a controller restart; both are reads, so repeating them is harmless). One more is exit 69,
/// unless the deadline passed first and a state was seen: then it is exit 7 with that state. Every other
/// failure ends the wait at once.
public record WaitCommand(Controller api,
                          JsonMapper json,
                          CliCommand.Wait command,
                          InstantSource clock,
                          Pacer pacer,
                          Instant deadline) {
    public static final Duration FIRST_INTERVAL = Duration.ofSeconds(2);
    public static final Duration MAX_INTERVAL = Duration.ofSeconds(10);
    public static final int TOLERATED_FAILURES = 5;
    private static final int SERVER_ERROR = 500;

    /// One iteration's knowledge: the next poll interval, consecutive transient failures, the last state seen.
    record Poll(Duration interval, int failures, Option<JobView> last) {
        static Poll poll(Duration interval, int failures, Option<JobView> last) {
            return new Poll(interval, failures, last);
        }
    }

    public static Promise<Outcome> await(Controller api,
                                         JsonMapper json,
                                         CliCommand.Wait command,
                                         InstantSource clock,
                                         Pacer pacer) {
        var waiting = waitCommand(api,
                                  json,
                                  command,
                                  clock,
                                  pacer,
                                  clock.instant().plus(command.timeout()));

        return Loop.loop(Poll.poll(FIRST_INTERVAL, 0, none()), waiting::step);
    }

    static WaitCommand waitCommand(Controller api,
                                   JsonMapper json,
                                   CliCommand.Wait command,
                                   InstantSource clock,
                                   Pacer pacer,
                                   Instant deadline) {
        return new WaitCommand(api, json, command, clock, pacer, deadline);
    }

    private Promise<Turn<Poll, Outcome>> step(Poll poll) {
        return api.job(command.job())
                  .fold(read -> read.fold(cause -> afterFailure(cause, poll),
                                          view -> afterRead(view, poll)));
    }

    private Promise<Turn<Poll, Outcome>> afterRead(JobView view, Poll poll) {
        return view.state()
                   .isTerminal()
               ? finished(view, poll)
               : notYet(view, poll);
    }

    private Promise<Turn<Poll, Outcome>> notYet(JobView view, Poll poll) {
        return remaining().isPositive()
               ? pollAgain(Poll.poll(longer(poll.interval()), 0, some(view)),
                           poll.interval())
               : timedOut(view);
    }

    private Promise<Turn<Poll, Outcome>> afterFailure(Cause cause, Poll poll) {
        return isTolerable(cause, poll.failures())
               ? retryOrTimeOut(cause, poll)
               : cause.promise();
    }

    /// A tolerated failure past the deadline is the deadline: exit 7 with the last state seen (69 if none was).
    private Promise<Turn<Poll, Outcome>> retryOrTimeOut(Cause cause, Poll poll) {
        return remaining().isPositive()
               ? pollAgain(Poll.poll(poll.interval(), poll.failures() + 1, poll.last()),
                           poll.interval())
               : poll.last()
                     .map(this::timedOut)
                     .or(cause::promise);
    }

    private Promise<Turn<Poll, Outcome>> pollAgain(Poll next, Duration pause) {
        return pacer.pause(shorter(pause,
                                   remaining()))
                    .map(_ -> Turn.again(next));
    }

    private Promise<Turn<Poll, Outcome>> timedOut(JobView view) {
        return Rendered.state(view,
                              "wait",
                              ExitCode.WAIT_TIMEOUT,
                              command.json(),
                              json)
                       .map(Turn::<Poll, Outcome> done)
                       .async();
    }

    /// Shared with [GateCommand], which waits the same way.
    static boolean isTolerable(Cause cause, int failures) {
        return failures < TOLERATED_FAILURES && isTransient(cause);
    }

    private static boolean isTransient(Cause cause) {
        return switch (cause) {
            case CliError.Unreachable _ -> true;
            case CliError.UnexpectedStatus unexpected -> unexpected.status() >= SERVER_ERROR;
            default -> false;
        };
    }

    /// Terminal: the result record's verdict line; a job without one (REFUSED) shows its state and reason. The
    /// result read gets the same tolerance as the job read.
    private Promise<Turn<Poll, Outcome>> finished(JobView view, Poll poll) {
        return api.result(command.job())
                  .fold(read -> read.fold(cause -> afterFailure(cause,
                                                                Poll.poll(poll.interval(),
                                                                          poll.failures(),
                                                                          some(view))),
                                          found -> reported(view, found)));
    }

    private Promise<Turn<Poll, Outcome>> reported(JobView view, Option<ResultRecordDto> found) {
        return found.map(record -> Rendered.verdict(record,
                                                    Lines::waited,
                                                    command.json(),
                                                    json))
                    .or(() -> Rendered.state(view,
                                             "wait",
                                             command.json(),
                                             json))
                    .map(Turn::<Poll, Outcome> done)
                    .async();
    }

    private Duration remaining() {
        return Duration.between(clock.instant(), deadline);
    }

    static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0
               ? first
               : second;
    }

    static Duration longer(Duration interval) {
        return shorter(interval.multipliedBy(2), MAX_INTERVAL);
    }
}
