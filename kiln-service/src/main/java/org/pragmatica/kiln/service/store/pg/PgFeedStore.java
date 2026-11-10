package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.feed.CheckFeed;
import org.pragmatica.kiln.core.feed.FeedChange;
import org.pragmatica.kiln.core.feed.IssueFeed;
import org.pragmatica.kiln.core.feed.ListFeed;
import org.pragmatica.kiln.core.feed.RunFeed;
import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.store.BaseTip;
import org.pragmatica.kiln.service.store.FeedStore;
import org.pragmatica.kiln.service.store.HeadChecks;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Transaction;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// [FeedStore] over kiln V4's `feed_check`, `feed_settle`, `feed_run`, `feed_issue` and `required_list_history`. Every
/// write runs through the change [Watermark] and one transaction: it locks the stored state it judges against (`FOR
/// UPDATE`), applies the rule, rewrites the state and inserts the changes ([PgChangeStore#insertFeed]).
record PgFeedStore(Db db, Watermark committed, HighWater feedMark) implements FeedStore {
    private static final String BASE_TIP = "SELECT sha, tree, seen_at FROM base_tip WHERE repo = $1 AND base = $2";

    private static final String UPSERT_TIP = "INSERT INTO base_tip (repo, base, sha, tree, seen_at) VALUES ($1, $2, $3, $4, $5) "
                                           + "ON CONFLICT (repo, base) DO UPDATE SET sha = EXCLUDED.sha, tree = EXCLUDED.tree, "
                                           + "seen_at = EXCLUDED.seen_at";

    private static final String DROP_OLD_HEAD = "DELETE FROM feed_check WHERE repo = $1 AND pr = $2 AND head_sha <> $3";

    private static final String STORED_CHECKS = "SELECT obs_key, fingerprint FROM feed_check WHERE repo = $1 AND pr = $2 FOR UPDATE";

    private static final String DROP_CHECKS = "DELETE FROM feed_check WHERE repo = $1 AND pr = $2";

    private static final String INSERT_CHECK = "INSERT INTO feed_check (repo, pr, head_sha, obs_key, fingerprint, observed_at) "
                                             + "VALUES ($1, $2, $3, $4, $5, $6)";

    private static final String STORED_SETTLE = "SELECT head_sha, first_seen, outcome FROM feed_settle WHERE repo = $1 AND pr = $2 FOR UPDATE";

    private static final String UPSERT_SETTLE = "INSERT INTO feed_settle (repo, pr, head_sha, first_seen, outcome, observed_at) "
                                              + "VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT (repo, pr) DO UPDATE SET "
                                              + "head_sha = EXCLUDED.head_sha, first_seen = EXCLUDED.first_seen, "
                                              + "outcome = EXCLUDED.outcome, observed_at = EXCLUDED.observed_at";

    private static final String LIST_VERSION = "SELECT coalesce(max(version), 0) AS version FROM required_list_history "
                                             + "WHERE repo = $1 AND base = $2";

    private static final String STORED_RUN = "SELECT status, conclusion, attempt FROM feed_run WHERE repo = $1 AND id = $2 FOR UPDATE";

    private static final String UPSERT_RUN = "INSERT INTO feed_run (repo, id, workflow, event, branch, status, conclusion, attempt, "
                                           + "created_at, refreshed_at) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10) "
                                           + "ON CONFLICT (repo, id) DO UPDATE SET workflow = EXCLUDED.workflow, "
                                           + "event = EXCLUDED.event, branch = EXCLUDED.branch, status = EXCLUDED.status, "
                                           + "conclusion = EXCLUDED.conclusion, attempt = EXCLUDED.attempt, "
                                           + "created_at = EXCLUDED.created_at, refreshed_at = EXCLUDED.refreshed_at";

    private static final String NEWEST_RUN = "SELECT max(created_at) AS newest FROM feed_run WHERE repo = $1 AND event = $2 "
                                           + "AND ($3::text IS NULL OR branch = $3::text)";

    private static final String REREADABLE = "SELECT id FROM feed_run WHERE repo = $1 AND (status <> 'completed' OR (created_at >= $2 "
                                           + "AND refreshed_at < $3)) ORDER BY (status = 'completed'), refreshed_at, id LIMIT $4";

    private static final String STORED_ISSUE = "SELECT state FROM feed_issue WHERE repo = $1 AND number = $2 FOR UPDATE";

