package org.pragmatica.temper.domain.hold;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/// A11 unit tier (m1-design §1.4, §9.1): the full table over (earlier holder, later holder, container). The one
/// VOID row is two DEAD observations with the container RUNNING; every other row is NONE.
class HoldRulesTest {
    static Stream<Arguments> everyObservation() {
        return Arrays.stream(HolderStatus.values())
                     .flatMap(earlier -> Arrays.stream(HolderStatus.values())
                                               .flatMap(later -> Arrays.stream(ContainerState.values())
                                                                       .map(container -> Arguments.of(earlier, later, container))));
    }

    @ParameterizedTest(name = "{0} then {1}, container {2}")
    @MethodSource("everyObservation")
    void decide_voidsOnlyForTwoDeadObservationsOfARunningContainer(HolderStatus earlier,
                                                                    HolderStatus later,
                                                                    ContainerState container) {
        var expected = earlier == HolderStatus.DEAD && later == HolderStatus.DEAD && container == ContainerState.RUNNING
                       ? HoldAction.VOID_PORT_GROUP_LOST
                       : HoldAction.NONE;

        assertThat(HoldRules.decide(earlier, later, container)).isEqualTo(expected);
    }

    @Test
    void decide_singleDeadObservation_isNotEnough() {
        assertThat(HoldRules.decide(HolderStatus.HELD, HolderStatus.DEAD, ContainerState.RUNNING)).isEqualTo(HoldAction.NONE);
        assertThat(HoldRules.decide(HolderStatus.DEAD, HolderStatus.HELD, ContainerState.RUNNING)).isEqualTo(HoldAction.NONE);
    }

        /// A clean exit as the caller observes it when it reads the holder first: by the time the holder reads DEAD
    /// the container has exited or gone. The read order itself is the caller's precondition, pinned by WP4's
    /// `HoldMonitorTest`; this pure function can only refuse to void a container that is not running.
    @Test
    void decide_isNone_whenTheContainerExitedOrIsAbsent() {
        assertThat(HoldRules.decide(HolderStatus.HELD, HolderStatus.DEAD, ContainerState.EXITED)).isEqualTo(HoldAction.NONE);
        assertThat(HoldRules.decide(HolderStatus.DEAD, HolderStatus.DEAD, ContainerState.EXITED)).isEqualTo(HoldAction.NONE);
        assertThat(HoldRules.decide(HolderStatus.DEAD, HolderStatus.DEAD, ContainerState.ABSENT)).isEqualTo(HoldAction.NONE);
    }

    @Test
    void decide_deadHolderOfARunningContainer_voids() {
        assertThat(HoldRules.decide(HolderStatus.DEAD, HolderStatus.DEAD, ContainerState.RUNNING))
            .isEqualTo(HoldAction.VOID_PORT_GROUP_LOST);
    }
}
