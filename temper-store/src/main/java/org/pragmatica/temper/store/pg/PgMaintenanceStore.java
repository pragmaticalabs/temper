package org.pragmatica.temper.store.pg;

import java.nio.file.Path;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.temper.store.api.MaintenanceKind;
import org.pragmatica.temper.store.api.MaintenanceRun;
import org.pragmatica.temper.store.api.MaintenanceStore;
import org.pragmatica.temper.store.api.StoreError;

import static org.pragmatica.lang.Option.option;


/// [MaintenanceStore] over Postgres.
record PgMaintenanceStore(Db db) implements MaintenanceStore {
    private static final String INSERT = "INSERT INTO maintenance_runs (kind, started_at, ended_at, ok, detail, path, bytes) "
                                       + "VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING id";

    static final String LAST_GOOD = "SELECT kind, started_at, ended_at, ok, detail, path, bytes FROM maintenance_runs "
                                  + "WHERE kind = $1 AND ok ORDER BY ended_at DESC, id DESC LIMIT 1";

    static final String LATEST = "SELECT kind, started_at, ended_at, ok, detail, path, bytes FROM maintenance_runs "
                               + "WHERE kind = $1 ORDER BY ended_at DESC, id DESC LIMIT 1";

    @Override
    public Promise<Long> record(MaintenanceRun run) {
        return Sql.query(db.executor(),
                         INSERT,
                         run.kind().wire(),
                         Sql.utc(run.startedAt()),
                         Sql.utc(run.endedAt()),
                         run.ok(),
                         Sql.nullable(run.detail()),
                         Sql.nullable(run.path().map(Path::toString)),
                         Sql.nullable(run.bytes()))
                  .flatMap(resultSet -> Sql.first(resultSet).async(StoreError.General.NO_ROW_RETURNED))
                  .map(row -> row.getLong("id"));
    }

    @Override
    public Promise<Option<MaintenanceRun>> lastGood(MaintenanceKind kind) {
        return Sql.query(db.executor(),
                         LAST_GOOD,
                         kind.wire())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgMaintenanceStore::run));
    }

    @Override
    public Promise<Option<MaintenanceRun>> latest(MaintenanceKind kind) {
        return Sql.query(db.executor(),
                         LATEST,
                         kind.wire())
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgMaintenanceStore::run));
    }

    static Result<MaintenanceRun> run(PgRow row) {
        return MaintenanceKind.maintenanceKind(row.getString("kind"))
                              .map(kind -> runOf(kind, row))
                              .mapError(Rows.corrupt("maintenance_runs"));
    }

    private static MaintenanceRun runOf(MaintenanceKind kind, PgRow row) {
        var path = Rows.text(row, "path").map(Path::of);

        return MaintenanceRun.maintenanceRun(kind,
                                             row.getInstant("started_at"),
                                             row.getInstant("ended_at"),
                                             row.getBoolean("ok"),
                                             Rows.text(row, "detail"),
                                             path,
                                             option(row.getLong("bytes")));
    }
}
