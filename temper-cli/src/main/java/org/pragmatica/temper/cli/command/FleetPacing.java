package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import org.pragmatica.temper.cli.CliError;


/// The clocks both `events` forms share (design §3 L1).
///
/// **The heartbeat clock** runs on this process's receipt time: it restarts when a reply's heartbeat line arrives, never
/// on any other byte, and never from the controller's `asOf` (a controller whose clock is wrong, or that replays an old
/// line, must not keep a waiter alive). [#SILENCE] without a heartbeat ends the wait with [CliError.NoHeartbeat] (3).
///
/// **Long-polls** ask the controller to hold up to [#LONG_POLL_SECONDS], and each request's answer bound is that plus
/// [#SLACK], cut to the time left before the heartbeat deadline: a hung controller cannot hold a waiter past it.
///
/// **Retries** pause 1 s doubling to 30 s, cut the same way, so the deadline is checked on time. An answer that brought
/// nothing new is followed by [#IDLE]: a controller that answers at once (or ignores `wait`) is not polled in a hot loop.
sealed interface FleetPacing {
    Duration SILENCE = Duration.ofSeconds(110);
    long LONG_POLL_SECONDS = 55;
    Duration SLACK = Duration.ofSeconds(5);
    Duration IDLE = Duration.ofSeconds(1);
    Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    Duration MAX_BACKOFF = Duration.ofSeconds(30);
    /// The shortest answer bound a request gets, even right before the deadline.
    Duration SHORTEST_REQUEST = Duration.ofSeconds(1);

    /// The heartbeat clock has run out at `now`.
    static boolean isSilent(Instant beat, Instant now) {
        return Duration.between(beat, now).compareTo(SILENCE) >= 0;
    }

    /// What is left of the heartbeat clock at `now`, never negative.
    static Duration silenceLeft(Instant beat, Instant now) {
        return nonNegative(Duration.between(now, beat.plus(SILENCE)));
    }

    /// The answer bound of a long-poll held up to `wait` seconds, cut to `left`.
    static Duration requestTimeout(long wait, Duration left) {
        return longest(SHORTEST_REQUEST,
                       shortest(Duration.ofSeconds(wait).plus(SLACK),
                                left));
    }

    static Duration longer(Duration backoff) {
        return shortest(backoff.multipliedBy(2), MAX_BACKOFF);
    }

    static Duration shortest(Duration first, Duration... others) {
        return Stream.of(others).reduce(first, FleetPacing::shorter);
    }

    private static Duration shorter(Duration first, Duration second) {
        return first.compareTo(second) <= 0
               ? first
               : second;
    }

    private static Duration longest(Duration first, Duration second) {
        return first.compareTo(second) >= 0
               ? first
               : second;
    }

    static Duration nonNegative(Duration duration) {
        return longest(duration, Duration.ZERO);
    }

    record unused() implements FleetPacing {}
}
