package org.pragmatica.temper.domain.diff;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.gate.GateCandidate;
import org.pragmatica.temper.domain.gate.GateEvidence;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// `diff <job> --base <sha>` over each `gate` answer (m2-design §3.1, §3.5 `DiffBaseTest`), through the real
/// [GateEvidence#select], so the two verbs read "the gate at S" alike. Probe: use the latest judged job on CONFLICT →
/// [#conflict_hasNoBase_listingEveryVote].
class DiffBaseTest {
    private static final Judge X = Judge.judge("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d").unwrap();
    private static final Judge Y = Judge.judge("5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80").unwrap();
    private static final Counts COUNTS = Counts.counts(12, 11, 1, 0, 0, 12, 3, none(), none(), none());

    @Test
    void passedFailedEmpty_baseIsTheAnsweringGate() {
        for (var state : List.of(JobState.PASSED, JobState.FAILED, JobState.EMPTY)) {
            var evidence = GateEvidence.select(List.of(finished(4690, state, X), finished(4650, JobState.VOID, X)));

            assertThat(base(evidence)).as(state.name()).isEqualTo(some(4690L));
        }
    }

    /// The newest judge's Gate, not an older judge's newer-looking one.
    @Test
    void newerJudge_answers_soItIsTheBase() {
        var evidence = GateEvidence.select(List.of(finished(4650, JobState.FAILED, X), finished(4760, JobState.PASSED, Y)));

        assertThat(evidence.answer()).isEqualTo(GateAnswer.PASSED);
        assertThat(base(evidence)).isEqualTo(some(4760L));
    }

    /// RUNNING: the same judge's newest vote it would otherwise be [G].
    @Test
    void running_baseIsItsPrevious() {
        var evidence = GateEvidence.select(List.of(finished(4711, JobState.FAILED, X), running(4720, X)));

        assertThat(evidence.answer()).isEqualTo(GateAnswer.RUNNING);
        assertThat(base(evidence)).isEqualTo(some(4711L));
    }

    @Test
    void runningWithoutAVote_hasNoBase() {
        var evidence = GateEvidence.select(List.of(running(4720, X)));

        assertThat(evidence.answer()).isEqualTo(GateAnswer.RUNNING);
        assertThat(DiffBase.choose(evidence)).isEqualTo(DiffBase.Missing.missing());
    }

    @Test
    void none_hasNoBase() {
        assertThat(DiffBase.choose(GateEvidence.select(List.of()))).isEqualTo(DiffBase.Missing.missing());
        assertThat(DiffBase.choose(GateEvidence.select(List.of(finished(4702, JobState.VOID, X))))).isEqualTo(DiffBase.Missing.missing());
    }

    /// Q-M2-26 (ruled R-M2-9): a CONFLICT is no base; picking the latest would present it as the answer (R-M2-5).
    @Test
    void conflict_hasNoBase_listingEveryVote() {
        var evidence = GateEvidence.select(List.of(finished(4690, JobState.PASSED, X), finished(4711, JobState.FAILED, X)));
        var base = DiffBase.choose(evidence);

        assertThat(evidence.answer()).isEqualTo(GateAnswer.CONFLICT);
        assertThat(base).isInstanceOf(DiffBase.Conflicting.class);
        assertThat(((DiffBase.Conflicting) base).votes()).extracting(candidate -> candidate.job().value()).containsExactly(4711L, 4690L);
    }

    private static Option<Long> base(GateEvidence evidence) {
        return switch (DiffBase.choose(evidence)) {
            case DiffBase.Found found -> some(found.gate().job().value());
            case DiffBase.Missing _, DiffBase.Conflicting _ -> none();
        };
    }

    private static GateCandidate finished(long id, JobState state, Judge judge) {
        return GateCandidate.gateCandidate(JobId.jobId(id).unwrap(), state, judge, List.of(judge), some(judge), some(COUNTS), List.of(), true);
    }

    private static GateCandidate running(long id, Judge judge) {
        return GateCandidate.gateCandidate(JobId.jobId(id).unwrap(), JobState.RUNNING, judge, List.of(judge), none(), none(), List.of(), false);
    }
}