    private static final String UPSERT_ISSUE = "INSERT INTO feed_issue (repo, number, state, updated_at, observed_at) "
                                             + "VALUES ($1, $2, $3, $4, $5) ON CONFLICT (repo, number) DO UPDATE SET "
                                             + "state = EXCLUDED.state, updated_at = EXCLUDED.updated_at, "
                                             + "observed_at = EXCLUDED.observed_at";

    private static final String NEWEST_ISSUE = "SELECT max(updated_at) AS newest FROM feed_issue WHERE repo = $1";

    private static final String LATEST_LIST = "SELECT version, contexts::text AS contexts FROM required_list_history "
                                            + "WHERE repo = $1 AND base = $2 ORDER BY version DESC LIMIT 1 FOR UPDATE";

    private static final String INSERT_LIST = "INSERT INTO required_list_history (repo, base, version, contexts, etag, first_seen, last_seen) "
                                            + "VALUES ($1, $2, $3, $4::jsonb, $6, $5, $5)";

    private static final String SEEN_LIST = "UPDATE required_list_history SET last_seen = $4, etag = $5 WHERE repo = $1 AND base = $2 AND version = $3";

    /// The settle row as stored for this head: a moved head starts again, first seen now, nothing reported.
    record Settle(Instant firstSeen, Option<String> outcome) {}

    /// What one head is judged against: its stored fingerprints, its settle row, and its base's list version.
    record State(Map<String, String> fingerprints, Settle settle, long version) {}

    /// The newest stored list version and its contexts.
    record Version(long version, List<String> contexts) {}

    // checks
    @Override
    public Promise<List<FeedChange>> checks(String repo, HeadChecks checks, Instant at) {
        return committed.tracked(recordedAt -> db.inTransaction(tx -> checksIn(tx, repo, checks, at, recordedAt)));
    }

    private Promise<List<FeedChange>> checksIn(Transaction tx,
                                               String repo,
                                               HeadChecks checks,
                                               Instant at,
                                               Instant recordedAt) {
        return Sql.execute(tx,
                           DROP_OLD_HEAD,
                           repo,
                           checks.pr().value(),
                           checks.head().value())
                  .flatMap(_ -> storedChecks(tx, repo, checks))
                  .flatMap(stored -> withSettle(tx, repo, checks, at, stored))
                  .flatMap(state -> judged(tx, repo, checks, state, at, recordedAt));
    }

    /// One statement at a time on the transaction's connection.
    private static Promise<State> withSettle(Transaction tx,
                                             String repo,
                                             HeadChecks checks,
                                             Instant at,
                                             Map<String, String> stored) {
        return storedSettle(tx, repo, checks, at).flatMap(settle -> withVersion(tx, repo, checks, stored, settle));
    }

    private static Promise<State> withVersion(Transaction tx,
                                              String repo,
                                              HeadChecks checks,
                                              Map<String, String> stored,
                                              Settle settle) {
        return listVersion(tx, repo, checks.base()).map(version -> new State(stored, settle, version));
    }

    private static Promise<Map<String, String>> storedChecks(Transaction tx, String repo, HeadChecks checks) {
        return Sql.query(tx,
                         STORED_CHECKS,
                         repo,
                         checks.pr().value())
                  .map(resultSet -> Sql.readAll(resultSet,
                                                row -> Map.entry(row.getString("obs_key"),
                                                                 row.getString("fingerprint"))))
                  .map(PgFeedStore::asMap);
    }

