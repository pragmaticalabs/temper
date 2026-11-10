package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.LostSettle;


/// What [UnitStore#settleLost] did to one unit (m4-design §2.3): the lost `attempt`, the rule that applied, and the job's
/// terminal state when this settle finished the job.
public record LostSettled(UnitRef ref, int attempt, LostSettle.Outcome outcome, Option<JobState> job) {
    public static LostSettled lostSettled(UnitRef ref, int attempt, LostSettle.Outcome outcome, Option<JobState> job) {
        return new LostSettled(ref, attempt, outcome, job);
    }
}
