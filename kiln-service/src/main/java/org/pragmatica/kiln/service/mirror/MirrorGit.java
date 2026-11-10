package org.pragmatica.kiln.service.mirror;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// kiln's bare mirror of GitHub (kiln-f1-design §3.3). Every reachability query names its ref patterns
/// (`refs/heads`, `refs/tags`, `refs/pull/*/head`); `refs/pull/*/merge` is never read. Built in WP7.
public interface MirrorGit {
    /// `fetch --prune` with the three refspecs; `clone --mirror` when the mirror is absent.
    Promise<Unit> fetch();
    /// The behind facts of `head` against `base`; both `UNKNOWN` when the head is absent. `behindTreeEmpty` is
    /// tree-equality of `merge-base(head, base)` and the base tip, not "empty commits only" ([BehindFacts]).
    Promise<BehindFacts> behind(Sha head, BranchName base);
    /// Whether each SHA is reachable from a ref of the three patterns; memoised per fetch generation.
    Promise<Map<Sha, Boolean>> reachable(Set<Sha> shas);
    /// HEAD's ancestry against the configured bases and the PR heads.
    Promise<Ancestry> ancestry(Sha head, List<BranchName> bases);
}
