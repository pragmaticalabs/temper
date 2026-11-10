package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.core.source.SourceStatus;
import org.pragmatica.kiln.service.store.SourceStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// [SourceStore] over `source_state`: one row per source key. Only [SourceState.Ok] sets `last_success_at`; every other
/// state keeps the stored one, so a failing source still shows its last success (spec §6 panel 1).
record PgSourceStore(Db db) implements SourceStore {
    private static final String TABLE = "source_state";

    private static final String RECORD = "INSERT INTO source_state (source, state, detail, since, last_success_at, updated_at) "
                                       + "VALUES ($1, $2, $3, $4, $5, $4) ON CONFLICT (source) DO UPDATE SET state = EXCLUDED.state, "
                                       + "detail = EXCLUDED.detail, since = EXCLUDED.since, "
                                       + "last_success_at = coalesce(EXCLUDED.last_success_at, source_state.last_success_at), "
                                       + "updated_at = EXCLUDED.updated_at";

    /// A state as its columns: the kind, its one text (a reason, status, field or the reset instant) and its time.
    record Stored(String state, Option<String> detail, Instant since, Option<Instant> success) {}

    @Override
    public Promise<Unit> record(Source source, SourceState state) {
        var stored = stored(state);

        return Sql.execute(db.executor(),
                           RECORD,
                           source.key(),
                           stored.state(),
                           Sql.nullable(stored.detail()),
                           Sql.utc(stored.since()),
                           Sql.nullable(stored.success().map(Sql::utc)));
    }

    @Override
    public Promise<List<SourceStatus>> all() {
        return Sql.query(db.executor(),
                         "SELECT source, state, detail, since, last_success_at FROM source_state ORDER BY source")
                  .flatMap(resultSet -> Sql.parseAll(resultSet, PgSourceStore::status));
    }

    static Stored stored(SourceState state) {
        return switch (state) {
            case SourceState.Ok(var at) -> new Stored("OK", none(), at, some(at));
            case SourceState.Unreachable(var reason, var since) -> new Stored("UNREACHABLE", some(reason), since, none());
            case SourceState.Incomplete(var reason, var at) -> new Stored("INCOMPLETE", some(reason), at, none());
            case SourceState.RateLimited(var reset, var since) -> new Stored("RATE_LIMITED",
                                                                             some(reset.toString()),
                                                                             since,
                                                                             none());
            case SourceState.GithubDown(var status, var since) -> new Stored("GITHUB_DOWN", some(status), since, none());
            case SourceState.TemperDown(var reason, var since) -> new Stored("TEMPER_DOWN", some(reason), since, none());
            case SourceState.TemperSchema(var field, var since) -> new Stored("TEMPER_SCHEMA",
                                                                              some(field),
                                                                              since,
                                                                              none());
        };
    }

    private static Result<SourceStatus> status(PgRow row) {
        return Result.all(Source.source(row.getString("source")),
                          state(row))
                     .map((source, state) -> SourceStatus.sourceStatus(source,
                                                                       state,
                                                                       Rows.instant(row, "last_success_at")))
                     .mapError(Rows.corrupt(TABLE));
    }

    private static Result<SourceState> state(PgRow row) {
        var since = row.getInstant("since");

        return switch (row.getString("state")) {
            case "OK" -> success(new SourceState.Ok(since));
            case "UNREACHABLE" -> Rows.required(row, "detail").map(detail -> new SourceState.Unreachable(detail, since));
            case "INCOMPLETE" -> Rows.required(row, "detail").map(detail -> new SourceState.Incomplete(detail, since));
            case "RATE_LIMITED" -> Rows.required(row, "detail").flatMap(Rows::parseInstant).map(reset -> new SourceState.RateLimited(reset,
                                                                                                                                     since));
            case "GITHUB_DOWN" -> Rows.required(row, "detail").map(detail -> new SourceState.GithubDown(detail, since));
            case "TEMPER_DOWN" -> Rows.required(row, "detail").map(detail -> new SourceState.TemperDown(detail, since));
            case "TEMPER_SCHEMA" -> Rows.required(row, "detail").map(detail -> new SourceState.TemperSchema(detail,
                                                                                                            since));
            case null, default -> Rows.UNKNOWN_VALUE.apply(row.getString("state")).result();
        };
    }
}
