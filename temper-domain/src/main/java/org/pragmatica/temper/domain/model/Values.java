package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Shared parsing steps for the string value objects: a required, pattern-shaped string.
public sealed interface Values {
    /// Parses a required string that must match `pattern` in full. A null or blank input fails as well.
    static Result<String> matching(String raw, Pattern pattern, String field) {
        return Verify.ensure(raw, Verify.Is::present, invalid(field)).flatMap(present -> Verify.ensure(present,
                                                                                                       Verify.Is::matches,
                                                                                                       pattern,
                                                                                                       invalid(field)));
    }

    /// Parses a required string that matches `pattern` and does not contain `forbidden`.
    static Result<String> matchingWithout(String raw, Pattern pattern, String forbidden, String field) {
        return matching(raw, pattern, field).flatMap(value -> Verify.ensure(value,
                                                                            Verify.Is::notContains,
                                                                            forbidden,
                                                                            invalid(field)));
    }

    /// Parses an optional raw value: absent stays absent, present must parse.
    static <T, R> Result<Option<T>> optional(Option<R> raw, Fn1<Result<T>, R> parser) {
        return raw.map(parser)
                  .map(parsed -> parsed.map(Option::some))
                  .or(success(none()));
    }

    record unused() implements Values {}
}
