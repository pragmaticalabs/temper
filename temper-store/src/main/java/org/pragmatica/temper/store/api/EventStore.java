package org.pragmatica.temper.store.api;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.subscribe.Selector;


/// Events and subscriber cursors (spec §8.3). Every transaction that inserts an event first takes
/// `pg_advisory_xact_lock(70700001)`, so commit order equals `seq` order and a reader never skips a `seq`
/// that commits later.
public interface EventStore {
    /// Appends one event in its own transaction and returns its `seq`. The stores' own transactions append through
    /// the package-private writer that takes the same lock.
    Promise<Long> append(Option<JobId> job, EventRow.Kind kind, EventPayload payload);
    /// Events with `seq > seq`, ascending, at most `limit`. `kinds` holds wire kinds (`finished`, ...); empty means
    /// every kind. It does not report events deleted by retention: a subscriber reads with [#pending].
    Promise<List<EventRow>> after(long seq, Set<String> kinds, int limit);
    /// What a subscriber at `cursor` reads next, in ONE statement (one snapshot): [Pending.Gap]`(cursor + 1, floor)`
    /// when the event floor is above `cursor`, otherwise [Pending.Events] with `seq > cursor` (filtered and limited
    /// as in [#after]). Retention moves the floor and deletes in one transaction, so a reader sees both changes or
    /// neither and never skips a deleted event without a gap (m1-design §4.4).
    Promise<Pending> pending(long cursor, Set<String> kinds, int limit);
    /// The event floor: every event with `seq <= floor` was deleted by retention (0 before the first deletion).
    Promise<Long> floor();
    /// The subscriber, created with `cursor = GREATEST(max(seq), floor)` when absent [G] (Q-14), so a subscriber
    /// created after retention starts at the floor and sees no spurious gap.
    Promise<SubscriberRow> subscriber(String name);
    /// `cursor = GREATEST(cursor, seq)`; an unknown subscriber fails with [StoreError.SubscriberNotFound].
    Promise<Unit> ack(String name, long seq);
    /// `selectors = (selectors ∪ add) − remove`, sorted by wire form and distinct, in ONE statement on the row
    /// (m2-design §2.3): concurrent changes serialise on its row lock, so neither loses the other's change. The
    /// subscriber is created first when absent, with the cursor rule of [#subscriber], so selectors can be set before
    /// the first poll. A selector in both lists ends removed.
    Promise<SubscriberRow> selectors(String name, List<Selector> add, List<Selector> remove);
    /// Skip-ack (R-M2-12): `cursor = seq` in one conditional statement, only while `selectors_version = version` and
    /// `cursor < seq`. A poll calls it with the version it read and the highest `seq` it scanned without a match, so a
    /// selector change that committed during the scan (a new version) leaves the cursor where it was and the next poll
    /// scans again. A mismatch, an unknown subscriber or a cursor already past `seq` change nothing and succeed.
    Promise<Unit> skipTo(String name, long version, long seq);
}
