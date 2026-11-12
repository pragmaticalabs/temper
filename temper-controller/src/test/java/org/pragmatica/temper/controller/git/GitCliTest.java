package org.pragmatica.temper.controller.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.temper.testsupport.HermeticProcess;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.submit.GitError;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.PullOrigin;
import org.pragmatica.temper.controller.submit.PullOriginError;
import org.pragmatica.temper.controller.submit.PullOrigins;
import org.pragmatica.temper.controller.submit.SourceResolver;
import org.pragmatica.temper.domain.model.PushedSource;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.SourceRequest;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


class GitCliTest {
    /// Against a real origin repository in a temp dir (needs only the `git` binary).
    @Nested
    class RealGit {
        @TempDir
        Path dir;

        private Path origin;
        private GitCli git;
        private String head;

        private String run(Path in, String... argv) throws IOException, InterruptedException {
            var process = HermeticProcess.builder(argv).directory(in.toFile()).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(),
                                 StandardCharsets.UTF_8).trim();

            assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();

            return out;
        }

        @BeforeEach
        void setUp() throws Exception {
            origin = Files.createDirectories(dir.resolve("origin"));
            run(origin, "git", "init", "-q", "-b", "main");
            run(origin, "git", "config", "user.email", "t@t");
            run(origin, "git", "config", "user.name", "t");
            Files.createDirectories(origin.resolve("aether/x/src/test/java"));
            Files.writeString(origin.resolve("aether/x/src/test/java/StreamPublishReshuffleTest.java"),
                              "class StreamPublishReshuffleTest {}\n");
            Files.writeString(origin.resolve("aether/x/src/test/java/StreamPublishReshuffleTestHelper.java"),
                              "class StreamPublishReshuffleTestHelper {}\n");
            run(origin, "git", "add", ".");
            run(origin, "git", "commit", "-q", "-m", "one");
            run(origin, "git", "tag", "-a", "v1", "-m", "annotated");
            head = run(origin, "git", "rev-parse", "HEAD");
            git = GitCli.gitCli(ProcessRunner.processRunner(), origin.toString(), dir.resolve("mirror.git"));
        }

        @Test
        void lsRemote_resolvesBranch() {
            assertThat(git.lsRemote(Ref.ref("main").unwrap()).await().unwrap().value()).isEqualTo(head);
        }

        @Test
        void lsRemote_resolvesAnnotatedTagToItsCommit() {
            assertThat(git.lsRemote(Ref.ref("v1").unwrap()).await().unwrap().value()).isEqualTo(head);
        }

