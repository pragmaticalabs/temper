package org.pragmatica.kiln.core.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// A required check's context, as GitHub names it in `required_status_checks.checks[].context`: a check run's `name`
/// or a commit status's `context`. Kept verbatim (it is matched by equality, spec §4.4); only a blank one, or one holding
/// a control character ([Printable]), is refused.
public record CheckContext(String value) {
    public static Result<CheckContext> checkContext(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid("check context"))
                     .flatMap(present -> Verify.ensure(present,
                                                       Verify.Is::matches,
                                                       Printable.TEXT,
                                                       invalid("check context")))
                     .map(CheckContext::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
