package org.pragmatica.kiln.core.summary;

import java.time.Instant;

import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.model.PrNumber;


/// One observed change (spec §7): `subject` is the cell key ([RequiredCheck#key]: `context`, or `context` U+001F
/// `appId` when an app is pinned) for [ChangeKind#CHECK_CELL], the PR for [ChangeKind#PR_STATE], the base the PR
/// targets for [ChangeKind#HEAD_MOVED] (`before`/`after` the old and new head SHA). A rendering shows a
/// cell subject as [RequiredCheck#label(String)], never raw.
///
/// Two instants (lead ruling, 2026-10-30): `observedAt` is when the fact was observed, and is what the summary shows;
/// `recordedAt` is the summary cursor, stamped by the store when the write recording the change begins, so a caller's lag
/// between observing and writing never puts a change behind a cursor already handed out. [Changes#between] answers
/// `recordedAt` = `observedAt`; the store restamps it ([#recorded]).
public record Change(Instant observedAt,
                     PrNumber pr,
                     ChangeKind kind,
                     String subject,
                     String before,
                     String after,
                     Instant recordedAt) {
    /// This change, recorded at `at`.
    public Change recorded(Instant at) {
        return new Change(observedAt, pr, kind, subject, before, after, at);
    }
}
