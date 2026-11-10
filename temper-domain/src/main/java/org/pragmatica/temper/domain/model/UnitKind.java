package org.pragmatica.temper.domain.model;

import java.time.Duration;


/// Kind of unit, with its default wall-clock bound (spec §4.3). M0 plans only `MVN` and `MODULE_TEST`.
public enum UnitKind {
    GATE_UNSHARDED(Duration.ofHours(3)),
    REST(Duration.ofHours(2)),
    EMBER(Duration.ofMinutes(90)),
    BUILD(Duration.ofMinutes(45)),
    MODULE_TEST(Duration.ofMinutes(60)),
    MVN(Duration.ofMinutes(60)),
    CONTROL(Duration.ofMinutes(30)),
    MUTANT(Duration.ofMinutes(30)),
    RERUN(Duration.ofMinutes(30));
    private final Duration defaultTimeout;
    UnitKind(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }
    /// The spec §4.3 default bound for this kind.
    public Duration defaultTimeout() {
        return defaultTimeout;
    }
}
