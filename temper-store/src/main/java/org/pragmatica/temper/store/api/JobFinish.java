package org.pragmatica.temper.store.api;

import org.pragmatica.temper.domain.verdict.Evaluation;


/// The controller's decision that a job is finished: its evaluation (normally `Verdicts.evaluate`). The store
/// builds the §5.4 result record from it and the job's rows inside the completing transaction.
public record JobFinish(Evaluation evaluation) {
    public static JobFinish jobFinish(Evaluation evaluation) {
        return new JobFinish(evaluation);
    }
}
