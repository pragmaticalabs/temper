package org.pragmatica.temper.controller.git;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOriginError;

import static org.assertj.core.api.Assertions.assertThat;

/// The GitHub PR-origin judge (R-M2-7) against a local HTTP server standing in for `api.github.com`: same-repo iff
/// `head.repo.full_name == base.repo.full_name`; a deleted fork (null `head.repo`) is a fork; every other outcome
/// fails, so the submit refuses (fail closed). No token is ever sent.
class GitHubPullsTest {
    private static final String ORIGIN = "git@github.com:pragmaticalabs/pragmatica.git";
    private static final PullNumber PR = new PullNumber(7);

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "";
    private volatile long delayMs = 0;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().getPath());
        authorizations.addAll(exchange.getRequestHeaders().getOrDefault("Authorization", List.of()));
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private GitHubPulls pulls(String origin, Duration timeout) {
        return GitHubPulls.gitHubPulls(HttpClient.newHttpClient(),
                                       URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                       origin,
                                       timeout);
    }

    private Result<PullOrigin> origin() {
        return pulls(ORIGIN, Duration.ofSeconds(5)).origin(PR).await();
    }

    private static String pull(String head, String base) {
        return "{\"number\":7,\"state\":\"open\",\"head\":{\"ref\":\"x\",\"sha\":\"abc\",\"repo\":" + head + "},"
               + "\"base\":{\"ref\":\"main\",\"repo\":" + base + "},\"user\":{\"login\":\"someone\"}}";
    }

    private static String repo(String fullName) {
        return "{\"id\":1,\"full_name\":\"" + fullName + "\",\"fork\":false}";
    }

    private static Cause failure(Result<PullOrigin> result) {
        assertThat(result.isFailure()).as("fails closed: " + result).isTrue();
        return result.fold(cause -> cause, _ -> null);
    }

    @Nested
    class Judgement {
        @Test
        void origin_sameRepo_headRepoIsTheBaseRepo() {
            body = pull(repo("pragmaticalabs/pragmatica"), repo("pragmaticalabs/pragmatica"));

            assertThat(origin().unwrap()).isEqualTo(PullOrigin.SAME_REPO);
            assertThat(paths).containsExactly("/repos/pragmaticalabs/pragmatica/pulls/7");
            assertThat(authorizations).as("unauthenticated: no token is ever sent").isEmpty();
        }

        @Test
        void origin_fork_headRepoIsAnotherRepo() {
            body = pull(repo("someone/pragmatica"), repo("pragmaticalabs/pragmatica"));

            assertThat(origin().unwrap()).isEqualTo(PullOrigin.FORK);
        }

        @Test
        void origin_fork_deletedForkHasNullHeadRepo() {
            body = pull("null", repo("pragmaticalabs/pragmatica"));

            assertThat(origin().unwrap()).isEqualTo(PullOrigin.FORK);
        }

        @Test
        void origin_fails_noBaseRepo() {
            body = pull(repo("pragmaticalabs/pragmatica"), "null");

            assertThat(failure(origin())).isEqualTo(PullOriginError.General.INCOMPLETE);
        }

        @Test
        void origin_fails_headRepoWithoutFullName() {
            body = pull("{\"id\":1}", repo("pragmaticalabs/pragmatica"));

            assertThat(failure(origin())).isEqualTo(PullOriginError.General.INCOMPLETE);
        }

        @Test
        void origin_fails_noHead() {
            body = "{\"base\":{\"repo\":" + repo("pragmaticalabs/pragmatica") + "}}";

            assertThat(failure(origin())).isEqualTo(PullOriginError.General.INCOMPLETE);
        }

        @Test
        void origin_fails_unparseableBody() {
            body = "<html>not json</html>";

            assertThat(failure(origin())).isInstanceOf(PullOriginError.Unparseable.class);
        }
    }

    /// A PR's head repository never changes, so a judged origin is asked once for the controller's lifetime.
    @Nested
    class Cache {
        @Test
        void origin_secondCallMakesNoRequest_onceJudged() {
            body = pull(repo("someone/pragmatica"), repo("pragmaticalabs/pragmatica"));
            var pulls = pulls(ORIGIN, Duration.ofSeconds(5));

            assertThat(pulls.known(PR).isEmpty()).isTrue();
            assertThat(pulls.origin(PR).await().unwrap()).isEqualTo(PullOrigin.FORK);
            assertThat(pulls.origin(PR).await().unwrap()).isEqualTo(PullOrigin.FORK);
            assertThat(paths).hasSize(1);
            assertThat(pulls.known(PR).unwrap()).isEqualTo(PullOrigin.FORK);
        }

        @Test
        void origin_failureNotKept_askedAgain() {
            status = 403;
            var pulls = pulls(ORIGIN, Duration.ofSeconds(5));

            assertThat(pulls.origin(PR).await().isFailure()).isTrue();
            status = 200;
            body = pull(repo("pragmaticalabs/pragmatica"), repo("pragmaticalabs/pragmatica"));
            assertThat(pulls.origin(PR).await().unwrap()).isEqualTo(PullOrigin.SAME_REPO);
            assertThat(paths).hasSize(2);
        }
    }

    @Nested
    class FailClosed {
        @ParameterizedTest
        @ValueSource(ints = {403, 429, 404, 500, 502})
        void origin_failsStatus_anyAnswerBut200(int code) {
            status = code;
            body = pull(repo("pragmaticalabs/pragmatica"), repo("pragmaticalabs/pragmatica"));

            var cause = failure(origin());

            assertThat(cause).isInstanceOf(PullOriginError.Status.class);
            assertThat(((PullOriginError.Status) cause).status()).isEqualTo(code);
        }

        @Test
        void origin_failsCallFailed_apiDown() {
            var pulls = pulls(ORIGIN, Duration.ofSeconds(5));
            server.stop(0);

            assertThat(failure(pulls.origin(PR).await())).isInstanceOf(PullOriginError.CallFailed.class);
        }

        @Test
        void origin_failsCallFailed_noAnswerWithinTheTimeout() {
            delayMs = 2000;
            body = pull(repo("pragmaticalabs/pragmatica"), repo("pragmaticalabs/pragmatica"));

            assertThat(failure(pulls(ORIGIN, Duration.ofMillis(200)).origin(PR).await())).isInstanceOf(PullOriginError.CallFailed.class);
        }

        @Test
        void origin_failsNotGithub_withoutAnyCall_localOrigin() {
            assertThat(failure(pulls("/srv/git/pragmatica.git", Duration.ofSeconds(5)).origin(PR).await()))
                .isEqualTo(PullOriginError.General.NOT_GITHUB);
            assertThat(paths).isEmpty();
        }
    }

    @Nested
    class OriginUrl {
        @ParameterizedTest
        @ValueSource(strings = {"git@github.com:pragmaticalabs/pragmatica.git", "git@github.com:pragmaticalabs/pragmatica",
                                "https://github.com/pragmaticalabs/pragmatica.git", "https://github.com/pragmaticalabs/pragmatica",
                                "https://x-access:secret@github.com/pragmaticalabs/pragmatica.git",
                                "ssh://git@github.com/pragmaticalabs/pragmatica.git", "https://github.com/pragmaticalabs/pragmatica/"})
        void gitHubRepo_parses_githubUrls(String url) {
            assertThat(GitHubRepo.gitHubRepo(url).unwrap().fullName()).isEqualTo("pragmaticalabs/pragmatica");
        }

        @Test
        void gitHubRepo_keepsDotsInsideTheName() {
            assertThat(GitHubRepo.gitHubRepo("git@github.com:o/.github.git").unwrap().fullName()).isEqualTo("o/.github");
        }

        @ParameterizedTest
        @ValueSource(strings = {"/srv/git/pragmatica.git", "file:///srv/pragmatica.git", "git@gitlab.com:o/r.git",
                                "https://github.com.evil.example/o/r.git", "https://github.com/o", "https://github.com/o/r/pulls",
                                "git@github.com:o/..", "https://github.com/../r.git", ""})
        void gitHubRepo_failsNotGithub_otherUrls(String url) {
            assertThat(GitHubRepo.gitHubRepo(url).isFailure()).as(url).isTrue();
        }
    }
}
