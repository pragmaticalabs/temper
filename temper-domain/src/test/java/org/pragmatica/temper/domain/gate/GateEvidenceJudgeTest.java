package org.pragmatica.temper.domain.gate;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.gate.Expected.answer;
import static org.pragmatica.temper.domain.gate.Expected.conflict;
import static org.pragmatica.temper.domain.gate.Gates.LOW;
import static org.pragmatica.temper.domain.gate.Gates.X;
import static org.pragmatica.temper.domain.gate.Gates.Y;
import static org.pragmatica.temper.domain.gate.Gates.candidate;
import static org.pragmatica.temper.domain.gate.Gates.finished;
import static org.pragmatica.temper.domain.gate.Gates.granted;
import static org.pragmatica.temper.domain.gate.Gates.units;

/// m2-design §1.1 rule 2 and §1.7 (rulings R-M2-10, R-M2-11; §1.6 `GateEvidenceJudgeTest`): the answer is computed
/// among the newest judge's Gates only, and a Gate judged across a deploy counts nowhere. `X` is the older build and
/// `Y` the newer one in every row. Probes and the row each turns red: answer across every judge → "older FAILED,
/// newer PASSED"; let an unjudged Gate decide the judge → "a newer-judge VOID"; order judges by commit hash → "a newer
/// judge whose commit sorts first"; ignore R-M2-11 → "granted under X, finished under Y"; compare the submit judge
/// instead of the grant judge → "queued under X, granted and finished under Y".
class GateEvidenceJudgeTest {
    record Row(String name, List<GateCandidate> candidates, Expected expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Row> rows() {
        return Stream.of(new Row("older judge FAILED, newer judge PASSED: PASSED, the FAILED in older (CTO)",
                                 List.of(finished(1, JobState.FAILED, X), finished(2, JobState.PASSED, Y)),
                                 answer(GateAnswer.PASSED, Y, 2).older(1L)),
                         new Row("same-judge disagreement: CONFLICT (CTO)",
                                 List.of(finished(1, JobState.PASSED, Y), finished(2, JobState.FAILED, Y)),
                                 conflict(Y, 2L, 1L)),
                         new Row("an old-judge CONFLICT under one newer-judge PASSED: PASSED, both old ones older",
                                 List.of(finished(1, JobState.PASSED, X),
                                         finished(2, JobState.FAILED, X),
                                         finished(3, JobState.PASSED, Y)),
                                 answer(GateAnswer.PASSED, Y, 3).older(2L, 1L)),
                         new Row("a newer-judge VOID over an older-judge PASSED: PASSED (unjudged never decides the judge)",
                                 List.of(finished(1, JobState.PASSED, X), finished(2, JobState.VOID, Y)),
                                 answer(GateAnswer.PASSED, X, 1).unjudged(2L)),
                         new Row("a newer-judge RUNNING over an older-judge FAILED: RUNNING, no previous, the FAILED older",
                                 List.of(finished(1, JobState.FAILED, X), granted(2, JobState.RUNNING, Y)),
                                 answer(GateAnswer.RUNNING, Y, 2).older(1L)),
                         new Row("pre-judge rows are older than any judge",
                                 List.of(finished(1, JobState.PASSED, Judge.PRE), finished(2, JobState.FAILED, X)),
                                 answer(GateAnswer.FAILED, X, 2).older(1L)),
                         new Row("a newer judge whose commit sorts first still answers (id order, not hash order)",
                                 List.of(finished(1, JobState.PASSED, X), finished(2, JobState.FAILED, LOW)),
                                 answer(GateAnswer.FAILED, LOW, 2).older(1L)),
                         new Row("granted under X, finished under Y: judge-changed, excluded from the answer (CTO, R-M2-11)",
                                 List.of(finished(1, JobState.PASSED, X),
                                         candidate(2, JobState.FAILED, X, some(X), some(Y))),
                                 answer(GateAnswer.PASSED, X, 1).judgeChanged(2L)),
                         new Row("queued under X, granted and finished under Y: counts under Y (CTO, R-M2-11)",
                                 List.of(finished(1, JobState.PASSED, X),
                                         candidate(2, JobState.FAILED, X, some(Y), some(Y))),
                                 answer(GateAnswer.FAILED, Y, 2).older(1L)),
                         new Row("judge-changed alone: NONE, listed",
                                 List.of(candidate(1, JobState.PASSED, X, some(X), some(Y))),
                                 Expected.nothing().judgeChanged(1L)),
                         new Row("judge-changed never joins a CONFLICT: the same-judge PASSED answers",
                                 List.of(finished(1, JobState.PASSED, Y),
                                         candidate(2, JobState.FAILED, X, some(X), some(Y))),
                                 answer(GateAnswer.PASSED, Y, 1).judgeChanged(2L)),
                         new Row("a VOID across a deploy is unjudged, not judge-changed",
                                 List.of(finished(1, JobState.PASSED, Y),
                                         candidate(2, JobState.VOID, X, some(X), some(Y))),
                                 answer(GateAnswer.PASSED, Y, 1).unjudged(2L)),
                         new Row("cancelled before any grant: no grant judge, unjudged",
                                 List.of(candidate(1, JobState.CANCELLED, X, none(), some(Y)), finished(2, JobState.PASSED, Y)),
                                 answer(GateAnswer.PASSED, Y, 2).unjudged(1L)),
                         new Row("a job granted before V6 has no grant judge, so it is never judge-changed (R-12)",
                                 List.of(candidate(1, JobState.FAILED, Judge.PRE, none(), some(Y))),
                                 answer(GateAnswer.FAILED, Y, 1)),
                         new Row("any unit granted under another judge makes the job judge-changed, not only the first",
                                 List.of(finished(1, JobState.PASSED, Y), units(2, JobState.FAILED, Y, List.of(Y, X), Y)),
                                 answer(GateAnswer.PASSED, Y, 1).judgeChanged(2L)),
                         new Row("units all granted under the finishing judge: a vote",
                                 List.of(finished(1, JobState.PASSED, Y), units(2, JobState.FAILED, X, List.of(Y, Y), Y)),
                                 conflict(Y, 2L, 1L)),
                         new Row("a running Gate counts under its first unit's grant judge",
                                 List.of(finished(1, JobState.FAILED, X),
                                         candidate(2, JobState.RUNNING, X, some(Y), none())),
                                 answer(GateAnswer.RUNNING, Y, 2).older(1L)),
                         new Row("a running Gate whose units were granted under X, then Y, counts under X (the first grant)",
                                 List.of(finished(1, JobState.PASSED, Y),
                                         GateCandidate.gateCandidate(JobId.jobId(2).unwrap(), JobState.RUNNING, X, List.of(X, Y),
                                                                     none(), none(), List.of(), false)),
                                 answer(GateAnswer.RUNNING, X, 2).older(1L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void select_answersAmongTheNewestJudgesGatesOnly(Row row) {
        row.expected()
           .check(GateEvidence.select(row.candidates()));
    }
}
