package org.pragmatica.kiln.service.temper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/// temper's read API on the loopback address: canned answers per path and query, every request's `Authorization`
/// recorded.
final class TemperFake implements AutoCloseable {
    static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    static final String TOKEN = "read-token-0123456789";
    static final String DIGEST = "/api/v1/digest?format=text&max=10";
    static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();

    record Canned(int status, String body) {}

    private final HttpServer server;
    private final Map<String, Canned> answers = new ConcurrentHashMap<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    private TemperFake(HttpServer server) {
        this.server = server;
    }

    static TemperFake start() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            var fake = new TemperFake(server);

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

    void answer(String path, int status, String body) {
        answers.put(path, new Canned(status, body));
    }

    void gate(Sha head, int status, String body) {
        answer("/api/v1/gate/" + head.value(), status, body);
    }

    List<String> authorizations() {
        return List.copyOf(authorizations);
    }

    JdkTemperHttp http() {
        return JdkTemperHttp.jdkTemperHttp(HttpClient.newHttpClient(), api(), TOKEN, Duration.ofSeconds(5));
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static <T> T await(Promise<T> promise) {
        return promise.await(WAIT).unwrap();
    }

    static Sha sha(long n) {
        return Sha.sha(String.format("%040x", n)).unwrap();
    }

    static PullRequest openPr(long number) {
        var branch = BranchName.branchName("b" + number).unwrap();

        return new PullRequest(PrNumber.prNumber(number).unwrap(), "pr " + number, PrState.OPEN, false,
                               BranchName.branchName("main").unwrap(), branch, Option.some("o/r"), sha(number),
                               Option.none(), Option.none(), T0, List.of(), List.of(), List.of());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var uri = exchange.getRequestURI();
            var path = uri.getRawQuery() == null
                       ? uri.getRawPath()
                       : uri.getRawPath() + "?" + uri.getRawQuery();

            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            var canned = answers.getOrDefault(path, new Canned(404, "not found"));
            var bytes = canned.body().getBytes(StandardCharsets.UTF_8);

            exchange.sendResponseHeaders(canned.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }
}
