package org.pragmatica.temper.domain.refusal;

import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.model.Values;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A data-driven refusal to add through the management API (spec §2.5, m1-design §6): a rule the domain can
/// evaluate, the issue it waits on, why, and the free-text condition that ends it. Code and matcher arrive
/// together in `rule`, parsed by [#ruleCode] and the matcher's own parser, so a refusal no code can evaluate
/// cannot exist.
public record NewRefusal(RefusalRule rule, Option<Integer> issue, String reason, Option<String> until) {
    /// `R-` plus upper-case letters, digits and dashes.
    public static final Pattern CODE = Pattern.compile("^R-[A-Z0-9-]{1,30}$");

    /// The structural codes are code, not data: they cannot be added or shadowed.
    public static final Set<String> STRUCTURAL = Set.of(RefusalCode.R_ENV.wire(),
                                                        RefusalCode.R_M2.wire(),
                                                        RefusalCode.R_SHELL.wire(),
                                                        RefusalCode.R_DISK.wire());

    /// A code a data-driven refusal may carry.
    public static Result<String> ruleCode(String raw) {
        return Values.matching(raw, CODE, "code").flatMap(code -> Verify.ensure(code,
                                                                                Predicate.not(STRUCTURAL::contains),
                                                                                invalid("code")));
    }

    /// The rule's code must be a [#ruleCode]; `reason` must be present; `issue`, when given, positive; `until`,
    /// when given, not blank.
    public static Result<NewRefusal> newRefusal(RefusalRule rule,
                                                Option<Integer> issue,
                                                String reason,
                                                Option<String> until) {
        return Result.all(ruleCode(rule.code()),
                          Values.optional(issue, NewRefusal::issueNumber),
                          Verify.ensure(reason,
                                        Verify.Is::present,
                                        invalid("reason")),
                          Values.optional(until, NewRefusal::untilText))
                     .map((_, parsedIssue, presentReason, parsedUntil) -> new NewRefusal(rule,
                                                                                         parsedIssue,
                                                                                         presentReason,
                                                                                         parsedUntil));
    }

    /// The rule's code.
    public String code() {
        return rule.code();
    }

    private static Result<Integer> issueNumber(Integer raw) {
        return Verify.ensure(raw, Verify.Is::positive, invalid("issue"));
    }

    private static Result<String> untilText(String raw) {
        return Verify.ensure(raw, Verify.Is::present, invalid("until"));
    }
}
