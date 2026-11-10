package org.pragmatica.temper.controller.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.t3.MacRoutes;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `POST fleet/mac` end to end over Postgres (M.scope, M.body, T3.4b): the MAC scope, every
/// 400 reason in its order, the one transaction (latest post, sessions, retention, the `mac` source) at temper's own
/// clock, and the `mac` source's staleness announced once.
@Tag("pg")
class MacRoutesIT {
    private static final String FULL = "full-token";
    private static final String READ = "read-token";
    private static final String WORKER = "worker-token";
    private static final String VERDICT = "verdict-token";
    private static final String MAC = "mac-token";
    private static final String NOT_PERMITTED = "{\"error\":\"forbidden: not permitted for this token\"}";
    private static final String READ_ONLY = "{\"error\":\"forbidden: read-only token\"}";
    private static final String POSTED = "2026-11-04T11:59:58Z";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T12:00:00Z"));
    private Store store;
    private String database;
    private Fleet fleet;
    private Router router;
    @TempDir
    Path state;

    @BeforeEach
    void setUp() {
        var named = Pg.freshNamed();
        store = named.store();
        database = named.database();
        fleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(), store.fleet(), ProcessRunner.processRunner(), none(), now::get);
        fleet.settle().await(Pg.WAIT).unwrap();
        router = router(true);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private Router router(boolean ready) {
        var responses = Responses.responses(TemperJson.mapper());

        return Router.router(Credentials.credentials(token(FULL), some(token(READ)), some(token(WORKER)), some(token(VERDICT)), some(token(MAC))),
                             MacRoutes.macRoutes(responses, TemperJson.mapper(), store.mac(), now::get, () -> ready).routes(),
                             responses);
    }

    private static BearerToken token(String raw) {
        return BearerToken.bearerToken(raw).unwrap();
    }

    // ---- scope -------------------------------------------------------------------------------------------------
    /// M.scope: only the MAC token posts; the full token is refused (T2 open question 11), as are READ, WORKER, VERDICT.
    @Test
    void scope_onlyTheMacToken_posts() {
        var body = body(POSTED, process(10, 1793667998L, "CTO"));

        assertThat(call(FULL, body).status().code()).isEqualTo(403);
        assertThat(text(call(FULL, body))).isEqualTo(NOT_PERMITTED);
        assertThat(call(READ, body).status().code()).isEqualTo(403);
        assertThat(text(call(READ, body))).isEqualTo(READ_ONLY);
        assertThat(call(WORKER, body).status().code()).isEqualTo(403);
        assertThat(call(VERDICT, body).status().code()).isEqualTo(403);
        assertThat(sql("SELECT count(*) AS v FROM mac_post")).as("no refused call stored anything").isEqualTo("0");
        assertThat(call(MAC, body).status().code()).isEqualTo(200);
        assertThat(sql("SELECT count(*) AS v FROM mac_post")).isEqualTo("1");
    }

    @Test
    void beforeStart_theRouteAnswers503NotReady() {
        var reply = call(router(false), MAC, body(POSTED));

        assertThat(reply.status().code()).isEqualTo(503);
        assertThat(text(reply)).isEqualTo("{\"error\":\"NOT_READY\"}");
    }

    // ---- body --------------------------------------------------------------------------------------------------
    /// M.body: every reason has its 400 message, and a refused body stores nothing.
    @Test
    void body_everyReason_hasItsMessage_andStoresNothing() {
        var good = process(10, 1793667998L, "CTO");
        var cases = new String[][] {
            {"too-large", "{\"v\":1,\"postedAt\":\"" + POSTED + "\",\"processes\":[],\"pad\":\"" + "x".repeat(65536) + "\"}"},
            {"json", "{not json"},
            {"v", "{\"v\":2,\"postedAt\":\"" + POSTED + "\",\"processes\":[]}"},
            {"v", "{\"postedAt\":\"" + POSTED + "\",\"processes\":[]}"},
            {"v", "{\"v\":\"1\",\"postedAt\":\"" + POSTED + "\",\"processes\":[]}"},
            {"postedAt", "{\"v\":1,\"postedAt\":\"yesterday\",\"processes\":[]}"},
            {"postedAt", "{\"v\":1,\"processes\":[]}"},
            {"processes", "{\"v\":1,\"postedAt\":\"" + POSTED + "\",\"processes\":{}}"},
            {"processes", "{\"v\":1,\"postedAt\":\"" + POSTED + "\"}"},
            {"too-many", "{\"v\":1,\"postedAt\":\"" + POSTED + "\",\"processes\":[" + entries(513) + "]}"},
            {"pid", body(POSTED, process(0, 1793667998L, "a"))},
            {"pid", body(POSTED, process(2147483648L, 1793667998L, "a"))},
            {"pid", body(POSTED, "{\"pid\":\"7\",\"start\":1793667998,\"label\":\"a\"}")},
            {"pid", body(POSTED, "{\"pid\":7.5,\"start\":1793667998,\"label\":\"a\"}")},
            {"start", body(POSTED, process(7, 999999999L, "a"))},
            {"start", body(POSTED, process(7, now.get().getEpochSecond() + 301, "a"))},
            {"start", body(POSTED, "{\"pid\":7,\"label\":\"a\"}")},
            {"label", body(POSTED, process(7, 1793667998L, "a b"))},
            {"label", body(POSTED, process(7, 1793667998L, ""))},
            {"label", body(POSTED, process(7, 1793667998L, "x".repeat(65)))},
            {"label", body(POSTED, "{\"pid\":7,\"start\":1793667998,\"label\":5}")},
            {"duplicate", body(POSTED, process(7, 1793667998L, "a"), process(7, 1793667998L, "b"))},
            {"pid", body(POSTED, good, process(0, 1793667998L, "a"), process(8, 5L, "b"))},
        };

        Arrays.stream(cases).forEach(testCase -> {
            var reply = call(MAC, testCase[1]);

            assertThat(reply.status().code()).as(testCase[0]).isEqualTo(400);
            assertThat(text(reply)).as(testCase[0]).isEqualTo("{\"error\":\"invalid mac post: " + testCase[0] + "\"}");
        });
        assertThat(sql("SELECT (SELECT count(*) FROM mac_post) || '/' || (SELECT count(*) FROM mac_session) AS v")).isEqualTo("0/0");
    }

