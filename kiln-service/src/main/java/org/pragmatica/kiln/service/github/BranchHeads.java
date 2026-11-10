package org.pragmatica.kiln.service.github;

import java.util.List;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.lang.Promise;


/// A repository's branch heads, read each cycle (CI interface T1 WP2, design §2.1: the push-run window covers `main` and
/// every `release-*` branch, listed by `git ls-remote --heads`). Not a GitHub REST request, so it costs no rate budget.
@FunctionalInterface
public interface BranchHeads {
    /// No branch beyond the configured ones: the tests' default.
    BranchHeads NONE = _ -> Promise.success(List.of());
    /// The names of `repo`'s branches (`owner/name`).
    Promise<List<BranchName>> heads(String repo);
}
