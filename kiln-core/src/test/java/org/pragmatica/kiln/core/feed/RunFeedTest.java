package org.pragmatica.kiln.core.feed;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.feed.FeedFixtures.T0;
import static org.pragmatica.kiln.core.feed.FeedFixtures.pinned;
import static org.pragmatica.kiln.core.feed.FeedFixtures.requiring;
import static org.pragmatica.kiln.core.feed.FeedFixtures.run;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The base kinds: runs (F11, R2-11), bot issues (F11) and required lists (NOTE 9).
class RunFeedTest {
    private static final WorkflowRun RED = run(5, "push", "completed", "failure", 1, 9, T0, T0);

    @Test
    void changes_aFirstSightOfACompletedRun_isConcluded_andARunStillGoingIsNothing() {
        assertThat(RunFeed.changes(none(), RED, T0)).singleElement()
                                                    .satisfies(change -> {
                                                        assertThat(change.kind()).isEqualTo(FeedKind.PUSH_RUN_CONCLUDED);
                                                        assertThat(change.subject()).isEqualTo("feat");
                                                        assertThat(change.pr()).isEqualTo(none());
                                                        assertThat(change.after()).isEqualTo("failure");
                                                        assertThat(change.payload()).containsEntry("runId", 5L)
                                                                                    .containsEntry("attempt", 1L)
                                                                                    .doesNotContainKey("previousAttempt");
                                                    });
        assertThat(RunFeed.changes(none(), run(5, "push", "in_progress", null, 1, 9, T0, T0), T0)).isEmpty();
    }

    @Test
    void changes_theSameRunAgain_isNothing_andARerunIsReportedWithItsAttempt() {
        var stored = some(new RunFeed.Stored("completed", some("failure"), 1));

        assertThat(RunFeed.changes(stored, RED, T0)).isEmpty();
        assertThat(RunFeed.changes(stored, run(5, "push", "completed", "success", 2, 9, T0, T0), T0)).singleElement()
                                                                                                       .satisfies(change -> {
                                                                                                           assertThat(change.before()).isEqualTo("failure");
                                                                                                           assertThat(change.payload()).containsEntry("attempt", 2L)
                                                                                                                                       .containsEntry("previousAttempt", 1L);
                                                                                                       });
        assertThat(RunFeed.changes(stored, run(5, "push", "completed", "failure", 2, 9, T0, T0), T0)).hasSize(1);
        assertThat(RunFeed.changes(some(new RunFeed.Stored("in_progress", none(), 1)), RED, T0)).hasSize(1);
    }

    @Test
    void changes_aScheduledRunIsScheduled_andACancelledRunIsAlsoRunCancelled() {
        var nightly = run(6, "schedule", "completed", "cancelled", 1, 9, T0, T0);

        assertThat(RunFeed.changes(none(), nightly, T0)).extracting(FeedChange::kind, FeedChange::subject)
                                                        .containsExactly(org.assertj.core.groups.Tuple.tuple(FeedKind.SCHEDULED_RUN_CONCLUDED, "CI"),
                                                                         org.assertj.core.groups.Tuple.tuple(FeedKind.RUN_CANCELLED, "CI"));
    }

    @Test
    void windowStart_overlapsTenMinutes_orReachesSevenDaysBack() {
        assertThat(RunFeed.windowStart(some(T0), T0.plusSeconds(3600))).isEqualTo(T0.minus(Duration.ofMinutes(10)));
        assertThat(RunFeed.windowStart(none(), T0)).isEqualTo(T0.minus(Duration.ofDays(7)));
    }

    @Test
    void reread_takesAtMostTheCap_inOrder_andNoneBelowZero() {
        assertThat(RunFeed.reread(List.of(3L, 1L, 2L), 2)).containsExactly(3L, 1L);
        assertThat(RunFeed.reread(List.of(3L), 5)).containsExactly(3L);
        assertThat(RunFeed.reread(List.of(3L), -1)).isEmpty();
    }

    @Test
    void issues_openedAndClosedWhileAway_isBoth_aReopenIsOpened_andNoChangeIsNothing() {
        var closed = new BotIssue(9, "forge", "u", T0, some("closed"), some(T0));
        var open = new BotIssue(9, "forge", "u", T0, some("open"), some(T0));

        assertThat(IssueFeed.changes(none(), closed, T0)).extracting(FeedChange::kind)
                                                         .containsExactly(FeedKind.BOT_ISSUE_OPENED, FeedKind.BOT_ISSUE_CLOSED);
        assertThat(IssueFeed.changes(some("closed"), open, T0)).extracting(FeedChange::kind)
                                                               .containsExactly(FeedKind.BOT_ISSUE_OPENED);
        assertThat(IssueFeed.changes(some("open"), closed, T0)).extracting(FeedChange::kind, FeedChange::before)
                                                               .containsExactly(org.assertj.core.groups.Tuple.tuple(FeedKind.BOT_ISSUE_CLOSED, "open"));
        assertThat(IssueFeed.changes(some("open"), open, T0)).isEmpty();
        assertThat(IssueFeed.changes(some("closed"), closed, T0)).isEmpty();
        assertThat(IssueFeed.since(none(), T0)).isEqualTo(T0.minus(Duration.ofDays(7)));
        assertThat(IssueFeed.since(some(T0), T0.plusSeconds(1))).isEqualTo(T0);
    }

