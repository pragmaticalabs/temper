package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.feed.FeedChange;
import org.pragmatica.kiln.core.github.BotIssue;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// The state the GitHub-derived change kinds are diffed against (CI interface T1 WP2, kiln V4), per repository. Each
/// write reads the stored state, applies the kind's rule (`org.pragmatica.kiln.core.feed`), replaces the state and
/// inserts the changes in one transaction, so a change commits or rolls back with the state it was judged against, and
/// answers the changes it recorded. Single writer: the GitHub poller's single-flight cycle is the only caller of a write.
public interface FeedStore {
    /// `CHECK_CONCLUDED` and `CHECKS_SETTLED` for one open PR's head.
    Promise<List<FeedChange>> checks(String repo, HeadChecks checks, Instant at);
    /// `PUSH_RUN_CONCLUDED`, `SCHEDULED_RUN_CONCLUDED` and `RUN_CANCELLED` for runs just read, each stored as read and
    /// refreshed at `at`.
    Promise<List<FeedChange>> runs(String repo, List<WorkflowRun> runs, Instant at);
    /// The newest `created_at` of a stored run of `event` (on `branch`, when one is given).
    Promise<Option<Instant>> newestCreated(String repo, String event, Option<String> branch);
    /// The runs to re-read by id (R2-11), at most `max`: every stored run not completed, whenever refreshed (lead ruling
    /// b: a non-terminal run is re-read every cycle, even one a listing refreshed this cycle), and every run created at
    /// or after `recent` refreshed before `before`; not-completed first, then oldest refreshed first, then by id.
    Promise<List<Long>> rereadable(String repo, Instant recent, Instant before, int max);
    /// Forgets a run GitHub no longer has (a re-read answered 404), so it is not asked again.
    Promise<Unit> forgotten(String repo, long id);
    /// `BOT_ISSUE_OPENED` and `BOT_ISSUE_CLOSED` for issues just read.
    Promise<List<FeedChange>> issues(String repo, List<BotIssue> issues, Instant at);
    /// The newest `updated_at` of a stored issue.
    Promise<Option<Instant>> newestIssue(String repo);

    /// A base's required contexts as read now, with the ETag of that read (G10): the history's version moves when they
    /// differ (`REQUIRED_LIST_CHANGED`); the version's `etag` is the one of the latest read that saw it.
    Promise<List<FeedChange>> requiredList(String repo,
                                           BranchName base,
                                           List<String> contexts,
                                           Option<String> etag,
                                           Instant at);

    /// The stored tip of `base` (`base_tip`): none before its first read. Also the merge gate's one
    /// store read.
    Promise<Option<BaseTip>> baseTip(String repo, BranchName base);
    /// Stores `tip` as `base`'s and inserts `moved` (its `BASE_MOVED`; none on a first read) in one transaction, under
    /// the change lock, and answers the changes it recorded. Single writer: the poller's cycle.
    Promise<List<FeedChange>> baseTip(String repo, BaseTip tip, List<FeedChange> moved);
}
