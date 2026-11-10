package org.pragmatica.kiln.service.loop;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// One loop's timer (kiln-f1-design §1.2, based on temper's `TaskSweeper`): `tick` every `period` after `delay`, on the
/// shared scheduler. Each tick is the loop's own single-flight run, so a tick while the previous one is in flight is
/// skipped and counted there, never queued here. A tick that throws or fails is logged and the timer goes on: an
/// exception escaping a fixed-rate task would cancel every later run.
public final class Periodic {
    private static final Logger log = LoggerFactory.getLogger(Periodic.class);

    private final String name;
    private final Duration delay;
    private final Duration period;
    private final Supplier<Promise<?>> tick;
    private final AtomicReference<Option<ScheduledFuture<?>>> scheduled = new AtomicReference<>(none());

    private Periodic(String name, Duration delay, Duration period, Supplier<Promise<?>> tick) {
        this.name = name;
        this.delay = delay;
        this.period = period;
        this.tick = tick;
    }

    public static Periodic periodic(String name, Duration delay, Duration period, Supplier<Promise<?>> tick) {
        return new Periodic(name, delay, period, tick);
    }

    /// Whether the timer is running (started and not stopped).
    public boolean running() {
        return scheduled.get()
                        .isPresent();
    }

    public String name() {
        return name;
    }

    public Duration period() {
        return period;
    }

    /// Starts the timer; a second start replaces nothing (the first timer runs on).
    public Unit start() {
        return scheduled.get()
                        .isPresent()
               ? unit()
               : started();
    }

    private Unit started() {
        scheduled.set(some(SharedScheduler.scheduleAtFixedRate(this::fire,
                                                               TimeSpan.fromDuration(delay),
                                                               TimeSpan.fromDuration(period))));

        return unit();
    }

    /// Cancels the timer; a tick in flight runs to its end.
    public Unit stop() {
        scheduled.getAndSet(none()).onPresent(future -> future.cancel(false));

        return unit();
    }

    /// One tick, never throwing: a synchronous throw and a failed run are logged.
    Unit fire() {
        Result.lift(Causes::fromThrowable, tick::get)
              .onFailure(cause -> log.warn("{} tick threw: {}",
                                           name,
                                           cause.message()))
              // May run late: the tick is detached; the loop's own guard decides whether the next one runs.
              .onSuccess(run -> run.onFailure(cause -> log.warn("{} tick failed: {}",
                                                                name,
                                                                cause.message())));

        return unit();
    }
}
