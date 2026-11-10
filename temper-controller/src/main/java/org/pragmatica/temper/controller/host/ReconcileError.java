package org.pragmatica.temper.controller.host;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.UnitRef;


/// Why reconcile did not complete; the link stays down and the reconnect decides again.
public sealed interface ReconcileError extends Cause {
    /// One unit's reconcile action failed; `origin` is what failed (the worker reply, the store, the link).
    @SuppressWarnings("JBCT-CAUSE-04")
    record ActionFailed(UnitRef unit, Cause origin, String message) implements ReconcileError, Cause.Wrapped {
        public static final Fn2<ActionFailed, UnitRef, Cause> FACTORY = Causes.forTwoValues("Reconcile action for %s failed",
                                                                                            ActionFailed::new);
    }

    /// The worker answered a re-attach `stale-attempt`: it runs another attempt of the unit than the store's current
    /// one. The current attempt is settled first; the session then reconnects, so the fresh scan's rule 0 discards
    /// the attempt still running before the host goes UP (m4-design §4.5).
    record StaleAttempt(UnitRef unit, String message) implements ReconcileError {
        public static final Fn1<StaleAttempt, UnitRef> FACTORY = Causes.forOneValue("Reconcile of %s found another attempt running",
                                                                                    StaleAttempt::new);
    }
}
