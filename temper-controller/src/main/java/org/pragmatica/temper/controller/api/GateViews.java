package org.pragmatica.temper.controller.api;

import java.util.List;

import org.pragmatica.temper.domain.gate.GateCandidate;
import org.pragmatica.temper.domain.gate.GateEvidence;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.protocol.api.GateView;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.protocol.api.ResultRecordDto.CountsDto;


/// [GateEvidence] → `GateView` (m2-design §1.4). The answered Gate's counts, annotations and evidence come from its
/// result record; a running one has none yet. `evidence` is `stored` or `missing`, as on the `result` line. `reruns`
/// are the answering Gate's (m2-design §5.3), read in the same snapshot as the candidates: information, never the
/// answer, since a rerun job is never a candidate.
public sealed interface GateViews {
    static GateView gateView(String sha, GateEvidence evidence, List<RerunView> answeringReruns) {
        var answering = evidence.answering();

        return GateView.gateView(sha,
                                 evidence.judge().map(Judge::value),
                                 evidence.answer(),
                                 answering.map(candidate -> candidate.job()
                                                                     .value()),
                                 answering.map(GateCandidate::state),
                                 answering.flatMap(GateCandidate::counts).map(CountsDto::countsDto),
                                 answering.map(GateCandidate::annotations).or(List.of()),
                                 answering.filter(candidate -> !candidate.isRunning()).map(GateViews::evidence),
                                 evidence.previous().map(GateViews::vote),
                                 votes(evidence.conflict()),
                                 evidence.running().map(JobId::value),
                                 votes(evidence.older()),
                                 evidence.judgeChanged().stream().map(GateViews::judgeChanged).toList(),
                                 evidence.unjudged().stream().map(JobId::value).toList(),
                                 answeringReruns);
    }

    /// Each candidate as the vote `gate` lists it; also a conflicting base of `diff --base` ([DiffViews]).
    static List<GateView.Vote> votes(List<GateCandidate> candidates) {
        return candidates.stream()
                         .map(GateViews::vote)
                         .toList();
    }

    private static GateView.Vote vote(GateCandidate candidate) {
        return GateView.Vote.vote(candidate.job().value(),
                                  candidate.state(),
                                  candidate.judge().value());
    }

    /// A judge-changed candidate always has a differing grant judge (that is what changed); its own judge stands in
    /// otherwise.
    private static GateView.JudgeChanged judgeChanged(GateCandidate candidate) {
        return GateView.JudgeChanged.judgeChanged(candidate.job().value(),
                                                  candidate.state(),
                                                  candidate.changedGrantJudge().or(candidate.judge()).value(),
                                                  candidate.judge().value());
    }

    private static String evidence(GateCandidate candidate) {
        return candidate.evidenceStored()
               ? "stored"
               : "missing";
    }

    record unused() implements GateViews {}
}
