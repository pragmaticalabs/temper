package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.retention.EvidenceItem;
import org.pragmatica.temper.domain.retention.JobRetention;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.RetentionStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.option;


/// [RetentionStore] over Postgres (m1-design §4.2).
record PgRetentionStore(Db db) implements RetentionStore {
    /// Testcases deleted per transaction, by `ctid` (§4.2).
    static final int BATCH = 10_000;

    /// The rows of `$1` may be deleted only while the job is finished and not pinned: the store's own guard, so a
    /// caller that skips the plan still cannot delete what retention must keep.
    private static final String COLLECTABLE = "EXISTS (SELECT 1 FROM jobs j WHERE j.id = $1 AND j.finished_at IS NOT NULL AND NOT j.keep)";

    private static final String JOB_UNITS = "(SELECT id FROM units WHERE job_id = $1)";

    private static final String TESTCASE_BATCH = "DELETE FROM testcases WHERE ctid IN (SELECT ctid FROM testcases WHERE job_id = $1 "
                                               + "AND " + COLLECTABLE
                                               + " LIMIT " + BATCH
                                               + ")";

    /// `manifest_units` (the per-unit status, `notReached`, unresolved annotations, `entry_count`) is not in ruling
    /// M1-11's list and stays, so a job's manifest still reads as computed after its entries went, and as `pruned`
    /// (ruling R-7) because fewer rows remain than `entry_count`.
    private static final List<String> OTHER_ROWS = List.of("DELETE FROM manifests WHERE job_id = $1 AND " + COLLECTABLE,
                                                           "DELETE FROM unit_facts WHERE unit_id IN " + JOB_UNITS
                                                          + " AND " + COLLECTABLE);

    /// Hosts job `j.id` was granted on (`checkouts`) whose checkout is not recorded gone.
    private static final String CHECKOUTS_OF_JOB = "SELECT 1 FROM checkouts k WHERE k.job_id = j.id "
                                                 + "AND NOT EXISTS (SELECT 1 FROM checkouts_gone g WHERE g.job_id = k.job_id AND g.host = k.host)";

    /// Finished, unpinned jobs with something left to delete, by id after the cursor `$2`. `has_rows` covers every row
    /// class [#deleteJobRows] deletes.
    private static final String CANDIDATES = "WITH c AS (SELECT j.id, j.keep, j.state, j.finished_at, "
                                           + "(EXISTS (SELECT 1 FROM testcases t WHERE t.job_id = j.id) "
                                           + "OR EXISTS (SELECT 1 FROM manifests m WHERE m.job_id = j.id) "
                                           + "OR EXISTS (SELECT 1 FROM units u JOIN unit_facts f ON f.unit_id = u.id WHERE u.job_id = j.id)) AS has_rows, "
                                           + "EXISTS (SELECT 1 FROM evidence e WHERE e.job_id = j.id AND e.deleted_at IS NULL) AS has_evidence, "
                                           + "EXISTS (" + CHECKOUTS_OF_JOB
                                           + ") AS has_checkout "
                                           + "FROM jobs j WHERE j.finished_at IS NOT NULL AND j.finished_at < $1 AND NOT j.keep AND j.id > $2) "
                                           + "SELECT id, keep, state, finished_at, has_rows FROM c "
                                           + "WHERE has_rows OR has_evidence OR has_checkout ORDER BY id LIMIT $3";

    private static final String ITEMS = "SELECT e.id, e.job_id, u.name, e.kind, e.stored_at FROM evidence e JOIN units u ON u.id = e.unit_id "
                                      + "WHERE e.job_id = ANY($1::bigint[]) AND e.deleted_at IS NULL ORDER BY e.id";

    private static final String HOSTS = "SELECT k.job_id, k.host FROM checkouts k WHERE k.job_id = ANY($1::bigint[]) "
                                      + "AND NOT EXISTS (SELECT 1 FROM checkouts_gone g "
                                      + "WHERE g.job_id = k.job_id AND g.host = k.host) ORDER BY k.job_id, k.host";

    /// One step of event deletion: the lowest [#BATCH] events up to `$1`, with their count and highest `seq`.
    private static final String DELETE_EVENT_STEP = "WITH d AS (DELETE FROM events WHERE seq IN (SELECT seq FROM events WHERE seq <= $1 "
                                                  + "ORDER BY seq LIMIT " + BATCH
                                                  + ") RETURNING seq) "
                                                  + "SELECT count(*) AS n, coalesce(max(seq), 0) AS top FROM d";

    /// Under the event order lock nothing is between `seq` allocation and commit, so every `seq <= upTo` is final.
    /// The floor never passes the last allocated `seq`: a later event must stay above it, or a reader would skip it.
    private static final String RAISE_FLOOR = "UPDATE event_floor SET floor_seq = GREATEST(floor_seq, "
                                            + "LEAST($1, coalesce(pg_sequence_last_value(pg_get_serial_sequence('events', 'seq')::regclass), 0))) WHERE id = 1";

