package org.pragmatica.kiln.service.mirror;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// The mirror could not answer: git failed to start, timed out, exited with an unexpected code, or printed what kiln
/// cannot read. Never an empty or zero answer (kiln-f1-design §3.4): the `mirror` source reads failing instead.
public sealed interface MirrorError extends Cause {
    enum General implements MirrorError {
        /// The mirror's history is cut (`rev-parse --is-shallow-repository` says `true`), so an unreachable answer could
        /// be a lie. No fetch deepens it; the operator replaces the mirror.
        SHALLOW_MIRROR("Shallow mirror: kiln answers only from a whole mirror"),
        /// The origin is not `https://<host>` without userinfo, `file:///` or an absolute path, or holds a control
        /// character. The text is not echoed: an origin URL can carry a credential.
        INVALID_ORIGIN("Mirror origin must be https://<host> with no user or password, file:/// or an absolute path, with no control character");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    @SuppressWarnings("JBCT-CAUSE-04")
    record Failed(String operation, Cause origin, String message) implements MirrorError, Cause.Wrapped {
        public static final Fn2<Failed, String, Cause> FACTORY = Causes.forTwoValues("git %s failed", Failed::new);

        @Override
        public boolean isTransient() {
            return origin.isTransient();
        }
    }
}
