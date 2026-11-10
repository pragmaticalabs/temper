package org.pragmatica.temper.domain.report;

import java.util.Arrays;
import java.util.Locale;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Outcome of one `<testcase>` (spec §5.1). The wire and DB form is lowercase.
public enum Outcome {
    PASSED,
    FAILED,
    ERROR,
    SKIPPED;
    /// Lowercase wire/DB form: `passed`, `failed`, `error`, `skipped`.
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
    /// FAILED or ERROR: counts as an observed failure.
    public boolean isFailure() {
        return this == FAILED || this == ERROR;
    }
    /// Parses the wire form (case-insensitive).
    public static Result<Outcome> outcome(String raw) {
        return option(raw).flatMap(Outcome::byWire)
                     .toResult(invalid("outcome").apply(raw));
    }
    private static Option<Outcome> byWire(String raw) {
        return Option.from(Arrays.stream(values())
                                 .filter(outcome -> outcome.wire()
                                                           .equalsIgnoreCase(raw.trim()))
                                 .findFirst());
    }
}
