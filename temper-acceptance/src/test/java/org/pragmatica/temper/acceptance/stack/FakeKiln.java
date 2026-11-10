package org.pragmatica.temper.acceptance.stack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.temper.protocol.json.TemperJson;

/// kiln's `GET /api/v1/changes` as the controller's `[fleet] kilnUrl` reads it, served from
/// memory: one epoch, floor 0, the changes [#change] added, and two sources as kiln names them: `github`, whose
/// `lastOkAt` is the reply time unless [#githubLastOkAt] froze it, and `kiln:highwater` (already prefixed),
/// written at reply time. [#stopAnswering] makes every reply a 503 (kiln unreachable, as the pull sees
/// it). The real kiln is not needed: the pull's decoding and planning are WP3's, and this is the wire WP1 serves.
final class FakeKiln implements AutoCloseable {
    private final HttpServer server;
    private final String epoch = UUID.randomUUID().toString();
    private final List<Map<String, Object>> changes = new CopyOnWriteArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();
    private volatile Instant githubLastOkAt;
    private volatile Instant highWaterLastOkAt;
    private volatile boolean answering = true;

    private FakeKiln(HttpServer server) {
        this.server = server;
    }

    static FakeKiln start() throws IOException {
        var kiln = new FakeKiln(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
        kiln.server.createContext("/api/v1/changes", kiln::answer);
        kiln.server.start();
        return kiln;
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /// Replies answered with 200 so far.
    int reads() {
        return reads.get();
    }

    /// The next change: id = the previous id + 1, observed now; answers its id.
    synchronized long change(String kind, long pr, String subject) {
        var id = changes.size() + 1L;
        var now = Instant.now().toString();
        var change = new LinkedHashMap<String, Object>();
        change.put("id", id);
        change.put("kind", kind);
        change.put("repo", "o/r");
        change.put("pr", pr);
        change.put("subject", subject);
        change.put("before", "a".repeat(40));
        change.put("after", "b".repeat(40));
        change.put("observedAt", now);
        change.put("recordedAt", now);
        change.put("payload", Map.of());
        changes.add(change);
        return id;
    }

    /// `sources.github.lastOkAt` stays `at` from now on.
    void githubLastOkAt(Instant at) {
        githubLastOkAt = at;
    }

    /// `sources["kiln:highwater"].lastOkAt` stays `at` from now on (kiln's high-water file no longer written).
    void highWaterLastOkAt(Instant at) {
        highWaterLastOkAt = at;
    }

    void stopAnswering() {
        answering = false;
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!answering) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            var body = TemperJson.mapper()
                                 .writeAsString(reply(after(exchange.getRequestURI())))
                                 .expect("fake kiln reply")
                                 .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            reads.incrementAndGet();
        }
    }

    private Map<String, Object> reply(long after) {
        var now = Instant.now();
        var github = new LinkedHashMap<String, Object>();
        github.put("lastOkAt", (githubLastOkAt == null ? now : githubLastOkAt).toString());
        github.put("periodS", 60);
        var reply = new LinkedHashMap<String, Object>();
        reply.put("epoch", epoch);
        reply.put("floor", 0);
        reply.put("head", (long) changes.size());
        reply.put("asOf", now.toString());
        var highWater = new LinkedHashMap<String, Object>();
        highWater.put("lastOkAt", (highWaterLastOkAt == null ? now : highWaterLastOkAt).toString());
        highWater.put("periodS", 60);
        reply.put("sources", Map.of("github", github, "kiln:highwater", highWater));
        reply.put("changes", changes.stream().filter(change -> (long) change.get("id") > after).toList());
        return reply;
    }

    private static long after(URI uri) {
        var query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        for (var pair : query.split("&")) {
            if (pair.startsWith("after=")) {
                return Long.parseLong(pair.substring("after=".length()));
            }
        }
        return 0;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
