package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.store.api.BaseRead;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.JobManifest;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.ResultStore;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredSide;
import org.pragmatica.temper.store.api.StoredSides;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;


/// [ResultStore] over Postgres.
record PgResultStore(Db db) implements ResultStore {
    /// Per job, the class's and its nested classes' summed time; the max over the last `$3` jobs (highest job id, that
    /// is the latest submitted) that are not reruns (maintainer ruling (b): a fast rerun never pushes a run of the class in its
    /// suite out of the window) and have a result, among those that stored rows of the class. No index serves `class`:
    /// each class's read scans `testcases` [G], one after another, which a rerun request (by hand, rare, a few failed
    /// classes) affords. Rows a shared-CPU host produced do not count (R-M4-15, m4-design §9.4): the filter is the unit's
    /// per-attempt snapshot `units.shared_cpu`, whose current attempt is the one that wrote its `testcases` rows; `IS NOT
    /// TRUE` keeps pre-M4 rows (backfilled false). A class with only shared-CPU history has none, and the window counts
    /// only jobs with qualifying rows, so cloud runs cannot push dedicated runs out of it.
    static final String CLASS_DURATION = "SELECT max(per_job.total_s) AS max_s FROM (SELECT t.job_id, sum(t.time_s) AS total_s "
                                       + "FROM testcases t JOIN units u ON u.id = t.unit_id JOIN results r ON r.job_id = t.job_id "
                                       + "JOIN jobs j ON j.id = t.job_id "
                                       + "WHERE j.rerun_of IS NULL AND u.shared_cpu IS NOT TRUE AND (t.class = $1 OR t.class LIKE $2 ESCAPE '!') "
                                       + "GROUP BY t.job_id ORDER BY t.job_id DESC LIMIT $3) per_job";

    @Override
    public Promise<Option<ResultRecord>> result(JobId id) {
        return recordIn(db.executor(), id);
    }

    @Override
    public Promise<List<TestcaseRow>> testcases(JobId id, Option<Outcome> outcome) {
        return Jobs.testcases(db.executor(), id, outcome.map(Outcome::wire));
    }

    @Override
    public Promise<Option<String>> recordJson(JobId id) {
        return Sql.query(db.executor(),
                         "SELECT record::text AS record FROM results WHERE job_id = $1",
                         id.value())
                  .map(PgResultStore::recordText);
    }

    @Override
    public Promise<List<JobId>> finishedSince(Instant since) {
        return Sql.query(db.executor(),
                         "SELECT r.job_id FROM results r JOIN jobs j ON j.id = r.job_id WHERE j.finished_at >= $1 ORDER BY r.job_id",
                         Sql.utc(since))
                  .flatMap(resultSet -> Sql.parseAll(resultSet,
                                                     row -> Rows.jobId(row, "job_id").mapError(Rows.corrupt("results"))));
    }

    /// One read snapshot: the manifest status and its entries agree.
    @Override
    public Promise<Option<JobManifest>> manifest(JobId id) {
        return db.inTransaction(tx -> manifestIn(tx, id));
    }

    /// One snapshot ([PgDigestStore#inSnapshot]) for both sides: what a retention delete committed before it is seen
    /// (and judged pruned against the record's count by the caller), nothing it commits during it.
    @Override
    public Promise<StoredSides> diffSides(JobId a, JobId b) {
        return PgDigestStore.inSnapshot(db, tx -> sidesIn(tx, a, b));
    }

    /// One snapshot ([PgDigestStore#inSnapshot]); one statement per class, one after another.
    @Override
    public Promise<Map<String, Duration>> classDurations(List<String> classNames, int lastJobs) {
        return PgDigestStore.inSnapshot(db,
                                        tx -> Sql.sequence(classNames,
                                                           className -> classDuration(tx, className, lastJobs)))
                            .map(PgResultStore::known);
    }

    private static Promise<Option<Map.Entry<String, Duration>>> classDuration(QueryExecutor tx,
                                                                              String className,
                                                                              int lastJobs) {
        return Sql.query(tx, CLASS_DURATION, className, nestedPattern(className), lastJobs).map(resultSet -> durationOf(className,
                                                                                                                        resultSet));
    }

    /// `max` over no rows is NULL: no history.
    private static Option<Map.Entry<String, Duration>> durationOf(String className, PgResultSet resultSet) {
        return Sql.first(resultSet)
                  .flatMap(row -> option(row.getDouble("max_s")))
                  .map(seconds -> Map.entry(className,
                                            Duration.ofMillis(Math.round(seconds * 1000))));
    }

