package org.pragmatica.temper.controller.signal;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

class WakeTest {
    @Test
    void next_oneWaitersTimeout_doesNotFailAnotherWaiter() {
        var wake = Wake.wake();
        var early = wake.next();
        var late = wake.next();

        assertThat(early.timeout(TimeSpan.timeSpan(20).millis()).await().isFailure()).isTrue();

        assertThat(late.isResolved()).as("the other waiter is still waiting").isFalse();
        wake.signal();
        assertThat(late.await(TimeSpan.timeSpan(1).seconds()).isSuccess()).isTrue();
    }

    @Test
    void next_takenAfterSignal_waitsForTheNextOne() {
        var wake = Wake.wake();
        wake.signal();

        assertThat(wake.next().isResolved()).isFalse();
    }

    @Test
    void next_timedOutWaiters_doNotAccumulate() {
        var wake = Wake.wake();
        for (int i = 0; i < 50; i++) {
            wake.next().timeout(TimeSpan.timeSpan(1).millis()).await();
        }

        var deadline = System.nanoTime() + 2_000_000_000L;
        while (wake.waiting() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(wake.waiting()).isZero();
    }

    @Test
    void signal_runsEveryListener() {
        var wake = Wake.wake();
        var runs = new AtomicInteger();
        wake.listen(runs::incrementAndGet);
        wake.listen(runs::incrementAndGet);

        wake.signal();

        assertThat(runs.get()).isEqualTo(2);
    }
}
