package org.pragmatica.kiln.service.github;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;


/// GitHub's REST API as kiln reads it, on the loopback address (design §7.1 WP6): canned answers per path and query,
/// each 200 with an ETag computed from its body, and a 304 (with no body and no `Link`, the worst case) when
/// `If-None-Match` names it. Pages are linked by an absolute `Link: rel="next"` as GitHub sends them. It counts the 200s
/// and 304s it served (K-10) and records every request with its `Authorization` and `If-None-Match`. With a rate set it
/// sends the rate headers, and each 200 (and, unauthenticated, each 304) spends one.
final class GitHubFake implements AutoCloseable {
    static final String NOT_FOUND = "{\"message\":\"Not Found\"}";

    record Canned(int status, String body) {}

    record Request(String path, String authorization, String ifNoneMatch, String body) {
        Request(String path, String authorization, String ifNoneMatch) {
            this(path, authorization, ifNoneMatch, "");
        }
    }

    record Rate(int remaining, int limit, Instant reset, boolean notModifiedCosts) {}

    private final HttpServer server;
    private final Map<String, Canned> answers = new ConcurrentHashMap<>();
    private final Map<String, Canned> prefixes = new ConcurrentHashMap<>();
    private final Map<String, String> links = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger full = new AtomicInteger();
    private final AtomicInteger notModified = new AtomicInteger();
    private final AtomicReference<Rate> rate = new AtomicReference<>();
    private final java.util.Set<String> exhausting = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    private final Map<String, String> retryAfters = new ConcurrentHashMap<>();
    private final Map<String, java.util.Deque<String>> sequences = new ConcurrentHashMap<>();

    private GitHubFake(HttpServer server) {
        this.server = server;
    }

    static GitHubFake start() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            var fake = new GitHubFake(server);

            server.createContext("/", fake::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();

            return fake;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    URI api() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    void answer(String path, String body) {
        answer(path, 200, body);
    }

    void answer(String path, int status, String body) {
        answers.put(path, new Canned(status, body));
    }

    /// Answers every path starting with `prefix` that has no exact answer (the longest prefix wins).
    void answerPrefix(String prefix, String body) {
        prefixes.put(prefix, new Canned(200, body));
    }

    /// Answers `path` with `bodies` in turn, one per request, the last repeated (a value that changes between reads).
    void answers(String path, List<String> bodies) {
        sequences.put(path, new java.util.concurrent.ConcurrentLinkedDeque<>(bodies));
        answer(path, bodies.getFirst());
    }

    private Canned canned(String path) {
        var sequence = sequences.get(path);

        if (sequence != null) {
            answer(path,
                   sequence.size() > 1
                   ? sequence.poll()
                   : sequence.peek());
        }

        var exact = answers.get(path);

        if (exact != null) {
            return exact;
        }

        return prefixes.entrySet()
                       .stream()
                       .filter(entry -> path.startsWith(entry.getKey()))
                       .max(java.util.Comparator.comparingInt(entry -> entry.getKey()
                                                                            .length()))
                       .map(Map.Entry::getValue)
                       .orElse(new Canned(404, NOT_FOUND));
    }

    /// The bodies as consecutive pages: the first at `path`, page k at `path&page=k`, each linking the next.
    void pages(String path, List<String> bodies) {
        for (int index = 0; index < bodies.size(); index++) {
            var at = pagePath(path, index + 1);

            answer(at, bodies.get(index));
            if (index + 1 < bodies.size()) {
                links.put(at, api() + pagePath(path, index + 2));
            } else {
                links.remove(at);
            }
        }
    }

    static String pagePath(String path, int page) {
        return page == 1
               ? path
               : path + "&page=" + page;
    }

    void rate(int remaining, int limit, Instant reset, boolean notModifiedCosts) {
        rate.set(new Rate(remaining, limit, reset, notModifiedCosts));
    }

    /// The answer at `path` reports `X-RateLimit-Remaining: 0`, whatever was left before it.
    void exhaustOn(String path) {
        exhausting.add(path);
    }

    /// The answer at `path` carries `Retry-After: <value>`.
    void retryAfter(String path, String value) {
        retryAfters.put(path, value);
    }

    void delay(Duration by) {
        delay.set(by);
    }

    int fulls() {
        return full.get();
    }

    int notModifieds() {
        return notModified.get();
    }

    List<Request> requests() {
        return List.copyOf(requests);
    }

    /// The paths requested since `from` (an index into [#requests]).
    List<String> pathsSince(int from) {
        var all = requests();

        return new ArrayList<>(all.subList(from, all.size())).stream()
                                                             .map(Request::path)
                                                             .toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String etag(String body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));

            return "\"" + HexFormat.of().formatHex(digest, 0, 12) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var uri = exchange.getRequestURI();
            var path = uri.getRawQuery() == null
                       ? uri.getRawPath()
                       : uri.getRawPath() + "?" + uri.getRawQuery();
            var headers = exchange.getRequestHeaders();
            var ifNoneMatch = headers.getFirst("If-None-Match");
            var body = new String(exchange.getRequestBody().readAllBytes(),
                                  StandardCharsets.UTF_8);

            requests.add(new Request(path, headers.getFirst("Authorization"), ifNoneMatch, body));
            sleep();
            var canned = canned(path);
            var tag = etag(canned.body());

            if (exhausting.contains(path) && rate.get() != null) {
                var current = rate.get();

                rate.set(new Rate(0, current.limit(), current.reset(), current.notModifiedCosts()));
            }

            if (canned.status() == 200 && tag.equals(ifNoneMatch)) {
                notModified.incrementAndGet();
                rateHeaders(exchange,
                            rate.get() != null && rate.get().notModifiedCosts());
                exchange.sendResponseHeaders(304, -1);

                return;
            }

            if (canned.status() == 200) {
                full.incrementAndGet();
                exchange.getResponseHeaders().add("ETag", tag);
                if (links.containsKey(path)) {
                    exchange.getResponseHeaders()
                            .add("Link",
                                 "<" + links.get(path) + ">; rel=\"next\", <" + api() + "/x>; rel=\"last\"");
                }
            }

            rateHeaders(exchange, canned.status() == 200);
            if (retryAfters.containsKey(path)) {
                exchange.getResponseHeaders().add("Retry-After", retryAfters.get(path));
            }

            var bytes = canned.body().getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(canned.status(),
                                         bytes.length == 0
                                         ? -1
                                         : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    private void rateHeaders(HttpExchange exchange, boolean costs) {
        var current = rate.get();

        if (current == null) {
            return;
        }

        var remaining = costs
                        ? Math.max(0, current.remaining() - 1)
                        : current.remaining();

        rate.set(new Rate(remaining, current.limit(), current.reset(), current.notModifiedCosts()));
        exchange.getResponseHeaders().add("X-RateLimit-Remaining", Integer.toString(remaining));
        exchange.getResponseHeaders().add("X-RateLimit-Limit",
                                          Integer.toString(current.limit()));
        exchange.getResponseHeaders().add("X-RateLimit-Reset",
                                          Long.toString(current.reset().getEpochSecond()));
    }

    private void sleep() {
        try {
            Thread.sleep(delay.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
