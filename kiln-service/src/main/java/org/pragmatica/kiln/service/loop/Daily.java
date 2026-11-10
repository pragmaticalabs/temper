package org.pragmatica.kiln.service.loop;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// A run once a day at a local time of day (kiln-f1-design §1.2, based on temper's `DumpSchedule`): the full pass at
/// `[scan] fullAt`. Each run schedules the next from the clock, so a slow run or a clock change never drifts it. The run
/// is the loop's own single-flight one; a failure is logged by [Periodic#fire]'s rule.
public final class Daily {
    private final Periodic run;
    private final LocalTime at;
    private final ZoneId zone;
    private final Supplier<Instant> clock;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<Option<ScheduledFuture<?>>> next = new AtomicReference<>(none());

    private Daily(Periodic run, LocalTime at, ZoneId zone, Supplier<Instant> clock) {
        this.run = run;
        this.at = at;
        this.zone = zone;
        this.clock = clock;
    }

    public static Daily daily(String name,
                              LocalTime at,
                              ZoneId zone,
                              Supplier<Instant> clock,
                              Supplier<Promise<?>> run) {
        return new Daily(Periodic.periodic(name, Duration.ZERO, Duration.ofDays(1), run),
                         at,
                         zone,
                         clock);
    }

    /// Whether a next run is scheduled.
    public boolean scheduled() {
        return next.get()
                   .isPresent();
    }

    public String name() {
        return run.name();
    }

    public Unit start() {
        stopped.set(false);

        return scheduleNext();
    }

    public Unit stop() {
        stopped.set(true);
        next.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// The time from `now` to the next `at` in `now`'s zone; a run due exactly now is a day away.
    static Duration untilNext(LocalTime at, ZonedDateTime now) {
        var today = now.with(at);

        return Duration.between(now,
                                today.isAfter(now)
                                ? today
                                : today.plusDays(1));
    }

    private Unit scheduleNext() {
        var delay = untilNext(at,
                              ZonedDateTime.ofInstant(clock.get(), zone));

        return stopped.get()
               ? unit()
               : scheduled(SharedScheduler.schedule(this::fire, TimeSpan.fromDuration(delay)));
    }

    private Unit scheduled(ScheduledFuture<?> future) {
        next.set(some(future));

        return unit();
    }

    private void fire() {
        run.fire();
        scheduleNext();
    }
}
