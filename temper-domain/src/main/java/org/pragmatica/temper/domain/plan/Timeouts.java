package org.pragmatica.temper.domain.plan;

import java.time.Duration;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitTimeout;


/// Unit wall-clock bounds (spec §4.3).
public sealed interface Timeouts {
    /// The default bound per unit kind (the table lives on [UnitKind]).
    static Duration defaultFor(UnitKind kind) {
        return kind.defaultTimeout();
    }

    /// The per-submit override when present, otherwise the kind's default.
    static Duration effective(UnitKind kind, Option<UnitTimeout> override) {
        return override.map(UnitTimeout::value)
                       .or(() -> defaultFor(kind));
    }

    record unused() implements Timeouts {}
}
