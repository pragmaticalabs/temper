package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.store.api.Committed;
import org.pragmatica.temper.store.api.ConsumedGo;
import org.pragmatica.temper.store.api.ControlPath;
import org.pragmatica.temper.store.api.CreatedGo;
import org.pragmatica.temper.store.api.DetectorBatch;
import org.pragmatica.temper.store.api.DetectorInput;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetSeqs;
import org.pragmatica.temper.store.api.GoBasis;
import org.pragmatica.temper.store.api.GoInvalidation;
import org.pragmatica.temper.store.api.GoOutcome;
import org.pragmatica.temper.store.api.InvalidatedGo;
import org.pragmatica.temper.store.api.MergeFacts;
import org.pragmatica.temper.store.api.MergeKey;
import org.pragmatica.temper.store.api.MergeRow;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.NewGo;
import org.pragmatica.temper.store.api.NewVerdict;
import org.pragmatica.temper.store.api.OpenGo;
import org.pragmatica.temper.store.api.QueueEntry;
import org.pragmatica.temper.store.api.QueuedPr;
import org.pragmatica.temper.store.api.RecordedOutcome;
import org.pragmatica.temper.store.api.RegisteredVerdict;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.T2Store;
import org.pragmatica.temper.store.api.VerdictRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// [T2Store] over Postgres. Every writing transaction takes [#T2_LOCK] first (the queue and control-path edits
/// included, so they serialise with the detector's queue removals); the two listings are single reads, no lock; fleet events go through
/// [PgFleetStore#insertAll] (the order lock), last in the transaction, so the lock order is T2 then order.
record PgT2Store(Db db) implements T2Store {
    static final long T2_LOCK = 70700002L;
    static final String DETECTOR = "t2:detector";
    private static final String STATE = "SELECT cursor, started_at FROM fleet_source WHERE name = $1";

    /// ONE row: the gating GO (`merged` before `merging`, then the newest; WP-0T clarifications) (`merging` or `merged`, at the sha, or at any sha when it is
    /// unknown; consumed alone is not enough) and whether a merge of the PR is recorded, at any sha (R3-2.i, WP-0T
    /// item 8: a PR merges once, so a `sha=unknown` row after the `temper-merge` record at the real sha is the same merge).
    private static final String FACTS = "SELECT g.id, g.base_tip_before, EXISTS (SELECT 1 FROM merges WHERE repo = $1 AND pr = $2::bigint) AS recorded "
                                      + "FROM (SELECT 1) one LEFT JOIN LATERAL (SELECT id::text AS id, base_tip_before::text AS base_tip_before FROM go "
                                      + "WHERE repo = $1 AND pr = $2::bigint AND outcome IN ('merging', 'merged') AND ($3::text = 'unknown' OR sha::text = $3::text) "
                                      + "ORDER BY outcome = 'merged' DESC, created_at DESC, id LIMIT 1) g ON true";

    /// The whole queue holding the PR, by position.
    private static final String QUEUE = "SELECT q.repo, q.base, q.position, q.pr FROM merge_queue m JOIN merge_queue q ON q.repo = m.repo AND q.base = m.base "
                                      + "WHERE m.repo = $1 AND m.pr = $2::bigint ORDER BY q.position";

    private static final String MERGE = "INSERT INTO merges (repo, pr, sha, base, go_id, route, merged_by, merged_at, event_seq) "
                                      + "VALUES ($1, $2::bigint, $3, $4, $5::uuid, $6, $7::text, $8::timestamptz, $9::bigint) ON CONFLICT DO NOTHING";

    /// The PR leaves its queue and every later position moves down by one, in one statement (the UPDATE reads the
    /// table as it was before the DELETE; `UNIQUE (repo, base, position)` is checked at commit).
    private static final String DEQUEUE = "WITH gone AS (DELETE FROM merge_queue WHERE repo = $1 AND pr = $2::bigint RETURNING repo, base, position) "
                                        + "UPDATE merge_queue q SET position = q.position - 1 FROM gone "
                                        + "WHERE q.repo = gone.repo AND q.base = gone.base AND q.position > gone.position";

    /// A reported verdict row.
    private static final String REPORTED = "INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) "
                                         + "VALUES ($1, $2::bigint, $3, $4, $5, $6, $7, $8) RETURNING id, at, report_path, report_sha256::text AS report_sha256";

