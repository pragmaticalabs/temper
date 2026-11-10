package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// When a host is LOST (m4-design §2.1, §3; rulings R-M4-1, R-M4-16). Pure, so the boundaries are tested without
/// clocks.
///
/// - [#clock]: the `downSince` clock. It starts when the link leaves UP (and at controller start), and is cleared only
///   by an UP that has lasted `stableUp` (Q-M4-1): a host that reaches UP for a moment every few minutes stays down.
/// - [#decide]: LOST when the link has been down for `lostAfter` or more, or at once on a host-key failure (Q-M4-2).
///   A link that is UP again when the clock reaches `lostAfter` (not yet for `stableUp`) defers the verdict (Q-M4-12):
///   the host keeps its units and takes no new grant ([#overdue]); an UP that lasts `stableUp` clears the clock, and a
///   link that leaves UP first is LOST at once.
public sealed interface HostLiveness {
    /// Why a host is LOST; [#wire] is the `hosts.lost_reason` value.
    enum Reason {
        LINK_DOWN("link-down"),
        HOST_KEY("host-key");
        private final String wire;
        Reason(String wire) {
            this.wire = wire;
        }
        public String wire() {
            return wire;
        }
    }

    sealed interface Verdict {}

    record Healthy() implements Verdict {}

    record Down(Instant since) implements Verdict {}

    record Lost(Reason reason) implements Verdict {}

    /// Down for `lostAfter`, but the link is UP again: no verdict until it has lasted `stableUp` or left UP.
    record Deferred(Instant since) implements Verdict {}

    /// The clock after `now`: `upSince` is when the link reached UP (none when it is not UP). Cleared by an UP of
    /// `stableUp` or more; otherwise kept; started at `now` when the link is not UP and no clock runs.
    static Option<Instant> clock(Option<Instant> downSince, Option<Instant> upSince, Instant now, Duration stableUp) {
        return stableUp(upSince, now, stableUp)
               ? none()
               : downSince.orElse(() -> upSince.isEmpty()
                                        ? some(now)
                                        : none());
    }

    private static boolean stableUp(Option<Instant> upSince, Instant now, Duration stableUp) {
        return upSince.filter(since -> lasted(since, now, stableUp))
                      .isPresent();
    }

    /// `up`: the link is UP now (with an open session).
    static Verdict decide(Option<Instant> downSince,
                          Instant now,
                          Duration lostAfter,
                          boolean hostKeyFailed,
                          boolean up) {
        return hostKeyFailed
               ? new Lost(Reason.HOST_KEY)
               : downSince.map(since -> down(since, now, lostAfter, up))
                          .or(Healthy::new);
    }

    /// The clock has run for `lostAfter` or more: LOST, or deferred while the link is UP. The host takes no new grant
    /// while this holds (Q-M4-12).
    static boolean overdue(Option<Instant> downSince, Instant now, Duration lostAfter) {
        return downSince.filter(since -> lasted(since, now, lostAfter))
                        .isPresent();
    }

    private static Verdict down(Instant since, Instant now, Duration lostAfter, boolean up) {
        return lasted(since, now, lostAfter)
               ? lostOrDeferred(since, up)
               : new Down(since);
    }

    private static Verdict lostOrDeferred(Instant since, boolean up) {
        return up
               ? new Deferred(since)
               : new Lost(Reason.LINK_DOWN);
    }

    private static boolean lasted(Instant since, Instant now, Duration bound) {
        return Duration.between(since, now).compareTo(bound) >= 0;
    }

    record unused() implements HostLiveness {}
}
