package org.pragmatica.kiln.service.github;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.feed.RunFeed;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.PgStores;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.pragmatica.kiln.service.github.GitHubFixtures.APP;
import static org.pragmatica.kiln.service.github.GitHubFixtures.MAIN;
import static org.pragmatica.kiln.service.github.GitHubFixtures.PULLS;
import static org.pragmatica.kiln.service.github.GitHubFixtures.REPO;
import static org.pragmatica.kiln.service.github.GitHubFixtures.ROOT;
import static org.pragmatica.kiln.service.github.GitHubFixtures.T0;
import static org.pragmatica.kiln.service.github.GitHubFixtures.array;
import static org.pragmatica.kiln.service.github.GitHubFixtures.await;
import static org.pragmatica.kiln.service.github.GitHubFixtures.botIssue;
import static org.pragmatica.kiln.service.github.GitHubFixtures.checkRuns;
import static org.pragmatica.kiln.service.github.GitHubFixtures.feedPoller;
import static org.pragmatica.kiln.service.github.GitHubFixtures.feedQuiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.open;
import static org.pragmatica.kiln.service.github.GitHubFixtures.pr;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quiet;
import static org.pragmatica.kiln.service.github.GitHubFixtures.quietHead;
import static org.pragmatica.kiln.service.github.GitHubFixtures.requiring;
import static org.pragmatica.kiln.service.github.GitHubFixtures.runsPage;
import static org.pragmatica.kiln.service.github.GitHubFixtures.sha;
import static org.pragmatica.kiln.service.github.GitHubFixtures.suiteRun;
import static org.pragmatica.kiln.service.github.GitHubFixtures.workflowRun;


/// The change feed's GitHub-derived kinds against a [GitHubFake] and real Postgres (CI interface T1 WP2, design §10 T1):
/// the positive controls T1.1 (pending→failure), T1.2 (granularity pin), T1.4 rerun of an old push run (R2-11), T1.7
/// (an issue opened and closed while kiln was stopped; a red push run 25 runs back), the bounded re-read within the
/// budget (R3-5), a required-list change, and a `[[repos]]` repository's changes carrying their repo.
@Tag("pg")
class GitHubFeedIT {
    private static final String CONTROL = "pragmaticalabs/temper-control";
    private static final String CONTROL_ROOT = "/repos/" + CONTROL + "/";
    private static final String CHECK_RUNS = ROOT + "commits/" + sha(2).value() + "/check-runs?filter=all&per_page=100";
    private static final String HEAD_RUNS = ROOT + "actions/runs?head_sha=" + sha(2).value() + "&per_page=100";
    private static final long SUITE = 7;
    private static final String OPEN_PULLS = "pulls?state=open&per_page=100";

    private KilnStore store;
    private GitHubFake fake;
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private GitHubPoller poller;