    private static Map<String, String> asMap(List<Map.Entry<String, String>> entries) {
        return entries.stream()
                      .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Promise<Settle> storedSettle(Transaction tx, String repo, HeadChecks checks, Instant at) {
        return Sql.query(tx,
                         STORED_SETTLE,
                         repo,
                         checks.pr().value())
                  .map(resultSet -> settleOf(resultSet, checks, at));
    }

    /// The stored row when it is about this head; a moved head (or none stored) starts again at `at`.
    private static Settle settleOf(PgResultSet resultSet, HeadChecks checks, Instant at) {
        return Sql.readFirst(resultSet, row -> row)
                  .filter(row -> sameHead(row, checks))
                  .map(PgFeedStore::settle)
                  .or(new Settle(at,
                                 none()));
    }

    private static boolean sameHead(PgRow row, HeadChecks checks) {
        return checks.head()
                     .value()
                     .equals(row.getString("head_sha"));
    }

    private static Settle settle(PgRow row) {
        return new Settle(row.getInstant("first_seen"), Rows.text(row, "outcome"));
    }

    private static Promise<Long> listVersion(Transaction tx, String repo, BranchName base) {
        return Sql.query(tx,
                         LIST_VERSION,
                         repo,
                         base.value())
                  .map(resultSet -> Sql.readFirst(resultSet,
                                                  row -> row.getLong("version"))
                                       .or(0L));
    }

    private Promise<List<FeedChange>> judged(Transaction tx,
                                             String repo,
                                             HeadChecks checks,
                                             State state,
                                             Instant at,
                                             Instant recordedAt) {
        var settle = state.settle();
        var settled = CheckFeed.settled(checks.list(), checks.observed(), checks.headRuns(), settle.firstSeen(), at);
        var changes = Stream.concat(CheckFeed.concluded(checks.pr(),
                                                        checks.head(),
                                                        state.fingerprints(),
                                                        checks.observed(),
                                                        state.version(),
                                                        at)
                                             .stream(),
                                    CheckFeed.settledChange(checks.pr(),
                                                            checks.head(),
                                                            settle.outcome(),
                                                            settled,
                                                            at)
                                             .stream())
                            .toList();
        var reported = settled.map(CheckFeed.Settled::outcome).orElse(settle.outcome());

        return replaceChecks(tx, repo, checks, at).flatMap(_ -> Sql.execute(tx,
                                                                            UPSERT_SETTLE,
                                                                            repo,
                                                                            checks.pr().value(),
                                                                            checks.head().value(),
                                                                            Sql.utc(settle.firstSeen()),
                                                                            Sql.nullable(reported),
                                                                            Sql.utc(at)))
                            .flatMap(_ -> PgChangeStore.insertFeed(tx, feedMark, repo, changes, recordedAt));
    }

    private static Promise<Unit> replaceChecks(Transaction tx, String repo, HeadChecks checks, Instant at) {
        return Sql.execute(tx,
                           DROP_CHECKS,
                           repo,
                           checks.pr().value())
                  .flatMap(_ -> Sql.sequence(List.copyOf(CheckFeed.fingerprints(checks.observed()).entrySet()),
                                             entry -> insertCheck(tx, repo, checks, entry, at)))
                  .mapToUnit();
    }

    private static Promise<Unit> insertCheck(Transaction tx,
                                             String repo,
                                             HeadChecks checks,
                                             Map.Entry<String, String> fingerprint,
                                             Instant at) {
        return Sql.execute(tx,
                           INSERT_CHECK,
                           repo,
                           checks.pr().value(),
                           checks.head().value(),
                           fingerprint.getKey(),
                           fingerprint.getValue(),
                           Sql.utc(at));
    }

    // runs
    @Override
    public Promise<List<FeedChange>> runs(String repo, List<WorkflowRun> runs, Instant at) {
        return committed.tracked(recordedAt -> db.inTransaction(tx -> runsIn(tx, repo, runs, at, recordedAt)));
    }

    private Promise<List<FeedChange>> runsIn(Transaction tx,
                                             String repo,
                                             List<WorkflowRun> runs,
                                             Instant at,
                                             Instant recordedAt) {
        return Sql.sequence(runs,
                            run -> run(tx, repo, run, at))
                  .map(PgFeedStore::flattened)
                  .flatMap(changes -> PgChangeStore.insertFeed(tx, feedMark, repo, changes, recordedAt));
    }

    private static Promise<List<FeedChange>> run(Transaction tx, String repo, WorkflowRun run, Instant at) {
        return Sql.query(tx,
                         STORED_RUN,
                         repo,
                         run.id())
                  .map(PgFeedStore::storedRun)
                  .map(stored -> RunFeed.changes(stored, run, at))
                  .flatMap(changes -> upsertRun(tx, repo, run, at, changes));
    }

    private static Option<RunFeed.Stored> storedRun(PgResultSet resultSet) {
        return Sql.readFirst(resultSet,
                             row -> new RunFeed.Stored(row.getString("status"),
                                                       Rows.text(row, "conclusion"),
                                                       row.getLong("attempt")));
    }

    /// Stores the run as read, refreshed at `at`, and answers its `changes`.
    private static Promise<List<FeedChange>> upsertRun(Transaction tx,
                                                       String repo,
                                                       WorkflowRun run,
                                                       Instant at,
                                                       List<FeedChange> changes) {
        return Sql.execute(tx,
                           UPSERT_RUN,
                           repo,
                           run.id(),
                           run.name(),
                           run.event().or(RunFeed.PUSH),
                           Sql.nullable(run.headBranch()),
                           run.status(),
                           Sql.nullable(run.conclusion()),
                           run.attempt(),
                           Sql.utc(run.createdAt()),
                           Sql.utc(at))
                  .map(_ -> changes);
    }

    @Override
    public Promise<Option<Instant>> newestCreated(String repo, String event, Option<String> branch) {
        return Sql.query(db.executor(),
                         NEWEST_RUN,
                         repo,
                         event,
                         Sql.nullable(branch))
                  .map(PgFeedStore::newest);
    }

    private static Option<Instant> newest(PgResultSet resultSet) {
        return Sql.first(resultSet).flatMap(row -> option(row.getInstant("newest")));
    }

    @Override
    public Promise<List<Long>> rereadable(String repo, Instant recent, Instant before, int max) {
        return Sql.query(db.executor(),
                         REREADABLE,
                         repo,
                         Sql.utc(recent),
                         Sql.utc(before),
                         max)
                  .map(resultSet -> Sql.readAll(resultSet,
                                                row -> row.getLong("id")));
    }

    @Override
    public Promise<Unit> forgotten(String repo, long id) {
        return Sql.execute(db.executor(), "DELETE FROM feed_run WHERE repo = $1 AND id = $2", repo, id);
    }

    // issues
    @Override
    public Promise<List<FeedChange>> issues(String repo, List<BotIssue> issues, Instant at) {
        return committed.tracked(recordedAt -> db.inTransaction(tx -> issuesIn(tx, repo, issues, at, recordedAt)));
    }

    private Promise<List<FeedChange>> issuesIn(Transaction tx,
                                               String repo,
                                               List<BotIssue> issues,
                                               Instant at,
                                               Instant recordedAt) {
        return Sql.sequence(issues,
                            issue -> issue(tx, repo, issue, at))
                  .map(PgFeedStore::flattened)
                  .flatMap(changes -> PgChangeStore.insertFeed(tx, feedMark, repo, changes, recordedAt));
    }

    private static Promise<List<FeedChange>> issue(Transaction tx, String repo, BotIssue issue, Instant at) {
        return Sql.query(tx,
                         STORED_ISSUE,
                         repo,
                         issue.number())
                  .map(resultSet -> Sql.readFirst(resultSet,
                                                  row -> row.getString("state")))
                  .map(stored -> IssueFeed.changes(stored, issue, at))
                  .flatMap(changes -> upsertIssue(tx, repo, issue, at, changes));
    }

    /// Stores the issue as read and answers its `changes`.
    private static Promise<List<FeedChange>> upsertIssue(Transaction tx,
                                                         String repo,
                                                         BotIssue issue,
                                                         Instant at,
                                                         List<FeedChange> changes) {
        return Sql.execute(tx,
                           UPSERT_ISSUE,
                           repo,
                           issue.number(),
                           issue.state().or(IssueFeed.OPEN),
                           Sql.utc(issue.updatedAt().or(issue.createdAt())),
                           Sql.utc(at))
                  .map(_ -> changes);
    }

    @Override
    public Promise<Option<Instant>> newestIssue(String repo) {
        return Sql.query(db.executor(),
                         NEWEST_ISSUE,
                         repo)
                  .map(PgFeedStore::newest);
    }

    // required lists
    @Override
    public Promise<List<FeedChange>> requiredList(String repo,
                                                  BranchName base,
                                                  List<String> contexts,
                                                  Option<String> etag,
                                                  Instant at) {
        return committed.tracked(recordedAt -> db.inTransaction(tx -> requiredListIn(tx,
                                                                                     repo,
                                                                                     base,
                                                                                     contexts,
                                                                                     etag,
                                                                                     at,
                                                                                     recordedAt)));
    }

    private Promise<List<FeedChange>> requiredListIn(Transaction tx,
                                                     String repo,
                                                     BranchName base,
                                                     List<String> contexts,
                                                     Option<String> etag,
                                                     Instant at,
                                                     Instant recordedAt) {
        return Sql.query(tx,
                         LATEST_LIST,
                         repo,
                         base.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgFeedStore::version))
                  .flatMap(latest -> versioned(tx, repo, base, latest, contexts, etag, at, recordedAt));
    }