    /// A carried row (F8): inserted only when the latest row at the carried SHA ($6) is ACCEPT at the same base, and it
    /// copies that row's report fields; no row inserted is CARRY_NO_ACCEPT.
    private static final String CARRIED = "WITH latest AS (SELECT id, base, verdict, report_path, report_sha256 FROM verdicts "
                                        + "WHERE repo = $1 AND pr = $2::bigint AND sha = $6 ORDER BY id DESC LIMIT 1) "
                                        + "INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256, carried_from, carried_verdict_id) "
                                        + "SELECT $1, $2::bigint, $3, $4, 'ACCEPT', $5, report_path, report_sha256, $6, id FROM latest "
                                        + "WHERE base = $4 AND verdict = 'ACCEPT' RETURNING id, at, report_path, report_sha256::text AS report_sha256";

    /// `verdict-changed`: every outstanding GO of (repo, pr), in creation order.
    private static final String VERDICT_CHANGED = "WITH changed AS (UPDATE go SET invalidated_at = now(), reason = 'verdict-changed' "
                                                + "WHERE repo = $1 AND pr = $2::bigint AND consumed_at IS NULL AND invalidated_at IS NULL "
                                                + "AND created_at > now() - interval '10 minutes' RETURNING id, pr, sha, base, created_at) "
                                                + "SELECT id::text AS id, pr, sha::text AS sha, base FROM changed ORDER BY created_at, id";

    /// Every queue row, with the newest outstanding GO of its PR.
    private static final String QUEUES = "SELECT q.repo, q.base, q.position, q.pr, q.added_by, q.added_at, g.id AS go_id, g.sha AS go_sha, g.expires_at AS go_expires_at "
                                       + "FROM merge_queue q LEFT JOIN LATERAL (SELECT id::text AS id, sha::text AS sha, created_at + interval '10 minutes' AS expires_at FROM go "
                                       + "WHERE go.repo = q.repo AND go.pr = q.pr AND consumed_at IS NULL AND invalidated_at IS NULL AND created_at > now() - interval '10 minutes' "
                                       + "ORDER BY created_at DESC, id LIMIT 1) g ON true "
                                       + "WHERE ($1::text IS NULL OR q.repo = $1::text) AND ($2::text IS NULL OR q.base = $2::text) ORDER BY q.repo, q.base, q.position";

    /// The PR's row (at the base, when given) with the size of its queue.
    private static final String QUEUED_AT = "SELECT q.base, q.position, (SELECT count(*) FROM merge_queue o WHERE o.repo = q.repo AND o.base = q.base) AS size "
                                          + "FROM merge_queue q WHERE q.repo = $1 AND q.pr = $2::bigint AND ($3::text IS NULL OR q.base = $3::text)";

    private static final String ENQUEUE = "INSERT INTO merge_queue (repo, base, position, pr, added_by) "
                                        + "SELECT $1::text, $2::text, coalesce(max(position), 0) + 1, $3::bigint, $4::text FROM merge_queue WHERE repo = $1::text AND base = $2::text "
                                        + "RETURNING position";

    /// [#DEQUEUE] at a base when one is given, answering the row removed.
    private static final String DEQUEUE_AT = "WITH gone AS (DELETE FROM merge_queue WHERE repo = $1 AND pr = $2::bigint AND ($3::text IS NULL OR base = $3::text) "
                                           + "RETURNING repo, base, position), "
                                           + "shifted AS (UPDATE merge_queue q SET position = q.position - 1 FROM gone "
                                           + "WHERE q.repo = gone.repo AND q.base = gone.base AND q.position > gone.position) "
                                           + "SELECT base, position FROM gone";

    /// The PR goes from $4 to $5 and every row between moves one place toward $4, in one statement (`UNIQUE (repo,
    /// base, position)` is checked at commit).
    private static final String MOVE = "UPDATE merge_queue SET position = CASE WHEN pr = $3::bigint THEN $5::int WHEN $5::int < $4::int THEN position + 1 ELSE position - 1 END "
                                     + "WHERE repo = $1 AND base = $2 AND position BETWEEN least($4::int, $5::int) AND greatest($4::int, $5::int)";

    private static final String PATHS = "SELECT glob, added_by, added_at FROM ci_control_paths WHERE repo = $1 ORDER BY glob";

    private static final String ADD_PATH = "INSERT INTO ci_control_paths (repo, glob, added_by) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING "
                                         + "RETURNING glob, added_by, added_at";

    private static final String REMOVE_PATH = "DELETE FROM ci_control_paths WHERE repo = $1 AND glob = $2";

    private static final String CURSOR = "UPDATE fleet_source SET cursor = $2::bigint, last_ok_at = $3::timestamptz WHERE name = $1";

    /// The outstanding GOs, in creation order.
    private static final String OPEN = "SELECT id::text AS id, repo, pr, sha::text AS sha, base, base_tip::text AS base_tip, fleet_seq FROM go "
                                     + "WHERE consumed_at IS NULL AND invalidated_at IS NULL AND created_at > now() - interval '10 minutes' ORDER BY created_at, id";

