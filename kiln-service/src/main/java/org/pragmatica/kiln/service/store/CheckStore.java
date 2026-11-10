package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `required_checks` and `check_cell`.
public interface CheckStore {
    /// Rewrites a base's list; a failed read is written as [RequiredChecks.Unknown].
    Promise<Unit> required(BranchName base, RequiredChecks list, Instant observedAt);
    /// Replaces a PR's cells with those of `head`, and records each `CHECK_CELL` change in the same transaction. A cell is
    /// keyed by its check, `{context, appId}`: one context pinned to two apps (or pinned and not) keeps two cells, and a
    /// change's subject is the check's [RequiredCheck#key] (shown through [RequiredCheck#label(String)], never raw). The
    /// changes come back in the change rule's order, each carrying `observedAt` and the store's `recordedAt`
    /// ([ChangeStore#watermark]).
    ///
    /// Precondition (lead ruling, WP5 verification): one writer per PR at a time (the single-flight `GitHubPoller`,
    /// §3.1). Concurrent writes for one PR may record a wrong `before` or fail.
    Promise<List<Change>> cells(PrNumber pr, Sha head, List<Cell> cells, Instant observedAt);
}
