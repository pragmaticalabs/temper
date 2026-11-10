package org.pragmatica.temper.domain.model;

import java.time.Duration;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.DateTime;

import static org.pragmatica.temper.domain.DomainError.invalid;
import static org.pragmatica.temper.domain.DomainError.unparseable;


/// Per-submit unit wall-clock bound (Q-6: the top-level `timeout` field, ISO-8601). The bounds are a guess
/// (m0-design §3.1): at least 1 minute so A10 can use `PT60S`, at most 6 hours.
public record UnitTimeout(Duration value) {
    public static final Duration MIN = Duration.ofMinutes(1);
    public static final Duration MAX = Duration.ofHours(6);
    private static final String FIELD = "timeout";

    public static Result<UnitTimeout> unitTimeout(String iso) {
        return Verify.ensure(iso,
                             Verify.Is::present,
                             invalid(FIELD))
                     .flatMap(UnitTimeout::isoDuration)
                     .flatMap(duration -> Verify.ensure(duration,
                                                        Verify.Is::between,
                                                        MIN,
                                                        MAX,
                                                        invalid(FIELD)))
                     .map(UnitTimeout::new);
    }

    private static Result<Duration> isoDuration(String text) {
        return DateTime.parseDuration(text.trim()).mapError(unparseable(FIELD, text));
    }
}
