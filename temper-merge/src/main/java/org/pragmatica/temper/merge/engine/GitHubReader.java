package org.pragmatica.temper.merge.engine;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;


/// What the runner reads from GitHub. Every read is idempotent; a failed read is an observation, never a decision: the
/// job stays where it is and the failure becomes its `lastObservation`, so a phase that only ever fails to read ends
/// as `TIMEOUT` naming the failure.
public interface GitHubReader {
    /// The pull request: `state` is GitHub's `OPEN`, `CLOSED` or `MERGED`; `base` the base branch name.
    record PullView(String head, boolean draft, String state, String base) {}

    /// One check run at a commit; `conclusion` is empty while it is not completed.
    record CheckRun(String name, String status, String conclusion, Instant startedAt) {}

    /// Where the pull request stands in the merge queue.
    sealed interface QueueView {
        record Queued(int position) implements QueueView {}

        /// Merged: the merge commit and the issues the merge closed (GitHub's closing references).
        record Merged(String mergeSha, List<String> closedIssues) implements QueueView {}

        /// Removed from the queue (GitHub's `RemovedFromMergeQueueEvent.reason`), not merged.
        record Removed(String reason) implements QueueView {}

        /// Neither queued, merged nor removed: just after an enqueue GitHub may not show the entry yet.
        record Absent() implements QueueView {}
    }

    /// A workflow run; `conclusion` is empty while it is not completed.
    record RunView(long id, String url, String status, String conclusion) {
        public boolean completed() {
            return "completed".equals(status);
        }
    }

    /// One job of a run.
    record JobView(long id, String name, String conclusion) {}

    Promise<PullView> pull(String repo, long pr);
    /// Required check names from `GET rules/branches/{base}` (the `required_status_checks` rules).
    Promise<List<String>> rulesetChecks(String repo, String base);
    /// Required check names from `GET branches/{base}` (`protection.required_status_checks`); both are readable
    /// without admin rights.
    Promise<List<String>> protectionChecks(String repo, String base);

    /// The required check names for `base`: the UNION of both sources, so a check named by only one is still required.
    default Promise<List<String>> requiredChecks(String repo, String base) {
        return Promise.all(rulesetChecks(repo, base), protectionChecks(repo, base)).map(GitHubReader::union);
    }

    static List<String> union(List<String> one, List<String> other) {
        return java.util.stream.Stream.concat(one.stream(),
                                              other.stream())
                                      .distinct()
                                      .toList();
    }

    Promise<List<CheckRun>> checkRuns(String repo, String sha);
    Promise<QueueView> queue(String repo, long pr);
    /// The newest `merge_group` run for this pull request (branch `gh-readonly-queue/<base>/pr-<pr>-<sha>`), if any.
    Promise<Option<RunView>> mergeGroupRun(String repo, long pr);
    Promise<List<JobView>> jobs(String repo, long runId);
    /// A job's log; GitHub serves it only after the whole run has completed.
    Promise<String> jobLog(String repo, long jobId);

    /// No read identity configured: every read fails with the same reason, which the job shows as its observation.
    static GitHubReader unconfigured() {
        var why = Causes.cause("no GitHub read identity is configured for temper-merge");

        return new GitHubReader() {
            @Override
            public Promise<PullView> pull(String repo, long pr) {
                return why.promise();
            }

            @Override
            public Promise<List<String>> requiredChecks(String repo, String base) {
                return why.promise();
            }

            @Override
            public Promise<List<String>> rulesetChecks(String repo, String base) {
                return why.promise();
            }

            @Override
            public Promise<List<String>> protectionChecks(String repo, String base) {
                return why.promise();
            }

            @Override
            public Promise<List<CheckRun>> checkRuns(String repo, String sha) {
                return why.promise();
            }

            @Override
            public Promise<QueueView> queue(String repo, long pr) {
                return why.promise();
            }

            @Override
            public Promise<Option<RunView>> mergeGroupRun(String repo, long pr) {
                return why.promise();
            }

            @Override
            public Promise<List<JobView>> jobs(String repo, long runId) {
                return why.promise();
            }

            @Override
            public Promise<String> jobLog(String repo, long jobId) {
                return why.promise();
            }
        };
    }
}
