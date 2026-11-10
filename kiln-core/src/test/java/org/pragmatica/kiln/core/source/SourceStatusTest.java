package org.pragmatica.kiln.core.source;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §6: a panel turns red when its source is older than 3 × its period.
class SourceStatusTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Duration PERIOD = Duration.ofSeconds(60);

    private static SourceStatus succeededAt(Instant at) {
        return SourceStatus.sourceStatus(Source.GITHUB, new SourceState.GithubDown("503", at), some(at));
    }

    @Test
    void staleAt_isFalse_atExactlyThreePeriods() {
        assertThat(succeededAt(NOW.minusSeconds(180)).staleAt(NOW, PERIOD)).isFalse();
    }

    @Test
    void staleAt_isTrue_pastThreePeriods() {
        assertThat(succeededAt(NOW.minusSeconds(181)).staleAt(NOW, PERIOD)).isTrue();
    }

    @Test
    void staleAt_isFalse_withinThreePeriods() {
        assertThat(succeededAt(NOW.minusSeconds(179)).staleAt(NOW, PERIOD)).isFalse();
        assertThat(succeededAt(NOW).staleAt(NOW, PERIOD)).isFalse();
    }

    /// The period scales the bound: 181 s is stale for a 60 s loop and fresh for a 120 s one.
    @Test
    void staleAt_scalesWithThePeriod() {
        assertThat(succeededAt(NOW.minusSeconds(181)).staleAt(NOW, Duration.ofSeconds(120))).isFalse();
        assertThat(succeededAt(NOW.minusSeconds(361)).staleAt(NOW, Duration.ofSeconds(120))).isTrue();
    }

    /// A source that never succeeded is never fresh, whatever its state says.
    @Test
    void staleAt_isTrue_withoutAnySuccess() {
        var never = SourceStatus.sourceStatus(Source.TEMPER, new SourceState.Ok(NOW), none());

        assertThat(never.staleAt(NOW, PERIOD)).isTrue();
    }
}
