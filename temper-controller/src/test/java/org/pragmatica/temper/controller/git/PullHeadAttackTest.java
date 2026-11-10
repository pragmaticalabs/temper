package org.pragmatica.temper.controller.git;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOriginError;
import org.pragmatica.temper.controller.submit.PullOrigins;
import org.pragmatica.temper.controller.submit.SourceResolver;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

/// Verifier fixtures for PR #22: attempts to get a fork-only commit accepted.
class PullHeadAttackTest {
    @Nested
    class Refs {
        @TempDir
        Path dir;
        private Path bare;
        private Path work;
        private GitCli git;
        private String forkOnly;
        private final Map<Long, PullOrigin> origins = new ConcurrentHashMap<>();
        private final List<PullNumber> asked = new CopyOnWriteArrayList<>();
        private final PullOrigins pulls = pull -> {
            asked.add(pull);
            var origin = origins.get(pull.value());
            return origin == null
                   ? Causes.cause("unscripted " + pull).promise()
                   : Promise.success(origin);
        };

        private String run(Path in, String... argv) throws IOException, InterruptedException {
            var process = HermeticProcess.builder(argv).directory(in.toFile()).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();
            return out;
        }

        private String commitOn(String base, String file) throws IOException, InterruptedException {
            run(work, "git", "checkout", "-q", "--detach", base);
            Files.writeString(work.resolve(file), file + "\n");
            run(work, "git", "add", file);
            run(work, "git", "commit", "-q", "-m", file);
            return run(work, "git", "rev-parse", "HEAD");
        }

        private void push(String sha, String ref) throws IOException, InterruptedException {
            run(work, "git", "push", "-q", "origin", sha + ":" + ref);
        }

        @BeforeEach
        void setUp() throws Exception {
            bare = dir.resolve("origin.git");
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
            forkOnly = commitOn("main", "fork7");
            push(forkOnly, "refs/pull/7/head");
            origins.put(7L, PullOrigin.FORK);
            git = GitCli.gitCli(ProcessRunner.processRunner(), bare.toString(), dir.resolve("mirror.git"));
        }

        private Result<PushedSource> resolved(SourceRequest request) {
            return SourceResolver.sourceResolver(git, git, pulls).resolve(request).await();
        }

        private Result<PushedSource> byRef(String ref) {
            return resolved(SourceRequest.byRef(ref).unwrap());
        }

        private Result<PushedSource> bySha(String sha) {
            return resolved(SourceRequest.bySha(sha).unwrap());
        }

        private static void assertRefused(Result<PushedSource> result, String code) {
            assertThat(result.isFailure()).as("refused " + code + ": " + result).isTrue();
            result.onFailure(cause -> assertThat(((Refusal) cause).code()).as(cause.message()).isEqualTo(code));
        }

        @ParameterizedTest
        @ValueSource(strings = {"pull/7/head", "7/head", "head", "HEAD"})
        void byRef_shortNames_neverReachAForkHead(String ref) {
            assertRefused(byRef(ref), "source");
        }

        /// A ref outside heads/tags/pull that a `clone --mirror` copies (refs/remotes, refs/notes-like): never counts.
        @Test
        void byRef_andBySha_otherNamespaces_refusedSource() throws Exception {
            var other = commitOn("main", "other");
            push(other, "refs/remotes/origin/x");
            push(other, "refs/changes/1/head");

            assertRefused(byRef("refs/remotes/origin/x"), "source");
            assertRefused(byRef("refs/changes/1/head"), "source");
            assertRefused(bySha(other), "source");
            assertThat(asked).isEmpty();
        }

        /// A realistic merge ref: base main merged with the fork head. Neither by ref nor by SHA.
        @Test
        void mergeRefOfAForkPr_refusedEitherWay() throws Exception {
            run(work, "git", "checkout", "-q", "--detach", "main");
            run(work, "git", "merge", "-q", "--no-ff", "-m", "merge", forkOnly);
            var merge = run(work, "git", "rev-parse", "HEAD");
            push(merge, "refs/pull/7/merge");

            assertRefused(byRef("refs/pull/7/merge"), "source");
            assertRefused(bySha(merge), "source");
            assertRefused(bySha(forkOnly), "fork-pr");
        }

        /// Tags do not count for a bare SHA.
        @Test
        void bySha_forkCommitTagged_stillForkPr() throws Exception {
            run(work, "git", "tag", "-a", "-m", "t", "t1", forkOnly);
            run(work, "git", "push", "-q", "origin", "t1");

            assertRefused(bySha(forkOnly), "fork-pr");
        }

