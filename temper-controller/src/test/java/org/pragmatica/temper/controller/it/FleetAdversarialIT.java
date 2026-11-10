package org.pragmatica.temper.controller.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.pragmatica.temper.store.api.SourceSeen;
import org.pragmatica.temper.store.api.KilnBatch;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.lang.utils.Causes;

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
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The independent verifier's adversarial checks of the fleet stream over Postgres (t1wp3v, adopted into the suite):
/// link flaps in order, an ack above the head, the retention gap, kiln down, clock steps, concurrent producers, bounds
/// and the text form.
@Tag("pg")
class FleetAdversarialIT {
    private static final String FULL = "full-token";
    private static final String READ = "read-token";
    private static final HostName BIGBOY = new HostName("bigboy");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T12:00:00Z"));
    private Store store;
    private Fleet fleet;
    private Router router;
    @TempDir
    Path state;

    @BeforeEach
    void setUp() {
        store = Pg.freshStore();
        fleet = fleet();
        fleet.settle().await(Pg.WAIT).unwrap();
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               fleet.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none(), () -> true),
                               Responses.responses(TemperJson.mapper()));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    // I1: link UP then DOWN back to back (a flap). The stream's LAST word for the host must be host-unreachable.
    @Test
    void i1_linkFlap_lastEventIsUnreachable() {
        for (int i = 0; i < 20; i++) {
            var host = new HostName("h" + i);
            fleet.signal().up().apply(host);
            fleet.signal().down().apply(host);
        }
        awaitRows(40);
        var last = new LinkedHashMap<String, String>();
        rows().forEach(row -> last.put(row.subject(), row.kind()));

        assertThat(last.values()).as("final kind per flapped host, in seq order").containsOnly("host-unreachable");
    }

    // I2: an ack above the head would move the cursor past seqs not yet written: 400 ACK_AHEAD, the cursor stays, and
    // the next event is delivered.
    @Test
    void i2_ackAboveHead_doesNotSwallowFutureEvents() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        var acked = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(50));
        append("host-answering", "bigboy");
        var reply = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1", ""));

        assertThat(acked.status().code()).as("C4.2: an ack above the head").isEqualTo(400);
        assertThat(text(acked)).contains("ACK_AHEAD");
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).as("the cursor stays").isZero();
        assertThat(reply).contains("host-answering");
    }

    // I3: retention gap over the route: one gap line (current epoch, seq = floor), heartbeat last; ack past it resumes.
    @Test
    void i3_gapOverTheRoute_thenResume() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "a");
        append("host-answering", "b");
        append("host-answering", "c");
        store.fleet().prune(Instant.now().plusSeconds(86_400)).await(Pg.WAIT).unwrap();
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        var lines = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1", "")).lines().toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("\"kind\":\"gap\"").contains("\"seq\":3").contains("\"epoch\":\"" + epoch + "\"").contains("\"from\":1");
        assertThat(lines.getLast()).contains("\"kind\":\"heartbeat\"");
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(3)).status().code()).isEqualTo(204);
        append("host-answering", "d");
        var after = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1", "")).lines().toList();
        assertThat(after).hasSize(2).first().satisfies(line -> assertThat(line).contains("\"seq\":4"));
        var textForm = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&format=text", "")).lines().toList();
        assertThat(textForm.getLast()).startsWith("{\"v\":1,\"kind\":\"heartbeat\"");
    }

    // I4: kiln down -> the pull fails, `kiln` goes stale by age once, recovers once; nothing wedges.
    @Test
    void i4_kilnDown_staleOnce_recoveredOnce() {
        var up = new AtomicBoolean(true);
        KilnClient kiln = (_, _) -> up.get()
            ? Promise.success(new KilnReply.Changes("E1", 1, 0, Map.of(), List.of()))
            : Causes.cause("connection refused").promise();
        var withKiln = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(BIGBOY), store.fleet(), ProcessRunner.processRunner(), some(kiln), now::get);
        withKiln.settle().await(Pg.WAIT).unwrap();
        withKiln.pull(kiln).await(Pg.WAIT).unwrap();
        withKiln.staleness(now.get()).await(Pg.WAIT).unwrap();
        up.set(false);
        now.set(now.get().plusSeconds(16));
        assertThat(withKiln.pull(kiln).await(Pg.WAIT).isFailure()).isTrue();
        withKiln.staleness(now.get()).await(Pg.WAIT).unwrap();
        withKiln.staleness(now.get()).await(Pg.WAIT).unwrap();
        up.set(true);
        withKiln.pull(kiln).await(Pg.WAIT).unwrap();
        withKiln.staleness(now.get()).await(Pg.WAIT).unwrap();

        assertThat(rows()).filteredOn(row -> row.subject().equals("kiln")).extracting(FleetEventRow::kind)
                          .containsExactly("source-stale", "source-recovered");
    }

    // I5: a clock step back: a success recorded at T+1h, then at T (clock corrected). The source then dies. At T+10min
    // it has not succeeded for 10 minutes (period 60) and must read stale.
    @Test
    void i5_clockStepBack_deadSourceStillReadsStale() {
        var t = now.get();
        store.fleet().seen(List.of(SourceSeen.sourceSeen("selfcheck", some(t.plusSeconds(3600)), 60))).await(Pg.WAIT).unwrap();
        store.fleet().seen(List.of(SourceSeen.sourceSeen("selfcheck", some(t), 60))).await(Pg.WAIT).unwrap();
        now.set(t.plusSeconds(600));
        var heartbeat = text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""));

        assertThat(heartbeat).as(heartbeat).contains("\"selfcheck\":{\"lastOkAt\":\"" + t + "\"");
    }

    // I6: many concurrent producers (append, pulled, staleness) and a reader that moves its cursor to the last seq it
    // read: the reader must see every committed row (F9, no skip).
    @Test
    void i6_concurrentProducers_readerNeverSkips() throws Exception {
        var producers = 8;
        var each = 40;
        var done = new CountDownLatch(producers);
        var seen = ConcurrentHashMap.<Long>newKeySet();
        var stop = new AtomicBoolean();
        var reader = new Thread(() -> {
            long cursor = 0;
            while (!stop.get() || hasAfter(cursor)) {
                var pending = store.fleet().pending(cursor, 500).await(Pg.WAIT).unwrap();
                if (pending instanceof FleetPending.Events events && !events.rows().isEmpty()) {
                    for (var row : events.rows()) {
                        seen.add(row.seq());
                    }
                    cursor = events.rows().getLast().seq();
                }
            }
        });
        reader.start();
        for (int p = 0; p < producers; p++) {
            var id = p;
            new Thread(() -> {
                for (int i = 0; i < each; i++) {
                    if (id % 2 == 0) {
                        store.fleet().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "p" + id, "{}"))).await(Pg.WAIT).unwrap();
                    } else {
                        var kilnState = FleetSourceRow.fleetSourceRow("kiln" + id, some("E"), i, some(now.get()), 5, 0, 0, i, false);
                        store.fleet().pulled(KilnBatch.kilnBatch(kilnState, List.of(),
                            List.of(NewFleetEvent.kilnRow(now.get(), "E" + id, i, "pr-state", "p" + id, "{}", false)))).await(Pg.WAIT).unwrap();
                    }
                }
                done.countDown();
            }).start();
        }
        done.await();
        stop.set(true);
        reader.join(60_000);
        var all = rows(0, 10_000);

        assertThat(all).hasSize(producers * each);
        assertThat(seen).as("rows the cursor reader saw").hasSize(producers * each);
    }

    private boolean hasAfter(long cursor) {
        return store.fleet().pending(cursor, 1).await(Pg.WAIT).unwrap() instanceof FleetPending.Events events && !events.rows().isEmpty();
    }

    private List<FleetEventRow> rows(long after, int limit) {
        return switch (store.fleet().pending(after, limit).await(Pg.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    // I7: route bounds: max=501 and wait=-1 refused, max=500 / wait=55 accepted shapes; ack on unknown 404.
    @Test
    void i7_bounds() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=501", "").status().code()).isEqualTo(400);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&wait=-1", "").status().code()).isEqualTo(400);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=500", "").status().code()).isEqualTo(200);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/nobody/ack", ackBody(1)).status().code()).isEqualTo(404);
        assertThat(call(READ, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/reset", "{\"start\":\"head\"}").status().code()).isEqualTo(403);
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=E:1&wait=56", "").status().code()).isEqualTo(400);
    }

    // I8: a subject with a newline, format=text: every non-final line is an event line, heartbeat last.
    @Test
    void i8_textForm_subjectWithNewline_staysOneLinePerEvent() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        fleet.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "line1\nline2", "{}"))).await(Pg.WAIT).unwrap();
        var lines = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&format=text", "")).lines().toList();

        assertThat(lines).as(String.join(" | ", lines)).hasSize(2);
    }

    private Fleet fleet() {
        return Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(BIGBOY), store.fleet(), ProcessRunner.processRunner(), none(), now::get);
    }

    private void append(String kind, String host) {
        fleet.writer()
             .append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", kind, host, "{\"host\":\"" + host + "\"}")))
             .await(Pg.WAIT)
             .unwrap();
    }

    private List<FleetEventRow> rows() {
        return switch (store.fleet().pending(0, 100).await(Pg.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    private void awaitRows(int count) {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (rows().size() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private ApiResponse call(String token, HttpMethod method, String pathAndQuery, String body) {
        return router.handle(ApiRequest.apiRequest(request(method, pathAndQuery, body, Map.of("Authorization", "Bearer " + token))))
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

    /// An ack body in the current epoch (lead ruling F2: an ack names the epoch its line was read in).
    private String ackBody(long seq) {
        return "{\"epoch\":\"" + store.fleet().head().await(Pg.WAIT).unwrap().epoch() + "\",\"seq\":" + seq + "}";
    }
}
