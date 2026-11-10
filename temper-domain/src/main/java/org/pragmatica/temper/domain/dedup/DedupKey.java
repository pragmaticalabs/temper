package org.pragmatica.temper.domain.dedup;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.CanonicalJson;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.model.RecipeOptions;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.domain.state.JobState;


/// Dedup key (spec §2.3, m0-design §3.5): the sha256 hex of [#material]. The digest itself is computed at the
/// adapter boundary (`org.pragmatica.temper.protocol.digest.Digests#dedupKey`), because the JDK digest
/// lookup can only fail by exception; this type holds the pure parts and the validated result.
///
/// `priority`, `keep` and `submittedBy` are not part of the key: they do not change what runs. The timeout
/// override is (Q-6): it can change the verdict.
public record DedupKey(String value) {
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    /// A computed key: 64 lowercase hex characters.
    public static Result<DedupKey> dedupKey(String sha256Hex) {
        return Values.matching(sha256Hex, SHA256_HEX, "dedup key").map(DedupKey::new);
    }

    /// The digest input: `len(a) ':' a '\n' len(b) ':' b '\n' len(c) ':' c`, where `a` is the source
    /// identity, `b` the [#canonical] recipe-with-options JSON and `c` the runner version: the controller's whole build
    /// version, judge and any `-dirty`/`+unjudged` suffix included (D-6 A1), so no verdict of another temper build is
    /// reused. Each length is the field's `String.length()`, so no field's content can be read as a boundary.
    public static String material(String sourceIdentity, Recipe recipe, RecipeOptions options, String runnerVersion) {
        return Stream.of(sourceIdentity,
                         canonical(recipe, options),
                         runnerVersion)
                     .map(DedupKey::lengthPrefixed)
                     .collect(Collectors.joining("\n"));
    }

    /// `b`: `{"options":<options>,"recipe":<recipe>}`, canonical.
    public static String canonical(Recipe recipe, RecipeOptions options) {
        return CanonicalJson.object(Map.of("options", options.canonical(), "recipe", recipe.canonical()));
    }

    /// Whether a job in `state` satisfies a resubmit with the same key (A9). VOID, ERROR and CANCELLED
    /// describe the environment, not the code; REFUSED never ran; a CANCELLING or SUPERSEDED job is on its
    /// way to CANCELLED. An UNKNOWN job runs (only its start was not recorded), so it is reused like a RUNNING one.
    public static boolean reusable(JobState state) {
        return switch (state) {
            case QUEUED, UNKNOWN, RUNNING, PASSED, FAILED, EMPTY -> true;
            case REFUSED, CANCELLING, SUPERSEDED, VOID, ERROR, CANCELLED -> false;
        };
    }

    private static String lengthPrefixed(String field) {
        return field.length() + ":" + field;
    }

    @Override
    public String toString() {
        return value;
    }
}
