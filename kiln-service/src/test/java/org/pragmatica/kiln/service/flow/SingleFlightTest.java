package org.pragmatica.kiln.service.flow;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The loops' guard (ruling 2): a tick during a run is skipped and counted; a throw or a run that never settles still
/// frees the next tick; [SingleFlight#bounded] steps always free a [Serial].
class SingleFlightTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();
    private static final Duration DEADLINE = Duration.ofMillis(300);

    @Test
    void aTickDuringARun_isSkipped_andCounted() {
        var flight = SingleFlight.singleFlight();
        var gate = Promise.<String> promise();

        var first = flight.run(() -> gate, Duration.ofSeconds(10));

        assertThat(flight.run(() -> Promise.success("second"), DEADLINE).await(WAIT).unwrap()).isEqualTo(none());
        assertThat(flight.skipped()).isEqualTo(1);
        gate.succeed("first");
        assertThat(first.await(WAIT).unwrap()).isEqualTo(some("first"));
        assertThat(flight.run(() -> Promise.success("third"), DEADLINE).await(WAIT).unwrap()).isEqualTo(some("third"));
    }

    @Test
    void aRunThatNeverSettles_failsAtTheDeadline_andFreesTheNext() {
        var flight = SingleFlight.singleFlight();

        assertThat(flight.run(Promise::<String> promise, DEADLINE).await(WAIT).isFailure()).isTrue();
        assertThat(flight.run(() -> Promise.success("next"), DEADLINE).await(WAIT).unwrap()).isEqualTo(some("next"));
    }

    @Test
    void aThrowingRun_failsTheTick_andFreesTheNext() {
        var flight = SingleFlight.singleFlight();

        assertThat(flight.run(() -> {
            throw new IllegalStateException("bug");
        }, DEADLINE).await(WAIT).isFailure()).isTrue();
        assertThat(flight.run(() -> Promise.success("next"), DEADLINE).await(WAIT).unwrap()).isEqualTo(some("next"));
        assertThat(flight.skipped()).isZero();
    }

    @Test
    void boundedSteps_alwaysFreeTheSerial() {
        var serial = new Serial();

        var stuck = serial.run(() -> SingleFlight.bounded(Promise::<String> promise, DEADLINE));
        var thrown = serial.run(() -> SingleFlight.<String> bounded(() -> {
            throw new IllegalStateException("bug");
        }, DEADLINE));
        var next = serial.run(() -> SingleFlight.bounded(() -> Promise.success("next"), DEADLINE));

        assertThat(stuck.await(WAIT).isFailure()).isTrue();
        assertThat(thrown.await(WAIT).isFailure()).isTrue();
        assertThat(next.await(WAIT).unwrap()).isEqualTo("next");
    }
}
