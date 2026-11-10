package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.refusal.NewRefusal;
import org.pragmatica.temper.domain.refusal.RefusalRule;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// `POST /api/v1/refusals` body (m1-design §6). Parsing gives a [NewRefusal] or a 400: a code outside
/// `^R-[A-Z0-9-]{1,30}$` or naming a structural refusal (`R-ENV`, `R-M2`, `R-SHELL`, `R-DISK`), a matcher of an
/// unknown kind (Jackson fails the body) or one that does not parse, a blank reason. A code that already exists
/// is the store's 409.
public record RefusalAddRequest(String code,
                                RefusalMatcherDto matcher,
                                Option<Integer> issue,
                                String reason,
                                Option<String> until) {
    public static RefusalAddRequest refusalAddRequest(String code,
                                                      RefusalMatcherDto matcher,
                                                      Option<Integer> issue,
                                                      String reason,
                                                      Option<String> until) {
        return new RefusalAddRequest(code, matcher, issue, reason, until);
    }

    public Result<NewRefusal> toDomain() {
        return NewRefusal.ruleCode(code)
                         .flatMap(this::rule)
                         .flatMap(rule -> NewRefusal.newRefusal(rule, issue, reason, until));
    }

    /// The matcher, parsed with the validated code.
    private Result<RefusalRule> rule(String validCode) {
        return option(matcher).toResult(invalid("matcher").apply("missing"))
                     .flatMap(present -> present.toDomain(validCode));
    }
}
