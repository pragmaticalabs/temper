package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// Git ref name as submitted. The leading-`-` ban prevents argv injection into git.
public record Ref(String value) {
    private static final Pattern PATTERN = Pattern.compile("^(?!-)[A-Za-z0-9._/-]{1,200}$");
    private static final Pattern FORBIDDEN_SHAPE = Pattern.compile("\\.\\.|//|/$|\\.lock$");
    private static final String FIELD = "ref";

    public static Result<Ref> ref(String raw) {
        return Values.matching(raw, PATTERN, FIELD)
                     .flatMap(value -> Verify.ensure(value,
                                                     Ref::wellShaped,
                                                     invalid(FIELD)))
                     .map(Ref::new);
    }

    private static boolean wellShaped(String value) {
        return ! FORBIDDEN_SHAPE.matcher(value).find();
    }

    @Override
    public String toString() {
        return value;
    }
}
