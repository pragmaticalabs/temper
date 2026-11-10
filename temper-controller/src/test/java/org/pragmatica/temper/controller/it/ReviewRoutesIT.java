package org.pragmatica.temper.controller.it;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.config.GitHubReviewer;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.review.CarryProof;
import org.pragmatica.temper.controller.review.HttpReviewGitHub;
import org.pragmatica.temper.controller.review.ReviewRoutes;
import org.pragmatica.temper.ghapp.AppAuthConfig;
import org.pragmatica.temper.ghapp.GitHubAppAuth;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.NewReview;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The review-path controls (R.1-R.8, R.10-R.14, R.20) and the read half of C.5, through the real router
/// over Postgres, a GitHub stub on loopback and fixture git repositories: the mirror is a bare clone of a work repository
/// whose merges (clean, hand-edited, conflicted, off the base's first-parent line, onto a side branch) are made by `git`.
@Tag("pg")
class ReviewRoutesIT {
    private static final String VERDICT = "verdict-token";
    private static final String READ = "read-token";
    private static final String REVIEWER = "temper-reviewer";
    private static final String PAT = "ghp_SECRET_PAT_VALUE_1";
    private static final String DIGEST = "d".repeat(64);
    private static final long PR = 7;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-05T12:00:00Z"));

    private Store store;
    private String database;
    private Fleet fleet;
    private Router router;
    private ReviewGitHubStub gh;
    private Path patFile;
    private Fixture fx;
    private ListAppender<ILoggingEvent> journal;

    @TempDir
    Path state;

    /// SHAs of the fixture repository: `F` the carried-from head; `M` its clean merge with the base tip `T1`; `H` that merge
    /// plus one hand-edited line; `C` a hand-resolved conflict with `T2`; `O` a merge with side commit `S` that is reachable
    /// from `main` but not on its first-parent line; `B` a clean merge with the tip of the side branch `side2`.
    record Fixture(Path mirror,
                   String f,
                   String m,
                   String t1,
                   String h,
                   String c,
                   String t2,
                   String o,
                   String s,
                   String b,
                   String side2Tip) {}

    /// Concurrent callers run here, never on the common pool: a 2-vCPU CI runner sizes that pool to ONE worker, which
    /// would serialize the "in flight" calls these tests need to overlap (CI run 42144810721).
    private final ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor();