    @Override
    public Promise<List<JobRetention>> jobs(Instant finishedBefore, Option<JobId> after, int limit) {
        return db.inTransaction(tx -> jobsIn(tx, finishedBefore, after, limit));
    }

    @Override
    public Promise<Option<Long>> eventHorizon(Instant before) {
        return Sql.query(db.executor(),
                         "SELECT max(seq) AS horizon FROM events WHERE at < $1",
                         Sql.utc(before))
                  .map(PgRetentionStore::horizon);
    }

    @Override
    public Promise<Long> deleteJobRows(JobId job) {
        return deleteTestcases(job, 0L).flatMap(testcases -> deleteOthers(job, testcases));
    }

    @Override
    public Promise<Long> deleteEventsUpTo(long upTo) {
        return deleteEventStep(upTo, 0L);
    }

    @Override
    public Promise<Unit> checkoutGone(HostName host, JobId job) {
        return Sql.execute(db.executor(),
                           "INSERT INTO checkouts_gone (job_id, host) VALUES ($1, $2) ON CONFLICT (job_id, host) DO NOTHING",
                           job.value(),
                           host.value());
    }

    // ---- candidates ------------------------------------------------------------------------------------------
    private record Candidates(List<PgRow> jobs, Map<Long, List<EvidenceItem>> items) {}

    /// One read snapshot: the candidates, their evidence and their hosts agree with each other.
    private static Promise<List<JobRetention>> jobsIn(Transaction tx,
                                                      Instant finishedBefore,
                                                      Option<JobId> after,
                                                      int limit) {
        return Sql.snapshot(tx)
                  .flatMap(_ -> Sql.query(tx,
                                          CANDIDATES,
                                          Sql.utc(finishedBefore),
                                          after.map(JobId::value).or(0L),
                                          limit))
                  .map(Sql::rows)
                  .flatMap(rows -> withItems(tx, rows))
                  .flatMap(candidates -> withHosts(tx, candidates));
    }

    private static Promise<Candidates> withItems(QueryExecutor tx, List<PgRow> jobs) {
        return Sql.query(tx,
                         ITEMS,
                         (Object) ids(jobs))
                  .flatMap(resultSet -> items(resultSet).async())
                  .map(items -> new Candidates(jobs,
                                               grouped(items)));
    }

    private static Promise<List<JobRetention>> withHosts(QueryExecutor tx, Candidates candidates) {
        return Sql.query(tx,
                         HOSTS,
                         (Object) ids(candidates.jobs()))
                  .map(resultSet -> hostsByJob(Sql.rows(resultSet)))
                  .flatMap(hosts -> assemble(candidates, hosts).async());
    }

    private static Option<Long> horizon(PgResultSet resultSet) {
        return Sql.first(resultSet).flatMap(row -> option(row.getLong("horizon")));
    }

    private static Result<List<Map.Entry<Long, EvidenceItem>>> items(PgResultSet resultSet) {
        return Result.allOf(Sql.rows(resultSet).stream().map(PgRetentionStore::item).toList());
    }

    private static Long[] ids(List<PgRow> jobs) {
        return jobs.stream()
                   .map(row -> row.getLong("id"))
                   .toArray(Long[]::new);
    }

    private static Result<Map.Entry<Long, EvidenceItem>> item(PgRow row) {
        return Result.all(UnitName.unitName(row.getString("name")),
                          EvidenceKind.evidenceKind(row.getString("kind")))
                     .map((unit, kind) -> Map.entry(row.getLong("job_id"),
                                                    EvidenceItem.evidenceItem(row.getLong("id"),
                                                                              unit,
                                                                              kind,
                                                                              row.getInstant("stored_at"))))
                     .mapError(Rows.corrupt("evidence"));
    }

    private static Map<Long, List<EvidenceItem>> grouped(List<Map.Entry<Long, EvidenceItem>> items) {
        return items.stream()
                    .collect(Collectors.groupingBy(Map.Entry::getKey,
                                                   Collectors.mapping(Map.Entry::getValue,
                                                                      Collectors.toList())));
    }

    private static Map<Long, List<String>> hostsByJob(List<PgRow> rows) {
        return rows.stream()
                   .collect(Collectors.groupingBy(row -> row.getLong("job_id"),
                                                  Collectors.mapping(row -> row.getString("host"),
                                                                     Collectors.toList())));
    }

    private static Result<List<JobRetention>> assemble(Candidates candidates, Map<Long, List<String>> hosts) {
        return Result.allOf(candidates.jobs().stream().map(row -> jobRetention(row, candidates.items(), hosts)).toList());
    }

