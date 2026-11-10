package org.pragmatica.temper.store.api;

import java.util.Arrays;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A kind of maintenance run (`maintenance_runs.kind`): the nightly `pg_dump` (m1-design §3.4).
public enum MaintenanceKind {
    PG_DUMP("pg_dump");
    private final String wire;
    MaintenanceKind(String wire) {
        this.wire = wire;
    }
    public String wire() {
        return wire;
    }
    public static Result<MaintenanceKind> maintenanceKind(String wire) {
        return Option.from(Arrays.stream(values()).filter(kind -> kind.wire.equals(wire)).findFirst()).toResult(invalid("maintenance kind").apply(wire));
    }
}
