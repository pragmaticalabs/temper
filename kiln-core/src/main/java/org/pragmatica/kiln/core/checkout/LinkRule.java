package org.pragmatica.kiln.core.checkout;

/// The spec §5.3 rule that linked a checkout to its PRs, in priority order; [#NONE] when no rule yielded a PR.
public enum LinkRule {
    BRANCH,
    SHA,
    ANCESTRY,
    NONE
}