    /// A detector invalidation; the plan read the GO as outstanding under the T2 lock.
    private static final String INVALIDATE = "UPDATE go SET invalidated_at = now(), reason = $2, invalidated_by_seq = $3::bigint "
                                           + "WHERE id = $1::uuid AND consumed_at IS NULL AND invalidated_at IS NULL";

    /// The latest verdict row by id for the head.
    private static final String LATEST = "SELECT id, verdict, declared_by, at, base, report_path, report_sha256::text AS report_sha256, carried_from::text AS carried_from "
                                       + "FROM verdicts WHERE repo = $1 AND pr = $2::bigint AND sha = $3 ORDER BY id DESC LIMIT 1";

    private static final String EPOCH = "SELECT id::text AS epoch FROM temper_epoch";

    /// Every fleet row after the gate's head, through the current one.
    private static final String AFTER = "SELECT " + PgFleetStore.EVENT_COLUMNS
                                      + " FROM fleet_events WHERE seq > $1::bigint ORDER BY seq";

    private static final String CREATE = "INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq) VALUES ($1, $2::bigint, $3, $4, $5, $6::bigint, $7::bigint) "
                                       + "RETURNING id::text AS id, created_at";

    /// The single-use update, with the report fields of the verdict row the GO rests on.
    private static final String CONSUME = "WITH c AS (UPDATE go SET consumed_at = now(), outcome = 'consumed', outcome_at = now() "
                                        + "WHERE id = $1::uuid AND consumed_at IS NULL AND invalidated_at IS NULL AND created_at > now() - interval '10 minutes' "
                                        + "RETURNING id, repo, pr, sha, base, base_tip, verdict_id, fleet_seq, consumed_at) "
                                        + "SELECT c.id::text AS id, c.repo, c.pr, c.sha::text AS sha, c.base, c.base_tip::text AS base_tip, c.verdict_id, c.fleet_seq, c.consumed_at, "
                                        + "v.report_path, v.report_sha256::text AS report_sha256 FROM c JOIN verdicts v ON v.id = c.verdict_id";

    /// `sibling-consumed`: every other outstanding GO of the consumed one's (repo, base), in creation order.
    private static final String SIBLINGS = "WITH s AS (UPDATE go SET invalidated_at = now(), reason = 'sibling-consumed' "
                                         + "WHERE repo = $1 AND base = $2 AND id <> $3::uuid AND consumed_at IS NULL AND invalidated_at IS NULL "
                                         + "AND created_at > now() - interval '10 minutes' RETURNING id, pr, sha, base, created_at) "
                                         + "SELECT id::text AS id, pr, sha::text AS sha, base FROM s ORDER BY created_at, id";

    /// Why a consume updated nothing, read in the same transaction.
    private static final String STATE_OF = "SELECT consumed_at IS NOT NULL AS consumed, reason FROM go WHERE id = $1::uuid";

    private static final String GO_ROW = "SELECT repo, pr, sha::text AS sha, base, base_tip::text AS base_tip, fleet_seq, outcome, base_tip_before::text AS base_tip_before "
                                       + "FROM go WHERE id = $1::uuid";

    private static final String SET_OUTCOME = "UPDATE go SET outcome = $2, outcome_detail = $3::text, outcome_at = now(), base_tip_before = coalesce($4::text, base_tip_before) "
                                            + "WHERE id = $1::uuid";

    /// R3-2.i: the `temper-merge` record, unless the PR has a merge recorded already (at any sha, either route).
    private static final String MERGE_GO = "INSERT INTO merges (repo, pr, sha, base, go_id, route) SELECT $1::text, $2::bigint, $3::text, $4::text, $5::uuid, 'temper-merge' "
                                         + "WHERE NOT EXISTS (SELECT 1 FROM merges WHERE repo = $1::text AND pr = $2::bigint) ON CONFLICT DO NOTHING";

    private static final String NEXT = "SELECT pr FROM merge_queue WHERE repo = $1 AND base = $2 AND position = 1";

    private static final String MERGE_SEQ = "UPDATE merges SET event_seq = $4::bigint WHERE repo = $1 AND pr = $2::bigint AND sha = $3";

    /// The allowed outcome transitions, `<from>-><to>`.
    private static final Set<String> TRANSITIONS = Set.of("consumed->refused",
                                                          "consumed->merging",
                                                          "merging->merged",
                                                          "merging->merge-failed");

