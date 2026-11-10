package org.pragmatica.kiln.core.feed;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.CheckRun;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.AppId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.feed.FeedFixtures.HEAD;
import static org.pragmatica.kiln.core.feed.FeedFixtures.PR;
import static org.pragmatica.kiln.core.feed.FeedFixtures.T0;
import static org.pragmatica.kiln.core.feed.FeedFixtures.checkRun;
import static org.pragmatica.kiln.core.feed.FeedFixtures.listing;
import static org.pragmatica.kiln.core.feed.FeedFixtures.numbered;
import static org.pragmatica.kiln.core.feed.FeedFixtures.pinned;
import static org.pragmatica.kiln.core.feed.FeedFixtures.requiring;
import static org.pragmatica.kiln.core.feed.FeedFixtures.run;
import static org.pragmatica.kiln.core.feed.FeedFixtures.status;
import static org.pragmatica.kiln.core.feed.FeedFixtures.unpinned;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class CheckFeedTest {
    private static final CheckFeed.Observation PENDING = observed("in_progress", null, 1).getFirst();

    private static List<CheckFeed.Observation> observed(String status, String conclusion, long attempt) {
        return CheckFeed.observed(requiring(pinned("ci")), PR,
                                  List.of(checkRun(20, "ci", status, conclusion, T0, 7)),
                                  List.of(),
                                  List.of(run(70, "pull_request", status, conclusion, attempt, 7, T0, T0)));
    }

    @Test
    void concluded_reportsAFirstTerminalConclusion_once() {
        var failed = observed("completed", "failure", 1);
        var first = CheckFeed.concluded(PR, HEAD, Map.of(), failed, 3, T0);

        assertThat(first).singleElement()
                         .satisfies(change -> {
                             assertThat(change.kind()).isEqualTo(FeedKind.CHECK_CONCLUDED);
                             assertThat(change.subject()).isEqualTo("ci");
                             assertThat(change.before()).isEqualTo(FeedChange.NONE);
                             assertThat(change.after()).isEqualTo("failure");
                             assertThat(change.payload()).containsEntry("check", "ci@15368")
                                                         .containsEntry("runId", 70L)
                                                         .containsEntry("suiteId", 7L)
                                                         .containsEntry("event", "pull_request")
                                                         .containsEntry("attempt", 1L)
                                                         .containsEntry("listVersion", 3L)
                                                         .containsEntry("source", "check-run");
                         });
        assertThat(CheckFeed.concluded(PR, HEAD, CheckFeed.fingerprints(failed), failed, 3, T0)).isEmpty();
    }

    @Test
    void concluded_reportsNothingWhilePending_andRemembersNothing() {
        assertThat(PENDING.terminal()).isFalse();
        assertThat(CheckFeed.concluded(PR, HEAD, Map.of(), List.of(PENDING), 1, T0)).isEmpty();
        assertThat(CheckFeed.fingerprints(List.of(PENDING))).isEmpty();
    }

    @Test
    void concluded_reportsARerun_byItsAttempt_withTheOldConclusionBefore() {
        var failed = CheckFeed.fingerprints(observed("completed", "failure", 1));
        var rerun = CheckFeed.concluded(PR, HEAD, failed, observed("completed", "success", 2), 1, T0);

        assertThat(rerun).singleElement()
                         .satisfies(change -> {
                             assertThat(change.before()).isEqualTo("failure");
                             assertThat(change.after()).isEqualTo("success");
                             assertThat(change.payload()).containsEntry("attempt", 2L);
                         });
    }

    @Test
    void observed_keepsEachSuiteApart_andAStatusSkippedIsNeverSuccess() {
        var observed = CheckFeed.observed(requiring(unpinned("ci")), PR,
                                          List.of(checkRun(20, "ci", "completed", "failure", T0, 7),
                                                  checkRun(21, "ci", "completed", "success", T0.plusSeconds(9), 8),
                                                  checkRun(21, "ci", "completed", "success", T0.plusSeconds(9), 8)),
                                          List.of(status(1, "ci", "pending", null), status(2, "ci", "success", "skipped")),
                                          List.of(run(70, "pull_request", "completed", "failure", 1, 7, T0, T0),
                                                  run(71, "push", "completed", "success", 1, 8, T0, T0)));

        assertThat(observed).extracting(CheckFeed.Observation::suite, CheckFeed.Observation::qualifying)
                            .containsExactly(org.assertj.core.groups.Tuple.tuple("suite:7", true),
                                             org.assertj.core.groups.Tuple.tuple("suite:8", false),
                                             org.assertj.core.groups.Tuple.tuple("status", true));
        assertThat(observed.getLast().conclusion()).isEqualTo(some("success*"));
    }

    @Test
    void settled_concluded_isGreenOnlyWhenEveryQualifyingObservationIsSuccess() {
        var green = observed("completed", "success", 1);
        var red = observed("completed", "failure", 1);

        assertThat(CheckFeed.settled(requiring(pinned("ci")), green, List.of(), T0, T0).map(CheckFeed.Settled::outcome))
                                                                                        .isEqualTo(some(CheckFeed.GREEN));
        assertThat(CheckFeed.settled(requiring(pinned("ci")), red, List.of(), T0, T0).map(CheckFeed.Settled::outcome))
                                                                                      .isEqualTo(some(CheckFeed.RED));
        assertThat(CheckFeed.settled(requiring(pinned("ci")), List.of(PENDING), List.of(run(70, "pull_request", "in_progress", null, 1, 7, T0, T0)), T0, T0.plus(Duration.ofHours(1))))
            .isEqualTo(none());
    }

    /// Positive control (design §2.5, NOTE 8): a required check whose path-filtered workflow never runs reads `missing`
    /// only once every run at the head has completed and the 10-minute window has passed, on a fixture clock.
    @Test
    void settled_aPathFilteredNeverRunWorkflow_isMissing_onlyAfterTenMinutes() {
        var list = requiring(pinned("ci"), pinned("never-runs"));
        var observed = CheckFeed.observed(list, PR,
                                          List.of(checkRun(20, "ci", "completed", "success", T0, 7)),
                                          List.of(),
                                          List.of(run(70, "pull_request", "completed", "success", 1, 7, T0, T0.plusSeconds(100))));
        var runs = List.of(run(70, "pull_request", "completed", "success", 1, 7, T0, T0.plusSeconds(100)));
        var lastCompletion = T0.plusSeconds(100);

        assertThat(CheckFeed.settled(list, observed, runs, T0, lastCompletion.plus(Duration.ofMinutes(10)).minusSeconds(1))).isEqualTo(none());
        assertThat(CheckFeed.settled(list, observed, runs, T0, lastCompletion.plus(Duration.ofMinutes(10))).stream().toList()).singleElement()
                                                                                                             .satisfies(settled -> {
                                                                                                                 assertThat(settled.outcome()).isEqualTo(CheckFeed.MISSING);
                                                                                                                 assertThat(settled.rule()).isEqualTo(CheckFeed.WINDOW);
                                                                                                                 assertThat(settled.missing()).containsExactly("never-runs@15368");
                                                                                                             });
    }

    @Test
    void settled_aRunStillGoing_holdsTheWindowOpen_andAHeadWithoutRunsCountsFromFirstSeen() {
        var list = requiring(pinned("ci"));
        var going = List.of(run(70, "pull_request", "in_progress", null, 1, 7, T0, T0));

        assertThat(CheckFeed.settled(list, List.of(), going, T0, T0.plus(Duration.ofHours(5)))).isEqualTo(none());
        assertThat(CheckFeed.settled(list, List.of(), List.of(), T0, T0.plus(Duration.ofMinutes(9)))).isEqualTo(none());
        assertThat(CheckFeed.settled(list, List.of(), List.of(), T0, T0.plus(Duration.ofMinutes(10))).map(CheckFeed.Settled::outcome))
                                                                                                    .isEqualTo(some(CheckFeed.MISSING));
    }

    @Test
    void settled_windowWithARedCheck_isRed() {
        var list = requiring(pinned("ci"), pinned("never-runs"));
        var observed = observed("completed", "failure", 1);
        var runs = List.of(run(70, "pull_request", "completed", "failure", 1, 7, T0, T0));

        assertThat(CheckFeed.settled(list, observed, runs, T0, T0.plus(Duration.ofMinutes(10))).map(CheckFeed.Settled::outcome))
                                                                                               .isEqualTo(some(CheckFeed.RED));
    }

    @Test
    void settledChange_reportsOnlyAnOutcomeOtherThanTheLastReported() {
        var red = some(new CheckFeed.Settled(CheckFeed.RED, CheckFeed.CONCLUDED, List.of(), List.of("ci")));

        assertThat(CheckFeed.settledChange(PR, HEAD, none(), red, T0).stream().toList()).singleElement()
                                                                     .satisfies(change -> {
                                                                         assertThat(change.kind()).isEqualTo(FeedKind.CHECKS_SETTLED);
                                                                         assertThat(change.subject()).isEqualTo(HEAD.value());
                                                                         assertThat(change.after()).isEqualTo(CheckFeed.RED);
                                                                         assertThat(change.payload()).containsEntry("settleWindowS", 600L);
                                                                     });
        assertThat(CheckFeed.settledChange(PR, HEAD, some(CheckFeed.RED), red, T0)).isEqualTo(none());
        assertThat(CheckFeed.settledChange(PR, HEAD, some(CheckFeed.MISSING), red, T0).map(FeedChange::before))
                                                                                      .isEqualTo(some(CheckFeed.MISSING));
        assertThat(CheckFeed.settledChange(PR, HEAD, none(), none(), T0)).isEqualTo(none());
    }

    /// PIT: each check keeps its own fingerprint; another app's run and another context's status never stand in.
    @Test
    void observed_keepsChecksApart_andIgnoresOtherAppsAndContexts() {
        var list = requiring(pinned("ci"), pinned("lint"));
        var foreign = new CheckRun(30, "ci", some(AppId.appId(1).unwrap()), "completed", some("success"), some(T0), some(9L));
        var observed = CheckFeed.observed(list, PR,
                                          List.of(checkRun(20, "ci", "completed", "failure", T0, 7),
                                                  checkRun(21, "lint", "completed", "failure", T0, 7),
                                                  foreign),
                                          List.of(),
                                          List.of(run(70, "pull_request", "completed", "failure", 1, 7, T0, T0)));

        assertThat(observed).hasSize(2);
        assertThat(CheckFeed.fingerprints(observed)).hasSize(2);
        assertThat(CheckFeed.concluded(PR, HEAD, Map.of(observed.getFirst().key(), observed.getFirst().fingerprint()), observed, 1, T0))
            .extracting(FeedChange::subject)
            .containsExactly("lint");
    }

    @Test
    void observed_aRunWithoutASuite_standsAlone_andTheLaterAttemptOfASuiteWins() {
        var noSuite = new CheckRun(40, "ci", some(AppId.appId(FeedFixtures.APP).unwrap()), "completed", some("success"), some(T0));
        var observed = CheckFeed.observed(requiring(pinned("ci")), PR,
                                          List.of(noSuite, checkRun(20, "ci", "completed", "success", T0, 7)),
                                          List.of(),
                                          List.of(run(72, "pull_request", "completed", "success", 2, 7, T0, T0),
                                                  run(71, "pull_request", "completed", "failure", 1, 7, T0, T0),
                                                  run(73, "pull_request", "completed", "failure", 2, 7, T0, T0)));

        assertThat(observed).extracting(CheckFeed.Observation::suite)
                            .containsExactly("run:40", "suite:7");
        assertThat(observed.getLast().attempt()).isEqualTo(some(2L));
        assertThat(observed.getLast().run().map(WorkflowRun::id)).isEqualTo(some(73L));
    }

    @Test
    void observed_aStatusOfAnotherContext_isNotUsed_andOnlyASkippedSuccessIsStarred() {
        var plain = CheckFeed.observed(requiring(unpinned("ci")), PR,
                                       List.of(),
                                       List.of(status(1, "other", "failure", null), status(2, "ci", "success", "all good")),
                                       List.of());
        var failedSkip = CheckFeed.observed(requiring(unpinned("ci")), PR,
                                            List.of(),
                                            List.of(status(3, "ci", "failure", "skipped")),
                                            List.of());

        assertThat(plain).singleElement()
                         .satisfies(observation -> assertThat(observation.conclusion()).isEqualTo(some("success")));
        assertThat(failedSkip.getFirst().conclusion()).isEqualTo(some("failure"));
    }

    // R2-3 (verifier t1wp2v findings, adopted): per distinct workflow only the latest run by run number, latest attempt

    /// The `no-changelog` waiver / a label re-trigger: the older suite of the same workflow failed, the newer passed.
    @Test
    void r23_aNewerPassingSuiteOfTheSameWorkflow_supersedesTheOlderFailedOne() {
        var list = requiring(pinned("ci"));
        var runs = List.of(run(70, "pull_request", "completed", "failure", 1, 7, T0, T0),
                           run(71, "pull_request", "completed", "success", 1, 8, T0.plusSeconds(60), T0.plusSeconds(120)));
        var observed = CheckFeed.observed(list, PR,
                                          List.of(checkRun(20, "ci", "completed", "failure", T0, 7),
                                                  checkRun(21, "ci", "completed", "success", T0.plusSeconds(60), 8)),
                                          List.of(),
                                          runs);

        assertThat(CheckFeed.settled(list, observed, runs, T0, T0.plusSeconds(130)).map(CheckFeed.Settled::outcome))
            .isEqualTo(some(CheckFeed.GREEN));
    }

    /// A suite cancelled by cancel-in-progress, then a later success of the same workflow.
    @Test
    void r23_aCancelledOlderSuite_plusALaterSuccess_isGreen() {
        var list = requiring(pinned("ci"));
        var runs = List.of(run(70, "pull_request", "completed", "cancelled", 1, 7, T0, T0),
                           run(71, "pull_request", "completed", "success", 1, 8, T0.plusSeconds(60), T0.plusSeconds(120)));
        var observed = CheckFeed.observed(list, PR,
                                          List.of(checkRun(20, "ci", "completed", "cancelled", T0, 7),
                                                  checkRun(21, "ci", "completed", "success", T0.plusSeconds(60), 8)),
                                          List.of(),
                                          runs);

        assertThat(CheckFeed.settled(list, observed, runs, T0, T0.plusSeconds(130)).map(CheckFeed.Settled::outcome))
            .isEqualTo(some(CheckFeed.GREEN));
    }

    /// A job named `ci` in another workflow (another `workflow_id`) that fails is its own workflow's latest run: red.
    @Test
    void r23_aCollidingJobOfAnotherWorkflowFailing_isRed() {
        var list = requiring(pinned("ci"));
        var runs = List.of(numbered(70, 100, 5, 1, "success", 7), numbered(71, 200, 1, 1, "failure", 8));

        assertThat(settledOf(list, runs, checkRun(20, "ci", "completed", "success", T0, 7), checkRun(21, "ci", "completed", "failure", T0, 8)))
            .isEqualTo(some(CheckFeed.RED));
    }

    /// The run number orders a workflow's runs, not the run id; within one run number, the later attempt wins.
    @Test
    void r23_theLatestRunNumber_thenTheLatestAttempt_standsForTheWorkflow() {
        var list = requiring(pinned("ci"));
        var byNumber = List.of(numbered(80, 100, 6, 1, "success", 7), numbered(90, 100, 5, 1, "failure", 8));
        var byAttempt = List.of(numbered(70, 100, 5, 2, "success", 7), numbered(70, 100, 5, 1, "failure", 8));
        var olderAttemptLater = List.of(numbered(70, 100, 5, 1, "success", 7), numbered(70, 100, 5, 2, "failure", 8));

        assertThat(settledOf(list, byNumber, checkRun(20, "ci", "completed", "success", T0, 7), checkRun(21, "ci", "completed", "failure", T0, 8)))
            .isEqualTo(some(CheckFeed.GREEN));
        assertThat(settledOf(list, byAttempt, checkRun(20, "ci", "completed", "success", T0, 7), checkRun(21, "ci", "completed", "failure", T0, 8)))
            .isEqualTo(some(CheckFeed.GREEN));
        assertThat(settledOf(list, olderAttemptLater, checkRun(20, "ci", "completed", "success", T0, 7), checkRun(21, "ci", "completed", "failure", T0, 8)))
            .isEqualTo(some(CheckFeed.RED));
    }

    /// Without a `workflow_id`, the run's name tells workflows apart: an older failing run of another workflow stays red.
    @Test
    void r23_withoutAWorkflowId_theRunNameTellsWorkflowsApart() {
        var list = requiring(pinned("ci"));
        var other = new WorkflowRun(70, "CI-other", "completed", some("failure"), "x", "https://x/70", T0, some("pull_request"), some("feat"),
                                    1, some(7L), some(T0), none(), none(), List.of(PR.value()));
        var runs = List.of(other, listing(71, "success", 8, List.of(PR.value())));

        assertThat(settledOf(list, runs, checkRun(20, "ci", "completed", "failure", T0, 7), checkRun(21, "ci", "completed", "success", T0, 8)))
            .isEqualTo(some(CheckFeed.RED));
    }

    // G4: a suite qualifies only when its run is a pull_request run that lists this PR

    /// A fork PR's run lists no PR (R2-b): nothing qualifies, so the head is `missing` after the window, never green.
    @Test
    void g4_aForkPrsRun_withNoPullRequestsListed_isMissing_neverGreen() {
        var list = requiring(pinned("ci"));
        var runs = List.of(listing(71, "success", 8, List.of()));

        assertThat(settledOf(list, runs, checkRun(21, "ci", "completed", "success", T0, 8))).isEqualTo(none());
        assertThat(CheckFeed.settled(list,
                                     CheckFeed.observed(list, PR, List.of(checkRun(21, "ci", "completed", "success", T0, 8)), List.of(), runs),
                                     runs,
                                     T0,
                                     T0.plus(Duration.ofMinutes(10))).map(CheckFeed.Settled::outcome))
            .isEqualTo(some(CheckFeed.MISSING));
    }

    /// Another PR's failing `pull_request` suite at the same head is not this PR's.
    @Test
    void g4_anotherPrsSuiteAtTheSameHead_isNotThisPrs() {
        var list = requiring(pinned("ci"));
        var runs = List.of(listing(70, "failure", 7, List.of(99L)), listing(71, "success", 8, List.of(PR.value())));

        assertThat(settledOf(list, runs, checkRun(20, "ci", "completed", "failure", T0, 7), checkRun(21, "ci", "completed", "success", T0, 8)))
            .isEqualTo(some(CheckFeed.GREEN));
    }

    // lead rulings on the settle rule

    /// A still-pending required context blocks settling, also after the window, and also as a commit status (N-1):
    /// never `missing`.
    @Test
    void settled_aStillPendingRequiredContext_blocksSettling_neverMissing() {
        var list = requiring(pinned("ci"), pinned("never-runs"));
        var runs = List.of(run(70, "pull_request", "completed", "success", 1, 7, T0, T0));
        var pending = CheckFeed.observed(list, PR, List.of(checkRun(20, "ci", "in_progress", null, T0, 7)), List.of(), runs);
        var statusList = requiring(unpinned("external"));
        var pendingStatus = CheckFeed.observed(statusList, PR, List.of(), List.of(status(1, "external", "pending", null)), List.of());

        assertThat(CheckFeed.settled(list, pending, runs, T0, T0.plus(Duration.ofHours(1)))).isEqualTo(none());
        assertThat(CheckFeed.settled(statusList, pendingStatus, List.of(), T0, T0.plus(Duration.ofHours(1)))).isEqualTo(none());
    }

    /// Lead ruling 2026-11-04: within a suite, the run with the greatest check-run id stands for it, never the
    /// latest `started_at`: a higher-id failure that started earlier is red, and a lower-id run that never started is
    /// superseded by a higher-id success.
    @Test
    void settled_greatestCheckRunIdInASuiteStandsForIt_notTheLatestStart_ruling1() {
        var list = requiring(pinned("ci"));
        var runs = List.of(run(70, "pull_request", "completed", "success", 1, 7, T0, T0));
        var queued = new CheckRun(20, "ci", checkRun(20, "ci", "x", null, T0, 7).app(), "queued", none(), none(), some(7L));

        assertThat(settledOf(list, runs, checkRun(20, "ci", "completed", "success", T0.plusSeconds(60), 7),
                             checkRun(21, "ci", "completed", "failure", T0, 7))).isEqualTo(some(CheckFeed.RED));
        assertThat(settledOf(list, runs, queued, checkRun(21, "ci", "completed", "success", T0.plusSeconds(60), 7)))
            .isEqualTo(some(CheckFeed.GREEN));
    }

    private static Option<String> settledOf(RequiredChecks.Required list, List<WorkflowRun> runs, CheckRun... checkRuns) {
        var observed = CheckFeed.observed(list, PR, List.of(checkRuns), List.of(), runs);

        return CheckFeed.settled(list, observed, runs, T0, T0).map(CheckFeed.Settled::outcome);
    }
}
