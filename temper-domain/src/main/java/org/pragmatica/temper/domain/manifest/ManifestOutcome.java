package org.pragmatica.temper.domain.manifest;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Cause;


/// A unit's manifest: computed, or refused because the configuration is outside what the rules can judge
/// (Q-M1-7: `ERROR(manifest: <reason>)`, never a guess).
public sealed interface ManifestOutcome {
    /// The expected classes (`entries`: each must report, its absence is `missing`); the classes that may or
    /// may not report (`optional`: neither their absence is `missing` nor their presence drift; disjoint from
    /// `entries`); the modules whose tests were never compiled (`notReached`, not covered); and the annotation
    /// types the rules met but could not resolve in the class index.
    record Computed(Set<ManifestEntry> entries,
                    Set<ManifestEntry> optional,
                    List<String> notReached,
                    Set<String> unresolvedAnnotations) implements ManifestOutcome {
        public Computed {
            entries = Set.copyOf(entries);
            optional = Set.copyOf(optional);
            notReached = List.copyOf(notReached);
            unresolvedAnnotations = Set.copyOf(unresolvedAnnotations);
        }

        public static Computed computed(Set<ManifestEntry> entries,
                                        Set<ManifestEntry> optional,
                                        List<String> notReached,
                                        Set<String> unresolvedAnnotations) {
            return new Computed(entries, optional, notReached, unresolvedAnnotations);
        }
    }

    /// The configuration cannot be judged; `cause` is the typed failure (a [ManifestError]).
    record Unsupported(Cause cause) implements ManifestOutcome {
        public static Unsupported unsupported(Cause cause) {
            return new Unsupported(cause);
        }

        /// The text `ERROR(manifest: <reason>)` reports.
        public String reason() {
            return cause.message();
        }
    }
}
