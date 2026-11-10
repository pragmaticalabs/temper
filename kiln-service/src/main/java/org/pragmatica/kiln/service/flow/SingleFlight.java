package org.pragmatica.kiln.service.flow;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;


/// One loop's guard (kiln-f1-design §3.1): a tick while the previous run is in flight is skipped and counted. Every exit
/// releases it (the WP6 lesson: fail the tick and free the next): a synchronous throw fails the tick, and a run that has
/// not settled by `deadline` fails it with a timeout, so a promise that never settles cannot hold the loop.
public final class SingleFlight {
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong skipped = new AtomicLong();

    public static SingleFlight singleFlight() {
        return new SingleFlight();
    }

    /// The run's result, or none when a run was still in flight (the tick is skipped).
    public <T> Promise<Option<T>> run(Supplier<Promise<T>> operation, Duration deadline) {
        return running.compareAndSet(false, true)
               ? bounded(operation, deadline).map(Option::some)
                        .withResult(_ -> running.set(false))
               : Promise.success(skippedRun());
    }

    /// The ticks skipped because a run was still in flight.
    public long skipped() {
        return skipped.get();
    }

    /// `operation` started now, with a synchronous throw as its failure and `deadline` as a timeout: the result always
    /// settles. A [Serial] step built this way always frees the next one.
    public static <T> Promise<T> bounded(Supplier<Promise<T>> operation, Duration deadline) {
        return Result.lift(Causes::fromThrowable, operation::get)
                     .async()
                     .flatMap(started -> started)
                     .timeout(TimeSpan.fromDuration(deadline));
    }

    private <T> Option<T> skippedRun() {
        skipped.incrementAndGet();

        return none();
    }
}
