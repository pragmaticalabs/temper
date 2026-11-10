package org.pragmatica.temper.controller.git;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOriginError;
import org.pragmatica.temper.controller.submit.PullOrigins;
import org.pragmatica.temper.controller.submit.SourceResolver;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifier fixtures for PR #22, round 2: the ordered, cached, stop-at-first-failure walk and the bounded exchange.
class PullJudgementAttackTest {
    private static String pull(String head, String base) {
        return "{\"head\":{\"repo\":{\"full_name\":\"" + head + "\"}},\"base\":{\"repo\":{\"full_name\":\"" + base + "\"}}}";
    }

    @Nested
    class Walk {
        @TempDir
        Path dir;
        private Path work;
        private GitCli git;
        private String same;
        private HttpServer server;

        private String run(Path in, String... argv) throws IOException, InterruptedException {
            var process = HermeticProcess.builder(argv).directory(in.toFile()).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();
            return out;
        }

        /// `same` is on no branch, only on the PR heads given.
        private void pushSameTo(long... pulls) throws IOException, InterruptedException {
            var argv = new ArrayList<>(List.of("git", "push", "-q", "origin"));
            for (var n : pulls) {
                argv.add(same + ":refs/pull/" + n + "/head");
            }
            run(work, argv.toArray(String[]::new));
        }

        @BeforeEach
        void setUp() throws Exception {
            var bare = dir.resolve("origin.git");
            work = Files.createDirectories(dir.resolve("work"));
            run(dir, "git", "init", "-q", "--bare", "-b", "main", bare.toString());
            run(work, "git", "init", "-q", "-b", "main");
            run(work, "git", "config", "user.email", "t@t");
            run(work, "git", "config", "user.name", "t");
            run(work, "git", "remote", "add", "origin", bare.toString());
            Files.writeString(work.resolve("README"), "one\n");
            run(work, "git", "add", "README");
            run(work, "git", "commit", "-q", "-m", "one");
            run(work, "git", "push", "-q", "origin", "main");
            run(work, "git", "checkout", "-q", "--detach", "main");
            Files.writeString(work.resolve("same"), "same\n");
            run(work, "git", "add", "same");
            run(work, "git", "commit", "-q", "-m", "same");
            same = run(work, "git", "rev-parse", "HEAD");
            git = GitCli.gitCli(ProcessRunner.processRunner(), bare.toString(), dir.resolve("mirror.git"));
        }

        @AfterEach
        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        private Result<PushedSource> bySha(PullOrigins pulls) {
            return SourceResolver.sourceResolver(git, git, pulls).resolve(SourceRequest.bySha(same).unwrap()).await();
        }

        private static void assertRefused(Result<PushedSource> result, String code) {
            assertThat(result.isFailure()).as("refused " + code + ": " + result).isTrue();
            result.onFailure(cause -> assertThat(((Refusal) cause).code()).as(cause.message()).isEqualTo(code));
        }

        /// An OLD fork PR force-pushed to contain the same-repo SHA sorts before it, but a fork does not stop the walk.
        @Test
        void olderForkForcePushed_doesNotStopTheWalk() throws Exception {
            pushSameTo(3, 9);
            var asked = new CopyOnWriteArrayList<PullNumber>();
            PullOrigins pulls = pull -> {
                asked.add(pull);
                return Promise.success(pull.value() == 9 ? PullOrigin.SAME_REPO : PullOrigin.FORK);
            };

            assertThat(bySha(pulls).isSuccess()).isTrue();
            assertThat(asked).containsExactly(new PullNumber(3), new PullNumber(9));
        }

        /// Was a finding: an older PR whose API answer keeps failing (a 404, e.g. a PR GitHub hides) stopped every walk
        /// before the same-repo PR. A per-PR failure no longer stops the walk.
        @Test
        void olderPersistentlyFailingPr_doesNotDenyLaterSameRepo() throws Exception {
            pushSameTo(3, 9);
            var asked = new CopyOnWriteArrayList<PullNumber>();
            PullOrigins pulls = pull -> {
                asked.add(pull);
                return pull.value() == 9
                       ? Promise.success(PullOrigin.SAME_REPO)
                       : PullOriginError.Status.FACTORY.apply(404).promise();
            };

            assertThat(bySha(pulls).isSuccess()).isTrue();
            assertThat(asked).containsExactly(new PullNumber(3), new PullNumber(9));
        }

        /// A rate limit is about the API, not the PR: it stops the walk to save the budget, and the PRs not reached are
        /// named as not checked.
        @Test
        void rateLimitOnAnOlderPr_stopsTheWalk() throws Exception {
            pushSameTo(3, 9);
            var asked = new CopyOnWriteArrayList<PullNumber>();
            PullOrigins pulls = pull -> {
                asked.add(pull);
                return pull.value() == 9
                       ? Promise.success(PullOrigin.SAME_REPO)
                       : PullOriginError.Status.FACTORY.apply(429).promise();
            };

            var result = bySha(pulls);

            assertRefused(result, "pr-origin-unverified");
            result.onFailure(cause -> assertThat(((Refusal) cause).detail()).contains("HTTP 429", "not checked (#9)"));
            assertThat(asked).containsExactly(new PullNumber(3));
        }

