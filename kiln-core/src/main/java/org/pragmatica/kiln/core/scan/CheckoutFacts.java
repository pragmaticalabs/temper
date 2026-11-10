package org.pragmatica.kiln.core.scan;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;


/// The full facts of one checkout from `scan` (spec §5.2). `head` is absent on an unborn branch; `branch` is absent on
/// a detached HEAD. `stash` is the count of the common git dir, shared by every worktree of it. `salvageRefs` is the
/// count of `refs/salvage/*`, when the scan reports it.
public record CheckoutFacts(CheckoutPath path,
                            Option<Sha> head,
                            Option<BranchName> branch,
                            Option<String> remoteUrl,
                            List<BranchTip> branches,
                            int modified,
                            int untracked,
                            Option<String> op,
                            Instant activityAt,
                            List<Long> processes,
                            boolean maven,
                            String commonDir,
                            int stash,
                            Option<Integer> salvageRefs) {
    public CheckoutFacts {
        branches = List.copyOf(branches);
        processes = List.copyOf(processes);
    }
}
