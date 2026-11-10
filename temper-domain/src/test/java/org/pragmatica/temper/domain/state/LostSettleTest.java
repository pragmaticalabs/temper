package org.pragmatica.temper.domain.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.pragmatica.temper.domain.model.SideEffects;

import static org.assertj.core.api.Assertions.assertThat;

/// m4-design §2.3: rows L1–L4 with the bound at 1 (R-M4-13), and their precedence.
class LostSettleTest {
    @ParameterizedTest
    @EnumSource(value = UnitState.class, names = {"GRANTED", "UNKNOWN", "RUNNING"})
    void settle_requeues_aFirstLoss_withoutSideEffects(UnitState state) {
        assertThat(LostSettle.settle(state, SideEffects.NONE, 0)).isEqualTo(LostSettle.Outcome.REQUEUED);
    }

    @ParameterizedTest
    @EnumSource(value = UnitState.class, names = {"GRANTED", "UNKNOWN", "RUNNING"})
    void settle_ends_aSecondLoss_atTheBound(UnitState state) {
        assertThat(LostSettle.settle(state, SideEffects.NONE, 1)).isEqualTo(LostSettle.Outcome.BOUND);
    }

    @ParameterizedTest
    @EnumSource(value = UnitState.class, names = {"GRANTED", "UNKNOWN", "RUNNING"})
    void settle_neverRequeues_anExternalUnit(UnitState state) {
        assertThat(LostSettle.settle(state, SideEffects.EXTERNAL, 0)).isEqualTo(LostSettle.Outcome.EXTERNAL);
    }

    @Test
    void settle_cancels_aCancellingUnit() {
        assertThat(LostSettle.settle(UnitState.CANCELLING, SideEffects.NONE, 0)).isEqualTo(LostSettle.Outcome.CANCELLED);
    }

    /// L1 before L2: a cancel already asked for the end the unit gets.
    @Test
    void settle_cancelling_winsOverExternal() {
        assertThat(LostSettle.settle(UnitState.CANCELLING, SideEffects.EXTERNAL, 1)).isEqualTo(LostSettle.Outcome.CANCELLED);
    }

    /// L2 before L3: both end ERROR(host-lost); the outcome names the side effects.
    @Test
    void settle_external_winsOverTheBound() {
        assertThat(LostSettle.settle(UnitState.RUNNING, SideEffects.EXTERNAL, 1)).isEqualTo(LostSettle.Outcome.EXTERNAL);
    }

    @Test
    void bound_isOneRequeue() {
        assertThat(LostSettle.REQUEUE_BOUND).isEqualTo(1);
    }
}
