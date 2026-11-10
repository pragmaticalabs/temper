package org.pragmatica.temper.controller.spike;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// m0-design §2.4 spike: does rc4 `http-server` deliver WebSocket Open, Text and Close to the handler?
///
/// Outcome (2026-10-13, the pre-release rc4 build): NO. Netty's `WebSocketServerProtocolHandler` performed the upgrade in
/// front of rc4's request handler, so the handler never stored its per-channel session state and received no frames,
/// while the client still completed the handshake. The controller uses the long-poll transport (§6.5 fallback) instead.
///
/// Outcome (2026-10-18, the released 1.0.0-rc4 on Maven Central): YES, the handler receives the Text and its echo
/// arrives; the canary that pinned the defect went red. This test now pins the working behaviour. Long-poll stays (maintainer
/// ruling 2026-10-18, m0-design §2.4): it works and nothing needs push latency.
class WebSocketSpikeTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();

    @Test
    void rc4WebSocket_deliversTextToHandler_andTheEchoArrives() throws Exception {
        var seen = new CopyOnWriteArrayList<String>();
        var port = freePort();
        var config = HttpServerConfig.httpServerConfig("ws-spike", port)
                                     .withWebSocket(WebSocketEndpoint.webSocketEndpoint("/api/v1/events",
                                                                                       () -> (session, message) -> record(seen,
                                                                                                                          session,
                                                                                                                          message)));
        var server = HttpServer.httpServer(config, (request, response) -> response.okText("http"))
                               .await(WAIT)
                               .unwrap();
        var replies = new LinkedBlockingQueue<String>();

        try {
            var socket = HttpClient.newHttpClient()
                                   .newWebSocketBuilder()
                                   .connectTimeout(Duration.ofSeconds(5))
                                   .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/v1/events"),
                                               new Collector(replies))
                                   .get(10, TimeUnit.SECONDS);

            socket.sendText("ping", true).get(5, TimeUnit.SECONDS);
            var echo = replies.poll(3, TimeUnit.SECONDS);
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);
            Thread.sleep(500);

            var control = HttpClient.newHttpClient()
                                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/x")).build(),
                                          HttpResponse.BodyHandlers.ofString());

            assertThat(control.body()).as("instrument control: the same server answers plain HTTP").isEqualTo("http");
            assertThat(echo).as("the handler saw the Text and its echo arrived").isEqualTo("echo:ping");
            assertThat(seen).as("released rc4 delivers the Text to the handler").contains("Text");
        } finally {
            server.stop().await(WAIT);
        }
    }

    private static void record(List<String> seen, WebSocketSession session, WebSocketMessage message) {
        seen.add(message.getClass().getSimpleName());
        if (message instanceof WebSocketMessage.Text text) {
            session.send("echo:" + text.content());
        }
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record Collector(LinkedBlockingQueue<String> replies) implements WebSocket.Listener {
        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            replies.add(data.toString());
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
    }
}
