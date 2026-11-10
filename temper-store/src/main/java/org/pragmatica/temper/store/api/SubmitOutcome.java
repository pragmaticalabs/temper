package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;


/// What [JobStore#submit] did.
public sealed interface SubmitOutcome {
    JobId job();

    /// A new QUEUED job. `toCancel` lists the units of superseded RUNNING jobs (without `keep`) that are now
    /// CANCELLING; the controller sends `CancelUnit` for each.
    record Accepted(JobId job, List<UnitRef> toCancel) implements SubmitOutcome {
        public Accepted {
            toCancel = List.copyOf(toCancel);
        }
    }

    /// An existing job with the same dedup key in a reusable state, reported with `deduplicated: true`.
    record Deduplicated(JobId job, JobState state) implements SubmitOutcome {}
}
