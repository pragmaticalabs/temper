package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.Changes;
import org.pragmatica.kiln.core.summary.PrSnapshot;


/// The change rule the store applies inside its write transaction: [Changes#between] in production (WP2's). The store
/// owns only the transaction (a change row commits or rolls back with its value, §2 `change`); its ITs pass their own
/// rule, so that guarantee is pinned whatever the rule decides.
@FunctionalInterface
interface ChangeDiff {
    ChangeDiff CHANGES = Changes::between;
    List<Change> between(PrSnapshot before, PrSnapshot after, Instant observedAt);
}
