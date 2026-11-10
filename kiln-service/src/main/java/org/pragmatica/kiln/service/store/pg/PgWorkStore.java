package org.pragmatica.kiln.service.store.pg;

import org.pragmatica.kiln.service.store.WorkBoard;
import org.pragmatica.kiln.service.store.WorkCard;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.store.WorkPhase;
import org.pragmatica.kiln.service.store.WorkStore;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.Transaction;


/// [WorkStore] over `work_item` and `work_event` (V5). The event table is append-only: this store never updates or
/// deletes an event. The item row is the only one written twice, and only its title and its `submitted_at` move.
record PgWorkStore(Db db) implements WorkStore {
    private static final String TABLE = "work_event";

    /// The title is taken when one is given and the item has none yet, or when the event is at or after every recorded
    /// event of the item; so a backfilled event (an earlier `at`) never overwrites a newer title. V5 records no title per
    /// event, so the one case this cannot order is a backfill landing between an older titled event and a newer untitled
    /// one: the older title stays (lead ruling, verification fix 3). Runs before the event is appended.
    private static final String UPSERT_ITEM = "INSERT INTO work_item (id, item, title, submitted_at) VALUES ($1, $2, $3, $4) "
                                            + "ON CONFLICT (id) DO UPDATE SET title = CASE WHEN EXCLUDED.title IS NOT NULL "
                                            + "AND (work_item.title IS NULL OR EXCLUDED.submitted_at >= (SELECT max(e.at) FROM work_event e "
                                            + "WHERE e.item_id = EXCLUDED.id)) THEN EXCLUDED.title ELSE work_item.title END, "
                                            + "submitted_at = least(work_item.submitted_at, EXCLUDED.submitted_at)";

    private static final String APPEND = "INSERT INTO work_event (item_id, at, phase, round, coder, verifier, note) "
                                       + "VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id";

    /// Per item: its latest event by (at, id), and the start of the latest run of events in one phase, which is the run
    /// the latest event belongs to (`lag` is NULL on the first event, so it always starts a run).
    private static final String BOARD = "SELECT i.id, i.item, i.title, i.submitted_at, e.at, e.phase, e.round, e.coder, "
                                      + "e.verifier, e.note, r.phase_since FROM work_item i "
                                      + "JOIN LATERAL (SELECT x.at, x.phase, x.round, x.coder, x.verifier, x.note "
                                      + "FROM work_event x WHERE x.item_id = i.id ORDER BY x.at DESC, x.id DESC LIMIT 1) e ON true "
                                      + "JOIN LATERAL (SELECT max(s.at) AS phase_since FROM (SELECT y.at, y.phase, "
                                      + "lag(y.phase) OVER (ORDER BY y.at, y.id) AS previous FROM work_event y "
                                      + "WHERE y.item_id = i.id) s WHERE s.previous IS DISTINCT FROM s.phase) r ON true "
                                      + "ORDER BY i.submitted_at, i.id";

    @Override
    public Promise<Long> record(WorkEvent event) {
        return db.inTransaction(tx -> recordIn(tx, event));
    }

    @Override
    public Promise<WorkBoard> board() {
        return Sql.query(db.executor(),
                         BOARD)
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgWorkStore::card))
                  .map(WorkBoard::new);
    }

    private static Promise<Long> recordIn(Transaction tx, WorkEvent event) {
        return Sql.execute(tx,
                           UPSERT_ITEM,
                           event.id(),
                           event.item(),
                           Sql.nullable(event.title()),
                           Sql.utc(event.at()))
                  .flatMap(_ -> Sql.query(tx,
                                          APPEND,
                                          event.id(),
                                          Sql.utc(event.at()),
                                          event.phase().label(),
                                          event.round(),
                                          Sql.nullable(event.coder()),
                                          Sql.nullable(event.verifier()),
                                          Sql.nullable(event.note())))
                  .flatMap(PgWorkStore::eventId);
    }

    /// `RETURNING id` answers one row; none would be a driver fault, never an id of 0.
    private static Promise<Long> eventId(PgResultSet resultSet) {
        return Sql.first(resultSet)
                  .map(row -> row.getLong("id"))
                  .toResult(Rows.corrupt(TABLE).apply(Rows.NULL_COLUMN.apply("id")))
                  .async();
    }

    private static Result<WorkCard> card(PgRow row) {
        return Rows.required(row, "phase")
                   .flatMap(PgWorkStore::phase)
                   .map(phase -> new WorkCard(row.getString("id"),
                                              row.getString("item"),
                                              Rows.text(row, "title"),
                                              row.getInstant("submitted_at"),
                                              phase,
                                              row.getInt("round"),
                                              Rows.text(row, "coder"),
                                              Rows.text(row, "verifier"),
                                              Rows.text(row, "note"),
                                              row.getInstant("at"),
                                              row.getInstant("phase_since")))
                   .mapError(Rows.corrupt(TABLE));
    }

    private static Result<WorkPhase> phase(String label) {
        return WorkPhase.workPhase(label).toResult(Rows.UNKNOWN_VALUE.apply(label));
    }
}
