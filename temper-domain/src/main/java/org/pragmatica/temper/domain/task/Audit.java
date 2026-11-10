package org.pragmatica.temper.domain.task;

import java.util.Arrays;
import java.util.Locale;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// The spot-audit flag of a task (m3-design §7.7, set by WP-2): `pending` blocks T15. Stored lower case (`tasks.audit`).
public enum Audit {
    NONE,
    PENDING,
    CAUGHT,
    MISSED;
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
    public static Result<Audit> audit(String raw) {
        return option(raw).flatMap(Audit::byWire)
                     .toResult(invalid("audit").apply(raw));
    }
    private static Option<Audit> byWire(String raw) {
        return Option.from(Arrays.stream(values()).filter(audit -> audit.wire()
                                                                        .equals(raw)).findFirst());
    }
}
