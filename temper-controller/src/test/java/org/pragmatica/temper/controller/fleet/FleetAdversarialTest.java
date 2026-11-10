package org.pragmatica.temper.controller.fleet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.api.FleetEventLine;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.KilnBatch;
import org.pragmatica.temper.store.api.NewFleetEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The independent verifier's adversarial unit checks (t1wp3v, adopted into the suite): the first kiln read, the text
/// form's escaping, kiln-down shapes, out-of-order ids, the self-check's bounds and a future `lastOkAt`.
class FleetAdversarialTest {
    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");
    private static final TimeSpan WAIT = TimeSpan.timeSpan(30).seconds();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<Long> delayMs = new AtomicReference<>(0L);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // V1: first read ever against a kiln whose oldest rows were pruned (floor > 1) answers `gap`; the rows that
    // follow are kiln's HISTORY and must be catch-up (H4, R2-6.3), not live.
    @Test
    void v1_firstReadGap_thenHistoricRows_areCatchUp() {
        var gap = new KilnReply.Gap(0, 100, "E1", 100, 150, Map.of());
        var afterGap = KilnPlan.plan(FleetSourceRow.unseen("kiln", 5), gap, NOW);
        var next = KilnPlan.plan(afterGap.kiln(), new KilnReply.Changes("E1", 100, 150, Map.of(), List.of(change(100), change(101))), NOW);

        assertThat(next.events()).as("historic rows 100..101 (head 150) after a first-read gap").allMatch(NewFleetEvent::catchUp);
    }

    // V2: the text form is line-oriented; a value with a newline/tab/CR must not break the line (the b64 rule's intent).
    @Test
    void v2_textValue_withNewlineOrTab_staysOneLine() {
        assertThat(FleetLines.value("a\nb")).startsWith("b64:");
        assertThat(FleetLines.value("a\tb")).startsWith("b64:");
        assertThat(FleetLines.value("a\rb")).startsWith("b64:");
    }

    // V3: a payload KEY with a space or '=' breaks k=v parsing.
    @Test
    void v3_textPayloadKey_withSpaceOrEquals_isNotEmittedRaw() {
        var payload = TemperJson.mapper().readTree("{\"a b\":\"x\",\"c=d\":\"y\"}").unwrap();
        var line = FleetEventLine.fleetEventLine("0123456789", 1, NOW, NOW, "k", "s", false, "kiln", payload);

        assertThat(FleetLines.text(line)).doesNotContain(" a b=").doesNotContain(" c=d=");
    }

    // V4: a non-ASCII value or key; a plain value and key stay as they are.
    @Test
    void v4_textValue_nonAscii_isB64() {
        assertThat(FleetLines.value("héllo")).startsWith("b64:");
        assertThat(FleetLines.value("o/r#7")).isEqualTo("o/r#7");
        assertThat(FleetLines.key("pr.number_1-a")).isEqualTo("pr.number_1-a");
        assertThat(FleetLines.key("clé")).startsWith("b64:");
    }

    // C4.2: the first read with no stored kiln epoch announces nothing, whatever the reply's shape; a reset on the
    // first read is catch-up to its head too.
    @Test
    void firstRead_gapOrReset_announcesNothing_andIsCatchUpToTheHead() {
        var gap = KilnPlan.plan(FleetSourceRow.unseen("kiln", 5), new KilnReply.Gap(0, 100, "E1", 100, 150, Map.of()), NOW);
        var reset = KilnPlan.plan(FleetSourceRow.unseen("kiln", 5), new KilnReply.Reset("E1", 150, Map.of()), NOW);

        assertThat(gap.events()).isEmpty();
        assertThat(gap.kiln().catchUpTo()).isEqualTo(150);
        assertThat(gap.kiln().cursor()).as("below kiln's floor, as a later gap sets it").isEqualTo(99);
        assertThat(reset.events()).isEmpty();
        assertThat(reset.kiln().catchUpTo()).isEqualTo(150);
        assertThat(KilnPlan.plan(gap.kiln(), new KilnReply.Gap(150, 160, "E1", 160, 170, Map.of()), NOW).events())
            .as("a later gap is announced").singleElement().satisfies(event -> assertThat(event.kind()).isEqualTo("gap"));
    }

