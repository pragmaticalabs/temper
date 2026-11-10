package org.pragmatica.temper.controller.git;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOriginError;
import org.pragmatica.temper.ghapp.AppAuthError;
import org.pragmatica.temper.ghapp.GitHubAppAuth;
import org.pragmatica.temper.ghapp.SecretToken;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// positive controls C.1-C.3 for the controller's PR judge, against local servers standing in
/// for `api.github.com` and for "another host".
class GitHubPullsAuthTest {
    private static final String ORIGIN = "git@github.com:pragmaticalabs/pragmatica.git";

    private static final PullNumber PR = new PullNumber(7);

    private static final String SAME = "{\"head\":{\"repo\":{\"full_name\":\"o/r\"}},\"base\":{\"repo\":{\"full_name\":\"o/r\"}},\"merged\":false}";

    private HttpServer api;
    private HttpServer elsewhere;
    private volatile int status = 200;
    private volatile String location = "";
    private volatile String link = "";
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final AtomicInteger apiHits = new AtomicInteger();
    private final AtomicInteger elsewhereHits = new AtomicInteger();
    private final List<String> elsewhereAuthorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        api = server(this::answerApi);
        elsewhere = server(this::answerElsewhere);
    }

    @AfterEach
    void stop() {
        api.stop(0);
        elsewhere.stop(0);
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", handler);
        server.start();

        return server;
    }

    private void answerApi(HttpExchange exchange) throws IOException {
        apiHits.incrementAndGet();
        authorizations.addAll(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
        if (!location.isEmpty()) {
            exchange.getResponseHeaders().add("Location", location);
        }

        if (!link.isEmpty()) {
            exchange.getResponseHeaders().add("Link", link);
        }

        reply(exchange,
              status,
              status == 200
              ? SAME
              : "");
    }

    private void answerElsewhere(HttpExchange exchange) throws IOException {
        elsewhereHits.incrementAndGet();
        elsewhereAuthorizations.addAll(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
        reply(exchange, 200, SAME);
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);

        exchange.sendResponseHeaders(code,
                                     bytes.length == 0
                                     ? -1
                                     : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress()
                                           .getPort();
    }

    private GitHubPulls authenticated(GitHubAppAuth auth) {
        return GitHubPulls.gitHubPulls(URI.create(url(api)), ORIGIN, Duration.ofSeconds(5), auth);
    }

    private static GitHubAppAuth token(String value) {
        return () -> Promise.success(new SecretToken(value));
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> null);
    }

    /// C.1
    @Test
    void withAnApp_everyRequestCarriesTheBearerToken_andWithoutOneNone() {
        var pulls = authenticated(token("ghs_installation"));

        assertThat(pulls.origin(PR).await().unwrap()).isEqualTo(PullOrigin.SAME_REPO);
        assertThat(pulls.isMerged(7).await().unwrap()).isFalse();
        assertThat(authorizations).containsExactly("Bearer ghs_installation", "Bearer ghs_installation");
        authorizations.clear();
        var anonymous = GitHubPulls.gitHubPulls(URI.create(url(api)), ORIGIN);

        assertThat(anonymous.origin(PR).await().unwrap()).isEqualTo(PullOrigin.SAME_REPO);
        assertThat(anonymous.isMerged(7).await().unwrap()).isFalse();
        assertThat(authorizations).as("without [github.app] no Authorization header is sent").isEmpty();
    }

    /// C.2: the submit's fork check reads [GitHubPulls#origin] and refuses on any failure (R-M2-7); T15 reads
    /// [GitHubPulls#isMerged] and refuses the close on any failure.
    @Test
    void credentialFailure_failsTheForkCheckAndTheMergedRead_andSendsNothing() {
        var pulls = authenticated(() -> AppAuthError.CredentialUnavailable.FACTORY.apply("mint refused: HTTP 401").promise());
        var origin = pulls.origin(PR).await();
        var merged = pulls.isMerged(7).await();

        assertThat(origin.isFailure()).as("fork check refuses: " + origin).isTrue();
        assertThat(causeOf(origin)).isInstanceOf(PullOriginError.CallFailed.class);
        assertThat(merged.isFailure()).as("T15 close refused: " + merged).isTrue();
        assertThat(apiHits.get()).as("no unauthenticated fallback: nothing reaches the API").isZero();
        assertThat(pulls.known()).as("a failure is not kept").isEmpty();
    }

    /// C.3
    @Test
    void aRedirectToAnotherHost_isNotFollowedWithTheToken() {
        status = 302;
        location = url(elsewhere) + "/repos/pragmaticalabs/pragmatica/pulls/7";
        var pulls = authenticated(token("ghs_installation"));
        var origin = pulls.origin(PR).await();

        assertThat(origin.isFailure()).isTrue();
        assertThat(causeOf(origin)).isInstanceOf(PullOriginError.Status.class);
        assertThat(elsewhereHits.get()).as("the other host was never contacted").isZero();
        assertThat(elsewhereAuthorizations).isEmpty();
    }

    /// C.3, the `Link` half: a pagination `Link` in an answer is data, never a request.
    @Test
    void aLinkToAnotherHost_isNeverFetched() {
        link = "<" + url(elsewhere) + "/next>; rel=\"next\"";
        var pulls = authenticated(token("ghs_installation"));

        assertThat(pulls.origin(PR).await().unwrap()).isEqualTo(PullOrigin.SAME_REPO);
        assertThat(elsewhereHits.get()).isZero();
    }
}
