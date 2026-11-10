package org.pragmatica.kiln.core.github;

/// A PR's state as kiln stores it (spec §4.1): GitHub's `open`/`closed`, with `merged_at` set read as [#MERGED].
/// Merged and closed come only from this state, never from ancestry against a base (spec §5.3).
public enum PrState {
    OPEN,
    CLOSED,
    MERGED
}
