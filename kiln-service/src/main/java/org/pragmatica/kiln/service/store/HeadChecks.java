package org.pragmatica.kiln.service.store;

import java.util.List;

import org.pragmatica.kiln.core.feed.CheckFeed;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.github.WorkflowRun;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;


/// One open PR's head as one cycle read it, for [FeedStore#checks]: the required list of its base, the observations of
/// that list's checks, and every workflow run at the head (the settle window).
public record HeadChecks(PrNumber pr,
                         Sha head,
                         BranchName base,
                         RequiredChecks.Required list,
                         List<CheckFeed.Observation> observed,
                         List<WorkflowRun> headRuns) {
    public HeadChecks {
        observed = List.copyOf(observed);
        headRuns = List.copyOf(headRuns);
    }
}
