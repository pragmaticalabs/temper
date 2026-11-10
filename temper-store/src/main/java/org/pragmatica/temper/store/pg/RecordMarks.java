package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.known.KnownMatch;
import org.pragmatica.temper.domain.rerun.RerunOutcome;
import org.pragmatica.temper.domain.verdict.Evaluation;


/// What a result record is marked with beyond its evaluation, read in the completing transaction ([Finishing]): the
/// grant judges (ruling R-M2-11) and the active known-failure entries (m2-design §4.3). `knownAnnotations` is computed
/// once here, so the record ([ResultRecords]) and its `finished` / `cancelled` event carry the same list.
/// `rerunAnnotations` are a rerun job's `rerun-of=` and `rerun(<class>)=…` (m2-design §5.3), empty for any other job;
/// they go on the record only (the event line carries the KNOWN annotations alone, ruling R-M2-14).
record RecordMarks(List<Judge> grantJudges,
                   KnownMatch known,
                   List<String> knownAnnotations,
                   List<String> rerunAnnotations) {
    static RecordMarks recordMarks(List<Judge> grantJudges,
                                   KnownMatch known,
                                   Evaluation evaluation,
                                   Finishing.Snapshot snapshot) {
        return new RecordMarks(List.copyOf(grantJudges),
                               known,
                               known.annotations(evaluation.failed()),
                               RerunOutcome.annotations(snapshot.job().recipe(),
                                                        snapshot.input()));
    }
}