    @BeforeEach
    void start() {
        store = PgStores.freshStore();
        fake = GitHubFake.start();
        quiet(fake, List.of(2L), List.of(sha(2)));
        feedQuiet(fake, ROOT);
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        fake.pages(ROOT + OPEN_PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        poller = feedPoller(fake, store, now, BranchHeads.NONE, List.of());
    }

    @AfterEach
    void stop() {
        fake.close();
        await(store.close());
    }

    // T1.1

    @Test
    void pendingThenFailure_isOneCheckConcluded_andChecksSettledRed() {
        fake.answer(CHECK_RUNS, checkRuns(suiteRun(20, "ci", "in_progress", null, T0, SUITE)));
        fake.answer(HEAD_RUNS, runsPage(List.of(workflowRun(70, "CI", "pull_request", "feat", "in_progress", null, 1, SUITE, T0, T0))));
        await(poller.cycle());
        assertThat(kinds("CHECK_CONCLUDED", "CHECKS_SETTLED")).isEmpty();

        fake.answer(CHECK_RUNS, checkRuns(suiteRun(20, "ci", "completed", "failure", T0, SUITE)));
        fake.answer(HEAD_RUNS,
                    runsPage(List.of(workflowRun(70, "CI", "pull_request", "feat", "completed", "failure", 1, SUITE, T0, T0.plusSeconds(50)))));
        later(60);
        await(poller.cycle());

        assertThat(kinds("CHECK_CONCLUDED")).singleElement()
                                            .satisfies(row -> {
                                                assertThat(row.subject()).isEqualTo("ci");
                                                assertThat(row.before()).isEqualTo("none");
                                                assertThat(row.after()).isEqualTo("failure");
                                                assertThat(row.pr()).isEqualTo(Option.some(prNumber(2)));
                                                assertThat(json(row)).contains("\"runId\":70",
                                                                                          "\"suiteId\":7",
                                                                                          "\"event\":\"pull_request\"",
                                                                                          "\"attempt\":1",
                                                                                          "\"required\":true",
                                                                                          "\"listVersion\":1",
                                                                                          "\"source\":\"check-run\"");
                                            });
        assertThat(kinds("CHECKS_SETTLED")).singleElement()
                                           .satisfies(row -> {
                                               assertThat(row.after()).isEqualTo("red");
                                               assertThat(row.subject()).isEqualTo(sha(2).value());
                                               assertThat(json(row)).contains("\"rule\":\"concluded\"");
                                           });

        later(120);
        await(poller.cycle());
        assertThat(kinds("CHECK_CONCLUDED", "CHECKS_SETTLED")).hasSize(2);

        // a moved head starts its settle row again: the same red is reported for the new head
        quietHead(fake, 2, sha(4));
        fake.answer(ROOT + "commits/" + sha(4).value() + "/check-runs?filter=all&per_page=100", checkRuns(suiteRun(22, "ci", "completed", "failure", T0, 8)));
        fake.answer(ROOT + "actions/runs?head_sha=" + sha(4).value() + "&per_page=100",
                    runsPage(List.of(workflowRun(72, "CI", "pull_request", "feat", "completed", "failure", 1, 8, T0, T0))));
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(4), T0.plusSeconds(150)))));
        fake.pages(ROOT + OPEN_PULLS, List.of(array(open(2, "feat", sha(4), T0.plusSeconds(150)))));
        later(180);
        await(poller.cycle());
        assertThat(kinds("CHECKS_SETTLED")).extracting(ChangeFeed.Row::subject)
                                           .containsExactly(sha(2).value(), sha(4).value());
    }

    /// F-2 (verifier): only a push run of the PR's check name exists. Were push runs to qualify, it would settle the
    /// head green at once; it is concluded on its own, and the head settles `missing` after the window.
    @Test
    void aPushRunOfTheSameName_neverStandsInForThePrRun() {
        fake.answer(CHECK_RUNS, checkRuns(suiteRun(21, "ci", "completed", "success", T0, 8)));
        fake.answer(HEAD_RUNS, runsPage(List.of(workflowRun(71, "CI", "push", "feat", "completed", "success", 1, 8, T0, T0))));
        await(poller.cycle());

        assertThat(kinds("CHECK_CONCLUDED")).extracting(ChangeFeed.Row::after)
                                            .containsExactly("success");
        assertThat(kinds("CHECKS_SETTLED")).isEmpty();

        later(600);
        await(poller.cycle());
        assertThat(kinds("CHECKS_SETTLED")).singleElement()
                                           .extracting(ChangeFeed.Row::after)
                                           .isEqualTo("missing");
    }

    // T1.2: a granularity pin, not a control (NOTE 10): the declared loss is that B is never seen

    @Test
    void granularityPin_headMovedAToBToCWithinOneCycle_isOneHeadMovedAToC() {
        await(poller.cycle());
        quietHead(fake, 2, sha(4));
        fake.pages(PULLS, List.of(array(open(2, "feat", sha(4), T0.plusSeconds(30)))));
        later(60);
        await(poller.cycle());

        assertThat(kinds("HEAD_MOVED")).singleElement()
                                       .satisfies(row -> {
                                           assertThat(row.before()).isEqualTo(sha(2).value());
                                           assertThat(row.after()).isEqualTo(sha(4).value());
                                       });
    }

    // T1.4 rerun of an old push run (R2-11)

    @Test
    void aRerunOfAnOldPushRun_outsideTheWindow_isPushRunConcludedAttempt2() {
        var old = workflowRun(500, "S3", "push", "main", "completed", "failure", 1, 50, T0.minus(Duration.ofDays(3)), T0.minus(Duration.ofDays(3)));
        var recent = workflowRun(501, "S3", "push", "main", "completed", "success", 1, 51, T0.minusSeconds(3600), T0.minusSeconds(3000));

        fake.answer(window("actions/runs?branch=main&event=push", T0.minus(Duration.ofDays(7))), runsPage(List.of(recent, old)));
        await(poller.cycle());
        assertThat(kinds("PUSH_RUN_CONCLUDED")).extracting(ChangeFeed.Row::after)
                                               .containsExactlyInAnyOrder("failure", "success");

        fake.answer(window("actions/runs?branch=main&event=push", T0.minusSeconds(3600 + 600)), runsPage(List.of(recent)));
        fake.answer(ROOT + "actions/runs/500",
                    workflowRun(500, "S3", "push", "main", "completed", "success", 2, 50, T0.minus(Duration.ofDays(3)), T0.plusSeconds(30)));
        later(60);
        await(poller.cycle());

        assertThat(kinds("PUSH_RUN_CONCLUDED")).hasSize(3)
                                               .last()
                                               .satisfies(row -> {
                                                   assertThat(row.subject()).isEqualTo("main");
                                                   assertThat(row.before()).isEqualTo("failure");
                                                   assertThat(row.after()).isEqualTo("success");
                                                   assertThat(json(row)).contains("\"runId\":500", "\"attempt\":2");
                                               });
    }

    // T1.7 outage recovery: the paths are literal, so a mutated read path cannot follow the test

    @Test
    void anIssueOpenedAndClosedWhileKilnWasStopped_isOpenedThenClosed() {
        fake.pages(ROOT + "issues?state=all&creator=app/github-actions&sort=updated&direction=asc&per_page=100&since="
                   + T0.minus(Duration.ofDays(7)),
                   List.of(array(botIssue(8, "open", T0.minusSeconds(9000), T0.minusSeconds(9000))),
                           array(botIssue(9, "closed", T0.minusSeconds(7200), T0.minusSeconds(3600)))));
        await(poller.cycle());
        fake.answer(ROOT + "issues?state=all&creator=app/github-actions&sort=updated&direction=asc&per_page=100&since="
                    + T0.minusSeconds(3600),
                    array(botIssue(9, "closed", T0.minusSeconds(7200), T0.minusSeconds(3600))));
        later(60);
        await(poller.cycle());

        assertThat(kinds("BOT_ISSUE_OPENED", "BOT_ISSUE_CLOSED")).extracting(ChangeFeed.Row::kind, ChangeFeed.Row::subject)
                                                                 .containsExactly(tuple("BOT_ISSUE_OPENED", "8"),
                                                                                  tuple("BOT_ISSUE_OPENED", "9"),
                                                                                  tuple("BOT_ISSUE_CLOSED", "9"));
    }

    @Test
    void aRedPushRun25RunsBack_isSeen_becauseTheWindowIsPagedToTheEnd() {
        var path = window("actions/runs?branch=main&event=push", T0.minus(Duration.ofDays(7)));
        var green = LongStream.rangeClosed(1, 24)
                              .mapToObj(id -> workflowRun(1000 + id, "S3", "push", "main", "completed", "success", 1, id, T0.minusSeconds(id * 60), T0))
                              .toList();
        var red = workflowRun(2025, "S3", "push", "main", "completed", "failure", 1, 99, T0.minusSeconds(3600 * 5), T0);

        fake.pages(path, List.of(runsPage(green.subList(0, 20)), runsPage(List.of(green.get(20), green.get(21), green.get(22), green.get(23), red))));
        await(poller.cycle());

        assertThat(kinds("PUSH_RUN_CONCLUDED")).hasSize(25)
                                               .filteredOn(row -> row.after().equals("failure"))
                                               .singleElement()
                                               .satisfies(row -> assertThat(json(row)).contains("\"runId\":2025"));
    }

    @Test
    void aCancelledReleasePushRun_isConcluded_andRunCancelled() {
        var release = BranchName.branchName("release-1.0.0-rc5").unwrap();

        poller = feedPoller(fake, store, now, _ -> Promise.success(List.of(release, BranchName.branchName("feature/x").unwrap())), List.of());
        fake.answer(window("actions/runs?branch=release-1.0.0-rc5&event=push", T0.minus(Duration.ofDays(7))),
                    runsPage(List.of(workflowRun(600, "S3", "push", "release-1.0.0-rc5", "completed", "cancelled", 1, 60, T0.minusSeconds(600), T0))));
        var before = poller.need(List.of()) - poller.watch().driftNeed();
        await(poller.cycle());

        // F-3: the listed release branch's push-run read is counted from now on (the drift reads are counted apart)
        assertThat(poller.need(List.of()) - poller.watch().driftNeed()).isEqualTo(before + 1);

        assertThat(kinds("PUSH_RUN_CONCLUDED", "RUN_CANCELLED")).extracting(ChangeFeed.Row::kind, ChangeFeed.Row::subject)
                                                                .containsExactly(tuple("PUSH_RUN_CONCLUDED", "release-1.0.0-rc5"),
                                                                                 tuple("RUN_CANCELLED", "release-1.0.0-rc5"));
        assertThat(fake.requests()).extracting(GitHubFake.Request::path)
                                   .noneMatch(path -> path.contains("feature%2Fx"));
    }

    // R3-5

    @Test
    void sevenDayBacklogOf50Runs_isReReadWithinThreeCycles_neverPastTheBudget() {
        var backlog = backlog(50);

        fake.rate(200, 5000, T0.plusSeconds(3600), false);
        var read = new HashSet<String>();

        IntStream.range(0, 3)
                 .forEach(cycle -> {
                     var from = fake.requests().size();

                     later(60L * (cycle + 1));
                     assertThat(await(poller.cycle())).isInstanceOf(SourceState.Ok.class);
                     var reread = rereads(fake.pathsSince(from));

                     assertThat(reread).hasSizeLessThanOrEqualTo(GitHubFeed.REREAD_MAX);
                     read.addAll(reread);
                 });
        assertThat(read).hasSize(backlog.size());
    }

    @Test
    void reRead_spendsOnlyWhatTheBudgetLeavesAboveTheReserve() {
        backlog(50);
        var path = window("actions/runs?branch=main&event=push", T0.minus(Duration.ofDays(2)).minus(RunFeed.OVERLAP));

        fake.pages(path, IntStream.range(0, 12).mapToObj(_ -> runsPage(List.of())).toList());
        fake.rate(46, 5000, T0.plusSeconds(3600), false);
        later(60);

        var from = fake.requests().size();

        assertThat(await(poller.cycle())).isInstanceOf(SourceState.Ok.class);
        assertThat(rereads(fake.pathsSince(from))).hasSizeBetween(1, GitHubFeed.REREAD_MAX - 1);
        assertThat(poller.rate().remaining()).isEqualTo(Option.some(10));
    }

    // required lists

    @Test
    void aChangedRequiredList_isOneRequiredListChanged_andANewVersion() {
        await(poller.cycle());
        fake.answer(ROOT + "branches/main",
                    "{\"name\":\"main\",\"protection\":{\"enabled\":true,\"required_status_checks\":{\"enforcement_level\":"
                    + "\"non_admins\",\"checks\":[{\"context\":\"ci\",\"app_id\":" + APP + "},{\"context\":\"lint\",\"app_id\":null}]}}}");
        later(60);
        await(poller.cycle());
        later(120);
        await(poller.cycle());
        fake.answer(ROOT + "branches/main", requiring("ci"));
        later(180);
        await(poller.cycle());

        // N-4: each version keeps the branch read's ETag
        assertThat(PgStores.dump(store, "required_list_history")).hasSize(3)
                                                                 .noneMatch(row -> row.replace("\": ", "\":").contains("\"etag\":null"))
                                                                 .anyMatch(row -> row.contains(GitHubFake.etag(requiring("ci")).replace("\"", "")));
        assertThat(kinds("REQUIRED_LIST_CHANGED")).hasSize(2)
                                                  .last()
                                                  .satisfies(row -> assertThat(json(row)).contains("\"version\":3"));
        assertThat(kinds("REQUIRED_LIST_CHANGED")).first()
                                                  .satisfies(row -> {
                                                      assertThat(row.subject()).isEqualTo("main");
                                                      assertThat(row.after()).isEqualTo("ci@" + APP + ",lint");
                                                      assertThat(json(row)).contains("\"version\":2");
                                                  });
    }

    @Test
    void aRunGitHubNoLongerHas_isForgotten_andTheCycleStaysOk() {
        backlog(1);
        fake.answer(ROOT + "actions/runs/3001", 404, GitHubFake.NOT_FOUND);
        later(60);

        assertThat(await(poller.cycle())).isInstanceOf(SourceState.Ok.class);
        assertThat(await(store.feed().rereadable(REPO, T0.minus(Duration.ofDays(7)), T0.plusSeconds(3600), 10))).isEmpty();
    }

    @Test
    void need_countsTheFeedsReads_perOpenPr_perBase_andPerOtherRepository() {
        var feed = GitHubFeed.gitHubFeed(null,
                                         null,
                                         BranchHeads.NONE,
                                         List.of(KilnConfig.Repo.repo(REPO, List.of(MAIN)),
                                                 KilnConfig.Repo.repo(CONTROL, List.of(MAIN, BranchName.branchName("x").unwrap()))));

        // per open PR 3; per repository: open PRs 1, 2 per base, listings 2, main and each other base 1 (release-* once
        // listed), scheduled + issues 2, re-read
        assertThat(feed.need(2)).isEqualTo(3 * 2 + (1 + 2 + 2 + 1 + 2 + GitHubFeed.REREAD_MAX)
                                           + (1 + 4 + 2 + 2 + 2 + GitHubFeed.REREAD_MAX));
        assertThat(GitHubFeed.gitHubFeed(null, null, BranchHeads.NONE, List.of()).need(5)).isEqualTo(15);
    }

    // R3-5 (lead ruling): the not-completed listings come first, so a rerun is re-read before the round robin reaches it

    @Test
    void aRerunListedInProgress_isReReadFirst_aheadOfAnOlderBacklog() {
        backlog(50);
        await(store.feed().runs(REPO,
                                List.of(new WorkflowRun(500, "S3", "completed", Option.some("failure"), "x", "https://x", T0.minus(Duration.ofDays(3)),
                                                        Option.some("push"), Option.some("main"), 1, Option.some(50L), Option.some(T0), Option.none(), Option.none(),
                                                        List.of())),
                                T0.minusSeconds(1800)));
        fake.answer(ROOT + "actions/runs?status=in_progress&per_page=100",
                    runsPage(List.of(workflowRun(500, "S3", "push", "main", "in_progress", null, 2, 50, T0.minus(Duration.ofDays(3)), T0),
                                     workflowRun(900, "CI", "pull_request", "feat", "in_progress", null, 1, 90, T0, T0))));
        fake.answer(ROOT + "actions/runs/500",
                    workflowRun(500, "S3", "push", "main", "completed", "success", 2, 50, T0.minus(Duration.ofDays(3)), T0.plusSeconds(90)));
        // PIT: were the PR run kept, its re-read would keep it (a 404 would forget it and hide the filter)
        fake.answer(ROOT + "actions/runs/900", workflowRun(900, "CI", "pull_request", "feat", "in_progress", null, 1, 90, T0, T0));
        later(60);
        await(poller.cycle());
        fake.answer(ROOT + "actions/runs?status=in_progress&per_page=100", runsPage(List.of()));
        later(120);
        await(poller.cycle());

        // the seeding reports its own first sights; only run 500's attempt 2 is under test: listed in progress and, being
        // non-terminal, re-read by id in the same first cycle (lead ruling b)
        assertThat(kinds("PUSH_RUN_CONCLUDED")).filteredOn(row -> json(row).contains("\"runId\":500,") && json(row).contains("\"attempt\":2"))
                                               .singleElement()
                                               .satisfies(row -> assertThat(row.observedAt()).isEqualTo(T0.plusSeconds(60)));
        assertThat(await(store.feed().rereadable(REPO, T0.minus(Duration.ofDays(7)), T0.plusSeconds(3600), 100))).doesNotContain(900L);
    }

    // one path for every repository (lead ruling): the same sequence as `[github] repo` and as a `[[repos]]` repository

    @Test
    void theSameSequence_asPrimaryAndAsSecondRepository_givesTheSameRows_apartFromRepo() {
        poller = feedPoller(fake, store, now, BranchHeads.NONE, List.of(KilnConfig.Repo.repo(CONTROL, List.of(MAIN))));
        feedQuiet(fake, CONTROL_ROOT);
        fake.answer(CONTROL_ROOT + "branches/main", requiring("ci"));
        fake.answer(CONTROL_ROOT + "rules/branches/main", "[]");
        fake.pages(CONTROL_ROOT + OPEN_PULLS, List.of(array(open(2, "feat", sha(2), T0))));
        lane(ROOT, "in_progress", null);
        lane(CONTROL_ROOT, "in_progress", null);
        await(poller.cycle());
        lane(ROOT, "completed", "failure");
        lane(CONTROL_ROOT, "completed", "failure");
        later(60);
        await(poller.cycle());

        var feedKinds = List.of("CHECK_CONCLUDED", "CHECKS_SETTLED", "PUSH_RUN_CONCLUDED", "SCHEDULED_RUN_CONCLUDED",
                                "RUN_CANCELLED", "BOT_ISSUE_OPENED", "BOT_ISSUE_CLOSED", "REQUIRED_LIST_CHANGED");
        var primary = rowsOf(REPO, feedKinds);

        assertThat(primary).extracting(row -> row.get(0))
                           .contains("CHECK_CONCLUDED", "CHECKS_SETTLED", "PUSH_RUN_CONCLUDED", "BOT_ISSUE_OPENED", "BOT_ISSUE_CLOSED");
        assertThat(rowsOf(CONTROL, feedKinds)).isEqualTo(primary);
    }

    /// One repository's answers for the identity test: PR #2's `ci` check in a pull_request suite, a red push run on main,
    /// a scheduled run, and an issue opened and closed.
    private void lane(String root, String status, String conclusion) {
        fake.answer(root + "commits/" + sha(2).value() + "/check-runs?filter=all&per_page=100",
                    checkRuns(suiteRun(20, "ci", status, conclusion, T0, SUITE)));
        fake.answer(root + "commits/" + sha(2).value() + "/statuses?per_page=100", "[]");
        fake.answer(root + "actions/runs?head_sha=" + sha(2).value() + "&per_page=100",
                    runsPage(List.of(workflowRun(70, "CI", "pull_request", "feat", status, conclusion, 1, SUITE, T0, T0))));
        fake.answer(root + GitHubFeed.windowQuery("actions/runs?branch=main&event=push", T0.minus(Duration.ofDays(7))),
                    runsPage(List.of(workflowRun(501, "S3", "push", "main", "completed", "failure", 1, 51, T0.minusSeconds(3600), T0))));
        fake.answer(root + GitHubFeed.windowQuery("actions/runs?event=schedule", T0.minus(Duration.ofDays(7))),
                    runsPage(List.of(workflowRun(601, "nightly", "schedule", "main", "completed", "success", 1, 61, T0.minusSeconds(7200), T0))));
        fake.answer(root + "issues?state=all&creator=app/github-actions&sort=updated&direction=asc&per_page=100&since="
                    + T0.minus(Duration.ofDays(7)),
                    array(botIssue(9, "closed", T0.minusSeconds(7200), T0.minusSeconds(3600))));
    }

    /// The rows of `repo`, in id order, without the repo and the id: kind, pr, subject, before, after, observedAt, payload.
    private List<List<String>> rowsOf(String repo, List<String> kinds) {
        return await(store.changes().feed(0, 500)).changes()
                                                  .stream()
                                                  .filter(row -> row.repo().equals(repo) && kinds.contains(row.kind()))
                                                  .map(row -> List.of(row.kind(), row.pr().map(pr -> Long.toString(pr.value())).or("-"),
                                                                      row.subject(), row.before(), row.after(),
                                                                      row.observedAt().toString(), json(row)))
                                                  .toList();
    }

    // [[repos]]

    @Test
    void aSecondRepository_recordsItsChangesUnderItsOwnRepo() {
        poller = feedPoller(fake, store, now, BranchHeads.NONE, List.of(KilnConfig.Repo.repo(CONTROL, List.of(MAIN))));
        feedQuiet(fake, CONTROL_ROOT);
        fake.pages(CONTROL_ROOT + "pulls?state=open&per_page=100",
                   List.of(array(pr(1, "open", "main", "control/pr", sha(5), T0))));
        fake.answer(CONTROL_ROOT + "branches/main", requiring("control"));
        fake.answer(CONTROL_ROOT + "rules/branches/main", "[]");
        fake.answer(CONTROL_ROOT + "commits/" + sha(5).value() + "/check-runs?filter=all&per_page=100",
                    checkRuns(suiteRun(30, "control", "completed", "failure", T0, 9)));
        fake.answer(CONTROL_ROOT + "commits/" + sha(5).value() + "/statuses?per_page=100", "[]");
        fake.answer(CONTROL_ROOT + "actions/runs?head_sha=" + sha(5).value() + "&per_page=100",
                    runsPage(List.of(workflowRun(80, "control", "pull_request", "control/pr", "completed", "failure", 1, 9, T0, T0, List.of(1L)))));
        await(poller.cycle());

        assertThat(kinds("CHECK_CONCLUDED")).filteredOn(row -> row.repo().equals(CONTROL))
                                            .singleElement()
                                            .satisfies(row -> {
                                                assertThat(row.subject()).isEqualTo("control");
                                                assertThat(row.after()).isEqualTo("failure");
                                                assertThat(json(row)).contains("\"event\":\"pull_request\"");
                                            });
        assertThat(kinds("CHECKS_SETTLED")).filteredOn(row -> row.repo().equals(CONTROL))
                                           .singleElement()
                                           .extracting(ChangeFeed.Row::after)
                                           .isEqualTo("red");
        assertThat(await(store.prs().all())).extracting(pr -> pr.number().value())
                                            .containsExactly(2L);
    }

    // helpers

    // verifier t1wp2v findings (adopted): lead rulings (a), (b) and design §4.2 G4

    /// The same PR fixture as `[github] repo` and as a `[[repos]]` repository, run in one kiln: (a).
    private void sameFixture(String root, Instant at, String conclusion, String list) {
        fake.answer(root + "commits/" + sha(2).value() + "/check-runs?filter=all&per_page=100",
                    checkRuns(suiteRun(20, "ci", "completed", conclusion, at, 7)));
        fake.answer(root + "commits/" + sha(2).value() + "/statuses?per_page=100", "[]");
        fake.answer(root + "actions/runs?head_sha=" + sha(2).value() + "&per_page=100",
                    runsPage(List.of(workflowRun(70, "CI", "pull_request", "feat", "completed", conclusion, 1, 7, T0, at))));
        fake.answer(root + "branches/main", list);
        fake.answer(root + "rules/branches/main", "[]");
        fake.answer(root + GitHubFeed.windowQuery("actions/runs?branch=main&event=push", T0.minus(Duration.ofDays(7))),
                    runsPage(List.of(workflowRun(500, "S3", "push", "main", "completed", "cancelled", 1, 50, T0.minusSeconds(600), T0))));
    }

    @Test
    void rulingA_theSameFixtureAsPrimaryAndAsSecondary_givesIdenticalRows_exceptRepo() {
        poller = feedPoller(fake, store, now, BranchHeads.NONE, List.of(KilnConfig.Repo.repo(CONTROL, List.of(MAIN))));
        feedQuiet(fake, CONTROL_ROOT);
        fake.pages(CONTROL_ROOT + OPEN_PULLS, List.of(array(pr(2, "open", "main", "feat", sha(2), T0))));
        sameFixture(ROOT, T0, "failure", requiring("ci"));
        sameFixture(CONTROL_ROOT, T0, "failure", requiring("ci"));
        await(poller.cycle());

        var two = "{\"name\":\"main\",\"protection\":{\"enabled\":true,\"required_status_checks\":{\"enforcement_level\":"
                  + "\"non_admins\",\"checks\":[{\"context\":\"ci\",\"app_id\":" + APP + "},{\"context\":\"lint\",\"app_id\":null}]}}}";
        later(60);
        sameFixture(ROOT, T0.plusSeconds(50), "success", two);
        sameFixture(CONTROL_ROOT, T0.plusSeconds(50), "success", two);
        await(poller.cycle());
        later(60 + 700);
        await(poller.cycle());

        var primary = rowsOf(REPO);

        assertThat(primary).as("primary rows").isNotEmpty();
        assertThat(rowsOf(CONTROL)).as("secondary rows equal primary rows except repo").isEqualTo(primary);
    }

    private List<List<Object>> rowsOf(String repo) {
        var feedKinds = List.of("CHECK_CONCLUDED", "CHECKS_SETTLED", "PUSH_RUN_CONCLUDED", "SCHEDULED_RUN_CONCLUDED",
                                "BOT_ISSUE_OPENED", "BOT_ISSUE_CLOSED", "RUN_CANCELLED", "REQUIRED_LIST_CHANGED");

        return await(store.changes().feed(0, 500)).changes()
                                                  .stream()
                                                  .filter(row -> row.repo().equals(repo) && feedKinds.contains(row.kind()))
                                                  .map(row -> List.<Object>of(row.kind(), row.pr().toString(), row.subject(), row.before(), row.after(),
                                                                              row.payload().or("")))
                                                  .toList();
    }

    /// Ruling (b): a rerun of a run deep in a 50-run backlog is listed by `status=in_progress` in cycle 1, stored
    /// non-terminal, and re-read by id in cycle 2: its conclusion is reported by cycle 2, not after the round robin.
    @Test
    void rulingB_aRerunDeepInTheBacklog_isConcludedWithinTwoCycles() {
        backlog(50);
        var going = runsPage(List.of(workflowRun(3050, "S3", "push", "main", "in_progress", null, 2, 50, T0.minus(Duration.ofDays(2)), T0.plusSeconds(30))));

        fake.answerPrefix(ROOT + "actions/runs?status=in_progress", going);
        fake.answer(ROOT + "actions/runs/3050",
                    workflowRun(3050, "S3", "push", "main", "in_progress", null, 2, 50, T0.minus(Duration.ofDays(2)), T0.plusSeconds(30)));
        later(60);
        await(poller.cycle());
        fake.answer(ROOT + "actions/runs/3050",
                    workflowRun(3050, "S3", "push", "main", "completed", "failure", 2, 50, T0.minus(Duration.ofDays(2)), T0.plusSeconds(90)));
        later(120);
        await(poller.cycle());

        assertThat(kinds("PUSH_RUN_CONCLUDED")).anySatisfy(row -> assertThat(json(row)).contains("\"runId\":3050", "\"attempt\":2"));
    }

    /// Ruling (b): a rerun of a run created more than 7 days ago (outside every window and the round robin) is listed
    /// non-terminal, then re-read to its conclusion.
    @Test
    void rulingB_aRerunOfARunOlderThanSevenDays_isConcluded() {
        var old = T0.minus(Duration.ofDays(30));

        fake.answerPrefix(ROOT + "actions/runs?status=queued",
                          runsPage(List.of(workflowRun(4000, "S3", "push", "main", "queued", null, 2, 40, old, T0))));
        fake.answer(ROOT + "actions/runs/4000", workflowRun(4000, "S3", "push", "main", "completed", "success", 2, 40, old, T0.plusSeconds(90)));
        later(60);
        await(poller.cycle());
        fake.answerPrefix(ROOT + "actions/runs?status=queued", runsPage(List.of()));
        later(120);
        await(poller.cycle());

        assertThat(kinds("PUSH_RUN_CONCLUDED")).singleElement()
                                               .satisfies(row -> assertThat(json(row)).contains("\"runId\":4000", "\"attempt\":2"));
    }

    /// G4: a suite qualifies only when its workflow run lists THIS PR in `pull_requests[]`. Another PR's failing
    /// `pull_request` suite at the same head (another workflow producing `ci`) does not make PR 2 red.
    @Test
    void g4_anotherPrsSuiteAtTheSameHead_isNotThisPrs() {
        fake.answer(CHECK_RUNS,
                    checkRuns(suiteRun(20, "ci", "completed", "failure", T0, 7), suiteRun(21, "ci", "completed", "success", T0.plusSeconds(30), 8)));
        fake.answer(HEAD_RUNS,
                    runsPage(List.of(workflowRun(70, "CI-other", "pull_request", "feat", "completed", "failure", 1, 7, T0, T0, List.of(99L)),
                                     workflowRun(71, "CI", "pull_request", "feat", "completed", "success", 1, 8, T0, T0, List.of(2L)))));
        await(poller.cycle());

        assertThat(kinds("CHECKS_SETTLED")).singleElement().extracting(ChangeFeed.Row::after).isEqualTo("green");
    }

    /// G4 / R2-b: a fork PR's suite has an empty `pull_requests[]`: no suite qualifies, so the head is never green.
    @Test
    void g4_aForkPrsSuite_withEmptyPullRequests_neverSettlesGreen() {
        fake.answer(CHECK_RUNS, checkRuns(suiteRun(21, "ci", "completed", "success", T0, 8)));
        fake.answer(HEAD_RUNS, runsPage(List.of(workflowRun(71, "CI", "pull_request", "feat", "completed", "success", 1, 8, T0, T0, List.of()))));
        await(poller.cycle());
        later(600);
        await(poller.cycle());

        assertThat(kinds("CHECKS_SETTLED")).extracting(ChangeFeed.Row::after)
                                           .containsExactly("missing");
    }

    private List<WorkflowRun> backlog(int count) {
        var runs = LongStream.rangeClosed(1, count)
                             .mapToObj(id -> new WorkflowRun(3000 + id, "S3", "completed", Option.some("success"), "x", "https://x", T0.minus(Duration.ofDays(2)),
                                                             Option.some("push"), Option.some("main"), 1, Option.some(id), Option.some(T0), Option.none(),
                                                             Option.none(), List.of()))
                             .toList();

        runs.forEach(run -> fake.answer(ROOT + "actions/runs/" + run.id(),
                                        workflowRun(run.id(), "S3", "push", "main", "completed", "success", 1, run.suiteId().unwrap(), run.createdAt(), T0)));
        await(store.feed().runs(REPO, runs, T0.minusSeconds(3600)));
        return runs;
    }

    private static List<String> rereads(List<String> paths) {
        return paths.stream()
                    .filter(path -> path.startsWith(ROOT + "actions/runs/"))
                    .toList();
    }

    /// The payload as stored (jsonb answers `"key": value`), without the space after each colon.
    private static String json(ChangeFeed.Row row) {
        return row.payload()
                  .or("")
                  .replace("\": ", "\":");
    }

    private static String window(String query, Instant from) {
        return ROOT + GitHubFeed.windowQuery(query, from);
    }

    private List<ChangeFeed.Row> kinds(String... kinds) {
        var wanted = List.of(kinds);

        return await(store.changes().feed(0, 500)).changes()
                                                  .stream()
                                                  .filter(row -> wanted.contains(row.kind()))
                                                  .toList();
    }

    private static PrNumber prNumber(long number) {
        return PrNumber.prNumber(number).unwrap();
    }

    private void later(long seconds) {
        now.set(T0.plusSeconds(seconds));
    }
}
