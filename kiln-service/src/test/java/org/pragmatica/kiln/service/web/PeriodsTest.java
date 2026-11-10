package org.pragmatica.kiln.service.web;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// WP9 open item 3: the Mac pushes every 2 h, so its push reads stale after 3 × that, 6 h, and not before.
class PeriodsTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private static boolean macStale(Duration age) {
        var pushed = NOW.minus(age);
        var periods = Periods.periods(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(120));

        return new Sources(List.of(new SourceStatus(Source.MAC, new SourceState.Ok(pushed), some(pushed))), periods).stale(Source.MAC, NOW);
    }

    @Test
    void macPush_isStaleAfterSixHours() {
        assertThat(Periods.MAC_PUSH).isEqualTo(Duration.ofHours(2));
        assertThat(macStale(Duration.ofHours(6).minusSeconds(1))).isFalse();
        assertThat(macStale(Duration.ofHours(6))).isFalse();
        assertThat(macStale(Duration.ofHours(6).plusSeconds(1))).isTrue();
    }

    @Test
    void periods_keepEachLoopsOwn() {
        var periods = Periods.periods(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofSeconds(4));

        assertThat(List.of(periods.of(Source.GITHUB), periods.of(Source.MIRROR), periods.of(Source.TEMPER), periods.of(Source.MAC)))
            .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(3), Duration.ofHours(2));
    }
}
