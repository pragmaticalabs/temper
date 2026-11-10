package org.pragmatica.temper.controller.git;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.submit.PullJudgement;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.refusal.Refusal;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifier fixtures for PR #22, round 3: the per-PR / API-wide classification through the real judge. Older PR #3
/// misbehaves in one way; #9 is same-repo.
class PullClassificationAttackTest {
    private static final Sha SHA = Sha.sha("a".repeat(40)).unwrap();
    private static final String SAME = "{\"head\":{\"repo\":{\"full_name\":\"o/r\"}},\"base\":{\"repo\":{\"full_name\":\"o/r\"}}}";
    private static final String FORK = "{\"head\":{\"repo\":{\"full_name\":\"x/r\"}},\"base\":{\"repo\":{\"full_name\":\"o/r\"}}}";

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile Handler three = exchange -> send(exchange, 200, FORK);

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            paths.add(path);
            if (path.endsWith("/3")) {
                three.handle(exchange);
            } else {
                send(exchange, 200, SAME);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Result<Sha> verdict() {
        var judge = GitHubPulls.gitHubPulls(HttpClient.newHttpClient(),
                                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                            "git@github.com:o/r.git",
                                            Duration.ofSeconds(3));
        return PullJudgement.pullJudgement(judge, List.of(new PullNumber(3), new PullNumber(9)))
                            .await()
                            .unwrap()
                            .verdict(SHA);
    }

    private static String code(Result<Sha> result) {
        return result.fold(cause -> ((Refusal) cause).code(), _ -> "accepted");
    }

    /// Per-PR answers about #3 never block #9.
    @ParameterizedTest
    @ValueSource(ints = {404, 410, 422})
    void perPullStatusOnOlder_laterSameRepoAccepted(int status) {
        three = exchange -> send(exchange, status, "{\"message\":\"x\"}");

        assertThat(code(verdict())).isEqualTo("accepted");
    }

    @Test
    void declaredOversizedOnOlder_laterSameRepoAccepted() {
        three = exchange -> send(exchange, 200, FORK + " ".repeat((int) GitHubPulls.MAX_BODY + 1));

        assertThat(code(verdict())).isEqualTo("accepted");
    }

    /// The same oversized answer, chunked, is API-wide: it stops the walk before #9. Fails closed. An outsider can not
    /// get a PR answer near 1 MiB (body ≤ 65536 chars), so only GitHub itself could trigger this.
    @Test
    void chunkedOversizedOnOlder_stopsTheWalk_unverified() {
        three = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                out.write(FORK.getBytes(StandardCharsets.UTF_8));
                for (int i = 0; i < 1100; i++) {
                    out.write(" ".repeat(1024).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // the client gave up
            }
        };

        assertThat(code(verdict())).isEqualTo("pr-origin-unverified");
        assertThat(paths).noneMatch(path -> path.endsWith("/9"));
    }

    /// Any status not in the per-PR allowlist stops the walk (fail closed): 451 (a legal block) and 5xx included.
    @ParameterizedTest
    @ValueSource(ints = {451, 500, 403})
    void otherStatusOnOlder_stopsTheWalk_unverified(int status) {
        three = exchange -> send(exchange, status, "{}");

        assertThat(code(verdict())).isEqualTo("pr-origin-unverified");
        assertThat(paths).noneMatch(path -> path.endsWith("/9"));
    }

    /// A slow answer for #3 is no answer: API-wide.
    @Test
    void slowOnOlder_stopsTheWalk_unverified() {
        three = exchange -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, FORK);
        };

        assertThat(code(verdict())).isEqualTo("pr-origin-unverified");
    }

    /// A per-PR failure with only forks otherwise is never fork-pr, and never accepted.
    @Test
    void perPullFailureAndForkOnly_isUnverified_neverForkPr() {
        three = exchange -> send(exchange, 404, "{}");
        var judge = GitHubPulls.gitHubPulls(HttpClient.newHttpClient(),
                                            URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                            "git@github.com:o/r.git",
                                            Duration.ofSeconds(3));
        server.removeContext("/");
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/3")) {
                send(exchange, 404, "{}");
            } else {
                send(exchange, 200, FORK);
            }
        });

        var result = PullJudgement.pullJudgement(judge, List.of(new PullNumber(3), new PullNumber(9))).await().unwrap().verdict(SHA);

        assertThat(code(result)).isEqualTo("pr-origin-unverified");
    }
}
