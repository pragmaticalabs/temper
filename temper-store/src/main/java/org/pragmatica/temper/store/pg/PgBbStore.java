package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbCollect;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.BbStore.Listing;
import org.pragmatica.temper.store.api.BbStore.RoundWrite;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.FleetSeqs;
import org.pragmatica.temper.store.api.SourceSeen;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// [BbStore] over Postgres. A success is one transaction:
/// baseline and alarm marks read, snapshot and collection state written, the source marked seen, the marks changed, the
/// events inserted last (order lock). A failure touches
/// `bb_collect` alone, so the snapshot, the baseline of the next diff, never moves on a failed read.
record PgBbStore(Db db) implements BbStore {
    private static final TypeToken<List<BbTicket>> TICKETS = new TypeToken<>() {};

    private static final String SNAPSHOT_COLUMNS = "host, taken_at, queue, tickets::text AS tickets, host_at, skew_s::text AS skew_s";

    private static final String UPSERT_SNAPSHOT = "INSERT INTO bb_snapshot (host, taken_at, queue, tickets, host_at, skew_s) "
                                                + "VALUES ($1, $2::timestamptz, $3, $4::jsonb, $5::timestamptz, $6::integer) "
                                                + "ON CONFLICT (host) DO UPDATE SET taken_at = EXCLUDED.taken_at, queue = EXCLUDED.queue, "
                                                + "tickets = EXCLUDED.tickets, host_at = EXCLUDED.host_at, skew_s = EXCLUDED.skew_s";

    private static final String ALARM_COLUMNS = "SELECT host, kind, subject, threshold, fired_at FROM bb_alarm ";
    private static final String ALARM_ORDER = " ORDER BY host, kind, subject, threshold";

    private static final String INSERT_ALARM = "INSERT INTO bb_alarm (host, kind, subject, threshold, fired_at) VALUES ($1, $2, $3, $4, $5::timestamptz)";

    private static final String DELETE_ALARM = "DELETE FROM bb_alarm WHERE host = $1 AND kind = $2 AND subject = $3 AND threshold = $4";