    /// `Class$%` with LIKE's own characters escaped by `!` (`!`, `%`, `_`), so `$` and the class's own `_` match
    /// literally; `!` needs no backslash, so the statement reads the same whatever `standard_conforming_strings` is.
    static String nestedPattern(String className) {
        return className.replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_") + "$%";
    }

    private static Map<String, Duration> known(List<Option<Map.Entry<String, Duration>>> durations) {
        return durations.stream()
                        .flatMap(Option::stream)
                        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /// One snapshot ([PgDigestStore#inSnapshot]) for side B, the candidates, the selection and side A.
    @Override
    public Promise<BaseRead> diffBase(ShaPrefix sha, JobId job, Fn1<Result<Option<JobId>>, List<GateRow>> choose) {
        return PgDigestStore.inSnapshot(db, tx -> baseIn(tx, sha, job, choose));
    }

    /// Side B and the candidates, before the selection.
    private record Seen(StoredSide b, List<GateRow> candidates) {
        static Seen seen(StoredSide b, List<GateRow> candidates) {
            return new Seen(b, candidates);
        }

        /// The read, with both sides when side A was read.
        BaseRead withSides(Option<StoredSide> a) {
            return BaseRead.baseRead(candidates, a.map(this::withA));
        }

        private StoredSides withA(StoredSide a) {
            return StoredSides.storedSides(a, b);
        }
    }

    /// One statement at a time on the one transaction: side B (so an unknown job fails first), the candidates, then side
    /// A when the selection names a base.
    private static Promise<BaseRead> baseIn(QueryExecutor tx,
                                            ShaPrefix sha,
                                            JobId job,
                                            Fn1<Result<Option<JobId>>, List<GateRow>> choose) {
        return sideIn(tx, job).mapWith(_ -> GateRows.read(tx, sha),
                                       Seen::seen)
                     .flatMap(seen -> withBase(tx, seen, choose));
    }

    private static Promise<BaseRead> withBase(QueryExecutor tx,
                                              Seen seen,
                                              Fn1<Result<Option<JobId>>, List<GateRow>> choose) {
        return choose.apply(seen.candidates())
                     .async()
                     .flatMap(base -> baseSide(tx, base))
                     .map(seen::withSides);
    }

    private static Promise<Option<StoredSide>> baseSide(QueryExecutor tx, Option<JobId> base) {
        return base.map(id -> presentSide(tx, id))
                   .or(() -> Promise.success(none()));
    }

    private static Promise<Option<StoredSide>> presentSide(QueryExecutor tx, JobId id) {
        return sideIn(tx, id).map(Option::some);
    }

    /// The job and its record, before its testcases are read.
    private record Recorded(JobRow job, Option<ResultRecord> record) {
        static Recorded recorded(JobRow job, Option<ResultRecord> record) {
            return new Recorded(job, record);
        }
    }

    /// One statement at a time on the one transaction: side A, then side B.
    private static Promise<StoredSides> sidesIn(QueryExecutor tx, JobId a, JobId b) {
        return sideIn(tx, a).mapWith(_ -> sideIn(tx, b), StoredSides::storedSides);
    }

    private static Promise<StoredSide> sideIn(QueryExecutor tx, JobId id) {
        return Jobs.load(tx, id)
                   .flatMap(job -> job.async(StoreError.JobNotFound.FACTORY.apply(id)))
                   .mapWith(_ -> recordIn(tx, id),
                            Recorded::recorded)
                   .mapWith(_ -> Jobs.testcases(tx,
                                                id,
                                                none()),
                            (recorded, rows) -> StoredSide.storedSide(recorded.job(),
                                                                      recorded.record(),
                                                                      rows));
    }

    private static Promise<Option<ResultRecord>> recordIn(QueryExecutor tx, JobId id) {
        return Sql.query(tx,
                         "SELECT record::text AS record FROM results WHERE job_id = $1",
                         id.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgResultStore::record));
    }

    private static Option<String> recordText(PgResultSet resultSet) {
        return Sql.first(resultSet).map(row -> row.getString("record"));
    }

    private static Promise<Option<JobManifest>> manifestIn(Transaction tx, JobId id) {
        return Sql.snapshot(tx).flatMap(_ -> Manifests.load(tx, id));
    }

    private static Result<ResultRecord> record(PgRow row) {
        return Json.read(row.getString("record"),
                         ResultRecordDto.class)
                   .flatMap(WireRecords::resultRecord)
                   .mapError(Rows.corrupt("results"));
    }
}
