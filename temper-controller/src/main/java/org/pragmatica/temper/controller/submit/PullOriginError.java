package org.pragmatica.temper.controller.submit;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Why a pull request's origin could not be established. Each one refuses the submit as `pr-origin-unverified`; a
/// resubmit may succeed once the API answers.
public sealed interface PullOriginError extends Cause {
    enum General implements PullOriginError {
        /// The configured origin URL is not shown: it can carry credentials.
        NOT_GITHUB("the origin is not a github.com repository URL"),
        INCOMPLETE("the GitHub API answer names no head or base repository"),
        OVERSIZED("the GitHub API answer declares a body over 1 MiB");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// No answer: a connection failure, the exchange deadline passed (body included), or a body without a declared
    /// length that ran over the cap.
    @SuppressWarnings("JBCT-CAUSE-04")
    record CallFailed(Cause origin, String message) implements PullOriginError, Cause.Wrapped {
        public static final Fn1<CallFailed, Cause> FACTORY = Causes.forOneValue("the GitHub API call failed",
                                                                                CallFailed::new);
    }

    /// Any answer but 200, including the rate limit (403, 429).
    record Status(int status, String message) implements PullOriginError {
        public static final Fn1<Status, Integer> FACTORY = Causes.forOneValue("the GitHub API answered HTTP %d",
                                                                              Status::new);
    }

    @SuppressWarnings("JBCT-CAUSE-04")
    record Unparseable(Cause origin, String message) implements PullOriginError, Cause.Wrapped {
        public static final Fn1<Unparseable, Cause> FACTORY = Causes.forOneValue("the GitHub API answer is not a pull request",
                                                                                 Unparseable::new);
    }
}
