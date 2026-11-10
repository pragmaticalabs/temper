package org.pragmatica.temper.acceptance.stack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/// A forwarding proxy between a CLI process and the controller, for [FleetEventsIT]'s lost-ack case (lead ruling F2):
/// every request passes through unchanged, except that while [#refuseAcks] is on, `POST …/ack` is answered 503 without
/// reaching the controller. It makes "the ack failed because the controller stopped for a restore" deterministic: the
/// line is printed, its ack is held back, and the controller is restored and restarted before the ack gets through.
/// A controller that does not answer (stopped) is a 502.
final class AckGate implements AutoCloseable {
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "content-length", "host", "expect", "upgrade", "transfer-encoding", "keep-alive");
    private static final Duration ANSWER = Duration.ofSeconds(70);

    private final HttpServer server;
    private final URI target;
    private final HttpClient client = HttpClient.newHttpClient();
    private final AtomicInteger refused = new AtomicInteger();
    private volatile boolean refusing;

    private AckGate(HttpServer server, URI target) {
        this.server = server;
        this.target = target;
    }

    static AckGate start(URI target) throws IOException {
        var gate = new AckGate(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0), target);
        gate.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        gate.server.createContext("/", gate::exchange);
        gate.server.start();
        return gate;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void refuseAcks(boolean on) {
        refusing = on;
    }

    /// Acks answered 503 so far.
    int refused() {
        return refused.get();
    }

    private void exchange(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = exchange.getRequestBody().readAllBytes();
            if (refusing && exchange.getRequestURI().getPath().endsWith("/ack")) {
                refused.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                answer(exchange, 503, "{\"error\":\"ack held by the test gate\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            forward(exchange, body);
        }
    }

    private void forward(HttpExchange exchange, byte[] body) throws IOException {
        var request = HttpRequest.newBuilder(target.resolve(exchange.getRequestURI().toString()))
                                 .timeout(ANSWER)
                                 .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase())) {
                values.forEach(value -> request.header(name, value));
            }
        });
        HttpResponse<byte[]> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            answer(exchange, 502, ("{\"error\":\"gate: " + e.getClass().getSimpleName() + "\"}").getBytes(StandardCharsets.UTF_8));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        response.headers().map().forEach((name, values) -> {
            if (!HOP_BY_HOP.contains(name.toLowerCase()) && !name.startsWith(":")) {
                exchange.getResponseHeaders().put(name, values);
            }
        });
        answer(exchange, response.statusCode(), response.body());
    }

    private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