    /// Retire ticks are serialized for the length of a transaction.
    private static final String RETIRE_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended('bb_alarm:host-retiring', 0))";

    private static final String COLLECT_OK = "INSERT INTO bb_collect (host, last_attempt_at, last_ok_at, failing_since, last_error) "
                                           + "VALUES ($1, $2::timestamptz, $2::timestamptz, NULL, NULL) "
                                           + "ON CONFLICT (host) DO UPDATE SET last_attempt_at = EXCLUDED.last_attempt_at, "
                                           + "last_ok_at = EXCLUDED.last_ok_at, failing_since = NULL, last_error = NULL "
                                           + "WHERE bb_collect.last_attempt_at <= EXCLUDED.last_attempt_at";

    private static final String COLLECT_FAILED = "INSERT INTO bb_collect (host, last_attempt_at, failing_since, last_error) "
                                               + "VALUES ($1, $2::timestamptz, $2::timestamptz, $3) "
                                               + "ON CONFLICT (host) DO UPDATE SET last_attempt_at = EXCLUDED.last_attempt_at, "
                                               + "failing_since = COALESCE(bb_collect.failing_since, EXCLUDED.failing_since), "
                                               + "last_error = EXCLUDED.last_error "
                                               + "WHERE bb_collect.last_attempt_at <= EXCLUDED.last_attempt_at";

    /// One host's collections are serialized for the length of a transaction (an abandoned step may still be running).
    private static final String HOST_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended('bb_snapshot:' || $1::text, 0))";

    @Override
    public Promise<Option<FleetSeqs>> collected(String host,
                                                Instant now,
                                                Listing listing,
                                                Fn2<RoundWrite, Option<BbSnapshot>, List<BbAlarm>> round) {
        return Json.write(listing.tickets())
                   .mapError(Rows.corrupt("bb_snapshot"))
                   .async()
                   .flatMap(json -> db.inTransaction(tx -> collectedIn(tx, host, now, listing, json, round)));
    }

    private static Promise<Option<FleetSeqs>> collectedIn(QueryExecutor tx,
                                                          String host,
                                                          Instant now,
                                                          Listing listing,
                                                          String json,
                                                          Fn2<RoundWrite, Option<BbSnapshot>, List<BbAlarm>> round) {
        return Sql.query(tx, HOST_LOCK, host)
                  .flatMap(_ -> snapshotIn(tx, host))
                  .flatMap(baseline -> baseline.filter(known -> !known.takenAt()
                                                                      .isBefore(now))
                                               .fold(() -> applied(tx, host, now, listing, json, round, baseline),
                                                     known -> stale(host, now, known)));
    }

    /// Snapshot, collect state and source first, then the marks, then the events last (order lock).
    private static Promise<Option<FleetSeqs>> applied(QueryExecutor tx,
                                                      String host,
                                                      Instant now,
                                                      Listing listing,
                                                      String json,
                                                      Fn2<RoundWrite, Option<BbSnapshot>, List<BbAlarm>> round,
                                                      Option<BbSnapshot> baseline) {
        return alarmsIn(tx, "WHERE host = $1", host).flatMap(rows -> written(tx, host, now, listing, json).flatMap(_ -> marked(tx,
                                                                                                                               round.apply(baseline,
                                                                                                                                           rows))));
    }

    private static Promise<Option<FleetSeqs>> marked(QueryExecutor tx, RoundWrite write) {
        return Sql.sequence(write.clear(),
                            alarm -> Sql.execute(tx,
                                                 DELETE_ALARM,
                                                 alarm.host(),
                                                 alarm.kind(),
                                                 alarm.subject(),
                                                 alarm.threshold()))
                  .flatMap(_ -> Sql.sequence(write.fire(),
                                             alarm -> Sql.execute(tx,
                                                                  INSERT_ALARM,
                                                                  alarm.host(),
                                                                  alarm.kind(),
                                                                  alarm.subject(),
                                                                  alarm.threshold(),
                                                                  Sql.utc(alarm.firedAt()))))
                  .flatMap(_ -> PgFleetStore.insertAll(tx,
                                                       write.events()));
    }

    private static Promise<Option<FleetSeqs>> stale(String host, Instant now, BbSnapshot baseline) {
        return BbStore.StaleStep.staleStep(host,
                                           now,
                                           baseline.takenAt())
                                .promise();
    }

    private static Promise<Unit> written(QueryExecutor tx, String host, Instant now, Listing listing, String json) {
        var at = Sql.utc(now);

        return Sql.execute(tx,
                           UPSERT_SNAPSHOT,
                           host,
                           at,
                           listing.queue(),
                           json,
                           Sql.utc(listing.hostAt()),
                           listing.skewS())
                  .flatMap(_ -> Sql.execute(tx, COLLECT_OK, host, at))
                  .flatMap(_ -> PgFleetStore.seenIn(tx,
                                                    List.of(SourceSeen.sourceSeen(BbStore.source(host),
                                                                                  some(now),
                                                                                  PERIOD_S))));
    }

    @Override
    public Promise<Option<FleetSeqs>> retiring(Instant now, Fn1<RoundWrite, List<BbAlarm>> decide) {
        return db.inTransaction(tx -> Sql.execute(tx, RETIRE_LOCK)
                                         .flatMap(_ -> alarmsIn(tx, "WHERE kind = $1", BbAlarm.HOST_RETIRING))
                                         .flatMap(rows -> marked(tx,
                                                                 decide.apply(rows))));
    }

    @Override
    public Promise<List<BbAlarm>> alarms() {
        return alarmsIn(db.executor(), "");
    }

    private static Promise<List<BbAlarm>> alarmsIn(QueryExecutor executor, String where, Object... params) {
        return Sql.query(executor, ALARM_COLUMNS + where + ALARM_ORDER, params).map(resultSet -> Sql.rows(resultSet)
                                                                                                    .stream()
                                                                                                    .map(PgBbStore::alarmRow)
                                                                                                    .toList());
    }

    private static BbAlarm alarmRow(PgRow row) {
        return BbAlarm.bbAlarm(row.getString("host"),
                               row.getString("kind"),
                               row.getString("subject"),
                               row.getString("threshold"),
                               row.getInstant("fired_at"));
    }

    @Override
    public Promise<Unit> failed(String host, Instant now, String error) {
        return Sql.execute(db.executor(), COLLECT_FAILED, host, Sql.utc(now), cut(error));
    }

    private static String cut(String error) {
        return error.length() <= MAX_ERROR
               ? error
               : error.substring(0, MAX_ERROR);
    }

    @Override
    public Promise<Option<BbSnapshot>> snapshot(String host) {
        return snapshotIn(db.executor(), host);
    }

    private static Promise<Option<BbSnapshot>> snapshotIn(QueryExecutor executor, String host) {
        return Sql.query(executor, "SELECT " + SNAPSHOT_COLUMNS + " FROM bb_snapshot WHERE host = $1", host).flatMap(resultSet -> Sql.parseFirst(resultSet,
                                                                                                                                                 PgBbStore::snapshotRow));
    }

    @Override
    public Promise<List<BbSnapshot>> snapshots() {
        return Sql.query(db.executor(),
                         "SELECT " + SNAPSHOT_COLUMNS + " FROM bb_snapshot ORDER BY host")
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgBbStore::snapshotRow));
    }

    @Override
    public Promise<List<BbCollect>> collects() {
        return Sql.query(db.executor(),
                         "SELECT host, last_attempt_at, last_ok_at, failing_since, last_error FROM bb_collect ORDER BY host")
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgBbStore::collectRow)
                                       .toList());
    }

    private static Result<BbSnapshot> snapshotRow(PgRow row) {
        return Json.read(row.getString("tickets"),
                         TICKETS)
                   .mapError(Rows.corrupt("bb_snapshot"))
                   .map(tickets -> BbSnapshot.bbSnapshot(row.getString("host"),
                                                         row.getInstant("taken_at"),
                                                         Option.option(row.getString("queue")),
                                                         tickets,
                                                         option(row.getInstant("host_at")),
                                                         option(row.getString("skew_s")).map(Integer::parseInt)));
    }

    private static BbCollect collectRow(PgRow row) {
        return BbCollect.bbCollect(row.getString("host"),
                                   option(row.getInstant("last_attempt_at")),
                                   option(row.getInstant("last_ok_at")),
                                   option(row.getInstant("failing_since")),
                                   option(row.getString("last_error")));
    }
}
