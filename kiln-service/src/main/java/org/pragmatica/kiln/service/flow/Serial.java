package org.pragmatica.kiln.service.flow;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// Runs asynchronous operations one at a time, in submission order, without blocking a thread: each operation
/// starts when the previous one has resolved (whatever its result). Used for the mirror's fetch lock (kiln-f1-design
/// §3.3) and, from WP8, one host's ssh sessions (§3.1). Copied from temper's controller `Serial`.
public final class Serial {
    private final AtomicReference<Promise<Unit>> tail = new AtomicReference<>(Promise.unitPromise());

    public <T> Promise<T> run(Supplier<Promise<T>> operation) {
        var done = Promise.<Unit> promise();
        var previous = tail.getAndSet(done);
        // May run late: the next operation waits for `done` itself, so a late release only delays it, never reorders.
        return previous.fold(_ -> operation.get())
                       .onResultRun(() -> release(done));
    }

    /// Resolving `done` lets the next queued operation start; the returned promise is `done` itself.
    private static Promise<Unit> release(Promise<Unit> done) {
        return done.succeed(unit());
    }
}
