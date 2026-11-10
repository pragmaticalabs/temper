package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.function.Function;

import org.pragmatica.temper.domain.gate.GateCandidate;
import org.pragmatica.temper.domain.gate.GateEvidence;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.store.api.GateRow;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.Store;

/// The gate read as the controller's route uses it, minus the UNKNOWN overlay (no host links here): the store's rows
/// mapped to candidates, then the pure selection.
final class GateAnswers {
    private GateAnswers() {}

    static List<GateRow> rows(Store store, Sha sha) {
        return store.jobs().gateCandidates(ShaPrefix.shaPrefix(sha.value()).unwrap()).await(PgFixture.WAIT).unwrap();
    }

    static GateEvidence evidence(Store store, Sha sha) {
        return GateEvidence.select(rows(store, sha).stream().map(GateAnswers::candidate).toList());
    }

    static GateCandidate candidate(GateRow row) {
        return GateCandidate.gateCandidate(row.job().id(),
                                           row.job().state(),
                                           row.submitJudge(),
                                           row.grantJudges(),
                                           row.resultJudge(),
                                           row.record().map(ResultRecord::counts),
                                           row.record().map(ResultRecord::annotations).or(List.of()),
                                           row.record().map(record -> record.evidence().stored()).or(false));
    }

    static List<JobId> ids(List<GateRow> rows) {
        return rows.stream().map(row -> row.job().id()).toList();
    }

    static List<JobId> ids(GateEvidence evidence, Function<GateEvidence, List<GateCandidate>> list) {
        return list.apply(evidence).stream().map(GateCandidate::job).toList();
    }
}
