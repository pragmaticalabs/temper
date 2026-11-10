package org.pragmatica.temper.store.api;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.state.UnitState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// The short factory, a row without its M4 facts, falls back to V11's fail-safe `external` side effects (m4-design §5:
/// such a unit is never requeued after a host loss), no CPU class and no lost attempts.
class UnitRowTest {
    @Test
    void shortFactory_yieldsExternalSideEffects_noCpuClass_noLostAttempts() {
        var row = UnitRow.unitRow(1, UnitName.MAIN, UnitKind.MVN, UnitState.QUEUED, none(), none(),
                                  List.of(List.of("mvn", "-B", "verify")), true, false, 6144, Duration.ofMinutes(60),
                                  none(), none(), none(), none(), none(), none(), none(), none(), none(), none(), false, none(),
                                  none(), none(), none(), 0);

        assertThat(row.sideEffects()).isEqualTo(SideEffects.EXTERNAL);
        assertThat(row.sharedCpu().isPresent()).isFalse();
        assertThat(row.lostAttempts()).isEmpty();
    }
}
