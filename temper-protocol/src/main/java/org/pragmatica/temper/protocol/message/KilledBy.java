package org.pragmatica.temper.protocol.message;

import org.pragmatica.temper.domain.verdict.KillCause;

import com.fasterxml.jackson.annotation.JsonProperty;


/// Why the worker killed a container; wire form `timeout` / `cancel` (also `units.killed_by`).
public enum KilledBy {
    @JsonProperty("timeout")
    TIMEOUT,
    @JsonProperty("cancel")
    CANCEL;
    public KillCause toDomain() {
        return switch (this) {
            case TIMEOUT -> KillCause.TIMEOUT;
            case CANCEL -> KillCause.CANCEL;
        };
    }
    public static KilledBy from(KillCause cause) {
        return switch (cause) {
            case TIMEOUT -> TIMEOUT;
            case CANCEL -> CANCEL;
        };
    }
}
