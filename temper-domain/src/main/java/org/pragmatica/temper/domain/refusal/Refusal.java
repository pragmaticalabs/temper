package org.pragmatica.temper.domain.refusal;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// A refused submit (or, for `R-DISK`, a refused unit start). `code` is the wire code: a [RefusalCode] for
/// the structural refusals, or the `refusals` row's code for data-driven ones. A refusal is a settled
/// verdict on the request, so it is terminal: no retry changes it.
public record Refusal(String code, String detail, String message) implements Cause, Cause.Terminal {
    public static final Fn2<Refusal, String, String> FACTORY = Causes.forTwoValues("REFUSED(%s): %s", Refusal::new);

    public static Refusal refusal(RefusalCode code, String detail) {
        return FACTORY.apply(code.wire(), detail);
    }

    /// A refusal raised by a data-driven rule, carrying the row's code.
    public static Refusal refusal(String code, String detail) {
        return FACTORY.apply(code, detail);
    }
}
