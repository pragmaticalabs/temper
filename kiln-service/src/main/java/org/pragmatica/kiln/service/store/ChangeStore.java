package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `change`: the observed changes the summary lists (spec §7), kept 30 days.
public interface ChangeStore {
    /// The committed watermark: every change with `recordedAt ≤` it is committed and visible, so a summary that lists
    /// `since < recordedAt ≤ watermark` and hands the watermark back as the next `since` never skips a change (lead
    /// ruling, added in WP5). `recordedAt` is the store's stamp, taken when the write begins, never the caller's
    /// `observedAt`. Changes above it may still be in flight. It is never the clock: with nothing in flight it is the
    /// newest committed `recordedAt` (`Instant.EPOCH` for an empty table), so it never exceeds `max(recordedAt)` stored,
    /// and every later stamp is strictly above it, also across a restart, where it starts at that maximum. Non-decreasing.
    /// A write that never settles holds it below that write's stamp until it does: the summary stalls and nothing is lost
    /// (lead ruling, accepted as the safe direction).
    Instant watermark();
    /// The changes recorded after `since` (`recordedAt > since`), oldest recorded first, then in insertion order: the
    /// first `limit`, and then every further change recorded at the same instant as the last of them. The answer can
    /// exceed `limit` and never ends inside one `recordedAt`, so `since` = its last `recordedAt` resumes without a loss.
    /// A `limit` below 1 fails with `KilnStoreError.InvalidLimit`: an empty page would hand back its own cursor.
    Promise<List<Change>> since(Instant since, int limit);
    /// The change feed after the id `after`: at most `max` rows with `id > after`, ascending, with
    /// the epoch, floor and head, all read from one snapshot. A `max` below 1 fails with `KilnStoreError.InvalidLimit`.
    Promise<ChangeFeed> feed(long after, int max);
    /// The oldest `recordedAt` kept, for the summary's `gap` line.
    Promise<Option<Instant>> oldest();
    /// Drops every change up to the greatest id recorded before `before` (by id, so none survives below the floor), and
    /// raises the feed's floor to that id + 1.
    Promise<Unit> prune(Instant before);
}
