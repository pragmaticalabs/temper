package org.pragmatica.temper.controller.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.ActiveUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// [Scheduler#pendingMemoryMb]: the memory floor's pending term counts an active unit until the facts were sampled
/// after its recorded start (both instants on the worker's clock), so a unit whose start the latest sample predates is
/// counted as pending rather than assumed to be in MemAvailable. The comparison is only as good as those two instants:
/// the cases it misjudges are listed on [org.pragmatica.temper.controller.policy.HostSnapshot].
class PendingMemoryTest {
    private static final Instant FACTS_AT = Instant.parse("2026-11-02T10:00:00Z");

    private static ActiveUnit unit(long id, UnitState state, int memoryMb, Option<Instant> startedAt) {
        return ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(id).unwrap(), UnitName.MAIN),
                                     id,
                                     state,
                                     memoryMb,
                                     Duration.ofMinutes(60),
                                     none(),
                                     startedAt,
                                     false,
                                     none(),
                                     none(),
                                     UnitKind.MVN,
                                     some(FACTS_AT.minusSeconds(60)),
                                     1);
    }

    @Test
    void pendingMemoryMb_grantedUnit_counts() {
        assertThat(Scheduler.pendingMemoryMb(List.of(unit(1, UnitState.GRANTED, 4096, none())), FACTS_AT)).isEqualTo(4096);
    }

    /// Facts sampled after the start reflect the unit: it is no longer pending.
    @Test
    void pendingMemoryMb_unitStartedBeforeTheFacts_doesNotCount() {
        assertThat(Scheduler.pendingMemoryMb(List.of(unit(1, UnitState.RUNNING, 4096, some(FACTS_AT.minusMillis(1)))),
                                             FACTS_AT)).isZero();
    }

    /// A start recorded at or after the sample is not in it: the unit is still pending though RUNNING.
    @Test
    void pendingMemoryMb_unitStartedAtOrAfterTheFacts_counts() {
        assertThat(Scheduler.pendingMemoryMb(List.of(unit(1, UnitState.RUNNING, 4096, some(FACTS_AT))), FACTS_AT)).isEqualTo(4096);
        assertThat(Scheduler.pendingMemoryMb(List.of(unit(1, UnitState.RUNNING, 4096, some(FACTS_AT.plusSeconds(1)))),
                                             FACTS_AT)).isEqualTo(4096);
    }

    @Test
    void pendingMemoryMb_sumsOnlyTheUnreflectedUnits() {
        var active = List.of(unit(1, UnitState.GRANTED, 4096, none()),
                             unit(2, UnitState.RUNNING, 6144, some(FACTS_AT.minusSeconds(5))),
                             unit(3, UnitState.RUNNING, 2048, some(FACTS_AT.plusSeconds(5))));

        assertThat(Scheduler.pendingMemoryMb(active, FACTS_AT)).isEqualTo(6144);
    }
}
