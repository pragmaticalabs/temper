package org.pragmatica.kiln.service.http;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [LoopbackServer] over a socket: the 8 MiB body cap (a body of exactly 8 MiB reaches its route, one byte more is 413
/// before any route sees it), the answer's status, type and body, and `no-store`.
class LoopbackServerTest {
    private final HttpClient client = HttpClient.newHttpClient();
    private LoopbackServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop().await(TimeSpan.timeSpan(10).seconds());
        }
    }

    private static boolean met(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/in"))
                                      .POST(HttpRequest.BodyPublishers.ofString(body))
                                      .build(),
                           HttpResponse.BodyHandlers.ofString());
    }

    /// Exchanges run each on its own virtual thread: two requests that wait for each other both finish.
    @Test
    void exchanges_runAtOnce() throws Exception {
        var both = new CountDownLatch(2);

        server = LoopbackServer.start(0, Router.router(List.of(Route.get("/meet", _ -> {
            both.countDown();
            return Promise.success(ApiResponse.text(met(both) ? "both" : "alone"));
        })))).unwrap();

        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/meet")).build();
        var first = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        assertThat(List.of(first.get(20, TimeUnit.SECONDS).body(), second.get(20, TimeUnit.SECONDS).body())).containsExactly("both", "both");
    }

    /// A stopped server refuses connections.
    @Test
    void stop_refusesConnections() throws Exception {
        server = LoopbackServer.start(0, Router.router(List.of())).unwrap();
        var port = server.port();

        server.stop().await(TimeSpan.timeSpan(10).seconds());
        server = null;
        assertThatThrownBy(() -> new Socket().connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000))
            .isInstanceOf(ConnectException.class);
    }

    @Test
    void body_isCappedAt8MiB() throws Exception {
        server = LoopbackServer.start(0, Router.router(List.of(Route.post("/in", request -> Promise.success(ApiResponse.text(Integer.toString(request.body().length())))))))
                               .unwrap();

        var atCap = post("x".repeat(LoopbackServer.MAX_BODY));
        var overCap = post("x".repeat(LoopbackServer.MAX_BODY + 1));

        assertThat(atCap.statusCode()).isEqualTo(200);
        assertThat(atCap.body()).isEqualTo(Integer.toString(LoopbackServer.MAX_BODY));
        assertThat(atCap.headers().firstValue("Content-Type")).hasValue(ApiResponse.TEXT);
        assertThat(atCap.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(overCap.statusCode()).isEqualTo(413);
    }
}
