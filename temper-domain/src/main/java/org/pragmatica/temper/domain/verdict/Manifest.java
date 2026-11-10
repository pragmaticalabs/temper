package org.pragmatica.temper.domain.verdict;

import java.util.Set;


/// The test classes a job is expected to run (spec §5.2, ruling R-2): each `required` class must report (an absent
/// one is missing); an `optional` class may or may not report (neither missing nor drift). A reported class in
/// neither set is drift. M0 never has one; it arrives with M1.
public record Manifest(Set<String> required, Set<String> optional) {
    public Manifest {
        required = Set.copyOf(required);
        optional = Set.copyOf(optional);
    }

    public static Manifest manifest(Set<String> required, Set<String> optional) {
        return new Manifest(required, optional);
    }

    /// Whether a reported class is expected at all: required or optional.
    public boolean expects(String className) {
        return required.contains(className) || optional.contains(className);
    }
}
