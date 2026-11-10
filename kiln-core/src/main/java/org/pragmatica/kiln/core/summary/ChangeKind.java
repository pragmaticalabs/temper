package org.pragmatica.kiln.core.summary;

/// What an observed change is about (spec §7 `summary`): a required-check cell, a PR's state, or an open PR's head moving.
/// The `change.kind` CHECK also admits the kinds WP2 adds, so they need no further migration.
public enum ChangeKind {
    CHECK_CELL,
    PR_STATE,
    HEAD_MOVED
}
