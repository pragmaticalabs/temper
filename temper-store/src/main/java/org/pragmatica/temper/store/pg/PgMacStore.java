package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.postgres.PgRow;
import org.pragmatica.postgres.net.QueryExecutor;
import org.pragmatica.temper.store.api.MacPost;
import org.pragmatica.temper.store.api.MacProcess;
import org.pragmatica.temper.store.api.MacSession;
import org.pragmatica.temper.store.api.MacStore;
import org.pragmatica.temper.store.api.NewMacPost;
import org.pragmatica.temper.store.api.SourceSeen;

import static org.pragmatica.lang.Option.some;


/// [MacStore] over Postgres. `mac_post` is the single row 1; every writing transaction
/// ends with the `mac` source's success, so a post that did not commit never reads as fresh.
record PgMacStore(Db db) implements MacStore {
    private static final TypeToken<List<MacProcess>> PROCESSES = new TypeToken<>() {};

    private static final String POST = "INSERT INTO mac_post (id, received_at, posted_at, processes) VALUES (1, $1::timestamptz, $2::timestamptz, $3::jsonb) "
                                     + "ON CONFLICT (id) DO UPDATE SET received_at = EXCLUDED.received_at, posted_at = EXCLUDED.posted_at, "
                                     + "processes = EXCLUDED.processes WHERE EXCLUDED.received_at >= mac_post.received_at";

    private static final String SESSIONS = "INSERT INTO mac_session (pid, start_s, label, first_seen, last_seen) "
                                         + "SELECT p.pid, p.start, p.label, $1::timestamptz, $1::timestamptz FROM jsonb_to_recordset($2::jsonb) AS p(pid int, start bigint, label text) "
                                         + "ON CONFLICT (pid, start_s) DO UPDATE SET last_seen = GREATEST(mac_session.last_seen, EXCLUDED.last_seen), "
                                         + "label = CASE WHEN EXCLUDED.last_seen >= mac_session.last_seen THEN EXCLUDED.label ELSE mac_session.label END";

    private static final String RETIRE = "DELETE FROM mac_session WHERE last_seen < $1::timestamptz - make_interval(days => $2::int)";

    @Override
    public Promise<Instant> post(NewMacPost post) {
        return Json.write(post.processes())
                   .mapError(Rows.corrupt("mac_post"))
                   .async()
                   .flatMap(json -> db.inTransaction(tx -> posted(tx, post, json)))
                   .map(_ -> post.receivedAt());
    }

    private static Promise<Unit> posted(QueryExecutor tx, NewMacPost post, String json) {
        var now = Sql.utc(post.receivedAt());

        return Sql.execute(tx,
                           POST,
                           now,
                           Sql.utc(post.postedAt()),
                           json)
                  .flatMap(_ -> Sql.execute(tx, SESSIONS, now, json))
                  .flatMap(_ -> Sql.execute(tx, RETIRE, now, RETAIN_DAYS))
                  .flatMap(_ -> PgFleetStore.seenIn(tx,
                                                    List.of(SourceSeen.sourceSeen(SOURCE,
                                                                                  some(post.receivedAt()),
                                                                                  PERIOD_S))));
    }

    @Override
    public Promise<Option<MacPost>> latest() {
        return Sql.query(db.executor(),
                         "SELECT received_at, posted_at, processes::text AS processes FROM mac_post WHERE id = 1")
                  .flatMap(resultSet -> Sql.parseFirst(resultSet, PgMacStore::postRow));
    }

    @Override
    public Promise<List<MacSession>> sessions() {
        return Sql.query(db.executor(),
                         "SELECT pid, start_s, label, first_seen, last_seen FROM mac_session ORDER BY pid, start_s")
                  .map(resultSet -> Sql.rows(resultSet)
                                       .stream()
                                       .map(PgMacStore::sessionRow)
                                       .toList());
    }

    private static Result<MacPost> postRow(PgRow row) {
        return Json.read(row.getString("processes"),
                         PROCESSES)
                   .mapError(Rows.corrupt("mac_post"))
                   .map(processes -> MacPost.macPost(row.getInstant("received_at"),
                                                     row.getInstant("posted_at"),
                                                     processes));
    }

    private static MacSession sessionRow(PgRow row) {
        return MacSession.macSession(row.getInt("pid"),
                                     row.getLong("start_s"),
                                     row.getString("label"),
                                     row.getInstant("first_seen"),
                                     row.getInstant("last_seen"));
    }
}
