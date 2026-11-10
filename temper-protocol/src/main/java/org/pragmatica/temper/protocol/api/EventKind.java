package org.pragmatica.temper.protocol.api;

import com.fasterxml.jackson.annotation.JsonProperty;


/// Event kinds (spec §8.3); the wire and `events.kind` form is lowercase with a dash.
public enum EventKind {
    @JsonProperty("queued")
    QUEUED("queued"),
    @JsonProperty("started")
    STARTED("started"),
    /// A unit runs but its start could not be recorded: it is UNKNOWN (ruling R-13, amended).
    @JsonProperty("start-unknown")
    START_UNKNOWN("start-unknown"),
    @JsonProperty("unit-finished")
    UNIT_FINISHED("unit-finished"),
    @JsonProperty("finished")
    FINISHED("finished"),
    @JsonProperty("cancelled")
    CANCELLED("cancelled"),
    @JsonProperty("superseded")
    SUPERSEDED("superseded"),
    /// A unit's attempt was lost with its host: requeued, or ended `ERROR(host-lost)` (m4-design §2.3).
    @JsonProperty("unit-lost")
    UNIT_LOST("unit-lost");
    private final String wire;
    EventKind(String wire) {
        this.wire = wire;
    }
    public String wire() {
        return wire;
    }
    /// The kinds `watch --terminal` delivers.
    public boolean isTerminal() {
        return this == FINISHED || this == CANCELLED;
    }
}
