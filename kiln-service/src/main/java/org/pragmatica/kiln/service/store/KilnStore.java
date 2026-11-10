package org.pragmatica.kiln.service.store;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// kiln-pg, as the loops and the pages see it (kiln-f1-design §3.5). Every table is a cache: dropping the database
/// loses nothing (§2), except the work ledger ([#work], `work_item` and `work_event`), which nothing rebuilds: back it up
/// first with `pg_dump --data-only -t work_item -t work_event` and load it into a fresh kiln-pg once V5 has applied
/// (docs/kiln-f1-design.md §2). Built in WP5 (`PgKilnStore`, opened by `KilnPostgres.connect`). WP5 added [#bases] (§2's
/// `base_run` and `gh_issue` had no writer) and [#close].
public interface KilnStore {
    PrStore prs();
    CheckStore checks();
    SourceStore sources();
    CheckoutStore checkouts();
    ChangeStore changes();
    EtagStore etags();
    TemperStore temper();
    ViewStore views();
    BaseStore bases();
    /// The change feed's GitHub-derived state (CI interface T1 WP2).
    FeedStore feed();
    /// The team's work ledger (the Work tab).
    WorkStore work();
    /// The pipeline baseline's merged PRs and their measurements (#102).
    PipelineStore pipeline();
    /// Closes the pool; the store is unusable after.
    Promise<Unit> close();
}
