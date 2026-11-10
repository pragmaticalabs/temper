package org.pragmatica.temper.controller.fleet;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.FleetHead;


/// What the high-water mark says about the database (R3-4: no operator step; design §2.2
/// Correction 2).
///
/// - No file (or one that does not parse) and a database whose stream was never written (no row, the sequence never
///   used): [Verdict#FIRST]; the file is written from the database.
/// - No file, but the stream was written: its history is unknown, [Verdict#UNKNOWN]: a new epoch.
/// - The mark's epoch differs from the database's, or the mark's seq is ABOVE the database's head
///   ([FleetHead#seq]: `GREATEST(max(seq), floor)`): the database was restored or replaced, [Verdict#RESTORED]: a new
///   epoch. Both halves are needed: a dump taken earlier in the SAME epoch restores the same epoch with a lower head.
///   The sequence is not compared (NOTE-7): a mark is always a committed seq, so it is never above a database that
///   holds its history, and the sequence's value in a dump taken while an insert was in flight is above that dump's
///   rows, which would hide the restore. The sequence only tells whether the stream was used ([FleetHead#used]).
/// - Otherwise [Verdict#CURRENT] (a mark behind the database is a crash between commit and rename, which is normal).
///
/// The same comparison ([#ahead]) runs while the controller runs, against the mark kept in memory ([TemperEpoch#verify]).
public sealed interface EpochCheck {
    enum Verdict {
        FIRST,
        CURRENT,
        RESTORED,
        UNKNOWN
    }

    static Verdict decide(Option<HighWater.Mark> file, FleetHead db) {
        return file.map(mark -> ahead(mark, db)
                                ? Verdict.RESTORED
                                : Verdict.CURRENT)
                   .or(() -> db.used()
                             ? Verdict.UNKNOWN
                             : Verdict.FIRST);
    }

    /// The mark belongs to another epoch, or is above the database's head.
    static boolean ahead(HighWater.Mark mark, FleetHead db) {
        return ! mark.epoch()
                     .equals(db.epoch()) || mark.seq() > db.seq();
    }

    record unused() implements EpochCheck {}
}
