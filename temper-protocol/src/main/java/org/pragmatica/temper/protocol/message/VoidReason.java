package org.pragmatica.temper.protocol.message;

import com.fasterxml.jackson.annotation.JsonProperty;


/// Why the worker voided a unit while it ran (protocol v2, `UnitExited.voidReason`): `port-group-lost` (the
/// bb-hold holder died while the container ran, m1-design §1.4) or `disk` (free space fell below the run floor,
/// §4.5). The wire form is the `VOID(<reason>)` detail.
public enum VoidReason {
    @JsonProperty("port-group-lost")
    PORT_GROUP_LOST("port-group-lost"),
    @JsonProperty("disk")
    DISK("disk");
    private final String wire;
    VoidReason(String wire) {
        this.wire = wire;
    }
    /// The `VOID(<reason>)` detail and the `.temper/void` file content.
    public String wire() {
        return wire;
    }
}
