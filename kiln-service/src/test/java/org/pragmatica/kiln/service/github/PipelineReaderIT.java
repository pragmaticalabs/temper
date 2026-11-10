package org.pragmatica.kiln.service.github;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.kiln.core.pipeline.Removal;
import org.pragmatica.kiln.service.api.PipelineJson;
import org.pragmatica.kiln.service.pipeline.PipelineReader;
import org.pragmatica.kiln.service.pipeline.PipelineScope;
import org.pragmatica.kiln.service.pipeline.PipelineStatus;
import org.pragmatica.kiln.service.pipeline.PipelineView;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.PipelineStore;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The pipeline baseline loop (#102) against a fake GitHub on loopback and a real kiln-pg: GraphQL search paging with the
/// cursor, run paging, every attempt's jobs, the store's progress across a slice stop and the resume (a PR larger than
/// the slice completes over several cycles and no request is ever repeated), an incomplete run read again alone and given
/// up after three days as INCOMPLETE, #2830's real runs and jobs, the start-date floor of the listing, a budget pause that
/// sends nothing and its resume, a 403 and a GraphQL refusal surfacing as the state, and nothing sent without the kiln
/// App.
class PipelineReaderIT {
    static final String REPO = "o/r";
    static final String BASE = "release-1.0.0-rc5";
    static final Instant T0 = Instant.parse("2026-11-06T05:00:00Z");
    static final String SHA_A = "a".repeat(40);
    static final String SHA_B = "b".repeat(40);
    static final String SHA_Q = "c".repeat(40);
    static final String SHA_D = "d".repeat(40);

    private GitHubFake fake;
    private KilnStore store;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0.plusSeconds(86_400));

    @BeforeEach
    void setUp() {
        fake = GitHubFake.start();
        store = PgStores.freshStore();
    }

    @AfterEach
    void tearDown() {
        fake.close();
        await(store.close());
    }

    /// The fake's port, read as the kiln App (the IT's token stands for the App's installation token).
    record AsApp(GitHubHttp http) implements GitHubHttp {
        @Override
        public Promise<GhAnswer> get(String pathAndQuery, Option<String> etag) {
            return http.get(pathAndQuery, etag);
        }

        @Override
        public Promise<GhAnswer> post(String path, String json) {
            return http.post(path, json);
        }

        @Override
        public Promise<Unit> ready() {
            return http.ready();
        }

        @Override
        public Credential.Mode mode() {
            return Credential.Mode.APP;
        }
    }

    private GitHubHttp port() {
        return JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), fake.api(), some("t"), Duration.ofSeconds(10));
    }

    private PipelineReader reader(GitHubHttp http, RateBudget budget, int slice) {
        return PipelineReader.pipelineReader(http,
                                             store.etags(),
                                             budget,
                                             store.pipeline(),
                                             new PipelineScope(REPO,
                                                               BASE,
                                                               Instant.parse("2026-10-28T00:00:00Z"),
                                                               slice,
                                                               Duration.ofMinutes(10)),
                                             now::get);
    }

    private PipelineReader reader(int slice) {
        return reader(new AsApp(port()), RateBudget.rateBudget(1_000_000, 10), slice);
    }

    // ---- canned GitHub -------------------------------------------------------------------------------------------------
    static String at(long minutes) {
        return "\"" + T0.plusSeconds(minutes * 60) + "\"";
    }

    /// PR 1: commit A (before the queue), failed in the queue, commit B made before it re-entered, merged. PR 2: commit D,
    /// never queued.
    static String prNode(long number) {
        return number == 1
               ? "{\"number\":1,\"title\":\"one\",\"mergedAt\":" + at(120)
                + ",\"headRefName\":\"feat-1\","
                + "\"commits\":{\"totalCount\":2,\"nodes\":[" + commit(SHA_A, 1, -10)
                + "," + commit(SHA_B, 1, 70)
                + "]},"
                + "\"timelineItems\":{\"nodes\":[{\"__typename\":\"AddedToMergeQueueEvent\",\"createdAt\":" + at(30)
                + "},"
                + "{\"__typename\":\"RemovedFromMergeQueueEvent\",\"createdAt\":" + at(60)
                + ",\"reason\":\"failed_checks\"},"
                + "{\"__typename\":\"AddedToMergeQueueEvent\",\"createdAt\":" + at(90)
                + "},"
                + "{\"__typename\":\"RemovedFromMergeQueueEvent\",\"createdAt\":" + at(119)
                + ",\"reason\":\"merged\"}]}}"
               : "{\"number\":2,\"title\":\"two <b>\",\"mergedAt\":" + at(200)
                + ",\"headRefName\":\"feat-2\","
                + "\"commits\":{\"totalCount\":1,\"nodes\":[" + commit(SHA_D, 1, 150)
                + "]},\"timelineItems\":{\"nodes\":[]}}";
    }

    /// A PR merged at `mergedAt`, one commit, never queued.
    static String plainNode(long number, String mergedAt, String headRef) {
        return "{\"number\":" + number
             + ",\"title\":\"t\",\"mergedAt\":\"" + mergedAt
             + "\",\"headRefName\":\"" + headRef
             + "\",\"commits\":{\"totalCount\":1,\"nodes\":[" + commit(SHA_D, 1, 0)
             + "]},\"timelineItems\":{\"nodes\":[]}}";
    }

    /// PR 3: commit A, queued at 30 and merged at 119 (its queue runs are listed in PR 1's window).
    static String bigNode() {
        return "{\"number\":3,\"title\":\"big\",\"mergedAt\":" + at(120)
             + ",\"headRefName\":\"feat-3\",\"commits\":{\"totalCount\":1,\"nodes\":[" + commit(SHA_A, 1, -10)
             + "]},\"timelineItems\":{\"nodes\":[{\"__typename\":\"AddedToMergeQueueEvent\",\"createdAt\":" + at(30)
             + "},{\"__typename\":\"RemovedFromMergeQueueEvent\",\"createdAt\":" + at(119)
             + ",\"reason\":\"merged\"}]}}";
    }

    static String commit(String oid, int parents, long minute) {
        return "{\"commit\":{\"oid\":\"" + oid
             + "\",\"messageHeadline\":\"fix\",\"committedDate\":" + at(minute)
             + ",\"parents\":{\"totalCount\":" + parents
             + "}}}";
    }

    static String search(String node, Option<String> next) {
        return "{\"data\":{\"search\":{\"pageInfo\":{\"hasNextPage\":" + next.isPresent()
             + ",\"endCursor\":" + next.map(cursor -> "\"" + cursor + "\"")
                                       .or("null")
             + "},\"nodes\":[" + node
             + "]}}}";
    }

    static String run(long id, String event, String sha, String branch, String status, long attempt) {
        return "{\"id\":" + id
             + ",\"name\":\"CI\",\"status\":\"" + status
             + "\",\"conclusion\":null,\"head_sha\":\"" + sha
             + "\",\"html_url\":\"https://x/" + id
             + "\",\"created_at\":" + at(0)
             + ",\"event\":\"" + event
             + "\",\"head_branch\":\"" + branch
             + "\",\"run_attempt\":" + attempt
             + "}";
    }

    static String runPath(long run) {
        return "/repos/" + REPO + "/actions/runs/" + run;
    }

    static String resource(String name) {
        try (InputStream in = PipelineReaderIT.class.getResourceAsStream("/pipeline/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String runs(String... runs) {
        return "{\"total_count\":" + runs.length + ",\"workflow_runs\":[" + String.join(",", runs) + "]}";
    }

    static String jobs(String name, long startMinute, long seconds) {
        return "{\"total_count\":1,\"jobs\":[{\"name\":\"" + name
             + "\",\"started_at\":" + at(startMinute)
             + ",\"completed_at\":\"" + T0.plusSeconds(startMinute * 60 + seconds)
             + "\"}]}";
    }

    static String jobsPath(long run, long attempt) {
        return "/repos/" + REPO + "/actions/runs/" + run + "/attempts/" + attempt + "/jobs?per_page=100";
    }

    static String prRunsPath(String branch) {
        return "/repos/" + REPO + "/actions/runs?event=pull_request&branch=" + branch + "&per_page=100";
    }

    static String queueRunsPath() {
        return "/repos/" + REPO
             + "/actions/runs?event=merge_group&created=" + T0.plusSeconds(29 * 60)
             + ".." + T0.plusSeconds(121 * 60)
             + "&per_page=100";
    }

    /// Two search pages (PR 1 with cursor `c1`, then PR 2); PR 1's PR runs over two pages (one run on A with two
    /// attempts, one foreign run on another sha), its queue runs (its own, and another PR's); PR 2's one run.
    private void canned(String pr1Status) {
        fake.answers("/graphql",
                     List.of(search(prNode(1), some("c1")), search(prNode(2), Option.none())));
        fake.pages(prRunsPath("feat-1"),
                   List.of(runs(run(11, "pull_request", SHA_A, "feat-1", pr1Status, 2)),
                           runs(run(12, "pull_request", "e".repeat(40), "feat-1", "completed", 1),
                                run(13, "pull_request", SHA_B, "feat-1", "completed", 1))));
        fake.answer(queueRunsPath(),
                    runs(run(21, "merge_group", SHA_Q, "gh-readonly-queue/" + BASE + "/pr-1-" + SHA_Q, "completed", 1),
                         run(22, "merge_group", SHA_Q, "gh-readonly-queue/" + BASE + "/pr-9-" + SHA_Q, "completed", 1)));
        fake.answer(runPath(11), run(11, "pull_request", SHA_A, "feat-1", pr1Status, 2));
        fake.answer(jobsPath(11, 1), jobs("test-group-g1", 0, 1000));
        fake.answer(runPath(11) + "/attempts/2", "{\"run_attempt\":2,\"run_started_at\":" + at(61) + "}");
        fake.answer(jobsPath(11, 2), jobs("ember-shard-1", 61, 400));
        fake.answer(jobsPath(13, 1), jobs("forge-tests", 70, 300));
        fake.answer(jobsPath(21, 1), jobs("test-group-g2", 31, 2000));
        fake.answer(prRunsPath("feat-2"), runs(run(31, "pull_request", SHA_D, "feat-2", "completed", 1)));
        fake.answer(jobsPath(31, 1), jobs("renamed-shard-7", 150, 600));
    }

    private List<PipelineStore.Row> rows() {
        return await(store.pipeline().rows());
    }

    private List<String> graphQlBodies() {
        return fake.requests()
                   .stream()
                   .filter(request -> request.path()
                                             .equals("/graphql"))
                   .map(GitHubFake.Request::body)
                   .toList();
    }

    /// Every REST path asked more than once (GraphQL excluded: each cycle lists anew, by design).
    private Map<String, Long> repeated() {
        return fake.requests()
                   .stream()
                   .map(GitHubFake.Request::path)
                   .filter(path -> !path.equals("/graphql"))
                   .collect(Collectors.groupingBy(Function.identity(),
                                                  Collectors.counting()))
                   .entrySet()
                   .stream()
                   .filter(entry -> entry.getValue() > 1)
                   .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private long asked(String path) {
        return fake.requests()
                   .stream()
                   .filter(request -> request.path()
                                             .equals(path))
                   .count();
    }

    // ---- tests ---------------------------------------------------------------------------------------------------------
    @Test
    void cycle_pagesSearchAndRuns_measuresEveryAttempt() {
        canned("completed");
        var reader = reader(100);
        var state = await(reader.cycle());
        var rows = rows();
        var one = rows.getFirst().measure().unwrap();

        assertThat(state.kind()).isEqualTo(PipelineStatus.Kind.OK);
        assertThat(state.reason()).isEqualTo("2 of 2 PRs measured");
        assertThat(fake.requests()
                       .stream()
                       .filter(request -> request.path()
                                                 .equals("/graphql"))
                       .map(GitHubFake.Request::body)
                       .toList()).hasSize(2)
                  .last()
                  .asString()
                  .contains("\"after\":\"c1\"");
        assertThat(rows).extracting(PipelineStore.Row::number).containsExactly(1L, 2L);
        assertThat(one.complete()).isTrue();
        assertThat(one.runs()).isEqualTo(3);
        assertThat(one.attempts()).isEqualTo(4);
        assertThat(one.prSeconds()).isEqualTo(1000);
        assertThat(one.queueSeconds()).isEqualTo(2000);
        assertThat(one.rerunSeconds()).isEqualTo(700);
        assertThat(one.removals()).extracting(Removal::kind).containsExactly(Removal.Kind.REAL_FAILURE);
        assertThat(rows.getLast().measure().map(PrMeasure::unknown).unwrap()).containsExactly("CI / renamed-shard-7");
        assertThat(asked(jobsPath(22, 1))).isZero();
        assertThat(asked(jobsPath(12, 1))).isZero();
        var json = PipelineJson.pipelineJson(await(reader.view()));
        var pr1 = json.prs().getLast();

        assertThat(json.measured()).isEqualTo(2);
        assertThat(json.weeks()).singleElement()
                  .satisfies(week -> assertThat(week.week()).isEqualTo("2026-W45"))
                  .satisfies(week -> assertThat(week.hours().total()).isEqualTo(1.19))
                  .satisfies(week -> assertThat(week.realFailures()).isEqualTo(1));
        assertThat(pr1.number()).isEqualTo(1);
        assertThat(pr1.hours().total()).isEqualTo(1.03);
        assertThat(pr1.hours().queueFirstEntry()).isEqualTo(0.56);
        assertThat(pr1.state()).isEqualTo("COMPLETE");
        assertThat(repeated()).isEmpty();
        assertThat(pr1.removals()).singleElement()
                  .satisfies(removal -> assertThat(removal.kind()).isEqualTo("REAL_FAILURE"))
                  .satisfies(removal -> assertThat(removal.reason()).isEqualTo("failed_checks"));
        assertThat(json.unknownNames()).containsExactly("CI / renamed-shard-7");
    }

    @Test
    void sliceStop_keepsProgress_andResumeNeverRereadsAFinalPr() {
        canned("completed");
        var reader = reader(10);
        var first = await(reader.cycle());
        var afterFirst = rows();

        assertThat(first.kind()).isEqualTo(PipelineStatus.Kind.FILLING);
        assertThat(afterFirst).hasSize(2);
        assertThat(afterFirst.getFirst().complete()).isTrue();
        assertThat(afterFirst.getLast().measure().isEmpty()).isTrue();
        assertThat(await(reader.view()).measured()).isEqualTo(1);
        assertThat(fake.requests()).hasSize(10);
        assertThat(graphQlBodies()).first().asString().contains("merged:>=2026-10-28T00:00:00Z");
        var second = await(reader.cycle());

        assertThat(second.kind()).isEqualTo(PipelineStatus.Kind.OK);
        assertThat(rows()).allMatch(PipelineStore.Row::complete);
        assertThat(asked(jobsPath(11, 1))).isEqualTo(1);
        assertThat(asked(queueRunsPath())).isEqualTo(1);
        // the second listing looks back one day from the newest stored merge (PR 2, 08:20 on 11-06), not from the start
        assertThat(graphQlBodies()).last().asString().contains("merged:>=2026-11-05T08:20:00Z");
    }

    /// A run in progress is read again alone (`GET /actions/runs/{id}`) each cycle; its settled attempt is never read
    /// again, its open attempt only once the run has completed, and the listings never.
    @Test
    void runInProgress_onlyTheIncompleteRunIsReadAgain_untilComplete() {
        canned("in_progress");
        var reader = reader(100);

        await(reader.tick());
        assertThat(rows().getFirst().complete()).isFalse();
        assertThat(await(reader.view()).status().kind()).isEqualTo(PipelineStatus.Kind.FILLING);
        assertThat(PipelineJson.pipelineJson(await(reader.view())).prs().getLast().state()).isEqualTo("IN_PROGRESS");
        assertThat(asked(runPath(11))).isZero();
        canned("completed");
        await(reader.tick());
        assertThat(rows().getFirst().complete()).isTrue();
        assertThat(asked(runPath(11))).isEqualTo(1);
        assertThat(asked(jobsPath(11, 1))).isEqualTo(1);
        assertThat(asked(jobsPath(11, 2))).isEqualTo(2);
        assertThat(asked(jobsPath(13, 1))).isEqualTo(1);
        assertThat(asked(prRunsPath("feat-1"))).isEqualTo(1);
        assertThat(asked(queueRunsPath())).isEqualTo(1);
    }

    /// A run that never completes costs one request a cycle, and three days after the merge none: the PR is stored
    /// complete with the run counted INCOMPLETE, in the store, the JSON and the week.
    @Test
    void runNeverCompleting_isGivenUpThreeDaysAfterTheMerge_asIncomplete() {
        canned("in_progress");
        var reader = reader(100);

        await(reader.cycle());
        await(reader.cycle());
        assertThat(asked(runPath(11))).isEqualTo(1);
        assertThat(asked(jobsPath(11, 2))).isEqualTo(1);
        assertThat(rows().getFirst().complete()).isFalse();
        now.set(T0.plusSeconds(120 * 60).plus(PipelineReader.GIVE_UP).plusSeconds(60));
        var before = fake.requests().size();
        var state = await(reader.cycle());
        var json = PipelineJson.pipelineJson(await(reader.view()));
        var pr1 = json.prs().getLast();

        assertThat(fake.pathsSince(before)).containsOnly("/graphql");
        assertThat(state.kind()).isEqualTo(PipelineStatus.Kind.OK);
        assertThat(rows()).allMatch(PipelineStore.Row::complete);
        assertThat(pr1.number()).isEqualTo(1);
        assertThat(pr1.state()).isEqualTo("INCOMPLETE");
        assertThat(pr1.incompleteRuns()).isEqualTo(1);
        assertThat(pr1.complete()).isTrue();
        assertThat(pr1.incompleteKind()).isEqualTo("RUNS_NEVER_COMPLETED");
        assertThat(pr1.incompleteReason()).isEqualTo("1 run(s) never completed");
        assertThat(pr1.hours().total()).isGreaterThan(0);
        assertThat(json.excluded()).isEqualTo(1);
        assertThat(json.excludedPrs()).singleElement().satisfies(excluded -> {
            assertThat(excluded.number()).isEqualTo(1);
            assertThat(excluded.kind()).isEqualTo("RUNS_NEVER_COMPLETED");
            assertThat(excluded.reason()).isEqualTo("1 run(s) never completed");
        });
        assertThat(json.weeks()).singleElement().satisfies(week -> {
            assertThat(week.incomplete()).isEqualTo(1);
            assertThat(week.excluded()).isEqualTo(1);
            assertThat(week.complete()).isEqualTo(1);
            assertThat(week.hours().total()).isEqualTo(json.prs().getFirst().hours().total());
        });
        await(reader.cycle());
        assertThat(asked(runPath(11))).isEqualTo(1);
    }

    /// PR 3 costs 15 REST requests (2 listings, 13 attempts) against a slice of 5 (one of them the search): it completes
    /// over four cycles, each resuming where the last stopped, and no REST request is ever sent twice.
    @Test
    void prLargerThanTheSlice_completesOverSeveralCycles_withNoRequestRepeated() {
        fake.answer("/graphql", search(bigNode(), Option.none()));
        fake.answer(prRunsPath("feat-3"),
                    runs(java.util.stream.LongStream.rangeClosed(101, 112)
                                                    .mapToObj(id -> run(id,
                                                                        "pull_request",
                                                                        SHA_A,
                                                                        "feat-3",
                                                                        "completed",
                                                                        1))
                                                    .toArray(String[]::new)));
        fake.answer(queueRunsPath(),
                    runs(run(201, "merge_group", SHA_Q, "gh-readonly-queue/" + BASE + "/pr-3-" + SHA_Q, "completed", 1)));
        java.util.stream.LongStream.rangeClosed(101, 112)
                                   .forEach(id -> fake.answer(jobsPath(id, 1),
                                                              jobs("test-group-g1", 0, 100)));
        fake.answer(jobsPath(201, 1), jobs("test-group-g2", 31, 500));
        var reader = reader(5);
        var first = await(reader.cycle());
        var progress = await(store.pipeline().progress(3));

        assertThat(first.kind()).isEqualTo(PipelineStatus.Kind.FILLING);
        assertThat(rows().getFirst().measure().isEmpty()).isTrue();
        assertThat(progress.prRunsListed()).isTrue();
        assertThat(progress.queueRunsListed()).isTrue();
        assertThat(progress.runs()).hasSize(13);
        assertThat(progress.attempts()).hasSize(2);
        var cycles = 1;

        while (await(reader.cycle()).kind() != PipelineStatus.Kind.OK && cycles < 10) {
            cycles++;
        }

        cycles++;
        var measure = rows().getFirst().measure().unwrap();

        assertThat(cycles).isEqualTo(4);
        assertThat(repeated()).isEmpty();
        assertThat(asked("/graphql")).isEqualTo(4);
        assertThat(measure.complete()).isTrue();
        assertThat(measure.runs()).isEqualTo(13);
        assertThat(measure.attempts()).isEqualTo(13);
        assertThat(measure.prSeconds()).isEqualTo(1200);
        assertThat(measure.queueSeconds()).isEqualTo(500);
    }

    /// #2830 from recorded answers (its GraphQL node, its two run listings and its seven attempts' jobs, trimmed to the
    /// fields kiln reads): the figures the review checked by hand against the live API. Full reactor is the ruling's
    /// set (9289 s); `build-and-test` (40 s) and `push-dedupe` (7 s) are other (477 s).
    @Test
    void recorded2830_measuresAsTheReviewCheckedByHand() {
        var ids = List.of(42399112242L,
                          42399112378L,
                          42399112403L,
                          42399112484L,
                          42399112531L,
                          42401449580L,
                          42401449596L);

        now.set(Instant.parse("2026-11-07T20:00:00Z"));
        fake.answer("/graphql", resource("2830-search.json"));
        fake.answer(prRunsPath("streams%2F7c-topic-preflight"), resource("2830-runs-pull_request.json"));
        fake.answer("/repos/" + REPO
                   + "/actions/runs?event=merge_group&created=2026-11-07T18:27:02Z..2026-11-07T18:54:25Z&per_page=100",
                    resource("2830-runs-merge_group.json"));
        ids.forEach(id -> fake.answer(jobsPath(id, 1), resource("2830-jobs-" + id + "-1.json")));
        var reader = reader(100);
        var state = await(reader.cycle());
        var measure = rows().getFirst().measure().unwrap();
        var pr = PipelineJson.pipelineJson(await(reader.view())).prs().getFirst();

        assertThat(state.kind()).isEqualTo(PipelineStatus.Kind.OK);
        assertThat(repeated()).isEmpty();
        assertThat(ids).allMatch(id -> asked(jobsPath(id, 1)) == 1);
        assertThat(measure.complete()).isTrue();
        assertThat(measure.runs()).isEqualTo(7);
        assertThat(measure.attempts()).isEqualTo(7);
        assertThat(measure.prSeconds()).isEqualTo(12_866);
        assertThat(measure.queueSeconds()).isEqualTo(12_280);
        assertThat(measure.rerunSeconds()).isZero();
        assertThat(measure.removals()).isEmpty();
        assertThat(measure.baseSyncs()).isEqualTo(1);
        assertThat(measure.syncSeconds()).isZero();
        assertThat(measure.unknown()).isEmpty();
        assertThat(pr.hours().total()).isEqualTo(6.99);
        assertThat(measure.fullReactorSeconds()).isEqualTo(9_289);
        assertThat(measure.otherSeconds()).isEqualTo(477);
        assertThat(pr.hours().fullReactor()).isEqualTo(2.58);
        assertThat(pr.hours().ember()).isEqualTo(3.08);
        assertThat(pr.hours().forge()).isEqualTo(0.5);
        assertThat(pr.hours().consensus()).isEqualTo(0.69);
        assertThat(pr.hours().other()).isEqualTo(0.13);
    }

    /// Run 42398657995 of pragmaticalabs/pragmatica, attempts 1 and 2 recorded (`gh api`, trimmed to the fields kiln reads;
    /// the PR around it is synthetic, its real PR #2828 being still open). Attempt 2 re-ran only `ember-shard-2`
    /// (18:27:13-18:45:28, 1095 s) but its jobs page lists all 17 jobs, 16 of them copied from attempt 1 with its times
    /// (12232 s). Only the job started at or after attempt 2's `run_started_at` (18:27:08) counts, and that start files it
    /// after the removal at 18:25. Attempt 1 needs no extra request.
    @Test
    void recordedRerun_countsOnlyTheJobsTheAttemptRan() {
        var id = 42398657995L;
        var sha = "af89e0d8d8b94f38ab65515a30f7aecbe9c18eca";
        var node = "{\"number\":2828,\"title\":\"rerun\",\"mergedAt\":\"2026-11-07T19:00:00Z\","
                 + "\"headRefName\":\"ahse/a3-1-catalog-core\",\"commits\":{\"totalCount\":1,\"nodes\":[{\"commit\":{\"oid\":\"" + sha
                 + "\",\"messageHeadline\":\"fix\",\"committedDate\":\"2026-11-07T17:50:00Z\","
                 + "\"parents\":{\"totalCount\":1}}}]},\"timelineItems\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":["
                 + "{\"__typename\":\"AddedToMergeQueueEvent\",\"createdAt\":\"2026-11-07T18:20:00Z\"},"
                 + "{\"__typename\":\"RemovedFromMergeQueueEvent\",\"createdAt\":\"2026-11-07T18:25:00Z\",\"reason\":\"failed_checks\"},"
                 + "{\"__typename\":\"AddedToMergeQueueEvent\",\"createdAt\":\"2026-11-07T18:50:00Z\"},"
                 + "{\"__typename\":\"RemovedFromMergeQueueEvent\",\"createdAt\":\"2026-11-07T19:00:00Z\",\"reason\":\"merged\"}]}}";

        now.set(Instant.parse("2026-11-07T20:00:00Z"));
        fake.answer("/graphql", search(node, Option.none()));
        fake.answer(prRunsPath("ahse%2Fa3-1-catalog-core"),
                    runs(run(id, "pull_request", sha, "ahse/a3-1-catalog-core", "completed", 2)));
        fake.answer("/repos/" + REPO
                   + "/actions/runs?event=merge_group&created=2026-11-07T18:19:00Z..2026-11-07T19:01:00Z&per_page=100",
                    runs());
        fake.answer(runPath(id) + "/attempts/2", resource("rerun-attempt-" + id + "-2.json"));
        fake.answer(jobsPath(id, 1), resource("rerun-jobs-" + id + "-1.json"));
        fake.answer(jobsPath(id, 2), resource("rerun-jobs-" + id + "-2.json"));
        var state = await(reader(100).cycle());
        var measure = rows().getFirst().measure().unwrap();

        assertThat(state.kind()).isEqualTo(PipelineStatus.Kind.OK);
        assertThat(repeated()).isEmpty();
        assertThat(asked(runPath(id) + "/attempts/2")).isEqualTo(1);
        assertThat(asked(runPath(id) + "/attempts/1")).isZero();
        assertThat(measure.attempts()).isEqualTo(2);
        assertThat(measure.prSeconds()).isEqualTo(12_266);
        assertThat(measure.rerunSeconds()).isEqualTo(1_095);
        assertThat(measure.queueSeconds()).isZero();
        assertThat(measure.totalSeconds()).isEqualTo(12_266 + 1_095);
        assertThat(measure.emberSeconds()).isEqualTo(5_683 + 1_095);
        assertThat(measure.removals()).extracting(Removal::kind).containsExactly(Removal.Kind.FLAKE);
    }

    /// A PR whose timeline has a next page is recorded INCOMPLETE with its reason, no REST request spent on it; the next PR
    /// of the same cycle is measured; the week sums leave the truncated one out and the JSON and the tab count it; a later
    /// cycle does not read it again.
    @Test
    void timelineOverOnePage_marksThatPrIncomplete_andMovesOn() {
        var truncated = plainNode(7, "2026-11-06T06:00:00Z", "feat-7").replace("\"timelineItems\":{\"nodes\"",
                                                                              "\"timelineItems\":{\"pageInfo\":{\"hasNextPage\":true},\"nodes\"");
        fake.answer("/graphql", search(truncated + "," + plainNode(8, "2026-11-06T07:00:00Z", "feat-8"), Option.none()));
        fake.answer(prRunsPath("feat-8"), runs(run(31, "pull_request", SHA_D, "feat-8", "completed", 1)));
        fake.answer(jobsPath(31, 1), jobs("forge-tests", 0, 600));
        var reader = reader(100);
        var state = await(reader.cycle());
        var view = await(reader.view());
        var json = PipelineJson.pipelineJson(view);
        var seven = json.prs().stream().filter(pr -> pr.number() == 7).findFirst().orElseThrow();
        var eight = json.prs().stream().filter(pr -> pr.number() == 8).findFirst().orElseThrow();

        assertThat(graphQlBodies()).first().asString().contains("pageInfo { hasNextPage } nodes { __typename");
        assertThat(state.kind()).isEqualTo(PipelineStatus.Kind.OK);
        assertThat(rows()).allMatch(PipelineStore.Row::complete);
        assertThat(seven.state()).isEqualTo("INCOMPLETE");
        assertThat(seven.incompleteReason()).contains("more than 100 queue events");
        assertThat(seven.hours().total()).isZero();
        assertThat(fake.requests()).extracting(GitHubFake.Request::path)
                  .noneMatch(path -> path.contains("feat-7"));
        assertThat(eight.state()).isEqualTo("COMPLETE");
        assertThat(eight.hours().forge()).isGreaterThan(0);
        assertThat(json.excluded()).isEqualTo(1);
        assertThat(json.weeks()).singleElement().satisfies(week -> {
            assertThat(week.prs()).isEqualTo(2);
            assertThat(week.measured()).isEqualTo(2);
            assertThat(week.incomplete()).isEqualTo(1);
            assertThat(week.excluded()).isEqualTo(1);
            assertThat(week.hours().total()).isEqualTo(eight.hours().total());
            assertThat(week.hours().forge()).isEqualTo(eight.hours().forge());
        });
        assertThat(view.excluded()).isEqualTo(1);

        var before = fake.requests().size();

        await(reader.cycle());
        assertThat(fake.pathsSince(before)).containsOnly("/graphql");
        assertThat(PipelineJson.pipelineJson(await(reader.view())).excluded()).isEqualTo(1);
    }

    /// The listing looks back a day from the newest stored merge, but never before the start date: a PR merged six hours
    /// after it does not pull the listing to the day before.
    @Test
    void listing_neverReachesBeforeTheStartDate() {
        fake.answer("/graphql", search(plainNode(7, "2026-10-28T06:00:00Z", "feat-7"), Option.none()));
        fake.answer(prRunsPath("feat-7"), runs());
        var reader = reader(100);

        await(reader.cycle());
        await(reader.cycle());
        assertThat(graphQlBodies()).hasSize(2)
                  .allSatisfy(body -> assertThat(body).contains("merged:>=2026-10-28T00:00:00Z")
                                                .doesNotContain("2026-10-27"));
        assertThat(rows()).singleElement().matches(PipelineStore.Row::complete);
    }

    @Test
    void budgetAtReserve_pausesAndSendsNothing_thenResumes() {
        canned("completed");
        var budget = RateBudget.rateBudget(1_000_000, 100);
        var reader = reader(new AsApp(port()), budget, 100);

        budget.observed(remaining(50), now.get());
        var paused = await(reader.cycle());

        assertThat(paused.kind()).isEqualTo(PipelineStatus.Kind.PAUSED);
        assertThat(paused.reason()).contains("reserve");
        assertThat(fake.requests()).isEmpty();
        budget.observed(remaining(4000), now.get());
        var resumed = await(reader.cycle());

        assertThat(resumed.kind()).isEqualTo(PipelineStatus.Kind.OK);
        assertThat(rows()).hasSize(2).allMatch(PipelineStore.Row::complete);
    }

    private GhAnswer remaining(int remaining) {
        return GhAnswer.ghAnswer(200,
                                 Option.none(),
                                 "",
                                 some(remaining),
                                 some(5000),
                                 some(now.get().plusSeconds(1200)),
                                 Option.none(),
                                 Option.none());
    }

    @Test
    void forbidden_surfacesAsFailedWithReason_inStateAndJson() {
        canned("completed");
        fake.answer(jobsPath(11, 1), 403, "{\"message\":\"Resource not accessible by integration\"}");
        var reader = reader(100);
        var state = await(reader.cycle());
        var json = PipelineJson.pipelineJson(await(reader.view()));

        assertThat(state.kind()).isEqualTo(PipelineStatus.Kind.FAILED);
        assertThat(state.reason()).contains("403").contains("actions:read");
        assertThat(json.state().kind()).isEqualTo("FAILED");
        assertThat(json.state().reason()).contains("403");
        assertThat(rows()).noneMatch(PipelineStore.Row::complete);
    }

    @Test
    void graphQlRefusal_andNotFound_surfaceAsFailed() {
        fake.answer("/graphql", "{\"data\":null,\"errors\":[{\"message\":\"Resource not accessible by integration\"}]}");
        var refused = await(reader(100).cycle());

        fake.answer("/graphql", search(prNode(2), Option.none()));
        var missing = await(reader(100).cycle());

        assertThat(refused.kind()).isEqualTo(PipelineStatus.Kind.FAILED);
        assertThat(refused.reason()).contains("Resource not accessible by integration");
        assertThat(missing.kind()).isEqualTo(PipelineStatus.Kind.FAILED);
        assertThat(missing.reason()).contains("404");
    }

    @Test
    void withoutTheApp_nothingIsSent() {
        canned("completed");
        var state = await(reader(port(), RateBudget.rateBudget(1_000_000, 10), 100).cycle());
        PipelineView view = await(reader(port(), RateBudget.rateBudget(1_000_000, 10), 100).view());

        assertThat(state.kind()).isEqualTo(PipelineStatus.Kind.NEEDS_APP);
        assertThat(state.reason()).contains("TOKEN");
        assertThat(fake.requests()).isEmpty();
        assertThat(view.total()).isZero();
    }
}
