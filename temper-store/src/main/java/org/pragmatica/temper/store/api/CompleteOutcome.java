package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;


/// What [UnitStore#complete] did.
public sealed interface CompleteOutcome {
    UnitRef ref();
    UnitState state();

    /// The unit was already terminal; nothing was written (the controller still acks the report).
    record AlreadyComplete(UnitRef ref, UnitState state) implements CompleteOutcome {}

    /// The unit was not active (QUEUED again after a requeue): a stale report; nothing was written.
    record NotActive(UnitRef ref, UnitState state) implements CompleteOutcome {}

    /// The report belongs to an attempt that is no longer the unit's current one on the reporting host (m4-design
    /// §4.3): the unit was requeued and granted again (`current` is its attempt now), or it is active on another
    /// host. Nothing was written; the report is acked, as nothing of it is left to reconcile.
    record Superseded(UnitRef ref, UnitState state, int attempt, int current) implements CompleteOutcome {}

    /// The unit is terminal now; `job` is the job's terminal state when this completion finished the job.
    record Completed(UnitRef ref, UnitState state, Option<JobState> job) implements CompleteOutcome {}
}
