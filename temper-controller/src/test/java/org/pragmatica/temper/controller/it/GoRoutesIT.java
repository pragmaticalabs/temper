package org.pragmatica.temper.controller.it;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.t2.FreshToken;
import org.pragmatica.temper.controller.t2.GoRoutes;
import org.pragmatica.temper.controller.t2.KilnFresh;
import org.pragmatica.temper.controller.t2.T2Detector;
import org.pragmatica.temper.controller.t2.T2Routes;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.Store;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The WP-A2 routes end to end over Postgres (A2 rows): `pr-fresh` against a
/// kiln double (G6, G7, G9, the token), GO creation (GO.g, R3-3.e, `verdict-changed`), consume (GO.a-c, R3-3.a, the
/// 2(d) report fields), outcome (GO.h, `merged` with its queue removal, R3-2.i) and the detector's invalidations (GO.d).
@Tag("pg")
class GoRoutesIT {
    private static final String FULL = "full-token";
    private static final String READ = "read-token";
    private static final String VERDICT = "verdict-token";
    private static final String X = "a".repeat(40);
    private static final String Y = "b".repeat(40);
    private static final String TIP = "c".repeat(40);
    private static final String NEW_TIP = "e".repeat(40);
    private static final String DIGEST = "d".repeat(64);
    private static final String ISO = "^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ$";
    private static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T12:00:00.700Z"));
    private final AtomicReference<String> kilnReply = new AtomicReference<>();
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
        KilnFresh kiln = (_, _, _) -> Promise.success(json(kilnReply.get()));
        router = router(some(kiln));
        kilnReply.set(kiln(X, "main", List.of("src/A.java"), 1, "[]"));
        sql("INSERT INTO ci_control_paths (repo, glob, added_by) VALUES ('o/r', '.github/**', 'test')");
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private Router router(Option<KilnFresh> kiln) {
        var responses = Responses.responses(TemperJson.mapper());
        var t2 = T2Routes.t2Routes(responses, TemperJson.mapper(), store.t2(), fleet.writer(), none(), now::get, () -> true);
        var go = GoRoutes.goRoutes(responses, TemperJson.mapper(), store.t2(), fleet.writer(), kiln, FreshToken.freshToken(new SecureRandom()),
                                   now::get, () -> true);

        return Router.router(Credentials.credentials(token(FULL), some(token(READ)), none(), some(token(VERDICT)), none()),
                             Stream.concat(t2.routes().stream(), go.routes().stream()).toList(),
                             responses);
    }

    private static BearerToken token(String raw) {
        return BearerToken.bearerToken(raw).unwrap();
    }

    // ---- pr-fresh --------------------------------------------------------------------------------------------
    /// All green: the reply field for field (kiln's `pull`, `baseTip`, `checks` handed on), the fleet head read
    /// first, the verdict row, no reason, a `v1` token; and nothing is written.
    @Test
    void prFresh_allGreen_answersTheC42Reply_withAToken_andWritesNothing() {
        var id = verdict(7, X, "ACCEPT", "main");
        var head = store.fleet().head().await(Pg.WAIT).unwrap();
        var counts = counts();

        var reply = get("/api/v1/fleet/pr-fresh?repo=o/r&pr=7&sha=" + X.toUpperCase(), READ);

        assertThat(reply.status().code()).isEqualTo(200);
        var body = json(text(reply));
        assertThat(fieldNames(body)).containsExactly("repo", "pr", "sha", "checkedAt", "fleet", "pull", "base", "baseTip", "checks", "verdict", "reasons",
                                                    "freshToken");
        assertThat(body.path("repo").stringValue()).isEqualTo("o/r");
        assertThat(body.path("pr").longValue()).isEqualTo(7);
        assertThat(body.path("sha").stringValue()).isEqualTo(X);
        assertThat(body.path("checkedAt").stringValue()).isEqualTo("2026-11-04T12:00:00Z");
        assertThat(body.path("fleet")).isEqualTo(json("{\"epoch\":\"" + head.epoch() + "\",\"seq\":" + head.seq() + "}"));
        assertThat(body.path("pull")).isEqualTo(json(kilnReply.get()).path("pull"));
        assertThat(body.path("base").stringValue()).isEqualTo("main");
        assertThat(body.path("baseTip")).isEqualTo(json(kilnReply.get()).path("baseTip"));
        assertThat(body.path("checks")).isEqualTo(json(kilnReply.get()).path("checks"));
        assertThat(body.path("verdict")).isEqualTo(json("{\"id\":" + id + ",\"verdict\":\"ACCEPT\",\"by\":\"tester\",\"at\":\"" + body.path("verdict").path("at").stringValue()
                                                        + "\",\"base\":\"main\",\"reportPath\":\"/r.md\",\"reportSha256\":\"" + DIGEST + "\",\"carriedFrom\":null}"));
        assertThat(body.path("verdict").path("at").stringValue()).matches(ISO);
        assertThat(body.path("reasons").size()).isZero();
        assertThat(body.path("freshToken").stringValue()).matches("^v1\\.1793793600000\\." + head.seq() + "\\.[A-Za-z0-9_-]{43}$");
        assertThat(counts()).isEqualTo(counts);
    }

    /// G6.a (end to end): a verdict only for the previous SHA is not this head's → `verdict-missing`, no token.
    @Test
    void g6a_verdictOnlyForThePreviousSha_isVerdictMissing() {
        verdict(7, Y, "ACCEPT", "main");

        assertThat(lines(fresh(7, X))).containsExactly("G6 verdict-missing");
        assertThat(fresh(7, X).path("freshToken").isNull()).isTrue();
        assertThat(fresh(7, X).path("verdict").isNull()).isTrue();
    }

