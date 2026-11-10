package org.pragmatica.kiln.service.loop;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// [Periodic] and [Daily] (kiln-f1-design §1.2 `kiln.service.loop`): a tick that throws or fails never stops the timer;
/// a started timer ticks and a stopped one does not; the daily run is due at the next local time of day.
class LoopTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    @Test
    void fire_survivesAThrowAndAFailure() {
        var calls = new AtomicInteger();
        var throwing = Periodic.periodic("t", Duration.ZERO, Duration.ofDays(1), () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        var failing = Periodic.periodic("f", Duration.ZERO, Duration.ofDays(1), () -> {
            calls.incrementAndGet();
            return Causes.cause("down").promise();
        });

        throwing.fire();
        failing.fire();
        assertThat(calls).hasValue(2);
    }

    @Test
    void started_ticks_stopped_doesNot() throws Exception {
        var calls = new AtomicInteger();
        var loop = Periodic.periodic("p", Duration.ZERO, Duration.ofMillis(20), () -> {
            calls.incrementAndGet();
            return Promise.unitPromise();
        });

        assertThat(loop.running()).isFalse();
        loop.start();
        loop.start();
        assertThat(loop.running()).isTrue();
        Thread.sleep(300);
        loop.stop();
        assertThat(loop.running()).isFalse();
        var stopped = calls.get();
        Thread.sleep(200);

        assertThat(stopped).isGreaterThan(2);
        assertThat(calls.get()).isLessThanOrEqualTo(stopped + 1);
        assertThat(loop.name()).isEqualTo("p");
        assertThat(loop.period()).isEqualTo(Duration.ofMillis(20));
    }

    @Test
    void untilNext_isTodayAhead_elseTomorrow() {
        var morning = ZonedDateTime.of(2026, 10, 30, 3, 0, 0, 0, ZONE);

        assertThat(Daily.untilNext(LocalTime.of(4, 0), morning)).isEqualTo(Duration.ofHours(1));
        assertThat(Daily.untilNext(LocalTime.of(3, 0), morning)).isEqualTo(Duration.ofHours(24));
        assertThat(Daily.untilNext(LocalTime.of(2, 0), morning)).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void daily_runsAtItsTime_andAStoppedOneDoesNot() throws Exception {
        var calls = new AtomicInteger();
        var now = Instant.now();
        var soon = LocalTime.ofInstant(now.plusMillis(300), ZONE);
        var daily = Daily.daily("full:x", soon, ZONE, Instant::now, () -> {
            calls.incrementAndGet();
            return Promise.unitPromise();
        });
        var stopped = Daily.daily("full:y", soon, ZONE, Instant::now, () -> {
            calls.addAndGet(100);
            return Promise.unitPromise();
        });

        daily.start();
        stopped.start();
        stopped.stop();
        Thread.sleep(1_000);
        daily.stop();

        assertThat(calls).hasValue(1);
        assertThat(daily.name()).isEqualTo("full:x");
    }

    /// A run that stops its own Daily schedules no next run; a stopped Daily started again schedules one.
    @Test
    void daily_stoppedDuringItsRun_schedulesNoNext_andRestarts() throws Exception {
        var self = new java.util.concurrent.atomic.AtomicReference<Daily>();
        var ran = new java.util.concurrent.CountDownLatch(1);
        var soon = LocalTime.ofInstant(Instant.now().plusMillis(300), ZONE);
        var daily = Daily.daily("full:z", soon, ZONE, Instant::now, () -> {
            self.get().stop();
            ran.countDown();
            return Promise.unitPromise();
        });

        self.set(daily);
        daily.start();
        assertThat(daily.scheduled()).isTrue();
        assertThat(ran.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        assertThat(daily.scheduled()).isFalse();

        daily.start();
        assertThat(daily.scheduled()).isTrue();
        daily.stop();
        assertThat(daily.scheduled()).isFalse();
    }
}
