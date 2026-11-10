package org.pragmatica.kiln.service.http;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// [Router] (kiln-f1-design §4): no credentials; an unknown path is 404, a known path under another method 405; a
/// route's failure degrades to its [ApiError] status, else 500, and a handler that throws still answers. [LoopbackServer]'s
/// query reading: decoded, the first of a repeated name kept.
class RouterTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();

    private final Router router = Router.router(List.of(Route.get("/ok", request -> Promise.success(ApiResponse.text("q=" + request.param("q").or("-")))),
                                                        Route.get("/bad", _ -> ApiError.badRequest("x", "why").promise()),
                                                        Route.get("/broken", _ -> Causes.cause("db down").promise()),
                                                        Route.get("/throws", _ -> {
                                                            throw new IllegalStateException("boom");
                                                        }),
                                                        Route.post("/in", request -> Promise.success(ApiResponse.json(request.body())))));

    private ApiResponse handle(String method, String path, Map<String, String> query) {
        return router.handle(ApiRequest.apiRequest(method, path, query, "{}")).await(WAIT).unwrap();
    }

    @Test
    void handle_routesByMethodAndExactPath() {
        assertThat(handle("GET", "/ok", Map.of("q", "1"))).isEqualTo(ApiResponse.text("q=1"));
        assertThat(handle("GET", "/ok", Map.of("q", " "))).isEqualTo(ApiResponse.text("q=-"));
        assertThat(handle("POST", "/in", Map.of())).isEqualTo(ApiResponse.json("{}"));
        assertThat(handle("GET", "/ok/", Map.of()).status()).isEqualTo(404);
        assertThat(handle("GET", "/nope", Map.of()).status()).isEqualTo(404);
        assertThat(handle("POST", "/ok", Map.of()).status()).isEqualTo(405);
        assertThat(handle("GET", "/in", Map.of()).status()).isEqualTo(405);
    }

    @Test
    void handle_failuresDegradeToTheirStatus() {
        assertThat(handle("GET", "/bad", Map.of())).isEqualTo(ApiResponse.failure(400, "bad parameter x: why"));
        assertThat(handle("GET", "/broken", Map.of())).isEqualTo(ApiResponse.failure(500, "kiln could not answer: db down"));
        assertThat(handle("GET", "/throws", Map.of()).status()).isEqualTo(500);
    }

    /// Several refused parameters gathered by `Result.all` are still a 400, listing each.
    @Test
    void handle_gatheredRefusals_areA400() {
        var gathered = Router.router(List.of(Route.get("/two", _ -> Result.all(ApiError.badRequest("a", "x").<String> result(),
                                                                               ApiError.badRequest("b", "y").<String> result())
                                                                          .map((first, second) -> ApiResponse.text(first + second))
                                                                          .async())));
        var answer = gathered.handle(ApiRequest.apiRequest("GET", "/two", Map.of(), "")).await(WAIT).unwrap();

        assertThat(answer.status()).isEqualTo(400);
        assertThat(answer.body()).contains("bad parameter a: x", "bad parameter b: y");
    }

    @Test
    void query_isDecoded_firstValueWins() {
        assertThat(LoopbackServer.query(URI.create("http://h/p?status=IN_USE&root=%7E%2Fwork%2F*&root=second&flag&a=b%20c")))
            .containsExactly(Map.entry("status", "IN_USE"),
                             Map.entry("root", "~/work/*"),
                             Map.entry("flag", ""),
                             Map.entry("a", "b c"));
        assertThat(LoopbackServer.query(URI.create("http://h/p"))).isEmpty();
        assertThat(LoopbackServer.query(URI.create("http://h/p?a=1&&b=2&"))).containsExactly(Map.entry("a", "1"), Map.entry("b", "2"));
        assertThat(LoopbackServer.query(URI.create("http://h/p?=v&k="))).containsExactly(Map.entry("", "v"), Map.entry("k", ""));
    }
}
