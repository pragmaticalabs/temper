package org.pragmatica.temper.domain.model;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// Scheduling priority; higher runs first. Absent means the phase-A default of -20 (spec §6.3).
public record Priority(int value) {
    public static final int DEFAULT = -20;
    public static final int MIN = -1000;
    public static final int MAX = 1000;

    public static Result<Priority> priority(Option<Integer> raw) {
        return Verify.ensure(raw.or(DEFAULT),
                             Verify.Is::between,
                             MIN,
                             MAX,
                             invalid("priority"))
                     .map(Priority::new);
    }
}
