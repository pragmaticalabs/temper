package org.pragmatica.kiln.core.github;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.kiln.core.Fixtures.causes;
import static org.pragmatica.kiln.core.Fixtures.ok;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// spec §4.1 and §4.2: the per-open-PR detail, the head commit's date, the base and scheduled workflow runs and the bot
/// issues. Each reads only its fields; a missing required field fails the read and names it.
class BaseReadDecodeTest {
    private static final Instant AT = Instant.parse("2026-10-30T10:00:00Z");

    @Test
    void openDetail_readsMergeability_andIgnoresOtherFields() {
        var detail = ok(OpenDetail.decode("{\"number\":7,\"mergeable\":true,\"mergeable_state\":\"clean\",\"extra\":[1]}",
                                          some(AT)));

        assertThat(detail).isEqualTo(new OpenDetail(some(true), some("clean"), some(AT)));
    }

    @Test
    void openDetail_readsNone_whenGitHubHasNotComputedMergeability() {
        assertThat(ok(OpenDetail.decode("{\"mergeable\":null}", none()))).isEqualTo(new OpenDetail(none(), none(), none()));
    }

    @Test
    void openDetail_fails_whenMergeableIsMistyped() {
        assertThat(causes(OpenDetail.decode("{\"mergeable\":\"yes\"}", none()))).singleElement()
                                                                              .isEqualTo(GitHubError.BadField.FACTORY.apply("mergeable"));
    }

    @Test
    void committedAt_readsTheCommitterDate() {
        assertThat(ok(OpenDetail.committedAt("{\"sha\":\"x\",\"commit\":{\"author\":{\"date\":\"2020-01-01T00:00:00Z\"},"
                                             + "\"committer\":{\"date\":\"2026-10-30T10:00:00Z\"}}}"))).isEqualTo(AT);
    }

    @Test
    void committedAt_fails_whenTheDateIsMissing() {
        assertThat(causes(OpenDetail.committedAt("{\"commit\":{}}"))).singleElement()
                                                                     .isEqualTo(GitHubError.BadField.FACTORY.apply("commit.committer.date"));
    }

    @Test
    void workflowRuns_readEveryRun_inPageOrder() {
        var json = "{\"total_count\":2,\"workflow_runs\":["
                   + "{\"id\":2,\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"failure\",\"head_sha\":\"abc\","
                   + "\"html_url\":\"https://x/2\",\"created_at\":\"2026-10-30T10:00:00Z\",\"run_number\":9},"
                   + "{\"id\":1,\"name\":\"CI\",\"status\":\"in_progress\",\"conclusion\":null,\"head_sha\":\"def\","
                   + "\"html_url\":\"https://x/1\",\"created_at\":\"2026-10-30T09:00:00Z\"}]}";

        assertThat(ok(WorkflowRun.decode(json))).containsExactly(new WorkflowRun(2, "CI", "completed", some("failure"), "abc", "https://x/2", AT, none(), none(), 1,
                                                                                 none(), none(), none(), some(9L), List.of()),
                                                                 new WorkflowRun(1,
                                                                                 "CI",
                                                                                 "in_progress",
                                                                                 none(),
                                                                                 "def",
                                                                                 "https://x/1",
                                                                                 AT.minusSeconds(3600)));
    }

    /// R2-3 and G4: the workflow, the run number and the numbers of `pull_requests[]`; a bad PR entry fails the run.
    @Test
    void workflowRun_readsItsWorkflow_runNumber_andListedPullRequests() {
        var json = "{\"id\":3,\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"success\",\"head_sha\":\"abc\","
                   + "\"html_url\":\"https://x/3\",\"created_at\":\"2026-10-30T10:00:00Z\",\"workflow_id\":161,\"run_number\":12,"
                   + "\"pull_requests\":[{\"number\":7,\"head\":{}},{\"number\":8}]}";
        var run = ok(WorkflowRun.decodeOne(json));

        assertThat(run.workflowId()).isEqualTo(some(161L));
        assertThat(run.runNumber()).isEqualTo(some(12L));
        assertThat(run.pullRequests()).containsExactly(7L, 8L);
        assertThat(run.lists(8)).isTrue();
        assertThat(run.lists(9)).isFalse();
        assertThat(causes(WorkflowRun.decodeOne(json.replace("{\"number\":8}", "{\"id\":8}")))).singleElement()
                                                                                       .isEqualTo(GitHubError.BadField.FACTORY.apply("number"));
    }

    @Test
    void workflowRuns_fail_whenTheListIsMissing() {
        assertThat(causes(WorkflowRun.decode("{\"total_count\":0}"))).singleElement()
                                                                     .isEqualTo(GitHubError.BadField.FACTORY.apply("workflow_runs"));
    }

    @Test
    void botIssues_leaveOutPullRequests() {
        var json = "[{\"number\":5,\"title\":\"nightly failed\",\"html_url\":\"https://x/5\",\"created_at\":\"2026-10-30T10:00:00Z\"},"
                   + "{\"number\":6,\"title\":\"a PR\",\"html_url\":\"https://x/6\",\"created_at\":\"2026-10-30T10:00:00Z\","
                   + "\"pull_request\":{\"url\":\"u\"}}]";

        assertThat(ok(BotIssue.decode(json))).containsExactly(new BotIssue(5, "nightly failed", "https://x/5", AT));
    }

    @Test
    void botIssues_fail_whenAnIssueMissesItsTitle() {
        assertThat(causes(BotIssue.decode("[{\"number\":5,\"html_url\":\"u\",\"created_at\":\"2026-10-30T10:00:00Z\"}]"))).singleElement()
                                                                                                                         .isEqualTo(GitHubError.BadField.FACTORY.apply("title"));
    }
}