    /// The edges that are accepted: 512 entries, the largest start (receipt + 300 s), the smallest, a body of exactly
    /// 65536 bytes, unknown fields.
    @Test
    void body_edges_areAccepted() {
        assertThat(call(MAC, "{\"v\":1,\"postedAt\":\"" + POSTED + "\",\"processes\":[" + entries(512) + "]}").status().code()).isEqualTo(200);
        assertThat(call(MAC, body(POSTED, process(7, now.get().getEpochSecond() + 300, "a"), process(8, 1000000000L, "b"))).status().code()).isEqualTo(200);
        var padding = "{\"v\":1,\"postedAt\":\"" + POSTED + "\",\"processes\":[],\"pad\":\"\"}";
        var exact = padding.replace("\"pad\":\"\"", "\"pad\":\"" + "x".repeat(65536 - padding.length()) + "\"");
        assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(65536);
        assertThat(call(MAC, exact).status().code()).isEqualTo(200);
        assertThat(call(MAC, "{\"v\":1,\"postedAt\":\"" + POSTED + "\",\"extra\":[1],\"processes\":[{\"pid\":7,\"start\":1793667998,\"label\":\"a\",\"more\":true}]}")
                       .status().code()).isEqualTo(200);
    }

    /// M.body: an empty `processes` is valid (no session is running) and replaces the latest post.
    @Test
    void emptyProcesses_isAccepted_andReplacesTheLatestPost() {
        call(MAC, body(POSTED, process(10, 1793667998L, "CTO")));
        var reply = call(MAC, body(POSTED));

        assertThat(reply.status().code()).isEqualTo(200);
        assertThat(sql("SELECT jsonb_array_length(processes) AS v FROM mac_post WHERE id = 1")).isEqualTo("0");
        assertThat(sql("SELECT count(*) AS v FROM mac_post")).isEqualTo("1");
        assertThat(sql("SELECT count(*) AS v FROM mac_session")).as("the sessions ever seen stay (DEAD(last seen))").isEqualTo("1");
    }

    // ---- transaction -------------------------------------------------------------------------------------------
    /// F13: `received_at`, `last_seen` and the `mac` source are temper's clock; `posted_at` is the Mac's claim, stored,
    /// never compared. The reply carries the receipt time and the session count.
    @Test
    void accepted_isStoredAtTemperClock_andRepliesWithIt() {
        var reply = call(MAC, body("2020-01-01T00:00:00Z", process(10, 1793667998L, "CTO"), process(11, 1793667999L, "pid:11")));

        assertThat(reply.status().code()).isEqualTo(200);
        assertThat(text(reply)).isEqualTo("{\"receivedAt\":\"2026-11-04T12:00:00Z\",\"sessions\":2}");
        assertThat(sql("SELECT to_char(received_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') || ' ' || to_char(posted_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS v FROM mac_post"))
            .isEqualTo("2026-11-04T12:00:00Z 2020-01-01T00:00:00Z");
        assertThat(sql("SELECT string_agg(pid || ' ' || start_s || ' ' || label || ' ' || (first_seen = last_seen AND last_seen = '2026-11-04T12:00:00Z') , ',' ORDER BY pid) AS v FROM mac_session"))
            .isEqualTo("10 1793667998 CTO true,11 1793667999 pid:11 true");
        var source = store.fleet().source("mac").await(Pg.WAIT).unwrap().unwrap();
        assertThat(source.lastOkAt()).isEqualTo(some(now.get()));
        assertThat(source.periodS()).isEqualTo(60);
    }

