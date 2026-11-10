package org.pragmatica.kiln.service.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Why `kiln.toml` (or a secret file it names) cannot be turned into a [KilnConfig]. Every failure is fatal at start,
/// so none is retryable. Copied from temper's controller `ConfigError`.
public sealed interface ConfigError extends Cause {
    /// A required key is absent.
    record Missing(String section, String key, String message) implements ConfigError, Cause.Terminal {
        public static final Fn2<Missing, String, String> FACTORY = Causes.forTwoValues("Missing [%s] %s", Missing::new);
    }

    /// A key is present but its value does not parse. The message carries the origin's reason, since kiln's start log
    /// prints the message alone.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Invalid(String section, String key, Cause origin, String message) implements ConfigError, Cause.Wrapped, Cause.Terminal {
        public static final Fn3<Invalid, String, String, Cause> FACTORY = (section, key, origin) -> new Invalid(section,
                                                                                                                key,
                                                                                                                origin,
                                                                                                                "Invalid [" + section
                                                                                                               + "] " + key
                                                                                                               + ": " + origin.message());
    }

    /// `[server] bind` or `hostNames` is refused (a wildcard, a loopback or non-IPv4 value, or `hostNames` without `bind`):
    /// the start is refused with exit 78 (`Main.EXIT_CONFIG`), since listening wider than intended is a misconfiguration,
    /// not a malformed file.
    record Listen(String key, String message) implements ConfigError, Cause.Terminal {
        public static Listen listen(String key, String reason) {
            return new Listen(key, "Invalid [server] " + key + ": " + reason);
        }
    }

    /// A section kiln does not read (a misspelt `[githb]`, or `[hosts]` written as one table instead of `[[hosts]]`).
    /// Refused, so a typo can never leave a setting silently at its default.
    record UnknownSection(String section, String message) implements ConfigError, Cause.Terminal {
        public static final Fn1<UnknownSection, String> FACTORY = Causes.forOneValue("Unknown section [%s]",
                                                                                     UnknownSection::new);
    }

    /// A key kiln does not read, in a section or a `[[hosts]]` table it does (a misspelt `tokenfile`). Refused for the
    /// same reason.
    record UnknownKey(String section, String key, String message) implements ConfigError, Cause.Terminal {
        public static final Fn2<UnknownKey, String, String> FACTORY = Causes.forTwoValues("Unknown key [%s] %s",
                                                                                          UnknownKey::new);
    }

    /// A key outside every section. An inline table (`server = { port = 7272 }`) is one: kiln reads a section only
    /// under its `[section]` header, so no spelling of it can bypass that section's rules.
    record TopLevelKey(String key, String message) implements ConfigError, Cause.Terminal {
        public static final Fn1<TopLevelKey, String> FACTORY = Causes.forOneValue("Unknown top-level key %s: every key belongs under a [section] header, and an inline table is not read",
                                                                                  TopLevelKey::new);
    }

    /// A file named by the configuration (the config itself, a token, the database password) cannot be read.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreadable(String path, Cause origin, String message) implements ConfigError, Cause.Wrapped, Cause.Terminal {
        public static final Fn2<Unreadable, String, Cause> FACTORY = Causes.forTwoValues("Cannot read %s",
                                                                                         Unreadable::new);
    }

    /// The GitHub credential is not exactly one of `tokenFile`, `[github.app]`, or `anonymous = true`: the start is refused with exit 78 (`Main.EXIT_CONFIG`).
    enum Credential implements ConfigError, Cause.Terminal {
        BOTH("config: tokenFile and [github.app] both set"),
        NONE("config: no GitHub credential"),
        ANONYMOUS_WITH_CREDENTIAL("config: anonymous = true with a tokenFile or [github.app]"),
        APP_REPOS_OTHER_OWNER("config: [github.app] needs every repository under the owner of [github] repo");
        private final String message;
        Credential(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
