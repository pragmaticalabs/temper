package org.pragmatica.temper.domain.diff;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.diff.TestDiffTest.row;

/// m2-design §3.1, §3.2: two complete judged sides are compared; a pruned side (F16), a side without a record and a
/// side not judged (VOID, ERROR, CANCELLED, R-M2-13; or REFUSED) are refused, never approximated. Probe: drop the pruned check → [#prunedSide_isRefused_notReportedAsRemoved].
class DiffOutcomeTest {
    private static final List<TestcaseRow> TWO = List.of(row("a.A", "one", Outcome.PASSED), row("a.A", "two", Outcome.PASSED));

    @Test
    void completeSides_areCompared() {
        var outcome = DiffOutcome.decide(side(1, JobState.PASSED, some(2), TWO),
                                     side(2, JobState.FAILED, some(2), List.of(row("a.A", "one", Outcome.PASSED),
                                                                              row("a.A", "two", Outcome.FAILED))));

        assertThat(outcome).isInstanceOf(DiffOutcome.Compared.class);
        assertThat(((DiffOutcome.Compared) outcome).diff().newlyFailing()).hasSize(1);
    }

    /// Fewer rows than the record counts: retention deleted some. Computing anyway would list them all as removed.
    @Test
    void prunedSide_isRefused_notReportedAsRemoved() {
        var pruned = side(1, JobState.PASSED, some(2), List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(DiffOutcome.decide(pruned, side(2, JobState.PASSED, some(2), TWO))).isEqualTo(refused(DiffObstacle.PRUNED,
                                                                                                    DiffOutcome.Side.A));
        assertThat(DiffOutcome.decide(side(2, JobState.PASSED, some(2), TWO), pruned)).isEqualTo(refused(DiffObstacle.PRUNED,
                                                                                                    DiffOutcome.Side.B));
        assertThat(DiffOutcome.decide(pruned, pruned)).isEqualTo(refused(DiffObstacle.PRUNED, DiffOutcome.Side.A, DiffOutcome.Side.B));
    }

    /// Every row deleted is pruned too; a job that stored none (counts 0) is not.
    @Test
    void fullyPrunedSide_isRefused_butAnEmptyRecordIsComplete() {
        assertThat(DiffOutcome.decide(side(1, JobState.PASSED, some(2), List.of()), side(2, JobState.PASSED, some(2), TWO)))
            .isEqualTo(refused(DiffObstacle.PRUNED, DiffOutcome.Side.A));
        assertThat(DiffOutcome.decide(side(1, JobState.EMPTY, some(0), List.of()), side(2, JobState.PASSED, some(2), TWO)))
            .isInstanceOf(DiffOutcome.Compared.class);
    }

    /// The record counts the rows of primary units only (`Verdicts.primaryUnits`): the rows of a rerun unit neither hide
    /// a prune nor join the diff.
    @Test
    void rerunRows_areNotCounted_andNotCompared() {
        var rerunUnit = UnitName.unitName("rerun-1").unwrap();
        var rerun = TestcaseRow.testcaseRow(rerunUnit, "a.A", "two", Outcome.FAILED, 0.1, false);
        var withRerun = DiffSide.diffSide(JobId.jobId(1).unwrap(), JobState.PASSED, some(counts(2)),
                                          List.of(row("a.A", "one", Outcome.PASSED), rerun), Set.of(rerunUnit));

        assertThat(DiffOutcome.decide(withRerun, side(2, JobState.PASSED, some(2), TWO))).isEqualTo(refused(DiffObstacle.PRUNED,
                                                                                                       DiffOutcome.Side.A));

        var complete = DiffSide.diffSide(JobId.jobId(1).unwrap(), JobState.PASSED, some(counts(2)),
                                         List.of(row("a.A", "one", Outcome.PASSED), row("a.A", "two", Outcome.PASSED), rerun),
                                         Set.of(rerunUnit));
        var compared = (DiffOutcome.Compared) DiffOutcome.decide(side(2, JobState.PASSED, some(2), TWO), complete);

        assertThat(compared.diff().newlyFailing()).as("the rerun row's FAILED is not B's outcome").isEmpty();
    }

    @Test
    void sideWithoutARecord_isNotTerminal() {
        var running = side(2, JobState.RUNNING, none(), List.of(row("a.A", "one", Outcome.PASSED)));

        assertThat(DiffOutcome.decide(side(1, JobState.PASSED, some(2), TWO), running)).isEqualTo(refused(DiffObstacle.NOT_TERMINAL,
                                                                                                     DiffOutcome.Side.B));
    }

    /// A REFUSED job never ran and never will: not judged, and not "not terminal", since waiting cannot help [G].
    @Test
    void refusedSide_isNotJudged() {
        assertThat(DiffOutcome.decide(side(1, JobState.REFUSED, none(), List.of()), side(2, JobState.PASSED, some(2), TWO)))
            .isEqualTo(refused(DiffObstacle.NOT_JUDGED, DiffOutcome.Side.A));
    }

    /// What waiting cannot change is reported first: a pruned or not judged side beats a running one (exit 8, not 9).
    @Test
    void permanentObstacle_isReportedBeforeNotTerminal() {
        var pruned = side(1, JobState.PASSED, some(2), List.of());
        var running = side(2, JobState.QUEUED, none(), List.of());

        assertThat(DiffOutcome.decide(pruned, running)).isEqualTo(refused(DiffObstacle.PRUNED, DiffOutcome.Side.A));
        assertThat(DiffOutcome.decide(running, pruned)).isEqualTo(refused(DiffObstacle.PRUNED, DiffOutcome.Side.B));
        assertThat(DiffOutcome.decide(side(1, JobState.REFUSED, none(), List.of()), running))
            .as("a REFUSED A with a running B")
            .isEqualTo(refused(DiffObstacle.NOT_JUDGED, DiffOutcome.Side.A));
        assertThat(DiffOutcome.decide(running, side(2, JobState.VOID, some(2), TWO)))
            .as("a running A with a VOID B")
            .isEqualTo(refused(DiffObstacle.NOT_JUDGED, DiffOutcome.Side.B));
    }

    /// R-M2-13: a VOID, ERROR or CANCELLED side, A or B, is refused, though its rows are complete.
    @Test
    void unjudgedSide_isRefused_onEitherSide() {
        assertThat(DiffOutcome.decide(side(1, JobState.VOID, some(2), TWO), side(2, JobState.CANCELLED, some(2), TWO)))
            .isEqualTo(refused(DiffObstacle.NOT_JUDGED, DiffOutcome.Side.A, DiffOutcome.Side.B));
        assertThat(DiffOutcome.decide(side(1, JobState.PASSED, some(2), TWO), side(2, JobState.ERROR, some(2), TWO)))
            .isEqualTo(refused(DiffObstacle.NOT_JUDGED, DiffOutcome.Side.B));
    }

    private static DiffSide side(long job, JobState state, Option<Integer> testcases, List<TestcaseRow> rows) {
        return DiffSide.diffSide(JobId.jobId(job).unwrap(), state, testcases.map(DiffOutcomeTest::counts), rows);
    }

    private static Counts counts(int testcases) {
        return Counts.counts(testcases, testcases, 0, 0, 0, testcases, 1, none(), none(), none());
    }

    private static DiffOutcome refused(DiffObstacle obstacle, DiffOutcome.Side... sides) {
        return DiffOutcome.Refused.refused(obstacle, List.of(sides));
    }
}
