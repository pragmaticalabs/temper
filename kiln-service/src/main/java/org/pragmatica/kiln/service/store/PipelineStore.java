package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.pipeline.MergedPr;
import org.pragmatica.kiln.core.pipeline.PrMeasure;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// `pipeline_pr`, `pipeline_run`, `pipeline_attempt` (V7, #102): the merged PRs the pipeline baseline measures, each PR's
/// reads as they arrive, and the measurements. Merged PRs are immutable, so caching them is safe; a complete measurement
/// is never replaced by a re-read, and neither is a settled attempt.
public interface PipelineStore {
    /// One stored PR: its merge time, its GraphQL node, and its measurement once read.
    record Row(long number, Instant mergedAt, String node, Option<PrMeasure> measure) {
        public boolean complete() {
            return measure.filter(PrMeasure::complete)
                          .isPresent();
        }
    }

    /// The two run listings of a PR, each stored once it has been read whole.
    enum Listing {
        /// `event=pull_request` on the PR's head branch.
        PR,
        /// `event=merge_group` created in the PR's queue window.
        QUEUE
    }

    /// One run of a PR as last read: `attempts` its latest attempt number, `completed` its status.
    record Run(long run,
               String workflow,
               String event,
               String headSha,
               String headBranch,
               long attempts,
               boolean completed) {}

    /// One attempt's jobs as [org.pragmatica.kiln.core.pipeline.RunAttempt#stored] writes them. `settled`: read when it
    /// could no longer change (a later attempt existed, or its run had completed); a settled attempt is never read again.
    record Attempt(long run, long attempt, String jobs, boolean settled) {}

    /// How far a PR's reads have come.
    record Progress(boolean prRunsListed, boolean queueRunsListed, List<Run> runs, List<Attempt> attempts) {
        public Progress {
            runs = List.copyOf(runs);
            attempts = List.copyOf(attempts);
        }

        public boolean listed(Listing listing) {
            return listing == Listing.PR
                   ? prRunsListed
                   : queueRunsListed;
        }
    }

    /// Records the listed PRs; a PR already listed keeps its row.
    Promise<Unit> listed(List<MergedPr.Listed> prs, Instant at);
    /// Every stored PR, oldest merge first.
    Promise<List<Row>> rows();
    /// Records a measurement; a complete one already stored is kept.
    Promise<Unit> measured(PrMeasure measure, Instant at);
    /// PR `number`'s reads so far, runs by id, attempts by run then attempt.
    Promise<Progress> progress(long number);
    /// Records one whole run listing of PR `number`: every run in it, then the listing as done.
    Promise<Unit> runsListed(long number, Listing listing, List<Run> runs);
    /// Records one run as re-read.
    Promise<Unit> run(long number, Run run);
    /// Records one attempt's jobs; a settled attempt already stored is kept.
    Promise<Unit> attempt(long number, Attempt attempt);
}
