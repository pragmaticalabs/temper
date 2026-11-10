package org.pragmatica.kiln.core.github;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// A base's required-check list, resolved every cycle (spec §4.3). Neither [NoneRequired] nor [Unknown] is ever green.
public sealed interface RequiredChecks {
    /// `why` of a [NoneRequired]: protection is disabled.
    String DISABLED = "disabled";
    /// `why` of a [NoneRequired]: `enforcement_level` is `off` (today's `main`).
    String OFF = "off";
    /// `why` of a [NoneRequired]: protection is on, and it requires no check.
    String NO_CHECKS = "no checks";
    /// Prefix of an [Unknown] status when the rulesets read failed under a classic list.
    String RULESETS = "rulesets ";
    Pattern WHITESPACE = Pattern.compile("\\s+");

    /// The branch read succeeded, protection is enabled and at least one check is required.
    record Required(List<RequiredCheck> checks, String enforcement) implements RequiredChecks {
        public Required {
            checks = List.copyOf(checks);
        }
    }

    /// The branch read succeeded and nothing is required; `why` names which rule (disabled, `off`, no checks).
    /// `stacked`: the base is not a configured one ("stacked base: CI may not run").
    record NoneRequired(String why, boolean stacked) implements RequiredChecks {}

    /// The branch read failed: the HTTP status, a timeout or an unparseable answer.
    record Unknown(String status) implements RequiredChecks {}

    /// The §4.3 table. Only the branch read decides the state; the rulesets only add contexts to a [Required] list and
    /// never stand in for the branch read, so a failed branch read is [Unknown] whatever the rulesets say. A failed
    /// rulesets read under a [Required] list is [Unknown] too: the list may be missing a context, and kiln is never
    /// greener than GitHub. A required check is the pair `{context, app_id}`: a pair both name is kept once, and a
    /// context they pin differently (two apps, or one app and none) is kept as both entries, each its own cell. GitHub
    /// enforces both layers, so dropping either could read greener than GitHub.
    static RequiredChecks resolve(Result<BranchRead> branch, Result<List<RequiredCheck>> rulesets, boolean configured) {
        return branch.fold(cause -> new Unknown(status(cause)), read -> classic(read, rulesets, !configured));
    }

    private static RequiredChecks classic(BranchRead read, Result<List<RequiredCheck>> rulesets, boolean stacked) {
        if (!read.enabled()) {
            return new NoneRequired(DISABLED, stacked);
        }

        if (read.enforcementLevel().filter(OFF::equals).isPresent()) {
            return new NoneRequired(OFF, stacked);
        }

        if (read.checks().isEmpty()) {
            return new NoneRequired(NO_CHECKS, stacked);
        }

        return rulesets.fold(cause -> new Unknown(RULESETS + status(cause)),
                             added -> required(read, added));
    }

    private static RequiredChecks required(BranchRead read, List<RequiredCheck> added) {
        return new Required(Stream.concat(read.checks().stream(),
                                          added.stream()).distinct().toList(),
                            read.enforcementLevel().or("unknown"));
    }

    /// The bare HTTP status when GitHub answered one (`UNKNOWN(403)`), else the failure's message on one line (a
    /// decode failure collects every bad field into a multi-line composite).
    private static String status(Cause cause) {
        return switch (cause) {
            case GitHubError.HttpStatus http -> Integer.toString(http.status());
            default -> WHITESPACE.matcher(cause.message()).replaceAll(" ");
        };
    }
}
