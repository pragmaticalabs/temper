package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.gate.Judge;


/// One Gate candidate as [JobStore#gateCandidates] reads it (m2-design §1.1, §1.7): the job with its units, the judge of
/// the controller that accepted it (`jobs.judge`), those of the controllers that granted its units (`units.granted_judge`,
/// in grant order, empty until a grant), that of the controller that finished it (`results.judge`), and its result
/// record once terminal.
public record GateRow(JobRow job,
                      Judge submitJudge,
                      List<Judge> grantJudges,
                      Option<Judge> resultJudge,
                      Option<ResultRecord> record) {
    public GateRow {
        grantJudges = List.copyOf(grantJudges);
    }

    public static GateRow gateRow(JobRow job,
                                  Judge submitJudge,
                                  List<Judge> grantJudges,
                                  Option<Judge> resultJudge,
                                  Option<ResultRecord> record) {
        return new GateRow(job, submitJudge, grantJudges, resultJudge, record);
    }
}
