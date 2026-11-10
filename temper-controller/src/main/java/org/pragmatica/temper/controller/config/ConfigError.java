package org.pragmatica.temper.controller.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Why `controller.toml` (or a secret file it names) cannot be turned into a [ControllerConfig]. Every failure is
/// fatal at start, so none is retryable.
public sealed interface ConfigError extends Cause {
    enum General implements ConfigError, Cause.Terminal {
        NO_VERSION("The controller version resource is missing or unfiltered"),
        NO_JUDGE("The controller judge line is missing from the version resource, or unfiltered"),
        /// Neither `[github.app]` nor `[github] anonymous = true`: the start is refused with exit 78. Unauthenticated GitHub reads must be asked for.
        NO_GITHUB_IDENTITY("Neither [github.app] (clientId, installationId, keyFile) nor [github] anonymous = true is configured");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// A required key is absent.
    record Missing(String section, String key, String message) implements ConfigError, Cause.Terminal {
        public static final Fn2<Missing, String, String> FACTORY = Causes.forTwoValues("Missing [%s] %s", Missing::new);
    }

    /// A key is present but its value does not parse. The message carries the origin's reason, since the controller's
    /// start log prints the message alone ("Invalid [hosts] retireAt" never said why).
    @SuppressWarnings("JBCT-CAUSE-04")
    record Invalid(String section, String key, Cause origin, String message) implements ConfigError, Cause.Wrapped, Cause.Terminal {
        public static final Fn3<Invalid, String, String, Cause> FACTORY = (section, key, origin) -> new Invalid(section,
                                                                                                                key,
                                                                                                                origin,
                                                                                                                "Invalid [" + section
                                                                                                               + "] " + key
                                                                                                               + ": " + origin.message());
    }

    /// A file named by the configuration (the config itself, the token, the database password) cannot be read.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreadable(String path, Cause origin, String message) implements ConfigError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<Unreadable, String, Cause> FACTORY = Causes.forTwoValues("Cannot read %s",
                                                                                         Unreadable::new);
    }
}
