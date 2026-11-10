package org.pragmatica.temper.controller.it;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.t2.KilnPull;
import org.pragmatica.temper.controller.t2.T2Routes;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.Store;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The WP-A1 routes end to end over Postgres: verdicts (R3-3.a `verdict-changed`,
/// G6.i `CARRY_NO_ACCEPT`), the serial queue (dense positions under the T2 lock, the optional `base`, kiln's R1 read
/// for an `add` without one) and G9's control paths, through the real router with the verdict token. GO rows are
/// inserted directly (their routes are WP-A2's).
@Tag("pg")
class T2RoutesIT {
    private static final String FULL = "full-token";
    private static final String READ = "read-token";
    private static final String VERDICT = "verdict-token";
    private static final String X = "a".repeat(40);
    private static final String Y = "b".repeat(40);
    private static final String TIP = "c".repeat(40);
    private static final String DIGEST = "d".repeat(64);
    private static final String OTHER_DIGEST = "e".repeat(64);
    private static final String ISO = "^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$";
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
        router = router(none(), true);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private Router router(Option<KilnPull> kiln, boolean ready) {
        var responses = Responses.responses(TemperJson.mapper());

        return Router.router(Credentials.credentials(token(FULL), some(token(READ)), none(), some(token(VERDICT)), none()),
                             T2Routes.t2Routes(responses, TemperJson.mapper(), store.t2(), fleet.writer(), kiln, now::get, () -> ready)
                                     .routes(),
                             responses);
    }

    private static BearerToken token(String raw) {
        return BearerToken.bearerToken(raw).unwrap();
    }

    // ---- verdicts ----------------------------------------------------------------------------------------------
    @Test
    void verdict_reported_isRegistered_withItsVerdictRegisteredEvent() {
        var reply = post("/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "main", DIGEST));

        assertThat(reply.status().code()).isEqualTo(201);
        var body = json(text(reply));
        assertThat(body.path("at").stringValue()).matches(ISO);
        assertThat(body.path("reportSha256").stringValue()).isEqualTo(DIGEST);
        assertThat(body.path("invalidated").isArray()).isTrue();
        assertThat(body.path("invalidated").size()).isZero();
        var id = body.path("id").longValue();
        assertThat(sql("SELECT repo || ' ' || pr || ' ' || sha || ' ' || base || ' ' || verdict || ' ' || declared_by || ' ' || report_path || ' '"
                       + " || report_sha256 || ' ' || coalesce(carried_from, '-') AS v FROM verdicts WHERE id = " + id))
            .isEqualTo("o/r 7 " + X + " main ACCEPT agent /r/report.md " + DIGEST + " -");
        assertThat(t2Rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("verdict-registered");
            assertThat(row.subject()).isEqualTo("o/r#7@" + X);
            assertThat(row.source()).isEqualTo("controller");
            assertThat(row.catchUp()).isFalse();
            assertThat(json(row.payload())).isEqualTo(json("{\"verdict\":\"ACCEPT\",\"by\":\"agent\",\"base\":\"main\",\"id\":" + id
                                                           + ",\"reportSha256\":\"" + DIGEST + "\"}"));
        });
    }

    /// R3-3.a: a FIX registered after a GO invalidates every outstanding GO of the PR (any SHA, Q2) with
    /// `verdict-changed` in the same transaction, one `go-invalidated` each after `verdict-registered`; a consumed, an
    /// expired and another PR's GO are untouched.
    @Test
    void r33a_fixAfterAGo_invalidatesThePrsOutstandingGos_verdictChanged() {
        var open = go(7, X, "now()");
        var openOtherSha = go(7, Y, "now() - interval '1 minute'");
        var otherPr = go(8, X, "now()");
        var expired = go(7, X, "now() - interval '11 minutes'");
        var consumed = go(7, X, "now()");
        sql("UPDATE go SET consumed_at = now(), outcome = 'consumed', outcome_at = now() WHERE id = '" + consumed + "'");

        var reply = post("/api/v1/fleet/verdicts", reported(7, X, "FIX", "main", OTHER_DIGEST));

        assertThat(reply.status().code()).isEqualTo(201);
        var invalidated = json(text(reply)).path("invalidated");
        assertThat(List.of(invalidated.get(0).stringValue(), invalidated.get(1).stringValue())).containsExactly(openOtherSha, open);
        assertThat(invalidated.size()).isEqualTo(2);
        assertThat(sql("SELECT string_agg(id::text || ' ' || coalesce(reason, '-') || ' ' || (invalidated_at IS NOT NULL), ',' ORDER BY id) AS v FROM go"))
            .isEqualTo(Arrays.stream(new String[] {open + " verdict-changed true", openOtherSha + " verdict-changed true", otherPr + " - false",
                                                   expired + " - false", consumed + " - false"})
                             .sorted()
                             .collect(Collectors.joining(",")));
        assertThat(t2Rows()).extracting(FleetEventRow::kind).containsExactly("verdict-registered", "go-invalidated", "go-invalidated");
        assertThat(t2Rows().get(1).subject()).isEqualTo("o/r#7");
        assertThat(json(t2Rows().get(1).payload())).isEqualTo(json("{\"go\":\"" + openOtherSha + "\",\"sha\":\"" + Y
                                                                   + "\",\"base\":\"main\",\"reason\":\"verdict-changed\"}"));
        assertThat(json(t2Rows().get(2).payload()).path("go").stringValue()).isEqualTo(open);
        assertThat(json(t2Rows().get(2).payload()).has("causeSeq")).isFalse();
    }

    /// G6.i: a carry needs the latest row at the carried SHA to be ACCEPT at the same base: none, a later FIX, or an
    /// ACCEPT at another base → 422 `CARRY_NO_ACCEPT`, nothing written; with one, the row copies its report fields.
    @Test
    void g6i_carry_withoutAnAcceptAtTheCarriedSha_is422CarryNoAccept() {
        assertCarryRefused();

        post("/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "release", DIGEST));
        assertCarryRefused();

        var accepted = json(text(post("/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "main", DIGEST)))).path("id").longValue();
        post("/api/v1/fleet/verdicts", reported(7, X, "FIX", "main", OTHER_DIGEST));
        assertCarryRefused();

        var reaccepted = json(text(post("/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "main", DIGEST)))).path("id").longValue();
        var reply = post("/api/v1/fleet/verdicts", carry(7, Y, X));

        assertThat(reply.status().code()).isEqualTo(201);
        var id = json(text(reply)).path("id").longValue();
        assertThat(json(text(reply)).path("reportSha256").stringValue()).isEqualTo(DIGEST);
        assertThat(sql("SELECT verdict || ' ' || sha || ' ' || carried_from || ' ' || carried_verdict_id || ' ' || report_path || ' ' || report_sha256 AS v "
                       + "FROM verdicts WHERE id = " + id))
            .isEqualTo("ACCEPT " + Y + " " + X + " " + reaccepted + " /r/report.md " + DIGEST);
        assertThat(accepted).isLessThan(reaccepted);
        assertThat(json(t2Rows().getLast().payload()).path("carriedFrom").stringValue()).isEqualTo(X);
    }

    private void assertCarryRefused() {
        var rows = sql("SELECT count(*) AS v FROM verdicts");
        var events = t2Rows().size();

        var reply = post("/api/v1/fleet/verdicts", carry(7, Y, X));

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).isEqualTo("{\"error\":\"CARRY_NO_ACCEPT\"}");
        assertThat(sql("SELECT count(*) AS v FROM verdicts")).isEqualTo(rows);
        assertThat(t2Rows()).hasSize(events);
    }

    @Test
    void verdict_lineMismatch_is422_andWritesNothing() {
        var reply = post("/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "main", DIGEST).replace("@" + X + " base", "@" + Y + " base"));

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).isEqualTo("{\"error\":\"VERDICT_LINE_MISMATCH\"}");
        assertThat(sql("SELECT count(*) AS v FROM verdicts")).isEqualTo("0");
        assertThat(t2Rows()).isEmpty();
    }

    /// Verifier FIX on WP-A1: a control character in `reportPath` (NUL, which Postgres `text` refuses, a newline, TAB, DEL) is
    /// 400 naming `reportPath`, and nothing is stored.
    @Test
    void verdict_reportPathWithAControlCharacter_is400_andWritesNothing() {
        for (var control : List.of("\\u0000", "\\n", "\\t", "\\u007f")) {
            var reply = post("/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "main", DIGEST).replace("/r/report.md", "/r/re" + control + "port.md"));

            assertThat(reply.status().code()).as(control).isEqualTo(400);
            assertThat(text(reply)).as(control).contains("reportPath");
        }
        assertThat(sql("SELECT count(*) AS v FROM verdicts")).isEqualTo("0");
        assertThat(t2Rows()).isEmpty();
    }

    /// T.full on the real routes: the full token is refused 403 on a verdict and a queue edit; the routes answer
    /// 503 `NOT_READY` before the controller's start completed.
    @Test
    void fullToken_is403_andNotReady_is503() {
        assertThat(call(router, HttpMethod.POST, "/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "main", DIGEST), FULL).status().code()).isEqualTo(403);
        assertThat(call(router, HttpMethod.POST, "/api/v1/fleet/merge-queue", add(7, "main"), FULL).status().code()).isEqualTo(403);
        assertThat(call(router(none(), false), HttpMethod.POST, "/api/v1/fleet/verdicts", reported(7, X, "ACCEPT", "main", DIGEST), VERDICT)
                       .status().code()).isEqualTo(503);
        assertThat(sql("SELECT count(*) AS v FROM verdicts")).isEqualTo("0");
    }

    // ---- merge queue -------------------------------------------------------------------------------------------
    @Test
    void queue_addMoveRemove_keepPositionsDense() {
        assertThat(text(post("/api/v1/fleet/merge-queue", add(7, "main")))).isEqualTo("{\"repo\":\"o/r\",\"base\":\"main\",\"pr\":7,\"position\":1}");
        assertThat(post("/api/v1/fleet/merge-queue", add(8, "main")).status().code()).isEqualTo(201);
        assertThat(json(text(post("/api/v1/fleet/merge-queue", add(9, "main")))).path("position").intValue()).isEqualTo(3);
        assertThat(json(text(post("/api/v1/fleet/merge-queue", add(10, "other")))).path("position").intValue()).isEqualTo(1);

        var again = post("/api/v1/fleet/merge-queue", add(7, "other"));
        assertThat(again.status().code()).isEqualTo(409);
        assertThat(text(again)).isEqualTo("{\"error\":\"ALREADY_QUEUED main 1\"}");

        assertThat(text(post("/api/v1/fleet/merge-queue/move", move(9, 1, "")))).isEqualTo("{\"repo\":\"o/r\",\"base\":\"main\",\"pr\":9,\"position\":1}");
        assertThat(order("main")).isEqualTo("1:9,2:7,3:8");
        post("/api/v1/fleet/merge-queue/move", move(9, 3, ",\"base\":\"main\""));
        assertThat(order("main")).isEqualTo("1:7,2:8,3:9");
        post("/api/v1/fleet/merge-queue/move", move(8, 2, ""));
        assertThat(order("main")).isEqualTo("1:7,2:8,3:9");
        post("/api/v1/fleet/merge-queue/move", move(7, 2, ""));
        assertThat(order("main")).isEqualTo("1:8,2:7,3:9");

        for (var to : List.of(0, 4, -1)) {
            var out = post("/api/v1/fleet/merge-queue/move", move(9, to, ""));
            assertThat(out.status().code()).as("to " + to).isEqualTo(422);
            assertThat(text(out)).isEqualTo("{\"error\":\"POSITION_OUT_OF_RANGE\"}");
        }
        assertThat(post("/api/v1/fleet/merge-queue/move", "{\"repo\":\"o/r\",\"pr\":9}").status().code()).isEqualTo(400);
        assertNotQueued(post("/api/v1/fleet/merge-queue/move", move(8, 1, ",\"base\":\"other\"")));
        assertNotQueued(post("/api/v1/fleet/merge-queue/move", move(99, 1, "")));
        assertNotQueued(post("/api/v1/fleet/merge-queue/remove", "{\"repo\":\"o/r\",\"pr\":8,\"base\":\"other\"}"));
        assertNotQueued(post("/api/v1/fleet/merge-queue/remove", "{\"repo\":\"o/r\",\"pr\":99}"));
        assertThat(order("main")).isEqualTo("1:8,2:7,3:9");

        assertThat(text(post("/api/v1/fleet/merge-queue/remove", "{\"repo\":\"o/r\",\"pr\":8}")))
            .isEqualTo("{\"repo\":\"o/r\",\"base\":\"main\",\"pr\":8,\"position\":1}");
        assertThat(order("main")).isEqualTo("1:7,2:9");
        post("/api/v1/fleet/merge-queue/remove", "{\"repo\":\"o/r\",\"pr\":9,\"base\":\"main\"}");
        assertThat(order("main")).isEqualTo("1:7");
        assertThat(order("other")).isEqualTo("1:10");
    }

    private static void assertNotQueued(ApiResponse reply) {
        assertThat(reply.status().code()).isEqualTo(404);
        assertThat(text(reply)).isEqualTo("{\"error\":\"NOT_QUEUED\"}");
    }

    /// Every queue edit takes the T2 lock: concurrent adds to one queue get distinct dense positions.
    @Test
    void queue_concurrentAdds_getDensePositions() {
        var replies = Promise.allOf(IntStream.rangeClosed(1, 12)
                                             .mapToObj(pr -> router.handle(ApiRequest.apiRequest(request(HttpMethod.POST,
                                                                                                         "/api/v1/fleet/merge-queue",
                                                                                                         add(pr, "main"),
                                                                                                         Map.of("Authorization", "Bearer " + VERDICT)))))
                                             .toList())
                             .await(Pg.WAIT)
                             .unwrap();

        assertThat(replies).allSatisfy(reply -> assertThat(reply.unwrap().status().code()).isEqualTo(201));
        assertThat(sql("SELECT string_agg(position::text, ',' ORDER BY position) AS v FROM merge_queue"))
            .isEqualTo(IntStream.rangeClosed(1, 12).mapToObj(Integer::toString).collect(Collectors.joining(",")));
    }

    /// The view: one queue per (repo, base) ordered by position, the filters, and the newest outstanding GO of each PR.
    @Test
    void queue_view_listsQueues_withTheNewestOutstandingGo() {
        post("/api/v1/fleet/merge-queue", add(7, "main"));
        post("/api/v1/fleet/merge-queue", add(8, "main"));
        post("/api/v1/fleet/merge-queue", add(9, "other"));
        go(7, X, "now() - interval '2 minutes'");
        var newest = go(7, Y, "now() - interval '1 minute'");
        go(8, X, "now() - interval '11 minutes'");

        var view = json(text(get("/api/v1/fleet/merge-queue", READ)));

        assertThat(view.path("queues").size()).isEqualTo(2);
        var main = view.path("queues").get(0);
        assertThat(main.path("repo").stringValue() + " " + main.path("base").stringValue()).isEqualTo("o/r main");
        var first = main.path("entries").get(0);
        assertThat(first.path("position").intValue() + ":" + first.path("pr").longValue() + ":" + first.path("addedBy").stringValue()).isEqualTo("1:7:agent");
        assertThat(first.path("addedAt").stringValue()).matches(ISO);
        assertThat(first.path("go").path("id").stringValue()).isEqualTo(newest);
        assertThat(first.path("go").path("sha").stringValue()).isEqualTo(Y);
        assertThat(Instant.parse(first.path("go").path("expiresAt").stringValue()))
            .isEqualTo(Instant.parse(sql("SELECT to_char(date_trunc('second', created_at + interval '10 minutes') AT TIME ZONE 'UTC', "
                                         + "'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') AS v FROM go WHERE id = '" + newest + "'")));
        assertThat(main.path("entries").get(1).path("go").isNull()).as("an expired GO is not outstanding").isTrue();
        assertThat(view.path("queues").get(1).path("base").stringValue()).isEqualTo("other");

        assertThat(json(text(get("/api/v1/fleet/merge-queue?base=other", READ))).path("queues").size()).isEqualTo(1);
        assertThat(json(text(get("/api/v1/fleet/merge-queue?repo=o/q", READ))).path("queues").size()).isZero();
        assertThat(json(text(get("/api/v1/fleet/merge-queue?repo=o/r&base=main", READ))).path("queues").get(0).path("entries").size()).isEqualTo(2);
    }

    /// An `add` without `base` takes `pull.base` from kiln's `pr-fresh` (R1 only); a PR not open is 422
    /// `NOT_OPEN`; kiln failing, or none configured, is 503.
    @Test
    void queue_addWithoutBase_asksKiln() {
        var asked = new AtomicReference<String>("");
        KilnPull kiln = (repo, pr) -> {
            asked.set(repo + "#" + pr);
            return switch ((int) pr) {
                case 7 -> Promise.success(KilnPull.PullState.pullState("open", "release-1"));
                case 8 -> Promise.success(KilnPull.PullState.pullState("merged", "main"));
                default -> Causes.cause("kiln answered 503").promise();
            };
        };
        var withKiln = router(some(kiln), true);

        var added = call(withKiln, HttpMethod.POST, "/api/v1/fleet/merge-queue", "{\"repo\":\"o/r\",\"pr\":7,\"addedBy\":\"agent\"}", VERDICT);
        assertThat(added.status().code()).isEqualTo(201);
        assertThat(json(text(added)).path("base").stringValue()).isEqualTo("release-1");
        assertThat(asked.get()).isEqualTo("o/r#7");

        var merged = call(withKiln, HttpMethod.POST, "/api/v1/fleet/merge-queue", "{\"repo\":\"o/r\",\"pr\":8,\"addedBy\":\"agent\"}", VERDICT);
        assertThat(merged.status().code()).isEqualTo(422);
        assertThat(text(merged)).isEqualTo("{\"error\":\"NOT_OPEN\"}");
        assertThat(call(withKiln, HttpMethod.POST, "/api/v1/fleet/merge-queue", "{\"repo\":\"o/r\",\"pr\":9,\"addedBy\":\"agent\"}", VERDICT)
                       .status().code()).isEqualTo(503);
        assertThat(post("/api/v1/fleet/merge-queue", "{\"repo\":\"o/r\",\"pr\":9,\"addedBy\":\"agent\"}").status().code()).isEqualTo(503);
        assertThat(order("release-1")).isEqualTo("1:7");
        assertThat(sql("SELECT count(*) AS v FROM merge_queue")).isEqualTo("1");
    }

    /// Lead ruling 2026-11-04 on guess 1: over the real [KilnPull] client, a kiln 404 on an `add` without `base` is the
    /// repo kiln does not configure (the kiln-404 rule), answered 404 `repo not configured`; every other kiln failure (a 503,
    /// an undecodable 200, an unreachable kiln) is 503. Nothing is queued either way.
    @Test
    void queue_addWithoutBase_kiln404IsRepoNotConfigured_otherKilnFailuresAre503() throws Exception {
        var status = new AtomicReference<Integer>(404);
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/pr-fresh", exchange -> {
            var bytes = (status.get() == 404 ? "{\"error\":\"repo not configured\"}" : "not json").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        try {
            var kiln = KilnPull.http(URI.create("http://127.0.0.1:" + stub.getAddress().getPort()), TemperJson.mapper(), Duration.ofSeconds(5));
            var via = router(some(kiln), true);
            var body = "{\"repo\":\"o/r\",\"pr\":7,\"addedBy\":\"agent\"}";

            var missing = call(via, HttpMethod.POST, "/api/v1/fleet/merge-queue", body, VERDICT);
            assertThat(missing.status().code()).isEqualTo(404);
            assertThat(text(missing)).isEqualTo("{\"error\":\"repo not configured\"}");

            status.set(503);
            assertThat(call(via, HttpMethod.POST, "/api/v1/fleet/merge-queue", body, VERDICT).status().code()).isEqualTo(503);
            status.set(200);
            assertThat(call(via, HttpMethod.POST, "/api/v1/fleet/merge-queue", body, VERDICT).status().code()).as("undecodable 200").isEqualTo(503);
        } finally {
            stub.stop(0);
        }
        var gone = KilnPull.http(URI.create("http://127.0.0.1:" + stub.getAddress().getPort()), TemperJson.mapper(), Duration.ofSeconds(5));
        assertThat(call(router(some(gone), true), HttpMethod.POST, "/api/v1/fleet/merge-queue", "{\"repo\":\"o/r\",\"pr\":7,\"addedBy\":\"agent\"}", VERDICT)
                       .status().code()).as("unreachable kiln").isEqualTo(503);
        assertThat(sql("SELECT count(*) AS v FROM merge_queue")).isEqualTo("0");
    }

    /// Verifier FIX on WP-A1: the `pull.base` kiln answers is held to the body `base`'s pattern; a base outside it (a
    /// space, an empty one, 256 characters) is kiln's bad payload, 503, and nothing is queued.
    @Test
    void queue_addWithoutBase_kilnBaseOutsideThePattern_is503_andQueuesNothing() {
        var bases = List.of("has space", "", "b".repeat(256));
        KilnPull kiln = (repo, pr) -> Promise.success(KilnPull.PullState.pullState("open", bases.get((int) pr - 1)));
        var withKiln = router(some(kiln), true);

        for (var pr : List.of(1, 2, 3)) {
            assertThat(call(withKiln, HttpMethod.POST, "/api/v1/fleet/merge-queue", "{\"repo\":\"o/r\",\"pr\":" + pr + ",\"addedBy\":\"agent\"}", VERDICT)
                           .status().code()).as("pr " + pr).isEqualTo(503);
        }
        assertThat(sql("SELECT count(*) AS v FROM merge_queue")).isEqualTo("0");
    }

    // ---- control paths -----------------------------------------------------------------------------------------
    @Test
    void controlPaths_listAddRemove() {
        var seeded = json(text(get("/api/v1/fleet/ci-control-paths?repo=pragmaticalabs/pragmatica", READ)));
        assertThat(seeded.path("repo").stringValue()).isEqualTo("pragmaticalabs/pragmatica");
        assertThat(IntStream.range(0, seeded.path("globs").size()).mapToObj(index -> seeded.path("globs").get(index).path("glob").stringValue()).toList())
            .containsExactly(".github/**", ".mvn/**", "pom.xml", "scripts/changelog-check.sh", "tools/**");
        assertThat(seeded.path("globs").get(0).path("addedBy").stringValue()).isEqualTo("V14");
        assertThat(seeded.path("globs").get(0).path("addedAt").stringValue()).matches(ISO);
        assertThat(json(text(get("/api/v1/fleet/ci-control-paths?repo=o/r", READ))).path("globs").size()).isZero();
        assertThat(get("/api/v1/fleet/ci-control-paths", READ).status().code()).isEqualTo(400);

        var added = post("/api/v1/fleet/ci-control-paths", path("o/r", ".github/**"));
        assertThat(added.status().code()).isEqualTo(201);
        assertThat(json(text(added)).path("glob").stringValue()).isEqualTo(".github/**");
        var again = post("/api/v1/fleet/ci-control-paths", path("o/r", ".github/**"));
        assertThat(again.status().code()).isEqualTo(409);
        assertThat(text(again)).isEqualTo("{\"error\":\"PATH_EXISTS\"}");
        for (var glob : List.of("../x", "a/../b", "a/..", "..", "/abs", "a b", "a\\\\b", "")) {
            assertThat(post("/api/v1/fleet/ci-control-paths", path("o/r", glob)).status().code()).as(glob).isEqualTo(400);
        }
        assertThat(post("/api/v1/fleet/ci-control-paths", path("o/r", "a..b/c.d")).status().code()).isEqualTo(201);

        var removed = post("/api/v1/fleet/ci-control-paths/remove", "{\"repo\":\"o/r\",\"glob\":\".github/**\"}");
        assertThat(removed.status().code()).isEqualTo(200);
        assertThat(text(removed)).isEqualTo("{\"repo\":\"o/r\",\"glob\":\".github/**\"}");
        assertThat(text(added)).contains("\"repo\":\"o/r\"", "\"addedBy\":\"agent\"");
        var gone = post("/api/v1/fleet/ci-control-paths/remove", "{\"repo\":\"o/r\",\"glob\":\".github/**\"}");
        assertThat(gone.status().code()).isEqualTo(404);
        assertThat(text(gone)).isEqualTo("{\"error\":\"PATH_NOT_FOUND\"}");
        assertThat(sql("SELECT string_agg(glob, ',') AS v FROM ci_control_paths WHERE repo = 'o/r'")).isEqualTo("a..b/c.d");
    }

    // ---- helpers -----------------------------------------------------------------------------------------------
    private static String reported(long pr, String sha, String verdict, String base, String digest) {
        return "{\"repo\":\"o/r\",\"pr\":" + pr + ",\"sha\":\"" + sha + "\",\"base\":\"" + base + "\",\"verdict\":\"" + verdict
               + "\",\"by\":\"agent\",\"reportPath\":\"/r/report.md\",\"reportSha256\":\"" + digest + "\",\"verdictLine\":\"VERDICT: " + verdict
               + " o/r#" + pr + "@" + sha + " base=" + base + "\"}";
    }

    private static String carry(long pr, String sha, String from) {
        return "{\"repo\":\"o/r\",\"pr\":" + pr + ",\"sha\":\"" + sha + "\",\"base\":\"main\",\"verdict\":\"ACCEPT\",\"by\":\"agent\",\"carriedFrom\":\""
               + from + "\"}";
    }

    private static String add(long pr, String base) {
        return "{\"repo\":\"o/r\",\"pr\":" + pr + ",\"base\":\"" + base + "\",\"addedBy\":\"agent\"}";
    }

    private static String move(long pr, int to, String base) {
        return "{\"repo\":\"o/r\",\"pr\":" + pr + ",\"to\":" + to + base + "}";
    }

    private static String path(String repo, String glob) {
        return "{\"repo\":\"" + repo + "\",\"glob\":\"" + glob + "\",\"addedBy\":\"agent\"}";
    }

    /// `position:pr` of the queue at `base`, by position.
    private String order(String base) {
        return sql("SELECT string_agg(position || ':' || pr, ',' ORDER BY position) AS v FROM merge_queue WHERE repo = 'o/r' AND base = '" + base + "'");
    }

    /// An outstanding GO (verdict row first, the FK), created at `createdAt` (SQL).
    private String go(long pr, String sha, String createdAt) {
        sql("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) VALUES ('o/r', " + pr + ", '" + sha
            + "', 'main', 'ACCEPT', 'tester', '/r.md', '" + DIGEST + "')");
        return sql("INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq, created_at) VALUES ('o/r', " + pr + ", '" + sha + "', 'main', '"
                   + TIP + "', (SELECT max(id) FROM verdicts), 0, " + createdAt + ") RETURNING id::text AS v");
    }

    private static JsonNode json(String payload) {
        return TemperJson.mapper().readTree(payload).unwrap();
    }

    private String sql(String statement) {
        return Pg.sql(database, statement);
    }

    private List<FleetEventRow> t2Rows() {
        return switch (store.fleet().pending(0, 500).await(Pg.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows()
                                                     .stream()
                                                     .filter(row -> List.of("verdict-registered", "go-invalidated").contains(row.kind()))
                                                     .toList();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    private ApiResponse post(String path, String body) {
        return call(router, HttpMethod.POST, path, body, VERDICT);
    }

    private ApiResponse get(String pathAndQuery, String token) {
        return call(router, HttpMethod.GET, pathAndQuery, "", token);
    }

    private static ApiResponse call(Router via, HttpMethod method, String pathAndQuery, String body, String token) {
        return via.handle(ApiRequest.apiRequest(request(method, pathAndQuery, body, Map.of("Authorization", "Bearer " + token))))
                  .await(Pg.WAIT)
                  .unwrap();
    }

    private static String text(ApiResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static HttpRequest request(HttpMethod method, String pathAndQuery, String body, Map<String, String> headers) {
        var parts = pathAndQuery.split("\\?", 2);
        var query = parts.length > 1
                    ? QueryParams.queryParams(Arrays.stream(parts[1].split("&"))
                                                    .map(pair -> pair.split("=", 2))
                                                    .collect(Collectors.toMap(pair -> pair[0], pair -> List.of(pair.length > 1 ? pair[1] : ""))))
                    : QueryParams.empty();
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
                return parts[0];
            }

            @Override
            public Headers headers() {
                return Headers.fromSingleValueMap(headers);
            }

            @Override
            public QueryParams queryParams() {
                return query;
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
    }
}
