package org.pragmatica.temper.domain.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.temper.domain.DomainError.invalid;
import static org.pragmatica.temper.domain.DomainError.unparseable;


/// The run marker's epoch in nanoseconds. Parsed from the marker file's TEXT, never from its mtime (A2):
/// a copied marker keeps its text but gets a fresh mtime, so the mtime proves nothing.
public record MarkerEpoch(long nanos) {
    private static final Pattern PATTERN = Pattern.compile("^[0-9]{1,19}$");
    private static final String FIELD = "marker epoch";

    public static Result<MarkerEpoch> markerEpoch(String fileText) {
        return Verify.ensure(fileText,
                             Verify.Is::present,
                             invalid(FIELD))
                     .map(String::trim)
                     .flatMap(MarkerEpoch::nanos)
                     .map(MarkerEpoch::new);
    }

    private static Result<Long> nanos(String text) {
        return Values.matching(text, PATTERN, FIELD)
                     .flatMap(MarkerEpoch::digitsValue)
                     .flatMap(nanos -> Verify.ensure(nanos,
                                                     Verify.Is::positive,
                                                     invalid(FIELD)));
    }

    private static Result<Long> digitsValue(String digits) {
        return Number.parseLong(digits).mapError(unparseable(FIELD, digits));
    }
}