    private static Result<Version> version(PgRow row) {
        return Json.read(row.getString("contexts"),
                         Json.STRINGS)
                   .map(contexts -> new Version(row.getLong("version"),
                                                contexts))
                   .mapError(Rows.corrupt("required_list_history"));
    }

    /// Condition: the same contexts move `last_seen`; other contexts (or none stored) are the next version.
    private Promise<List<FeedChange>> versioned(Transaction tx,
                                                String repo,
                                                BranchName base,
                                                Option<Version> latest,
                                                List<String> contexts,
                                                Option<String> etag,
                                                Instant at,
                                                Instant recordedAt) {
        return latest.filter(version -> version.contexts()
                                               .equals(contexts))
                     .map(same -> seen(tx, repo, base, same, etag, at))
                     .or(() -> nextVersion(tx, repo, base, latest, contexts, etag, at, recordedAt));
    }

    /// The same contexts again: only `last_seen` and the `etag` move, and nothing is reported.
    private static Promise<List<FeedChange>> seen(Transaction tx,
                                                  String repo,
                                                  BranchName base,
                                                  Version same,
                                                  Option<String> etag,
                                                  Instant at) {
        return Sql.execute(tx,
                           SEEN_LIST,
                           repo,
                           base.value(),
                           same.version(),
                           Sql.utc(at),
                           Sql.nullable(etag))
                  .map(_ -> List.of());
    }

