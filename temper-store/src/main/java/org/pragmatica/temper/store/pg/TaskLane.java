package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.task.Lease;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.domain.task.TaskId;
import org.pragmatica.temper.store.api.Checkpoint;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.TaskReport;

import static org.pragmatica.lang.Option.option;


/// The lane registry's SQL (V20): `task_checkpoint` and `task_report`. Every statement runs on the executor it is given,
/// so a caller's transaction (the claim, the heartbeat) holds them together with the task row's own change.
sealed interface TaskLane {
    TypeToken<List<Checkpoint.Item>> ITEMS = new TypeToken<>() {};

    TypeToken<List<Checkpoint.Entry>> ENTRIES = new TypeToken<>() {};

    String CHECKPOINT_COLUMNS = "seq, lease_epoch, pushed_sha, checklist::text AS checklist, read_map::text AS read_map, note, at";

    String REPORT_COLUMNS = "task_id, last_hb_at, report_epoch, report_at, report_head, report_dirty, report_unpushed, "
                          + "observed_ctx_k, observed_turns, observed_at";

    /// The fence, taking the task row `FOR UPDATE`: it serialises two checkpoints of one task and the expiry sweep. A separate
    /// statement from the insert on purpose: under READ COMMITTED a statement's snapshot is taken when it starts, so a
    /// single `WITH held ... INSERT ... max(seq)` that waited for the lock would number from a stale `max(seq)`.
    String FENCE = "SELECT id FROM tasks WHERE id = $1 AND status = 'LEASED' AND lease_epoch = $2 AND lease_hash = $3 FOR UPDATE";

    String INSERT_CHECKPOINT = "INSERT INTO task_checkpoint (task_id, seq, lease_epoch, pushed_sha, checklist, read_map, note, at) "
                             + "SELECT $1::bigint, coalesce(max(seq), 0) + 1, $2::bigint, $3::text, $4::jsonb, $5::jsonb, $6::text, $7::timestamptz "
                             + "FROM task_checkpoint WHERE task_id = $1 RETURNING " + CHECKPOINT_COLUMNS;

    String TOUCH = "INSERT INTO task_report (task_id, last_hb_at) VALUES ($1, $2::timestamptz) "
                 + "ON CONFLICT (task_id) DO UPDATE SET last_hb_at = EXCLUDED.last_hb_at";

    String TOUCH_WITH_TREE = "INSERT INTO task_report (task_id, last_hb_at, report_epoch, report_at, report_head, report_dirty, "
                           + "report_unpushed) VALUES ($1, $2::timestamptz, $3::bigint, $2::timestamptz, $4::text, $5::int, $6::int) "
                           + "ON CONFLICT (task_id) DO UPDATE SET last_hb_at = EXCLUDED.last_hb_at, "
                           + "report_epoch = EXCLUDED.report_epoch, report_at = EXCLUDED.report_at, "
                           + "report_head = EXCLUDED.report_head, report_dirty = EXCLUDED.report_dirty, "
                           + "report_unpushed = EXCLUDED.report_unpushed";

    /// Only the observed columns; a task that does not exist inserts nothing (zero rows, reported as not found).
    String OBSERVE = "INSERT INTO task_report (task_id, observed_ctx_k, observed_turns, observed_at) "
                   + "SELECT id, $2::int, $3::int, $4::timestamptz FROM tasks WHERE id = $1 "
                   + "ON CONFLICT (task_id) DO UPDATE SET observed_ctx_k = EXCLUDED.observed_ctx_k, "
                   + "observed_turns = EXCLUDED.observed_turns, observed_at = EXCLUDED.observed_at";

    /// Must run in a transaction: the fence's row lock is held until it ends, so the numbering below it is exclusive.
    static Promise<Checkpoint> checkpoint(QueryExecutor tx, Lease lease, Checkpoint.New body, Instant now) {
        return Result.all(Json.write(body.checklist()),
                          Json.write(body.readMap()))
                     .async()
                     .flatMap((checklist, readMap) -> fenced(tx, lease).flatMap(_ -> inserted(tx,
                                                                                              lease,
                                                                                              body,
                                                                                              checklist,
                                                                                              readMap,
                                                                                              now)));
    }

    private static Promise<Unit> fenced(QueryExecutor tx, Lease lease) {
        return Sql.query(tx,
                         FENCE,
                         lease.task().value(),
                         lease.epoch(),
                         lease.tokenHash())
                  .flatMap(resultSet -> Sql.first(resultSet).async(TaskError.General.LEASE_LOST))
                  .map(_ -> Unit.unit());
    }

