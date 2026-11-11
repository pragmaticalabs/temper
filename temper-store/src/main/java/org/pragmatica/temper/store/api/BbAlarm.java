package org.pragmatica.temper.store.api;

import java.time.Instant;


/// One fired alarm mark. `(host, kind, subject, threshold)` is the no-flood
/// identity: an alarm event is emitted only when its key is absent, and the mark is written in the same transaction as
/// the event. `subject` is the ticket id, `-` for `clock-skew`, the `retireAt` ISO instant for `host-retiring`;
/// `threshold` the limit in minutes, `grace` for `waiter-gone`, `30` for `clock-skew`, `3` or `1` (days) for
/// `host-retiring`. `firedAt` is on the controller clock.
public record BbAlarm(String host, String kind, String subject, String threshold, Instant firedAt) {
    public static final String QUEUE_WAIT = "queue-wait";
    /// A running `group=1` ticket with no progress for the stall limit.
    public static final String PORTGROUP_HELD = "portgroup-held";
    /// A running `group=1` ticket past the age backstop, whatever its progress; lower severity than `portgroup-held`.
    public static final String PORTGROUP_LONG = "portgroup-long";
    public static final String WAITER_GONE = "waiter-gone";
    public static final String CLOCK_SKEW = "clock-skew";
    public static final String HOST_RETIRING = "host-retiring";
    /// The subject of a `clock-skew` mark (one per host).
    public static final String NO_TICKET = "-";
    /// The threshold of a `waiter-gone` mark.
    public static final String GRACE = "grace";

    public static BbAlarm bbAlarm(String host, String kind, String subject, String threshold, Instant firedAt) {
        return new BbAlarm(host, kind, subject, threshold, firedAt);
    }

    /// The same key, whatever `firedAt`.
    public boolean sameKey(BbAlarm other) {
        return host.equals(other.host)
               && kind.equals(other.kind)
               && subject.equals(other.subject)
               && threshold.equals(other.threshold);
    }
}
