package org.pragmatica.temper.worker.unit;

import java.util.concurrent.ScheduledFuture;

import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import static org.pragmatica.lang.Unit.unit;


/// One-shot scheduling, replaceable by a manual clock in tests (`UnitTimerTest`).
@FunctionalInterface
public interface Timers {
    Scheduled schedule(Runnable task, TimeSpan delay);

    /// A scheduled task that can still be cancelled.
    @FunctionalInterface
    interface Scheduled {
        Unit cancel();
    }

    /// Production: `SharedScheduler` (one timer thread; task bodies run on virtual threads).
    static Timers shared() {
        return (task, delay) -> cancellable(SharedScheduler.schedule(task, delay));
    }

    private static Scheduled cancellable(ScheduledFuture<?> future) {
        return () -> cancelled(future);
    }

    private static Unit cancelled(ScheduledFuture<?> future) {
        future.cancel(false);

        return unit();
    }
}
