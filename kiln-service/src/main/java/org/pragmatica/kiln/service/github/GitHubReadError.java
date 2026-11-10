package org.pragmatica.kiln.service.github;

import java.time.Instant;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Failures of a GitHub exchange before GitHub's answer is judged: the exchange failed, kiln refused to spend its rate
/// reserve, or a path would leave the API host. A status GitHub answered is [org.pragmatica.kiln.core.github.GitHubError.HttpStatus].
/// No message carries a token: requests carry it only in a header, and no header is ever formatted.
public sealed interface GitHubReadError extends Cause {
    /// Fixed-text failures.
    enum General implements GitHubReadError {
        /// A step of a cycle that passed its deadline: the cycle already reads `GITHUB-DOWN(cycle timed out)`, and its
        /// late steps send and write nothing more.
        LATE("the cycle passed its deadline");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// The exchange failed: connect, TLS, the whole-exchange timeout, or a body over the cap.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreachable(Cause origin, String message) implements GitHubReadError, Cause.Wrapped {
        public static final Fn1<Unreachable, Cause> FACTORY = Causes.forOneValue("GitHub unreachable", Unreachable::new);
    }

    /// The kiln App holds no unexpired installation token and could not mint one: the
    /// request is refused locally. It is never sent unauthenticated, and never with another identity. `origin` is the
    /// App auth's own cause; none of them carries a token.
    @SuppressWarnings("JBCT-CAUSE-04")
    record CredentialUnavailable(Cause origin, String message) implements GitHubReadError, Cause.Wrapped {
        public static final Fn1<CredentialUnavailable, Cause> FACTORY = Causes.forOneValue("GitHub credential unavailable",
                                                                                           CredentialUnavailable::new);
    }

    /// `X-RateLimit-Remaining` is at the reserve, or a secondary limit holds: kiln sends nothing more until `reset`
    /// (design §8).
    record BudgetSpent(Instant reset, String message) implements GitHubReadError {
        public static final Fn1<BudgetSpent, Instant> FACTORY = Causes.forOneValue("GitHub rate budget kept in reserve until %s",
                                                                                   BudgetSpent::new);
    }

    /// A path that is not one absolute path on the API host (`//host/...`, a scheme, an unparseable URI); `origin`
    /// says which.
    @SuppressWarnings("JBCT-CAUSE-04")
    record BadPath(String path, Cause origin, String message) implements GitHubReadError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<BadPath, String, Cause> FACTORY = Causes.forTwoValues("Refused GitHub path %s",
                                                                                      BadPath::new);
    }
}
