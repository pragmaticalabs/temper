package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.store.api.EventPayload;
import org.pragmatica.temper.store.api.EventRow;
import org.pragmatica.temper.store.api.EventStore;
import org.pragmatica.temper.store.api.Pending;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.SubscriberRow;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// [EventStore] over Postgres.
record PgEventStore(Db db) implements EventStore {
    /// `kinds` filter shared by [#after] and [#pending]: an empty array means every kind.
    private static final String KIND_FILTER = "(cardinality($2::text[]) = 0 OR kind = ANY($2::text[]))";

    /// ONE statement, so the floor and the events come from one snapshot even under READ COMMITTED (m1-design §4.4):
    /// retention raises the floor and deletes in one transaction, and this read sees both or neither. The floor row
    /// always exists (V2 seeds it), so there is at least one row; without events its event columns are NULL.
    private static final String PENDING = "SELECT f.floor_seq, e.seq, e.at, e.job_id, e.kind, e.payload::text AS payload "
                                        + "FROM event_floor f LEFT JOIN LATERAL (SELECT seq, at, job_id, kind, payload FROM events "
                                        + "WHERE seq > $1 AND " + KIND_FILTER
                                        + " ORDER BY seq LIMIT $3) e ON true "
                                        + "WHERE f.id = 1 ORDER BY e.seq";

    /// Created at `GREATEST(max(seq), floor)` (Q-14): every event above that cursor commits later (event commits are
    /// serialised in `seq` order) and is delivered, and a subscriber created after retention emptied the table
    /// starts at the floor rather than below it (no spurious gap).
    private static final String CREATE_SUBSCRIBER = "INSERT INTO subscribers (name, cursor) "
                                                  + "SELECT $1, GREATEST(coalesce(max(e.seq), 0), (SELECT floor_seq FROM event_floor WHERE id = 1)) "
                                                  + "FROM events e ON CONFLICT (name) DO NOTHING";

    private static final String SUBSCRIBER_COLUMNS = "name, cursor, selectors::text AS selectors, selectors_version";

    /// R-M2-12: the version check and the forward-only guard sit in the one statement that moves the cursor.
    private static final String SKIP_TO = "UPDATE subscribers SET cursor = $3 WHERE name = $1 AND selectors_version = $2 AND cursor < $3";

    /// ONE statement reads and writes the set (m2-design §2.3): the UPDATE takes the row lock, and a concurrent change
    /// waits for it and is then evaluated against the committed set (READ COMMITTED re-checks the updated row), so two
    /// clients never lose each other's change. `COLLATE "C"` sorts by code point, as `String.compareTo` does.
    private static final String CHANGE_SELECTORS = "UPDATE subscribers s SET selectors_version = s.selectors_version + 1, selectors = "
                                                 + "(SELECT coalesce(jsonb_agg(c.selector ORDER BY c.selector COLLATE \"C\"), '[]'::jsonb) "
                                                 + "FROM (SELECT old.selector FROM jsonb_array_elements_text(s.selectors) AS old(selector) "
                                                 + "UNION SELECT unnest($2::text[])) c WHERE c.selector <> ALL($3::text[])) "
                                                 + "WHERE s.name = $1 RETURNING " + SUBSCRIBER_COLUMNS;

    @Override
    public Promise<Long> append(Option<JobId> job, EventRow.Kind kind, EventPayload payload) {
        return db.inTransaction(tx -> EventWriter.append(tx, new PendingEvent(job, kind, payload)));
    }

