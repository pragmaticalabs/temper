package org.pragmatica.kiln.service.github;

import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// [JdkGitHubHttp] against a [GitHubFake]: the headers it sends (bearer only with a token, `If-None-Match` only with an
/// ETag) and reads (ETag, rate, `Link: rel="next"` as a path), paths refused off the API host, and every failure (a
/// closed port, the whole-exchange deadline, a body over the cap) without the token in it.
class JdkGitHubHttpTest {
    private static final String TOKEN = "ghp_secret0123456789";
    private static final Instant RESET = Instant.ofEpochSecond(T0.getEpochSecond() + 900);

    private GitHubFake fake;

    @BeforeEach
    void start() {
        fake = GitHubFake.start();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @Test
    void get_sendsTheBearerAndIfNoneMatch_andReadsTheHeaders() {
        fake.pages("/repos/o/r/pulls?per_page=100", java.util.List.of("[1]", "[2]"));
        fake.rate(4999, 5000, RESET, false);
        var answer = await(http(some(TOKEN), Duration.ofSeconds(5)).get("/repos/o/r/pulls?per_page=100", some("\"x\"")));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("[1]");
        assertThat(answer.etag()).isEqualTo(some(GitHubFake.etag("[1]")));
        assertThat(answer.remaining()).isEqualTo(some(4998));
        assertThat(answer.limit()).isEqualTo(some(5000));
        assertThat(answer.reset()).isEqualTo(some(RESET));
        assertThat(answer.next()).isEqualTo(some("/repos/o/r/pulls?per_page=100&page=2"));
        assertThat(fake.requests()).singleElement()
                  .isEqualTo(new GitHubFake.Request("/repos/o/r/pulls?per_page=100", "Bearer " + TOKEN, "\"x\""));
    }

    /// `Retry-After` is read as seconds or as an RFC 1123 date; any other value is still a `Retry-After`, unreadable.
    @Test
    void get_readsRetryAfterSecondsOrDate_andUnreadableOtherwise() {
        fake.answer("/repos/o/r/a", 403, "{}");
        fake.retryAfter("/repos/o/r/a", "30");
        fake.answer("/repos/o/r/b", 403, "{}");
        fake.retryAfter("/repos/o/r/b", "Wed, 21 Oct 2026 07:28:00 GMT");
        fake.answer("/repos/o/r/c", 403, "{}");
        var http = http(none(), Duration.ofSeconds(5));

        assertThat(await(http.get("/repos/o/r/a", none())).retryAfter()).isEqualTo(some(new GhAnswer.RetryAfter.Delay(Duration.ofSeconds(30))));
        assertThat(await(http.get("/repos/o/r/b", none())).retryAfter()).isEqualTo(some(new GhAnswer.RetryAfter.At(Instant.parse("2026-10-21T07:28:00Z"))));
        assertThat(await(http.get("/repos/o/r/c", none())).retryAfter()).isEqualTo(none());
        assertThat(JdkGitHubHttp.retryAfter("soon")).isEqualTo(new GhAnswer.RetryAfter.Unreadable());
        assertThat(JdkGitHubHttp.retryAfter(" 7 ")).isEqualTo(new GhAnswer.RetryAfter.Delay(Duration.ofSeconds(7)));
    }

    @Test
    void get_sendsNoAuthorization_withoutAToken_andAnswersA304() {
        fake.answer("/repos/o/r/x", "{}");
        var answer = await(http(none(), Duration.ofSeconds(5)).get("/repos/o/r/x", some(GitHubFake.etag("{}"))));

        assertThat(answer.status()).isEqualTo(304);
        assertThat(answer.body()).isEmpty();
        assertThat(fake.requests()).singleElement()
                  .isEqualTo(new GitHubFake.Request("/repos/o/r/x",
                                                    null,
                                                    GitHubFake.etag("{}")));
    }

    @Test
    void get_answersAnErrorStatus_asAnAnswer() {
        assertThat(await(http(none(), Duration.ofSeconds(5)).get("/repos/o/r/missing", none())).status()).isEqualTo(404);
    }

    @Test
    void get_refusesAPathOffTheApiHost() {
        var http = http(some(TOKEN), Duration.ofSeconds(5));

        assertThat(failure(http.get("//evil.example/steal", none()))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(failure(http.get("@evil.example/steal", none()))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(failure(http.get("/a b", none()))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void get_failsUnreachable_onAClosedPort_withoutTheToken() throws Exception {
        URI closed;

        try (var socket = new ServerSocket(0)) {
            closed = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }

        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), closed, some(TOKEN), Duration.ofSeconds(5));
        var cause = failure(http.get("/repos/o/r/x", none()));

        assertThat(cause).isInstanceOf(GitHubReadError.Unreachable.class);
        assertThat(chain(cause)).doesNotContain(TOKEN);
    }

    @Test
    void get_failsOnTheWholeExchangeDeadline() {
        fake.answer("/repos/o/r/slow", "{}");
        fake.delay(Duration.ofSeconds(3));
        assertThat(failure(http(some(TOKEN), Duration.ofMillis(300)).get("/repos/o/r/slow", none()))).isInstanceOf(GitHubReadError.Unreachable.class);
    }

    @Test
    void get_failsOnABodyOverTheCap() {
        fake.answer("/repos/o/r/huge", "x".repeat((int) JdkGitHubHttp.MAX_BODY + 1));
        var cause = failure(http(some(TOKEN), Duration.ofSeconds(30)).get("/repos/o/r/huge", none()));

        assertThat(cause).isInstanceOf(GitHubReadError.Unreachable.class);
        assertThat(chain(cause)).doesNotContain(TOKEN);
    }

    /// The one POST (#102): a GraphQL read to `/graphql` is sent, with the bearer and the body as given.
    @Test
    void post_sendsAGraphQlRead_toGraphql() {
        var body = "{\"query\":\"query($q: String!) { search(query: $q, type: ISSUE, first: 1) { nodes { __typename } } }\"}";

        fake.answer("/graphql", "{\"data\":{}}");
        var answer = await(http(some(TOKEN), Duration.ofSeconds(5)).post("/graphql", body));

        assertThat(answer.status()).isEqualTo(200);
        assertThat(fake.requests()).singleElement()
                  .isEqualTo(new GitHubFake.Request("/graphql", "Bearer " + TOKEN, null, body));
    }

    /// A mutation, a subscription, or a mutation beside a query, is refused before anything is sent.
    @Test
    void post_refusesAMutation_sendingNothing() {
        fake.answer("/graphql", "{\"data\":{}}");
        var http = http(some(TOKEN), Duration.ofSeconds(5));

        assertThat(failure(http.post("/graphql",
                                     "{\"query\":\"mutation { addComment(input: {subjectId: \\\"x\\\", body: \\\"y\\\"}) { clientMutationId } }\"}"))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(failure(http.post("/graphql", "{\"query\":\"query { viewer { login } } mutation M { x }\"}"))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(failure(http.post("/graphql", "{\"query\":\"subscription { x }\"}"))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(fake.requests()).isEmpty();
    }

    /// A POST to any path but `/graphql` is refused before anything is sent, whatever its body.
    @Test
    void post_refusesAnyOtherPath_sendingNothing() {
        fake.answer("/repos/o/r/issues/1/comments", "{}");
        var http = http(some(TOKEN), Duration.ofSeconds(5));

        assertThat(failure(http.post("/repos/o/r/issues/1/comments", "{\"body\":\"hi\"}"))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(failure(http.post("/graphql/", "{\"query\":\"{ viewer { login } }\"}"))).isInstanceOf(GitHubReadError.BadPath.class);
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void next_keepsOnlyThePathAndQuery() {
        assertThat(JdkGitHubHttp.next("<https://api.github.com/repositories/1/pulls?state=all&page=2>; rel=\"next\", "
                                     + "<https://api.github.com/repositories/1/pulls?page=14>; rel=\"last\"")).isEqualTo(some("/repositories/1/pulls?state=all&page=2"));
        assertThat(JdkGitHubHttp.next("<https://api.github.com/x?page=1>; rel=\"prev\"")).isEqualTo(none());
    }

    private GitHubHttp http(Option<String> token, Duration timeout) {
        return JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), fake.api(), token, timeout);
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await()
                      .unwrap();
    }

    private static <T> Cause failure(Promise<T> promise) {
        return promise.await()
                      .fold(cause -> cause,
                            value -> {
                                throw new AssertionError("expected a failure, got " + value);
                            });
    }

    private static String chain(Cause cause) {
        return cause.stream()
                    .map(Cause::message)
                    .reduce("", String::concat) + cause;
    }
}
