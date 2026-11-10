package org.pragmatica.temper.cli.merge;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// The Mac-side failures of the T2 checks. They never reach an exit code on their own: each becomes a reason line
/// (`source-unavailable git <step>`, `source-unavailable budget-exceeded`) or a `refused` line.
sealed interface GateFault extends Cause {
    enum General implements GateFault {
        BUDGET_SPENT("merge-gate budget exceeded"),
        REPORT_CHANGED("report-changed: the report's sha256 is not the registered one");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// One git step failed (non-zero exit, timeout, could not start); the runner's failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record GitFailed(String step, Cause origin, String message) implements GateFault, Cause.Wrapped {
        static final Fn2<GitFailed, String, Cause> FACTORY = Causes.forTwoValues("git %s failed", GitFailed::new);
    }

    /// A T2 route answered a status other than its success one (and other than 401/403, which are
    /// `CliError.General`); `error` is the body's `error` text (the wire code for 409 and 422).
    record Answered(int status, String path, String error, String message) implements GateFault {
        static final Fn3<Answered, Integer, String, String> FACTORY = Causes.forThreeValues("Controller answered HTTP %d from %s: %s",
                                                                                            Answered::new);
    }

    /// The step a failure names in `source-unavailable git <step>`; a failure that is not a git step's is `unknown`.
    static String stepOf(Cause cause) {
        return cause instanceof GitFailed failed
               ? failed.step()
               : "unknown";
    }
}
