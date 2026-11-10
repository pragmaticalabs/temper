package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The fleet event stream (design §2.2). EVERY insert into `fleet_events`, from every
/// producer, first takes `pg_advisory_xact_lock(70700001)` (the lock job events take), and inserts last in its
/// transaction, so seqs commit in order and a cursor that only grows never skips a row (F9).
public interface FleetStore {
    /// temper's epoch, `GREATEST(max(seq), floor)` and the sequence's last value (restore checks).
    Promise<FleetHead> head();
    /// Replaces temper's epoch with a new random one (a restore was detected) and answers it.
    Promise<String> renewEpoch();
    /// Inserts `events` in one transaction under the order lock; answers the lowest and highest seq inserted (none when
    /// nothing was).
    Promise<Option<FleetSeqs>> append(List<NewFleetEvent> events);
    /// One kiln pull in ONE transaction (F10): the source rows first, then, under the order lock, every event with
    /// `ON CONFLICT DO NOTHING`, so a duplicate neither aborts the batch nor stops the cursor. Answers the lowest
    /// and highest seq inserted.
    Promise<Option<FleetSeqs>> pulled(KilnBatch batch);
    /// Every `fleet_source` row, by name.
    Promise<List<FleetSourceRow>> sources();
    /// The row named `name`, if any.
    Promise<Option<FleetSourceRow>> source(String name);
    /// Creates the rows absent (never ok, cursor 0); an existing row is left as it is.
    Promise<Unit> expect(List<SourceSeen> sources);
    /// Writes each producer's success (`last_ok_at` when given, `period_s`), creating its row when absent.
    Promise<Unit> seen(List<SourceSeen> sources);

    /// Records `stale` for `name` and inserts `event` in one transaction, but only when the stored verdict differs:
    /// a transition is announced once; and only while `last_ok_at` still equals `readLastOkAt`, the value the verdict
    /// was computed from (a success written in between wins). Answers the seq inserted, none when nothing changed.
    Promise<Option<FleetSeqs>> staleness(String name,
                                         boolean stale,
                                         Option<Instant> readLastOkAt,
                                         NewFleetEvent event);

    /// Events with `seq > cursor`, at most `limit`, or a gap when the floor is above `cursor`, in one statement.
    Promise<FleetPending> pending(long cursor, int limit);
    /// Creates the subscriber at `cursor` (none: the head) bound to `tokenId`; false when the name exists.
    Promise<Boolean> create(String name, Option<Long> cursor, String tokenId);
    Promise<Option<FleetSubscriber>> subscriber(String name);
    /// Sets the cursor (none: the head) explicitly; false for an unknown subscriber.
    Promise<Boolean> reset(String name, Option<Long> cursor);
    /// `cursor = GREATEST(cursor, seq)` and `last_poll_at = now`, only while `epoch` is the current epoch and `seq` is at
    /// or below the head (lead ruling F2): the check and the update are ONE statement, the epoch row locked
    /// `FOR SHARE`, so a rotation is never between them. The answer says what the statement found.
    Promise<FleetAcked> ack(String name, String epoch, long seq);
    /// `last_poll_at = at`.
    Promise<Unit> polled(String name, Instant at);
    /// Records the heartbeat tick `at` (one per minute) and deletes ticks before `keepFrom`.
    Promise<Unit> tick(Instant at, Instant keepFrom);
    /// Ticks at or after `since`, ascending.
    Promise<List<Instant>> ticks(Instant since);
    /// Retention: deletes every event up to the newest one written before `keepFrom` and raises the floor to it, in
    /// one transaction, so a reader sees both or neither.
    Promise<Unit> prune(Instant keepFrom);
}
