package org.pragmatica.temper.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Failures of the pure domain: value-object parsing and plan construction.
///
/// Every value-object factory reports through [InvalidValue], so a caller (the HTTP layer) renders one
/// `400 {error}` shape, while the offending field and raw text stay typed for the response.
public sealed interface DomainError extends Cause {
    /// A raw input that does not parse into its value object.
    record InvalidValue(String field, String raw, String message) implements DomainError, Cause.Terminal {
        public static final Fn2<InvalidValue, String, String> FACTORY = Causes.forTwoValues("Invalid %s: '%s'",
                                                                                            InvalidValue::new);
    }

    /// A raw input whose JDK parse failed (duration, number); carries the parser's own failure as `origin`.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unparseable(String field, String raw, Cause origin, String message) implements DomainError, Cause.Wrapped, Cause.Terminal {
        public static final Fn3<Unparseable, String, String, Cause> FACTORY = Causes.forThreeValues("Invalid %s: '%s'",
                                                                                                    Unparseable::new);
    }

    /// Translator for a failed JDK parse of `raw`: keeps the parser's cause as the origin.
    static Fn1<Unparseable, Cause> unparseable(String field, String raw) {
        return origin -> Unparseable.FACTORY.apply(field, raw, origin);
    }

    /// Cause provider for [org.pragmatica.lang.Verify] chains: renders the offending value into an [InvalidValue].
    static Fn1<InvalidValue, Object> invalid(String field) {
        return raw -> InvalidValue.FACTORY.apply(field, String.valueOf(raw));
    }
}
