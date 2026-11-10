package org.pragmatica.temper.domain.task;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.task.Tasks.T0;
import static org.pragmatica.temper.domain.task.Tasks.applied;

/// m3-design §11.5 `RoundCapTest` (HR2, ruling R-M3-10): with the default cap 3 the task is fixed three times and the
/// fourth failing VERIFY parks `round-cap` with both reports (the last VERIFY and FIX evidence) and the open items; a CI
/// red sends the task to FIX without counting a round.
///
/// Probe: count CI→FIX in `round` (`ciRed_doesNotCountARound` goes red, and the cap is reached a round early).
class RoundCapTest {
    @Test
    void capThree_threeFixVisits_theFourthFailingVerifyParks() {
        var state = created();
        var fixVisits = 0;

        for (var verify = 1; verify <= 3; verify++) {
            state = failVerify(state).state();
            assertThat(state.stage()).isEqualTo(Stage.FIX);
            fixVisits++;
            state = fix(state);
        }
        var fourth = failVerify(state);

        assertThat(fixVisits).isEqualTo(3);
        assertThat(fourth.state().stage()).isEqualTo(Stage.NEEDS_RULING);
        assertThat(fourth.state().counters().round()).isEqualTo(3);
        assertThat(fourth.parked().map(ParkRequest::kind)).isEqualTo(some(RulingKind.ROUND_CAP));
        assertThat(fourth.parked().map(ParkRequest::evidence)).isEqualTo(some(List.of(5L, 4L)));
        assertThat(fourth.parked().map(ParkRequest::items)).isEqualTo(some(List.of("open-1")));
        // Review #7: the offered retry says where a ruling resumes the task: VERIFY, the stage it was raised from.
        assertThat(fourth.state().raisedFrom()).isEqualTo(some(Stage.VERIFY));
        assertThat(fourth.parked().map(request -> request.options().getFirst().text()).unwrap()).contains("VERIFY").doesNotContain("FIX");
    }

    @Test
    void ciRed_doesNotCountARound() {
        var inCi = TaskState.created(3, some(12), T0)
                            .entered(Stage.CI, T0);
        var red = applied(inCi, new Command.CiDecision(new Command.CiOutcome.Red("ci-run-1-1")), T0).state();

        assertThat(red.stage()).isEqualTo(Stage.FIX);
        assertThat(red.counters().round()).isZero();
        assertThat(red.counters().ciRounds()).isEqualTo(1);
    }

    @Test
    void afterCiRed_stillThreeVerifyFixRounds() {
        var inCi = TaskState.created(3, some(12), T0)
                            .entered(Stage.CI, T0);
        var state = fix(applied(inCi, new Command.CiDecision(new Command.CiOutcome.Red("ci-run-1-1")), T0).state());

        for (var verify = 1; verify <= 3; verify++) {
            state = fix(failVerify(state).state());
        }

        assertThat(failVerify(state).state().stage()).isEqualTo(Stage.NEEDS_RULING);
    }

    @Test
    void ruling_withExtraRounds_liftsTheCap() {
        var state = created();

        for (var verify = 1; verify <= 3; verify++) {
            state = fix(failVerify(state).state());
        }
        var parked = failVerify(state).state();
        var ruled = applied(parked, Tasks.rule(some(1)), T0).state();
        var again = failVerify(ruled);

        assertThat(ruled.stage()).isEqualTo(Stage.VERIFY);
        assertThat(again.state().stage()).isEqualTo(Stage.FIX);
        assertThat(failVerify(fix(again.state())).state().stage()).isEqualTo(Stage.NEEDS_RULING);
    }

    /// A task in VERIFY/READY, cap 3, round 0.
    private static TaskState created() {
        return TaskState.created(3, some(12), T0)
                        .entered(Stage.VERIFY, T0);
    }

    private static Transition failVerify(TaskState verifyReady) {
        var leased = applied(verifyReady, Tasks.claim(), T0).state();

        return applied(leased, Tasks.verify(EvidenceOutcome.Verdict.FAIL, List.of("open-1")), T0);
    }

    private static TaskState fix(TaskState fixReady) {
        return applied(applied(fixReady, Tasks.claim(), T0).state(), Tasks.evidence(Stage.VERIFY), T0).state();
    }
}
