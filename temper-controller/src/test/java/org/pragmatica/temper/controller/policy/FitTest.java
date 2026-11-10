package org.pragmatica.temper.controller.policy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.store.api.QueuedUnit;

import static org.assertj.core.api.Assertions.assertThat;

/// [Fit]: each fit rule alone fails with its own failure carrying its numbers; a unit that passes them all has none;
/// each rule's boundary is the one [GrantPolicy] documents (`≤ budget`, `≥ floor`, `< maxHolding`), and
/// [GrantPolicy#fits] is exactly "no failure".
class FitTest {
    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;

    /// Budget 20480 MB with 8192 active; free 100 GiB over a 50 GiB floor; port group 0 of 1, not isolated.
    private static HostSnapshot host(int activeMemory, long freeBytes, int portGroupUnits) {
        return HostSnapshot.hostSnapshot(5, 0, 1, 20480, activeMemory, freeBytes, 50 * GIB, false, portGroupUnits, 1);
    }

    private static QueuedUnit unit(int memoryMb, boolean needsPortGroup) {
        return QueuedUnit.queuedUnit(1, -20, T0, memoryMb, needsPortGroup, UnitKind.MVN);
    }

    @Test
    void fit_allRulesPass_noFailures() {
        var fit = Fit.fit(host(8192, 100 * GIB, 0), unit(6144, true));

        assertThat(fit.failures()).isEmpty();
        assertThat(fit.fits()).isTrue();
    }

    @Test
    void fit_memoryAlone_failsWithTheNumbers() {
        var fit = Fit.fit(host(16384, 100 * GIB, 0), unit(6144, true));

        assertThat(fit.failures()).containsExactly(new Fit.Memory(16384, 6144, 20480));
        assertThat(fit.fits()).isFalse();
    }

    @Test
    void fit_diskFloorAlone_failsWithTheNumbers() {
        var fit = Fit.fit(host(8192, 50 * GIB - 1, 0), unit(6144, true));

        assertThat(fit.failures()).containsExactly(new Fit.DiskFloor(50 * GIB - 1, 50 * GIB));
    }

    @Test
    void fit_portGroupAlone_failsWithUnitsAndMaxHolding() {
        var fit = Fit.fit(host(8192, 100 * GIB, 1), unit(6144, true));

        assertThat(fit.failures()).containsExactly(new Fit.PortGroup(1, 1));
    }

    /// Asymmetric numbers, so the failure cannot carry them swapped: 3 units hold or wait, 2 may.
    @Test
    void fit_portGroupOverfull_carriesUnitsThenMaxHolding() {
        var host = HostSnapshot.hostSnapshot(5, 0, 1, 20480, 8192, 100 * GIB, 50 * GIB, false, 3, 2);

        assertThat(Fit.fit(host, unit(6144, true)).failures()).containsExactly(new Fit.PortGroup(3, 2));
    }

    /// A unit that does not go through bb-hold ignores a full port group.
    @Test
    void fit_portGroupFull_unitWithoutPortGroup_fits() {
        assertThat(Fit.fit(host(8192, 100 * GIB, 1), unit(6144, false)).fits()).isTrue();
    }

    @Test
    void fit_everyRuleFails_namesThemAllInRuleOrder() {
        var fit = Fit.fit(host(20480, 0, 1), unit(1, true));

        assertThat(fit.failures()).containsExactly(new Fit.Memory(20480, 1, 20480),
                                                   new Fit.DiskFloor(0, 50 * GIB),
                                                   new Fit.PortGroup(1, 1));
    }

    @Test
    void fit_boundaries_exactBudgetFits_exactFloorFits_belowMaxHoldingFits() {
        assertThat(Fit.fit(host(14336, 100 * GIB, 0), unit(6144, false)).fits()).as("active + unit = budget").isTrue();
        assertThat(Fit.fit(host(14337, 100 * GIB, 0), unit(6144, false)).failures()).as("one MB over")
                                                                                   .containsExactly(new Fit.Memory(14337, 6144, 20480));
        assertThat(Fit.fit(host(8192, 50 * GIB, 0), unit(6144, false)).fits()).as("free = floor").isTrue();
        assertThat(Fit.fit(host(8192, 100 * GIB, 0), unit(6144, true)).fits()).as("0 < maxHolding 1").isTrue();
    }

    /// Budget, disk and port group all pass; MemAvailable `availableMb`, `pendingMb` of temper's units it does not
    /// reflect yet, and a floor of `floorMb`.
    private static HostSnapshot memHost(long availableMb, int pendingMb, long floorMb) {
        return HostSnapshot.hostSnapshot(5, 0, 1, 40960, 0, 100 * GIB, 50 * GIB, false, 0, 1, availableMb, pendingMb, floorMb);
    }

    /// 10240 − 1024 − 4096 = 5120 < 6144. Without the pending term it would be 6144, which fits, so the failure is
    /// the pending memory's doing.
    @Test
    void fit_memFloorBelow_failsWithTheNumbers() {
        var fit = Fit.fit(memHost(10240, 1024, 6144), unit(4096, false));

        assertThat(fit.failures()).containsExactly(new Fit.MemFloor(10240, 1024, 4096, 6144));
        assertThat(fit.fits()).isFalse();
    }

    /// `≥ floor`: landing exactly on the floor fits, one MB under does not.
    @Test
    void fit_memFloorExactlyAtFloor_fits() {
        assertThat(Fit.fit(memHost(11264, 1024, 6144), unit(4096, false)).fits()).as("11264 - 1024 - 4096 = 6144").isTrue();
        assertThat(Fit.fit(memHost(11263, 1024, 6144), unit(4096, false)).failures()).as("one MB under")
                                                                                   .containsExactly(new Fit.MemFloor(11263, 1024, 4096, 6144));
    }

    /// A floor of 0 is off: nothing about memory is read, even with no MemAvailable at all.
    @Test
    void fit_memFloorZero_neverRefuses() {
        assertThat(Fit.fit(memHost(0, 8192, 0), unit(4096, false)).fits()).isTrue();
    }

    /// The unit's own memory counts: free 7 GiB, nothing pending, a 4 GiB unit and a 6 GiB floor leave 3 GiB.
    @Test
    void fit_memFloor_countsTheUnitsOwnMemory() {
        var fit = Fit.fit(memHost(7 * 1024, 0, 6 * 1024), unit(4 * 1024, false));

        assertThat(fit.failures()).containsExactly(new Fit.MemFloor(7 * 1024, 0, 4 * 1024, 6 * 1024));
    }

    /// A unit granted earlier in the round counts as pending for the next one.
    @Test
    void withGranted_addsTheUnitToPendingMemory() {
        assertThat(memHost(10240, 1024, 6144).withGranted(unit(4096, false)).pendingMemoryMb()).isEqualTo(5120);
    }

    @Test
    void grantPolicyFits_isExactlyNoFailure() {
        var hosts = new HostSnapshot[]{host(8192, 100 * GIB, 0), host(14337, 100 * GIB, 0), host(8192, 1, 0), host(8192, 100 * GIB, 1),
                                       memHost(7168, 0, 6144)};

        for (var host : hosts) {
            for (var needsPortGroup : new boolean[]{false, true}) {
                var unit = unit(6144, needsPortGroup);

                assertThat(GrantPolicy.fits(host, unit)).isEqualTo(Fit.fit(host, unit).failures().isEmpty());
            }
        }
    }
}
