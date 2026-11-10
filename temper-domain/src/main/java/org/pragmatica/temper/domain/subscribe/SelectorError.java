package org.pragmatica.temper.domain.subscribe;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Failures of the CLI's selector classification ([Selector#classified]) that are not a value-object parse failure.
public sealed interface SelectorError extends Cause {
    /// 7 to 39 hex: a short SHA matches no event, whose payloads carry 40 hex (F3), and it may be meant as a ref.
    record AmbiguousShortSha(String raw, String message) implements SelectorError, Cause.Terminal {
        public static final Fn1<AmbiguousShortSha, String> FACTORY = Causes.forOneValue("Ambiguous selector '%s': a short SHA matches no event; use sha:<full 40 hex> or ref:<name>",
                                                                                        AmbiguousShortSha::new);
    }
}
