package org.pragmatica.kiln.core.model;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// A GitHub pull request number: 1 or more.
public record PrNumber(long value) {
    public static Result<PrNumber> prNumber(long raw) {
        return Verify.ensure(raw, Verify.Is::positive, invalid("PR number")).map(PrNumber::new);
    }

    @Override
    public String toString() {
        return "#" + value;
    }
}
