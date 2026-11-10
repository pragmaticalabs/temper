package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.http.ApiRequest;
import org.pragmatica.kiln.service.http.ApiResponse;
import org.pragmatica.kiln.service.store.BaseTip;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.service.github.GitHubFixtures.MAIN;
import static org.pragmatica.kiln.service.github.GitHubFixtures.REPO;
import static org.pragmatica.kiln.service.github.GitHubFixtures.ROOT;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.checkRuns;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quoted;
import static org.pragmatica.kiln.service.github.GitHubFixtures.requiring;
import static org.pragmatica.kiln.service.github.GitHubFixtures.runsPage;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.kiln.service.github.GitHubFixtures.status;
import static org.pragmatica.kiln.service.github.GitHubFixtures.suiteRun;
import static org.pragmatica.kiln.service.github.GitHubFixtures.workflowRun;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `GET /api/v1/pr-fresh` over the GitHub stub (the rows marked B, at the HTTP level): the reads, the
/// JSON, the 503/400/404 shapes, and R2-7.b (no `If-None-Match`, even with ETags stored for the same paths).
class PrFreshTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final Sha HEAD = sha(2);
    private static final String H = HEAD.value();
    private static final String PULL = ROOT + "pulls/2";
    private static final String BRANCH = ROOT + "branches/main";
    private static final String RULES = ROOT + "rules/branches/main";
    private static final String CHECK_RUNS = ROOT + "commits/" + H + "/check-runs?filter=all&per_page=100";
    private static final String HEAD_RUNS = ROOT + "actions/runs?head_sha=" + H + "&per_page=100";
    private static final String STATUSES = ROOT + "commits/" + H + "/statuses?per_page=100";
    private static final String FILES = ROOT + "pulls/2/files?per_page=100";
    private static final long SUITE = 7;

    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private final List<String> tipReads = new ArrayList<>();
    private Option<BaseTip> stored = none();

    @BeforeEach
    void start() {
        fake = GitHubFake.start();
        fake.answer(PULL, pull("open", "null", HEAD, "main", 2));
        fake.answer(BRANCH, requiring("ci"));
        fake.answer(RULES, "[]");
        fake.answer(CHECK_RUNS, checkRuns(suiteRun(20, "ci", "completed", "success", T0, SUITE)));
        fake.answer(HEAD_RUNS, runsPage(List.of(prRun(100, "success"))));
        fake.answer(STATUSES, "[]");
        fake.answer(FILES, "[{\"filename\":\"src/a.java\"},{\"filename\":\"ci/x.yml\",\"previous_filename\":\".github/workflows/x.yml\"}]");
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    static String pull(String state, String mergedAt, Sha head, String base, int changed) {
        return "{\"number\":2,\"state\":\"" + state + "\",\"merged_at\":" + mergedAt + ",\"head\":{\"sha\":\"" + head.value()
               + "\",\"ref\":\"feat\",\"repo\":{\"full_name\":\"fork/r\"}},\"base\":{\"ref\":\"" + base + "\"},\"changed_files\":"
               + changed + ",\"merged_by\":null}";
    }

    private static String prRun(long id, String conclusion) {
        return workflowRun(id, "CI", "pull_request", "feat", "completed", conclusion, 1, SUITE, T0, T0);
    }

    private PrFresh fresh(Duration deadline, RateBudget budget) {
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), fake.api(), some("t"), Duration.ofSeconds(10));

        return new PrFresh(http, budget, this::tip, List.of(KilnConfig.Repo.repo(REPO, List.of(MAIN))), now::get, deadline);
    }

    private Promise<Option<BaseTip>> tip(String repo, BranchName base) {
        tipReads.add(repo + " " + base.value());

        return Promise.success(stored);
    }

    private ApiResponse ask(Map<String, String> query) {
        return ask(fresh(PrFresh.DEADLINE, RateBudget.rateBudget(1_000_000, 10)), query);
    }

    private static ApiResponse ask(PrFresh fresh, Map<String, String> query) {
        return await(fresh.answer(ApiRequest.apiRequest("GET", PrFresh.PATH, query, "")));
    }

    private static JsonNode json(ApiResponse response) {
        assertThat(response.contentType()).isEqualTo(ApiResponse.JSON);

        return MAPPER.readTree(response.body()).unwrap();
    }

    private JsonNode gated() {
        var response = ask(Map.of("repo", REPO, "pr", "2", "sha", H));

        assertThat(response.status()).isEqualTo(200);
        return json(response);
    }

    private static List<String> reasons(JsonNode reply) {
        return reply.get("reasons").valueStream().map(reason -> reason.get("check").asString() + " " + reason.get("line").asString()).toList();
    }

    @Test
    void prFresh_allGreen_answersTheContractJson_withoutReasons() {
        var reply = gated();

        assertThat(reasons(reply)).isEmpty();
        assertThat(reply.get("asOf").asString()).isEqualTo("2026-10-30T10:00:00Z");
        assertThat(reply.get("sha").asString()).isEqualTo(H);
        assertThat(reply.get("pull").toString()).isEqualTo("{\"state\":\"open\",\"headSha\":\"" + H + "\",\"headRef\":\"feat\",\"headRepo\":\"fork/r\","
                                                            + "\"base\":\"main\",\"changedFiles\":2}");
        assertThat(reply.get("required").get("state").asString()).isEqualTo("REQUIRED");
        assertThat(reply.get("required").get("contexts").toString()).isEqualTo("[{\"context\":\"ci\",\"appId\":15368}]");
        assertThat(reply.get("required").get("etag").asString()).isEqualTo(GitHubFake.etag(requiring("ci")));
        assertThat(reply.get("checks").toString()).isEqualTo("[{\"context\":\"ci\",\"appId\":15368,\"outcome\":\"green\",\"observation\":\"7\","
                                                              + "\"workflow\":\"CI\",\"runId\":100,\"runNumber\":null,\"attempt\":1,\"conclusion\":\"success\"}]");
        assertThat(reply.get("counts").toString()).isEqualTo("{\"checkRuns\":{\"read\":1,\"total\":1},\"workflowRuns\":{\"read\":1,\"total\":1}}");
        assertThat(reply.get("requiredAgain").get("state").asString()).isEqualTo("REQUIRED");
        assertThat(reply.get("requiredAgain").get("etag").asString()).isEqualTo(GitHubFake.etag(requiring("ci")));
        assertThat(reply.get("baseTip").toString()).isEqualTo("{\"tracked\":true,\"sha\":null,\"seenAt\":null}");
        assertThat(reply.get("reads").asInt()).isEqualTo(9);
        assertThat(fake.pathsSince(0)).containsExactly(PULL, BRANCH, RULES, CHECK_RUNS, HEAD_RUNS, STATUSES, FILES, BRANCH, RULES);
        assertThat(tipReads).containsExactly(REPO + " main");
    }

    @Test
    void prFresh_renameListsBothPaths_g9f() {
        var files = gated().get("files");

        assertThat(files.get("read").asInt()).isEqualTo(2);
        assertThat(files.get("paths").toString()).isEqualTo("[\"src/a.java\",\"ci/x.yml\",\".github/workflows/x.yml\"]");
    }

    @Test
    void prFresh_neverSendsIfNoneMatch_r27b() {
        gated();
        gated();

        assertThat(fake.requests()).isNotEmpty().allSatisfy(request -> assertThat(request.ifNoneMatch()).isNull());
        assertThat(fake.notModifieds()).isZero();
    }

    @Test
    void prFresh_headMovedAndMerged_g2() {
        fake.answer(PULL, pull("closed", quoted(T0), sha(3), "main", 2));

        assertThat(reasons(gated())).containsExactly("G2 head-moved pulls " + sha(3).value(), "G2 not-open merged");
    }

    @Test
    void prFresh_noneRequired_requiredNone_noChecksJudged_g3a() {
        fake.answer(BRANCH, GitHubFixtures.unprotected());

        var reply = gated();

        assertThat(reasons(reply)).containsExactly("G3 required-none");
        assertThat(reply.get("required").get("detail").asString()).isEqualTo("disabled");
        assertThat(reply.get("checks").size()).isZero();
    }

    @Test
    void prFresh_redRunOnPage2OfCheckRuns_checkRed_g4b() {
        fake.pages(CHECK_RUNS, List.of("{\"total_count\":2,\"check_runs\":[" + suiteRun(21, "lint", "completed", "success", T0, SUITE) + "]}",
                                       "{\"total_count\":2,\"check_runs\":[" + suiteRun(22, "ci", "completed", "failure", T0, SUITE) + "]}"));

        assertThat(reasons(gated())).containsExactly("G4 check-red ci 7");
    }

    @Test
    void prFresh_totalCountAboveDistinctIds_inconsistent_stopsG4_g4h() {
        fake.answer(CHECK_RUNS, "{\"total_count\":3,\"check_runs\":[" + suiteRun(22, "ci", "completed", "failure", T0, SUITE) + "]}");
        fake.answer(HEAD_RUNS, "{\"total_count\":2,\"workflow_runs\":[" + prRun(100, "success") + "]}");

        var reply = gated();

        assertThat(reasons(reply)).containsExactly("G4 check-read-inconsistent check-runs 1/3", "G4 check-read-inconsistent workflow-runs 1/2");
        assertThat(reply.get("checks").size()).isZero();
    }

    @Test
    void prFresh_forkPrRunListsNoPr_checkMissing_g4l() {
        fake.answer(HEAD_RUNS, runsPage(List.of(workflowRun(100, "CI", "pull_request", "feat", "completed", "success", 1, SUITE, T0, T0, List.of()))));

        var reply = gated();

        assertThat(reasons(reply)).containsExactly("G4 check-missing ci");
        assertThat(reply.get("checks").get(0).get("observation").isNull()).isTrue();
    }

    @Test
    void prFresh_unpinnedFailingStatus_checkRedStatus_g4g() {
        fake.answer(BRANCH, GitHubFixtures.requiringUnpinned("ci"));
        fake.answer(STATUSES, array(status(5, "ci", "failure")));

        assertThat(reasons(gated())).containsExactly("G4 check-red ci status");
    }

    @Test
    void prFresh_listChangesBetweenReads_requiredChanged_g10() {
        fake.answers(BRANCH, List.of(requiring("ci"), requiring("ci2")));

        var reply = gated();

        assertThat(reasons(reply)).containsExactly("G10 required-changed");
        assertThat(reply.get("requiredAgain").get("contexts").toString()).isEqualTo("[{\"context\":\"ci2\",\"appId\":15368}]");
    }

    @Test
    void prFresh_storedTip_isAnsweredFromS1_untrackedBaseIsNotRead() {
        stored = some(new BaseTip(MAIN, sha(9), sha(8), T0.plusMillis(1500)));

        assertThat(gated().get("baseTip").toString()).isEqualTo("{\"tracked\":true,\"sha\":\"" + sha(9).value() + "\",\"seenAt\":\"2026-10-30T10:00:01Z\"}");

        tipReads.clear();
        fake.answer(PULL, pull("open", "null", HEAD, "stacked", 2));
        fake.answer(ROOT + "branches/stacked", requiring("ci"));
        fake.answer(ROOT + "rules/branches/stacked", "[]");

        assertThat(gated().get("baseTip").toString()).isEqualTo("{\"tracked\":false,\"sha\":null,\"seenAt\":null}");
        assertThat(tipReads).isEmpty();
    }

    @Test
    void prFresh_withoutSha_readsR1Only_andAnswersOnlyPull() {
        var response = ask(Map.of("repo", REPO, "pr", "2"));
        var reply = json(response);

        assertThat(response.status()).isEqualTo(200);
        assertThat(reply.propertyNames()).containsExactly("asOf", "repo", "pr", "pull", "reads");
        assertThat(reply.get("reads").asInt()).isEqualTo(1);
        assertThat(fake.pathsSince(0)).containsExactly(PULL);
    }

    @Test
    void prFresh_githubStatus_unavailable503() {
        fake.answer(STATUSES, 502, "{}");

        var response = ask(Map.of("repo", REPO, "pr", "2", "sha", H));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UNAVAILABLE\",\"detail\":\"github 502 " + STATUSES + "\"}");
    }

    @Test
    void prFresh_undecodable_unavailableDecode() {
        fake.answer(PULL, "{}");

        assertThat(ask(Map.of("repo", REPO, "pr", "2", "sha", H)).body()).isEqualTo("{\"error\":\"UNAVAILABLE\",\"detail\":\"decode pulls\"}");
    }

    /// The on-demand reads count toward kiln's hourly cap: a cap of 2 sends exactly 2 requests, counts them, and
    /// answers 503 rate-budget for the third.
    @Test
    void prFresh_readsCountTowardTheHourlyCap() {
        var budget = RateBudget.rateBudget(2, 10);
        budget.observed(new GhAnswer(200, none(), "", some(4000), some(5000), some(T0.plusSeconds(3600)), none(), none()), T0);

        var response = ask(fresh(PrFresh.DEADLINE, budget), Map.of("repo", REPO, "pr", "2", "sha", H));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UNAVAILABLE\",\"detail\":\"rate-budget\"}");
        assertThat(fake.requests()).hasSize(2);
        assertThat(budget.spent(T0)).isEqualTo(2);
    }

    @Test
    void prFresh_budgetRefused_unavailableRateBudget() {
        fake.rate(12, 5000, T0.plusSeconds(3600), false);

        var response = ask(Map.of("repo", REPO, "pr", "2", "sha", H));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UNAVAILABLE\",\"detail\":\"rate-budget\"}");
    }

    @Test
    void prFresh_pastTheDeadline_unavailableDeadline() {
        fake.delay(Duration.ofMillis(700));

        var response = ask(fresh(Duration.ofSeconds(1), RateBudget.rateBudget(1_000_000, 10)), Map.of("repo", REPO, "pr", "2", "sha", H));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UNAVAILABLE\",\"detail\":\"deadline\"}");
    }

    // verifier t2wpbv
    @Test
    void vx5_workflowRunsShortAlone_inconsistent_stopsG4() {
        fake.answer(CHECK_RUNS, checkRuns(suiteRun(22, "ci", "completed", "failure", T0, SUITE)));
        fake.answer(HEAD_RUNS, "{\"total_count\":2,\"workflow_runs\":[" + prRun(100, "failure") + "]}");

        var reply = gated();

        assertThat(reasons(reply)).containsExactly("G4 check-read-inconsistent workflow-runs 1/2");
        assertThat(reply.get("checks").size()).isZero();
    }

    @Test
    void vx6_filesStopAtThirtyPages_g9() {
        fake.pages(FILES, java.util.stream.IntStream.rangeClosed(1, 31).mapToObj(n -> "[{\"filename\":\"f" + n + "\"}]").toList());

        var reply = gated();

        assertThat(reply.get("files").get("read").asInt()).isEqualTo(30);
        assertThat(fake.pathsSince(0).stream().filter(path -> path.startsWith(FILES))).hasSize(30);
    }

    @Test
    void vx7_networkFailure_unavailableUnreachable() {
        GitHubHttp down = (_, _) -> org.pragmatica.lang.utils.Causes.cause("connect refused").promise();
        var response = ask(new PrFresh(down, RateBudget.rateBudget(1_000_000, 10), this::tip, List.of(KilnConfig.Repo.repo(REPO, List.of(MAIN))), now::get, PrFresh.DEADLINE),
                           Map.of("repo", REPO, "pr", "2", "sha", H));

        assertThat(response.status()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"error\":\"UNAVAILABLE\",\"detail\":\"github unreachable " + PULL + "\"}");
    }

    @Test
    void vx8_pastTheDeadline_noFurtherReadsAfterThe503() throws InterruptedException {
        fake.delay(Duration.ofMillis(700));

        var response = ask(fresh(Duration.ofSeconds(1), RateBudget.rateBudget(1_000_000, 10)), Map.of("repo", REPO, "pr", "2", "sha", H));
        var atAnswer = fake.requests().size();

        Thread.sleep(4000);
        assertThat(response.status()).isEqualTo(503);
        assertThat(fake.requests().size()).isLessThanOrEqualTo(atAnswer + 1);
    }

    @Test
    void prFresh_badParameters_400_unconfiguredRepo_404() {
        assertThat(ask(Map.of("repo", REPO, "pr", "2", "sha", "abc")).status()).isEqualTo(400);
        assertThat(ask(Map.of("repo", REPO, "pr", "x", "sha", H)).status()).isEqualTo(400);
        assertThat(ask(Map.of("pr", "2")).status()).isEqualTo(400);
        assertThat(ask(Map.of("repo", REPO, "pr", "2", "sha", H.toUpperCase())).status()).isEqualTo(200);

        var unconfigured = ask(Map.of("repo", "other/repo", "pr", "2", "sha", H));

        assertThat(unconfigured.status()).isEqualTo(404);
        assertThat(unconfigured.body()).isEqualTo("{\"error\":\"repo not configured\"}");
        assertThat(json(ask(Map.of("repo", REPO, "pr", "0"))).get("error").asString()).startsWith("bad parameter pr");
    }
}
