package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.store.api.FleetAcked;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetHead;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.FleetSeqs;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.FleetSubscriber;
import org.pragmatica.temper.store.api.KilnBatch;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.SourceSeen;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// [FleetStore] over Postgres. Every `fleet_events` insert goes through [#insertAll], which takes
/// [EventWriter#ORDER_LOCK] first, and is the last thing its transaction does (the source rows are written before), so
/// the order lock is never held while waiting for a row lock another writer holds.
record PgFleetStore(Db db) implements FleetStore {
    private static final String HEAD = "SELECT t.id::text AS epoch, GREATEST(coalesce((SELECT max(seq) FROM fleet_events), 0), f.floor_seq) AS seq, "
                                     + "(SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM fleet_events_seq_seq) AS sequence "
                                     + "FROM temper_epoch t, fleet_floor f WHERE f.id = 1";

    /// `ON CONFLICT DO NOTHING` is the F10 guard: a kiln change already stored (a pull repeated after a lost reply)
    /// inserts nothing, and the batch and its cursor update still commit. Rows of other producers carry NULL keys,
    /// which never conflict.
    private static final String INSERT = "INSERT INTO fleet_events (epoch, observed_at, source, source_epoch, source_seq, kind, subject, payload, catch_up) "
                                       + "SELECT t.id, $1::timestamptz, $2::text, $3::text, $4::bigint, $5::text, $6::text, $7::jsonb, $8::boolean "
                                       + "FROM temper_epoch t ON CONFLICT (source, source_epoch, source_seq) DO NOTHING RETURNING seq";

    static final String EVENT_COLUMNS = "epoch::text AS epoch, seq, at, observed_at, kind, subject, catch_up, source, payload::text AS payload";

    /// ONE statement: the floor and the events come from one snapshot (as `PgEventStore.PENDING`).
    private static final String PENDING = "SELECT f.floor_seq, e.* FROM fleet_floor f LEFT JOIN LATERAL (SELECT " + EVENT_COLUMNS
                                        + " FROM fleet_events WHERE seq > $1 ORDER BY seq LIMIT $2) e ON true WHERE f.id = 1 ORDER BY e.seq";

    /// ONE statement (lead ruling F2): the epoch row is locked `FOR SHARE`, so `renewEpoch`'s UPDATE waits for it or it
    /// for that (and then reads the new epoch); the cursor moves only when `$3` is that epoch and `$2` is at or below the
    /// head, all from the statement's one snapshot. It answers the epoch, the head, and whether a row was updated.
    private static final String ACK = "WITH h AS (SELECT t.id::text AS epoch, GREATEST(coalesce((SELECT max(seq) FROM fleet_events), 0), f.floor_seq) AS head "
                                    + "FROM temper_epoch t, fleet_floor f WHERE f.id = 1 FOR SHARE OF t), "
                                    + "u AS (UPDATE fleet_subscribers SET cursor = GREATEST(cursor, $2::bigint), last_poll_at = now() "
                                    + "WHERE name = $1 AND $3::text = (SELECT epoch FROM h) AND $2::bigint <= (SELECT head FROM h) RETURNING 1) "
                                    + "SELECT h.epoch, h.head, (SELECT count(*) FROM u) AS moved FROM h";

    private static final String HEAD_SEQ = "GREATEST(coalesce((SELECT max(seq) FROM fleet_events), 0), (SELECT floor_seq FROM fleet_floor WHERE id = 1))";

    private static final String KILN_ROW = "INSERT INTO fleet_source (name, epoch, cursor, last_ok_at, period_s, generation, catch_up_to, head) "
                                         + "VALUES ($1, $2::text, $3::bigint, $4::timestamptz, $5::int, $6::int, $7::bigint, $8::bigint) "
                                         + "ON CONFLICT (name) DO UPDATE SET epoch = EXCLUDED.epoch, cursor = EXCLUDED.cursor, "
                                         + "last_ok_at = coalesce(EXCLUDED.last_ok_at, fleet_source.last_ok_at), period_s = EXCLUDED.period_s, "
                                         + "generation = EXCLUDED.generation, catch_up_to = EXCLUDED.catch_up_to, head = EXCLUDED.head";

