package org.pragmatica.temper.acceptance.sizing;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.acceptance.trust.CapturesAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// The Maven Reactor Summary parser on the real S1 prepare build of pragmatica on bigboy (150 modules) and on the
/// three time formats Maven prints.
class ReactorSummaryTest {
    @Test
    void parse_readsEveryModuleOfARealSummary() {
        var wall = ReactorSummary.parse(CapturesAccess.text("/sizing/reactor-summary-s1.log"));

        assertThat(wall).hasSize(150);
        assertThat(wall.get("Pragmatica 1.0.0-rc4")).isCloseTo(0.209, within(1e-9));
        assertThat(wall.get("Generated Project Build Gate 1.0.0-rc4")).isCloseTo(0.453, within(1e-9));
    }

    @Test
    void parse_readsSecondsMinutesAndHours_andSkipsSkipped() {
        var wall = ReactorSummary.parse("""
            [INFO] old summary
            [INFO] Reactor Summary for pragmatica 1.0.0-rc4:
            [INFO]
            [INFO] core ............................................... SUCCESS [  9.120 s]
            [INFO] ember .............................................. FAILURE [01:31 min]
            [INFO] forge-tests ........................................ SUCCESS [01:02 h]
            [INFO] later .............................................. SKIPPED
            [INFO] BUILD FAILURE
            """);

        assertThat(wall).containsOnlyKeys("core", "ember", "forge-tests");
        assertThat(wall.get("core")).isCloseTo(9.12, within(1e-9));
        assertThat(wall.get("ember")).isCloseTo(91, within(1e-9));
        assertThat(wall.get("forge-tests")).isCloseTo(3720, within(1e-9));
    }

    @Test
    void parse_keepsTheLastSummaryOnly() {
        var wall = ReactorSummary.parse("""
            [INFO] Reactor Summary for first:
            [INFO] early ............................................. SUCCESS [  1.000 s]
            [INFO] Reactor Summary for second:
            [INFO] late .............................................. SUCCESS [  2.000 s]
            """);

        assertThat(wall).containsOnlyKeys("late");
    }

    @Test
    void seconds_rejectsAnUnknownUnit() {
        assertThat(ReactorSummary.seconds("1", "d")).isEqualTo(Option.none());
    }
}
