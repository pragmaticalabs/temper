package org.pragmatica.temper.worker;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// [InFlight]: concurrent callers share one in-flight promise, and a caller that saw it resolve never gets it back.
/// The maps here remove slowly (300 ms), as a late asynchronous callback would: a removal that is not a chain step
/// would still be pending when the retry, chained on the first call, runs.
class InFlightTest {
    private final AtomicInteger starts = new AtomicInteger();

    private <T> Promise<T> counted(Promise<T> promise) {
        starts.incrementAndGet();
        return promise;
    }

    @Test
    void joined_sharesTheInFlightPromise() {
        var inFlight = new SlowRemovals<String, Promise<String>>();
        var pending = Promise.<String> promise();
        var first = InFlight.joined(inFlight, "k", () -> counted(pending));
        var second = InFlight.joined(inFlight, "k", () -> counted(Promise.success("other")));

        pending.succeed("shared");

        assertThat(ok(first.await())).isEqualTo("shared");
        assertThat(ok(second.await())).isEqualTo("shared");
        assertThat(starts.get()).isEqualTo(1);
    }

    @Test
    void joined_aRetryAfterAFailure_startsAfresh_neverGettingTheCachedFailure() {
        var inFlight = new SlowRemovals<String, Promise<String>>();
        var failing = Promise.<String> promise();
        var retried = InFlight.joined(inFlight, "k", () -> counted(failing))
                              .fold(_ -> InFlight.joined(inFlight, "k", () -> counted(Promise.success("fresh"))));

        failing.fail(Causes.cause("build failed"));

        assertThat(ok(retried.await())).isEqualTo("fresh");
        assertThat(starts.get()).isEqualTo(2);
    }

    @Test
    void joinedUntilFailed_keepsASuccess_andRetriesAFailure() {
        var inFlight = new SlowRemovals<String, Promise<String>>();
        var failing = Promise.<String> promise();
        var retried = InFlight.joinedUntilFailed(inFlight, "k", () -> counted(failing))
                              .fold(_ -> InFlight.joinedUntilFailed(inFlight, "k", () -> counted(Promise.success("done"))));

        failing.fail(Causes.cause("collection failed"));

        assertThat(ok(retried.await())).isEqualTo("done");
        assertThat(ok(InFlight.joinedUntilFailed(inFlight, "k", () -> counted(Promise.success("again")))
                              .await())).isEqualTo("done");
        assertThat(starts.get()).isEqualTo(2);
    }

    /// A map whose `remove` takes 300 ms.
    static final class SlowRemovals<K, V> extends ConcurrentHashMap<K, V> {
        @Override
        public V remove(Object key) {
            sleep();
            return super.remove(key);
        }

        @Override
        public boolean remove(Object key, Object value) {
            sleep();
            return super.remove(key, value);
        }

        private static void sleep() {
            try {
                Thread.sleep(Duration.ofMillis(300));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
