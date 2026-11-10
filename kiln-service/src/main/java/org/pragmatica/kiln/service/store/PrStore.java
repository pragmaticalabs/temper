package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The `pr` table: every PR, kept forever (K-13).
public interface PrStore {
    /// Inserts or updates, and records each `PR_STATE` change in the same transaction. Never deletes. The changes come back
    /// in the change rule's order, each carrying `observedAt` and the store's `recordedAt` ([ChangeStore#watermark]).
    default Promise<List<Change>> upsert(List<PullRequest> prs, Instant observedAt) {
        return upsert(prs, Map.of(), observedAt);
    }

    /// As [#upsert(List, Instant)]; a PR in `mergedBy` adds its `mergedBy` to its `PR_STATE` row's payload (CI interface
    /// The login the poller read for a stored open PR listed merged).
    Promise<List<Change>> upsert(List<PullRequest> prs, Map<PrNumber, String> mergedBy, Instant observedAt);

    /// Writes the open-PR detail. An absent head date keeps the stored one (a failed date read never erases it);
    /// [#upsert] clears it when the head SHA changes, so a kept date is always the current head's.
    Promise<Unit> openDetail(PrNumber pr, OpenDetail detail, Instant observedAt);
    Promise<Unit> behind(PrNumber pr, Sha head, BranchName base, BehindFacts facts, Instant fetchedAt);
    /// The full table (K-13).
    Promise<List<PullRequest>> all();
    /// The `pulls_high_water` cursor: the greatest `updated_at` read.
    Promise<Option<Instant>> highWater();
    Promise<Unit> highWater(Instant at);
    /// The `backfill_done` cursor: when the one-time backfill (design §4.1) finished; none until it has.
    Promise<Option<Instant>> backfillDone();
    Promise<Unit> backfillDone(Instant at);
}
