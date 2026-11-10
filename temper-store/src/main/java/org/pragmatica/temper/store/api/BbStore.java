package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The bb ticket collection tables: one `bb_snapshot` row per host (the baseline, replaced
/// by every success), and one `bb_collect` row per host (attempt state, so `failing_since` survives a restart).
public interface BbStore {
    /// One successful collection of `host` in ONE transaction at `now`: the baseline row and the host's `bb_alarm` rows
    /// are read and handed to `round` (the events and the alarm marks it makes of them), `bb_snapshot(host, now, queue, tickets, host_at, skew_s)` is upserted, `bb_collect` is set to a success
    /// (`failing_since` and `last_error` cleared), the source `tickets:<host>` is marked seen at `now`, the `clear` marks
    /// are deleted and the `fire` marks inserted, and the events are inserted last under the order lock: an alarm event
    /// and its mark commit together or not at all. Answers the lowest and highest seq inserted. Collections of one host
    /// are serialized (a per-host transaction advisory lock taken first). A step whose `now` is not after the baseline's
    /// `takenAt` is stale: nothing is written, no event, and it fails with [StaleStep].
    Promise<Option<FleetSeqs>> collected(String host,
                                         Instant now,
                                         Listing listing,
                                         Fn2<RoundWrite, Option<BbSnapshot>, List<BbAlarm>> round);

    /// The `host-retiring` tick in ONE transaction: every `bb_alarm` row of kind `host-retiring` is read and
    /// handed to `decide`; its `clear` marks are deleted, its `fire` marks inserted and its events inserted last under
    /// the order lock. Ticks are serialized (a transaction advisory lock taken first).
    Promise<Option<FleetSeqs>> retiring(Instant now, Fn1<RoundWrite, List<BbAlarm>> decide);
    /// Every `bb_alarm` row, by host, kind, subject and threshold.
    Promise<List<BbAlarm>> alarms();

    /// One proven `bb-tickets` reply: the trailer's `queue` (`present` | `absent`), the ticket lines in output order, the
    /// trailer's `at` (host clock) and the round's measured skew in whole seconds (positive = host ahead).
    record Listing(String queue, List<BbTicket> tickets, Instant hostAt, int skewS) {
        public Listing {
            tickets = List.copyOf(tickets);
        }

        public static Listing listing(String queue, List<BbTicket> tickets, Instant hostAt, int skewS) {
            return new Listing(queue, tickets, hostAt, skewS);
        }
    }

    /// What one round (or one retire tick) writes besides the snapshot: the events, and the alarm marks to insert and
    /// to delete.
    record RoundWrite(List<NewFleetEvent> events, List<BbAlarm> fire, List<BbAlarm> clear) {
        public static final RoundWrite NOTHING = new RoundWrite(List.of(), List.of(), List.of());

        public RoundWrite {
            events = List.copyOf(events);
            fire = List.copyOf(fire);
            clear = List.copyOf(clear);
        }

        public static RoundWrite roundWrite(List<NewFleetEvent> events, List<BbAlarm> fire, List<BbAlarm> clear) {
            return new RoundWrite(events, fire, clear);
        }

        /// Events only: no alarm mark changes.
        public static RoundWrite eventsOnly(List<NewFleetEvent> events) {
            return new RoundWrite(events, List.of(), List.of());
        }
    }

    /// One failed attempt at `now` (ignored when `bb_collect` already records a later attempt): `last_attempt_at` set, `failing_since` kept or set to `now`, `last_error` set
    /// (cut to 512 characters). The snapshot is untouched and no event is emitted.
    Promise<Unit> failed(String host, Instant now, String error);
    /// The host's baseline, if a collection ever succeeded.
    Promise<Option<BbSnapshot>> snapshot(String host);
    /// Every host's baseline, by host.
    Promise<List<BbSnapshot>> snapshots();
    /// Every host's collection state, by host.
    Promise<List<BbCollect>> collects();

    /// The source's name in `fleet_source` for `host`, and its period; stale after 3 periods (T1 rule).
    static String source(String host) {
        return SOURCE_PREFIX + host;
    }

    /// A step that lost the race to a newer one for the same host; it wrote nothing.
    record StaleStep(String host, Instant taken, Instant baseline, String message) implements Cause {
        public static StaleStep staleStep(String host, Instant taken, Instant baseline) {
            return new StaleStep(host,
                                 taken,
                                 baseline,
                                 "stale step dropped (taken " + taken + " <= baseline " + baseline + ")");
        }
    }

    String SOURCE_PREFIX = "tickets:";
    int PERIOD_S = 30;
    int MAX_ERROR = 512;
}
