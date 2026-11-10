package org.pragmatica.temper.domain.diff;

import java.util.List;
import java.util.Locale;

import org.pragmatica.temper.domain.report.Outcome;


/// One test's outcome on one side of a diff (m2-design §3.2; ruling R-M2-9 on Q-M2-8): the rows of one `(class, name)`
/// key folded into one. A key repeats when dynamic tests share a display name or two units run one class.
public enum KeyOutcome {
    PASSED,
    FAILED,
    SKIPPED;
    /// The CLI's marker text, lowercase: `passed`, `failed`, `skipped` (`[failed]`). The JSON carries the constant name.
    public String marker() {
        return name().toLowerCase(Locale.ROOT);
    }
    /// FAILED if any row failed or errored; else SKIPPED if every row was skipped; else PASSED. `outcomes` is never
    /// empty: a key exists because a row has it.
    public static KeyOutcome fold(List<Outcome> outcomes) {
        return outcomes.stream()
                       .anyMatch(Outcome::isFailure)
               ? FAILED
               : skippedOrPassed(outcomes);
    }
    private static KeyOutcome skippedOrPassed(List<Outcome> outcomes) {
        return outcomes.stream()
                       .allMatch(outcome -> outcome == Outcome.SKIPPED)
               ? SKIPPED
               : PASSED;
    }
}