    /// G6.b (end to end): FIX after ACCEPT → the latest row by id decides.
    @Test
    void g6b_fixAfterAccept_isVerdictFix() {
        verdict(7, X, "ACCEPT", "main");
        var fix = verdict(7, X, "FIX", "main");

        var body = fresh(7, X);

        assertThat(lines(body)).containsExactly("G6 verdict-FIX");
        assertThat(body.path("verdict").path("id").longValue()).isEqualTo(fix);
        assertThat(body.path("freshToken").isNull()).isTrue();
    }

    /// G6.j (end to end): the verdict's base is not the PR's (`pull.base`).
    @Test
    void g6j_verdictBaseNotThePrBase_isVerdictBaseMismatch() {
        verdict(7, X, "ACCEPT", "dev");

        assertThat(lines(fresh(7, X))).containsExactly("G6 verdict-base-mismatch dev");
    }

    /// G9 (end to end) with kiln's reasons: every line in the order (G2, G9, G10, G6), no token.
    @Test
    void prFresh_kilnReasonsG9AndG6_inPrintOrder_noToken() {
        kilnReply.set(kiln(X, "main", List.of(".github/workflows/ci.yml", "src/A.java"), 3,
                           "[{\"check\":\"G10\",\"line\":\"required-changed\"},{\"check\":\"G2\",\"line\":\"head-moved pulls " + Y + "\"}]"));

        var body = fresh(7, X);

        assertThat(lines(body)).containsExactly("G2 head-moved pulls " + Y, "G9 files-read-incomplete 2/3",
                                                "G9 ci-control-changed .github/workflows/ci.yml (needs an explicit maintainer ruling)", "G10 required-changed",
                                                "G6 verdict-missing");
        assertThat(body.path("freshToken").isNull()).isTrue();
    }

    /// G9.h (end to end): a repo with no `ci_control_paths` row fails closed.
    @Test
    void g9h_repoWithoutRows_isCiControlUnconfigured() {
        sql("DELETE FROM ci_control_paths WHERE repo = 'o/r'");
        verdict(7, X, "ACCEPT", "main");

        assertThat(lines(fresh(7, X))).containsExactly("G9 ci-control-unconfigured");
    }

    /// Without `sha`, kiln is asked without one and only `pull` is answered, no token.
    @Test
    void prFresh_withoutSha_answersOnlyPull() {
        var asked = new AtomicReference<Option<String>>();
        KilnFresh kiln = (_, _, sha) -> {
            asked.set(sha);
            return Promise.success(json("{\"pull\":{\"state\":\"open\",\"base\":\"main\",\"headSha\":\"" + X + "\"}}"));
        };

        var body = json(text(call(router(some(kiln)), HttpMethod.GET, "/api/v1/fleet/pr-fresh?repo=o/r&pr=7", "", READ)));

        assertThat(asked.get()).isEqualTo(none());
        assertThat(body.path("sha").isNull()).isTrue();
        assertThat(body.path("pull").path("base").stringValue()).isEqualTo("main");
        assertThat(body.path("reasons").size()).isZero();
        assertThat(body.path("verdict").isNull()).isTrue();
        assertThat(body.path("freshToken").isNull()).isTrue();
    }

    /// 400 for a bad query: no repo, a bad pr, a bad sha.
    @Test
    void prFresh_badQuery_is400() {
        assertThat(get("/api/v1/fleet/pr-fresh?pr=7&sha=" + X, READ).status().code()).isEqualTo(400);
        assertThat(get("/api/v1/fleet/pr-fresh?repo=o/r&pr=x&sha=" + X, READ).status().code()).isEqualTo(400);
        assertThat(get("/api/v1/fleet/pr-fresh?repo=o/r&pr=0&sha=" + X, READ).status().code()).isEqualTo(400);
        assertThat(get("/api/v1/fleet/pr-fresh?repo=o/r&pr=7&sha=abc", READ).status().code()).isEqualTo(400);
    }