    @Override
    public Promise<List<EventRow>> after(long seq, Set<String> kinds, int limit) {
        return Sql.query(db.executor(),
                         "SELECT seq, at, job_id, kind, payload::text AS payload FROM events WHERE seq > $1 AND " + KIND_FILTER
                        + " ORDER BY seq LIMIT $3",
                         seq,
                         Sql.texts(kinds.stream()),
                         limit)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, Rows::eventRow));
    }

    @Override
    public Promise<Pending> pending(long cursor, Set<String> kinds, int limit) {
        return Sql.query(db.executor(),
                         PENDING,
                         cursor,
                         Sql.texts(kinds.stream()),
                         limit)
                  .flatMap(resultSet -> pendingOf(cursor,
                                                  Sql.rows(resultSet)).async());
    }

    @Override
    public Promise<Long> floor() {
        return Sql.query(db.executor(),
                         "SELECT floor_seq FROM event_floor WHERE id = 1")
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> row.getLong("floor_seq"));
    }

    @Override
    public Promise<SubscriberRow> subscriber(String name) {
        return db.inTransaction(tx -> subscriberIn(tx, name));
    }

    @Override
    public Promise<Unit> ack(String name, long seq) {
        return Sql.update(db.executor(),
                          "UPDATE subscribers SET cursor = GREATEST(cursor, $2) WHERE name = $1",
                          name,
                          seq)
                  .flatMap(changed -> acked(name, changed));
    }

    @Override
    public Promise<SubscriberRow> selectors(String name, List<Selector> add, List<Selector> remove) {
        return db.inTransaction(tx -> selectorsIn(tx, name, add, remove));
    }

    @Override
    public Promise<Unit> skipTo(String name, long version, long seq) {
        return Sql.update(db.executor(),
                          SKIP_TO,
                          name,
                          version,
                          seq)
                  .mapToUnit();
    }

    private static Promise<SubscriberRow> subscriberIn(Transaction tx, String name) {
        return Sql.execute(tx, CREATE_SUBSCRIBER, name)
                  .flatMap(_ -> Sql.query(tx, "SELECT " + SUBSCRIBER_COLUMNS + " FROM subscribers WHERE name = $1", name))
                  .flatMap(resultSet -> subscriberOf(name, resultSet));
    }

    private static Promise<SubscriberRow> selectorsIn(Transaction tx,
                                                      String name,
                                                      List<Selector> add,
                                                      List<Selector> remove) {
        return Sql.execute(tx, CREATE_SUBSCRIBER, name)
                  .flatMap(_ -> Sql.query(tx,
                                          CHANGE_SELECTORS,
                                          name,
                                          wireForms(add),
                                          wireForms(remove)))
                  .flatMap(resultSet -> subscriberOf(name, resultSet));
    }

    private static String[] wireForms(List<Selector> selectors) {
        return Sql.texts(selectors.stream().map(Selector::wire));
    }

    private static Promise<SubscriberRow> subscriberOf(String name, PgResultSet resultSet) {
        return Sql.first(resultSet)
                  .async(StoreError.SubscriberNotFound.FACTORY.apply(name))
                  .flatMap(row -> subscriberRow(row).async());
    }

    /// A stored selector that does not parse is a corrupt row: V7 reset the untyped ones (R-12).
    private static Result<SubscriberRow> subscriberRow(PgRow row) {
        return Json.read(row.getString("selectors"),
                         Json.STRINGS)
                   .flatMap(PgEventStore::typed)
                   .map(selectors -> SubscriberRow.subscriberRow(row.getString("name"),
                                                                 row.getLong("cursor"),
                                                                 selectors,
                                                                 row.getLong("selectors_version")))
                   .mapError(Rows.corrupt("subscribers"));
    }

    private static Result<List<Selector>> typed(List<String> stored) {
        return Result.allOf(stored.stream().map(Selector::selector).toList());
    }

    /// A floor above the cursor is a gap, whatever the events say; otherwise the rows that carry an event.
    private static Result<Pending> pendingOf(long cursor, List<PgRow> rows) {
        return Option.from(rows.stream().findFirst())
                     .toResult(StoreError.General.NO_ROW_RETURNED)
                     .flatMap(first -> gapOrEvents(cursor,
                                                   first.getLong("floor_seq"),
                                                   rows));
    }

    private static Result<Pending> gapOrEvents(long cursor, long floor, List<PgRow> rows) {
        return floor > cursor
               ? success(new Pending.Gap(cursor + 1, floor))
               : events(rows);
    }

    private static Result<Pending> events(List<PgRow> rows) {
        return Result.allOf(rows.stream().filter(PgEventStore::hasEvent).map(Rows::eventRow).toList()).map(Pending.Events::new);
    }

    private static boolean hasEvent(PgRow row) {
        return option(row.getLong("seq")).isPresent();
    }

    private static Promise<Unit> acked(String name, int changed) {
        return changed > 0
               ? Promise.unitPromise()
               : StoreError.SubscriberNotFound.FACTORY.apply(name).promise();
    }
}
