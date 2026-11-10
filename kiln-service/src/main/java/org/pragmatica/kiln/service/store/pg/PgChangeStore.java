package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.pragmatica.kiln.core.feed.FeedChange;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.summary.Change;
import org.pragmatica.kiln.core.summary.ChangeKind;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.ChangeStore;
import org.pragmatica.kiln.service.store.KilnStoreError;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.vo.Uuid;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// [ChangeStore] over `change`. [#insert] is the only writer, called by [PgPrStore] and [PgCheckStore] inside the
/// transaction that changes the value, so a change row commits or rolls back with it. [#feed] serves
/// `GET /api/v1/changes` from one read-only snapshot, after the restore check ([HighWater#guarded]).
record PgChangeStore(Db db, Watermark committed, HighWater feedMark) implements ChangeStore {
    private static final String TABLE = "change";

    private static final String INSERT = "INSERT INTO change (observed_at, kind, pr, subject, before, after, recorded_at, repo, payload) "
                                       + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb)";

    /// The epoch; `head` the last id the sequence handed out (0 before the first), never `max(id)`, so pruning every row
    /// never moves it back; `floor` the prune watermark (`change_floor`): every id below it was pruned, and an id missing
    /// at or above it was burned by a rolled-back write, never pruned (lead ruling, verifier FIX-1). An id the sequence
    /// handed to a write still in flight is counted in `head` before its row is listed.
    private static final String BOUNDS = "SELECT e.epoch, s.head, f.floor "
                                       + "FROM (SELECT id::text AS epoch FROM kiln_epoch) e, "
                                       + "(SELECT CASE WHEN is_called THEN last_value ELSE 0 END AS head FROM change_id_seq) s, "
                                       + "(SELECT floor FROM change_floor) f";

    /// A missing epoch row is replaced by a new epoch (verifier FIX-2): an unknown epoch reads as a reset, never as a
    /// failure. The unique index on a constant keeps it one row when two readers race.
    static final String ENSURE_EPOCH = "INSERT INTO kiln_epoch (id, created_at) SELECT gen_random_uuid(), now() "
                                     + "WHERE NOT EXISTS (SELECT 1 FROM kiln_epoch) ON CONFLICT DO NOTHING";

    /// Prunes by id (lead ruling): the cutoff is the greatest id recorded before `$1`; every id at or below it is deleted
    /// and the floor becomes cutoff + 1, in one statement. So no id survives below the floor, also when a clock step back
    /// left `recorded_at` out of id order; a newer row below the cutoff goes with it, and a reader behind it reads a gap.
    private static final String PRUNE = "WITH cutoff AS (SELECT max(id) AS id FROM change WHERE recorded_at < $1), "
                                      + "gone AS (DELETE FROM change WHERE id <= (SELECT id FROM cutoff)) "
                                      + "UPDATE change_floor SET floor = greatest(floor, (SELECT id + 1 FROM cutoff)) WHERE id = 1";

    /// Every transaction that inserts into `change` takes this transaction-scoped lock before its first id is drawn,
    /// so change transactions commit in id order even with a second kiln process on the database (verifier NOTE-3).
    static final long CHANGE_LOCK = 7172L;

    private static final String AFTER = "SELECT id, kind, repo, pr, subject, before, after, observed_at, recorded_at, "
                                      + "payload::text AS payload FROM change WHERE id > $1 ORDER BY id LIMIT $2";

    /// The feed's bounds, before its rows are attached.
    record Bounds(Uuid epoch, long floor, long head) {}

    /// The first `limit` rows after `since`, widened to the end of the last instant they reach: a page never ends inside
    /// one `recorded_at`, since the next page starts after it (no rows when none follow `since`).
    ///
    /// Only the summary's kinds ([ChangeKind]) about a PR are listed (CI interface T1 WP2): the feed's own kinds, and a
    /// row about no PR, are for `/api/v1/changes`, and a kind this build does not know (a newer kiln's, after a
    /// rollback) is left out rather than failing the summary.
    private static final String SUMMARY_ROWS = "kind = ANY($3::text[]) AND pr IS NOT NULL";

    private static final String SINCE = "SELECT observed_at, kind, pr, subject, before, after, recorded_at FROM change "
                                      + "WHERE recorded_at > $1 AND " + SUMMARY_ROWS
                                      + " AND recorded_at <= (SELECT max(recorded_at) FROM "
                                      + "(SELECT recorded_at FROM change WHERE recorded_at > $1 AND " + SUMMARY_ROWS
                                      + " ORDER BY recorded_at, id LIMIT $2) page) "
                                      + "ORDER BY recorded_at, id";

    @Override
    public Promise<List<Change>> since(Instant since, int limit) {
        return Verify.ensure(limit,
                             Verify.Is::positive,
                             KilnStoreError.InvalidLimit.FACTORY.apply(limit))
                     .async()
                     .flatMap(positive -> page(since, positive));
    }

    private Promise<List<Change>> page(Instant since, int limit) {
        return Sql.query(db.executor(),
                         SINCE,
                         Sql.utc(since),
                         limit,
                         Sql.texts(Arrays.stream(ChangeKind.values()).map(ChangeKind::name)))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgChangeStore::change));
    }

    @Override
    public Promise<ChangeFeed> feed(long after, int max) {
        return Verify.ensure(max,
                             Verify.Is::positive,
                             KilnStoreError.InvalidLimit.FACTORY.apply(max))
                     .async()
                     .flatMap(positive -> guardedFeed(after, positive));
    }

    /// The restore check first (R2-F1): a database restored under a running kiln gets a new epoch before this read, so
    /// a reader holding the old one is told to reset. The check also creates a missing epoch row (verifier FIX-2).
    private Promise<ChangeFeed> guardedFeed(long after, int max) {
        return feedMark.guarded(db.executor())
                       .flatMap(_ -> db.inTransaction(tx -> feedIn(tx,
                                                                   after,
                                                                   max,
                                                                   feedMark.lastOkAt())));
    }

    /// Bounds and rows from one `REPEATABLE READ` snapshot ([Sql#snapshot]), so `head` is at least every listed id and
    /// `floor` is the floor of the rows listed. The bounds query is an aggregate, so it always answers one row; none
    /// would fail as `CoreError.emptyOption`.
    private static Promise<ChangeFeed> feedIn(Transaction tx, long after, int max, Option<Instant> highWaterOkAt) {
        return Sql.snapshot(tx)
                  .flatMap(_ -> Sql.query(tx, BOUNDS))
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgChangeStore::bounds))
                  .flatMap(Option::async)
                  .flatMap(bounds -> rowsAfter(tx, bounds, after, max, highWaterOkAt));
    }

    private static Promise<ChangeFeed> rowsAfter(Transaction tx,
                                                 Bounds bounds,
                                                 long after,
                                                 int max,
                                                 Option<Instant> highWaterOkAt) {
        return Sql.query(tx, AFTER, after, max)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgChangeStore::row))
                  .map(rows -> new ChangeFeed(bounds.epoch(),
                                              bounds.floor(),
                                              bounds.head(),
                                              rows,
                                              highWaterOkAt));
    }

    @Override
    public Instant watermark() {
        return committed.watermark();
    }

    @Override
    public Promise<Option<Instant>> oldest() {
        return Sql.query(db.executor(),
                         "SELECT min(recorded_at) AS oldest FROM change")
                  .map(PgChangeStore::oldestOf);
    }

    @Override
    public Promise<Unit> prune(Instant before) {
        return Sql.execute(db.executor(), PRUNE, Sql.utc(before));
    }

    /// The newest `recorded_at` stored, where the watermark starts ([Watermark#watermark]).
    static Promise<Option<Instant>> lastRecorded(QueryExecutor executor) {
        return Sql.query(executor, "SELECT max(recorded_at) AS last FROM change").map(resultSet -> instant(resultSet,
                                                                                                           "last"));
    }

    private static Option<Instant> instant(PgResultSet resultSet, String column) {
        return Sql.first(resultSet).flatMap(row -> option(row.getInstant(column)));
    }

    private static Option<Instant> oldestOf(PgResultSet resultSet) {
        return instant(resultSet, "oldest");
    }

    /// `changes` as recorded at `recordedAt`: the cursor the store stamps, never the caller's `observedAt`.
    static List<Change> recorded(List<Change> changes, Instant recordedAt) {
        return changes.stream()
                      .map(change -> change.recorded(recordedAt))
                      .toList();
    }

    /// Inserts `changes` about `repo` on `tx`, one statement at a time, and answers them.
    ///
    /// Single writer (review F9): the only callers are [PgPrStore#upsert] and [PgCheckStore#cells],
    /// and the only caller of those is the GitHub poller's single-flight cycle (`GitHubPoller.guarded`), so no two change
    /// transactions overlap and ids commit in id order: a reader paging `id > after` never passes over an id committed
    /// later; [#CHANGE_LOCK] enforces the order in the database too, whoever writes. A cycle past its deadline keeps the guard until its in-flight write settles (`GitHubPoller.held`). Pinned by
    /// `GitHubPollerIT.concurrentTicks_runOneCycle_soChangeWritesNeverOverlap` and
    /// `GitHubPollerIT.timedOutCycle_holdsTheGuard_untilItsWriteSettles`.
    static Promise<List<Change>> insert(QueryExecutor tx, HighWater mark, String repo, List<Change> changes) {
        return insert(tx, mark, repo, changes, _ -> none());
    }

    /// [#insert], each row with the payload `payloads` answers for its change (SQL NULL when none): the `PR_STATE`
    /// facts [PgPrStore#upsert] writes. Same single writer, lock and restore check.
    static Promise<List<Change>> insert(QueryExecutor tx,
                                        HighWater mark,
                                        String repo,
                                        List<Change> changes,
                                        Fn1<Option<Map<String, Object>>, Change> payloads) {
        return guardedWrite(tx, mark).flatMap(_ -> Sql.sequence(changes,
                                                                change -> insertOne(tx,
                                                                                    repo,
                                                                                    change,
                                                                                    payloads.apply(change))))
                           .map(_ -> changes);
    }

    /// Every path that inserts into `change` ([#insert], [#insertFeed]) calls this first, before its first id is drawn:
    /// [#CHANGE_LOCK] for the rest of `tx`, so change transactions commit in id order whoever writes; then the restore
    /// check inside the transaction ([HighWater#verified]), so a restore cannot land between the check and the ids drawn.
    private static Promise<Unit> guardedWrite(QueryExecutor tx, HighWater mark) {
        return Sql.execute(tx,
                           "SELECT pg_advisory_xact_lock($1)",
                           CHANGE_LOCK)
                  .flatMap(_ -> mark.verified(tx))
                  .mapToUnit();
    }

    private static Promise<Unit> insertOne(QueryExecutor tx,
                                           String repo,
                                           Change change,
                                           Option<Map<String, Object>> payload) {
        return Rows.flip(payload.map(Json::write))
                   .async()
                   .flatMap(text -> Sql.execute(tx,
                                                INSERT,
                                                Sql.utc(change.observedAt()),
                                                change.kind().name(),
                                                change.pr().value(),
                                                change.subject(),
                                                change.before(),
                                                change.after(),
                                                Sql.utc(change.recordedAt()),
                                                repo,
                                                Sql.nullable(text)));
    }

    /// Inserts the feed's `changes` about `repo` on `tx`, stamped `recordedAt`, one statement at a time, and answers
    /// them. Single writer as [#insert]: the only caller is `PgFeedStore`, called only from the GitHub poller's
    /// single-flight cycle; it takes the lock and runs the restore check as [#insert] does ([#guardedWrite]).
    static Promise<List<FeedChange>> insertFeed(QueryExecutor tx,
                                                HighWater mark,
                                                String repo,
                                                List<FeedChange> changes,
                                                Instant recordedAt) {
        return guardedWrite(tx, mark).flatMap(_ -> Sql.sequence(changes,
                                                                change -> insertFeedOne(tx, repo, change, recordedAt)))
                           .map(_ -> changes);
    }

    private static Promise<Unit> insertFeedOne(QueryExecutor tx, String repo, FeedChange change, Instant recordedAt) {
        return Json.write(change.payload())
                   .async()
                   .flatMap(payload -> Sql.execute(tx,
                                                   INSERT,
                                                   Sql.utc(change.observedAt()),
                                                   change.kind().name(),
                                                   Sql.nullable(change.pr().map(PrNumber::value)),
                                                   change.subject(),
                                                   change.before(),
                                                   change.after(),
                                                   Sql.utc(recordedAt),
                                                   repo,
                                                   payload));
    }

    private static Result<Bounds> bounds(PgRow row) {
        return Rows.required(row, "epoch")
                   .flatMap(Uuid::uuid)
                   .map(epoch -> new Bounds(epoch,
                                            row.getLong("floor"),
                                            row.getLong("head")))
                   .mapError(Rows.corrupt("kiln_epoch"));
    }

    private static Result<ChangeFeed.Row> row(PgRow row) {
        return Result.all(Rows.flip(option(row.getLong("pr")).map(PrNumber::prNumber)),
                          Rows.required(row, "kind"))
                     .map((pr, kind) -> new ChangeFeed.Row(row.getLong("id"),
                                                           kind,
                                                           row.getString("repo"),
                                                           pr,
                                                           row.getString("subject"),
                                                           row.getString("before"),
                                                           row.getString("after"),
                                                           row.getInstant("observed_at"),
                                                           row.getInstant("recorded_at"),
                                                           Rows.text(row, "payload")))
                     .mapError(Rows.corrupt(TABLE));
    }

    private static Result<Change> change(PgRow row) {
        return Result.all(PrNumber.prNumber(row.getLong("pr")),
                          Rows.constant(ChangeKind.class,
                                        row.getString("kind")))
                     .map((pr, kind) -> new Change(row.getInstant("observed_at"),
                                                   pr,
                                                   kind,
                                                   row.getString("subject"),
                                                   row.getString("before"),
                                                   row.getString("after"),
                                                   row.getInstant("recorded_at")))
                     .mapError(Rows.corrupt(TABLE));
    }
}
