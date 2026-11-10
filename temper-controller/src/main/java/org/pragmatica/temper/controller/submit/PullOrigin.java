package org.pragmatica.temper.controller.submit;

/// Where a pull request's head branch lives. Only a `SAME_REPO` head counts as pushed: fork PRs are a non-goal
/// (spec §13), and a PR whose fork was deleted is a `FORK`.
public enum PullOrigin {
    SAME_REPO,
    FORK;
    /// `head` and `base` are the repositories' `owner/name`.
    public static PullOrigin pullOrigin(String head, String base) {
        return head.equals(base)
               ? SAME_REPO
               : FORK;
    }
}
