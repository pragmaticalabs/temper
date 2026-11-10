package org.pragmatica.temper.domain.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// One `mvn` argv element of an `Mvn` recipe. Content is NOT restricted here: the refusals judge it.
public record MvnArg(String value) {
    public static final int MAX_LENGTH = 4096;
    private static final String FIELD = "mvn argument";

    public static Result<MvnArg> mvnArg(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid(FIELD))
                     .flatMap(present -> Verify.ensure(present,
                                                       Verify.Is::lenBetween,
                                                       1,
                                                       MAX_LENGTH,
                                                       invalid(FIELD)))
                     .flatMap(bounded -> Verify.ensure(bounded,
                                                       Verify.Is::notContains,
                                                       "\u0000",
                                                       invalid(FIELD)))
                     .map(MvnArg::new);
    }

    @Override
    public String toString() {
        return value;
    }
}