        /// Documents behaviour, not a defect of this PR: `--ref <tag>` is accepted without reachability (pre-existing;
        /// only a writer can push a tag), so a writer's tag on a fork commit makes it runnable.
        @Test
        void byRef_annotatedTagOnForkCommit_accepted_writerVouches() throws Exception {
            run(work, "git", "tag", "-a", "-m", "t", "t2", forkOnly);
            run(work, "git", "push", "-q", "origin", "t2");

            assertThat(byRef("t2").unwrap().sha().value()).isEqualTo(forkOnly);
            assertThat(byRef("refs/tags/t2").unwrap().sha().value()).isEqualTo(forkOnly);
            assertThat(asked).isEmpty();
        }

        /// A fork head that is an ancestor of a same-repo PR head: a writer pushed it, so it counts.
        @Test
        void forkHeadAncestorOfSameRepoHead_bothAccepted() throws Exception {
            var same = commitOn(forkOnly, "same9");
            push(same, "refs/pull/9/head");
            origins.put(9L, PullOrigin.SAME_REPO);

            assertThat(bySha(forkOnly).isSuccess()).isTrue();
            assertThat(bySha(same).isSuccess()).isTrue();
        }

        /// A fork head built on a same-repo PR head: the same-repo commit counts, the fork's own commit does not.
        @Test
        void forkHeadDescendantOfSameRepoHead_onlyTheSameRepoCommit() throws Exception {
            var same = commitOn("main", "same9");
            push(same, "refs/pull/9/head");
            var fork = commitOn(same, "fork11");
            push(fork, "refs/pull/11/head");
            origins.put(9L, PullOrigin.SAME_REPO);
            origins.put(11L, PullOrigin.FORK);

            assertRefused(bySha(fork), "fork-pr");
            assertRefused(byRef("refs/pull/11/head"), "fork-pr");
            assertThat(bySha(same).isSuccess()).isTrue();
        }

        /// Was a finding: anyone can open fork PRs whose head is a same-repo commit. Ten of them sorting before the
        /// same-repo PR (refname order: "10" < "2") pushed it past a 10-candidate cap, refused as `fork-pr` with a false
        /// detail. Candidates are now judged oldest first by number, so the same-repo PR is asked first.
        @Test
        void tenForkHeadsSortingFirst_sameRepoCommitAccepted() throws Exception {
            var same = commitOn("main", "same2");
            push(same, "refs/pull/2/head");
            origins.put(2L, PullOrigin.SAME_REPO);
            for (long n = 10; n < 20; n++) {
                push(same, "refs/pull/" + n + "/head");
                origins.put(n, PullOrigin.FORK);
            }

            assertThat(bySha(same).unwrap().sha().value()).isEqualTo(same);
            assertThat(asked).containsExactly(new PullNumber(2));
        }

        /// The walk stops at the first failure; the PRs it did not reach are named, and the refusal is never `fork-pr`.
        @Test
        void refusalHonesty_unreachedCandidates_arePrOriginUnverified_neverForkPr() throws Exception {
            push(forkOnly, "refs/pull/3/head");
            push(forkOnly, "refs/pull/8/head");
            origins.put(8L, PullOrigin.FORK);

            var result = bySha(forkOnly);

            assertRefused(result, "pr-origin-unverified");
            result.onFailure(cause -> assertThat(((Refusal) cause).detail()).contains("#3: unscripted #3",
                                                                                      "2 candidate pull request(s) not checked (#7, #8)"));
            assertThat(asked).containsExactly(new PullNumber(3));
        }
    }

    @Nested
    class Api {
        private HttpServer server;
        private final List<String> paths = new CopyOnWriteArrayList<>();

        @BeforeEach
        void start() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        }

        @AfterEach
        void stop() {
            server.stop(0);
        }

        private static String pull(String head, String base) {
            return "{\"head\":{\"repo\":{\"full_name\":\"" + head + "\"}},\"base\":{\"repo\":{\"full_name\":\"" + base + "\"}}}";
        }