    private static Result<JobRetention> jobRetention(PgRow row,
                                                     Map<Long, List<EvidenceItem>> items,
                                                     Map<Long, List<String>> hosts) {
        var id = row.getLong("id");

        return Result.all(Rows.jobId(row, "id"),
                          Rows.enumValue(JobState.class,
                                         row.getString("state")))
                     .map((job, state) -> JobRetention.jobRetention(job,
                                                                    row.getBoolean("keep"),
                                                                    state.isTerminal(),
                                                                    option(row.getInstant("finished_at")),
                                                                    items.getOrDefault(id,
                                                                                       List.of()),
                                                                    hosts.getOrDefault(id,
                                                                                       List.of()),
                                                                    row.getBoolean("has_rows")))
                     .mapError(Rows.corrupt("jobs"));
    }

    // ---- job rows --------------------------------------------------------------------------------------------
    /// Batches until one deletes fewer than [#BATCH] rows; each batch commits on its own, so a long delete never
    /// holds one huge transaction, and a failure keeps what earlier batches deleted (retention re-plans next tick).
    private Promise<Long> deleteTestcases(JobId job, long deleted) {
        return db.inTransaction(tx -> testcaseBatch(tx, job))
                 .flatMap(count -> afterBatch(job, deleted + count, count));
    }

    private Promise<Long> afterBatch(JobId job, long deleted, long count) {
        return count < BATCH
               ? Promise.success(deleted)
               : deleteTestcases(job, deleted);
    }

    private static Promise<Long> testcaseBatch(Transaction tx, JobId job) {
        return retentionOn(tx).flatMap(_ -> Sql.update(tx,
                                                       TESTCASE_BATCH,
                                                       job.value()))
                          .map(Integer::longValue);
    }

    private Promise<Long> deleteOthers(JobId job, long testcases) {
        return db.inTransaction(tx -> othersIn(tx, job))
                 .map(others -> testcases + others);
    }

    private static Promise<Long> othersIn(Transaction tx, JobId job) {
        return retentionOn(tx).flatMap(_ -> Sql.sequence(OTHER_ROWS,
                                                         sql -> Sql.update(tx,
                                                                           sql,
                                                                           job.value())))
                          .map(PgRetentionStore::total);
    }

    private static long total(List<Integer> counts) {
        return counts.stream()
                     .mapToLong(Integer::longValue)
                     .sum();
    }

    /// The only door of the write-once trigger (m0-design §5.2), for this transaction only.
    private static Promise<Unit> retentionOn(Transaction tx) {
        return Sql.execute(tx, "SET LOCAL temper.retention = 'on'");
    }

    // ---- events ----------------------------------------------------------------------------------------------
    /// One step's deletions: how many, and the highest `seq` among them.
    private record EventStep(long count, long top) {}

    /// Steps of at most [#BATCH] events, each its own transaction, so the order lock (which every event writer waits
    /// for) is held for one bounded step at a time. Each step raises the floor to what it deleted (the last step to
    /// `upTo`) in the same transaction: the floor never passes an event that is still stored.
    private Promise<Long> deleteEventStep(long upTo, long deleted) {
        return db.inTransaction(tx -> eventStepIn(tx, upTo))
                 .flatMap(step -> afterEventStep(upTo,
                                                 deleted + step.count(),
                                                 step));
    }

    private Promise<Long> afterEventStep(long upTo, long deleted, EventStep step) {
        return step.count() < BATCH
               ? Promise.success(deleted)
               : deleteEventStep(upTo, deleted);
    }

    /// The order lock first: with nothing between `seq` allocation and commit, every `seq` up to the floor is settled
    /// (committed or rolled back), so no event can commit below the floor after it moves. Without it an event in
    /// flight could commit beneath the floor, still stored but only ever announced as part of a gap.
    private static Promise<EventStep> eventStepIn(Transaction tx, long upTo) {
        return Sql.execute(tx,
                           "SELECT pg_advisory_xact_lock($1)",
                           EventWriter.ORDER_LOCK)
                  .flatMap(_ -> Sql.query(tx, DELETE_EVENT_STEP, upTo))
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> new EventStep(row.getLong("n"),
                                            row.getLong("top")))
                  .flatMap(step -> raiseFloor(tx, upTo, step));
    }

    private static Promise<EventStep> raiseFloor(Transaction tx, long upTo, EventStep step) {
        return Sql.execute(tx, RAISE_FLOOR, floorOf(upTo, step)).map(_ -> step);
    }

    /// A full step raises the floor to its highest deleted `seq`; the last (short) step to `upTo`.
    private static long floorOf(long upTo, EventStep step) {
        return step.count() < BATCH
               ? upTo
               : step.top();
    }
}
