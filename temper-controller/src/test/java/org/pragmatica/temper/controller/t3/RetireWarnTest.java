package org.pragmatica.temper.controller.t3;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbStore.RoundWrite;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// `host-retiring`, the pure decision over the marks the previous ticks left. Rows S1.10,
/// S1.11, S1.12 (the restart over Postgres is in `TicketAlarmsIT`).
class RetireWarnTest {
    private static final Instant RETIRE = Instant.parse("2026-11-22T00:00:00Z");

    private static final class Ticks {
        private final List<BbAlarm> rows = new ArrayList<>();
        private final List<NewFleetEvent> events = new ArrayList<>();

        List<NewFleetEvent> tick(Instant now, RetireWarn.Retire... hosts) {
            RoundWrite write = RetireWarn.decide(List.of(hosts), now, List.copyOf(rows));

            write.clear().forEach(gone -> rows.removeIf(gone::sameKey));
            rows.addAll(write.fire());
            events.addAll(write.events());

            return write.events();
        }
    }

    private static RetireWarn.Retire host(Option<Instant> retireAt) {
        return RetireWarn.Retire.retire("cloudbb-3", retireAt);
    }

    private static int days(NewFleetEvent event) {
        return TemperJson.mapper()
                         .readTree(event.payload())
                         .unwrap()
                         .path("days")
                         .intValue();
    }

    /// S1.10: `remaining` exactly 3 d → `days 3`; 3 d + 1 s → nothing; exactly 1 d → `days 1`; each once over many ticks.
    /// Mutations: `<` instead of `≤` → nothing at exactly 3 d, red; no persisted mark → every tick fires, red.
    @Test
    void s1_10_thresholdsFireAtExactlyTheirRemaining_onceEach() {
        var ticks = new Ticks();
        var retire = host(some(RETIRE));

        assertThat(ticks.tick(RETIRE.minus(Duration.ofDays(3)).minusSeconds(1),
                              retire)).isEmpty();
        var three = ticks.tick(RETIRE.minus(Duration.ofDays(3)),
                               retire);

        assertThat(three).extracting(RetireWarnTest::days).containsExactly(3);
        assertThat(three.getFirst().subject()).isEqualTo("cloudbb-3");
        assertThat(three.getFirst().kind()).isEqualTo("host-retiring");
        var payload = TemperJson.mapper().readTree(three.getFirst().payload()).unwrap();

        assertThat(payload.path("retireAt").stringValue()).isEqualTo(RETIRE.toString());
        assertThat(payload.path("remainingH").intValue()).isEqualTo(72);
        for (var minutes = 1; minutes < 60; minutes++) {
            assertThat(ticks.tick(RETIRE.minus(Duration.ofDays(3)).plusSeconds(60L * minutes),
                                  retire)).isEmpty();
        }

        assertThat(ticks.tick(RETIRE.minus(Duration.ofDays(1)).minusSeconds(1),
                              retire)).isEmpty();
        assertThat(ticks.tick(RETIRE.minus(Duration.ofDays(1)),
                              retire)).extracting(RetireWarnTest::days)
                  .containsExactly(1);
        assertThat(ticks.tick(RETIRE.minusSeconds(60), retire)).isEmpty();
        assertThat(ticks.events).hasSize(2);
    }

    /// S1.11: a first tick at `remaining` = 12 h → one event, `days 1`, and no `days 3` ever after. Mutation: emit every
    /// due threshold → a `days 3` and a `days 1`, red.
    @Test
    void s1_11_aLateFirstTick_saysOnlyTheTightestThreshold() {
        var ticks = new Ticks();
        var retire = host(some(RETIRE));

        assertThat(ticks.tick(RETIRE.minus(Duration.ofHours(12)),
                              retire)).extracting(RetireWarnTest::days)
                  .containsExactly(1);
        assertThat(ticks.rows).extracting(BbAlarm::threshold).containsExactlyInAnyOrder("3", "1");
        for (var hours = 11; hours > 0; hours--) {
            assertThat(ticks.tick(RETIRE.minus(Duration.ofHours(hours)),
                                  retire)).isEmpty();
        }

        assertThat(ticks.events).hasSize(1);
    }

    /// S1.12: `retireAt` changed (and restarted) → both thresholds re-armed for the new instant, the old marks deleted;
    /// removed → nothing fires, the marks deleted; in the past → nothing. Mutation: `retireAt` left out of the key → the
    /// old marks cover the new instant, nothing fires, red.
    @Test
    void s1_12_aChangedRetireAt_reArms_aRemovedOneClears_aPastOneSaysNothing() {
        var ticks = new Ticks();
        var now = RETIRE.minus(Duration.ofHours(12));

        ticks.tick(now, host(some(RETIRE)));
        var moved = RETIRE.plus(Duration.ofDays(1));

        assertThat(ticks.tick(now.plusSeconds(60),
                              host(some(moved)))).extracting(RetireWarnTest::days)
                  .containsExactly(3);
        assertThat(ticks.rows).extracting(BbAlarm::subject).containsOnly(moved.toString());
        assertThat(ticks.tick(moved.minus(Duration.ofDays(1)),
                              host(some(moved)))).extracting(RetireWarnTest::days)
                  .containsExactly(1);
        assertThat(ticks.tick(moved.minus(Duration.ofHours(20)),
                              host(none()))).isEmpty();
        assertThat(ticks.rows).isEmpty();
        assertThat(ticks.tick(RETIRE.plusSeconds(1), host(some(RETIRE)))).as("at or after retireAt").isEmpty();
        assertThat(ticks.tick(RETIRE, host(some(RETIRE)))).isEmpty();
        assertThat(ticks.rows).isEmpty();
    }

    /// A mark of a host no longer configured is deleted; another host's marks are not touched by this host's change.
    @Test
    void marksOfAnUnconfiguredHost_areDeleted() {
        var ticks = new Ticks();
        var now = RETIRE.minus(Duration.ofHours(12));
        var other = RetireWarn.Retire.retire("cloudbb-d1", some(RETIRE));

        ticks.tick(now, host(some(RETIRE)), other);
        assertThat(ticks.rows).hasSize(4);
        assertThat(ticks.tick(now.plusSeconds(60), other)).isEmpty();
        assertThat(ticks.rows).extracting(BbAlarm::host).containsOnly("cloudbb-d1");
    }
}
