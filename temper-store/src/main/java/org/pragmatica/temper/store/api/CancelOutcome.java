package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;


/// What [JobStore#requestCancel] did; `state()` is the job's state afterwards (`CancelResponse`).
public sealed interface CancelOutcome {
    JobId job();
    JobState state();

    /// Nothing of the job was granted or running: it is finished now, with its result row. `state` is CANCELLED
    /// unless a unit that already ended makes it VOID or ERROR (spec §5.3 order).
    record Cancelled(JobId job, JobState state) implements CancelOutcome {}

    /// The job is CANCELLING; `toKill` are its active units (also on a repeated request, so a lost `CancelUnit`
    /// can be resent).
    record Cancelling(JobId job, List<UnitRef> toKill) implements CancelOutcome {
        public Cancelling {
            toKill = List.copyOf(toKill);
        }

        @Override
        public JobState state() {
            return JobState.CANCELLING;
        }
    }

    /// The job was already terminal; nothing changed.
    record AlreadyTerminal(JobId job, JobState state) implements CancelOutcome {}
}
