package org.pragmatica.temper.domain.exit;

import java.util.List;

import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Verdict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;


/// A3 (enum part): the spec §8.1 numbers, plus 77 for unauthorized (Q-15), alarm-check's 0/2/3, gate's CONFLICT 11
/// (m2-design §1.5), the task group's LEASE_LOST 12 (m3-design §11.2), the CLI's VERSION_SKEW 13 (D-6), and
/// `events --until`'s 3 to 7.
class ExitCodeTest {
    @ParameterizedTest
    @CsvSource({"PASSED,0", "ACCEPTED,0", "FAILED,1", "REFUSED,2", "EMPTY,3", "VOID,4", "ERROR,5", "CANCELLED,6", "WAIT_TIMEOUT,7", "NONE,8", "PENDING,9", "DEDUPLICATED,10", "CONFLICT,11", "LEASE_LOST,12", "VERSION_SKEW,13", "CHECKPOINT_TOO_LARGE,14", "SHA_NOT_ON_BRANCH,15", "MIRROR_UNAVAILABLE,16", "USAGE,64", "UNREACHABLE,69", "UNAUTHORIZED,77", "ALARM_CLEAR,0", "ALARM_UNDELIVERED,2", "ALARM_FIRING,3", "NO_HEARTBEAT,3", "SOURCE_STALE,4", "EVENTS_TIMEOUT,5", "SELF_CHECK_FAILED,6", "EPOCH_CHANGED,7", "MERGE_FAILED,3"})
    void code_pinsSpecTable(ExitCode exitCode, int code) {
        assertThat(exitCode.code()).isEqualTo(code);
    }

    @Test
    void enum_hasNoUnpinnedConstants() {
        assertThat(ExitCode.values()).hasSize(30);
    }

    @Test
    void forVerdict_mapsEveryVerdict() {
        assertThat(ExitCode.forVerdict(new Verdict.Passed())).isEqualTo(ExitCode.PASSED);
        assertThat(ExitCode.forVerdict(new Verdict.Failed("tests"))).isEqualTo(ExitCode.FAILED);
        assertThat(ExitCode.forVerdict(new Verdict.Empty(List.of(UnitName.MAIN)))).isEqualTo(ExitCode.EMPTY);
        assertThat(ExitCode.forVerdict(new Verdict.Void("disk"))).isEqualTo(ExitCode.VOID);
        assertThat(ExitCode.forVerdict(new Verdict.Error("lost"))).isEqualTo(ExitCode.ERROR);
        assertThat(ExitCode.forVerdict(new Verdict.Cancelled())).isEqualTo(ExitCode.CANCELLED);
    }

    @ParameterizedTest
    @CsvSource({"PASSED,PASSED", "FAILED,FAILED", "REFUSED,REFUSED", "EMPTY,EMPTY", "VOID,VOID", "ERROR,ERROR", "CANCELLED,CANCELLED", "QUEUED,PENDING", "RUNNING,PENDING", "CANCELLING,PENDING", "SUPERSEDED,PENDING"})
    void forState_mapsEveryState(JobState state, ExitCode exitCode) {
        assertThat(ExitCode.forState(state)).isEqualTo(exitCode);
    }

    /// Probe: map CONFLICT to FAILED → the CONFLICT row is red (and so is `ExitCodeContractTest`'s gate 11 row).
    @ParameterizedTest
    @CsvSource({"PASSED,PASSED", "FAILED,FAILED", "EMPTY,EMPTY", "CONFLICT,CONFLICT", "NONE,NONE", "RUNNING,PENDING"})
    void forGate_mapsEveryAnswer(GateAnswer answer, ExitCode exitCode) {
        assertThat(ExitCode.forGate(answer)).isEqualTo(exitCode);
    }

    /// m2-design §3.4 (rulings R-M2-9 on Q-M2-6 and R-M2-13). Probes: exit 0 on an added failing test → the `0,1,5` row
    /// is red; exit 0 on an EMPTY B → the `0,0,0` row.
    @ParameterizedTest
    @CsvSource({"0,0,5,PASSED", "1,0,5,FAILED", "0,1,5,FAILED", "2,3,5,FAILED", "0,0,0,FAILED"})
    void forDiff_isZeroOnlyWithNothingNewlyFailing_noAddedFailure_andBTests(int newlyFailing,
                                                                            int addedFailing,
                                                                            int testcasesB,
                                                                            ExitCode exitCode) {
        assertThat(ExitCode.forDiff(newlyFailing, addedFailing, testcasesB)).isEqualTo(exitCode);
    }

    /// 9 only while waiting can change the answer; every other obstacle is 8.
    @ParameterizedTest
    @CsvSource({"NOT_TERMINAL,PENDING", "PRUNED,NONE", "NOT_JUDGED,NONE", "NO_BASE,NONE", "BASE_CONFLICT,NONE"})
    void forDiff_mapsEveryObstacle(DiffObstacle obstacle, ExitCode exitCode) {
        assertThat(ExitCode.forDiff(obstacle)).isEqualTo(exitCode);
    }

    @Test
    void jobState_fromVerdict_isTerminal() {
        assertThat(JobState.fromVerdict(new Verdict.Failed("rc"))).isEqualTo(JobState.FAILED);
        assertThat(JobState.fromVerdict(new Verdict.Empty(List.of(UnitName.MAIN))).isTerminal()).isTrue();
        assertThat(JobState.CANCELLING.isTerminal()).isFalse();
    }
}
