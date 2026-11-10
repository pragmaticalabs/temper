package org.pragmatica.temper.domain.task;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.Sha;


/// The read-point stamp (m3-design §6.5, lesson (d), ruling R-M3-24): `temper task claim --checkout DIR` clones the
/// task's repository at the issued read point and writes `DIR/.temper-read-point` = `{task, epoch, readPoint, at}`.
/// Evidence carries the stamp read back from that file and `checkoutHead` (`git rev-parse HEAD` of `DIR` at the start of
/// the step); [#refusals] is their check against what the claim issued (applied by WP-2's evidence validation).
///
/// Honest limit (M3a): the stamp proves that a stamped clone existed at the read point for this epoch, not that the
/// worker read only it. The epoch is fresh per claim, so a worker that never ran in this claim's clone cannot produce it.
public record ReadPointStamp(long task, long epoch, Sha readPoint, Instant at) {
    /// The stamp's file name inside the clone.
    public static final String FILE = ".temper-read-point";
    public static final String UNSTAMPED = "read-point-unstamped";
    public static final String STALE = "read-point-stale";

    public static ReadPointStamp readPointStamp(long task, long epoch, Sha readPoint, Instant at) {
        return new ReadPointStamp(task, epoch, readPoint, at);
    }

    /// No stamp, or no checkout head, is `read-point-unstamped` [G: the design pairs both with either code]; a stamp
    /// of another epoch or read point, or a checkout at another commit, is `read-point-stale`. Empty when both equal
    /// the issued values.
    public static List<String> refusals(long issuedEpoch,
                                        Sha issuedReadPoint,
                                        Option<ReadPointStamp> stamp,
                                        Option<Sha> checkoutHead) {
        return stamp.isEmpty() || checkoutHead.isEmpty()
               ? List.of(UNSTAMPED)
               : staleness(issuedEpoch, issuedReadPoint, stamp, checkoutHead);
    }

    private static List<String> staleness(long issuedEpoch,
                                          Sha issuedReadPoint,
                                          Option<ReadPointStamp> stamp,
                                          Option<Sha> checkoutHead) {
        return isIssued(stamp, issuedEpoch, issuedReadPoint) && isAt(checkoutHead, issuedReadPoint)
               ? List.of()
               : List.of(STALE);
    }

    private static boolean isIssued(Option<ReadPointStamp> stamp, long epoch, Sha readPoint) {
        return stamp.filter(found -> found.epoch() == epoch)
                    .filter(found -> found.readPoint()
                                          .equals(readPoint))
                    .isPresent();
    }

    private static boolean isAt(Option<Sha> checkoutHead, Sha readPoint) {
        return checkoutHead.filter(readPoint::equals)
                           .isPresent();
    }
}