    @BeforeEach
    void setUp() throws Exception {
        var named = Pg.freshNamed();

        store = named.store();
        database = named.database();
        fleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state),
                            List.of(),
                            store.fleet(),
                            ProcessRunner.processRunner(),
                            none(),
                            now::get);
        fleet.settle().await(Pg.WAIT).unwrap();
        gh = new ReviewGitHubStub(REVIEWER);
        patFile = state.resolve("reviewer.pat");
        Files.writeString(patFile, PAT + "\n");
        fx = fixture(state.resolve("fx"));
        journal = new ListAppender<>();
        journal.start();
        ((Logger) org.slf4j.LoggerFactory.getLogger(ReviewRoutes.class)).addAppender(journal);
        router = router(Duration.ofSeconds(30),
                        TimeSpan.timeSpan(30).seconds(),
                        ProcessRunner.processRunner());
        store.reviews().addAuthors("o/r", PR, List.of("c-x"), "tester").await(Pg.WAIT).unwrap();
    }

    @AfterEach
    void tearDown() {
        callers.close();
        ((Logger) org.slf4j.LoggerFactory.getLogger(ReviewRoutes.class)).detachAppender(journal);
        gh.close();
        store.close();
    }

    private Router router(Duration postTimeout, TimeSpan gitCommand, ProcessRunner runner) throws Exception {
        return router(postTimeout, gitCommand, runner, () -> true);
    }

    private Router router(Duration postTimeout,
                          TimeSpan gitCommand,
                          ProcessRunner runner,
                          java.util.function.BooleanSupplier ready) throws Exception {
        var key = state.resolve("app.pem");

        if (!Files.exists(key)) {
            var generator = KeyPairGenerator.getInstance("RSA");

            generator.initialize(2048);
            var pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64,
                                                                              "\n".getBytes())
                                                              .encodeToString(generator.generateKeyPair()
                                                                                       .getPrivate()
                                                                                       .getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";

            Files.writeString(key, pem);
            Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-------"));
        }

        var config = AppAuthConfig.appAuthConfig("controller-app",
                                                 "Iv23liTestClient",
                                                 1L,
                                                 key,
                                                 List.of("r"),
                                                 Map.of("pull_requests", "read"),
                                                 gh.api())
                                  .unwrap();
        var app = GitHubAppAuth.githubAppAuth(config, GitHubAppAuth.httpClient(), java.time.Clock.systemUTC()).unwrap();
        var github = HttpReviewGitHub.httpReviewGitHub(gh.api(),
                                                       Duration.ofSeconds(10),
                                                       postTimeout,
                                                       some(app),
                                                       new GitHubReviewer(REVIEWER, patFile));
        var proof = new CarryProof(runner,
                                   fx.mirror(),
                                   () -> Promise.success(Unit.unit()),
                                   gitCommand,
                                   TimeSpan.timeSpan(90).seconds(),
                                   TimeSpan.timeSpan(120).seconds());
        var responses = Responses.responses(TemperJson.mapper());
        var routes = ReviewRoutes.reviewRoutes(responses,
                                               TemperJson.mapper(),
                                               store.reviews(),
                                               store.t2(),
                                               fleet.writer(),
                                               some(github),
                                               repo -> some(proof),
                                               "test-1.0",
                                               now::get,
                                               ready);

        return Router.router(Credentials.credentials(BearerToken.bearerToken("full-token").unwrap(),
                                                     some(BearerToken.bearerToken(READ).unwrap()),
                                                     none(),
                                                     some(BearerToken.bearerToken(VERDICT).unwrap()),
                                                     none()),
                             routes.routes(),
                             responses);
    }

    // ---- R.1 - R.5 ---------------------------------------------------------------------------------------------
    @Test
    void r1_noVerdict_isRefused_andGitHubIsNeverCalled() {
        gh.head(PR, fx.f());
        var reply = review(PR, fx.f(), "v-a");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("NO_VERDICT");
        assertThat(gh.seen()).isEmpty();
        assertThat(gh.posts()).isZero();
    }

    @Test
    void r2_verdictByAnotherLane_isVerifierMismatch() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f());
        var reply = review(PR, fx.f(), "v-b");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("VERIFIER_MISMATCH");
        assertThat(gh.posts()).isZero();
    }

    @Test
    void r3_asInTheAuthorSet_isSelfReview_withAReviewRefusedEvent_caseInsensitively() {
        verdict(PR, fx.f(), "ACCEPT", "main", "C-X");
        gh.head(PR, fx.f());
        var reply = review(PR, fx.f(), "C-X");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("SELF_REVIEW");
        assertThat(gh.posts()).isZero();
        assertThat(events("review-refused")).singleElement()
                  .satisfies(row -> assertThat(row.payload()).contains("SELF_REVIEW"));
    }

    @Test
    void r4_emptyAuthorSet_isAuthorUnknown() {
        verdict(9, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(9, fx.f());
        var reply = review(9, fx.f(), "v-a");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("AUTHOR_UNKNOWN");
        assertThat(gh.posts()).isZero();
    }

    @Test
    void r5_stubHeadMoved_isHeadMoved_andNothingIsPosted() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.t1());
        var reply = review(PR, fx.f(), "v-a");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("HEAD_MOVED");
        assertThat(gh.posts()).isZero();
    }

    // ---- R.6 ---------------------------------------------------------------------------------------------------
    @Test
    void r6_fixPostsRequestChanges_acceptPostsApprove() {
        verdict(PR, fx.f(), "FIX", "main", "v-a");
        verdict(8, fx.f(), "ACCEPT", "main", "v-a");
        store.reviews().addAuthors("o/r", 8, List.of("c-x"), "tester").await(Pg.WAIT).unwrap();
        gh.head(PR, fx.f()).head(8, fx.f());
        var fix = review(PR, fx.f(), "v-a");
        var accept = review(8, fx.f(), "v-a");

        assertThat(fix.status().code()).isEqualTo(200);
        assertThat(accept.status().code()).isEqualTo(200);
        assertThat(gh.seen("POST", "/pulls/7/reviews")).singleElement()
                  .satisfies(seen -> assertThat(json(seen.body()).path("event").stringValue()).isEqualTo("REQUEST_CHANGES"));
        assertThat(gh.seen("POST", "/pulls/8/reviews")).singleElement()
                  .satisfies(seen -> assertThat(json(seen.body()).path("event").stringValue()).isEqualTo("APPROVE"));
        assertThat(json(text(fix)).path("event").stringValue()).isEqualTo("REQUEST_CHANGES");
    }

    @Test
    void r6_carriedAccept_withAPassingProof_postsApprove_andRecordsTheBaseTip() {
        carried(PR, fx.m(), fx.f(), "main", "v-a");
        gh.head(PR, fx.m());
        var reply = review(PR, fx.m(), "v-a");

        assertThat(reply.status().code()).isEqualTo(200);
        var posted = gh.seen("POST", "/pulls/7/reviews").getFirst();

        assertThat(json(posted.body()).path("event").stringValue()).isEqualTo("APPROVE");
        assertThat(json(posted.body()).path("body").stringValue()).contains("carry_base_tip=" + fx.t1())
                  .contains("by=v-a")
                  .contains("report_sha256=" + DIGEST);
        assertThat(sql("SELECT carry_base_tip || ' ' || carry_base_ref AS v FROM reviews WHERE pr = 7")).isEqualTo(fx.t1()
                                                                                                                  + " main");
    }

    // ---- R.7 ---------------------------------------------------------------------------------------------------
    @Test
    void r7_timedOutPost_leavesSending_andTheRetryAdoptsTheReviewWithoutAnotherPost() throws Exception {
        router = router(Duration.ofSeconds(1),
                        TimeSpan.timeSpan(30).seconds(),
                        ProcessRunner.processRunner());
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f()).delayPosts(PR, 3000);
        var first = review(PR, fx.f(), "v-a");

        assertThat(first.status().code()).isEqualTo(503);
        assertThat(text(first)).contains("UNKNOWN");
        assertThat(sql("SELECT state AS v FROM reviews WHERE pr = 7")).isEqualTo("sending");
        var retry = review(PR, fx.f(), "v-a");

        assertThat(retry.status().code()).isEqualTo(200);
        assertThat(json(text(retry)).path("alreadyPosted").booleanValue()).isTrue();
        assertThat(gh.posts()).isEqualTo(1);
        assertThat(sql("SELECT state || ' ' || github_review_id AS v FROM reviews WHERE pr = 7")).isEqualTo("posted 1001");
    }

    // ---- R.8 ---------------------------------------------------------------------------------------------------
    @Test
    void r8_unauthorizedPost_is503ReviewerCredential_andNoPatByteIsAnywhere() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f()).onPost(_ -> ReviewGitHubStub.Reply.status(401, "Bad credentials " + PAT));
        var reply = review(PR, fx.f(), "v-a");

        assertThat(reply.status().code()).isEqualTo(503);
        assertThat(text(reply)).contains("REVIEWER_CREDENTIAL").doesNotContain(PAT);
        assertThat(journal.list).isNotEmpty();
        assertThat(journal.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.joining("\n"))).doesNotContain(PAT);
        assertThat(sql("SELECT state || ' ' || coalesce(failure, '') AS v FROM reviews WHERE pr = 7")).startsWith("failed ")
                  .doesNotContain(PAT);
        assertThat(sql("SELECT payload::text AS v FROM fleet_events ORDER BY seq DESC LIMIT 1")).doesNotContain(PAT);
    }

    // ---- R.10 --------------------------------------------------------------------------------------------------
    @Test
    void r10_thePatIsReadPerCall_aRotatedFileIsSentNext() throws Exception {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        verdict(8, fx.f(), "ACCEPT", "main", "v-a");
        store.reviews().addAuthors("o/r", 8, List.of("c-x"), "tester").await(Pg.WAIT).unwrap();
        gh.head(PR, fx.f()).head(8, fx.f());
        assertThat(review(PR, fx.f(), "v-a").status().code()).isEqualTo(200);
        Files.writeString(patFile, "ghp_ROTATED_VALUE_2\n");
        assertThat(review(8, fx.f(), "v-a").status().code()).isEqualTo(200);
        assertThat(gh.seen("POST", "/pulls/7/reviews").getFirst().authorization()).isEqualTo("Bearer " + PAT);
        assertThat(gh.seen("POST", "/pulls/8/reviews").getFirst().authorization()).isEqualTo("Bearer ghp_ROTATED_VALUE_2");
    }

    // ---- C.5 (read half) ---------------------------------------------------------------------------------------
    @Test
    void c5_readsCarryTheAppReadToken_theMintAsksForPullRequestsRead_thePostCarriesOnlyThePat() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f());
        assertThat(review(PR, fx.f(), "v-a").status().code()).isEqualTo(200);
        var mint = gh.seen("POST", "/access_tokens");

        assertThat(mint).hasSize(1);
        assertThat(json(mint.getFirst().body()).path("permissions").toString()).isEqualTo("{\"pull_requests\":\"read\"}");
        assertThat(gh.seen("GET", "/pulls/7")).isNotEmpty()
                  .allSatisfy(seen -> assertThat(seen.authorization()).isEqualTo("Bearer " + ReviewGitHubStub.APP_TOKEN));
        assertThat(gh.seen("POST", "/pulls/7/reviews")).singleElement()
                  .satisfies(seen -> assertThat(seen.authorization()).isEqualTo("Bearer " + PAT));
    }

    // ---- R.11 - R.13, R.20 -------------------------------------------------------------------------------------
    @Test
    void r11_mergePlusOneExtraLine_isHandResolved_andNothingIsPosted() {
        carried(PR, fx.h(), fx.f(), "main", "v-a");
        gh.head(PR, fx.h());
        var reply = review(PR, fx.h(), "v-a");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("CARRY_NOT_MECHANICAL hand-resolved");
        assertThat(gh.posts()).isZero();
    }

    @Test
    void r12_conflictedMergeResolvedByHand_isConflicted() {
        carried(PR, fx.c(), fx.f(), "main", "v-a");
        gh.head(PR, fx.c());
        var reply = review(PR, fx.c(), "v-a");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("CARRY_NOT_MECHANICAL conflicted");
        assertThat(gh.posts()).isZero();
    }

    @Test
    void r13_parent2ReachableButOffTheFirstParentLine_isParent2OffBase() {
        carried(PR, fx.o(), fx.f(), "main", "v-a");
        gh.head(PR, fx.o());
        var reply = review(PR, fx.o(), "v-a");

        assertThat(reply.status().code()).isEqualTo(422);
        assertThat(text(reply)).contains("CARRY_NOT_MECHANICAL parent2-off-base");
        assertThat(gh.posts()).isZero();
    }

    @Test
    void r13_aStalledGitCommand_is503CarryProofUnavailable() throws Exception {
        router = router(Duration.ofSeconds(30),
                        TimeSpan.timeSpan(1).seconds(),
                        stalling("rev-list"));
        carried(PR, fx.m(), fx.f(), "main", "v-a");
        gh.head(PR, fx.m());
        var reply = review(PR, fx.m(), "v-a");

        assertThat(reply.status().code()).isEqualTo(503);
        assertThat(text(reply)).contains("CARRY_PROOF_UNAVAILABLE");
        assertThat(gh.posts()).isZero();
    }

    @Test
    void r20_rowBaseASideBranch_prBaseRc5_isBaseMoved_andRowBaseEqualToPrBaseRunsTheProof() {
        carried(PR, fx.b(), fx.f(), "side2", "v-a");
        gh.head(PR, fx.b()).base("rc5");
        var moved = review(PR, fx.b(), "v-a");

        assertThat(moved.status().code()).isEqualTo(422);
        assertThat(text(moved)).contains("BASE_MOVED");
        assertThat(gh.posts()).isZero();
        carried(8, fx.m(), fx.f(), "rc5", "v-a");
        store.reviews().addAuthors("o/r", 8, List.of("c-x"), "tester").await(Pg.WAIT).unwrap();
        gh.head(8, fx.m());
        var proven = review(8, fx.m(), "v-a");

        assertThat(proven.status().code()).isEqualTo(200);
        assertThat(gh.posts()).isEqualTo(1);
    }

    // ---- R.14 --------------------------------------------------------------------------------------------------
    @Test
    void r14_aSecondCallOnTheSamePrIsInFlight_aCallOnAnotherPrIsNotDelayed() throws Exception {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        verdict(8, fx.f(), "ACCEPT", "main", "v-a");
        store.reviews().addAuthors("o/r", 8, List.of("c-x"), "tester").await(Pg.WAIT).unwrap();
        gh.head(PR, fx.f()).head(8, fx.f()).delayPosts(PR, 5000);
        var first = CompletableFuture.supplyAsync(() -> review(PR, fx.f(), "v-a"), callers);

        assertThat(gh.postArrived().await(20, TimeUnit.SECONDS)).isTrue();
        var second = review(PR, fx.f(), "v-a");
        var started = System.nanoTime();
        var other = review(8, fx.f(), "v-a");
        var tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(second.status().code()).isEqualTo(409);
        assertThat(text(second)).contains("IN_FLIGHT");
        assertThat(other.status().code()).isEqualTo(200);
        assertThat(tookMillis).isLessThan(3000);
        assertThat(first.get(30, TimeUnit.SECONDS).status().code()).isEqualTo(200);
        assertThat(gh.seen("POST", "/pulls/7/reviews")).hasSize(1);
    }

    /// B1: four reviews in flight on four PRs fill the lock sessions; a fifth review on a fifth PR is answered 503
    /// `BUSY` at once (not queued), and store work outside the reviews still completes.
    @Test
    void lockSessions_fourReviewsInFlight_aFifthIs503BusyAtOnce_andStoreWorkStillCompletes() throws Exception {
        var inFlight = new ArrayList<CompletableFuture<ApiResponse>>();

        for (long pr = 7; pr <= 11; pr++) {
            verdict(pr, fx.f(), "ACCEPT", "main", "v-a");
            store.reviews().addAuthors("o/r", pr, List.of("c-x"), "tester").await(Pg.WAIT).unwrap();
            gh.head(pr, fx.f()).delayPosts(pr, 6000);
        }

        for (long pr = 7; pr <= 10; pr++) {
            var n = pr;

            inFlight.add(CompletableFuture.supplyAsync(() -> review(n, fx.f(), "v-a"), callers));
        }

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (gh.seen("POST", "/reviews").size() < 4 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(gh.seen("POST", "/reviews")).hasSize(4);
        var started = System.nanoTime();
        var fifth = review(11, fx.f(), "v-a");

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("refused at once").isLessThan(2000);
        assertThat(fifth.status().code()).isEqualTo(503);
        assertThat(text(fifth)).contains("BUSY");
        assertThat(store.t2()
                        .latestVerdict("o/r",
                                       11,
                                       fx.f())
                        .await(TimeSpan.timeSpan(5).seconds())
                        .unwrap()
                        .isPresent()).isTrue();
        for (var call : inFlight) {
            assertThat(call.get(30, TimeUnit.SECONDS).status().code()).isEqualTo(200);
        }

        assertThat(gh.seen("POST", "/pulls/11/reviews")).isEmpty();
        assertThat(review(11, fx.f(), "v-a").status().code()).as("a slot is free again").isEqualTo(200);
    }

    /// B3: under a slow POST the lock is held until the call has written its outcome: the first time the lock is seen
    /// released from another session, the row is `posted` already, and a second call meanwhile posted nothing.
    @Test
    void slowPost_theLockIsReleasedOnlyAfterTheRowIsWritten_andNoSecondPostMeanwhile() throws Exception {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f()).delayPosts(PR, 3000);
        var first = CompletableFuture.supplyAsync(() -> review(PR, fx.f(), "v-a"), callers);

        assertThat(gh.postArrived().await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(review(PR, fx.f(), "v-a").status().code()).isEqualTo(409);
        var atRelease = "";
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

        while (System.nanoTime() < deadline) {
            var state = sql("SELECT (SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND granted AND database = (SELECT oid FROM pg_database WHERE datname = current_database()) AND classid = 70700003)::text"
                           + " || ' ' || coalesce((SELECT state FROM reviews WHERE pr = 7), '-') AS v");

            if (state.startsWith("0 ")) {
                atRelease = state;
                break;
            }
        }

        assertThat(atRelease).as("lock count and row state when the lock was first seen released").isEqualTo("0 posted");
        assertThat(first.get(30, TimeUnit.SECONDS).status().code()).isEqualTo(200);
        assertThat(gh.seen("POST", "/pulls/7/reviews")).hasSize(1);
    }

    // ---- step 7: existing rows ---------------------------------------------------------------------------------
    @Test
    void step7_aPostedRowWhoseReviewIsStillActive_isAlreadyPosted_withoutANewPost() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f());
        review(PR, fx.f(), "v-a");
        var again = review(PR, fx.f(), "v-a");

        assertThat(again.status().code()).isEqualTo(200);
        assertThat(json(text(again)).path("alreadyPosted").booleanValue()).isTrue();
        assertThat(gh.posts()).isEqualTo(1);
        assertThat(sql("SELECT count(*) || ' ' || max(attempts) AS v FROM reviews WHERE pr = 7")).isEqualTo("1 1");
    }

    @Test
    void r17_aPostedRowWhoseReviewIsNowDismissed_isReposted_attemptsTwo_oneRow() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f());
        review(PR, fx.f(), "v-a");
        gh.dismiss(1001);
        var again = review(PR, fx.f(), "v-a");

        assertThat(again.status().code()).isEqualTo(200);
        assertThat(json(text(again)).path("alreadyPosted").booleanValue()).isFalse();
        assertThat(gh.posts()).isEqualTo(2);
        assertThat(sql("SELECT count(*) || ' ' || max(attempts) || ' ' || max(github_review_id) AS v FROM reviews WHERE pr = 7")).isEqualTo("1 2 1002");
    }

    @Test
    void r21_aSendingRow_doesNotAdoptADismissedReviewOfTheReviewer_itPostsAgain() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f()).review(900, REVIEWER, "DISMISSED", fx.f());
        sendingRow(PR, fx.f(), "APPROVE");
        var retry = review(PR, fx.f(), "v-a");

        assertThat(retry.status().code()).isEqualTo(200);
        assertThat(json(text(retry)).path("alreadyPosted").booleanValue()).isFalse();
        assertThat(gh.posts()).isEqualTo(1);
        assertThat(sql("SELECT state || ' ' || attempts AS v FROM reviews WHERE pr = 7")).isEqualTo("posted 2");
    }

    @Test
    void step7_aSendingRow_doesNotAdoptAnActiveReviewOfSomeoneElse_itPostsAgain() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f()).review(901, "someone-else", "APPROVED", fx.f());
        sendingRow(PR, fx.f(), "APPROVE");
        var retry = review(PR, fx.f(), "v-a");

        assertThat(retry.status().code()).isEqualTo(200);
        assertThat(json(text(retry)).path("alreadyPosted").booleanValue()).isFalse();
        assertThat(gh.posts()).isEqualTo(1);
    }

    @Test
    void step7_aSendingRow_adoptsAnActiveReviewOfTheReviewerOnThatSha_withoutAPost() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f()).review(902, REVIEWER.toUpperCase(), "APPROVED", fx.f());
        sendingRow(PR, fx.f(), "APPROVE");
        var retry = review(PR, fx.f(), "v-a");

        assertThat(json(text(retry)).path("alreadyPosted").booleanValue()).isTrue();
        assertThat(gh.posts()).isZero();
        assertThat(sql("SELECT state || ' ' || github_review_id AS v FROM reviews WHERE pr = 7")).isEqualTo("posted 902");
    }

    // ---- input and GitHub text bounds --------------------------------------------------------------------------
    @Test
    void repo_isGitHubsOwnerNameGrammar_dotSegmentsAndBadOwnersAreRefused() {
        for (var repo : List.of("o/..", "o/.", "../r", "-o/r", "o/", "o/r/x", "o o/r")) {
            var reply = call(HttpMethod.POST,
                             "/api/v1/fleet/reviews",
                             "{\"repo\":\"" + repo
                            + "\",\"pr\":7,\"sha\":\"" + fx.f()
                            + "\",\"as\":\"v-a\",\"body\":\"x\"}");

            assertThat(reply.status().code()).as(repo).isEqualTo(400);
        }

        assertThat(gh.seen()).isEmpty();
    }

    @Test
    void aRejectedReview_reportsGitHubsMessage_butNeverANonJsonBody() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR,
                fx.f())
          .onPost(n -> n == 1
                       ? ReviewGitHubStub.Reply.status(422, "Validation Failed")
                       : new ReviewGitHubStub.Reply(422, "<html>\n<body>proxy secret-marker</body></html>", 0));
        var json = review(PR, fx.f(), "v-a");
        var html = review(PR, fx.f(), "v-a");

        assertThat(text(json)).contains("GITHUB_REJECTED Validation Failed");
        assertThat(html.status().code()).isEqualTo(422);
        assertThat(text(html)).contains("GITHUB_REJECTED non-JSON reply, status 422")
                  .doesNotContain("html")
                  .doesNotContain("secret-marker");
    }

    /// A `sending` row as a crashed poster leaves it (the route's own upsert, so the key and `attempts` are real).
    private void sendingRow(long pr, String sha, String event) {
        var id = Long.parseLong(sql("SELECT max(id) AS v FROM verdicts"));

        store.reviews()
             .sending(NewReview.newReview("o/r",
                                          pr,
                                          sha,
                                          event,
                                          id,
                                          "v-a",
                                          none(),
                                          "fresh",
                                          DIGEST))
             .await(Pg.WAIT)
             .unwrap();
    }

    // ---- GET fleet/reviews (READ) ------------------------------------------------------------------------------
    @Test
    void getReviews_aReadToken_seesExactlyIdStateShaAndSendingAt_ofThatPrsRows() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f());
        review(PR, fx.f(), "v-a");
        sendingRow(PR + 1, fx.m(), "APPROVE");
        var reply = get("/api/v1/fleet/reviews?repo=o/r&pr=" + PR, some("Bearer " + READ));
        var rows = json(text(reply));

        assertThat(reply.status().code()).isEqualTo(200);
        assertThat(rows.size()).isEqualTo(1);
        assertThat(rows.get(0).propertyNames()).containsExactlyInAnyOrder("githubReviewId", "state", "sha", "sendingAt");
        assertThat(rows.get(0).get("state").asText()).isEqualTo("posted");
        assertThat(rows.get(0).get("githubReviewId").isNumber()).isTrue();
        assertThat(rows.get(0).get("sha").asText()).isEqualTo(fx.f());
    }

    @Test
    void getReviews_theReadToken_isRefusedOnThePost() {
        var reply = call(HttpMethod.POST,
                         "/api/v1/fleet/reviews",
                         "{\"repo\":\"o/r\",\"pr\":" + PR + ",\"sha\":\"" + fx.f() + "\",\"as\":\"v-a\",\"body\":\"x\"}",
                         some("Bearer " + READ));

        assertThat(reply.status().code()).isEqualTo(403);
        assertThat(gh.seen()).isEmpty();
    }

    @Test
    void getReviews_withoutAToken_is401() {
        assertThat(get("/api/v1/fleet/reviews?repo=o/r&pr=" + PR, none()).status().code()).isEqualTo(401);
    }

    @Test
    void getReviews_anUnknownPr_isAnEmptyList() {
        var reply = get("/api/v1/fleet/reviews?repo=o/r&pr=999", some("Bearer " + READ));

        assertThat(reply.status().code()).isEqualTo(200);
        assertThat(text(reply)).isEqualTo("[]");
    }

    @Test
    void getReviews_isScopedToTheRepo_aSamePrNumberInAnotherRepoIsNotListed() {
        verdict(PR, fx.f(), "ACCEPT", "main", "v-a");
        gh.head(PR, fx.f());
        review(PR, fx.f(), "v-a");
        var id = Long.parseLong(sql("SELECT max(id) AS v FROM verdicts"));

        store.reviews()
             .sending(NewReview.newReview("other/x",
                                          PR,
                                          fx.m(),
                                          "APPROVE",
                                          id,
                                          "v-a",
                                          none(),
                                          "fresh",
                                          DIGEST))
             .await(Pg.WAIT)
             .unwrap();
        var mine = json(text(get("/api/v1/fleet/reviews?repo=o/r&pr=" + PR, some("Bearer " + READ))));
        var theirs = json(text(get("/api/v1/fleet/reviews?repo=other/x&pr=" + PR, some("Bearer " + READ))));

        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("sha").asText()).isEqualTo(fx.f());
        assertThat(theirs.size()).isEqualTo(1);
        assertThat(theirs.get(0).get("sha").asText()).isEqualTo(fx.m());
    }

    @Test
    void getReviews_beforeTheControllerIsReady_is503NotReady() throws Exception {
        var notReady = router(Duration.ofSeconds(30),
                              TimeSpan.timeSpan(30).seconds(),
                              ProcessRunner.processRunner(),
                              () -> false);
        var early = notReady.handle(ApiRequest.apiRequest(request(HttpMethod.GET,
                                                                  "/api/v1/fleet/reviews?repo=o/r&pr=" + PR,
                                                                  "",
                                                                  some("Bearer " + READ))))
                            .await(Pg.WAIT)
                            .unwrap();

        assertThat(early.status().code()).isEqualTo(503);
        assertThat(text(early)).isEqualTo("{\"error\":\"NOT_READY\"}");
    }

    @Test
    void getReviews_badRepoOrPr_is400() {
        for (var query : List.of("repo=o/..&pr=7",
                                 "repo=o/r&pr=0",
                                 "repo=o/r&pr=-1",
                                 "repo=o/r&pr=x",
                                 "repo=o/r",
                                 "pr=7",
                                 "repo=-o/r&pr=7")) {
            assertThat(get("/api/v1/fleet/reviews?" + query,
                           some("Bearer " + READ)).status().code()).as(query)
                      .isEqualTo(400);
        }
    }

    // ---- fixtures and helpers ----------------------------------------------------------------------------------
    /// A runner that replaces the git command naming `operation` with a 5 s sleep.
    private static ProcessRunner stalling(String operation) {
        var real = ProcessRunner.processRunner();

        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return command.argv()
                              .contains(operation)
                       ? real.run(new Command(List.of("sleep", "5"),
                                              command.workDir(),
                                              command.env(),
                                              command.inheritEnv(),
                                              command.timeout(),
                                              command.outputLimit(),
                                              command.stdoutFile(),
                                              command.stderrFile()))
                       : real.run(command);
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return real.spawn(command);
            }
        };
    }

    private ApiResponse review(long pr, String sha, String as) {
        return call(HttpMethod.POST,
                    "/api/v1/fleet/reviews",
                    "{\"repo\":\"o/r\",\"pr\":" + pr
                   + ",\"sha\":\"" + sha
                   + "\",\"as\":\"" + as
                   + "\",\"body\":\"looks fine\"}");
    }

    /// A verdict row declared by `by`, straight into the table (the route belongs to the merge gate).
    private void verdict(long pr, String sha, String verdict, String base, String by) {
        sql("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256) VALUES ('o/r', " + pr
           + ", '" + sha
           + "', '" + base
           + "', '" + verdict
           + "', '" + by
           + "', '/r/report.md', '" + DIGEST
           + "')");
    }

    /// An ACCEPT at `from`, then the carried ACCEPT at `sha` pointing to it.
    private void carried(long pr, String sha, String from, String base, String by) {
        verdict(pr, from, "ACCEPT", base, by);
        sql("INSERT INTO verdicts (repo, pr, sha, base, verdict, declared_by, report_path, report_sha256, carried_from, carried_verdict_id) VALUES ('o/r', " + pr
           + ", '" + sha
           + "', '" + base
           + "', 'ACCEPT', '" + by
           + "', '/r/report.md', '" + DIGEST
           + "', '" + from
           + "', (SELECT max(id) FROM verdicts))");
    }

    private ApiResponse get(String pathAndQuery, Option<String> authorization) {
        return router.handle(ApiRequest.apiRequest(request(HttpMethod.GET, pathAndQuery, "", authorization)))
                     .await(Pg.WAIT)
                     .unwrap();
    }

    private ApiResponse call(HttpMethod method, String path, String body, Option<String> authorization) {
        return router.handle(ApiRequest.apiRequest(request(method, path, body, authorization)))
                     .await(Pg.WAIT)
                     .unwrap();
    }

    private ApiResponse call(HttpMethod method, String path, String body) {
        return router.handle(ApiRequest.apiRequest(request(method, path, body)))
                     .await(Pg.WAIT)
                     .unwrap();
    }

    private String sql(String statement) {
        return Pg.sql(database, statement);
    }

    private List<FleetEventRow> events(String kind) {
        return switch (store.fleet()
                            .pending(0, 500)
                            .await(Pg.WAIT)
                            .unwrap()) {
            case FleetPending.Events rows -> rows.rows().stream().filter(row -> row.kind()
                                                                                   .equals(kind)).toList();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    private static JsonNode json(String payload) {
        return TemperJson.mapper()
                         .readTree(payload)
                         .unwrap();
    }

    private static String text(ApiResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static HttpRequest request(HttpMethod method, String path, String body) {
        return request(method, path, body, some("Bearer " + VERDICT));
    }

    private static HttpRequest request(HttpMethod method,
                                       String pathAndQuery,
                                       String body,
                                       Option<String> authorization) {
        var parts = pathAndQuery.split("\\?", 2);
        var query = parts.length > 1
                    ? QueryParams.queryParams(Arrays.stream(parts[1].split("&"))
                                                    .map(pair -> pair.split("=", 2))
                                                    .collect(Collectors.toMap(pair -> pair[0],
                                                                              pair -> List.of(pair.length > 1
                                                                                              ? pair[1]
                                                                                              : ""))))
                    : QueryParams.empty();
        var path = parts[0];

        return new HttpRequest() {
            @Override
            public String requestId() {
                return "test";
            }

            @Override
            public HttpMethod method() {
                return method;
            }

            @Override
            public String path() {
                return path;
            }

            @Override
            public Headers headers() {
                return Headers.fromSingleValueMap(authorization.fold(Map::<String, String> of,
                                                                     value -> Map.of("Authorization", value)));
            }

            @Override
            public QueryParams queryParams() {
                return query;
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    private static String git(Path dir, String... args) {
        var argv = new ArrayList<String>(List.of("git",
                                                 "-C",
                                                 dir.toString(),
                                                 "-c",
                                                 "user.name=t",
                                                 "-c",
                                                 "user.email=t@t",
                                                 "-c",
                                                 "commit.gpgsign=false",
                                                 "-c",
                                                 "init.defaultBranch=main"));

        argv.addAll(Arrays.asList(args));
        try {
            var process = HermeticProcess.builder(argv).redirectErrorStream(true).start();
            var out = new String(process.getInputStream().readAllBytes(),
                                 StandardCharsets.UTF_8).strip();

            process.waitFor();

            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(e);
        }
    }

    private static void write(Path dir, String name, String content) throws IOException {
        Files.writeString(dir.resolve(name), content);
    }

    /// The work repository, then its bare clone as the mirror. Every commit under test is held by a branch so the clone has it.
    private static Fixture fixture(Path root) throws IOException {
        var work = root.resolve("work");

        Files.createDirectories(work);
        git(work, "init", "-b", "main");
        write(work, "a.txt", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n");
        write(work, "b.txt", "b\n");
        git(work, "add", ".");
        git(work, "commit", "-m", "c0");
        git(work, "branch", "feat");
        git(work, "branch", "side");
        git(work, "branch", "side2");
        git(work, "checkout", "feat");
        write(work, "a.txt", "1\n2\n3\n4\n5\n6\n7\n8\nnine-feat\n10\n");
        git(work, "commit", "-am", "F");
        var f = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "main");
        write(work, "b.txt", "b2\n");
        git(work, "commit", "-am", "T1");
        var t1 = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "-b", "m-clean", f);
        git(work, "merge", "--no-ff", "-m", "M", "main");
        var m = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "-b", "m-hand", f);
        git(work, "merge", "--no-commit", "--no-ff", "main");
        write(work, "a.txt", "1\n2\n3\n4\n5\n6\n7\n8\nnine-feat\n10\nextra\n");
        git(work, "commit", "-am", "H");
        var h = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "main");
        write(work, "a.txt", "1\n2\n3\n4\n5\n6\n7\n8\nnine-main\n10\n");
        git(work, "commit", "-am", "T2");
        var t2 = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "-b", "m-conflict", f);
        git(work, "merge", "--no-ff", "main");
        write(work, "a.txt", "1\n2\n3\n4\n5\n6\n7\n8\nresolved\n10\n");
        git(work, "commit", "-am", "C");
        var c = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "side");
        write(work, "c.txt", "s\n");
        git(work, "add", "c.txt");
        git(work, "commit", "-m", "S");
        var s = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "main");
        git(work, "merge", "--no-ff", "-m", "T3", "side");
        git(work, "checkout", "-b", "m-off", f);
        git(work, "merge", "--no-ff", "-m", "O", s);
        var o = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "side2");
        write(work, "d.txt", "d\n");
        git(work, "add", "d.txt");
        git(work, "commit", "-m", "S2");
        var side2 = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "-b", "m-side2", f);
        git(work, "merge", "--no-ff", "-m", "B", side2);
        var b = git(work, "rev-parse", "HEAD");

        git(work, "checkout", "main");
        git(work, "branch", "rc5", "main");
        var mirror = root.resolve("mirror.git");

        git(root, "clone", "--bare", work.toString(), mirror.toString());

        return new Fixture(mirror, f, m, t1, h, c, t2, o, s, b, side2);
    }
}
