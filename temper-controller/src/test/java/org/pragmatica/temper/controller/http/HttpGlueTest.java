package org.pragmatica.temper.controller.http;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;

/// D-6 over a real rc4 HTTP server: the version gate runs before authentication and before any handler (M1), and every
/// reply this adapter makes, rc4's own 500 for a throwing handler included, carries the controller's version (L3).
class HttpGlueTest {
    private static final String OWN = "0.1.0-SNAPSHOT+111111111111";
    private static final String OTHER = "0.1.0-SNAPSHOT+222222222222";
    private static final String TOKEN = "glue-token";

    private final AtomicInteger writes = new AtomicInteger();
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        var routes = List.of(Route.route(HttpMethod.POST, "write", this::write),
                             Route.route(HttpMethod.POST, "boom", HttpGlueTest::boom));
        var router = Router.router(Credentials.credentials(BearerToken.bearerToken(TOKEN)
                                                                      .unwrap(),
                                                           Option.none()),
                                   routes,
                                   Responses.responses(TemperJson.mapper()));
        var port = freePort();
        server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("glue-test", port),
                                       HttpGlue.httpGlue(router, VersionGate.versionGate(OWN, routes)))
                           .await()
                           .unwrap();
        base = "http://127.0.0.1:" + port + "/api/v1/";
    }

    @AfterEach
    void stop() {
        server.stop()
              .await();
    }

    private Promise<ApiResponse> write(ApiRequest request) {
        writes.incrementAndGet();
        return Promise.success(Responses.noContent());
    }

    private static Promise<ApiResponse> boom(ApiRequest request) {
        throw new IllegalStateException("handler threw");
    }

    @Test
    void skewedWrite_withoutToken_is412_beforeAuthentication_andTheHandlerNeverRuns() throws Exception {
        var reply = post("write", Map.of("Temper-Version", OTHER));

        assertThat(reply.statusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED.code());
        assertThat(reply.body()).contains(OTHER, OWN);
        assertThat(reply.headers()
                        .firstValue("Temper-Version")).contains(OWN);
        assertThat(writes.get()).isZero();
    }

    @Test
    void sameBuildWrite_isAuthenticatedAsBefore() throws Exception {
        assertThat(post("write", Map.of("Temper-Version", OWN)).statusCode()).isEqualTo(401);
        assertThat(post("write", Map.of("Temper-Version", OWN, "Authorization", "Bearer " + TOKEN)).statusCode()).isEqualTo(204);
        assertThat(writes.get()).isEqualTo(1);
    }

    /// L3: rc4 answers a handler that throws with its own 500 on the same writer, after the header was set.
    @Test
    void throwingHandler_rc4s500_carriesTheVersion() throws Exception {
        var reply = post("boom", Map.of("Temper-Version", OWN, "Authorization", "Bearer " + TOKEN));

        assertThat(reply.statusCode()).isEqualTo(500);
        assertThat(reply.headers()
                        .firstValue("Temper-Version")).contains(OWN);
    }

    private HttpResponse<String> post(String path, Map<String, String> headers) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                                 .timeout(Duration.ofSeconds(10))
                                 .POST(HttpRequest.BodyPublishers.ofString("{}"));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
