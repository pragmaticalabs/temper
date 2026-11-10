package org.pragmatica.kiln.core.model;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.kiln.core.model.ModelError.invalid;


/// A full git commit id: 40 lowercase hex characters, as git and GitHub print it. Nothing is normalised: an
/// abbreviated or upper-case id is refused rather than guessed at.
// Accepted gap: a record cannot hide its canonical constructor, so `new Sha(...)` bypasses the factory (as on every VO).
public record Sha(String value) {
    private static final Pattern PATTERN = Pattern.compile("^[0-9a-f]{40}$");

    public static Result<Sha> sha(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::present,
                             invalid("sha"))
                     .flatMap(present -> Verify.ensure(present,
                                                       Verify.Is::matches,
                                                       PATTERN,
                                                       invalid("sha")))
                     .map(Sha::new);
    }

    /// The first 7 characters, as the dashboard shows a head.
    public String abbreviated() {
        return value.substring(0, 7);
    }

    @Override
    public String toString() {
        return value;
    }
}
