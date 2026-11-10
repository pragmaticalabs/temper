package org.pragmatica.temper.controller.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.fleet.KilnClient;
import org.pragmatica.temper.controller.fleet.KilnReply;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.t2.T2Detector;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.Store;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The `t2:detector` end to end over Postgres: kiln `pr-state` rows arrive
/// through the real pull from a stub kiln reply, GO and queue rows are inserted directly (their routes come with
/// WP-A1/A2), and the detector's batch decides `ungated-merge` / `merged` / `queue-advanced` (R3-2.a-g, Q.c), and its
/// staleness reaches the heartbeat (D.stale, the WP-0T half).
@Tag("pg")
class T2DetectorIT {
    private static final String FULL = "full-token";
    private static final String SHA = "a".repeat(40);
    private static final String OTHER = "b".repeat(40);
    private static final String TIP = "c".repeat(40);
    /// Before every `started_at` V14 writes (the database clock at migration time).
    private static final Instant BEFORE_START = Instant.parse("2026-01-01T00:00:00Z");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T12:00:00Z"));
    private Store store;
    private String database;
    private Fleet fleet;
    private T2Detector detector;
    private Router router;
    private long kilnId;
    @TempDir
    Path state;

    @BeforeEach
    void setUp() {
        var named = Pg.freshNamed();
        store = named.store();
        database = named.database();
        fleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(), store.fleet(), ProcessRunner.processRunner(), none(), now::get);
        fleet.settle().await(Pg.WAIT).unwrap();
        detector = T2Detector.t2Detector(store.t2(), fleet.writer(), now::get);
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), none()),
                               fleet.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none(), () -> true),
                               Responses.responses(TemperJson.mapper()));
        // kiln's first read is catch-up up to its head (0 here), so every later row is live
        pull(0, List.of());
    }

    @AfterEach
    void tearDown() {
        detector.stop();
        store.close();
    }

    /// R3-2.a: a hand merge (kiln `pr-state` open→merged) with no GO anywhere → one `ungated-merge sha=<head>
    /// by=unknown`, which a waiter on `--until ungated-merge` reads (the R3-2.live path), in the C8 text form.
    @Test
    void r32a_handMergeWithNoGo_isOneUngatedMerge_withTheHead() {
        var prState = merged(7, payload(SHA));
        detect();

        assertThat(t2Rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("ungated-merge");
            assertThat(row.subject()).isEqualTo("o/r#7");
            assertThat(row.source()).isEqualTo("controller");
            assertThat(row.catchUp()).isFalse();
            assertThat(json(row.payload())).isEqualTo(json("{\"sha\":\"" + SHA + "\",\"by\":\"unknown\",\"base\":\"main\",\"mergedAt\":\"2026-11-03T11:59:00Z\","
                                                           + "\"prStateSeq\":" + prState + "}"));
        });
        assertThat(sql("SELECT route || ' ' || coalesce(go_id::text, '-') || ' ' || event_seq AS v FROM merges WHERE pr = 7"))
            .isEqualTo("kiln - " + prState);

        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        assertThat(text(call(HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&until=ungated-merge", "")))
            .contains("\"kind\":\"ungated-merge\"");
        call(HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        assertThat(text(call(HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&format=text", "")))
            .contains(" ungated-merge o/r#7 catchUp=0 ")
            .contains(" sha=" + SHA)
            .contains(" by=unknown")
            .contains(" base=main")
            .contains(" mergedAt=2026-11-03T11:59:00Z")
            .contains(" prStateSeq=" + prState);
    }

    /// R3-2.b: a GO for that sha with outcome `merged`, or `merging` (a CLI that died before recording), gates it.
    @Test
    void r32b_goMergedOrMergingAtTheSha_isMergedRouteKiln_noUngatedMerge() {
        var go = go(7, SHA, "merged");
        var merging = go(8, SHA, "merging");
        merged(7, payload(SHA));
        merged(8, payload(SHA));
        detect();

        assertThat(t2Rows()).extracting(FleetEventRow::kind).containsExactly("merged", "merged");
        assertThat(json(t2Rows().getFirst().payload())).isEqualTo(json("{\"go\":\"" + go + "\",\"sha\":\"" + SHA + "\",\"base\":\"main\",\"baseTipBefore\":\""
                                                            + TIP + "\",\"route\":\"kiln\"}"));
        assertThat(json(t2Rows().getLast().payload()).path("go").stringValue()).isEqualTo(merging);
        assertThat(sql("SELECT go_id::text AS v FROM merges WHERE pr = 7")).isEqualTo(go);
    }

    /// R3-2.c: a GO consumed and then refused at step 2, followed by a hand merge, does not gate (consumed alone is
    /// not enough); neither does one whose `gh` call failed.
    @Test
    void r32c_goConsumedThenRefused_thenHandMerged_isUngated() {
        go(7, SHA, "refused");
        go(8, SHA, "merge-failed");
        go(9, SHA, "consumed");
        merged(7, payload(SHA));
        merged(8, payload(SHA));
        merged(9, payload(SHA));
        detect();

        assertThat(t2Rows()).extracting(FleetEventRow::kind).containsExactly("ungated-merge", "ungated-merge", "ungated-merge");
    }

    /// R3-2.d: a GO at another sha of the same PR does not gate the merge of this sha.
    @Test
    void r32d_goForAnotherSha_isUngated() {
        go(7, OTHER, "merged");
        merged(7, payload(SHA));
        detect();

        assertThat(t2Rows()).extracting(FleetEventRow::kind).containsExactly("ungated-merge");
    }

    /// R3-2.e: kiln rewound (restored from a backup) replays the same merge under a new generation: one event.
    @Test
    void r32e_sameMergeDeliveredTwiceByAKilnRewind_isOneEvent() {
        var observed = Instant.now().plusSeconds(3600);
        pull(1, List.of(change(1, 7, payload(SHA), observed)));
        detect();
        pull(0, List.of());                                         // kiln's head below the cursor: a rewind
        pull(1, List.of(change(1, 7, payload(SHA), observed)));     // the replay: id 1 again, under generation 1
        detect();

        assertThat(rows().stream().filter(row -> row.kind().equals("pr-state")).count()).as("both deliveries reached the stream").isEqualTo(2);
        assertThat(t2Rows()).extracting(FleetEventRow::kind).containsExactly("ungated-merge");
    }

    /// R3-2.f: kiln's first read replays its whole retention as catch-up; a merge observed before the detector
    /// started is skipped, one observed after it is not.
    @Test
    void r32f_catchUpMergeObservedBeforeStartedAt_isNothing() {
        var fresh = Pg.freshNamed();
        try (var other = fresh.store()) {
            var firstFleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("first")), List.of(), other.fleet(), ProcessRunner.processRunner(), none(), now::get);
            firstFleet.settle().await(Pg.WAIT).unwrap();
            KilnClient kiln = (_, _) -> Promise.success(new KilnReply.Changes("E1", 0, 2, Map.of(),
                                                                              List.of(change(1, 7, payload(SHA), BEFORE_START),
                                                                                      change(2, 8, payload(SHA), Instant.now().plusSeconds(3600)))));
            firstFleet.pull(kiln).await(Pg.WAIT).unwrap();

            T2Detector.t2Detector(other.t2(), firstFleet.writer(), now::get).detect().await(Pg.WAIT).unwrap();

            var events = eventsOf(other);
            assertThat(events).filteredOn(row -> row.kind().equals("pr-state")).allMatch(FleetEventRow::catchUp).hasSize(2);
            assertThat(events).filteredOn(row -> row.source().equals("controller") && row.kind().contains("merge"))
                              .extracting(FleetEventRow::subject)
                              .containsExactly("o/r#8");
            assertThat(Pg.sql(fresh.database(), "SELECT string_agg(pr::text, ',') AS v FROM merges")).isEqualTo("8");
        }
    }

    /// R3-2.g: a `pr-state` payload without `head` (a kiln without WP-0K) still fires, `sha=unknown`.
    @Test
    void r32g_payloadWithoutHead_isUngatedMergeShaUnknown() {
        merged(7, "{\"base\":\"main\"}");
        detect();

        assertThat(t2Rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("ungated-merge");
            assertThat(json(row.payload()).path("sha").stringValue()).isEqualTo("unknown");
        });
        assertThat(sql("SELECT sha AS v FROM merges WHERE pr = 7")).isEqualTo("unknown");
    }

    /// Q.c: the merged PR leaves the queue, positions stay dense, `queue-advanced` names the new head.
    @Test
    void qc_mergedPrLeavesTheQueue_positionsDense_queueAdvancedEmitted() {
        queue("main", 7, 9, 11);
        queue("dev", 7 + 100);
        merged(7, payload(SHA));
        detect();

        assertThat(sql("SELECT string_agg(base || ' ' || position || ':' || pr, ',' ORDER BY base, position) AS v FROM merge_queue"))
            .isEqualTo("dev 1:107,main 1:9,main 2:11");
        assertThat(t2Rows()).extracting(FleetEventRow::kind).containsExactly("ungated-merge", "queue-advanced");
        assertThat(t2Rows().getLast().subject()).isEqualTo("main");
        assertThat(json(t2Rows().getLast().payload())).isEqualTo(json("{\"repo\":\"o/r\",\"base\":\"main\",\"merged\":\"o/r#7\",\"next\":\"o/r#9\"}"));
    }

    /// D.stale (WP-0T half): a batch is the detector's success; one that stops reads stale after 3 × 5 s, announced
    /// once by the minute's staleness.
    @Test
    void dstale_detectorFreshAfterABatch_staleOnceItStops() {
        detect();
        assertThat(text(call(HttpMethod.GET, "/api/v1/fleet/heartbeat", "")))
            .contains("\"t2:detector\":{\"lastOkAt\":\"" + now.get() + "\",\"periodS\":5,\"stale\":false}");

        var lastOk = now.get();
        now.set(lastOk.plusSeconds(16));

        assertThat(text(call(HttpMethod.GET, "/api/v1/fleet/heartbeat", "")))
            .contains("\"t2:detector\":{\"lastOkAt\":\"" + lastOk + "\",\"periodS\":5,\"stale\":true}");
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        assertThat(rows()).filteredOn(row -> row.kind().equals("source-stale")).extracting(FleetEventRow::subject).contains("t2:detector");
    }

    /// The cursor moves past every row read, the detector's own rows included, so a second batch reads nothing again.
    @Test
    void detect_movesTheCursorPastEveryRowRead_ownRowsIncluded() {
        merged(7, payload(SHA));
        detect();
        detect();

        assertThat(t2Rows()).hasSize(1);
        assertThat(sql("SELECT cursor::text AS v FROM fleet_source WHERE name = 't2:detector'"))
            .isEqualTo(String.valueOf(rows().getLast().seq()));
    }

    /// The schedule wires the loop, and it runs again after each batch (single flight, released when a batch ends):
    /// two merges pulled one after the other are both announced.
    @Test
    void scheduled_runsAgainAfterEachBatch_twoMergesPulledInTurnAreBothAnnounced() {
        detector.schedule();
        merged(7, payload(SHA));
        awaitT2Rows(1);
        merged(8, payload(SHA));
        awaitT2Rows(2);

        assertThat(t2Rows()).extracting(FleetEventRow::subject).containsExactly("o/r#7", "o/r#8");
    }

    /// After `stop` neither the wake signal of a pull nor the tick runs a batch; a direct batch still would.
    @Test
    void stopped_aPulledMergeWakesNothing() throws Exception {
        detector.schedule();
        detector.stop();
        merged(7, payload(SHA));
        Thread.sleep(1_500);

        assertThat(t2Rows()).as("stopped: the pull's wake ran no batch").isEmpty();
        detect();
        assertThat(t2Rows()).hasSize(1);
    }

    /// Verifier probe ("inserts go through FleetWriter"): the detector's events advance the high-water mark
    /// and signal the wake, as every producer's.
    @Test
    void vprobe_detectorEvents_advanceTheHighWaterMark_andWake() {
        merged(7, payload(SHA));
        var woken = fleet.writer().wake().next();
        detect();

        assertThat(fleet.writer().highWater().mark().seq()).isEqualTo(t2Rows().getLast().seq());
        assertThat(woken.isResolved()).as("the detector's insert signalled the wake").isTrue();
    }

    /// Verifier probe ("on the fleet wake signal"): a pulled merge runs a batch well before the next 5 s tick.
    @Test
    void vprobe_scheduled_aPulledMergeWakesTheDetector_beforeTheTick() throws Exception {
        detector.schedule();
        Thread.sleep(700);
        var start = System.nanoTime();
        merged(7, payload(SHA));
        var deadline = start + 2_500_000_000L;
        while (t2Rows().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(t2Rows()).as("announced within 2.5 s of the pull (the tick is 5 s)").hasSize(1);
    }

    /// Verifier probe (lock order T2 then order lock): kiln pulls, staleness verdicts and detector batches run
    /// concurrently without a deadlock, and every merge is announced once.
    @Test
    void vprobe_concurrentPullsStalenessAndBatches_neverDeadlock_everyMergeAnnouncedOnce() throws Exception {
        var pool = Executors.newFixedThreadPool(3);
        try {
            for (var i = 1; i <= 15; i++) {
                long pr = 100 + i;
                var pulled = CompletableFuture.runAsync(() -> merged(pr, payload(SHA)), pool);
                var batch = CompletableFuture.runAsync(this::detect, pool);
                var stale = CompletableFuture.runAsync(() -> fleet.staleness(now.get().plusSeconds(20 + pr % 2))
                                                                                       .await(Pg.WAIT)
                                                                                       .unwrap(),
                                                                            pool);
                CompletableFuture.allOf(pulled, batch, stale).get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        detect();
        detect();

        assertThat(t2Rows()).extracting(FleetEventRow::subject).doesNotHaveDuplicates().hasSize(15);
    }

    private void awaitT2Rows(int count) {
        var deadline = System.nanoTime() + 15_000_000_000L;
        while (t2Rows().size() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(t2Rows()).hasSize(count);
    }

    // ---- helpers -----------------------------------------------------------------------------------------------
    private void detect() {
        detector.detect().await(Pg.WAIT).unwrap();
    }

    /// A live kiln `pr-state` open→merged row for PR `pr`; answers its fleet seq.
    private long merged(long pr, String kilnPayload) {
        kilnId++;
        pull(kilnId, List.of(change(kilnId, pr, kilnPayload, now.get())));
        return rows().stream().filter(row -> row.kind().equals("pr-state")).mapToLong(FleetEventRow::seq).max().orElseThrow();
    }

    private void pull(long head, List<KilnReply.Change> changes) {
        KilnClient kiln = (_, _) -> Promise.success(new KilnReply.Changes("E1", 0, head, Map.of(), changes));

        fleet.pull(kiln).await(Pg.WAIT).unwrap();
    }

    private static KilnReply.Change change(long id, long pr, String kilnPayload, Instant observedAt) {
        return new KilnReply.Change(id,
                                    "PR_STATE",
                                    some("o/r"),
                                    some(pr),
                                    String.valueOf(pr),
                                    some("open"),
                                    some("merged"),
                                    observedAt,
                                    none(),
                                    TemperJson.mapper().readTree(kilnPayload).unwrap());
    }

    /// kiln's PR_STATE payload as WP-0K writes it.
    private static String payload(String head) {
        return "{\"head\":\"" + head + "\",\"base\":\"main\",\"headRef\":\"feature\",\"mergedAt\":\"2026-11-03T11:59:00Z\"}";
    }

    /// A GO row inserted directly (the GO routes come with WP-A2), consumed, with `outcome`; answers its id.
    private String go(long pr, String sha, String outcome) {
        sql("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) VALUES ('o/r', " + pr + ", '" + sha
            + "', 'main', 'ACCEPT', 'tester', '/r.md', '" + "d".repeat(64) + "')");
        return sql("INSERT INTO go (repo, pr, sha, base, base_tip, verdict_id, fleet_seq, consumed_at, outcome, outcome_at, base_tip_before) "
                   + "VALUES ('o/r', " + pr + ", '" + sha + "', 'main', '" + TIP + "', (SELECT max(id) FROM verdicts), 0, now(), '" + outcome
                   + "', now(), '" + TIP + "') RETURNING id::text AS v");
    }

    private void queue(String base, long... prs) {
        for (var i = 0; i < prs.length; i++) {
            sql("INSERT INTO merge_queue (repo, base, position, pr, added_by) VALUES ('o/r', '" + base + "', " + (i + 1) + ", " + prs[i] + ", 'tester')");
        }
    }

    /// A payload as JSON: `jsonb` keeps the keys, not their order, so payloads compare as objects.
    private static JsonNode json(String payload) {
        return TemperJson.mapper().readTree(payload).unwrap();
    }

    private String sql(String statement) {
        return Pg.sql(database, statement);
    }

    private List<FleetEventRow> t2Rows() {
        return rows().stream()
                     .filter(row -> List.of("ungated-merge", "merged", "queue-advanced").contains(row.kind()))
                     .toList();
    }

    private List<FleetEventRow> rows() {
        return eventsOf(store);
    }

    private static List<FleetEventRow> eventsOf(Store store) {
        return switch (store.fleet().pending(0, 500).await(Pg.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    private ApiResponse call(HttpMethod method, String pathAndQuery, String body) {
        return router.handle(ApiRequest.apiRequest(request(method, pathAndQuery, body, Map.of("Authorization", "Bearer " + FULL))))
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