    /// A second post replaces `mac_post`, refreshes `last_seen` and `label` and keeps `first_seen`; a reused pid with
    /// another start is another session (H5).
    @Test
    void secondPost_refreshesSessions_keepsFirstSeen_andAReusedPidIsAnotherSession() {
        call(MAC, body(POSTED, process(10, 1793667998L, "pid:10")));
        now.set(now.get().plusSeconds(60));
        call(MAC, body(POSTED, process(10, 1793667998L, "CTO"), process(10, 1793668100L, "oss")));

        assertThat(sql("SELECT string_agg(pid || ' ' || start_s || ' ' || label || ' ' || to_char(first_seen AT TIME ZONE 'UTC', 'HH24:MI:SS') || ' ' || to_char(last_seen AT TIME ZONE 'UTC', 'HH24:MI:SS'), ',' ORDER BY start_s) AS v FROM mac_session"))
            .isEqualTo("10 1793667998 CTO 12:00:00 12:01:00,10 1793668100 oss 12:01:00 12:01:00");
        assertThat(sql("SELECT jsonb_array_length(processes) AS v FROM mac_post")).isEqualTo("2");
    }

    /// Rows not seen for 30 days go in the post transaction; 29 days stay.
    @Test
    void post_deletesSessionsNotSeenFor30Days() {
        sql("INSERT INTO mac_session (pid, start_s, label, first_seen, last_seen) VALUES "
            + "(1, 1700000001, 'old', '2026-09-01T00:00:00Z', '2026-10-05T11:59:59Z'), "
            + "(2, 1700000002, 'kept', '2026-09-01T00:00:00Z', '2026-10-05T12:00:01Z')");

        call(MAC, body(POSTED));

        assertThat(sql("SELECT string_agg(label, ',' ORDER BY pid) AS v FROM mac_session")).isEqualTo("kept");
    }

    // ---- the mac source ----------------------------------------------------------------------------------------
    /// T3.4b: `mac` is an expected source from the start (never posted reads stale), a post makes it fresh, and the
    /// Mac job stopping ages it past 3 x 60 s: `source-stale mac` once, `source-recovered mac` once.
    @Test
    void macSource_isExpectedAtSettle_andStaleAfter180s_announcedOnce() {
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        assertThat(macEvents()).as("never posted: stale, once").containsExactly("source-stale");

        call(MAC, body(POSTED));
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        assertThat(macEvents()).containsExactly("source-stale", "source-recovered");

        now.set(now.get().plusSeconds(180));
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        assertThat(macEvents()).as("180 s is still fresh").hasSize(2);
        now.set(now.get().plusSeconds(1));
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        assertThat(macEvents()).containsExactly("source-stale", "source-recovered", "source-stale");
        assertThat(store.fleet().source("mac").await(Pg.WAIT).unwrap().unwrap().stale()).isTrue();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------
    private List<String> macEvents() {
        return switch (store.fleet().pending(0, 500).await(Pg.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows()
                                                     .stream()
                                                     .filter(row -> "mac".equals(row.subject()))
                                                     .map(FleetEventRow::kind)
                                                     .toList();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    private static String process(long pid, long start, String label) {
        return "{\"pid\":" + pid + ",\"start\":" + start + ",\"label\":\"" + label + "\"}";
    }

    private static String entries(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(pid -> process(pid, 1793667998L, "p")).collect(Collectors.joining(","));
    }

    private static String body(String postedAt, String... processes) {
        return "{\"v\":1,\"postedAt\":\"" + postedAt + "\",\"processes\":[" + String.join(",", processes) + "]}";
    }

    private String sql(String statement) {
        return Pg.sql(database, statement);
    }

    private ApiResponse call(String token, String body) {
        return call(router, token, body);
    }

    private ApiResponse call(Router via, String token, String body) {
        return call(via, HttpMethod.POST, "/api/v1/fleet/mac", body, token);
    }

    private static ApiResponse call(Router via, HttpMethod method, String path, String body, String token) {
        return via.handle(ApiRequest.apiRequest(request(method, path, body, Map.of("Authorization", "Bearer " + token))))
                  .await(Pg.WAIT)
                  .unwrap();
    }

    private static String text(ApiResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static HttpRequest request(HttpMethod method, String path, String body, Map<String, String> headers) {
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "test";
            }

            @Override
            public HttpMethod method() {
                return method;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public Headers headers() {
                return Headers.fromSingleValueMap(headers);
            }

            @Override
            public QueryParams queryParams() {
                return QueryParams.empty();
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
    }
}
