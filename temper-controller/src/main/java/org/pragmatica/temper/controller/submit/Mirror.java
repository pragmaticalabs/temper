package org.pragmatica.temper.controller.submit;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;


/// The controller's bare mirror of the origin (m0-design §6.3). A `--mirror` clone has no remote-tracking refs, so
/// "contained in a remote ref" (spec §2.2 `git branch -r --contains`) is `for-each-ref --contains` over `refs/heads`
/// and, separately, `refs/pull/*/head` (m1-design M1-3, R-M2-7), whose numbers [PullOrigins] then judges.
public interface Mirror {
    /// Clones the mirror on first use, otherwise fetches with prune; serialised per mirror.
    Promise<Unit> fetch();
    /// Whether some branch head contains `sha`; an unknown object is "not contained".
    Promise<Boolean> containedInBranch(Sha sha);
    /// Every pull request whose head ref contains `sha`, oldest (lowest number) first: fork PRs are usually newer
    /// (an older fork can be force-pushed, which [PullJudgement] allows for). None for an unknown object.
    Promise<List<PullNumber>> pullHeadsContaining(Sha sha);
    /// The tip of `branch` after a fetch (m3-design §5.4 `base-unknown`, §6.5 a claim's read point); none when the mirror
    /// has no such branch.
    Promise<Option<Sha>> branchTip(Ref branch);
    /// Whether `sha` is `branch`'s tip or one of its ancestors, after a fetch (a lane checkpoint's `pushedSha`, issue
    /// lane-registry WP1). False for a branch the mirror lacks and for an object it does not have; a mirror that cannot
    /// answer fails rather than says no.
    Promise<Boolean> branchContains(Ref branch, Sha sha);
}