        /// A per-PR failure is still a failure: with no same-repo PR it refuses as unverified, never as `fork-pr`.
        @Test
        void perPullFailure_plusFork_isPrOriginUnverified_neverForkPr() throws Exception {
            pushSameTo(3, 9);
            var asked = new CopyOnWriteArrayList<PullNumber>();
            PullOrigins pulls = pull -> {
                asked.add(pull);
                return pull.value() == 9
                       ? Promise.success(PullOrigin.FORK)
                       : PullOriginError.Status.FACTORY.apply(404).promise();
            };

            assertRefused(bySha(pulls), "pr-origin-unverified");
            assertThat(asked).containsExactly(new PullNumber(3), new PullNumber(9));
        }

        /// Rate-limit exhaustion by many older fork PRs, through the real cached judge: refused while the limit lasts,
        /// progress is kept (forks cached), and the SHA is accepted once the limit resets. Bounded, fail closed.
        @Test
        void manyOlderForks_rateLimit_refusedThenAcceptedAfterReset() throws Exception {
            var forks = new long[70];
            for (int i = 0; i < 70; i++) {
                forks[i] = i + 1;
            }
            pushSameTo(forks);
            pushSameTo(100);
            var budget = new AtomicInteger(60);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                var path = exchange.getRequestURI().getPath();
                var ok = budget.getAndDecrement() > 0;
                var body = ok
                           ? (path.endsWith("/100") ? pull("o/r", "o/r") : pull("x/r", "o/r"))
                           : "{\"message\":\"API rate limit exceeded\"}";
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(ok ? 200 : 403, bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            var judge = GitHubPulls.gitHubPulls(HttpClient.newHttpClient(),
                                                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                                "git@github.com:o/r.git",
                                                Duration.ofSeconds(5));

            assertRefused(bySha(judge), "pr-origin-unverified");
            assertThat(judge.known()).hasSize(60);
            assertRefused(bySha(judge), "pr-origin-unverified");
            budget.set(60);
            assertThat(bySha(judge).isSuccess()).isTrue();
            assertThat(judge.known().get(new PullNumber(100))).isEqualTo(PullOrigin.SAME_REPO);
        }
    }

    @Nested
    class Exchange {
        private HttpServer server;

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private Result<PullOrigin> origin(Duration timeout) {
            return GitHubPulls.gitHubPulls(HttpClient.newHttpClient(),
                                           URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                                           "git@github.com:o/r.git",
                                           timeout)
                              .origin(new PullNumber(7))
                              .await();
        }

        /// The deadline cancels the exchange: the server sees its connection closed soon after, so no connection is
        /// left streaming.
        @Test
        void deadline_cancelsTheExchange_serverSeesTheConnectionClosed() throws Exception {
            var writeFailedAt = new AtomicLong();
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    for (int i = 0; i < 200; i++) {
                        out.write(new byte[1024]);
                        out.flush();
                        Thread.sleep(50);
                    }
                } catch (IOException e) {
                    writeFailedAt.set(System.nanoTime());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            var started = System.nanoTime();

            var result = origin(Duration.ofMillis(500));

            assertThat(result.isFailure()).as(String.valueOf(result)).isTrue();
            assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2000);
            var deadline = System.nanoTime() + 4_000_000_000L;
            while (writeFailedAt.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(writeFailedAt.get()).as("the server saw the client close the connection").isNotZero();
            assertThat((writeFailedAt.get() - started) / 1_000_000).isLessThan(4000);
        }

        /// Chunked (no Content-Length) and over the cap: refused, not buffered whole.
        @Test
        void chunkedBodyOverTheCap_failsCallFailed() {
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    out.write(pull("o/r", "o/r").getBytes(StandardCharsets.UTF_8));
                    for (int i = 0; i < 2 * 1024; i++) {
                        out.write(" ".repeat(1024).getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                    // the client gave up, as intended
                }
            });

            var result = origin(Duration.ofSeconds(5));

            assertThat(result.isFailure()).as(String.valueOf(result)).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(PullOriginError.CallFailed.class));
        }

        /// The client never decompresses, so a gzip answer is unreadable (fail closed) and no gzip bomb can expand.
        @Test
        void gzipBody_isNeverDecompressed_failsClosed() throws Exception {
            var zipped = new ByteArrayOutputStream();
            try (var gz = new GZIPOutputStream(zipped)) {
                gz.write(pull("o/r", "o/r").getBytes(StandardCharsets.UTF_8));
            }
            server.createContext("/", exchange -> {
                exchange.getResponseHeaders().add("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, zipped.size());
                try (var out = exchange.getResponseBody()) {
                    out.write(zipped.toByteArray());
                }
            });

            var result = origin(Duration.ofSeconds(5));

            assertThat(result.isFailure()).as(String.valueOf(result)).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(PullOriginError.Unparseable.class));
        }
    }
}