    /// The last value written (never GREATEST: a success recorded under a clock that was ahead must not keep a
    /// dead source fresh after the clock is corrected); an absent success (NULL) keeps the stored one.
    private static final String SEEN = "INSERT INTO fleet_source (name, last_ok_at, period_s) VALUES ($1, $2::timestamptz, $3::int) "
                                     + "ON CONFLICT (name) DO UPDATE SET last_ok_at = coalesce(EXCLUDED.last_ok_at, fleet_source.last_ok_at), "
                                     + "period_s = EXCLUDED.period_s";

    private static final String SOURCE_COLUMNS = "name, epoch, cursor, last_ok_at, period_s, generation, catch_up_to, head, stale";

    private static final String PRUNE = "WITH cut AS (SELECT max(seq) AS s FROM fleet_events WHERE at < $1::timestamptz), "
                                      + "gone AS (DELETE FROM fleet_events WHERE seq <= (SELECT s FROM cut) RETURNING seq) "
                                      + "UPDATE fleet_floor SET floor_seq = GREATEST(floor_seq, coalesce((SELECT max(seq) FROM gone), 0)) WHERE id = 1";

    @Override
    public Promise<FleetHead> head() {
        return Sql.query(db.executor(),
                         HEAD)
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> FleetHead.fleetHead(row.getString("epoch"),
                                                  row.getLong("seq"),
                                                  row.getLong("sequence")));
    }

    @Override
    public Promise<String> renewEpoch() {
        return Sql.query(db.executor(),
                         "UPDATE temper_epoch SET id = gen_random_uuid(), created_at = now() RETURNING id::text AS epoch")
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> row.getString("epoch"));
    }

    @Override
    public Promise<Option<FleetSeqs>> append(List<NewFleetEvent> events) {
        return db.inTransaction(tx -> insertAll(tx, events));
    }

    @Override
    public Promise<Option<FleetSeqs>> pulled(KilnBatch batch) {
        return db.inTransaction(tx -> pulledIn(tx, batch));
    }

    @Override
    public Promise<List<FleetSourceRow>> sources() {
        return Sql.query(db.executor(),
                         "SELECT " + SOURCE_COLUMNS + " FROM fleet_source ORDER BY name")
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgFleetStore::sourceRow)
                                       .toList());
    }

    @Override
    public Promise<Option<FleetSourceRow>> source(String name) {
        return Sql.query(db.executor(),
                         "SELECT " + SOURCE_COLUMNS + " FROM fleet_source WHERE name = $1",
                         name)
                  .map(resultSet -> Sql.first(resultSet).map(PgFleetStore::sourceRow));
    }

    @Override
    public Promise<Unit> expect(List<SourceSeen> sources) {
        return Sql.sequence(sources,
                            source -> Sql.execute(db.executor(),
                                                  "INSERT INTO fleet_source (name, period_s) VALUES ($1, $2::int) ON CONFLICT (name) DO NOTHING",
                                                  source.name(),
                                                  source.periodS()))
                  .mapToUnit();
    }

    @Override
    public Promise<Unit> seen(List<SourceSeen> sources) {
        return db.inTransaction(tx -> seenIn(tx, sources));
    }

    @Override
    public Promise<Option<FleetSeqs>> staleness(String name,
                                                boolean stale,
                                                Option<Instant> readLastOkAt,
                                                NewFleetEvent event) {
        return db.inTransaction(tx -> stalenessIn(tx, name, stale, readLastOkAt, event));
    }

    @Override
    public Promise<FleetPending> pending(long cursor, int limit) {
        return pendingIn(db.executor(), cursor, limit);
    }

    @Override
    public Promise<Boolean> create(String name, Option<Long> cursor, String tokenId) {
        return Sql.update(db.executor(),
                          "INSERT INTO fleet_subscribers (name, cursor, token_id) SELECT $1, coalesce($2::bigint, " + HEAD_SEQ
                         + "), $3 ON CONFLICT (name) DO NOTHING",
                          name,
                          Sql.nullable(cursor),
                          tokenId)
                  .map(changed -> changed > 0);
    }

    @Override
    public Promise<Option<FleetSubscriber>> subscriber(String name) {
        return Sql.query(db.executor(),
                         "SELECT name, cursor, token_id FROM fleet_subscribers WHERE name = $1",
                         name)
                  .map(resultSet -> Sql.first(resultSet).map(row -> FleetSubscriber.fleetSubscriber(row.getString("name"),
                                                                                                    row.getLong("cursor"),
                                                                                                    row.getString("token_id"))));
    }

    @Override
    public Promise<Boolean> reset(String name, Option<Long> cursor) {
        return Sql.update(db.executor(),
                          "UPDATE fleet_subscribers SET cursor = coalesce($2::bigint, " + HEAD_SEQ + ") WHERE name = $1",
                          name,
                          Sql.nullable(cursor))
                  .map(changed -> changed > 0);
    }

    @Override
    public Promise<FleetAcked> ack(String name, String epoch, long seq) {
        return Sql.query(db.executor(),
                         ACK,
                         name,
                         seq,
                         epoch)
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> FleetAcked.fleetAcked(row.getString("epoch"),
                                                    row.getLong("head"),
                                                    row.getLong("moved") > 0));
    }

    @Override
    public Promise<Unit> polled(String name, Instant at) {
        return Sql.execute(db.executor(),
                           "UPDATE fleet_subscribers SET last_poll_at = $2::timestamptz WHERE name = $1",
                           name,
                           Sql.utc(at));
    }

    @Override
    public Promise<Unit> tick(Instant at, Instant keepFrom) {
        return db.inTransaction(tx -> Sql.execute(tx,
                                                  "INSERT INTO fleet_heartbeat_tick (at) VALUES ($1::timestamptz) ON CONFLICT DO NOTHING",
                                                  Sql.utc(at))
                                         .flatMap(_ -> Sql.execute(tx,
                                                                   "DELETE FROM fleet_heartbeat_tick WHERE at < $1::timestamptz",
                                                                   Sql.utc(keepFrom))));
    }

    @Override
    public Promise<List<Instant>> ticks(Instant since) {
        return Sql.query(db.executor(),
                         "SELECT at FROM fleet_heartbeat_tick WHERE at >= $1::timestamptz ORDER BY at",
                         Sql.utc(since))
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(row -> row.getInstant("at"))
                                       .toList());
    }

    @Override
    public Promise<Unit> prune(Instant keepFrom) {
        return Sql.execute(db.executor(), PRUNE, Sql.utc(keepFrom));
    }

    /// The source rows first, then the events under the order lock (events last, see the class comment).
    private static Promise<Option<FleetSeqs>> pulledIn(Transaction tx, KilnBatch batch) {
        return kilnRow(tx,
                       batch.kiln()).flatMap(_ -> seenIn(tx,
                                                         batch.sources()))
                      .flatMap(_ -> insertAll(tx,
                                              batch.events()));
    }

    private static Promise<Unit> kilnRow(QueryExecutor tx, FleetSourceRow kiln) {
        return Sql.execute(tx,
                           KILN_ROW,
                           kiln.name(),
                           Sql.nullable(kiln.epoch()),
                           kiln.cursor(),
                           Sql.nullable(kiln.lastOkAt().map(Sql::utc)),
                           kiln.periodS(),
                           kiln.generation(),
                           kiln.catchUpTo(),
                           kiln.head());
    }

    static Promise<Unit> seenIn(QueryExecutor tx, List<SourceSeen> sources) {
        return Sql.sequence(sources,
                            source -> Sql.execute(tx,
                                                  SEEN,
                                                  source.name(),
                                                  Sql.nullable(source.lastOkAt().map(Sql::utc)),
                                                  source.periodS()))
                  .mapToUnit();
    }

    /// The verdict is written only while `last_ok_at` is still the value it was computed from: a success written in
    /// between (a pull) makes the update a no-op, and the next minute decides again (no spurious stale/recovered pair).
    private static Promise<Option<FleetSeqs>> stalenessIn(Transaction tx,
                                                          String name,
                                                          boolean stale,
                                                          Option<Instant> readLastOkAt,
                                                          NewFleetEvent event) {
        return Sql.update(tx,
                          "UPDATE fleet_source SET stale = $2::boolean WHERE name = $1 AND stale <> $2::boolean "
                         + "AND last_ok_at IS NOT DISTINCT FROM $3::timestamptz",
                          name,
                          stale,
                          Sql.nullable(readLastOkAt.map(Sql::utc)))
                  .flatMap(changed -> announced(tx, changed, event));
    }

    private static Promise<Option<FleetSeqs>> announced(QueryExecutor tx, int changed, NewFleetEvent event) {
        return changed > 0
               ? insertAll(tx, List.of(event))
               : Promise.success(none());
    }

    /// [#pending] on `executor`: package-private for [PgT2Store], whose detector reads in its own transaction.
    static Promise<FleetPending> pendingIn(QueryExecutor executor, long cursor, int limit) {
        return Sql.query(executor, PENDING, cursor, limit).flatMap(resultSet -> pendingOf(cursor, Sql.rows(resultSet)));
    }

    /// The order lock, then every insert; nothing is locked when there is nothing to insert. Package-private for
    /// `FleetStoreIT`, which holds a transaction open after it.
    static Promise<Option<FleetSeqs>> insertAll(QueryExecutor tx, List<NewFleetEvent> events) {
        return events.isEmpty()
               ? Promise.success(none())
               : Sql.execute(tx,
                             "SELECT pg_advisory_xact_lock($1)",
                             EventWriter.ORDER_LOCK)
                    .flatMap(_ -> Sql.sequence(events,
                                               event -> insert(tx, event)))
                    .map(PgFleetStore::inserted);
    }

    private static Promise<Option<Long>> insert(QueryExecutor tx, NewFleetEvent event) {
        return Sql.query(tx,
                         INSERT,
                         Sql.utc(event.observedAt()),
                         event.source(),
                         Sql.nullable(event.sourceEpoch()),
                         Sql.nullable(event.sourceSeq()),
                         event.kind(),
                         event.subject(),
                         event.payload(),
                         event.catchUp())
                  .map(resultSet -> Sql.first(resultSet).map(row -> row.getLong("seq")));
    }

    /// The lowest and highest seq of the rows inserted; none when every insert was a duplicate.
    private static Option<FleetSeqs> inserted(List<Option<Long>> seqs) {
        var inserted = seqs.stream().flatMap(Option::stream).mapToLong(Long::longValue).summaryStatistics();

        return inserted.getCount() > 0
               ? some(FleetSeqs.fleetSeqs(inserted.getMin(), inserted.getMax()))
               : none();
    }

    private static Promise<FleetPending> pendingOf(long cursor, List<PgRow> rows) {
        return Option.from(rows.stream().findFirst())
                     .async(StoreError.General.NO_ROW_RETURNED)
                     .map(first -> gapOrEvents(cursor,
                                               first.getLong("floor_seq"),
                                               rows));
    }

    private static FleetPending gapOrEvents(long cursor, long floor, List<PgRow> rows) {
        return floor > cursor
               ? new FleetPending.Gap(cursor + 1, floor)
               : new FleetPending.Events(rows.stream()
                                             .filter(row -> option(row.getLong("seq")).isPresent())
                                             .map(PgFleetStore::eventRow)
                                             .toList());
    }

    static FleetEventRow eventRow(PgRow row) {
        return FleetEventRow.fleetEventRow(row.getString("epoch"),
                                           row.getLong("seq"),
                                           row.getInstant("at"),
                                           row.getInstant("observed_at"),
                                           row.getString("kind"),
                                           row.getString("subject"),
                                           row.getBoolean("catch_up"),
                                           row.getString("source"),
                                           row.getString("payload"));
    }

    private static FleetSourceRow sourceRow(PgRow row) {
        return FleetSourceRow.fleetSourceRow(row.getString("name"),
                                             option(row.getString("epoch")),
                                             row.getLong("cursor"),
                                             option(row.getInstant("last_ok_at")),
                                             row.getInt("period_s"),
                                             row.getInt("generation"),
                                             row.getLong("catch_up_to"),
                                             row.getLong("head"),
                                             row.getBoolean("stale"));
    }
}
