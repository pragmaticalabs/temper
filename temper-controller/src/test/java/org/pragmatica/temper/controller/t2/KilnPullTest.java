package org.pragmatica.temper.controller.t2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;


/// The queue's kiln read (without `sha`) over a stub kiln: the query names the repo
/// (encoded) and the PR and no `sha`; `pull.state` and `pull.base` are read by key; a non-200 or a reply without
/// them fails the read.
class KilnPullTest {
    private final AtomicReference<String> query = new AtomicReference<>("");
    private final AtomicReference<String> reply = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private HttpServer server;
    private KilnPull kiln;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/pr-fresh", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            var bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        kiln = KilnPull.http(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TemperJson.mapper(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void pull_readsStateAndBase_fromAnR1OnlyReply() {
        reply.set("{\"asOf\":\"2026-11-04T12:00:00Z\",\"repo\":\"o/r\",\"pr\":7,\"pull\":{\"state\":\"open\",\"headSha\":\"" + "a".repeat(40)
                  + "\",\"headRef\":\"f\",\"headRepo\":null,\"base\":\"release-1\",\"changedFiles\":2},\"reads\":1}");

        var pull = kiln.pull("o/r", 7).await().unwrap();

        assertThat(pull).isEqualTo(KilnPull.PullState.pullState("open", "release-1"));
        assertThat(pull.open()).isTrue();
        assertThat(query.get()).isEqualTo("repo=o%2Fr&pr=7");
        assertThat(KilnPull.PullState.pullState("merged", "main").open()).isFalse();
    }

    @Test
    void pull_fails_onANon200_orWithoutStateOrBase() {
        status.set(503);
        reply.set("{\"error\":\"UNAVAILABLE\",\"detail\":\"rate-budget\"}");
        assertThat(kiln.pull("o/r", 7).await().isFailure()).isTrue();

        status.set(404);
        reply.set("{\"error\":\"repo not configured\"}");
        assertThat(kiln.pull("o/r", 7).await().<Object> fold(cause -> cause, value -> value)).isEqualTo(KilnPull.Failure.REPO_NOT_CONFIGURED);

        status.set(200);
        reply.set("{\"pull\":{\"state\":\"open\"}}");
        assertThat(kiln.pull("o/r", 7).await().isFailure()).isTrue();
        reply.set("{\"pull\":{\"base\":\"main\",\"state\":7}}");
        assertThat(kiln.pull("o/r", 7).await().isFailure()).isTrue();
        reply.set("not json");
        assertThat(kiln.pull("o/r", 7).await().isFailure()).isTrue();
    }
}
