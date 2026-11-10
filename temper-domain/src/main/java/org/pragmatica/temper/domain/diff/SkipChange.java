package org.pragmatica.temper.domain.diff;

/// A test on both sides that moved PASSED → SKIPPED, SKIPPED → PASSED or FAILED → SKIPPED (m2-design §3.2; rulings
/// R-M2-9 on Q-M2-8 and R-M2-13): in no section, only counted (`skip-changed=N`) and listed in `--json`, and never in
/// the exit code. A FAILED → SKIPPED move is not a fix; SKIPPED → FAILED is not a skip change but newly failing.
public record SkipChange(String className, String name, KeyOutcome a, KeyOutcome b) {
    public static SkipChange skipChange(String className, String name, KeyOutcome a, KeyOutcome b) {
        return new SkipChange(className, name, a, b);
    }
}
