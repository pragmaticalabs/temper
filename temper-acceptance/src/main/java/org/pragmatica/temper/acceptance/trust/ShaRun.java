package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;
import java.util.Set;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One SHA's way through the T1 harness: its snapshot directory, the preflight (none on a re-run from snapshots),
/// the temper job, and either the judgement, the failure that stopped it, or `capacity`: why its job never left the
/// queue within `--queue-hours` ([TrustError.Capacity]). A SHA that is not comparable or failed part-way is reported
/// as such, never dropped.
public record ShaRun(String sha,
                     Path dir,
                     Option<Preflight> preflight,
                     Option<Long> job,
                     Option<Judged> judged,
                     Option<String> failure,
                     Option<String> capacity) {
    /// The judged evidence: bb-report's numbers, temper's side, the comparison, and the unit's facts at start and end
    /// (the evidence's `facts-start.json` / `facts-end.json`, or why they are unavailable).
    public record Judged(BbReport bb, TemperSide temper, ShaComparison comparison, String factsStart, String factsEnd) {
        public static Judged judged(BbReport bb,
                                    TemperSide temper,
                                    ShaComparison comparison,
                                    String factsStart,
                                    String factsEnd) {
            return new Judged(bb, temper, comparison, factsStart, factsEnd);
        }
    }

    /// A SHA whose preflight failed: not comparable.
    public static ShaRun shaRun(String sha, Path dir, Preflight preflight) {
        return new ShaRun(sha, dir, some(preflight), none(), none(), none(), none());
    }

    /// A judged SHA.
    public static ShaRun shaRun(String sha, Path dir, Option<Preflight> preflight, long job, Judged judged) {
        return new ShaRun(sha, dir, preflight, some(job), some(judged), none(), none());
    }

    /// A SHA stopped by `cause`.
    public static ShaRun shaRun(String sha, Path dir, Option<Preflight> preflight, Option<Long> job, Cause cause) {
        return new ShaRun(sha, dir, preflight, job, none(), some(cause.message()), none());
    }

    /// A SHA whose job never got to run (still QUEUED at `--queue-hours`, or at a `--rerun`): NOT COMPARABLE
    /// (capacity), the job left as it is.
    public static ShaRun shaRun(String sha,
                                Path dir,
                                Option<Preflight> preflight,
                                long job,
                                TrustError.Capacity cause) {
        return new ShaRun(sha, dir, preflight, some(job), none(), none(), some(cause.message()));
    }

    /// Judged, and a fork crash on either side made it NOT COMPARABLE (R-14): it does not count toward T1's SHAs.
    public boolean crashed() {
        return judged.map(found -> !found.comparison()
                                         .comparable())
                     .or(false);
    }

    /// Its job never left the queue within `--queue-hours` (owner ruling, option A): NOT COMPARABLE (capacity). A
    /// capacity signal, not a judgement: never ✘, and, like a fork crash, left out of T1's count, so T1 needs another
    /// comparable SHA in its place.
    public boolean starved() {
        return capacity.isPresent();
    }

    /// Judged, comparable and the planted red (maintainer ruling "T1 red", m1-design §14): bb reported at least one failed
    /// class and `planted`, the class the red SHA deliberately breaks, is among them. A red build without a failed
    /// class (rc ≠ 0 alone, a compile error say) never counts, and neither does a red from another class
    /// ([#otherRed]). Whether temper agreed is the comparison's matter: a counted SHA that did not pass fails T1.
    public boolean plantedRed(String planted) {
        return comparableFailed().filter(failed -> failed.contains(planted))
                               .isPresent();
    }

    /// Passed, so temper agreed, and red from a class other than `planted`, instead of it or beside it: "not the
    /// planted red". Those classes are listed and explained in the report and never count as T1's red. A SHA on which
    /// temper and bb disagree is never this: it is ✘, counted, and fails T1 (main-session reading of the ruling, "T1
    /// disagreement always fails", m1-design §14).
    public boolean otherRed(String planted) {
        return agreedFailed().filter(failed -> !Set.of(planted).containsAll(failed))
                           .isPresent();
    }

    /// Passed, so temper agreed, and red from other classes only, the planted one not among them: "not the planted
    /// red" (maintainer ruling, m1-design §14: "explained, and not counted"). Like a NOT COMPARABLE SHA, it is left out of
    /// T1's count. A SHA red from the planted class beside others still counts ([#otherRed] lists the others); one on
    /// which the sides disagree is counted, so its ✘ fails T1.
    public boolean notPlantedRed(String planted) {
        return agreedFailed().filter(failed -> !failed.contains(planted))
                           .isPresent();
    }

    /// Counts toward T1's SHAs: neither NOT COMPARABLE ([#crashed], [#starved]) nor an agreed red from other classes
    /// only ([#notPlantedRed]).
    public boolean counted(String planted) {
        return ! (crashed() || starved() || notPlantedRed(planted));
    }

    /// bb's failed classes of a judged, comparable SHA, when there is at least one.
    private Option<Set<String>> comparableFailed() {
        return judged.filter(found -> found.comparison()
                                           .comparable())
                     .map(found -> found.bb()
                                        .failedClasses())
                     .filter(failed -> !failed.isEmpty());
    }

    /// bb's failed classes of a judged SHA whose comparison passed (temper's failed set equals them), when there is
    /// at least one.
    private Option<Set<String>> agreedFailed() {
        return comparableFailed().filter(_ -> passed());
    }

    /// Passed only when judged and the comparison passed.
    public boolean passed() {
        return judged.map(found -> found.comparison()
                                        .passed())
                     .or(false);
    }
}
