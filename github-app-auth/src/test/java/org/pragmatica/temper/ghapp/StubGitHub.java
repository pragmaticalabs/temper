package org.pragmatica.temper.ghapp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/// A GitHub that answers only `POST /app/installations/{id}/access_tokens`, records what it was sent, and answers from
/// a function of the request's ordinal (1-based) so a test scripts successes and failures.
final class StubGitHub implements AutoCloseable {
    record Reply(int status, String body, Duration delay) {
        static Reply created(String token, String expiresAt) {
            return new Reply(201,
                             "{\"token\":\"" + token + "\",\"expires_at\":\"" + expiresAt
                             + "\",\"permissions\":{\"pull_requests\":\"read\"}}",
                             Duration.ZERO);
        }

        static Reply status(int status, String message) {
            return new Reply(status, "{\"message\":\"" + message + "\"}", Duration.ZERO);
        }

        Reply after(Duration delay) {
            return new Reply(status, body, delay);
        }
    }

    record Seen(String path, String authorization, String body) {
        String jwt() {
            return authorization.substring("Bearer ".length());
        }
    }

    private final HttpServer server;
    private final AtomicInteger count = new AtomicInteger();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile IntFunction<Reply> script;

    StubGitHub(IntFunction<Reply> script) throws IOException {
        this.script = script;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    void script(IntFunction<Reply> next) {
        script = next;
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<Seen> seen() {
        return seen;
    }

    private void answer(HttpExchange exchange) throws IOException {
        var ordinal = count.incrementAndGet();
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        seen.add(new Seen(exchange.getRequestURI().getPath(),
                          exchange.getRequestHeaders().getFirst("Authorization"),
                          body));
        var reply = script.apply(ordinal);

        sleep(reply.delay());
        var bytes = reply.body().getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(reply.status(), bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// True when `jwt`'s signature verifies under `key` as RS256.
    static boolean verifies(String jwt, PublicKey key) throws Exception {
        var parts = jwt.split("\\.");
        var signer = Signature.getInstance("SHA256withRSA");

        signer.initVerify(key);
        signer.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        return parts[2].length() > 0 && signer.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    static String part(String jwt, int index) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[index]), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
