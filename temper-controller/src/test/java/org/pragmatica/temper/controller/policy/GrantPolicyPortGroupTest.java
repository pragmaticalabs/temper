package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.controller.scheduler.GrantRound;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// A11 unit tier (m1-design §1.3, §9.1, Q-M1-12): a port-group unit is granted while fewer than `maxHolding` temper
/// units wait for or hold the group on the host; on a port-isolated host only a non-Gate unit bypasses that, since an
/// unsharded Gate keeps bb-hold there (m0-design §12.4, ruling R-3). Before M1 the fit was
/// `!needsPortGroup || portIsolated`, so a Gate on a shared host was never granted.
class GrantPolicyPortGroupTest {
    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Duration BOUND = GrantPolicy.STARVATION_BOUND;

    private static HostSnapshot host(boolean portIsolated, int portGroupUnits, int maxHolding) {
        return HostSnapshot.hostSnapshot(5, 0, portGroupUnits, 40960, 0, 100 * GIB, 50 * GIB, portIsolated, portGroupUnits, maxHolding);
    }

    private static QueuedUnit gate(long id) {
        return QueuedUnit.queuedUnit(id, 5, T0, 16384, true, UnitKind.GATE_UNSHARDED);
    }

    /// A non-Gate unit that needs the port group (a module test of `aether/ember`).
    private static QueuedUnit moduleTest(long id) {
        return QueuedUnit.queuedUnit(id, 5, T0, 6144, true, UnitKind.MODULE_TEST);
    }

    private static QueuedUnit plain(long id) {
        return QueuedUnit.queuedUnit(id, 5, T0, 6144, false, UnitKind.MVN);
    }

    @Test
    void choose_grantsPortGroupUnit_noTemperUnitHoldsTheGroup() {
        var decision = GrantPolicy.choose(host(false, 0, 1), List.of(gate(1)), Starvation.NONE, T0, BOUND);

        assertThat(decision.grant()).isEqualTo(some(1L));
    }

    @Test
    void choose_blocksPortGroupUnit_atMaxHolding() {
        var decision = GrantPolicy.choose(host(false, 1, 1), List.of(gate(1)), Starvation.NONE, T0, BOUND);

        assertThat(decision.grant()).isEqualTo(none());
    }

    @Test
    void choose_backfillsUnitWithoutPortGroup_whileTheGroupIsHeld() {
        var decision = GrantPolicy.choose(host(false, 1, 1), List.of(gate(1), plain(2)), Starvation.NONE, T0, BOUND);

        assertThat(decision.grant()).isEqualTo(some(2L));
    }

    @Test
    void choose_grantsBelowAHigherMaxHolding() {
        assertThat(GrantPolicy.choose(host(false, 1, 2), List.of(gate(1)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(some(1L));
        assertThat(GrantPolicy.choose(host(false, 2, 2), List.of(gate(1)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(none());
    }

    @Test
    void choose_portIsolatedHost_nonGateBypassesMaxHolding() {
        var decision = GrantPolicy.choose(host(true, 3, 1), List.of(moduleTest(1)), Starvation.NONE, T0, BOUND);

        assertThat(decision.grant()).isEqualTo(some(1L));
    }

    /// Ruling R-3: a Gate goes through bb-hold even on a port-isolated host, so it counts against `maxHolding` there.
    @Test
    void choose_portIsolatedHost_gateStillCountsAgainstMaxHolding() {
        assertThat(GrantPolicy.choose(host(true, 1, 1), List.of(gate(1)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(none());
        assertThat(GrantPolicy.choose(host(true, 0, 1), List.of(gate(1)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(some(1L));
    }

    /// Ruling R-3 within one tick: on a port-isolated host the first Gate takes the slot, so the second is not granted,
    /// while a non-Gate port-group unit is.
    @Test
    void grantRound_portIsolatedHost_grantsOneGatePerSlot_withinOneTick() {
        var round = GrantRound.grantRound(host(true, 0, 1), new AtomicReference<>(Starvation.NONE), T0, BOUND);
        var queue = new ArrayList<>(List.of(gate(1), gate(2), moduleTest(3)));
        var granted = new ArrayList<Long>();

        for (var attempt = 0; attempt < 3; attempt++) {
            round.apply(List.copyOf(queue))
                 .grant()
                 .onPresent(id -> {
                     granted.add(id);
                     queue.removeIf(unit -> unit.unitId() == id);
                 });
        }

        assertThat(granted).containsExactly(1L, 3L);
    }

    /// The memory floor mirrors the budget's in-round accumulation: the round's snapshot carries each grant as pending
    /// memory, so two units that fit the floor only together are never both granted within one tick. MemAvailable
    /// 10240, floor 4096: one 4096 unit leaves 6144, a second would leave 2048.
    @Test
    void grantRound_memFloor_grantsOneOfTwoUnitsThatFitOnlyTogether() {
        var host = HostSnapshot.hostSnapshot(5, 0, 0, 40960, 0, 100 * GIB, 50 * GIB, false, 0, 1, 10240, 0, 4096);
        var round = GrantRound.grantRound(host, new AtomicReference<>(Starvation.NONE), T0, BOUND);
        var queue = new ArrayList<>(List.of(plainOf(1, 4096), plainOf(2, 4096)));
        var granted = new ArrayList<Long>();

        for (var attempt = 0; attempt < 2; attempt++) {
            round.apply(List.copyOf(queue))
                 .grant()
                 .onPresent(id -> {
                     granted.add(id);
                     queue.removeIf(unit -> unit.unitId() == id);
                 });
        }

        assertThat(granted).containsExactly(1L);
    }

    private static QueuedUnit plainOf(long id, int memoryMb) {
        return QueuedUnit.queuedUnit(id, -20, T0, memoryMb, false, UnitKind.MVN);
    }

    @Test
    void holdsPortGroup_matchesTheLauncherRouting() {
        assertThat(HostSnapshot.holdsPortGroup(UnitKind.GATE_UNSHARDED, true, true)).isTrue();
        assertThat(HostSnapshot.holdsPortGroup(UnitKind.GATE_UNSHARDED, true, false)).isTrue();
        assertThat(HostSnapshot.holdsPortGroup(UnitKind.MODULE_TEST, true, false)).isTrue();
        assertThat(HostSnapshot.holdsPortGroup(UnitKind.MODULE_TEST, true, true)).isFalse();
        assertThat(HostSnapshot.holdsPortGroup(UnitKind.GATE_UNSHARDED, false, false)).isFalse();
    }

    /// The grant round updates its snapshot per grant, so two port-group units queued together never both pass a
    /// `maxHolding = 1` check within one tick.
    @Test
    void grantRound_grantsOnePortGroupUnitPerSlot_withinOneTick() {
        var round = GrantRound.grantRound(host(false, 0, 1), new AtomicReference<>(Starvation.NONE), T0, BOUND);
        var queue = new ArrayList<>(List.of(gate(1), gate(2), plain(3)));
        var granted = new ArrayList<Long>();

        for (var attempt = 0; attempt < 3; attempt++) {
            round.apply(List.copyOf(queue))
                 .grant()
                 .onPresent(id -> {
                     granted.add(id);
                     queue.removeIf(unit -> unit.unitId() == id);
                 });
        }

        assertThat(granted).containsExactly(1L, 3L);
    }
}
