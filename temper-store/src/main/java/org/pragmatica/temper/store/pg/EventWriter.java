package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.store.api.StoreError;


/// The only event writer. Each insert first takes `pg_advisory_xact_lock(70700001)` in the caller's transaction,
/// so `seq` allocation and commit happen inside one serialised section: commit order equals `seq` order, and a
/// reader that saw `seq = n` can never later see a committed `seq < n` it skipped (m0-design §5.3).
///
/// Events are appended LAST in every transaction: once a transaction holds the order lock it takes no row lock
/// another event writer could hold, which keeps the lock graph acyclic.
sealed interface EventWriter {
    long ORDER_LOCK = 70700001L;

    static Promise<Long> append(QueryExecutor tx, PendingEvent event) {
        return Sql.execute(tx, "SELECT pg_advisory_xact_lock($1)", ORDER_LOCK).flatMap(_ -> insert(tx, event));
    }

    static Promise<Unit> appendAll(QueryExecutor tx, List<PendingEvent> events) {
        return Sql.sequence(events, event -> append(tx, event)).mapToUnit();
    }

    private static Promise<Long> insert(QueryExecutor tx, PendingEvent event) {
        return Json.write(event.payload())
                   .async()
                   .flatMap(payload -> Sql.query(tx,
                                                 "INSERT INTO events (job_id, kind, payload) VALUES ($1, $2, $3::jsonb) RETURNING seq",
                                                 Sql.nullable(event.job().map(JobId::value)),
                                                 WireRecords.eventKind(event.kind()).wire(),
                                                 payload))
                   .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                   .map(row -> row.getLong("seq"));
    }

    record unused() implements EventWriter {}
}
