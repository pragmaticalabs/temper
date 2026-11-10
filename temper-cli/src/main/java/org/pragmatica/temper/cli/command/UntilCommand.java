package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.Loop;
import org.pragmatica.temper.cli.Loop.Turn;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.Pacer;
import org.pragmatica.temper.cli.transport.FleetApi;
import org.pragmatica.temper.cli.transport.FleetLine;
import org.pragmatica.temper.cli.transport.FleetPosition;
import org.pragmatica.temper.cli.transport.FleetReply;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.pragmatica.lang.Option.some;


/// `events --until <kind,…> …` (design §2.4 and §3 L1): the agent form. The agent runs it in the
/// background and is woken by its exit; it parses nothing. There is no server cursor and no ack, so nothing is lost
/// when the agent dies: the next wait passes the seq it last read as `--after`.
///
/// Each turn calls the stateless `GET /fleet/events/after` (held up to 55 s), from `--after`, or from the head the
/// heartbeat names when `--after` is absent. The answer is judged in this order, and the first that holds ends the wait:
/// - an event or the heartbeat names another epoch than `--after`'s: 7 (a restored or replaced database);
/// - the heartbeat's `selfCheck.ok` is false: 6;
/// - a source the wait depends on is stale ([Dependencies]): 4;
/// - an event matches (its kind is one of `--until`, every `--match` pair holds, and it is not a catch-up row unless
///   `--include-catch-up`): the line is printed as received, exit 0. The CLI matches each line itself, besides the
///   controller, so a line the controller should not have sent cannot end the wait.
///
/// The heartbeat comes before the match (lead ruling F1: 4 beats 0): no match read while a dependency is stale can be
/// trusted, so a match that arrives with a stale dependency or a failed self-check ends the wait with 4 or 6 and is
/// not printed. A reply without its heartbeat cannot be judged: it is retried from the same position, its events
/// included, the clock not restarted.
///
/// Otherwise the position moves past the events received (never to the heartbeat's `seqHead`: an event committed
/// while the reply was built would be skipped), and the next turn asks again after [FleetPacing#IDLE].
///
/// Before every request: no heartbeat for 110 s by receipt time is 3, and `--timeout` reached is 5 ([FleetPacing]).
/// Every heartbeat is judged on receipt, and the last long-poll ends 1 s before `--timeout` ([#waitSeconds]), so 5
/// means nothing was wrong but time: a stale dependency or a failed self-check the controller reported by then is 4 or 6.
/// A failure [FleetApi#isFinal] names ends the wait with its own code; any other (unreachable, a 5xx, an answer that
/// does not decode or lacks its heartbeat) is retried with backoff until one of those clocks runs out. Recovery
/// strategy: FER (retry forward): the wait holds no state the next request cannot carry.
///
/// The loop is a [Loop]: constant stack and memory however long the wait.
public record UntilCommand(FleetApi api,
                           CliCommand.EventsUntil command,
                           Dependencies dependencies,
                           Console console,
                           InstantSource clock,
                           Pacer pacer,
                           Option<Instant> deadline) {
    /// How much earlier than `--timeout` the last long-poll's hold ends (lead ruling O2).
    static final Duration DEADLINE_MARGIN = Duration.ofSeconds(1);
    /// How long after `--timeout` a long-poll's answer is still awaited (lead ruling O2): the cap on the overshoot.
    static final Duration ANSWER_GRACE = Duration.ofSeconds(1);

    /// The loop state: the position to ask after (none until the head is known), when the last heartbeat arrived, and
    /// the pause before the next retry.
    record Waiting(Option<FleetPosition> after, Instant beat, Duration backoff) {
        static Waiting waiting(Option<FleetPosition> after, Instant started) {
            return new Waiting(after, started, FleetPacing.FIRST_BACKOFF);
        }

        /// A heartbeat arrived at `at`: the clock restarts and the backoff starts over.
        Waiting beaten(FleetPosition position, Instant at) {
            return new Waiting(some(position), at, FleetPacing.FIRST_BACKOFF);
        }

        Waiting at(FleetPosition position) {
            return new Waiting(some(position), beat, backoff);
        }

        Waiting backedOff() {
            return new Waiting(after, beat, FleetPacing.longer(backoff));
        }
    }

    public static Promise<Outcome> until(FleetApi api,
                                         CliCommand.EventsUntil command,
                                         Console console,
                                         InstantSource clock,
                                         Pacer pacer) {
        var started = clock.instant();
        var until = new UntilCommand(api,
                                     command,
                                     Dependencies.dependencies(command.kinds(), command.match()),
                                     console,
                                     clock,
                                     pacer,
                                     command.timeout().map(started::plus));

        return Loop.loop(Waiting.waiting(command.after(), started),
                         until::poll);
    }

    private Promise<Turn<Waiting, Outcome>> poll(Waiting state) {
        var now = clock.instant();

        return ended(now, state).map(UntilCommand::stop)
                    .or(() -> asked(now, state));
    }

    /// 3 before 5: a waiter that heard nothing for 110 s learns that, whatever its timeout.
    private Option<Cause> ended(Instant now, Waiting state) {
        return FleetPacing.isSilent(state.beat(), now)
               ? some(CliError.NoHeartbeat.FACTORY.apply(FleetPacing.SILENCE.toSeconds()))
               : expired(now);
    }

    private Option<Cause> expired(Instant now) {
        return deadline.filter(end -> !now.isBefore(end))
                       .map(_ -> timedOut());
    }

    private Cause timedOut() {
        return CliError.EventsTimeout.FACTORY.apply(command.timeout().map(Duration::toString).or(""));
    }

    private Promise<Turn<Waiting, Outcome>> asked(Instant now, Waiting state) {
        return state.after()
                    .map(after -> askedAfter(now, after, state))
                    .or(() -> askedHead(now, state));
    }

    private Promise<Turn<Waiting, Outcome>> askedHead(Instant now, Waiting state) {
        return api.heartbeat(FleetPacing.requestTimeout(0,
                                                        left(state, now)))
                  .fold(answer -> started(answer, state));
    }

    private Promise<Turn<Waiting, Outcome>> askedAfter(Instant now, FleetPosition after, Waiting state) {
        var wait = waitSeconds(now);

        return api.after(FleetApi.AfterQuery.afterQuery(after,
                                                        command.kinds(),
                                                        command.match().stream().map(EventMatch::text).toList(),
                                                        command.includeCatchUp()),
                         wait,
                         FleetPacing.requestTimeout(wait,
                                                    answerLeft(state, now)))
                  .fold(answer -> answered(answer, after, state));
    }

    /// The long-poll's hold: 55 s, or the whole seconds left of `--timeout` less one when fewer, at least 1 (lead
    /// ruling O2). Its answer bound ([#answerLeft]) reaches 1 s past `--timeout`, so the controller's heartbeat-only
    /// answer when the hold ends has a 2 s window and is judged (6, 4) like any other, instead of being cut off and
    /// reported as an unreachable controller; a controller that never answers overshoots `--timeout` by at most 1 s.
    private long waitSeconds(Instant now) {
        return Math.max(1,
                        Math.min(FleetPacing.LONG_POLL_SECONDS,
                                 deadline.map(end -> secondsBefore(now, end)).or(FleetPacing.LONG_POLL_SECONDS)));
    }

    /// Whole seconds from `now` to one second before `end`.
    private static long secondsBefore(Instant now, Instant end) {
        return Duration.between(now, end)
                       .minus(DEADLINE_MARGIN)
                       .toSeconds();
    }

    /// The head as the heartbeat names it, when `--after` was not given.
    private Promise<Turn<Waiting, Outcome>> started(Result<FleetLine.Heartbeat> answer, Waiting state) {
        return answer.fold(cause -> retryOrStop(cause, state), beat -> fromHead(beat, state, clock.instant()));
    }

    private Promise<Turn<Waiting, Outcome>> fromHead(FleetLine.Heartbeat beat, Waiting state, Instant received) {
        return verdict(beat).map(UntilCommand::stop)
                      .or(() -> Promise.success(Turn.again(state.beaten(FleetPosition.fleetPosition(beat.epoch(),
                                                                                                    beat.seqHead()),
                                                                        received))));
    }

    private Promise<Turn<Waiting, Outcome>> answered(Result<FleetReply> answer, FleetPosition after, Waiting state) {
        return answer.fold(cause -> retryOrStop(cause, state), reply -> examined(reply, after, state, clock.instant()));
    }

    private Promise<Turn<Waiting, Outcome>> examined(FleetReply reply,
                                                     FleetPosition after,
                                                     Waiting state,
                                                     Instant received) {
        return foreignEpoch(reply, after).map(UntilCommand::stop)
                           .or(() -> judged(reply, after, state, received));
    }

    /// The heartbeat first (lead ruling F1); a reply without one is retried from `after`, the clock not restarted.
    private Promise<Turn<Waiting, Outcome>> judged(FleetReply reply,
                                                   FleetPosition after,
                                                   Waiting state,
                                                   Instant received) {
        return reply.heartbeat()
                    .map(beat -> checked(beat, reply, after, state, received))
                    .or(() -> retryAfter(FleetApi.missingHeartbeat(FleetApi.AFTER_PATH),
                                         state.at(after)));
    }

    /// 6 or 4 before 0: the verdict, then the match.
    private Promise<Turn<Waiting, Outcome>> checked(FleetLine.Heartbeat beat,
                                                    FleetReply reply,
                                                    FleetPosition after,
                                                    Waiting state,
                                                    Instant received) {
        return verdict(beat).map(UntilCommand::stop)
                      .or(() -> matched(reply, after, state, received));
    }

    /// The first epoch in the reply (an event's or the heartbeat's) that is not `after`'s.
    private static Option<Cause> foreignEpoch(FleetReply reply, FleetPosition after) {
        return Option.from(Stream.concat(reply.events().stream().map(FleetLine.Event::epoch),
                                         reply.heartbeat().stream().map(FleetLine.Heartbeat::epoch))
                                 .filter(epoch -> !epoch.equals(after.epoch()))
                                 .findFirst()).map(epoch -> CliError.EpochChanged.FACTORY.apply(after.epoch(), epoch));
    }

    private Promise<Turn<Waiting, Outcome>> matched(FleetReply reply,
                                                    FleetPosition after,
                                                    Waiting state,
                                                    Instant received) {
        return Option.from(reply.events().stream().filter(this::isWanted).findFirst())
                     .map(UntilCommand::woken)
                     .or(() -> idle(state.beaten(past(after, reply),
                                                 received)));
    }

    private boolean isWanted(FleetLine.Event event) {
        return command.kinds()
                      .contains(event.kind())
               && (command.includeCatchUp() || !event.catchUp())
               && command.match()
                         .stream()
                         .allMatch(pair -> pair.holdsFor(event));
    }

    /// Printed by the CLI's own output step, which turns a closed stdout into exit 5.
    private static Promise<Turn<Waiting, Outcome>> woken(FleetLine.Event event) {
        return Promise.success(Turn.done(Outcome.outcome(event.line(), ExitCode.ACCEPTED)));
    }

    private static FleetPosition past(FleetPosition after, FleetReply reply) {
        return after.past(reply.events().stream().map(FleetLine.Event::seq).reduce(after.seq(), Math::max));
    }

    /// 6 before 4: a failed self-check says more than the staleness it may cause.
    private Option<Cause> verdict(FleetLine.Heartbeat beat) {
        return beat.selfCheckOk()
               ? dependencies.staleIn(beat)
                             .map(UntilCommand::stale)
               : some(CliError.SelfCheckFailed.FACTORY.apply(String.join(",", beat.failed())));
    }

    private static Cause stale(String source) {
        return CliError.SourceStale.FACTORY.apply(source);
    }

    private Promise<Turn<Waiting, Outcome>> idle(Waiting next) {
        return pacer.pause(bounded(FleetPacing.IDLE, next))
                    .map(_ -> Turn.again(next));
    }

    private Promise<Turn<Waiting, Outcome>> retryOrStop(Cause cause, Waiting state) {
        return FleetApi.isFinal(cause)
               ? cause.promise()
               : retryAfter(cause, state);
    }

    private Promise<Turn<Waiting, Outcome>> retryAfter(Cause cause, Waiting state) {
        var pause = bounded(state.backoff(), state);

        console.problem(Console.oneLine(cause) + "; retrying in " + pause.toSeconds() + "s");

        return pacer.pause(pause)
                    .map(_ -> Turn.again(state.backedOff()));
    }

    /// A long-poll's answer bound: what is left of the heartbeat clock, and of `--timeout` plus [#ANSWER_GRACE], whichever
    /// ends first ([FleetPacing#requestTimeout] adds the hold and slack cap).
    private Duration answerLeft(Waiting state, Instant now) {
        var silence = FleetPacing.silenceLeft(state.beat(), now);

        return FleetPacing.shortest(silence,
                                    deadline.map(end -> FleetPacing.nonNegative(Duration.between(now, end).plus(ANSWER_GRACE)))
                                            .or(silence));
    }

    /// `pause`, cut to what is left of the heartbeat clock and of `--timeout`, so the next turn ends the wait on time.
    private Duration bounded(Duration pause, Waiting state) {
        return FleetPacing.shortest(pause, left(state, clock.instant()));
    }

    /// What is left at `now` of the heartbeat clock and of `--timeout`, whichever ends first: a request's answer bound
    /// (headers and body, `HttpCall.withTimeout`) and every pause are cut to it.
    private Duration left(Waiting state, Instant now) {
        var silence = FleetPacing.silenceLeft(state.beat(), now);

        return FleetPacing.shortest(silence,
                                    deadline.map(end -> FleetPacing.nonNegative(Duration.between(now, end))).or(silence));
    }

    private static Promise<Turn<Waiting, Outcome>> stop(Cause cause) {
        return cause.promise();
    }
}
