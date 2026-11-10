package org.pragmatica.temper.store.api;

import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;


/// A GRANTED, UNKNOWN, RUNNING or CANCELLING unit on a host: input to the host snapshot (count, memory, port-group
/// units) and to reconcile. `portGroup` is its hold (`waiting`/`held`, m1-design §1.3), `ticket` the bb-* ticket once
/// granted; `kind` lets the host snapshot count a Gate as a bb-hold unit even on a port-isolated host (ruling R-3).
/// `grantedAt` names the unit's current attempt ([Granted#grantedAt]), and `attempt` is its number ([Granted#attempt]).
public record ActiveUnit(UnitRef ref,
                         long unitId,
                         UnitState state,
                         int memoryMb,
                         Duration timeout,
                         Option<Instant> leaseUntil,
                         Option<Instant> startedAt,
                         boolean needsPortGroup,
                         Option<PortGroup> portGroup,
                         Option<String> ticket,
                         UnitKind kind,
                         Option<Instant> grantedAt,
                         int attempt) {
    public static ActiveUnit activeUnit(UnitRef ref,
                                        long unitId,
                                        UnitState state,
                                        int memoryMb,
                                        Duration timeout,
                                        Option<Instant> leaseUntil,
                                        Option<Instant> startedAt,
                                        boolean needsPortGroup,
                                        Option<PortGroup> portGroup,
                                        Option<String> ticket,
                                        UnitKind kind,
                                        Option<Instant> grantedAt,
                                        int attempt) {
        return new ActiveUnit(ref,
                              unitId,
                              state,
                              memoryMb,
                              timeout,
                              leaseUntil,
                              startedAt,
                              needsPortGroup,
                              portGroup,
                              ticket,
                              kind,
                              grantedAt,
                              attempt);
    }
}
