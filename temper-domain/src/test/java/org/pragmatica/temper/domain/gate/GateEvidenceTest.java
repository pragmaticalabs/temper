package org.pragmatica.temper.domain.gate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.pragmatica.temper.domain.state.JobState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.domain.gate.Expected.answer;
import static org.pragmatica.temper.domain.gate.Expected.conflict;
import static org.pragmatica.temper.domain.gate.Gates.X;
import static org.pragmatica.temper.domain.gate.Gates.finished;
import static org.pragmatica.temper.domain.gate.Gates.granted;
import static org.pragmatica.temper.domain.gate.Gates.queued;

/// m2-design §1.1 rules 1-6 under one judge (§1.6 `GateEvidenceTest`): a table over candidate sets, each row the whole
/// expected evidence. Probes (§1.6) and the row each turns red: VOID counted as judged → "VOID newest over a PASSED";
/// the latest verdict answering a disagreement → "PASSED then FAILED"; RUNNING checked before CONFLICT → "a
/// disagreement plus a newer RUNNING"; the input order taken for the age → [#select_ordersByJobId_notByInputOrder].
class GateEvidenceTest {
    record Row(String name, List<GateCandidate> candidates, Expected expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Row> rows() {
        return Stream.of(new Row("no candidates", List.of(), Expected.nothing()),
                         new Row("one PASSED", List.of(finished(1, JobState.PASSED, X)), answer(GateAnswer.PASSED, X, 1)),
                         new Row("one FAILED", List.of(finished(1, JobState.FAILED, X)), answer(GateAnswer.FAILED, X, 1)),
                         new Row("one EMPTY", List.of(finished(1, JobState.EMPTY, X)), answer(GateAnswer.EMPTY, X, 1)),
                         new Row("VOID only: NONE, listed unjudged",
                                 List.of(finished(1, JobState.VOID, X)),
                                 Expected.nothing().unjudged(1L)),
                         new Row("ERROR and CANCELLED only: NONE, both unjudged, newest first",
                                 List.of(finished(1, JobState.ERROR, X), finished(2, JobState.CANCELLED, X)),
                                 Expected.nothing().unjudged(2L, 1L)),
                         new Row("newest RUNNING over an older PASSED: RUNNING with previous",
                                 List.of(finished(1, JobState.PASSED, X), granted(2, JobState.RUNNING, X)),
                                 answer(GateAnswer.RUNNING, X, 2).previous(1)),
                         new Row("newest QUEUED over an older FAILED: RUNNING with previous",
                                 List.of(finished(1, JobState.FAILED, X), queued(2, X)),
                                 answer(GateAnswer.RUNNING, X, 2).previous(1)),
                         new Row("newest UNKNOWN alone: RUNNING without previous",
                                 List.of(granted(1, JobState.UNKNOWN, X)),
                                 answer(GateAnswer.RUNNING, X, 1)),
                         new Row("PASSED then FAILED: CONFLICT, both listed, never the latest (R-M2-5)",
                                 List.of(finished(1, JobState.PASSED, X), finished(2, JobState.FAILED, X)),
                                 conflict(X, 2L, 1L)),
                         new Row("FAILED, FAILED: FAILED about the newest",
                                 List.of(finished(1, JobState.FAILED, X), finished(2, JobState.FAILED, X)),
                                 answer(GateAnswer.FAILED, X, 2)),
                         new Row("a disagreement plus a newer RUNNING: CONFLICT with running",
                                 List.of(finished(1, JobState.PASSED, X),
                                         finished(2, JobState.FAILED, X),
                                         granted(3, JobState.RUNNING, X)),
                                 conflict(X, 2L, 1L).running(3)),
                         new Row("a disagreement over an OLDER running Gate: no running (it is not newer)",
                                 List.of(granted(1, JobState.CANCELLING, X),
                                         finished(2, JobState.PASSED, X),
                                         finished(3, JobState.FAILED, X)),
                                 conflict(X, 3L, 2L)),
                         new Row("VOID newest over a PASSED: PASSED, the VOID unjudged",
                                 List.of(finished(1, JobState.PASSED, X), finished(2, JobState.VOID, X)),
                                 answer(GateAnswer.PASSED, X, 1).unjudged(2L)),
                         new Row("EMPTY against FAILED is a disagreement",
                                 List.of(finished(1, JobState.EMPTY, X), finished(2, JobState.FAILED, X)),
                                 conflict(X, 2L, 1L)),
                         new Row("an older RUNNING under a newer PASSED: PASSED",
                                 List.of(granted(1, JobState.RUNNING, X), finished(2, JobState.PASSED, X)),
                                 answer(GateAnswer.PASSED, X, 2)),
                         new Row("ERROR between a PASSED and a newer RUNNING: RUNNING with previous, ERROR unjudged",
                                 List.of(finished(1, JobState.PASSED, X),
                                         finished(2, JobState.ERROR, X),
                                         granted(3, JobState.RUNNING, X)),
                                 answer(GateAnswer.RUNNING, X, 3).previous(1).unjudged(2L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void select_answersPerTheRules(Row row) {
        row.expected()
           .check(GateEvidence.select(row.candidates()));
    }

    /// "Latest" is the highest job id: every permutation of one candidate set selects the same evidence.
    @Test
    void select_ordersByJobId_notByInputOrder() {
        var candidates = new ArrayList<>(List.of(finished(1, JobState.FAILED, X),
                                                 finished(2, JobState.VOID, X),
                                                 finished(3, JobState.FAILED, X),
                                                 granted(4, JobState.RUNNING, X)));
        var expected = answer(GateAnswer.RUNNING, X, 4).previous(3).unjudged(2L);

        for (var turn = 0; turn < candidates.size(); turn++) {
            expected.check(GateEvidence.select(List.copyOf(candidates)));
            Collections.rotate(candidates, 1);
        }
        Collections.reverse(candidates);
        expected.check(GateEvidence.select(candidates));
    }

    @Test
    void vote_isPassedFailedEmptyOnly() {
        assertThat(Stream.of(JobState.values()).filter(state -> GateAnswer.vote(state).isPresent()))
            .containsExactly(JobState.PASSED, JobState.FAILED, JobState.EMPTY);
    }
}