        @Test
        void lsRemote_refusesSource_unknownRef() {
            var result = git.lsRemote(Ref.ref("nope").unwrap()).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(((Refusal) cause).code()).isEqualTo("source"));
        }

        /// Lane checkpoint: `pushedSha` must be the branch's tip or an ancestor. Mutation: make `branchContains` ask
        /// `containedInBranch` (any branch) and the "other branch" case turns red.
        @Test
        void branchContains_tipAndAncestorYes_otherBranchUnknownShaUnknownBranchNo() throws Exception {
            run(origin, "git", "checkout", "-q", "-b", "lane");
            Files.writeString(origin.resolve("lane.txt"), "lane\n");
            run(origin, "git", "add", ".");
            run(origin, "git", "commit", "-q", "-m", "lane one");
            var laneOne = run(origin, "git", "rev-parse", "HEAD");

            Files.writeString(origin.resolve("lane.txt"), "lane two\n");
            run(origin, "git", "commit", "-q", "-a", "-m", "lane two");
            var laneTip = run(origin, "git", "rev-parse", "HEAD");

            run(origin, "git", "checkout", "-q", "main");
            Files.writeString(origin.resolve("main.txt"), "main\n");
            run(origin, "git", "add", ".");
            run(origin, "git", "commit", "-q", "-m", "main two");
            var mainTip = run(origin, "git", "rev-parse", "HEAD");
            var lane = Ref.ref("lane").unwrap();

            assertThat(git.branchContains(lane,
                                          Sha.sha(laneTip).unwrap()).await().unwrap()).as("the tip").isTrue();
            assertThat(git.branchContains(lane,
                                          Sha.sha(laneOne).unwrap()).await().unwrap()).as("an ancestor").isTrue();
            assertThat(git.branchContains(lane,
                                          Sha.sha(head).unwrap()).await().unwrap()).as("the fork point is an ancestor")
                      .isTrue();
            assertThat(git.branchContains(lane,
                                          Sha.sha(mainTip).unwrap()).await().unwrap()).as("on another branch only")
                      .isFalse();
            assertThat(git.branchContains(lane,
                                          Sha.sha("1".repeat(40)).unwrap()).await().unwrap()).as("unknown object")
                      .isFalse();
            assertThat(git.branchContains(Ref.ref("no-such-branch").unwrap(),
                                          Sha.sha(head).unwrap()).await().unwrap()).as("unknown branch")
                      .isFalse();
        }

        private String commitOnLane(String content) throws Exception {
            Files.writeString(origin.resolve("lane.txt"), content);
            run(origin, "git", "add", ".");
            run(origin, "git", "commit", "-q", "-m", content.trim());

            return run(origin, "git", "rev-parse", "HEAD");
        }

        /// Fail closed: with the mirror in place and origin unreachable, the fetch `branchTip` makes fails, and that is a
        /// [GitError], never true and never false (a pushedSha that could not be checked is not accepted, nor refused as
        /// "not on the branch"). Mutation: `.recover(_ -> true)` or `.recover(_ -> false)` on `branchContains` → red.
        @Test
        void branchContains_originUnreachable_failsGitError() throws Exception {
            run(origin, "git", "checkout", "-q", "-b", "lane");
            var laneTip = commitOnLane("lane one\n");
            var lane = Ref.ref("lane").unwrap();

            assertThat(git.branchContains(lane,
                                          Sha.sha(laneTip).unwrap()).await().unwrap()).as("mirror built").isTrue();
            Files.move(origin, dir.resolve("origin-gone"));
            var result = git.branchContains(lane,
                                            Sha.sha(laneTip).unwrap()).await();

            assertThat(result.isFailure()).as("an unreachable origin is neither yes nor no").isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(GitError.class));
        }

        /// A force-push that drops the old tip: the object stays in the mirror, but it is no longer on the branch, so
        /// the answer is false. Mutation: answer from object presence (`containedInBranch`-style cat-file only) → red.
        @Test
        void branchContains_forcePushedAwayTip_isFalseThoughTheObjectIsInTheMirror() throws Exception {
            run(origin, "git", "checkout", "-q", "-b", "lane");
            var keep = commitOnLane("lane one\n");
            var dropped = commitOnLane("lane two\n");
            var lane = Ref.ref("lane").unwrap();

            assertThat(git.branchContains(lane,
                                          Sha.sha(dropped).unwrap()).await().unwrap()).as("before").isTrue();
            run(origin, "git", "checkout", "-q", "main");
            run(origin, "git", "branch", "-f", "lane", keep);
            assertThat(git.branchContains(lane,
                                          Sha.sha(dropped).unwrap()).await().unwrap()).as("dropped tip").isFalse();
            assertThat(git.branchContains(lane,
                                          Sha.sha(keep).unwrap()).await().unwrap()).as("kept tip").isTrue();
            run(dir,
                "git",
                "--git-dir",
                dir.resolve("mirror.git").toString(),
                "cat-file",
                "-e",
                dropped + "^{commit}");
        }

        @Test
        void containedInBranch_afterFetch_trueForHeadFalseForUnknown() {
            git.fetch().await().unwrap();
            assertThat(git.containedInBranch(Sha.sha(head).unwrap()).await().unwrap()).isTrue();
            assertThat(git.containedInBranch(Sha.sha("1".repeat(40)).unwrap()).await().unwrap()).isFalse();
        }

        @Test
        void fetch_secondCallFetchesIntoExistingMirror() {
            git.fetch().await().unwrap();
            assertThat(git.fetch().await().isSuccess()).isTrue();
        }

        @Test
        void facts_readsDeclaringFile_wholeWordOnly_fetchingWhenNeeded() {
            var facts = git.facts(Sha.sha(head).unwrap(),
                                  Set.of("StreamPublishReshuffleTest")).await().unwrap();
            var sources = facts.sources("StreamPublishReshuffleTest");

            assertThat(sources).hasSize(1);
            assertThat(sources.getFirst().path()).isEqualTo("aether/x/src/test/java/StreamPublishReshuffleTest.java");
            assertThat(sources.getFirst().content()).contains("class StreamPublishReshuffleTest {}");
        }

        /// The rerun bound's weights read: a file in the tree is its bytes; a path the tree lacks is none, not a failure.
        @Test
        void file_readsAFileInTheTree_noneForAnAbsentPath() {
            var sha = Sha.sha(head).unwrap();

            assertThat(git.file(sha, "aether/x/src/test/java/StreamPublishReshuffleTest.java")
                          .await()
                          .unwrap()
                          .map(bytes -> new String(bytes, StandardCharsets.UTF_8))).isEqualTo(Option.some("class StreamPublishReshuffleTest {}\n"));
            assertThat(git.file(sha, "tools/ember-shard-weights.txt").await().unwrap().isEmpty()).isTrue();
        }
    }

    /// Pushed reachability over `refs/pull/*/head` (m1-design M1-3, R-M2-7), against a bare origin whose PR heads are
    /// on no branch, with a scripted [PullOrigins] that counts its calls. `refs/pull/*/merge` arrives with the first
    /// `--mirror` clone but never counts.
    @Nested
    class PullHeads {
        @TempDir
        Path dir;

        private Path bare;
        private Path work;
        private GitCli git;
        private String pullOnly;
        private String mergeOnly;
        private String head;
        private final Map<Long, Promise<PullOrigin>> origins = new ConcurrentHashMap<>();
        private final List<PullNumber> asked = new CopyOnWriteArrayList<>();

        private final PullOrigins pulls = pull -> {
            asked.add(pull);

            return origins.getOrDefault(pull.value(),
                                        Causes.cause("unscripted " + pull).promise());
        };

        private String run(Path in, String... argv) throws IOException, InterruptedException {
            var process = HermeticProcess.builder(argv).directory(in.toFile()).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(),
                                 StandardCharsets.UTF_8).trim();

            assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();

            return out;
        }

        /// A commit on no branch of `work`, pushed to the bare origin as `ref` only.
        private String pushedOnlyAs(String ref, String file) throws IOException, InterruptedException {
            run(work, "git", "checkout", "-q", "--detach", "main");
            Files.writeString(work.resolve(file), file + "\n");
            run(work, "git", "add", file);
            run(work, "git", "commit", "-q", "-m", file);
            run(work, "git", "push", "-q", "origin", "HEAD:" + ref);

            return run(work, "git", "rev-parse", "HEAD");
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
            head = run(work, "git", "rev-parse", "HEAD");
            pullOnly = pushedOnlyAs("refs/pull/7/head", "pr7");
            mergeOnly = pushedOnlyAs("refs/pull/7/merge", "merge7");
            git = GitCli.gitCli(ProcessRunner.processRunner(), bare.toString(), dir.resolve("mirror.git"));
            assertThat(run(bare, "git", "branch", "--contains", pullOnly)).as("the fixture's PR head is on no branch")
                      .isEmpty();
        }

        private List<PullNumber> pullHeads(String sha) {
            return git.pullHeadsContaining(Sha.sha(sha).unwrap())
                      .await()
                      .unwrap();
        }

        private Result<PushedSource> resolved(SourceRequest request) {
            return SourceResolver.sourceResolver(git, git, pulls)
                                 .resolve(request)
                                 .await();
        }

        private Result<PushedSource> bySha(String sha) {
            return resolved(SourceRequest.bySha(sha).unwrap());
        }

        private static void assertRefused(Result<PushedSource> result, String code) {
            assertThat(result.isFailure()).as("refused " + code).isTrue();
            result.onFailure(cause -> assertThat(((Refusal) cause).code()).as(cause.message())
                                                .isEqualTo(code));
        }

        @Test
        void pullHeadsContaining_thePullRequest_shaOnlyOnAPullHead() {
            git.fetch().await().unwrap();
            assertThat(git.containedInBranch(Sha.sha(pullOnly).unwrap()).await().unwrap()).isFalse();
            assertThat(pullHeads(pullOnly)).containsExactly(new PullNumber(7));
        }

        @Test
        void pullHeadsContaining_none_shaOnlyOnAPullMergeRef() throws Exception {
            git.fetch().await().unwrap();
            assertThat(run(dir,
                           "git",
                           "--git-dir",
                           dir.resolve("mirror.git").toString(),
                           "cat-file",
                           "-t",
                           mergeOnly)).as("the --mirror clone brought the merge ref's commit")
                      .isEqualTo("commit");
            assertThat(git.containedInBranch(Sha.sha(mergeOnly).unwrap()).await().unwrap()).isFalse();
            assertThat(pullHeads(mergeOnly)).isEmpty();
        }

        @Test
        void containedInBranch_andPullHeads_none_shaInTheMirrorOnNoRef() throws Exception {
            var orphan = pushedOnlyAs("refs/heads/tmp", "orphan");

            git.fetch().await().unwrap();
            run(work, "git", "push", "-q", "origin", "--delete", "tmp");
            git.fetch().await().unwrap();
            assertThat(run(dir,
                           "git",
                           "--git-dir",
                           dir.resolve("mirror.git").toString(),
                           "cat-file",
                           "-t",
                           orphan)).as("the object outlives its pruned branch")
                      .isEqualTo("commit");
            assertThat(git.containedInBranch(Sha.sha(orphan).unwrap()).await().unwrap()).isFalse();
            assertThat(pullHeads(orphan)).isEmpty();
        }

        @Test
        void pullHeadsContaining_none_unknownObject() {
            git.fetch().await().unwrap();
            assertThat(pullHeads("1".repeat(40))).isEmpty();
        }

        @Test
        void fetch_bringsAPullHeadPushedAfterTheClone() throws Exception {
            git.fetch().await().unwrap();
            var later = pushedOnlyAs("refs/pull/8/head", "pr8");

            assertThat(pullHeads(later)).as("not fetched yet").isEmpty();
            git.fetch().await().unwrap();
            assertThat(pullHeads(later)).containsExactly(new PullNumber(8));
        }

        /// The mirror follows the origin's refs. (GitHub keeps a closed PR's head ref; this is a ref the origin
        /// itself removed.)
        @Test
        void fetch_prunesAPullHeadTheOriginRemoved() throws Exception {
            git.fetch().await().unwrap();
            run(work, "git", "push", "-q", "origin", "--delete", "refs/pull/7/head");
            git.fetch().await().unwrap();
            assertThat(pullHeads(pullOnly)).isEmpty();
        }

        /// The path `temper gate` will read: a same-repo PR-only SHA resolves to a Pushed source at exactly that SHA.
        @Test
        void resolve_bySha_pushedSource_shaOnlyOnASameRepoPullHead() {
            origins.put(7L, Promise.success(PullOrigin.SAME_REPO));
            var source = bySha(pullOnly).unwrap();

            assertThat(source.sha().value()).isEqualTo(pullOnly);
            assertThat(source.ref().isEmpty()).isTrue();
            assertThat(asked).containsExactly(new PullNumber(7));
        }

        @Test
        void resolve_bySha_refusesForkPr_shaOnlyOnAForkPullHead() {
            origins.put(7L, Promise.success(PullOrigin.FORK));
            assertRefused(bySha(pullOnly), "fork-pr");
        }

        @Test
        void resolve_bySha_sameRepoWins_shaOnAForkAndASameRepoPullHead() throws Exception {
            run(work, "git", "push", "-q", "origin", pullOnly + ":refs/pull/9/head");
            origins.put(7L, Promise.success(PullOrigin.FORK));
            origins.put(9L, Promise.success(PullOrigin.SAME_REPO));
            assertThat(bySha(pullOnly).unwrap().sha().value()).isEqualTo(pullOnly);
        }

        @Test
        void resolve_bySha_refusesPrOriginUnverified_originUnknown() {
            origins.put(7L,
                        PullOriginError.Status.FACTORY.apply(429).promise());
            var result = bySha(pullOnly);

            assertRefused(result, "pr-origin-unverified");
            result.onFailure(cause -> assertThat(((Refusal) cause).detail()).contains("#7", "HTTP 429"));
        }

        /// The same-repo PR is the older one, so it is reached before the newer PR whose origin would fail.
        @Test
        void resolve_bySha_sameRepoReachedFirst_aNewerUnverifiedPullHeadNotAsked() throws Exception {
            run(work, "git", "push", "-q", "origin", pullOnly + ":refs/pull/9/head");
            origins.put(7L, Promise.success(PullOrigin.SAME_REPO));
            origins.put(9L,
                        PullOriginError.Status.FACTORY.apply(403).promise());
            assertThat(bySha(pullOnly).unwrap().sha().value()).isEqualTo(pullOnly);
            assertThat(asked).containsExactly(new PullNumber(7));
        }

        /// The verifier's correction: outsiders' fork PRs on a SHA are usually newer than its own PR. A same-repo #5
        /// containing S, plus forks #6..#80 whose heads are exactly S, costs one API call.
        @Test
        void resolve_bySha_sameRepoOldestPr_oneApiCall_despiteSeventyFiveNewerForks() throws Exception {
            run(work, "git", "checkout", "-q", "--detach", pullOnly);
            Files.writeString(work.resolve("pr5"), "pr5\n");
            run(work, "git", "add", "pr5");
            run(work, "git", "commit", "-q", "-m", "pr5");
            var five = run(work, "git", "rev-parse", "HEAD");
            var refspecs = new java.util.ArrayList<>(List.of("git", "push", "-q", "origin", five + ":refs/pull/5/head"));

            for (long n = 6; n <= 80; n++) {
                refspecs.add(pullOnly + ":refs/pull/" + n + "/head");
                origins.put(n, Promise.success(PullOrigin.FORK));
            }

            run(work, refspecs.toArray(String[]::new));
            origins.put(5L, Promise.success(PullOrigin.SAME_REPO));
            assertThat(bySha(pullOnly).unwrap().sha().value()).isEqualTo(pullOnly);
            assertThat(asked).containsExactly(new PullNumber(5));
        }

        @Test
        void resolve_bySha_refusesPrOriginUnverified_forkAndUnverifiedPullHeads() throws Exception {
            run(work, "git", "push", "-q", "origin", pullOnly + ":refs/pull/9/head");
            origins.put(7L, Promise.success(PullOrigin.FORK));
            origins.put(9L,
                        PullOriginError.Status.FACTORY.apply(403).promise());
            assertRefused(bySha(pullOnly), "pr-origin-unverified");
        }

        /// Oldest first, by PR number as an integer (refname order would put #10 before #2), whether the head is the
        /// SHA or a descendant.
        @Test
        void pullHeadsContaining_oldestFirst_byNumber() throws Exception {
            run(work, "git", "checkout", "-q", "--detach", pullOnly);
            Files.writeString(work.resolve("child"), "child\n");
            run(work, "git", "add", "child");
            run(work, "git", "commit", "-q", "-m", "child of pr7");
            var descendant = run(work, "git", "rev-parse", "HEAD");

            run(work, "git", "push", "-q", "origin", descendant + ":refs/pull/40/head");
            run(work, "git", "push", "-q", "origin", pullOnly + ":refs/pull/2/head");
            run(work, "git", "push", "-q", "origin", pullOnly + ":refs/pull/10/head");
            git.fetch().await().unwrap();
            assertThat(pullHeads(pullOnly)).containsExactly(new PullNumber(2),
                                                            new PullNumber(7),
                                                            new PullNumber(10),
                                                            new PullNumber(40));
        }

        /// An origin already known costs no call, and a known same-repo PR wins even after an earlier candidate fails.
        @Test
        void resolve_bySha_knownSameRepo_noApiCall() throws Exception {
            run(work, "git", "push", "-q", "origin", pullOnly + ":refs/pull/9/head");
            origins.put(9L,
                        PullOriginError.Status.FACTORY.apply(403).promise());
            PullOrigins knowing = new PullOrigins() {
                @Override
                public Promise<PullOrigin> origin(PullNumber pull) {
                    return pulls.origin(pull);
                }

                @Override
                public Option<PullOrigin> known(PullNumber pull) {
                    return pull.value() == 7
                           ? Option.some(PullOrigin.SAME_REPO)
                           : Option.none();
                }
            };
            var source = SourceResolver.sourceResolver(git, git, knowing)
                                       .resolve(SourceRequest.bySha(pullOnly).unwrap())
                                       .await();

            assertThat(source.unwrap().sha().value()).isEqualTo(pullOnly);
            assertThat(asked).isEmpty();
        }

        /// Through the production judge against a local stand-in for api.github.com: the second submit of the same
        /// PR-only SHA makes no API call.
        @Test
        void resolve_bySha_secondSubmit_noApiCall_withTheRealJudge() throws Exception {
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var answer = "{\"head\":{\"repo\":{\"full_name\":\"o/r\"}},\"base\":{\"repo\":{\"full_name\":\"o/r\"}}}".getBytes();

            server.createContext("/",
                                 exchange -> {
                                     requests.incrementAndGet();
                                     exchange.sendResponseHeaders(200, answer.length);
                                     try (var out = exchange.getResponseBody()) {
                                     out.write(answer);
                                 }
                                 });
            server.start();
            try {
                var judge = GitHubPulls.gitHubPulls(java.net.URI.create("http://127.0.0.1:" + server.getAddress()
                                                                                                    .getPort()),
                                                    "git@github.com:o/r.git");
                var resolver = SourceResolver.sourceResolver(git, git, judge);

                assertThat(resolver.resolve(SourceRequest.bySha(pullOnly).unwrap()).await().isSuccess()).isTrue();
                assertThat(resolver.resolve(SourceRequest.bySha(pullOnly).unwrap()).await().isSuccess()).isTrue();
                assertThat(requests.get()).as("one call ever for PR 7").isEqualTo(1);
            } finally {
                server.stop(0);
            }
        }

        @Test
        void resolve_bySha_branchHead_neverAsksTheApi() {
            assertThat(bySha(head).unwrap().sha().value()).isEqualTo(head);
            assertThat(asked).isEmpty();
        }

        @Test
        void resolve_bySha_refusesSource_shaOnNoRef_neverAsksTheApi() {
            assertRefused(bySha("1".repeat(40)), "source");
            assertRefused(bySha(mergeOnly), "source");
            assertThat(asked).isEmpty();
        }

        /// A PR head named in full resolves through `ls-remote` and must still be same-repo.
        @Test
        void resolve_byPullRef_refusesForkPr_forkPullHead() {
            origins.put(7L, Promise.success(PullOrigin.FORK));
            assertRefused(resolved(SourceRequest.byRef("refs/pull/7/head").unwrap()),
                          "fork-pr");
        }

        @Test
        void resolve_byPullRef_pushedSource_sameRepoPullHead() {
            origins.put(7L, Promise.success(PullOrigin.SAME_REPO));
            var source = resolved(SourceRequest.byRef("refs/pull/7/head").unwrap()).unwrap();

            assertThat(source.sha().value()).isEqualTo(pullOnly);
            assertThat(source.ref().map(Ref::value).or("")).isEqualTo("refs/pull/7/head");
        }

        @Test
        void resolve_byMergeRef_refusesSource() {
            assertRefused(resolved(SourceRequest.byRef("refs/pull/7/merge").unwrap()),
                          "source");
            assertThat(asked).isEmpty();
        }

        @Test
        void resolve_byBranch_neverAsksTheApi() {
            assertThat(resolved(SourceRequest.byRef("main").unwrap()).unwrap().sha().value()).isEqualTo(head);
            assertThat(asked).isEmpty();
        }
    }

    /// The reactor at submit (m1-design §1.6) read with `git show` from the mirror; unknown (none) when a pom is
    /// missing or `.mvn/maven.config` selects another reactor.
    @Nested
    class Reactor {
        @TempDir
        Path dir;

        private Path origin;
        private GitCli git;

        private String run(String... argv) throws IOException, InterruptedException {
            var process = HermeticProcess.builder(argv).directory(origin.toFile()).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(),
                                 StandardCharsets.UTF_8).trim();

            assertThat(process.waitFor()).as(String.join(" ", argv) + ": " + out).isZero();

            return out;
        }

        private String commit(String message) throws Exception {
            run("git", "add", "-A");
            run("git", "commit", "-q", "-m", message);

            return run("git", "rev-parse", "HEAD");
        }

        private void pom(String module, String... modules) throws IOException {
            var directory = Files.createDirectories(origin.resolve(module));

            Files.writeString(directory.resolve("pom.xml"),
                              "<project><modules>" + String.join("",
                                                                 Arrays.stream(modules)
                                                                       .map(m -> "<module>" + m + "</module>")
                                                                       .toList())
                             + "</modules></project>");
        }

        private Option<ReactorModules> reactorAt(String sha) {
            return git.facts(Sha.sha(sha).unwrap(),
                             Set.of())
                      .await()
                      .unwrap()
                      .reactor();
        }

        @BeforeEach
        void setUp() throws Exception {
            origin = Files.createDirectories(dir.resolve("origin"));
            run("git", "init", "-q", "-b", "main");
            run("git", "config", "user.email", "t@t");
            run("git", "config", "user.name", "t");
            git = GitCli.gitCli(ProcessRunner.processRunner(), origin.toString(), dir.resolve("mirror.git"));
        }

        @Test
        void facts_readTheReactor_throughAggregatorPoms() throws Exception {
            pom("", "core", "aether");
            pom("core");
            pom("aether", "ember");
            pom("aether/ember");
            Files.createDirectories(origin.resolve(".mvn"));
            Files.writeString(origin.resolve(".mvn/maven.config"), "-T 1C\n--no-transfer-progress\n");
            var sha = commit("reactor");

            assertThat(reactorAt(sha).map(found -> found.paths())).isEqualTo(Option.some(Set.of("",
                                                                                                "core",
                                                                                                "aether",
                                                                                                "aether/ember")));
        }

        @Test
        void facts_reactorUnknown_whenAPomIsMissing_orTheConfigSelectsProfiles() throws Exception {
            pom("", "core", "gone");
            pom("core");
            var missing = commit("missing pom");

            pom("", "core");
            Files.createDirectories(origin.resolve(".mvn"));
            Files.writeString(origin.resolve(".mvn/maven.config"), "-Pwith-e2e\n");
            var profiled = commit("profile in config");

            assertThat(reactorAt(missing).isEmpty()).isTrue();
            assertThat(reactorAt(profiled).isEmpty()).isTrue();
        }
    }

    /// The reviewer's case: git failing AFTER the object is known must be a GitError (503), never a refusal.
    @Nested
    class GitFailures {
        @Test
        void containedInBranch_failsGitError_forEachRefFails() {
            ProcessRunner runner = new ProcessRunner() {
                @Override
                public Promise<Completed> run(Command command) {
                    var rc = command.argv().contains("for-each-ref")
                             ? 128
                             : 0;

                    return Promise.success(Completed.completed("git", rc, new byte[0], "fatal".getBytes(), false));
                }

                @Override
                public Result<Spawned> spawn(Command command) {
                    return Causes.cause("unused").result();
                }
            };
            var git = GitCli.gitCli(runner, "origin", Path.of("/m"));
            var result = git.containedInBranch(Sha.sha("a".repeat(40)).unwrap()).await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(GitError.class));
        }

        @Test
        void containedInBranch_failsGitError_brokenMirror() {
            ProcessRunner runner = new ProcessRunner() {
                @Override
                public Promise<Completed> run(Command command) {
                    return Promise.success(Completed.completed("git",
                                                               128,
                                                               new byte[0],
                                                               "not a git repository".getBytes(),
                                                               false));
                }

                @Override
                public Result<Spawned> spawn(Command command) {
                    return Causes.cause("unused").result();
                }
            };
            var result = GitCli.gitCli(runner,
                                       "origin",
                                       Path.of("/m"))
                               .containedInBranch(Sha.sha("a".repeat(40)).unwrap())
                               .await();

            assertThat(result.isFailure()).as("a broken mirror is a 503, not a source refusal").isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(GitError.class));
        }

        /// `merge-base --is-ancestor` exits 0/1 for yes/no; any other code (here 128) is a failed git call, never "no"
        /// and never "yes". Mutation: `ancestry` answers `success(true)` or `success(false)` for the third branch → red.
        @Test
        void branchContains_failsGitError_mergeBaseOtherThanZeroOrOne() {
            var tip = "b".repeat(40);
            ProcessRunner runner = new ProcessRunner() {
                @Override
                public Promise<Completed> run(Command command) {
                    var argv = command.argv();
                    var rc = argv.contains("merge-base")
                             ? 128
                             : 0;
                    var out = argv.contains("rev-parse")
                              ? tip + "\n"
                              : "";

                    return Promise.success(Completed.completed("git", rc, out.getBytes(), "fatal".getBytes(), false));
                }

                @Override
                public Result<Spawned> spawn(Command command) {
                    return Causes.cause("unused").result();
                }
            };
            var result = GitCli.gitCli(runner,
                                       "origin",
                                       Path.of("/m"))
                               .branchContains(Ref.ref("lane").unwrap(),
                                               Sha.sha("a".repeat(40)).unwrap())
                               .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).isInstanceOf(GitError.class));
        }

        @Test
        void lsRemote_argvSeparatesOptionsFromOrigin() {
            var seen = new java.util.concurrent.CopyOnWriteArrayList<List<String>>();
            ProcessRunner runner = new ProcessRunner() {
                @Override
                public Promise<Completed> run(Command command) {
                    seen.add(command.argv());

                    return Promise.success(Completed.completed("git", 2, new byte[0], new byte[0], false));
                }

                @Override
                public Result<Spawned> spawn(Command command) {
                    return Causes.cause("unused").result();
                }
            };

            GitCli.gitCli(runner, "--upload-pack=evil", Path.of("/m")).lsRemote(Ref.ref("main").unwrap()).await();
            assertThat(seen.getFirst()).containsSequence("--exit-code", "--", "--upload-pack=evil");
        }

        /// A file the tree lists but git cannot show, or a listing that fails, is the failed git call (503), never "no
        /// file": the rerun bound must not fall back to its default over a broken mirror.
        @Test
        void file_failsGitError_listingOrShowFails() {
            var sha = "a".repeat(40);

            for (var failing : List.of("ls-tree", "show")) {
                ProcessRunner runner = new ProcessRunner() {
                    @Override
                    public Promise<Completed> run(Command command) {
                        var argv = command.argv();
                        var rc = argv.contains(failing)
                                 ? 128
                                 : 0;
                        var out = argv.contains("ls-tree")
                                  ? "tools/ember-shard-weights.txt\n"
                                  : "";

                        return Promise.success(Completed.completed("git", rc, out.getBytes(), "fatal".getBytes(), false));
                    }

                    @Override
                    public Result<Spawned> spawn(Command command) {
                        return Causes.cause("unused").result();
                    }
                };
                var result = GitCli.gitCli(runner,
                                           "origin",
                                           Path.of("/m"))
                                   .file(Sha.sha(sha).unwrap(),
                                         "tools/ember-shard-weights.txt")
                                   .await();

                assertThat(result.isFailure()).as(failing).isTrue();
                result.onFailure(cause -> assertThat(((GitError.Failed) cause).operation()).as(failing)
                                                    .isEqualTo(failing));
            }
        }

        /// The submit route answers 503 only for a [GitError], so a class that cannot be shown must surface as the
        /// failed git call itself (`Collect.first`), not as a `CompositeCause` (a 500) or a wrapper around one.
        @Test
        void facts_failsGitError_aDeclaringFileCannotBeShown() {
            var sha = "a".repeat(40);
            ProcessRunner runner = new ProcessRunner() {
                @Override
                public Promise<Completed> run(Command command) {
                    var argv = command.argv();
                    var rc = argv.contains("show")
                             ? 128
                             : 0;
                    var out = argv.contains("grep")
                              ? sha + ":X.java\n"
                              : "";

                    return Promise.success(Completed.completed("git", rc, out.getBytes(), "fatal".getBytes(), false));
                }

                @Override
                public Result<Spawned> spawn(Command command) {
                    return Causes.cause("unused").result();
                }
            };
            var result = GitCli.gitCli(runner,
                                       "origin",
                                       Path.of("/m"))
                               .facts(Sha.sha(sha).unwrap(),
                                      Set.of("X"))
                               .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause).as(cause.getClass().getName())
                                                .isInstanceOf(GitError.Failed.class));
            result.onFailure(cause -> assertThat(((GitError.Failed) cause).operation()).as("the git call that failed, not a wrapper")
                                                .isEqualTo("show"));
        }
    }
}
