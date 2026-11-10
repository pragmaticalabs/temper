package org.pragmatica.temper.worker.unit;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.failure;
import static org.pragmatica.temper.worker.Fixtures.ok;

class EpochNanosTest {
    @Test
    void epochNanos_roundTripsAnInstant() {
        var instant = Instant.parse("2026-10-13T10:15:30.123456789Z");
        var nanos = ok(EpochNanos.epochNanos(instant));

        assertThat(nanos).isEqualTo(instant.getEpochSecond() * 1_000_000_000L + 123_456_789L);
        assertThat(EpochNanos.instant(nanos)).isEqualTo(instant);
    }

    @Test
    void epochNanos_failsInsteadOfWrapping() {
        failure(EpochNanos.epochNanos(Instant.parse("2300-01-01T00:00:00Z")));
        failure(EpochNanos.plus(Long.MAX_VALUE - 1, Duration.ofSeconds(1)));
    }
}