    @Test
    void lists_contextsAreSortedLabels_andAChangeNeedsAnEarlierList() {
        var main = BranchName.branchName("main").unwrap();

        assertThat(ListFeed.contexts(requiring(pinned("z"), pinned("a")))).isEqualTo(some(List.of("a@15368", "z@15368")));
        assertThat(ListFeed.contexts(new RequiredChecks.NoneRequired("off", false))).isEqualTo(some(List.of()));
        assertThat(ListFeed.contexts(new RequiredChecks.Unknown("502"))).isEqualTo(none());
        assertThat(ListFeed.changed(main, none(), List.of("a"), 1, T0)).isEqualTo(none());
        assertThat(ListFeed.changed(main, some(List.of("a")), List.of("a"), 1, T0)).isEqualTo(none());
        assertThat(ListFeed.changed(main, some(List.of("a")), List.of("a", "b"), 2, T0).stream().toList()).singleElement()
                                                                                       .satisfies(change -> {
                                                                                           assertThat(change.kind()).isEqualTo(FeedKind.REQUIRED_LIST_CHANGED);
                                                                                           assertThat(change.before()).isEqualTo("a");
                                                                                           assertThat(change.after()).isEqualTo("a,b");
                                                                                           assertThat(change.payload()).containsEntry("version", 2L);
                                                                                       });
    }

    @Test
    void decode_readsTheFeedFields_andAnOlderAnswerStillDecodes() {
        var full = "{\"workflow_runs\":[{\"id\":1,\"name\":\"S3\",\"status\":\"completed\",\"conclusion\":\"failure\",\"head_sha\":\"x\","
                   + "\"html_url\":\"u\",\"created_at\":\"2026-11-03T10:00:00Z\",\"event\":\"push\",\"head_branch\":\"main\","
                   + "\"run_attempt\":2,\"check_suite_id\":44,\"updated_at\":\"2026-11-03T10:05:00Z\"}]}";
        var older = "{\"workflow_runs\":[{\"id\":1,\"name\":\"S3\",\"status\":\"queued\",\"conclusion\":null,\"head_sha\":\"x\","
                    + "\"html_url\":\"u\",\"created_at\":\"2026-11-03T10:00:00Z\"}]}";

        assertThat(WorkflowRun.decode(full).unwrap()).singleElement()
                                                     .satisfies(decoded -> {
                                                         assertThat(decoded.attempt()).isEqualTo(2);
                                                         assertThat(decoded.event()).isEqualTo(some("push"));
                                                         assertThat(decoded.headBranch()).isEqualTo(some("main"));
                                                         assertThat(decoded.suiteId()).isEqualTo(some(44L));
                                                         assertThat(decoded.updatedAt()).isEqualTo(some(T0.plusSeconds(300)));
                                                         assertThat(decoded.completed()).isTrue();
                                                     });
        assertThat(WorkflowRun.decode(older).unwrap()).singleElement()
                                                      .satisfies(decoded -> {
                                                          assertThat(decoded.attempt()).isEqualTo(1);
                                                          assertThat(decoded.event()).isEqualTo(none());
                                                          assertThat(decoded.completed()).isFalse();
                                                      });
        assertThat(WorkflowRun.decodeOne(full.substring(full.indexOf('[') + 1, full.lastIndexOf(']'))).unwrap()
                              .id()).isEqualTo(1);
        assertThat(BotIssue.decode("[{\"number\":3,\"title\":\"t\",\"html_url\":\"u\",\"created_at\":\"2026-11-03T10:00:00Z\","
                                   + "\"state\":\"closed\",\"updated_at\":\"2026-11-03T10:00:00Z\"}]")
                           .unwrap()).singleElement()
                                     .satisfies(issue -> assertThat(issue.closed()).isTrue());
        assertThat(org.pragmatica.kiln.core.github.CheckRun.decode("{\"check_runs\":[{\"id\":1,\"name\":\"ci\",\"status\":\"queued\","
                                                                   + "\"check_suite\":{\"id\":77}}]}")
                                                           .unwrap()).singleElement()
                                                                     .satisfies(checkRun -> assertThat(checkRun.suiteId()).isEqualTo(some(77L)));
    }
}
