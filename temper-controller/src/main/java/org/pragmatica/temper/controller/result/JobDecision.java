package org.pragmatica.temper.controller.result;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.verdict.VerdictInput;
import org.pragmatica.temper.domain.verdict.Verdicts;
import org.pragmatica.temper.store.api.JobFinish;

import static org.pragmatica.lang.Option.some;


/// The `decideJob` function every `UnitStore.complete` call passes (m0-design §6.6): once every unit of the job is
/// terminal, the job finishes with `Verdicts.evaluate`. The store builds the §5.4 result record from the evaluation
/// and the job's rows in the same transaction (timings, units, source, recipe; `evidence = {"stored": false}` in M0).
public sealed interface JobDecision {
    static Option<JobFinish> decide(VerdictInput input) {
        return some(JobFinish.jobFinish(Verdicts.evaluate(input)));
    }

    record unused() implements JobDecision {}
}