    @Override
    public Promise<Option<FleetSeqs>> detect(int limit,
                                             Fn2<Promise<DetectorBatch>, DetectorInput, MergeFacts.Reader> plan) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> input(tx, limit))
                                            .flatMap(input -> plan.apply(input,
                                                                         reader(tx)))
                                            .flatMap(batch -> written(tx, batch)));
    }

    private static Promise<Unit> locked(QueryExecutor tx) {
        return Sql.execute(tx, "SELECT pg_advisory_xact_lock($1)", T2_LOCK);
    }

    private static Promise<DetectorInput> input(QueryExecutor tx, int limit) {
        return Sql.query(tx, STATE, DETECTOR)
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .flatMap(row -> inputAt(tx, row, limit));
    }

    private static Promise<DetectorInput> inputAt(QueryExecutor tx, PgRow row, int limit) {
        var cursor = row.getLong("cursor");

        return PgFleetStore.pendingIn(tx, cursor, limit).flatMap(pending -> open(tx).map(gos -> DetectorInput.detectorInput(cursor,
                                                                                                                            option(row.getInstant("started_at")),
                                                                                                                            pending,
                                                                                                                            gos)));
    }

    private static Promise<List<OpenGo>> open(QueryExecutor tx) {
        return Sql.query(tx, OPEN).map(resultSet -> Sql.rows(resultSet)
                                                       .stream()
                                                       .map(row -> OpenGo.openGo(row.getString("id"),
                                                                                 basis(row)))
                                                       .toList());
    }

    private static GoBasis basis(PgRow row) {
        return GoBasis.goBasis(row.getString("repo"),
                               row.getLong("pr"),
                               row.getString("sha"),
                               row.getString("base"),
                               row.getString("base_tip"),
                               row.getLong("fleet_seq"));
    }

    /// The merge facts of one key, read in `tx` (under the T2 lock already taken).
    private static MergeFacts.Reader reader(QueryExecutor tx) {
        return key -> facts(tx, key);
    }

    private static Promise<MergeFacts> facts(QueryExecutor tx, MergeKey key) {
        return Sql.query(tx,
                         FACTS,
                         key.repo(),
                         key.pr(),
                         key.sha())
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .mapWith(_ -> queue(tx, key),
                           PgT2Store::factsOf);
    }

    private static MergeFacts factsOf(PgRow row, List<QueueEntry> queue) {
        return MergeFacts.mergeFacts(option(row.getString("id")).map(id -> MergeFacts.GatingGo.gatingGo(id,
                                                                                                        option(row.getString("base_tip_before")))),
                                     row.getBoolean("recorded"),
                                     queue);
    }

    private static Promise<List<QueueEntry>> queue(QueryExecutor tx, MergeKey key) {
        return Sql.query(tx,
                         QUEUE,
                         key.repo(),
                         key.pr())
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgT2Store::queueEntry)
                                       .toList());
    }

    private static QueueEntry queueEntry(PgRow row) {
        return QueueEntry.queueEntry(row.getString("repo"),
                                     row.getString("base"),
                                     row.getInt("position"),
                                     row.getLong("pr"));
    }

    /// The table rows first, then the cursor, then the events under the order lock (last, see the class comment).
    private static Promise<Option<FleetSeqs>> written(QueryExecutor tx, DetectorBatch batch) {
        return Sql.sequence(batch.invalidated(),
                            invalidation -> invalidated(tx, invalidation))
                  .flatMap(_ -> Sql.sequence(batch.merges(),
                                             merge -> merged(tx, merge)))
                  .flatMap(_ -> Sql.sequence(batch.dequeued(),
                                             entry -> dequeued(tx, entry)))
                  .flatMap(_ -> Sql.execute(tx,
                                            CURSOR,
                                            DETECTOR,
                                            batch.cursor(),
                                            Sql.utc(batch.lastOkAt())))
                  .flatMap(_ -> PgFleetStore.insertAll(tx,
                                                       batch.events()));
    }

    /// The plan read the merge as not recorded under the T2 lock, so a conflict here means a writer skipped the lock:
    /// the batch is refused (rolled back) instead of announcing a merge twice.
    private static Promise<Unit> merged(QueryExecutor tx, MergeRow merge) {
        return Sql.update(tx,
                          MERGE,
                          merge.key().repo(),
                          merge.key().pr(),
                          merge.key().sha(),
                          merge.base(),
                          Sql.nullable(merge.goId()),
                          merge.route(),
                          Sql.nullable(merge.mergedBy()),
                          Sql.nullable(merge.mergedAt().map(Sql::utc)),
                          Sql.nullable(merge.eventSeq()))
                  .flatMap(PgT2Store::insertedOnce);
    }

    /// The plan read the GO as outstanding under the T2 lock (and `now()` is the transaction's), so no row updated means
    /// a writer skipped the lock: the batch is refused (rolled back) instead of announcing an invalidation not made.
    private static Promise<Unit> invalidated(QueryExecutor tx, GoInvalidation invalidation) {
        return Sql.update(tx,
                          INVALIDATE,
                          invalidation.go().id(),
                          invalidation.reason(),
                          invalidation.causeSeq())
                  .flatMap(PgT2Store::invalidatedOnce);
    }

    private static Promise<Unit> invalidatedOnce(int updated) {
        return updated == 1
               ? Promise.unitPromise()
               : StoreError.General.GO_NOT_OUTSTANDING.promise();
    }

    private static Promise<Unit> insertedOnce(int inserted) {
        return inserted == 1
               ? Promise.unitPromise()
               : StoreError.General.MERGE_ALREADY_RECORDED.promise();
    }

    private static Promise<Unit> dequeued(QueryExecutor tx, QueueEntry entry) {
        return Sql.execute(tx, DEQUEUE, entry.repo(), entry.pr());
    }

    // ---- verdicts -------------------------------------------------------------------------------------
    @Override
    public Promise<Committed<RegisteredVerdict>> register(NewVerdict verdict,
                                                          Fn1<List<NewFleetEvent>, RegisteredVerdict> events) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> verdictRow(tx, verdict))
                                            .flatMap(row -> invalidated(tx, verdict, row))
                                            .flatMap(registered -> announced(tx, registered, events)));
    }

    private static Promise<PgRow> verdictRow(QueryExecutor tx, NewVerdict verdict) {
        return switch (verdict.basis()) {
            case NewVerdict.Basis.Report report -> Sql.query(tx,
                                                             REPORTED,
                                                             verdict.repo(),
                                                             verdict.pr(),
                                                             verdict.sha(),
                                                             verdict.base(),
                                                             verdict.verdict(),
                                                             verdict.by(),
                                                             report.path(),
                                                             report.sha256()).flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED));
            case NewVerdict.Basis.Carried carried -> Sql.query(tx,
                                                               CARRIED,
                                                               verdict.repo(),
                                                               verdict.pr(),
                                                               verdict.sha(),
                                                               verdict.base(),
                                                               verdict.by(),
                                                               carried.from()).flatMap(resultSet -> Sql.first(resultSet).async(StoreError.CarryNoAccept.FACTORY.apply(verdict.repo(),
                                                                                                                                                                      verdict.pr(),
                                                                                                                                                                      carried.from())));
        };
    }

    private static Promise<RegisteredVerdict> invalidated(QueryExecutor tx, NewVerdict verdict, PgRow row) {
        return Sql.query(tx,
                         VERDICT_CHANGED,
                         verdict.repo(),
                         verdict.pr())
                  .map(resultSet -> RegisteredVerdict.registeredVerdict(row.getLong("id"),
                                                                        row.getInstant("at"),
                                                                        row.getString("report_path"),
                                                                        row.getString("report_sha256"),
                                                                        invalidatedGos(resultSet)));
    }

    private static List<InvalidatedGo> invalidatedGos(PgResultSet resultSet) {
        return Sql.rows(resultSet)
                  .stream()
                  .map(row -> InvalidatedGo.invalidatedGo(row.getString("id"),
                                                          row.getLong("pr"),
                                                          row.getString("sha"),
                                                          row.getString("base")))
                  .toList();
    }

    /// The events last, under the order lock (see the class comment).
    private static Promise<Committed<RegisteredVerdict>> announced(QueryExecutor tx,
                                                                   RegisteredVerdict registered,
                                                                   Fn1<List<NewFleetEvent>, RegisteredVerdict> events) {
        return PgFleetStore.insertAll(tx,
                                      events.apply(registered))
                           .map(seqs -> Committed.committed(registered, seqs));
    }

    // ---- GO lifecycle -----------------------------------------------------------------------------
    @Override
    public Promise<Option<VerdictRow>> latestVerdict(String repo, long pr, String sha) {
        return Sql.query(db.executor(),
                         LATEST,
                         repo,
                         pr,
                         sha)
                  .map(resultSet -> Sql.first(resultSet).map(PgT2Store::verdictRow));
    }

    private static VerdictRow verdictRow(PgRow row) {
        return VerdictRow.verdictRow(row.getLong("id"),
                                     row.getString("verdict"),
                                     row.getString("declared_by"),
                                     row.getInstant("at"),
                                     row.getString("base"),
                                     row.getString("report_path"),
                                     row.getString("report_sha256"),
                                     option(row.getString("carried_from")));
    }

    @Override
    public Promise<CreatedGo> createGo(NewGo go, Fn1<Option<String>, List<FleetEventRow>> scan) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> currentEpoch(tx, go))
                                            .flatMap(_ -> currentVerdict(tx, go))
                                            .flatMap(_ -> unmatched(tx, go, scan))
                                            .flatMap(_ -> created(tx, go)));
    }

    private static Promise<Unit> currentEpoch(QueryExecutor tx, NewGo go) {
        return Sql.query(tx, EPOCH)
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .flatMap(row -> sameEpoch(row.getString("epoch"),
                                            go));
    }

    private static Promise<Unit> sameEpoch(String epoch, NewGo go) {
        return epoch.equals(go.fleetEpoch())
               ? Promise.unitPromise()
               : StoreError.EpochChanged.FACTORY.apply(go.fleetEpoch()).promise();
    }

    /// The latest row for the head must still be the one the gate read.
    private static Promise<Unit> currentVerdict(QueryExecutor tx, NewGo go) {
        return Sql.query(tx,
                         LATEST,
                         go.basis().repo(),
                         go.basis().pr(),
                         go.basis().sha())
                  .flatMap(resultSet -> Sql.first(resultSet)
                                           .filter(row -> row.getLong("id") == go.verdictId())
                                           .async(StoreError.CreationInvalidated.FACTORY.apply("verdict-changed")))
                  .mapToUnit();
    }

    /// The rows committed after the gate's head through now; the first the rules match refuses.
    private static Promise<Unit> unmatched(QueryExecutor tx, NewGo go, Fn1<Option<String>, List<FleetEventRow>> scan) {
        return Sql.query(tx,
                         AFTER,
                         go.basis().fleetSeq())
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgFleetStore::eventRow)
                                       .toList())
                  .flatMap(rows -> scan.apply(rows)
                                       .map(reason -> StoreError.CreationInvalidated.FACTORY.apply(reason).<Unit> promise())
                                       .or(Promise::unitPromise));
    }

    private static Promise<CreatedGo> created(QueryExecutor tx, NewGo go) {
        return Sql.query(tx,
                         CREATE,
                         go.basis().repo(),
                         go.basis().pr(),
                         go.basis().sha(),
                         go.basis().base(),
                         go.basis().baseTip(),
                         go.verdictId(),
                         go.basis().fleetSeq())
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> CreatedGo.createdGo(row.getString("id"),
                                                  row.getInstant("created_at")));
    }

    @Override
    public Promise<Committed<ConsumedGo>> consume(String id, Fn1<List<NewFleetEvent>, ConsumedGo> events) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> Sql.query(tx, CONSUME, id))
                                            .flatMap(resultSet -> consumedOrRefused(tx,
                                                                                    id,
                                                                                    Sql.first(resultSet)))
                                            .flatMap(consumed -> PgFleetStore.insertAll(tx,
                                                                                        events.apply(consumed))
                                                                             .map(seqs -> Committed.committed(consumed,
                                                                                                              seqs))));
    }

    private static Promise<ConsumedGo> consumedOrRefused(QueryExecutor tx, String id, Option<PgRow> row) {
        return row.map(consumed -> siblings(tx, consumed))
                  .or(() -> refused(tx, id));
    }

    /// One row updated: the siblings of its (repo, base) are invalidated in the same transaction.
    private static Promise<ConsumedGo> siblings(QueryExecutor tx, PgRow row) {
        return Sql.query(tx,
                         SIBLINGS,
                         row.getString("repo"),
                         row.getString("base"),
                         row.getString("id"))
                  .map(resultSet -> ConsumedGo.consumedGo(row.getString("id"),
                                                          basis(row),
                                                          row.getLong("verdict_id"),
                                                          row.getInstant("consumed_at"),
                                                          invalidatedGos(resultSet),
                                                          row.getString("report_path"),
                                                          row.getString("report_sha256")));
    }

    /// Zero rows updated: no row → not found; consumed → used; invalidated → its reason; else expired.
    private static Promise<ConsumedGo> refused(QueryExecutor tx, String id) {
        return Sql.query(tx, STATE_OF, id)
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.GoNotFound.FACTORY.apply(id)))
                  .flatMap(row -> refusal(id, row).<ConsumedGo> promise());
    }

    private static StoreError refusal(String id, PgRow row) {
        return row.getBoolean("consumed")
               ? StoreError.GoUsed.FACTORY.apply(id)
               : option(row.getString("reason")).<StoreError> map(reason -> StoreError.GoInvalidated.FACTORY.apply(id,
                                                                                                                   reason))
                       .or(() -> StoreError.GoExpired.FACTORY.apply(id));
    }

    @Override
    public Promise<Committed<RecordedOutcome>> outcome(GoOutcome outcome,
                                                       Fn1<List<NewFleetEvent>, RecordedOutcome> events) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> Sql.query(tx,
                                                                        GO_ROW,
                                                                        outcome.id()))
                                            .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.GoNotFound.FACTORY.apply(outcome.id())))
                                            .flatMap(row -> transition(tx, outcome, row))
                                            .flatMap(recorded -> announcedOutcome(tx, recorded, events)));
    }

    private static Promise<RecordedOutcome> transition(QueryExecutor tx, GoOutcome outcome, PgRow row) {
        var from = option(row.getString("outcome")).or("none");

        return TRANSITIONS.contains(from + "->" + outcome.outcome())
               ? Sql.execute(tx,
                             SET_OUTCOME,
                             outcome.id(),
                             outcome.outcome(),
                             Sql.nullable(outcome.detail()),
                             Sql.nullable(outcome.baseTipBefore()))
                    .flatMap(_ -> mergedBy(tx, outcome, row))
               : StoreError.BadTransition.FACTORY.apply(from,
                                                        outcome.outcome())
                                                 .promise();
    }

    /// `merged`: the merge record (R3-2.i), and when this transaction made it, the PR's queue removal.
    private static Promise<RecordedOutcome> mergedBy(QueryExecutor tx, GoOutcome outcome, PgRow row) {
        return outcome.outcome()
                      .equals("merged")
               ? Sql.update(tx,
                            MERGE_GO,
                            row.getString("repo"),
                            row.getLong("pr"),
                            row.getString("sha"),
                            row.getString("base"),
                            outcome.id())
                    .flatMap(inserted -> recordedMerge(tx, outcome, row, inserted))
               : Promise.success(withoutMerge(outcome));
    }

    /// The merge was recorded already (by the kiln route, or a replay of this outcome): nothing more (R3-2.i).
    private static Promise<RecordedOutcome> recordedMerge(QueryExecutor tx,
                                                          GoOutcome outcome,
                                                          PgRow row,
                                                          int inserted) {
        return inserted == 1
               ? leftQueue(tx, outcome, row)
               : Promise.success(withoutMerge(outcome));
    }

    private static RecordedOutcome withoutMerge(GoOutcome outcome) {
        return RecordedOutcome.recordedOutcome(outcome.id(), outcome.outcome(), none());
    }

    private static Promise<RecordedOutcome> leftQueue(QueryExecutor tx, GoOutcome outcome, PgRow row) {
        var basis = basis(row);

        return Sql.query(tx,
                         DEQUEUE_AT,
                         basis.repo(),
                         basis.pr(),
                         Sql.nullable(none()))
                  .map(resultSet -> Sql.first(resultSet).map(gone -> QueueEntry.queueEntry(basis.repo(),
                                                                                           gone.getString("base"),
                                                                                           gone.getInt("position"),
                                                                                           basis.pr())))
                  .flatMap(dequeued -> nextIn(tx, dequeued))
                  .map(queue -> RecordedOutcome.recordedOutcome(outcome.id(),
                                                                outcome.outcome(),
                                                                some(RecordedOutcome.Merge.merge(basis,
                                                                                                 option(row.getString("base_tip_before")),
                                                                                                 queue.dequeued(),
                                                                                                 queue.next()))));
    }

    /// The PR's queue entry as it was and the head of that queue after the removal.
    private record QueueLeft(Option<QueueEntry> dequeued, Option<QueueEntry> next) {}

    private static Promise<QueueLeft> nextIn(QueryExecutor tx, Option<QueueEntry> dequeued) {
        return dequeued.map(gone -> Sql.query(tx,
                                              NEXT,
                                              gone.repo(),
                                              gone.base())
                                       .map(resultSet -> new QueueLeft(dequeued,
                                                                       Sql.first(resultSet).map(head -> QueueEntry.queueEntry(gone.repo(),
                                                                                                                              gone.base(),
                                                                                                                              1,
                                                                                                                              head.getLong("pr"))))))
                       .or(() -> Promise.success(new QueueLeft(none(),
                                                               none())));
    }

    /// The events last (order lock); a `merged` record's `event_seq` is the first of them, the `merged` event.
    private static Promise<Committed<RecordedOutcome>> announcedOutcome(QueryExecutor tx,
                                                                        RecordedOutcome recorded,
                                                                        Fn1<List<NewFleetEvent>, RecordedOutcome> events) {
        return PgFleetStore.insertAll(tx,
                                      events.apply(recorded))
                           .flatMap(seqs -> sequenced(tx, recorded, seqs))
                           .map(seqs -> Committed.committed(recorded, seqs));
    }

    private static Promise<Option<FleetSeqs>> sequenced(QueryExecutor tx,
                                                        RecordedOutcome recorded,
                                                        Option<FleetSeqs> seqs) {
        return recorded.merge()
                       .flatMap(merge -> seqs.map(inserted -> Sql.execute(tx,
                                                                          MERGE_SEQ,
                                                                          merge.basis().repo(),
                                                                          merge.basis().pr(),
                                                                          merge.basis().sha(),
                                                                          inserted.first())))
                       .map(update -> update.map(_ -> seqs))
                       .or(() -> Promise.success(seqs));
    }

    // ---- merge queue ----------------------------------------------------------------------------------
    @Override
    public Promise<List<QueuedPr>> queues(Option<String> repo, Option<String> base) {
        return Sql.query(db.executor(),
                         QUEUES,
                         Sql.nullable(repo),
                         Sql.nullable(base))
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgT2Store::queuedPr)
                                       .toList());
    }

    private static QueuedPr queuedPr(PgRow row) {
        return QueuedPr.queuedPr(queueEntry(row),
                                 row.getString("added_by"),
                                 row.getInstant("added_at"),
                                 option(row.getString("go_id")).map(id -> QueuedPr.OpenGo.openGo(id,
                                                                                                 row.getString("go_sha"),
                                                                                                 row.getInstant("go_expires_at"))));
    }

    @Override
    public Promise<QueueEntry> enqueue(String repo, String base, long pr, String addedBy) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> queuedAt(tx,
                                                                       repo,
                                                                       pr,
                                                                       none()))
                                            .flatMap(PgT2Store::notQueued)
                                            .flatMap(_ -> appended(tx, repo, base, pr, addedBy)));
    }

    private static Promise<Option<PgRow>> queuedAt(QueryExecutor tx, String repo, long pr, Option<String> base) {
        return Sql.query(tx,
                         QUEUED_AT,
                         repo,
                         pr,
                         Sql.nullable(base))
                  .map(Sql::first);
    }

    private static Promise<Unit> notQueued(Option<PgRow> row) {
        return row.map(found -> StoreError.AlreadyQueued.FACTORY.apply(found.getString("base"),
                                                                       found.getInt("position"))
                                                                .<Unit> promise())
                  .or(Promise::unitPromise);
    }

    private static Promise<QueueEntry> appended(QueryExecutor tx, String repo, String base, long pr, String addedBy) {
        return Sql.query(tx, ENQUEUE, repo, base, pr, addedBy)
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> QueueEntry.queueEntry(repo,
                                                    base,
                                                    row.getInt("position"),
                                                    pr));
    }

    @Override
    public Promise<QueueEntry> dequeue(String repo, long pr, Option<String> base) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> Sql.query(tx,
                                                                        DEQUEUE_AT,
                                                                        repo,
                                                                        pr,
                                                                        Sql.nullable(base)))
                                            .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.NotQueued.FACTORY.apply(repo,
                                                                                                                                pr)))
                                            .map(row -> QueueEntry.queueEntry(repo,
                                                                              row.getString("base"),
                                                                              row.getInt("position"),
                                                                              pr)));
    }

    @Override
    public Promise<QueueEntry> move(String repo, long pr, int to, Option<String> base) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> queuedAt(tx, repo, pr, base))
                                            .flatMap(row -> row.async(StoreError.NotQueued.FACTORY.apply(repo, pr)))
                                            .flatMap(row -> moved(tx, repo, pr, to, row)));
    }

    private static Promise<QueueEntry> moved(QueryExecutor tx, String repo, long pr, int to, PgRow row) {
        var size = Math.toIntExact(row.getLong("size"));
        var queueBase = row.getString("base");

        return to >= 1 && to <= size
               ? Sql.execute(tx,
                             MOVE,
                             repo,
                             queueBase,
                             pr,
                             row.getInt("position"),
                             to)
                    .map(_ -> QueueEntry.queueEntry(repo, queueBase, to, pr))
               : StoreError.PositionOutOfRange.FACTORY.apply(to, size).promise();
    }

    // ---- control paths --------------------------------------------------------------------------------
    @Override
    public Promise<List<ControlPath>> controlPaths(String repo) {
        return Sql.query(db.executor(),
                         PATHS,
                         repo)
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgT2Store::controlPath)
                                       .toList());
    }

    private static ControlPath controlPath(PgRow row) {
        return ControlPath.controlPath(row.getString("glob"), row.getString("added_by"), row.getInstant("added_at"));
    }

    @Override
    public Promise<ControlPath> addControlPath(String repo, String glob, String addedBy) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> Sql.query(tx, ADD_PATH, repo, glob, addedBy))
                                            .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.PathExists.FACTORY.apply(repo,
                                                                                                                                 glob)))
                                            .map(PgT2Store::controlPath));
    }

    @Override
    public Promise<Unit> removeControlPath(String repo, String glob) {
        return db.inTransaction(tx -> locked(tx).flatMap(_ -> Sql.update(tx, REMOVE_PATH, repo, glob))
                                            .flatMap(removed -> removed == 1
                                                                ? Promise.unitPromise()
                                                                : StoreError.PathNotFound.FACTORY.apply(repo, glob).<Unit> promise()));
    }
}