    /// G7.b: kiln 404 → 404 `repo not configured`; kiln 503 → the single G7 reason with kiln's detail; kiln stopped →
    /// `source-unavailable kiln unreachable`; never a token, `pull` null. A blank detail reads `status 503`; a 200 over
    /// HTTP is decoded, the query carrying repo, pr and sha.
    @Test
    void g7b_kilnUnavailable_isTheSingleG7Reason_kiln404Is404() throws Exception {
        verdict(7, X, "ACCEPT", "main");
        var status = new AtomicReference<Integer>(404);
        var body = new AtomicReference<>("{\"error\":\"repo not configured\"}");
        var query = new AtomicReference<String>();
        var stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v1/pr-fresh", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            var bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        stub.start();
        var kiln = KilnFresh.http(URI.create("http://127.0.0.1:" + stub.getAddress().getPort()), TemperJson.mapper(), Duration.ofSeconds(5));
        var via = router(some(kiln));
        var path = "/api/v1/fleet/pr-fresh?repo=o/r&pr=7&sha=" + X;
        try {
            var missing = call(via, HttpMethod.GET, path, "", READ);
            assertThat(missing.status().code()).isEqualTo(404);
            assertThat(text(missing)).isEqualTo("{\"error\":\"repo not configured\"}");

            status.set(503);
            body.set("{\"error\":\"UNAVAILABLE\",\"detail\":\"github 502 pulls/7\"}");
            var unavailable = json(text(call(via, HttpMethod.GET, path, "", READ)));
            assertThat(lines(unavailable)).containsExactly("G7 source-unavailable kiln github 502 pulls/7");
            assertThat(unavailable.path("freshToken").isNull()).isTrue();
            assertThat(unavailable.path("pull").isNull()).isTrue();

            body.set("{\"error\":\"UNAVAILABLE\",\"detail\":\" \"}");
            assertThat(lines(json(text(call(via, HttpMethod.GET, path, "", READ))))).containsExactly("G7 source-unavailable kiln status 503");

            status.set(200);
            body.set(kilnReply.get());
            var served = json(text(call(via, HttpMethod.GET, path, "", READ)));
            assertThat(query.get()).isEqualTo("repo=o%2Fr&pr=7&sha=" + X);
            assertThat(lines(served)).isEmpty();
            assertThat(served.path("pull")).isEqualTo(json(kilnReply.get()).path("pull"));
            assertThat(served.path("freshToken").isString()).isTrue();
        } finally {
            stub.stop(0);
        }
        var stopped = call(via, HttpMethod.GET, path, "", READ);
        assertThat(stopped.status().code()).isEqualTo(200);
        assertThat(lines(json(text(stopped)))).containsExactly("G7 source-unavailable kiln unreachable");
        assertThat(lines(json(text(call(router(none()), HttpMethod.GET, path, "", READ))))).containsExactly("G7 source-unavailable kiln not-configured");
    }

    // ---- create ------------------------------------------------------------------------------------------------
    /// A fresh token creates the row: 201 `{"id","createdAt","expiresAt"}` (10 minutes later), the row holding the body.
    @Test
    void go_freshToken_creates201_theRowHoldsTheBody() {
        var id = verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);

        var reply = post("/api/v1/fleet/go", goBody(fresh, 7, TIP, fresh.path("freshToken").stringValue()), READ);

