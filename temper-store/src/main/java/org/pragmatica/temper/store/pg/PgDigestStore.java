package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
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
import org.pragmatica.temper.store.api.DigestQuery;
import org.pragmatica.temper.store.api.DigestSnapshot;
import org.pragmatica.temper.store.api.DigestStore;
import org.pragmatica.temper.store.api.EventCursor;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventWindow;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.StoredHost;

import static org.pragmatica.lang.Option.option;


/// [DigestStore] over Postgres: every statement runs in one transaction opened by [#inSnapshot].
record PgDigestStore(Db db) implements DigestStore {
    /// ONE statement for the floor, the head, the anchor and the events, as [PgEventStore]'s pending read: the rows
    /// above the cursor, the floor that says what retention deleted below it, and the `at` of the event the cursor
    /// names (`anchor_at`, its identity, [EventCursor]) cannot disagree. `$1` NULL (no `since`) starts at the head, so
    /// no event is read and the anchor is the head event. The floor and head row always exists (V2 seeds the floor);
    /// without events its event columns are NULL.
    private static final String WINDOW = "SELECT f.floor_seq, h.head, a.at AS anchor_at, e.seq, e.at, e.job_id, e.kind, e.payload::text AS payload "
                                       + "FROM event_floor f CROSS JOIN (SELECT coalesce(max(seq), 0) AS head FROM events) h "
                                       + "LEFT JOIN events a ON a.seq = coalesce($1::bigint, h.head) "
                                       + "LEFT JOIN LATERAL (SELECT seq, at, job_id, kind, payload FROM events "
                                       + "WHERE seq > GREATEST(coalesce($1::bigint, h.head), f.floor_seq) ORDER BY seq LIMIT $2) e ON true "
                                       + "WHERE f.id = 1 ORDER BY e.seq";

    private static final String JOBS = "SELECT " + Rows.JOB_COLUMNS + Rows.JOB_FROM + " WHERE j.id = ANY($1::bigint[])";

    /// The waiting jobs: QUEUED ones, and any other job with a unit still QUEUED (a RUNNING Gate with shards left to
    /// grant), since the grant's candidates are QUEUED units whatever their job's state. The grant's order
    /// (`PgUnitStore`'s candidates: priority descending, submit time, id). `total` is counted before the LIMIT, so it is
    /// every such job, not only those listed.
    private static final String QUEUE = "SELECT " + Rows.JOB_COLUMNS
                                      + ", count(*) OVER () AS total" + Rows.JOB_FROM
                                      + " WHERE j.state = 'QUEUED' OR EXISTS (SELECT 1 FROM units q WHERE q.job_id = j.id AND q.state = 'QUEUED')"
                                      + " ORDER BY j.priority DESC, j.submitted_at ASC, j.id ASC LIMIT $1";

    private static final String HOSTS = "SELECT h.name, h.state, (SELECT count(*) FROM units u WHERE u.host = h.name AND u.state IN " + PgUnitStore.ACTIVE_STATES
                                      + ") AS active FROM hosts h ORDER BY h.name";

    @Override
    public Promise<DigestSnapshot> snapshot(DigestQuery query) {
        return inSnapshot(db, tx -> snapshotIn(tx, query));
    }

    /// The digest's transaction: `REPEATABLE READ, READ ONLY` is its first statement ([Sql#snapshot]), so every read in
    /// `reads` sees the snapshot of the first one, and a write in it fails in Postgres (SQLSTATE 25006). The transaction
    /// then commits with nothing to commit.
    static <T> Promise<T> inSnapshot(Db db, Fn1<Promise<T>, Transaction> reads) {
        return db.inTransaction(tx -> Sql.snapshot(tx).flatMap(_ -> reads.apply(tx)));
    }

    // ---- the reads, in this order, one statement at a time on the transaction ---------------------------------------
    private record Seen(EventWindow window, Map<JobId, JobRow> jobs) {}

    private record Waiting(Seen seen, Queue queue) {}

    private record Hosted(Waiting waiting, List<StoredHost> hosts) {}

    private record Queue(List<JobRow> jobs, int total, List<QueuedUnit> candidates) {}

    private record Dumps(Option<MaintenanceRun> latest, Option<MaintenanceRun> lastGood) {}

    private static Promise<DigestSnapshot> snapshotIn(Transaction tx, DigestQuery query) {
        return windowIn(tx, query).mapWith(window -> jobsIn(tx, window),
                                           Seen::new)
                       .mapWith(_ -> queueIn(tx),
                                Waiting::new)
                       .mapWith(_ -> hostsIn(tx),
                                Hosted::new)
                       .mapWith(_ -> dumpsIn(tx),
                                PgDigestStore::snapshotOf);
    }

    private static DigestSnapshot snapshotOf(Hosted hosted, Dumps dumps) {
        var seen = hosted.waiting().seen();
        var queue = hosted.waiting().queue();

        return DigestSnapshot.digestSnapshot(seen.window(),
                                             seen.jobs(),
                                             queue.jobs(),
                                             queue.total(),
                                             queue.candidates(),
                                             hosted.hosts(),
                                             dumps.latest(),
                                             dumps.lastGood());
    }

