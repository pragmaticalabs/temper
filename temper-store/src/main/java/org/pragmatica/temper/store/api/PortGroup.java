package org.pragmatica.temper.store.api;

import java.util.Arrays;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A unit's port-group hold (`units.port_group`, m1-design §1.3): `WAITING` while bb-hold waits for the group,
/// `HELD` once granted. A unit without a hold has none.
public enum PortGroup {
    WAITING("waiting"),
    HELD("held");
    private final String wire;
    PortGroup(String wire) {
        this.wire = wire;
    }
    /// The column value and the `UnitView.portGroup` text.
    public String wire() {
        return wire;
    }
    public static Result<PortGroup> portGroup(String wire) {
        return Option.from(Arrays.stream(values()).filter(state -> state.wire.equals(wire)).findFirst()).toResult(invalid("port group").apply(wire));
    }
}
