package org.pragmatica.temper.domain.verdict;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitName;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The terminal job outcome (spec §5.3). `kind()` is the upper-case wire form; `detail()` is the
/// parenthesised reason, as in `FAILED(timeout)`: a closed-vocabulary token. A VOID / ERROR reason as reported may
/// carry free text after its token (`manifest: effective-pom rc=1`); `detail()` is its [#token], `fullReason()` the
/// reported text and [#explanation] the part after the token.
public sealed interface Verdict {
    /// The infrastructure token, for a reason whose token is blank (the controller's m0 convention).
    String INFRA = "infra";
    String kind();
    Option<String> detail();

    /// The reason as reported, explanation included: the stored unit reason. `detail()` unless overridden.
    default Option<String> fullReason() {
        return detail();
    }

    /// `infra: docker build failed` → `infra`; a reason without `:` is its own token; a blank token is [#INFRA].
    static String token(String reason) {
        var colon = reason.indexOf(':');

        return Option.some(colon < 0
                           ? reason
                           : reason.substring(0, colon))
                     .map(String::trim)
                     .filter(value -> !value.isEmpty())
                     .or(INFRA);
    }

    /// `infra: docker build failed` → `docker build failed`; absent without `:` or when nothing follows it.
    static Option<String> explanation(String reason) {
        var colon = reason.indexOf(':');

        return Option.some(reason.substring(colon + 1))
                     .filter(_ -> colon >= 0)
                     .map(String::trim)
                     .filter(value -> !value.isEmpty());
    }

    /// The host was compromised during a unit. `reason` is as reported; `detail()` is its token.
    record Void(String reason) implements Verdict {
        @Override
        public String kind() {
            return "VOID";
        }

        @Override
        public Option<String> detail() {
            return some(token(reason));
        }

        @Override
        public Option<String> fullReason() {
            return some(reason);
        }
    }

    /// Lost unit, report parse failure, plan error, or infrastructure failure before mvn ran. `reason` is as
    /// reported; `detail()` is its token.
    record Error(String reason) implements Verdict {
        @Override
        public String kind() {
            return "ERROR";
        }

        @Override
        public Option<String> detail() {
            return some(token(reason));
        }

        @Override
        public Option<String> fullReason() {
            return some(reason);
        }
    }

    /// Cancelled by request or by supersede.
    record Cancelled() implements Verdict {
        @Override
        public String kind() {
            return "CANCELLED";
        }

        @Override
        public Option<String> detail() {
            return none();
        }
    }

    /// A unit requested tests, exited rc 0 and reported none; lists every such unit.
    record Empty(List<UnitName> units) implements Verdict {
        public Empty {
            units = List.copyOf(units);
        }

        @Override
        public String kind() {
            return "EMPTY";
        }

        @Override
        public Option<String> detail() {
            return some(String.join(",",
                                    units.stream().map(UnitName::value).toList()));
        }
    }

    /// `timeout`, `missing-classes`, `tests` or `rc`.
    record Failed(String reason) implements Verdict {
        @Override
        public String kind() {
            return "FAILED";
        }

        @Override
        public Option<String> detail() {
            return some(reason);
        }
    }

    /// Everything else.
    record Passed() implements Verdict {
        @Override
        public String kind() {
            return "PASSED";
        }

        @Override
        public Option<String> detail() {
            return none();
        }
    }
}
