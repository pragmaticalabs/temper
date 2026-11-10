package org.pragmatica.kiln.service.temper;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// temper could not be read: each is `TEMPER-DOWN` (spec §5.6), distinct from a gate answer missing a field
/// (`TEMPER-SCHEMA`, a [org.pragmatica.kiln.core.temper.GateRead.SchemaBroken] value, never a failure). No message
/// carries the read token: it travels only in its header.
public sealed interface TemperReadError extends Cause {
    /// Fixed-text failures.
    enum General implements TemperReadError {
        /// A cycle that passed its deadline (`TEMPER-DOWN(cycle timed out)`); its late steps fail with it too, and read
        /// and write nothing more.
        TIMED_OUT("cycle timed out");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// The exchange failed: connect refused, TLS, the whole-exchange timeout, or a body over the cap.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Unreachable(Cause origin, String message) implements TemperReadError, Cause.Wrapped {
        public static final Fn1<Unreachable, Cause> FACTORY = Causes.forOneValue("temper unreachable", Unreachable::new);
    }

    /// temper answered a status other than 200: 5xx, 401 and 403 by the spec, and any other [guess: a 404 means the
    /// endpoint is gone, which no body field can describe].
    record HttpStatus(int status, String message) implements TemperReadError {
        public static final Fn1<HttpStatus, Integer> FACTORY = Causes.forOneValue("temper answered HTTP %d",
                                                                                  HttpStatus::new);
    }

    /// The `TEMPER-DOWN` reason: `HTTP <status>`, `unreachable: <what failed>`, or `cycle timed out`, on one line.
    static String reason(TemperReadError error) {
        return switch (error) {
            case HttpStatus(var status, var _) -> "HTTP " + status;
            case Unreachable(var origin, var _) -> "unreachable: " + origin.message().replaceAll("\\s+", " ");
            case General.TIMED_OUT -> General.TIMED_OUT.message();
        };
    }
}
