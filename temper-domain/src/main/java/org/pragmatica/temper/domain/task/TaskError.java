package org.pragmatica.temper.domain.task;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// A command [TaskMachine] refuses (m3-design §5.2, §6.4, §11.1). The fixed texts are the wire codes the API answers
/// with and the CLI maps to exit codes: `lease-lost` (409, exit 12), `not-ready` and `not-held` (409, exit 9), and the
/// guard failures (422, exit 2). Every unlisted (stage, status, command) is [Refused] (409). `read-point-unknown` is a
/// claim of a task with neither a head nor a resolvable base tip (§6.5; 409). `ci-wait-outside-ci` (422) is a `wait --ci`
/// outside the CI stage: only the watcher's T12 ends a CI wait, and T12 runs from CI alone, so such a wait would never
/// end.
public sealed interface TaskError extends Cause {
    enum General implements TaskError, Cause.Terminal {
        LEASE_LOST("lease-lost"),
        NOT_READY("not-ready"),
        NOT_HELD("not-held"),
        BAD_NEXT("bad-next"),
        AFTER_PENDING("after-pending"),
        NOTHING_AWAITED("nothing-awaited"),
        NOT_SETTLED("not-settled"),
        NOT_EXPIRED("not-expired"),
        NOT_MERGED("not-merged"),
        AUDIT_PENDING("audit-pending"),
        READ_POINT_UNKNOWN("read-point-unknown"),
        CI_WAIT_OUTSIDE_CI("ci-wait-outside-ci");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// A command no row of the transition table admits in this stage and status.
    record Refused(String command, Stage stage, Status status, String message) implements TaskError, Cause.Terminal {
        public static final Fn3<Refused, String, Stage, Status> FACTORY = Causes.forThreeValues("refused: %s in %s/%s",
                                                                                                Refused::new);
    }
}