        private void serve(String path, int status, String location, String body) {
            server.createContext(path, exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                if (location != null) {
                    exchange.getResponseHeaders().add("Location", location);
                }
                var bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
        }

        private URI api() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private Result<PullOrigin> origin(HttpClient client, String origin, Duration timeout) {
            return GitHubPulls.gitHubPulls(client, api(), origin, timeout).origin(new PullNumber(7)).await();
        }

        /// Names differing only in case are treated as a fork: fails safe (GitHub returns canonical names for both).
        @Test
        void caseDifferentFullName_isFork() {
            serve("/", 200, null, pull("PragmaticaLabs/temper", "pragmaticalabs/temper"));

            assertThat(origin(HttpClient.newHttpClient(), "git@github.com:pragmaticalabs/temper.git", Duration.ofSeconds(5)).unwrap())
                .isEqualTo(PullOrigin.FORK);
        }

        /// A renamed repository answers 301; the production client (the factory `Main` uses, pointed at this server)
        /// follows it.
        @Test
        void renamedRepo_redirectFollowed_byTheProductionClient() {
            serve("/repos/old/temper/pulls/7", 301, api() + "/repos/new/temper/pulls/7", "");
            serve("/repos/new/temper/pulls/7", 200, null, pull("new/temper", "new/temper"));

            assertThat(GitHubPulls.gitHubPulls(api(), "git@github.com:old/temper.git").origin(new PullNumber(7)).await().unwrap())
                .isEqualTo(PullOrigin.SAME_REPO);
            assertThat(paths).containsExactly("/repos/old/temper/pulls/7", "/repos/new/temper/pulls/7");
        }

        @Test
        void redirectNotFollowed_failsClosed() {
            serve("/", 301, api() + "/elsewhere", "");

            assertThat(origin(HttpClient.newHttpClient(), "git@github.com:old/temper.git", Duration.ofSeconds(5)).isFailure()).isTrue();
        }

        /// Was a finding: the request timeout bounded only the response headers, and a body trickled past it was
        /// waited for. The whole exchange now has the deadline.
        @Test
        void slowBodyPastTheTimeout_failsWithinTheDeadline() {
            server.createContext("/", exchange -> {
                exchange.sendResponseHeaders(200, 0);
                try (var out = exchange.getResponseBody()) {
                    for (int i = 0; i < 6; i++) {
                        out.write(' ');
                        out.flush();
                        Thread.sleep(500);
                    }
                    out.write(pull("o/r", "o/r").getBytes(StandardCharsets.UTF_8));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            var started = System.nanoTime();

            var result = origin(HttpClient.newHttpClient(), "git@github.com:o/r.git", Duration.ofMillis(500));

            assertThat((System.nanoTime() - started) / 1_000_000).as("bounded by the 500 ms deadline").isLessThan(2000);
            assertThat(result.isFailure()).as(String.valueOf(result)).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(PullOriginError.CallFailed.class));
        }

        /// A declared length over the cap is about this PR's answer: not read, and the walk goes on (a per-PR failure).
        @Test
        void oversizedBody_failsOversized() {
            serve("/", 200, null, pull("o/r", "o/r") + " ".repeat((int) GitHubPulls.MAX_BODY));

            var result = origin(HttpClient.newHttpClient(), "git@github.com:o/r.git", Duration.ofSeconds(5));

            assertThat(result.isFailure()).as(String.valueOf(result)).isTrue();
            result.onFailure(cause -> assertThat(cause).isEqualTo(PullOriginError.General.OVERSIZED));
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://GitHub.com/o/r.git", "git@GITHUB.COM:o/r.git", "https://github.com:443/o/r.git",
                                "ssh://git@github.com:22/o/r.git", "ssh://git@github.com/o/r.git", "ssh://git@github.com/o/r",
                                "ssh://git@GitHub.com:22/o/r"})
        void unusualButValidGithubUrls_parse(String url) {
            assertThat(GitHubRepo.gitHubRepo(url).unwrap().fullName()).as(url).isEqualTo("o/r");
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://www.github.com/o/r.git", "git@github.com:/o/r.git", "https://github.com:22/o/r.git",
                                "ssh://git@github.com:443/o/r.git", "https://github.com:8443/o/r.git", "http://github.com/o/r.git",
                                "git@www.github.com:o/r.git"})
        void otherGithubLookalikes_failNotGithub(String url) {
            assertThat(GitHubRepo.gitHubRepo(url).isFailure()).as(url).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"https://github.com/Pragmaticalabs/Temper", "git@github.com:O/R.git/", " git@github.com:o/r.git "})
        void uppercaseAndTrailing_parse(String url) {
            assertThat(GitHubRepo.gitHubRepo(url).isSuccess()).as(url).isTrue();
        }
    }
}
