package org.pragmatica.temper.domain.task;

import java.util.Arrays;
import java.util.Locale;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// The kinds of a NEEDS-RULING request (m3-design §5.5 [P]). Wire and stored form: lower case with `-`
/// (`round-cap`). The kinds temper raises itself are filled by temper with its facts and a `temper-default`
/// recommendation, and a worker or the maintainer cannot raise them ([#isTemperRaised]).
public enum RulingKind {
    DESIGN,
    ROUND_CAP,
    STUCK_LEASE,
    WORKER_FAILURES,
    SCHEMA_UNEXPLAINED,
    SURFACE_CONFLICT,
    CI_ABSENT,
    CI_CANCELLED,
    FOREIGN_PUSH,
    MERGE_CONFLICT,
    AUDIT_MISSED,
    BUDGET;
    public String wire() {
        return name().toLowerCase(Locale.ROOT)
                   .replace('_', '-');
    }
    /// `round-cap`, `stuck-lease`, `worker-failures`, `ci-*`, `foreign-push`, `audit-missed` (§5.5).
    public boolean isTemperRaised() {
        return switch (this) {
            case ROUND_CAP, STUCK_LEASE, WORKER_FAILURES, CI_ABSENT, CI_CANCELLED, FOREIGN_PUSH, AUDIT_MISSED -> true;
            case DESIGN, SCHEMA_UNEXPLAINED, SURFACE_CONFLICT, MERGE_CONFLICT, BUDGET -> false;
        };
    }
    public static Result<RulingKind> rulingKind(String raw) {
        return option(raw).flatMap(RulingKind::byWire)
                     .toResult(invalid("kind").apply(raw));
    }
    private static Option<RulingKind> byWire(String raw) {
        return Option.from(Arrays.stream(values()).filter(kind -> kind.wire()
                                                                      .equals(raw)).findFirst());
    }
}
