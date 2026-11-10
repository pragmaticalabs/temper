package org.pragmatica.temper.domain.model;

import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// Full 40-hex git commit SHA, lowercased. Short SHAs are refused (Q-9 ruling).
public record Sha(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[0-9a-f]{40}$");

    public static Result<Sha> sha(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid("sha"))
                     .map(Sha::lowercase)
                     .flatMap(lower -> Values.matching(lower, PATTERN, "sha"))
                     .map(Sha::new);
    }

    /// The 7-character prefix used in one-line CLI and event output.
    public String shortForm() {
        return value.substring(0, 7);
    }

    private static String lowercase(String raw) {
        return raw.trim()
                  .toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
