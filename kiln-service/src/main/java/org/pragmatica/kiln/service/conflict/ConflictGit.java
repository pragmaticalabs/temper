package org.pragmatica.kiln.service.conflict;

import java.util.List;
import java.util.Map;

import org.pragmatica.kiln.core.conflict.Changes;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The git questions the conflict watch asks of kiln's bare mirror (temper #100). Read-only on the mirror: every object
/// a merge writes goes to a scratch object directory ([GitConflicts]). A failed or timed-out call fails the promise; it
/// never reads as "clean" or "absent".
public interface ConflictGit {
    /// `refs/heads/<branch>`'s commit.
    Promise<Sha> tip(BranchName branch);
    /// Whether the mirror holds `head` as a commit (a fresh push may not be fetched yet).
    Promise<Boolean> present(Sha head);
    /// What `head` changes against its merge base with `tip` (`diff --name-status -M tip...head`), renames detected.
    Promise<Changes> changed(Sha tip, Sha head);
    /// `merge-tree --write-tree ours theirs`: exit 0 is [Merge.Clean] with the merged tree, exit 1 is
    /// [Merge.Conflicted] with the conflicted paths; any other exit fails.
    Promise<Merge> merge(Sha ours, Sha theirs);
    /// A commit of `tree` whose one parent is `parent`: what a squash merge of a clean merge leaves on the branch.
    Promise<Sha> squash(Sha tree, Sha parent);
    /// The mirror's object files and their lengths, for the bytes a fetch added.
    Promise<ObjectFiles> mirrorObjects();
    /// Empties the scratch object directory (after the tip moves, nothing in it is reused).
    Promise<Unit> resetScratch();

    /// The mirror's object files (loose objects and pack data) by path under `objects/`, each with its length in bytes:
    /// the bytes git wrote, never the blocks a filesystem has allocated so far. On ZFS a fresh file reads 1 KiB of blocks
    /// until its transaction group commits, so a block count (`count-objects`' `size:`) grows with nothing written
    /// (temper #136).
    record ObjectFiles(Map<String, Long> lengths) {
        public ObjectFiles {
            lengths = Map.copyOf(lengths);
        }

        /// The lengths of the files added since `earlier`. None when a file `earlier` held is gone or changed length: a
        /// `gc` or repack rewrote the store, and what it wrote was not fetched (it may be larger than what it replaced).
        /// Unknown, not zero: a fetch in that same step is hidden along with the rewrite.
        public Option<Long> grownSince(ObjectFiles earlier) {
            var kept = earlier.lengths()
                              .entrySet()
                              .stream()
                              .allMatch(file -> file.getValue()
                                                    .equals(lengths.get(file.getKey())));

            return kept
                   ? Option.some(lengths.entrySet()
                                        .stream()
                                        .filter(file -> !earlier.lengths()
                                                                .containsKey(file.getKey()))
                                        .mapToLong(Map.Entry::getValue)
                                        .sum())
                   : Option.none();
        }
    }

    /// The outcome of one merge-tree.
    sealed interface Merge {
        record Clean(Sha tree) implements Merge {}

        record Conflicted(List<String> paths) implements Merge {
            public Conflicted {
                paths = List.copyOf(paths);
            }
        }
    }
}
