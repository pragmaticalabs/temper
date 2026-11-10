package org.pragmatica.temper.worker;

import java.util.function.Supplier;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// Runs asynchronous operations one at a time, in submission order, without blocking a thread: each operation
/// starts when the previous one has resolved, whatever its outcome. Used where m0-design asks for an
/// in-process lock around asynchronous work (the shared mirror fetch).
public final class Serial {
    private Promise<Unit> tail = Promise.success(unit());

    private Serial() {}

    public static Serial serial() {
        return new Serial();
    }

    /// Queues `operation` behind everything submitted before it and returns its own outcome.
    ///
    /// FER (degrade forward) on the queue only: a failed operation must not stall its successors, so the queue
    /// tail recovers to `unit()`. The failure itself is not absorbed: the caller gets it through the returned
    /// promise.
    public synchronized <T> Promise<T> run(Supplier<Promise<T>> operation) {
        var next = tail.<T> fold(_ -> operation.get());

        tail = next.mapToUnit().recover(_ -> unit());

        return next;
    }
}