    // V5: kiln garbage JSON, 500, empty body and a slow kiln all FAIL the pull, bounded by the timeout (no wedge).
    @Test
    void v5_kilnDownShapes_failBounded() {
        var client = KilnClient.http(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TemperJson.mapper(), Duration.ofSeconds(2));

        body.set("not json {");
        assertThat(client.changes(none(), 0).await(WAIT).isFailure()).as("garbage").isTrue();
        body.set("{\"epoch\":\"E1\",\"floor\":1,\"head\":3,\"changes\":[]}");
        status.set(500);
        assertThat(client.changes(none(), 0).await(WAIT).isFailure()).as("500").isTrue();
        status.set(200);
        body.set("");
        assertThat(client.changes(none(), 0).await(WAIT).isFailure()).as("empty").isTrue();
        body.set("{\"epoch\":\"E1\",\"floor\":1,\"head\":3,\"changes\":[{\"id\":1}]}");
        assertThat(client.changes(none(), 0).await(WAIT).isFailure()).as("change without kind").isTrue();
        body.set("{\"epoch\":\"E1\",\"floor\":1,\"head\":3,\"changes\":[]}");
        delayMs.set(8000L);
        var started = System.nanoTime();
        assertThat(client.changes(none(), 0).await(WAIT).isFailure()).as("slow").isTrue();
        assertThat(System.nanoTime() - started).as("bounded by the 2 s timeout").isLessThan(5_000_000_000L);
        server.stop(0);
        assertThat(client.changes(none(), 0).await(WAIT).isFailure()).as("refused").isTrue();
    }

    // V6: duplicated / out-of-order ids in one reply: cursor = max, no throw.
    @Test
    void v6_outOfOrderAndDuplicateIds_cursorIsMax() {
        KilnBatch batch = KilnPlan.plan(FleetSourceRow.fleetSourceRow("kiln", some("E1"), 2, some(NOW), 5, 0, 0, 2, false),
                                        new KilnReply.Changes("E1", 1, 9, Map.of(), List.of(change(5), change(3), change(5))), NOW);

        assertThat(batch.kiln().cursor()).isEqualTo(5);
        assertThat(batch.events()).hasSize(3);
    }

    // V7: slow loopback (answers after 8 s) is `loopback` failed within ~5 s; ssh-keyscan that cannot run is `ssh-banner`.
    @Test
    void v7_slowLoopback_andMissingKeyscan() {
        delayMs.set(8000L);
        ProcessRunner missing = new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return Causes.cause("ssh-keyscan: not found").promise();
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return Causes.cause("no").result();
            }
        };
        var started = System.nanoTime();
        var check = SelfCheck.selfCheck(missing, server.getAddress().getPort()).run().await(WAIT).unwrap();

        assertThat(System.nanoTime() - started).isLessThan(7_000_000_000L);
        assertThat(check.ok()).isFalse();
        assertThat(check.failed()).containsExactly(SelfCheck.BANNER, SelfCheck.LOOPBACK);
    }

    // V8: staleness by age is false for a lastOkAt in the future (a clock step back keeps a source fresh).
    @Test
    void v8_futureLastOkAt_neverStale() {
        assertThat(Staleness.stale(some(NOW.plusSeconds(3600)), 60, NOW.plusSeconds(1800)))
            .as("a source whose last success is 'in the future' after a clock step back").isTrue();
    }

    private static KilnReply.Change change(long id) {
        return new KilnReply.Change(id, "PR_STATE", some("o/r"), some(7L), "o/r#7", none(), none(), NOW, none(),
                                    TemperJson.mapper().readTree("{}").unwrap());
    }
}
