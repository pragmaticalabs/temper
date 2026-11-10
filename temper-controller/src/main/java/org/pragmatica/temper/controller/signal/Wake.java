package org.pragmatica.temper.controller.signal;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// The in-process wake signal (m0-design §6.5): every write that can append an event or free capacity (submit,
/// unit start, unit exit, cancel, link UP) signals it. The single controller JVM is the only event writer, so no
/// LISTEN/NOTIFY is needed.
///
/// Two kinds of waiters: listeners (the scheduler's immediate tick) run on every signal, and [#next] hands out a
/// promise resolved by the NEXT signal (event long-polls). A waiter must take [#next] BEFORE it looks for work, so a
/// signal between the look and the wait is never lost.
public final class Wake {
    private final Set<Promise<Unit>> waiters = ConcurrentHashMap.newKeySet();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static Wake wake() {
        return new Wake();
    }

    /// A promise of the waiter's own, resolved by the next [#signal]. Each call gets a fresh promise because
    /// `Promise.timeout` fails the promise it is called on (rc4 arms the timeout on the original, not on the
    /// derived promise): a shared promise would let one waiter's timeout fail every other waiter. A waiter leaves the
    /// set as soon as it resolves (signal or timeout), so an idle controller holds no dead waiters.
    public Promise<Unit> next() {
        var waiter = Promise.<Unit> promise();

        waiters.add(waiter);
        // May run late: a resolved waiter left in the set is harmless (`signal`'s `succeed` on it is a no-op). Not
        // `withResult`: the caller would get the derived promise, and its `timeout` would never fail `waiter`.
        waiter.onResultRun(() -> waiters.remove(waiter));

        return waiter;
    }

    public Unit listen(Runnable listener) {
        listeners.add(listener);

        return unit();
    }

    public Unit signal() {
        waiters.forEach(Wake::release);
        listeners.forEach(Runnable::run);

        return unit();
    }

    /// Resolving the waiter is its whole release; its own `onResultRun` removes it from the set.
    private static Unit release(Promise<Unit> waiter) {
        waiter.succeed(unit());

        return unit();
    }

    /// Waiters not yet resolved, for tests.
    int waiting() {
        return waiters.size();
    }
}
