package org.pragmatica.temper.store.pg;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.HostStore;
import org.pragmatica.temper.store.api.StoreError;


/// [HostStore] over Postgres. The upsert writes every configured column, so a restart with an edited `sharedCpu` or
/// `retireAt` (or one removed: false, NULL) takes effect at once (m4-design §10, F8).
record PgHostStore(Db db) implements HostStore {
    @Override
    public Promise<Unit> upsert(HostConfig h) {
        return Sql.execute(db.executor(),
                           "INSERT INTO hosts (name, address, host_key, cap, memory_budget_mb, port_isolated, shared_cpu, retire_at) "
                          + "VALUES ($1, $2, $3, $4, $5, $6, $7, $8) ON CONFLICT (name) DO UPDATE SET address = EXCLUDED.address, "
                          + "host_key = EXCLUDED.host_key, cap = EXCLUDED.cap, memory_budget_mb = EXCLUDED.memory_budget_mb, "
                          + "port_isolated = EXCLUDED.port_isolated, shared_cpu = EXCLUDED.shared_cpu, retire_at = EXCLUDED.retire_at",
                           h.name().value(),
                           h.address(),
                           Sql.nullable(h.hostKey()),
                           h.cap(),
                           h.memoryBudgetMb(),
                           h.portIsolated(),
                           h.sharedCpu(),
                           Sql.nullable(h.retireAt().map(Sql::utc)));
    }

    @Override
    public Promise<Unit> state(HostName h, String state) {
        return Sql.update(db.executor(),
                          "UPDATE hosts SET state = CASE WHEN state = 'LOST' THEN state ELSE $2 END WHERE name = $1",
                          h.value(),
                          state)
                  .flatMap(changed -> found(h, changed));
    }

    /// The guard is `cleared_lost_at` (V12): a row before its first loss and a row after recovery are otherwise the
    /// same (not LOST, no mark), so only the remembered mark tells a late write from a new loss.
    @Override
    public Promise<Unit> lost(HostName h, HostLoss loss) {
        return Sql.update(db.executor(),
                          "UPDATE hosts SET state = 'LOST', lost_at = COALESCE(lost_at, $2), "
                         + "lost_reason = COALESCE(lost_reason, $3) WHERE name = $1 "
                         + "AND (cleared_lost_at IS NULL OR cleared_lost_at < $2)",
                          h.value(),
                          Sql.utc(loss.at()),
                          loss.reason())
                  .flatMap(changed -> marked(h, changed));
    }

    @Override
    public Promise<Unit> recovered(HostName h, HostLoss seen) {
        return Sql.update(db.executor(),
                          "UPDATE hosts SET state = 'UP', cleared_lost_at = lost_at, lost_at = NULL, lost_reason = NULL "
                         + "WHERE name = $1 AND lost_at = $2",
                          h.value(),
                          Sql.utc(seen.at()))
                  .flatMap(changed -> marked(h, changed));
    }

    /// No row changed: the host is unknown, or the mark write was refused as stale.
    private Promise<Unit> marked(HostName h, int changed) {
        return changed > 0
               ? Promise.unitPromise()
               : Sql.query(db.executor(),
                           "SELECT count(*) AS n FROM hosts WHERE name = $1",
                           h.value())
                    .flatMap(resultSet -> Sql.parseFirst(resultSet,
                                                         row -> Result.success(row.getLong("n"))))
                    .flatMap(n -> n.filter(count -> count > 0)
                                   .map(_ -> StoreError.StaleLoss.FACTORY.apply(h).<Unit> promise())
                                   .or(() -> StoreError.HostNotFound.FACTORY.apply(h).promise()));
    }

    @Override
    public Promise<Option<HostLoss>> loss(HostName h) {
        return Sql.query(db.executor(),
                         "SELECT lost_at, lost_reason FROM hosts WHERE name = $1 AND state = 'LOST'",
                         h.value())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgHostStore::hostLoss));
    }

    private static Result<HostLoss> hostLoss(PgRow row) {
        return Result.success(HostLoss.hostLoss(row.getInstant("lost_at"), row.getString("lost_reason")));
    }

    @Override
    public Promise<Unit> facts(HostName h, HostFacts f) {
        return Json.write(HostFactsDto.hostFactsDto(f))
                   .async()
                   .flatMap(json -> Sql.update(db.executor(),
                                               "UPDATE hosts SET facts = $2::jsonb, facts_at = $3 WHERE name = $1",
                                               h.value(),
                                               json,
                                               Sql.utc(f.at())))
                   .flatMap(changed -> found(h, changed));
    }

    private static Promise<Unit> found(HostName h, int changed) {
        return changed > 0
               ? Promise.unitPromise()
               : StoreError.HostNotFound.FACTORY.apply(h).promise();
    }
}
