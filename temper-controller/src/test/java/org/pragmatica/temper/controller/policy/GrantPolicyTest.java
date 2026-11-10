package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class GrantPolicyTest {
    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Duration BOUND = GrantPolicy.STARVATION_BOUND;

    private static HostSnapshot host(int cap, int foreign, int active, int activeMemory) {
        return HostSnapshot.hostSnapshot(cap, foreign, active, 20480, activeMemory, 100 * GIB, 50 * GIB, false, 0, 1);
    }

    private static QueuedUnit unit(long id, int memoryMb) {
        return QueuedUnit.queuedUnit(id, -20, T0, memoryMb, false, UnitKind.MVN);
    }

    @Nested
    class Cap {
        @Test
        void choose_grantsNothing_capOneAndOneForeignReactor() {
            var decision = GrantPolicy.choose(host(1, 1, 0, 0), List.of(unit(1, 6144)), Starvation.NONE, T0, BOUND);

            assertThat(decision.grant()).isEqualTo(none());
        }

        @Test
        void choose_grantsNothing_capReachedByForeignPlusActive() {
            var decision = GrantPolicy.choose(host(5, 3, 2, 0), List.of(unit(1, 6144)), Starvation.NONE, T0, BOUND);

            assertThat(decision.grant()).isEqualTo(none());
        }

        @Test
        void choose_keepsStarvation_whenCapped() {
            var starving = Starvation.starvation(some(7L), some(T0));
            var decision = GrantPolicy.choose(host(1, 1, 0, 0), List.of(unit(7, 99999)), starving, T0, BOUND);

            assertThat(decision.next()).isEqualTo(starving);
        }

        @Test
        void choose_grantsHead_belowCap() {
            var decision = GrantPolicy.choose(host(5, 3, 1, 0), List.of(unit(1, 6144)), Starvation.NONE, T0, BOUND);

            assertThat(decision.grant()).isEqualTo(some(1L));
            assertThat(decision.next()).isEqualTo(Starvation.NONE);
        }
    }

    @Nested
    class Fit {
        @Test
        void choose_grantsNothing_belowDiskFloor() {
            var low = HostSnapshot.hostSnapshot(5, 0, 0, 20480, 0, 10 * GIB, 50 * GIB, false, 0, 1);

            assertThat(GrantPolicy.choose(low, List.of(unit(1, 512)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(none());
        }

        @Test
        void fits_atExactBudget() {
            assertThat(GrantPolicy.fits(host(5, 0, 1, 14336), unit(1, 6144))).isTrue();
            assertThat(GrantPolicy.fits(host(5, 0, 1, 14337), unit(1, 6144))).isFalse();
        }
    }

    @Nested
    class BackfillAndStarvation {
        private final List<QueuedUnit> queue = List.of(unit(1, 16000), unit(2, 4000), unit(3, 1000));
        private final HostSnapshot busy = host(5, 0, 1, 8000);

        @Test
        void choose_backfillsFirstFittingUnit_headBlocked() {
            var decision = GrantPolicy.choose(busy, queue, Starvation.NONE, T0, BOUND);

            assertThat(decision.grant()).isEqualTo(some(2L));
            assertThat(decision.next()).isEqualTo(Starvation.starvation(some(1L), some(T0)));
        }

        @Test
        void choose_keepsFirstBlockedTime_sameHead() {
            var first = GrantPolicy.choose(busy, queue, Starvation.NONE, T0, BOUND);
            var later = GrantPolicy.choose(busy, queue, first.next(), T0.plusSeconds(600), BOUND);

            assertThat(later.next()).isEqualTo(Starvation.starvation(some(1L), some(T0)));
            assertThat(later.grant()).isEqualTo(some(2L));
        }

        @Test
        void choose_stillBackfills_atExactlyTheBound() {
            var starving = Starvation.starvation(some(1L), some(T0));

            assertThat(GrantPolicy.choose(busy, queue, starving, T0.plusSeconds(1200), BOUND).grant()).isEqualTo(some(2L));
        }

        @Test
        void choose_stopsBackfill_after1200Seconds() {
            var starving = Starvation.starvation(some(1L), some(T0));
            var decision = GrantPolicy.choose(busy, queue, starving, T0.plusSeconds(1201), BOUND);

            assertThat(decision.grant()).isEqualTo(none());
            assertThat(decision.next()).isEqualTo(starving);
        }

        @Test
        void choose_restartsClock_newHead() {
            var otherHead = Starvation.starvation(some(99L), some(T0));
            var decision = GrantPolicy.choose(busy, queue, otherHead, T0.plusSeconds(5000), BOUND);

            assertThat(decision.grant()).isEqualTo(some(2L));
            assertThat(decision.next()).isEqualTo(Starvation.starvation(some(1L), some(T0.plusSeconds(5000))));
        }

        @Test
        void choose_grantsStarvedHead_onceItFits() {
            var starving = Starvation.starvation(some(1L), some(T0));
            var decision = GrantPolicy.choose(host(5, 0, 0, 0), queue, starving, T0.plusSeconds(5000), BOUND);

            assertThat(decision.grant()).isEqualTo(some(1L));
            assertThat(decision.next()).isEqualTo(Starvation.NONE);
        }

        @Test
        void choose_resetsStarvation_emptyQueue() {
            var starving = Starvation.starvation(some(1L), some(T0));

            assertThat(GrantPolicy.choose(busy, List.of(), starving, T0, BOUND).next()).isEqualTo(Starvation.NONE);
        }
    }

    /// R-M4-7 (m4-design §8): cloudbb-3/4 at cap 3 and `memoryBudgetMb` 18432, with the default unit (6144 MB) and Gate
    /// (16384 MB) memory. No new rule: the existing cap and fit decide every row.
    @Nested
    class CloudLimits {
        private static HostSnapshot cloud(int foreign, int active, int activeMemory) {
            return HostSnapshot.hostSnapshot(3, foreign, active, 18432, activeMemory, 100 * GIB, 50 * GIB, false, 0, 1);
        }

        private static QueuedUnit gate(long id) {
            return QueuedUnit.queuedUnit(id, -20, T0, 16384, false, UnitKind.GATE_UNSHARDED);
        }

        /// Three ordinary units fit exactly (3 × 6144 = 18432); a fourth is stopped by the cap.
        @Test
        void choose_threeUnitsFitExactly_aFourthIsCapped() {
            var host = cloud(0, 0, 0);

            for (var id = 1L; id <= 3; id++) {
                assertThat(GrantPolicy.choose(host, List.of(unit(id, 6144)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(some(id));
                host = host.withGranted(unit(id, 6144));
            }
            assertThat(host.activeMemoryMb()).isEqualTo(18432);
            assertThat(GrantPolicy.choose(host, List.of(unit(4, 6144)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(none());
        }

        /// A 16 GB Gate fits alone (16384 ≤ 18432) ...
        @Test
        void choose_gateFitsAlone() {
            assertThat(GrantPolicy.choose(cloud(0, 0, 0), List.of(gate(1)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(some(1L));
        }

        /// ... but not beside one ordinary unit (6144 + 16384 = 22528 > 18432), and no unit fits beside a Gate.
        @Test
        void choose_gateNeverBesideAnotherUnit() {
            assertThat(GrantPolicy.choose(cloud(0, 1, 6144), List.of(gate(1)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(none());
            assertThat(GrantPolicy.choose(cloud(0, 1, 16384), List.of(unit(2, 6144)), Starvation.NONE, T0, BOUND).grant()).isEqualTo(none());
        }

        /// F12, R-M4-18: three busy bb reactors fill the cap, so temper gets zero slots, and the blocked head starts no
        /// starvation clock (rule 1 keeps the state as it was).
        @Test
        void choose_threeForeignReactors_zeroSlots_noStarvationClock() {
            var decision = GrantPolicy.choose(cloud(3, 0, 0), List.of(unit(1, 6144)), Starvation.NONE, T0, BOUND);

            assertThat(decision.grant()).isEqualTo(none());
            assertThat(decision.next()).isEqualTo(Starvation.NONE);
        }
    }
}
