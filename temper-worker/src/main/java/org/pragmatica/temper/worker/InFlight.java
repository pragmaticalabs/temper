package org.pragmatica.temper.worker;

import java.util.Map;
import java.util.function.Supplier;

import org.pragmatica.lang.Promise;


/// Per-key sharing of an in-flight operation (a runner image build, a workspace preparation, a unit's start or
/// exit processing): concurrent callers for one key join the one promise instead of racing it. The map must be a
/// `ConcurrentHashMap` (its `computeIfAbsent` is atomic).
///
/// The entry is removed as a chain step of that promise (`withResult`/`withFailure`), never by an `onResultRun`
/// callback: Pragmatica runs `on*` callbacks asynchronously, after the promise's dependents, so a caller who saw the
/// promise resolve could retry before the removal and be handed the same, already resolved promise again, a cached
/// failure included. A chain step runs before any caller's continuation, so a retry after a resolution always starts
/// afresh. Only a call that races the resolution itself can still join it, exactly as if it had joined a moment
/// earlier.
public sealed interface InFlight {
    /// The key's in-flight promise, or a new one from `start`; forgotten once it resolves.
    static <K, V> Promise<V> joined(Map<K, Promise<V>> inFlight, K key, Supplier<Promise<V>> start) {
        var promise = inFlight.computeIfAbsent(key, _ -> start.get());

        return promise.withResult(_ -> inFlight.remove(key, promise));
    }

    /// The key's in-flight or succeeded promise, or a new one from `start`; forgotten only when it fails, so the next
    /// call retries a failure while a success is shared from then on.
    static <K, V> Promise<V> joinedUntilFailed(Map<K, Promise<V>> inFlight, K key, Supplier<Promise<V>> start) {
        var promise = inFlight.computeIfAbsent(key, _ -> start.get());

        return promise.withFailure(_ -> inFlight.remove(key, promise));
    }

    record unused() implements InFlight {}
}