    private static Promise<Checkpoint> inserted(QueryExecutor tx,
                                                Lease lease,
                                                Checkpoint.New body,
                                                String checklist,
                                                String readMap,
                                                Instant now) {
        return Sql.query(tx,
                         INSERT_CHECKPOINT,
                         lease.task().value(),
                         lease.epoch(),
                         body.pushedSha(),
                         checklist,
                         readMap,
                         body.note(),
                         Sql.utc(now))
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, TaskLane::checkpointRow))
                  .flatMap(stored -> stored.async(StoreError.General.NO_ROW_RETURNED));
    }

    static Promise<Option<Checkpoint>> latest(QueryExecutor executor, TaskId id) {
        return Sql.query(executor,
                         "SELECT " + CHECKPOINT_COLUMNS
                        + " FROM task_checkpoint WHERE task_id = $1 ORDER BY seq DESC LIMIT 1",
                         id.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, TaskLane::checkpointRow));
    }

    static Result<Checkpoint> checkpointRow(PgRow row) {
        return Result.all(Json.read(row.getString("checklist"),
                                    ITEMS),
                          Json.read(row.getString("read_map"),
                                    ENTRIES))
                     .map((checklist, readMap) -> new Checkpoint(row.getInt("seq"),
                                                                 row.getLong("lease_epoch"),
                                                                 row.getString("pushed_sha"),
                                                                 checklist,
                                                                 readMap,
                                                                 row.getString("note"),
                                                                 row.getInstant("at")))
                     .mapError(Rows.corrupt("task_checkpoint"));
    }

    /// A heartbeat that the lease fence accepted: its time, and the tree when the agent sent one.
    static Promise<Unit> heartbeat(QueryExecutor tx, Lease lease, Option<TaskReport.Tree> tree, Instant now) {
        var at = Sql.utc(now);

        return tree.map(report -> Sql.execute(tx,
                                              TOUCH_WITH_TREE,
                                              lease.task().value(),
                                              at,
                                              lease.epoch(),
                                              report.head(),
                                              report.dirty(),
                                              report.unpushed()))
                   .or(() -> Sql.execute(tx,
                                         TOUCH,
                                         lease.task().value(),
                                         at));
    }

    /// A claim restarts the heartbeat clock; the predecessor's tree report stays as it was (it is read first).
    static Promise<TaskReport> claimed(QueryExecutor tx, TaskId id, Instant now) {
        return reports(tx,
                       List.of(id)).map(found -> found.getOrDefault(id, TaskReport.NONE))
                      .flatMap(before -> Sql.execute(tx,
                                                     TOUCH,
                                                     id.value(),
                                                     Sql.utc(now))
                                            .map(_ -> before));
    }

    static Promise<Unit> observe(QueryExecutor executor, TaskId id, int ctxK, int turns, Instant now) {
        return Sql.update(executor,
                          OBSERVE,
                          id.value(),
                          ctxK,
                          turns,
                          Sql.utc(now))
                  .flatMap(updated -> updated == 0
                                      ? StoreError.TaskNotFound.FACTORY.apply(id).<Unit> promise()
                                      : Promise.unitPromise());
    }

    static Promise<Map<TaskId, TaskReport>> reports(QueryExecutor executor, List<TaskId> ids) {
        return Sql.query(executor,
                         "SELECT " + REPORT_COLUMNS + " FROM task_report WHERE task_id = ANY($1::text[]::bigint[])",
                         (Object) Sql.texts(ids.stream().map(TaskId::toString)))
                  .flatMap(resultSet -> Sql.parseAll(resultSet, TaskLane::reportRow))
                  .map(TaskLane::byTask);
    }

    private static Map<TaskId, TaskReport> byTask(List<Map.Entry<TaskId, TaskReport>> rows) {
        var result = new HashMap<TaskId, TaskReport>();

        rows.forEach(entry -> result.put(entry.getKey(), entry.getValue()));

        return result;
    }

    private static Result<Map.Entry<TaskId, TaskReport>> reportRow(PgRow row) {
        return TaskId.taskId(row.getLong("task_id"))
                     .map(id -> Map.entry(id,
                                          report(row)))
                     .mapError(Rows.corrupt("task_report"));
    }

    private static TaskReport report(PgRow row) {
        return new TaskReport(option(row.getInstant("last_hb_at")),
                              option(row.getLong("report_epoch")).map(epoch -> new TaskReport.Reported(row.getString("report_head"),
                                                                                                       row.getInt("report_dirty"),
                                                                                                       row.getInt("report_unpushed"),
                                                                                                       epoch,
                                                                                                       row.getInstant("report_at"))),
                              option(row.getInt("observed_ctx_k")).map(ctx -> new TaskReport.Observed(ctx,
                                                                                                      row.getInt("observed_turns"),
                                                                                                      row.getInstant("observed_at"))));
    }

    record unused() implements TaskLane {}
}