    /// `max + 1` rows, so the window can tell that more follow.
    private static Promise<EventWindow> windowIn(QueryExecutor tx, DigestQuery query) {
        return Sql.query(tx,
                         WINDOW,
                         Sql.nullable(query.since().map(EventCursor::seq)),
                         query.max() + 1)
                  .flatMap(resultSet -> window(query,
                                               Sql.rows(resultSet)).async());
    }

    private static Result<EventWindow> window(DigestQuery query, List<PgRow> rows) {
        return Option.from(rows.stream().findFirst())
                     .toResult(StoreError.General.NO_ROW_RETURNED)
                     .flatMap(first -> windowOf(query, first, rows));
    }

    /// Every row carries the floor and the head; the rows with an event carry one each.
    private static Result<EventWindow> windowOf(DigestQuery query, PgRow first, List<PgRow> rows) {
        return events(rows).flatMap(read -> EventWindow.eventWindow(query.since(),
                                                                    first.getLong("floor_seq"),
                                                                    first.getLong("head"),
                                                                    option(first.getInstant("anchor_at")),
                                                                    read,
                                                                    query.max()));
    }

    private static Result<List<EventRow>> events(List<PgRow> rows) {
        return Result.allOf(rows.stream().filter(PgDigestStore::hasEvent).map(Rows::eventRow).toList());
    }

    private static boolean hasEvent(PgRow row) {
        return option(row.getLong("seq")).isPresent();
    }

    /// The jobs the window's events name, with their units (for the owner and priority of each event).
    private static Promise<Map<JobId, JobRow>> jobsIn(QueryExecutor tx, EventWindow window) {
        return Sql.query(tx,
                         JOBS,
                         (Object) jobIds(window))
                  .flatMap(resultSet -> Jobs.withUnits(tx,
                                                       Sql.rows(resultSet)))
                  .map(PgDigestStore::byId);
    }

    private static Long[] jobIds(EventWindow window) {
        return window.events()
                     .stream()
                     .flatMap(event -> event.job()
                                            .stream())
                     .map(JobId::value)
                     .distinct()
                     .toArray(Long[]::new);
    }

    private static Map<JobId, JobRow> byId(List<JobRow> jobs) {
        return jobs.stream()
                   .collect(Collectors.toUnmodifiableMap(JobRow::id,
                                                         Function.identity()));
    }

    private static Promise<Queue> queueIn(QueryExecutor tx) {
        return Sql.query(tx, QUEUE, QUEUE_LIMIT)
                  .map(Sql::rows)
                  .flatMap(rows -> queueOf(tx, rows));
    }

    private static Promise<Queue> queueOf(QueryExecutor tx, List<PgRow> rows) {
        return Jobs.withUnits(tx, rows).mapWith(_ -> candidatesIn(tx),
                                                (jobs, candidates) -> new Queue(jobs, total(rows), candidates));
    }

    /// The grant's candidates in the same snapshot as the queue, so every unit id the explanation names belongs to a job
    /// row of this answer or to no listed job at all.
    private static Promise<List<QueuedUnit>> candidatesIn(QueryExecutor tx) {
        return Sql.query(tx, PgUnitStore.QUEUED_UNITS).flatMap(resultSet -> Sql.parseAll(resultSet,
                                                                                         PgUnitStore::queuedUnit));
    }

    private static int total(List<PgRow> rows) {
        return Option.from(rows.stream().findFirst())
                     .map(row -> Math.toIntExact(row.getLong("total")))
                     .or(0);
    }

    private static Promise<List<StoredHost>> hostsIn(QueryExecutor tx) {
        return Sql.query(tx, HOSTS).flatMap(resultSet -> Sql.parseAll(resultSet, PgDigestStore::storedHost));
    }

    private static Result<StoredHost> storedHost(PgRow row) {
        return HostName.hostName(row.getString("name"))
                       .map(name -> StoredHost.storedHost(name,
                                                          row.getString("state"),
                                                          Math.toIntExact(row.getLong("active"))))
                       .mapError(Rows.corrupt("hosts"));
    }

    /// The latest run first, then the latest good one, as `GET /maintenance` reads them, but in the one snapshot.
    private static Promise<Dumps> dumpsIn(QueryExecutor tx) {
        return dumpIn(tx, PgMaintenanceStore.LATEST).mapWith(_ -> dumpIn(tx, PgMaintenanceStore.LAST_GOOD), Dumps::new);
    }

    private static Promise<Option<MaintenanceRun>> dumpIn(QueryExecutor tx, String sql) {
        return Sql.query(tx,
                         sql,
                         MaintenanceKind.PG_DUMP.wire())
                  .flatMap(PgDigestStore::firstRun);
    }

    private static Promise<Option<MaintenanceRun>> firstRun(PgResultSet resultSet) {
        return Sql.parseFirst(resultSet, PgMaintenanceStore::run);
    }
}
