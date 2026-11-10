package org.pragmatica.temper.acceptance.stack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/// GitHub's REST API as kiln reads it, on the loopback address: a canned body per path-and-query,
/// 200 (with an ETag, 304 on a matching `If-None-Match`) for an answered path and 404 for any other, with every request
/// recorded (path, `If-None-Match`).
final class GitHubStub implements AutoCloseable {
    record Request(String path, String ifNoneMatch) {}

    private final HttpServer server;
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, String> prefixes = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private GitHubStub(HttpServer server) {
        this.server = server;
    }

    static GitHubStub start() throws IOException {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        var stub = new GitHubStub(server);

        server.createContext("/", stub::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return stub;
    }

    URI api() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    /// `path` (with its query) now answers 200 `body`.
    void answer(String path, String body) {
        answers.put(path, body);
    }

    /// Every path starting with `prefix` and without an exact answer answers 200 `body` (the longest prefix wins).
    void answerPrefix(String prefix, String body) {
        prefixes.put(prefix, body);
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var uri = exchange.getRequestURI();
            var path = uri.getRawQuery() == null ? uri.getRawPath() : uri.getRawPath() + "?" + uri.getRawQuery();

            requests.add(new Request(path, exchange.getRequestHeaders().getFirst("If-None-Match")));
            var body = answers.get(path);

            if (body == null) {
                body = prefixes.entrySet()
                               .stream()
                               .filter(entry -> path.startsWith(entry.getKey()))
                               .max(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                               .map(Map.Entry::getValue)
                               .orElse(null);
            }
            var bytes = (body == null ? "{\"message\":\"Not Found\"}" : body).getBytes(StandardCharsets.UTF_8);
            var etag = body == null ? null : etag(bytes);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (etag != null) {
                exchange.getResponseHeaders().set("ETag", etag);
            }
            if (etag != null && etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    /// GitHub's strong ETag, here a digest of the body: pr-fresh's G10 compares the two it reads (a null ETag counts as differing).
    private static String etag(byte[] body) {
        try {
            return "\"" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body), 0, 12) + "\"";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
