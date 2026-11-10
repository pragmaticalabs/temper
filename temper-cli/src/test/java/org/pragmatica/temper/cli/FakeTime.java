package org.pragmatica.temper.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// A clock that only moves when the code under test pauses: every [#pause] advances it by the requested
/// duration at once and is recorded, so poll intervals and backoffs are asserted without real waiting.
final class FakeTime implements InstantSource, Pacer {
    private volatile Instant now = Instant.parse("2026-10-13T10:00:00Z");
    private final List<Duration> pauses = new CopyOnWriteArrayList<>();

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public synchronized Promise<Unit> pause(Duration duration) {
        pauses.add(duration);
        now = now.plus(duration);
        return Promise.success(Unit.unit());
    }

    /// The monotonic counter: nanoseconds of fake time since the epoch, moved only by pauses, like [#instant].
    long nanos() {
        return Duration.between(Instant.EPOCH, now).toNanos();
    }

    List<Duration> pauses() {
        return List.copyOf(pauses);
    }
}
