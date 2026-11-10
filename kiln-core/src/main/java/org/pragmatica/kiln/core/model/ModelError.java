package org.pragmatica.kiln.core.model;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Failures of kiln's value objects. Every factory reports through [InvalidValue], so the offending field and its raw
/// text stay typed for the caller (a decoder names the field, the config loader the key).
public sealed interface ModelError extends Cause {
    /// A raw input that does not parse into its value object.
    record InvalidValue(String field, String raw, String message) implements ModelError, Cause.Terminal {
        public static final Fn2<InvalidValue, String, String> FACTORY = Causes.forTwoValues("Invalid %s: '%s'",
                                                                                            InvalidValue::new);
    }

    /// Cause provider for [org.pragmatica.lang.Verify] chains: renders the offending value into an [InvalidValue].
    static Fn1<InvalidValue, Object> invalid(String field) {
        return raw -> InvalidValue.FACTORY.apply(field, String.valueOf(raw));
    }
}
