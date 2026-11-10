package org.pragmatica.temper.domain.model;

import java.util.Locale;
import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.temper.domain.DomainError.invalid;


/// A commit SHA or a prefix of one, 7 to 40 hex digits, lowercased (m2-design §1.4: `gate <sha>`). A full SHA is a
/// prefix of itself, so `gate` takes either; which commits a prefix names is the store's answer, never a guess.
public record ShaPrefix(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[0-9a-f]{7,40}$");

    public static Result<ShaPrefix> shaPrefix(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid("sha"))
                     .map(ShaPrefix::lowercase)
                     .flatMap(lower -> Values.matching(lower, PATTERN, "sha"))
                     .map(ShaPrefix::new);
    }

    /// Whether `sha` starts with this prefix.
    public boolean isPrefixOf(Sha sha) {
        return sha.value()
                  .startsWith(value);
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
