package org.pragmatica.kiln.service.store.pg;

import java.time.Instant;

import org.pragmatica.kiln.service.store.Cached;
import org.pragmatica.kiln.service.store.EtagStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.postgres.PgRow;


/// [EtagStore] over `gh_etag`: the last 200 answer per URL.
record PgEtagStore(Db db) implements EtagStore {
    private static final String STORE = "INSERT INTO gh_etag (url, etag, body, observed_at) VALUES ($1, $2, $3, $4) "
                                      + "ON CONFLICT (url) DO UPDATE SET etag = EXCLUDED.etag, body = EXCLUDED.body, "
                                      + "observed_at = EXCLUDED.observed_at";

    @Override
    public Promise<Option<Cached>> cached(String url) {
        return Sql.query(db.executor(),
                         "SELECT etag, body, observed_at FROM gh_etag WHERE url = $1",
                         url)
                  .map(resultSet -> Sql.readFirst(resultSet, PgEtagStore::cached));
    }

    @Override
    public Promise<Unit> store(String url, String etag, String body, Instant at) {
        return Sql.execute(db.executor(), STORE, url, etag, body, Sql.utc(at));
    }

    private static Cached cached(PgRow row) {
        return new Cached(row.getString("etag"), row.getString("body"), row.getInstant("observed_at"));
    }
}
