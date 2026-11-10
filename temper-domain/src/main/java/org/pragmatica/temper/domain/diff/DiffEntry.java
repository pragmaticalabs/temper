package org.pragmatica.temper.domain.diff;

/// One test in a diff section (m2-design §3.2): its `(class, name)` key as the report names it, and its folded outcome
/// on the side the section reports (B's for `added`, `newly-failing` and `newly-passing`; A's for `removed`).
public record DiffEntry(String className, String name, KeyOutcome outcome) {
    public static DiffEntry diffEntry(String className, String name, KeyOutcome outcome) {
        return new DiffEntry(className, name, outcome);
    }
}
