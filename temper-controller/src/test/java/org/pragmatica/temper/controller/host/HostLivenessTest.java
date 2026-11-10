package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m4-design §2.1, §3, §12.1 (rulings R-M4-1, R-M4-16): the down clock and the LOST decision, without clocks.
///
/// Probe P7: make [HostLiveness#clock] reset on any UP (drop the `stableUp` bound) → the flap rows go red.
class HostLivenessTest {
    private static final Instant T0 = Instant.parse("2026-10-26T12:00:00Z");
    private static final Duration LOST_AFTER = Duration.ofMinutes(10);
    private static final Duration STABLE_UP = Duration.ofSeconds(60);

    @Test
    void decide_lost_exactlyLostAfter() {
        assertThat(HostLiveness.decide(some(T0), T0.plus(LOST_AFTER), LOST_AFTER, false, false))
            .isEqualTo(new HostLiveness.Lost(HostLiveness.Reason.LINK_DOWN));
    }

    @Test
    void decide_down_oneMillisecondBefore() {
        assertThat(HostLiveness.decide(some(T0), T0.plus(LOST_AFTER).minusMillis(1), LOST_AFTER, false, false))
            .isEqualTo(new HostLiveness.Down(T0));
    }

    @Test
    void decide_healthy_noClock() {
        assertThat(HostLiveness.decide(none(), T0.plus(Duration.ofDays(1)), LOST_AFTER, false, false))
            .isEqualTo(new HostLiveness.Healthy());
    }

    /// Q-M4-2: a host-key failure is LOST at once, whatever the clock says.
    @Test
    void decide_lostHostKey_atOnce() {
        assertThat(HostLiveness.decide(some(T0), T0, LOST_AFTER, true, false))
            .isEqualTo(new HostLiveness.Lost(HostLiveness.Reason.HOST_KEY));
        assertThat(HostLiveness.decide(none(), T0, LOST_AFTER, true, false))
            .isEqualTo(new HostLiveness.Lost(HostLiveness.Reason.HOST_KEY));
    }

    /// Q-M4-8: at controller start no clock runs and the link is not UP, so the clock starts at the start.
    @Test
    void clock_startsNow_controllerStart() {
        assertThat(HostLiveness.clock(none(), none(), T0, STABLE_UP)).isEqualTo(some(T0));
    }

    @Test
    void clock_kept_linkStillDown() {
        assertThat(HostLiveness.clock(some(T0), none(), T0.plusSeconds(300), STABLE_UP)).isEqualTo(some(T0));
    }

    /// Q-M4-1: an UP shorter than `stableUp` keeps the clock, so a flapping host is still LOST at `lostAfter`.
    @Test
    void clock_kept_upShorterThanStableUp() {
        var up = T0.plusSeconds(120);

        assertThat(HostLiveness.clock(some(T0), some(up), up.plus(STABLE_UP).minusMillis(1), STABLE_UP)).isEqualTo(some(T0));
    }

    @Test
    void clock_reset_upForStableUp() {
        var up = T0.plusSeconds(120);

        assertThat(HostLiveness.clock(some(T0), some(up), up.plus(STABLE_UP), STABLE_UP)).isEqualTo(none());
    }

    /// A flap: UP for 30 s every 2 min. The clock never resets and the host is LOST at `lostAfter` from the first drop.
    @Test
    void clock_flapping_lostAtLostAfter() {
        var clock = HostLiveness.clock(none(), none(), T0, STABLE_UP);
        for (var cycle = 0; cycle < 5; cycle++) {
            var up = T0.plusSeconds(120L * cycle + 90);
            clock = HostLiveness.clock(clock, some(up), up.plusSeconds(30), STABLE_UP);
            clock = HostLiveness.clock(clock, none(), up.plusSeconds(30), STABLE_UP);
        }

        assertThat(clock).isEqualTo(some(T0));
        assertThat(HostLiveness.decide(clock, T0.plus(LOST_AFTER), LOST_AFTER, false, false))
            .isEqualTo(new HostLiveness.Lost(HostLiveness.Reason.LINK_DOWN));
    }

    /// Healthy and UP, the clock stays clear; leaving UP starts it at the drop.
    @Test
    void clock_startsAtDrop_afterStableUp() {
        var up = T0.minusSeconds(3600);

        assertThat(HostLiveness.clock(none(), some(up), T0, STABLE_UP)).isEqualTo(none());
        assertThat(HostLiveness.clock(none(), none(), T0, STABLE_UP)).isEqualTo(some(T0));
    }

    /// Q-M4-12: down for `lostAfter` while the link is UP again defers the verdict; a link-down past the bound is LOST;
    /// a host-key failure is LOST whether UP or not.
    @Test
    void decide_overdueWhileUp_deferred() {
        assertThat(HostLiveness.decide(some(T0), T0.plus(LOST_AFTER), LOST_AFTER, false, true))
            .isEqualTo(new HostLiveness.Deferred(T0));
        assertThat(HostLiveness.decide(some(T0), T0.plus(LOST_AFTER).minusMillis(1), LOST_AFTER, false, true))
            .isEqualTo(new HostLiveness.Down(T0));
        assertThat(HostLiveness.decide(some(T0), T0, LOST_AFTER, true, true))
            .isEqualTo(new HostLiveness.Lost(HostLiveness.Reason.HOST_KEY));
        assertThat(HostLiveness.decide(none(), T0, LOST_AFTER, false, true)).isEqualTo(new HostLiveness.Healthy());
    }

    /// Q-M4-12: the host takes no new grant from `lostAfter` on, until the clock clears.
    @Test
    void overdue_fromLostAfter_untilCleared() {
        assertThat(HostLiveness.overdue(some(T0), T0.plus(LOST_AFTER), LOST_AFTER)).isTrue();
        assertThat(HostLiveness.overdue(some(T0), T0.plus(LOST_AFTER).minusMillis(1), LOST_AFTER)).isFalse();
        assertThat(HostLiveness.overdue(none(), T0.plus(LOST_AFTER), LOST_AFTER)).isFalse();
    }

    @Test
    void reason_wire_storedValues() {
        assertThat(HostLiveness.Reason.LINK_DOWN.wire()).isEqualTo("link-down");
        assertThat(HostLiveness.Reason.HOST_KEY.wire()).isEqualTo("host-key");
    }
}
