package org.pragmatica.temper.controller.submit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// git could not answer: it failed to start, timed out, or exited with an unexpected code. The submit answers 503
/// and persists nothing, because a git outage says nothing about the request (a ref git DID report missing is a
/// `Refusal(source)` instead).
public sealed interface GitError extends Cause {
    @SuppressWarnings("JBCT-CAUSE-04")
    record Failed(String operation, Cause origin, String message) implements GitError, Cause.Wrapped {
        public static final Fn2<Failed, String, Cause> FACTORY = Causes.forTwoValues("git %s failed", Failed::new);

        @Override
        public boolean isTransient() {
            return origin.isTransient();
        }
    }
}
