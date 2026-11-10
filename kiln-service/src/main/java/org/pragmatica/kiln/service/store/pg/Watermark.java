package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// The committed watermark of `change` (lead rulings, 2026-10-30): an instant `W` such that every change with
/// `recorded_at ≤ W` is committed and visible. A summary cursor taken from the wall clock could lose a change recorded
/// at `t` that commits after a read at `now > t`; a cursor taken from [#watermark] cannot.
///
/// Mechanism: every change-writing transaction runs through [#tracked], which stamps its `recorded_at` from the clock
/// and registers that stamp **before** the transaction begins, and removes it only after the transaction has committed
/// or rolled back. The newest stamp of a committed write that recorded a change is `committed`. `W` is `committed` when
/// nothing is in flight, and the earlier of `committed` and the oldest stamp in flight minus 1 µs otherwise; it is never
/// the clock. So no `W` handed out ever exceeds `max(recorded_at)` of the table, and `W` never moves back. The stamp is
/// the store's, never the caller's `observedAt`, and is strictly above every `W` handed out (a clock that steps back is
/// overtaken by 1 µs). Everything is in whole microseconds, the precision of `timestamptz`, so a stored stamp never
/// rounds down past `W`; 1 µs below the oldest whole-µs stamp admits exactly what 1 ns below it would.
///
/// At a restart `committed` and `W` start at `max(recorded_at)` ([#watermark]; `Instant.EPOCH` for an empty table). Since
/// no `W` handed out before the restart exceeded it, every stamp after the restart is above every cursor a reader holds,
/// whatever the clock says. A write that never settles holds `W` below its stamp until it does: the summary stalls and
/// nothing is lost (lead ruling, accepted as the safe direction).
///
/// Scope: one kiln process, the only writer of kiln-pg. A second writing process would need a shared mechanism.
final class Watermark {
    private static final ChronoUnit PRECISION = ChronoUnit.MICROS;

    private final Supplier<Instant> clock;
    private final TreeMap<Instant, Integer> inFlight = new TreeMap<>();
    private Instant committed;
    private Instant issued;

    private Watermark(Supplier<Instant> clock, Instant lastRecorded) {
        this.clock = clock;
        this.committed = lastRecorded;
        this.issued = lastRecorded;
    }

    /// Starts at `lastRecorded`, the newest `recorded_at` stored (`Instant.EPOCH` when there is none).
    static Watermark watermark(Supplier<Instant> clock, Option<Instant> lastRecorded) {
        return new Watermark(clock, lastRecorded.or(Instant.EPOCH));
    }

    /// Runs `write` with the `recorded_at` its change rows must carry, and keeps that stamp in flight until the write
    /// settles; a committed write that recorded a change makes its stamp the newest committed one.
    <T> Promise<List<T>> tracked(Fn1<Promise<List<T>>, Instant> write) {
        var stamp = begin();

        return write.apply(stamp)
                    .fold(outcome -> settled(stamp, outcome));
    }

    synchronized Instant watermark() {
        var candidate = inFlight.isEmpty()
                        ? committed
                        : earlier(committed,
                                  inFlight.firstKey().minus(1, PRECISION));

        issued = later(issued, candidate);

        return issued;
    }

    private synchronized Instant begin() {
        var stamp = later(clock.get().truncatedTo(PRECISION),
                          issued.plus(1, PRECISION));

        inFlight.merge(stamp, 1, Integer::sum);

        return stamp;
    }

    private synchronized <T> Promise<List<T>> settled(Instant stamp, Result<List<T>> outcome) {
        inFlight.computeIfPresent(stamp, (_, count) -> remaining(count));
        committed = outcome.map(changes -> newestCommitted(stamp, changes)).or(committed);

        return Promise.resolved(outcome);
    }

    /// A write that recorded no change leaves no row at `stamp`, so it never moves `committed`.
    private Instant newestCommitted(Instant stamp, List<?> changes) {
        return changes.isEmpty()
               ? committed
               : later(committed, stamp);
    }

    /// `Map.computeIfPresent` contract: null removes the entry.
    @NullReturn
    private static Integer remaining(Integer count) {
        return count > 1
               ? count - 1
               : null;
    }

    private static Instant later(Instant one, Instant other) {
        return one.isAfter(other)
               ? one
               : other;
    }

    private static Instant earlier(Instant one, Instant other) {
        return one.isBefore(other)
               ? one
               : other;
    }
}
