package org.pragmatica.temper.controller.policy;

import java.time.Duration;

import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.ActiveUnit;


/// An active (GRANTED, UNKNOWN, RUNNING, CANCELLING) unit as the store has it, for reconcile. `started` is whether
/// the store knows it started: its start recorded (`UnitStarted` arrived), or UNKNOWN (the start arrived but was not
/// recorded, R-13 amended); m0-design §3.10 adds it so a started unit with nothing on disk is lost rather than
/// requeued.
public record DbUnit(UnitRef ref, UnitState state, Duration timeout, boolean started) {
    public static DbUnit dbUnit(UnitRef ref, UnitState state, Duration timeout, boolean started) {
        return new DbUnit(ref, state, timeout, started);
    }

    public static DbUnit dbUnit(ActiveUnit unit) {
        return new DbUnit(unit.ref(),
                          unit.state(),
                          unit.timeout(),
                          unit.startedAt().isPresent() || unit.state() == UnitState.UNKNOWN);
    }
}
