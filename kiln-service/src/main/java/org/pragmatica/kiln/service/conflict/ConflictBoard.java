package org.pragmatica.kiln.service.conflict;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.conflict.Prediction;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;


/// What the Conflicts tab and `GET /api/v1/conflicts` show (temper #100), held in memory only:
/// - `predictions`: the textual conflicts merge-tree predicts now;
/// - `carried`: predictions announced earlier that this step could not re-check (a failed git call, a head not yet in
///   the mirror, a pair not run because one side conflicts with the tip); they stay until a check decides them;
/// - `unknown`: the open PRs this step could not check;
/// - `skipped`: the pair checks this step skipped over its cap ([ConflictWatch#PAIR_CHECKS]); later steps run them;
/// - `costs`: the last steps that ran a check, newest first: what one push cost.
public record ConflictBoard(BranchName branch,
                            Option<Sha> tip,
                            Option<Instant> checkedAt,
                            int open,
                            int unknown,
                            int skipped,
                            List<Prediction> predictions,
                            List<Prediction> carried,
                            List<Cost> costs,
                            Option<String> failure) {
    /// How many cost rows the board keeps.
    public static final int COSTS = 20;

    /// The limit, as the tab and the README state it (maintainer ruling, the s48 #2677 lesson).
    public static final String LIMIT = "merge-tree finds textual conflicts only: a clean textual merge can still break "
                                     + "compilation, for example a new caller of a signature another PR changes. A pair is "
                                     + "checked only when the two PRs touch a common path, one adds a file where the other "
                                     + "has a directory, or one moves a directory the other adds into; a rename git does not "
                                     + "detect reads as a delete and an add.";

    public ConflictBoard {
        predictions = List.copyOf(predictions);
        carried = List.copyOf(carried);
        costs = List.copyOf(costs);
    }

    /// Before the first step.
    public static ConflictBoard empty(BranchName branch) {
        return new ConflictBoard(branch,
                                 Option.none(),
                                 Option.none(),
                                 0,
                                 0,
                                 0,
                                 List.of(),
                                 List.of(),
                                 List.of(),
                                 Option.none());
    }

    /// One step that ran at least one check: the mirror fetch that triggered it (`fetchMillis`, and `fetchedBytes`, the
    /// lengths of the object files added to the mirror since the previous step, none when a `gc` rewrote its store), and
    /// the checks it ran.
    ///
    /// `fetchedBytes` is none when it is unknown, which is not zero: it is also none when a `gc` or repack ran in the same
    /// step as the fetch, because the store was rewritten and the bytes that fetch added cannot be told apart from what
    /// the compaction wrote. That fetch's bytes are hidden for that step (accepted); the next step measures afresh. A
    /// reader must never take none as "nothing fetched".
    public record Cost(Instant at,
                       Option<Long> fetchMillis,
                       Option<Long> fetchedBytes,
                       long checkMillis,
                       int tipChecks,
                       int pairChecks) {
        public String line() {
            return "conflict checks: " + tipChecks
                 + " tip, " + pairChecks
                 + " pair in " + checkMillis
                 + " ms; fetch " + fetchMillis.map(ms -> ms + " ms")
                                              .or("n/a")
                 + ", " + fetchedBytes.map(bytes -> bytes + " bytes")
                                      .or("bytes n/a");
        }
    }
}
