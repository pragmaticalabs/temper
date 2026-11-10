package org.pragmatica.temper.worker.unit;

import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Epoch nanoseconds, the text form of `marker`, `start` and `end` (spec §4.4). Exact arithmetic: an
/// overflow (after the year 2262) is a failure, never a wrapped value.
public sealed interface EpochNanos {
    /// `seconds * 1e9 + nanos` of `instant`.
    static Result<Long> epochNanos(Instant instant) {
        return Result.lift(Causes::fromThrowable,
                           () -> Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                                               instant.getNano()));
    }

    /// The instant `nanos` after the epoch.
    static Instant instant(long nanos) {
        return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L));
    }

    /// `start + duration`, exactly.
    static Result<Long> plus(long startNanos, Duration duration) {
        return Result.lift(Causes::fromThrowable,
                           () -> Math.addExact(startNanos, duration.toNanos()));
    }

    record unused() implements EpochNanos {}
}