        assertThat(reply.status().code()).isEqualTo(201);
        var body = json(text(reply));
        assertThat(fieldNames(body)).containsExactly("id", "createdAt", "expiresAt");
        assertThat(body.path("id").stringValue()).matches(UUID);
        assertThat(Duration.between(Instant.parse(body.path("createdAt").stringValue()), Instant.parse(body.path("expiresAt").stringValue())))
            .isEqualTo(Duration.ofMinutes(10));
        assertThat(sql("SELECT repo || ' ' || pr || ' ' || sha || ' ' || base || ' ' || base_tip || ' ' || verdict_id || ' ' || fleet_seq AS v FROM go WHERE id = '"
                       + body.path("id").stringValue() + "'"))
            .isEqualTo("o/r 7 " + X + " main " + TIP + " " + id + " " + fresh.path("fleet").path("seq").longValue());
    }

    /// GO.g: a forged or altered token, or a field the token does not bind → 422 `FRESH_TOKEN_INVALID`; 46 s old →
    /// 422 `FRESH_TOKEN_EXPIRED` (45 s is still fresh); the epoch rotated → 409 `EPOCH_CHANGED`. Only the fresh one
    /// creates a row.
    @Test
    void goG_forgedOrStaleToken_is422_rotatedEpochIs409() {
        verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);
        var token = fresh.path("freshToken").stringValue();

        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA")), READ), 422, "FRESH_TOKEN_INVALID");
        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, "v1.1.1.x"), READ), 422, "FRESH_TOKEN_INVALID");
        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 8, TIP, token), READ), 422, "FRESH_TOKEN_INVALID");
        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, token).replace("\"base\":\"main\"", "\"base\":\"dev\""), READ), 422, "FRESH_TOKEN_INVALID");
        var checkedAt = Instant.parse(fresh.path("checkedAt").stringValue());
        now.set(checkedAt.plusSeconds(46));
        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, token), READ), 422, "FRESH_TOKEN_EXPIRED");
        now.set(checkedAt.plusSeconds(45));
        sql("UPDATE temper_epoch SET id = gen_random_uuid()");
        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, token), READ), 409, "EPOCH_CHANGED");
        assertThat(sql("SELECT count(*) AS v FROM go")).isEqualTo("0");
    }

    /// Lead ruling 11-04: the token binds `baseTip`, so a token minted for one tip and presented with another (a
    /// forged tip, which the `base-moved` match would read) → 422 `FRESH_TOKEN_INVALID`, and no row.
    @Test
    void goG_tokenForOneBaseTip_presentedWithAnother_is422() {
        verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);

        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, NEW_TIP, fresh.path("freshToken").stringValue()), READ), 422, "FRESH_TOKEN_INVALID");
        assertThat(sql("SELECT count(*) AS v FROM go")).isEqualTo("0");
    }

    /// GO.g boundary: at exactly 45 s the token is still fresh.
    @Test
    void goG_tokenAt45s_isStillFresh() {
        verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);
        now.set(Instant.parse(fresh.path("checkedAt").stringValue()).plusSeconds(45));

        assertThat(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, fresh.path("freshToken").stringValue()), READ).status().code()).isEqualTo(201);
    }

    /// A verdict registered after the gate read → 409 `GO_INVALIDATED verdict-changed`.
    @Test
    void go_verdictChangedAfterTheGate_is409_verdictChanged() {
        verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);
        verdict(7, X, "ACCEPT", "main");

        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, fresh.path("freshToken").stringValue()), READ), 409,
                      "GO_INVALIDATED verdict-changed");
        assertThat(sql("SELECT count(*) AS v FROM go")).isEqualTo("0");
    }

    /// R3-3.e: a check event (or a base move) committed between `pr-fresh` and `POST fleet/go` → 409 `GO_INVALIDATED
    /// <reason>`; a check event at another head does not refuse.
    @Test
    void r33e_eventBetweenPrFreshAndGo_is409_goInvalidated() {
        verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);
        var token = fresh.path("freshToken").stringValue();

        append("check-concluded", "7", "{\"repo\":\"o/r\",\"pr\":7,\"head\":\"" + Y + "\"}");
        assertThat(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, token), READ).status().code()).as("another head").isEqualTo(201);
        append("check-concluded", "7", "{\"repo\":\"o/r\",\"pr\":7,\"head\":\"" + X + "\"}");
        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, token), READ), 409, "GO_INVALIDATED checks-changed");

        verdict(8, X, "ACCEPT", "main");
        var other = fresh(8, X);
        append("base-moved", "main", "{\"base\":\"main\",\"treeChanged\":true,\"repo\":\"o/r\",\"after\":\"" + NEW_TIP + "\"}");
        assertRefused(post("/api/v1/fleet/go", goBody(other, 8, TIP, other.path("freshToken").stringValue()), READ), 409, "GO_INVALIDATED base-moved");
        assertThat(sql("SELECT count(*) AS v FROM go")).isEqualTo("1");
    }

    // ---- consume ---------------------------------------------------------------------------------------------
    /// The consume reply field for field, with the GO's verdict report fields (amendment 2(d)).
    @Test
    void consume_answersTheRow_andItsVerdictReport() {
        var go = go(7, X, "main", "now()");
        var verdictId = sql("SELECT verdict_id AS v FROM go WHERE id = '" + go + "'");

        var reply = post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT);

        assertThat(reply.status().code()).isEqualTo(200);
        var body = json(text(reply));
        assertThat(fieldNames(body)).containsExactly("id", "repo", "pr", "sha", "base", "baseTip", "verdictId", "consumedAt", "invalidated", "verdict");
        assertThat(body.path("consumedAt").stringValue()).matches(ISO);
        ((ObjectNode) body).remove("consumedAt");
        assertThat(body).isEqualTo(json("{\"id\":\"" + go + "\",\"repo\":\"o/r\",\"pr\":7,\"sha\":\"" + X + "\",\"base\":\"main\",\"baseTip\":\"" + TIP
                                        + "\",\"verdictId\":" + verdictId + ",\"invalidated\":[],\"verdict\":{\"reportPath\":\"/r.md\",\"reportSha256\":\"" + DIGEST
                                        + "\"}}"));
        assertThat(sql("SELECT outcome || ' ' || (consumed_at IS NOT NULL) AS v FROM go WHERE id = '" + go + "'")).isEqualTo("consumed true");
    }

    /// GO.a: two GOs at base main (A, B) and one at dev; consuming A invalidates B (`sibling-consumed`, one
    /// `go-invalidated` subject B's PR), not the dev one; consuming B → 409 `go-invalidated sibling-consumed`.
    @Test
    void goA_consumeA_invalidatesSiblingB_thenConsumeBIs409() {
        var a = go(7, X, "main", "now()");
        var b = go(8, Y, "main", "now()");
        var dev = go(9, X, "dev", "now()");

        var consumed = json(text(post("/api/v1/fleet/go/" + a + "/consume", "{}", VERDICT)));

        assertThat(consumed.path("invalidated")).isEqualTo(json("[\"" + b + "\"]"));
        assertThat(t2Rows("go-invalidated")).singleElement().satisfies(row -> {
            assertThat(row.subject()).isEqualTo("o/r#8");
            assertThat(row.source()).isEqualTo("controller");
            assertThat(json(row.payload())).isEqualTo(json("{\"go\":\"" + b + "\",\"sha\":\"" + Y + "\",\"base\":\"main\",\"reason\":\"sibling-consumed\"}"));
        });
        assertRefused(post("/api/v1/fleet/go/" + b + "/consume", "{}", VERDICT), 409, "go-invalidated sibling-consumed");
        assertThat(post("/api/v1/fleet/go/" + dev + "/consume", "{}", VERDICT).status().code()).isEqualTo(200);
    }

    /// GO.b: single use.
    @Test
    void goB_consumeTwice_isGoUsed() {
        var go = go(7, X, "main", "now()");

        assertThat(post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT).status().code()).isEqualTo(200);
        assertRefused(post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT), 409, "go-used");
    }

    /// GO.c: 11 minutes old → `go-expired`; an unknown or malformed id → 404 `GO_NOT_FOUND`.
    @Test
    void goC_elevenMinutesOld_isGoExpired_unknownIsNotFound() {
        var go = go(7, X, "main", "now() - interval '11 minutes'");

        assertRefused(post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT), 409, "go-expired");
        assertRefused(post("/api/v1/fleet/go/00000000-0000-4000-8000-000000000000/consume", "{}", VERDICT), 404, "GO_NOT_FOUND");
        assertRefused(post("/api/v1/fleet/go/not-a-uuid/consume", "{}", VERDICT), 404, "GO_NOT_FOUND");
        assertThat(sql("SELECT count(*) AS v FROM go WHERE consumed_at IS NOT NULL")).isEqualTo("0");
    }

    /// R3-3.a (consume half): a FIX registered after the GO → consume answers `go-invalidated verdict-changed`.
    @Test
    void r33a_fixAfterGo_consumeIsGoInvalidatedVerdictChanged() {
        var go = go(7, X, "main", "now()");
        post("/api/v1/fleet/verdicts", "{\"repo\":\"o/r\",\"pr\":7,\"sha\":\"" + X + "\",\"base\":\"main\",\"verdict\":\"FIX\",\"by\":\"agent\",\"reportPath\":\"/r.md\","
                                       + "\"reportSha256\":\"" + DIGEST + "\",\"verdictLine\":\"VERDICT: FIX o/r#7@" + X + " base=main\"}", VERDICT);

        assertRefused(post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT), 409, "go-invalidated verdict-changed");
    }

    // ---- outcome ---------------------------------------------------------------------------------------------
    /// GO.h: only the four transitions; `consumed→merged`, an outcome on an unconsumed GO and a repeat → 409
    /// `BAD_TRANSITION <from>-><to>`; `merging` without `baseTipBefore`, or an unknown outcome → 400.
    @Test
    void goH_disallowedTransitions_are409BadTransition() {
        var go = go(7, X, "main", "now()");
        var path = "/api/v1/fleet/go/" + go + "/outcome";

        assertRefused(post(path, "{\"outcome\":\"refused\",\"detail\":\"x\"}", VERDICT), 409, "BAD_TRANSITION none->refused");
        post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT);
        assertRefused(post(path, "{\"outcome\":\"merged\"}", VERDICT), 409, "BAD_TRANSITION consumed->merged");
        assertRefused(post(path, "{\"outcome\":\"merge-failed\",\"detail\":\"x\"}", VERDICT), 409, "BAD_TRANSITION consumed->merge-failed");
        assertThat(post(path, "{\"outcome\":\"merging\"}", VERDICT).status().code()).isEqualTo(400);
        assertThat(post(path, "{\"outcome\":\"done\"}", VERDICT).status().code()).isEqualTo(400);
        assertThat(post(path, "{\"outcome\":\"refused\",\"detail\":\"" + "x".repeat(501) + "\"}", VERDICT).status().code()).isEqualTo(400);
        var refused = json(text(post(path, "{\"outcome\":\"refused\",\"detail\":\"" + "x".repeat(500) + "\"}", VERDICT)));
        assertThat(refused).isEqualTo(json("{\"id\":\"" + go + "\",\"outcome\":\"refused\",\"event\":null}"));
        assertRefused(post(path, "{\"outcome\":\"merging\",\"baseTipBefore\":\"" + TIP + "\"}", VERDICT), 409, "BAD_TRANSITION refused->merging");
        assertRefused(post("/api/v1/fleet/go/00000000-0000-4000-8000-000000000000/outcome", "{\"outcome\":\"refused\"}", VERDICT), 404, "GO_NOT_FOUND");
    }

    /// `merging` then `merged`: the merge row (route `temper-merge`, `go_id`, `event_seq` = the `merged` event), the
    /// `merged` event with `baseTipBefore`, the PR out of its queue with `queue-advanced` naming the next; the reply's
    /// event is the `merged` one.
    @Test
    void outcome_merged_recordsTheMerge_emitsMerged_andAdvancesTheQueue() {
        var go = go(7, X, "main", "now()");
        sql("INSERT INTO merge_queue (repo, base, position, pr, added_by) VALUES ('o/r', 'main', 1, 7, 'a'), ('o/r', 'main', 2, 11, 'a')");
        post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT);
        var merging = json(text(post("/api/v1/fleet/go/" + go + "/outcome", "{\"outcome\":\"merging\",\"baseTipBefore\":\"" + TIP + "\"}", VERDICT)));
        assertThat(merging.path("event").isNull()).isTrue();

        var merged = json(text(post("/api/v1/fleet/go/" + go + "/outcome", "{\"outcome\":\"merged\"}", VERDICT)));

        var mergedRow = t2Rows("merged").getFirst();
        assertThat(merged).isEqualTo(json("{\"id\":\"" + go + "\",\"outcome\":\"merged\",\"event\":{\"kind\":\"merged\",\"seq\":" + mergedRow.seq() + "}}"));
        assertThat(mergedRow.subject()).isEqualTo("o/r#7");
        assertThat(json(mergedRow.payload())).isEqualTo(json("{\"go\":\"" + go + "\",\"sha\":\"" + X + "\",\"base\":\"main\",\"baseTipBefore\":\"" + TIP
                                                             + "\",\"route\":\"temper-merge\"}"));
        assertThat(sql("SELECT route || ' ' || go_id || ' ' || event_seq || ' ' || sha AS v FROM merges WHERE pr = 7"))
            .isEqualTo("temper-merge " + go + " " + mergedRow.seq() + " " + X);
        assertThat(t2Rows("queue-advanced")).singleElement()
                                            .satisfies(row -> assertThat(json(row.payload())).isEqualTo(json("{\"repo\":\"o/r\",\"base\":\"main\",\"merged\":\"o/r#7\",\"next\":\"o/r#11\"}")));
        assertThat(sql("SELECT string_agg(pr || '@' || position, ',') AS v FROM merge_queue")).isEqualTo("11@1");
        assertThat(sql("SELECT outcome AS v FROM go WHERE id = '" + go + "'")).isEqualTo("merged");
    }

    /// R3-2.i: the `temper merge` record first, then kiln's `pr-state` row (at the real sha, and again with no head)
    /// → one `merged` (route temper-merge) and no `ungated-merge`.
    @Test
    void r32i_outcomeMergedThenTheKilnRow_isOneMerged() {
        var go = merged(7, X);
        append("pr-state", "7", "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"head\":\"" + X + "\",\"base\":\"main\"}");
        append("pr-state", "7", "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"base\":\"main\"}");

        detect();

        assertThat(t2Rows("merged")).singleElement()
                                    .satisfies(row -> assertThat(json(row.payload()).path("route").stringValue()).isEqualTo("temper-merge"));
        assertThat(t2Rows("ungated-merge")).isEmpty();
        assertThat(sql("SELECT count(*) || ' ' || min(go_id::text) AS v FROM merges")).isEqualTo("1 " + go);
    }

    /// R3-2.i the other way: kiln's row first (the GO is `merging`, so it is `merged` route kiln), then the outcome →
    /// no second `merged`, the reply's event null.
    @Test
    void r32i_kilnRowThenOutcomeMerged_isOneMerged() {
        var go = go(7, X, "main", "now()");
        post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT);
        post("/api/v1/fleet/go/" + go + "/outcome", "{\"outcome\":\"merging\",\"baseTipBefore\":\"" + TIP + "\"}", VERDICT);
        append("pr-state", "7", "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"base\":\"main\"}");
        detect();

        var reply = json(text(post("/api/v1/fleet/go/" + go + "/outcome", "{\"outcome\":\"merged\"}", VERDICT)));

        assertThat(reply.path("event").isNull()).isTrue();
        assertThat(t2Rows("merged")).singleElement()
                                    .satisfies(row -> assertThat(json(row.payload()).path("route").stringValue()).isEqualTo("kiln"));
        assertThat(sql("SELECT count(*) AS v FROM merges")).isEqualTo("1");
    }

    // ---- detector -------------------------------------------------------------------------------------
    /// GO.d end to end: a `base-moved` with treeChanged=true after the GO → the detector invalidates it (`base-moved`,
    /// `invalidated_by_seq` the row's seq), one `go-invalidated` with `causeSeq`; consuming it → `go-invalidated
    /// base-moved`. GO.e: an empty move and a move to its own tip leave another GO valid.
    @Test
    void goD_baseMovedSeenByTheDetector_invalidatesTheGo() {
        var moved = go(7, X, "main", "now()");
        append("base-moved", "main", "{\"base\":\"main\",\"treeChanged\":false,\"repo\":\"o/r\",\"after\":\"" + NEW_TIP + "\"}");
        append("base-moved", "main", "{\"base\":\"main\",\"treeChanged\":true,\"repo\":\"o/r\",\"after\":\"" + TIP + "\"}");
        var seq = append("base-moved", "main", "{\"base\":\"main\",\"treeChanged\":true,\"repo\":\"o/r\",\"after\":\"" + NEW_TIP + "\"}");
        var atNewTip = go(8, X, "main", "now()", NEW_TIP, seq - 1);

        detect();

        assertThat(sql("SELECT reason || ' ' || invalidated_by_seq AS v FROM go WHERE id = '" + moved + "'")).isEqualTo("base-moved " + seq);
        assertThat(t2Rows("go-invalidated")).singleElement()
                                             .satisfies(row -> assertThat(json(row.payload())).isEqualTo(json("{\"go\":\"" + moved + "\",\"sha\":\"" + X
                                                                                                              + "\",\"base\":\"main\",\"reason\":\"base-moved\",\"causeSeq\":" + seq + "}")));
        assertRefused(post("/api/v1/fleet/go/" + moved + "/consume", "{}", VERDICT), 409, "go-invalidated base-moved");
        assertThat(post("/api/v1/fleet/go/" + atNewTip + "/consume", "{}", VERDICT).status().code()).isEqualTo(200);
    }

    /// R3-3.b-d end to end: a `check-concluded` at the GO's head after its fleet seq invalidates (`checks-changed`); one
    /// at another head does not.
    @Test
    void r33bd_checkEventsSeenByTheDetector() {
        var hit = go(7, X, "main", "now()");
        var miss = go(8, X, "dev", "now()");
        append("check-concluded", "7", "{\"repo\":\"o/r\",\"pr\":7,\"head\":\"" + X + "\"}");
        append("check-concluded", "8", "{\"repo\":\"o/r\",\"pr\":8,\"head\":\"" + Y + "\"}");

        detect();

        assertThat(sql("SELECT reason AS v FROM go WHERE id = '" + hit + "'")).isEqualTo("checks-changed");
        assertThat(sql("SELECT coalesce(reason, 'valid') AS v FROM go WHERE id = '" + miss + "'")).isEqualTo("valid");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------
    private String merged(long pr, String sha) {
        var go = go(pr, sha, "main", "now()");
        post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT);
        post("/api/v1/fleet/go/" + go + "/outcome", "{\"outcome\":\"merging\",\"baseTipBefore\":\"" + TIP + "\"}", VERDICT);
        assertThat(post("/api/v1/fleet/go/" + go + "/outcome", "{\"outcome\":\"merged\"}", VERDICT).status().code()).isEqualTo(200);
        return go;
    }

    // ---- verifier t2wpa2v ----------------------------------------------------------------------------------------
    private ApiResponse awaited(Promise<ApiResponse> reply) {
        return reply.await(Pg.WAIT).unwrap();
    }

    private Promise<ApiResponse> consuming(String go) {
        return router.handle(ApiRequest.apiRequest(request(HttpMethod.POST, "/api/v1/fleet/go/" + go + "/consume", "{}",
                                                           Map.of("Authorization", "Bearer " + VERDICT))));
    }

    private static String code(ApiResponse reply) {
        return reply.status().code() == 200 ? "200" : reply.status().code() + " " + json(text(reply)).path("error").stringValue();
    }

    @Test
    void vt1_raceTwoSiblingConsumes_exactlyOneWins() {
        for (var round = 0; round < 10; round++) {
            var a = go(7, X, "race" + round, "now()");
            var b = go(8, Y, "race" + round, "now()");
            var first = consuming(a);
            var second = consuming(b);
            assertThat(Stream.of(first, second).map(this::awaited).map(GoRoutesIT::code).sorted().toList()).as("round " + round)
                .containsExactly("200", "409 go-invalidated sibling-consumed");
        }
        assertThat(sql("SELECT count(*) AS v FROM go WHERE consumed_at IS NOT NULL")).isEqualTo("10");
    }

    @Test
    void vt2_consume_doesNotInvalidateAnotherRepo_anExpiredOrAConsumedGo() {
        var a = go(7, X, "main", "now()");
        var expired = go(8, Y, "main", "now() - interval '11 minutes'");
        var otherRepo = sql("INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq) VALUES ('o/x', 7, '" + X + "', 'main', '" + TIP
                            + "', (SELECT max(id) FROM verdicts), 0) RETURNING id::text AS v");

        assertThat(json(text(post("/api/v1/fleet/go/" + a + "/consume", "{}", VERDICT))).path("invalidated")).isEqualTo(json("[]"));
        var c = go(9, X, "main", "now()");
        assertThat(json(text(post("/api/v1/fleet/go/" + c + "/consume", "{}", VERDICT))).path("invalidated")).isEqualTo(json("[]"));
        assertRefused(post("/api/v1/fleet/go/" + expired + "/consume", "{}", VERDICT), 409, "go-expired");
        assertThat(post("/api/v1/fleet/go/" + otherRepo + "/consume", "{}", VERDICT).status().code()).isEqualTo(200);
        assertThat(sql("SELECT count(*) AS v FROM go WHERE reason IS NOT NULL")).isEqualTo("0");
    }

    @Test
    void vt3_onePrMergedAtItsShaAndUnknown_inOneBatch_isOneAnnouncement() {
        append("pr-state", "7", "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"head\":\"" + X + "\",\"base\":\"main\"}");
        append("pr-state", "7", "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"base\":\"main\"}");
        detect();

        assertThat(t2Rows("ungated-merge")).hasSize(1);
        assertThat(sql("SELECT count(*) AS v FROM merges")).isEqualTo("1");
    }

    @Test
    void vt3b_control_theSameRowsInTwoBatches_isOneAnnouncement() {
        append("pr-state", "7", "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"head\":\"" + X + "\",\"base\":\"main\"}");
        detect();
        append("pr-state", "7", "{\"repo\":\"o/r\",\"pr\":7,\"before\":\"open\",\"after\":\"merged\",\"base\":\"main\"}");
        detect();

        assertThat(t2Rows("ungated-merge")).hasSize(1);
        assertThat(sql("SELECT count(*) AS v FROM merges")).isEqualTo("1");
    }

    @Test
    void vt4_mergingToMergeFailed_isAllowed_andTerminalStatesRefuse() {
        var go = go(7, X, "main", "now()");
        var path = "/api/v1/fleet/go/" + go + "/outcome";
        post("/api/v1/fleet/go/" + go + "/consume", "{}", VERDICT);

        assertThat(post(path, "{\"outcome\":\"merging\",\"baseTipBefore\":\"" + TIP + "\"}", VERDICT).status().code()).isEqualTo(200);
        assertThat(post(path, "{\"outcome\":\"merge-failed\",\"detail\":\"gh 1\"}", VERDICT).status().code()).isEqualTo(200);
        assertRefused(post(path, "{\"outcome\":\"merged\"}", VERDICT), 409, "BAD_TRANSITION merge-failed->merged");
        assertRefused(post(path, "{\"outcome\":\"merging\",\"baseTipBefore\":\"" + TIP + "\"}", VERDICT), 409, "BAD_TRANSITION merge-failed->merging");
        var other = merged(8, Y);
        assertRefused(post("/api/v1/fleet/go/" + other + "/outcome", "{\"outcome\":\"merged\"}", VERDICT), 409, "BAD_TRANSITION merged->merged");
        assertRefused(post("/api/v1/fleet/go/" + other + "/outcome", "{\"outcome\":\"merge-failed\",\"detail\":\"x\"}", VERDICT), 409,
                      "BAD_TRANSITION merged->merge-failed");
        assertThat(sql("SELECT count(*) AS v FROM merges")).isEqualTo("1");
    }

    @Test
    void vt5_creationScan_ignoresAnotherRepoAndAnotherBase() {
        verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);
        append("check-concluded", "7", "{\"repo\":\"o/x\",\"pr\":7,\"head\":\"" + X + "\"}");
        append("base-moved", "dev", "{\"base\":\"dev\",\"treeChanged\":true,\"repo\":\"o/r\",\"after\":\"" + NEW_TIP + "\"}");
        append("base-moved", "main", "{\"base\":\"main\",\"treeChanged\":true,\"repo\":\"o/x\",\"after\":\"" + NEW_TIP + "\"}");

        assertThat(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, fresh.path("freshToken").stringValue()), READ).status().code()).isEqualTo(201);
    }

    @Test
    void vt7_creationScan_eventAtExactlyFleetSeqPlusOne_refuses() {
        verdict(7, X, "ACCEPT", "main");
        var fresh = fresh(7, X);
        var seq = append("check-cell", "7", "{\"repo\":\"o/r\",\"pr\":7}");

        assertThat(seq).isEqualTo(fresh.path("fleet").path("seq").longValue() + 1);
        assertRefused(post("/api/v1/fleet/go", goBody(fresh, 7, TIP, fresh.path("freshToken").stringValue()), READ), 409, "GO_INVALIDATED checks-changed");
    }

    private void detect() {
        T2Detector.t2Detector(store.t2(), fleet.writer(), now::get).detect().await(Pg.WAIT).unwrap();
    }

    private long append(String kind, String subject, String payload) {
        return fleet.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "kiln", kind, subject, payload))).await(Pg.WAIT).unwrap().unwrap();
    }

    private static void assertRefused(ApiResponse reply, int status, String error) {
        assertThat(reply.status().code()).as(text(reply)).isEqualTo(status);
        assertThat(json(text(reply)).path("error").stringValue()).isEqualTo(error);
    }

    private static String kiln(String sha, String base, List<String> paths, int changed, String reasons) {
        return "{\"asOf\":\"2026-11-04T12:00:00Z\",\"repo\":\"o/r\",\"pr\":7,\"sha\":\"" + sha + "\","
               + "\"pull\":{\"state\":\"open\",\"headSha\":\"" + sha + "\",\"headRef\":\"f\",\"headRepo\":\"o/r\",\"base\":\"" + base + "\",\"changedFiles\":" + changed + "},"
               + "\"required\":{\"state\":\"REQUIRED\",\"contexts\":[{\"context\":\"ci\",\"appId\":null}],\"etag\":\"e\",\"detail\":null},"
               + "\"checks\":[{\"context\":\"ci\",\"appId\":null,\"outcome\":\"green\",\"observation\":\"5\",\"workflow\":\"1\",\"runId\":9,\"runNumber\":3,"
               + "\"attempt\":1,\"conclusion\":\"success\"}],"
               + "\"counts\":{\"checkRuns\":{\"read\":1,\"total\":1},\"workflowRuns\":{\"read\":1,\"total\":1}},"
               + "\"files\":{\"read\":" + paths.size() + ",\"paths\":[" + paths.stream().map(path -> "\"" + path + "\"").collect(Collectors.joining(",")) + "]},"
               + "\"requiredAgain\":{\"state\":\"REQUIRED\",\"contexts\":[{\"context\":\"ci\",\"appId\":null}],\"etag\":\"e\"},"
               + "\"baseTip\":{\"tracked\":true,\"sha\":\"" + TIP + "\",\"seenAt\":\"2026-11-04T11:59:00Z\"},"
               + "\"reasons\":" + reasons + ",\"reads\":9}";
    }

    private JsonNode fresh(long pr, String sha) {
        var reply = get("/api/v1/fleet/pr-fresh?repo=o/r&pr=" + pr + "&sha=" + sha, READ);
        assertThat(reply.status().code()).isEqualTo(200);
        return json(text(reply));
    }

    private static String goBody(JsonNode fresh, long pr, String baseTip, String token) {
        return "{\"repo\":\"o/r\",\"pr\":" + pr + ",\"sha\":\"" + fresh.path("sha").stringValue() + "\",\"base\":\"" + fresh.path("base").stringValue()
               + "\",\"baseTip\":\"" + baseTip + "\",\"verdictId\":" + fresh.path("verdict").path("id").longValue() + ",\"fleetEpoch\":\""
               + fresh.path("fleet").path("epoch").stringValue() + "\",\"fleetSeq\":" + fresh.path("fleet").path("seq").longValue() + ",\"checkedAt\":\""
               + fresh.path("checkedAt").stringValue() + "\",\"freshToken\":\"" + token + "\"}";
    }

    private static List<String> lines(JsonNode body) {
        return body.path("reasons").valueStream().map(reason -> reason.path("check").stringValue() + " " + reason.path("line").stringValue()).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    private String counts() {
        return sql("SELECT (SELECT count(*) FROM fleet_events) || ' ' || (SELECT count(*) FROM go) || ' ' || (SELECT count(*) FROM verdicts) AS v");
    }

    private long verdict(long pr, String sha, String verdict, String base) {
        return Long.parseLong(sql("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) VALUES ('o/r', " + pr + ", '"
                                  + sha + "', '" + base + "', '" + verdict + "', 'tester', '/r.md', '" + DIGEST + "') RETURNING id AS v"));
    }

    private String go(long pr, String sha, String base, String createdAt) {
        return go(pr, sha, base, createdAt, TIP, 0);
    }

    private String go(long pr, String sha, String base, String createdAt, String baseTip, long fleetSeq) {
        verdict(pr, sha, "ACCEPT", base);
        return sql("INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq, created_at) VALUES ('o/r', " + pr + ", '" + sha + "', '" + base + "', '"
                   + baseTip + "', (SELECT max(id) FROM verdicts), " + fleetSeq + ", " + createdAt + ") RETURNING id::text AS v");
    }

    private static JsonNode json(String payload) {
        return TemperJson.mapper().readTree(payload).unwrap();
    }

    private String sql(String statement) {
        return Pg.sql(database, statement);
    }

    private List<FleetEventRow> t2Rows(String kind) {
        return switch (store.fleet().pending(0, 500).await(Pg.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows().stream().filter(row -> row.kind().equals(kind)).toList();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    private ApiResponse post(String path, String body, String token) {
        return call(router, HttpMethod.POST, path, body, token);
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
