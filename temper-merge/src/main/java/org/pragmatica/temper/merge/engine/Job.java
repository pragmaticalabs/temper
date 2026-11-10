package org.pragmatica.temper.merge.engine;

import java.time.Instant;
import java.util.List;


/// One merge job as it is stored: what was asked (`repo`, `pr`, `sha`, `ready`), where it stands (`state`, `reason`,
/// `phaseSince`, `lastObservation`) and what was found at the end (`mergeSha`, `closedIssues`, `removal`, `run`,
/// `failedJobs`, `failedTests`). Absent text is the empty string, so the stored form has no nulls.
///
/// `phaseSince` is when the job entered its state; a phase's deadline counts from it ([Policy#deadline]).
public record Job(String id,
                  String repo,
                  long pr,
                  String sha,
                  boolean ready,
                  JobState state,
                  String reason,
                  Instant submittedAt,
                  Instant phaseSince,
                  String lastObservation,
                  String mergeSha,
                  List<String> closedIssues,
                  String removal,
                  String run,
                  List<String> failedJobs,
                  List<String> failedTests) {
    public Job {
        closedIssues = List.copyOf(closedIssues);
        failedJobs = List.copyOf(failedJobs);
        failedTests = List.copyOf(failedTests);
    }

    public static Job submitted(String id, String repo, long pr, String sha, boolean ready, Instant now) {
        return new Job(id,
                       repo,
                       pr,
                       sha,
                       ready,
                       JobState.SUBMITTED,
                       ready
                       ? "submitted --ready"
                       : "submitted",
                       now,
                       now,
                       "",
                       "",
                       List.of(),
                       "",
                       "",
                       List.of(),
                       List.of());
    }

    /// Into `next`, the phase clock restarting at `now`.
    public Job moved(JobState next, String why, Instant now) {
        return new Job(id,
                       repo,
                       pr,
                       sha,
                       ready,
                       next,
                       why,
                       submittedAt,
                       now,
                       lastObservation,
                       mergeSha,
                       closedIssues,
                       removal,
                       run,
                       failedJobs,
                       failedTests);
    }

    /// The same state, with what this step saw.
    public Job observed(String observation) {
        return new Job(id,
                       repo,
                       pr,
                       sha,
                       ready,
                       state,
                       reason,
                       submittedAt,
                       phaseSince,
                       observation,
                       mergeSha,
                       closedIssues,
                       removal,
                       run,
                       failedJobs,
                       failedTests);
    }

    public Job merged(String commit, List<String> issues, Instant now) {
        return new Job(id,
                       repo,
                       pr,
                       sha,
                       ready,
                       JobState.MERGED,
                       "merged " + commit,
                       submittedAt,
                       now,
                       lastObservation,
                       commit,
                       issues,
                       removal,
                       run,
                       failedJobs,
                       failedTests);
    }

    /// Removed from the queue: `why` is GitHub's removal reason, kept in `removal` for the final report.
    public Job dropped(String why, Instant now) {
        return new Job(id,
                       repo,
                       pr,
                       sha,
                       ready,
                       JobState.AWAITING_RUN,
                       "removed from the queue: " + why,
                       submittedAt,
                       now,
                       lastObservation,
                       mergeSha,
                       closedIssues,
                       why,
                       run,
                       failedJobs,
                       failedTests);
    }

    /// The removal's final report: the merge-group run, its failed jobs and the failing tests read from their logs.
    public Job reported(String runText, List<String> jobs, List<String> tests, Instant now) {
        return new Job(id,
                       repo,
                       pr,
                       sha,
                       ready,
                       JobState.REMOVED,
                       "removed from the queue: " + removal,
                       submittedAt,
                       now,
                       lastObservation,
                       mergeSha,
                       closedIssues,
                       removal,
                       runText,
                       jobs,
                       tests);
    }
}
