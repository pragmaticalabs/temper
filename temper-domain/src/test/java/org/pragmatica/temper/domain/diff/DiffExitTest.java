package org.pragmatica.temper.domain.diff;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.report.TestcaseRow;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Counts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.diff.TestDiffTest.row;

/// m2-design §3.5 `DiffExitTest` (ruling R-M2-13 on Q-M2-30): the exit code of a whole diff, from two sides through
/// [DiffOutcome#decide] and [ExitCode#forDiff(DiffOutcome)]. Probes and the test each turns red: skipped → failed as a skip
/// change → [#skippedToFailed_exits1]; skip changes raising the exit → [#failedToSkipped_exits0_withOneSkipChange];
/// exit 0 on an EMPTY B → [#emptyB_exits1]; a VOID side diffed instead of refused → [#unjudgedSide_exits8_onEitherSide].
class DiffExitTest {
    @Test
    void skippedToFailed_exits1() {
        var outcome = DiffOutcome.decide(side(1, JobState.PASSED, List.of(row("a.A", "one", Outcome.SKIPPED))),
                                     side(2, JobState.FAILED, List.of(row("a.A", "one", Outcome.FAILED))));

        assertThat(ExitCode.forDiff(outcome)).isEqualTo(ExitCode.FAILED);
        assertThat(((DiffOutcome.Compared) outcome).diff().skipChanged()).isEmpty();
    }

    @Test
    void failedToSkipped_exits0_withOneSkipChange() {
        var outcome = DiffOutcome.decide(side(1, JobState.FAILED, List.of(row("a.A", "one", Outcome.FAILED), row("a.A", "two", Outcome.PASSED))),
                                     side(2, JobState.PASSED, List.of(row("a.A", "one", Outcome.SKIPPED), row("a.A", "two", Outcome.PASSED))));

        assertThat(ExitCode.forDiff(outcome)).isEqualTo(ExitCode.PASSED);
        assertThat(((DiffOutcome.Compared) outcome).diff().skipChanged()).hasSize(1);
    }

    /// Every A test is removed and nothing fails, yet a side that ran no tests is never a clean diff.
    @Test
    void emptyB_exits1() {
        var outcome = DiffOutcome.decide(side(1, JobState.PASSED, List.of(row("a.A", "one", Outcome.PASSED))),
                                     side(2, JobState.EMPTY, List.of()));

        assertThat(outcome).isInstanceOf(DiffOutcome.Compared.class);
        assertThat(ExitCode.forDiff(outcome)).isEqualTo(ExitCode.FAILED);
    }

    @Test
    void unjudgedSide_exits8_onEitherSide() {
        var rows = List.of(row("a.A", "one", Outcome.PASSED));

        for (var state : List.of(JobState.VOID, JobState.ERROR, JobState.CANCELLED)) {
            var asA = DiffOutcome.decide(side(1, state, rows), side(2, JobState.PASSED, rows));
            var asB = DiffOutcome.decide(side(1, JobState.PASSED, rows), side(2, state, rows));

            assertThat(asA).as(state + " as A").isEqualTo(DiffOutcome.Refused.refused(DiffObstacle.NOT_JUDGED, List.of(DiffOutcome.Side.A)));
            assertThat(asB).as(state + " as B").isEqualTo(DiffOutcome.Refused.refused(DiffObstacle.NOT_JUDGED, List.of(DiffOutcome.Side.B)));
            assertThat(ExitCode.forDiff(asA)).isEqualTo(ExitCode.NONE);
            assertThat(ExitCode.forDiff(asB)).isEqualTo(ExitCode.NONE);
        }
    }

    @Test
    void cleanDiff_exits0_andANotTerminalSide9() {
        var rows = List.of(row("a.A", "one", Outcome.PASSED));

        assertThat(ExitCode.forDiff(DiffOutcome.decide(side(1, JobState.PASSED, rows), side(2, JobState.PASSED, rows)))).isEqualTo(ExitCode.PASSED);
        assertThat(ExitCode.forDiff(DiffOutcome.decide(side(1, JobState.PASSED, rows),
                                                   DiffSide.diffSide(JobId.jobId(2).unwrap(), JobState.RUNNING, none(), rows))))
            .isEqualTo(ExitCode.PENDING);
    }

    /// A terminal side whose record counts exactly its rows.
    private static DiffSide side(long job, JobState state, List<TestcaseRow> rows) {
        return DiffSide.diffSide(JobId.jobId(job).unwrap(), state, some(counts(rows.size())), rows);
    }

    private static Counts counts(int testcases) {
        return Counts.counts(testcases, testcases, 0, 0, 0, testcases, 1, none(), none(), none());
    }
}