    private Promise<List<FeedChange>> nextVersion(Transaction tx,
                                                  String repo,
                                                  BranchName base,
                                                  Option<Version> latest,
                                                  List<String> contexts,
                                                  Option<String> etag,
                                                  Instant at,
                                                  Instant recordedAt) {
        var version = latest.map(Version::version).or(0L) + 1;
        var changes = ListFeed.changed(base, latest.map(Version::contexts), contexts, version, at).stream().toList();

        return Json.write(contexts)
                   .async()
                   .flatMap(json -> Sql.execute(tx,
                                                INSERT_LIST,
                                                repo,
                                                base.value(),
                                                version,
                                                json,
                                                Sql.utc(at),
                                                Sql.nullable(etag)))
                   .flatMap(_ -> PgChangeStore.insertFeed(tx, feedMark, repo, changes, recordedAt));
    }

    // base tips
    @Override
    public Promise<Option<BaseTip>> baseTip(String repo, BranchName base) {
        return Sql.query(db.executor(),
                         BASE_TIP,
                         repo,
                         base.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet,
                                                       row -> baseTip(base, row)));
    }

    private static Result<BaseTip> baseTip(BranchName base, PgRow row) {
        return Result.all(Sha.sha(row.getString("sha")),
                          Sha.sha(row.getString("tree")))
                     .map((sha, tree) -> new BaseTip(base,
                                                     sha,
                                                     tree,
                                                     row.getInstant("seen_at")))
                     .mapError(Rows.corrupt("base_tip"));
    }

    @Override
    public Promise<List<FeedChange>> baseTip(String repo, BaseTip tip, List<FeedChange> moved) {
        return committed.tracked(recordedAt -> db.inTransaction(tx -> baseTipIn(tx, repo, tip, moved, recordedAt)));
    }

    /// The change lock first ([PgChangeStore#insertFeed] takes it before any id is drawn, also for no rows), then the tip.
    private Promise<List<FeedChange>> baseTipIn(Transaction tx,
                                                String repo,
                                                BaseTip tip,
                                                List<FeedChange> moved,
                                                Instant recordedAt) {
        return PgChangeStore.insertFeed(tx, feedMark, repo, moved, recordedAt).flatMap(changes -> Sql.execute(tx,
                                                                                                              UPSERT_TIP,
                                                                                                              repo,
                                                                                                              tip.base()
                                                                                                                 .value(),
                                                                                                              tip.sha()
                                                                                                                 .value(),
                                                                                                              tip.tree()
                                                                                                                 .value(),
                                                                                                              Sql.utc(tip.seenAt()))
                                                                                                     .map(_ -> changes));
    }

    private static <T> List<T> flattened(List<List<T>> lists) {
        return lists.stream()
                    .flatMap(List::stream)
                    .toList();
    }
}
