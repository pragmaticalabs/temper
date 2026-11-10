package org.pragmatica.temper.domain.gate;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;


/// What `gate <sha>` answers (m2-design §1.1; rulings R-M2-5, R-M2-9, R-M2-10, R-M2-11), selected from the Gate
/// candidates at one SHA by [#select]. "Newest" is the highest job id, a `bigserial` assigned at submit, never a time.
///
/// Rules, first match wins:
/// 1. **Judged** = PASSED, FAILED or EMPTY. VOID, ERROR and CANCELLED never answer and never decide the judge; they
///    are listed in `unjudged`. A judged Gate whose grant judge is not its finishing judge was **judged across a
///    deploy** (R-M2-11): it is excluded from the answer and from the CONFLICT set, decides nothing, and is listed in
///    `judgeChanged`.
/// 2. **The newest judge** (R-M2-10) is the judge of the newest candidate that is judged within one build or still
///    running ([GateCandidate#judge]). Every rule below looks only at that judge's candidates; the judged candidates of
///    the other judges are history (`older`), never the answer.
/// 3. **CONFLICT** (R-M2-5): that judge's votes carry more than one distinct verdict. Every vote is listed, newest first,
///    and no single verdict is the answer. A non-terminal candidate of that judge newer than every vote is `running`.
/// 4. **RUNNING**: that judge's newest candidate is not terminal; its newest vote, if any, is `previous` (R-M2-9).
/// 5. The one verdict of that judge's votes, about its newest vote.
/// 6. **NONE**: no candidate decides a judge.
///
/// `answering` is the candidate a PASSED, FAILED, EMPTY or RUNNING answer is about; `previous` only accompanies
/// RUNNING, `conflict` and `running` only CONFLICT. Every list is newest first.
public record GateEvidence(Option<Judge> judge,
                           GateAnswer answer,
                           Option<GateCandidate> answering,
                           Option<GateCandidate> previous,
                           List<GateCandidate> conflict,
                           Option<JobId> running,
                           List<GateCandidate> older,
                           List<GateCandidate> judgeChanged,
                           List<JobId> unjudged) {
    public GateEvidence {
        conflict = List.copyOf(conflict);
        older = List.copyOf(older);
        judgeChanged = List.copyOf(judgeChanged);
        unjudged = List.copyOf(unjudged);
    }

    public static GateEvidence gateEvidence(Option<Judge> judge,
                                            GateAnswer answer,
                                            Option<GateCandidate> answering,
                                            Option<GateCandidate> previous,
                                            List<GateCandidate> conflict,
                                            Option<JobId> running,
                                            List<GateCandidate> older,
                                            List<GateCandidate> judgeChanged,
                                            List<JobId> unjudged) {
        return new GateEvidence(judge, answer, answering, previous, conflict, running, older, judgeChanged, unjudged);
    }

    /// Pure; the candidates may come in any order. The rules above, applied by [GateSelection].
    public static GateEvidence select(List<GateCandidate> candidates) {
        return GateSelection.select(candidates);
    }
}
