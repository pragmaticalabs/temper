package org.pragmatica.temper.cli.merge;

import java.time.InstantSource;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.domain.exit.ExitCode;


/// The exact stdout of the T2 verbs. R3-1: no line here carries a token-file path; the merge
/// line names the verdict token only as the literal `--token-file <verdict-token>`.
sealed interface MergeLines {
    String TOKEN_PLACEHOLDER = "--token-file <verdict-token>";
    Pattern WIRE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");
    int DETAIL_LIMIT = 500;

    /// ISO-8601 UTC at second precision (`as-of`).
    static String asOf(InstantSource clock) {
        return DateTimeFormatter.ISO_INSTANT.format(clock.instant().truncatedTo(ChronoUnit.SECONDS));
    }

    static String no(List<Reason> reasons, String asOf) {
        return Stream.concat(Stream.concat(Stream.of("NO"),
                                           Reason.ordered(reasons).stream().map(Reason::line).distinct()),
                             Stream.of("as-of " + asOf))
                     .collect(Collectors.joining("\n"));
    }

    static String go(MergeWire.GoBody body, MergeWire.VerdictView verdict, MergeWire.GoCreated go, String asOf) {
        return Stream.of(Stream.of("GO " + body.repo() + "#" + body.pr() + "@" + body.sha(),
                                   "base " + body.base() + " tip=" + body.baseTip(),
                                   "verdict " + verdict.verdict()
                                  + " by=" + verdict.by()
                                  + " at=" + verdict.at()
                                  + " report=" + verdict.reportPath()
                                  + " sha256=" + sha12(verdict.reportSha256())),
                         verdict.carriedFrom().map(from -> "verdict carried from " + from).stream(),
                         Stream.of("go " + go.id() + " expires=" + go.expiresAt(),
                                   "temper merge " + go.id() + " " + TOKEN_PLACEHOLDER,
                                   "as-of " + asOf))
                     .flatMap(lines -> lines)
                     .collect(Collectors.joining("\n"));
    }

    static String consumed(MergeWire.Consumed consumed) {
        return "consumed go " + consumed.id()
             + " " + consumed.repo()
             + "#" + consumed.pr()
             + "@" + sha12(consumed.sha())
             + " base=" + consumed.base()
             + " base_tip=" + sha12(consumed.baseTip());
    }

    static String merged(MergeWire.Consumed consumed, String tipBefore) {
        return "merged " + consumed.repo()
             + "#" + consumed.pr()
             + " go=" + consumed.id()
             + " base_tip_before=" + tipBefore;
    }

    static String verdict(long id,
                          String repo,
                          long pr,
                          String sha,
                          String verdict,
                          String base,
                          String by,
                          String sha256,
                          List<String> invalidated) {
        return Stream.concat(Stream.of("verdict " + id
                                      + " " + repo
                                      + "#" + pr
                                      + "@" + sha12(sha)
                                      + " " + verdict
                                      + " base=" + base
                                      + " by=" + by
                                      + " sha256=" + sha12(sha256)),
                             invalidated.stream().map(go -> "invalidated go " + go + " verdict-changed"))
                     .collect(Collectors.joining("\n"));
    }

    static String position(String word, MergeWire.QueuePosition position) {
        return word
             + " " + position.repo()
             + "#" + position.pr()
             + " base=" + position.base()
             + " position=" + position.position();
    }

    static String sha12(String sha) {
        return BaseCheck.sha12(sha);
    }

    /// A wire code's first word in the CLI's lower-kebab form (`ALREADY_QUEUED main 2` → `already-queued main 2`); text
    /// that is not a wire code stays as it is (`go-used`, `go-invalidated sibling-consumed`).
    static String kebab(String error) {
        var parts = error.strip().split(" ", 2);

        return Stream.concat(Stream.of(WIRE_CODE.matcher(parts[0]).matches()
                                       ? parts[0].toLowerCase(Locale.ROOT)
                                              .replace('_', '-')
                                       : parts[0]),
                             Stream.of(parts).skip(1))
                     .collect(Collectors.joining(" "));
    }

    static String detail(String text) {
        return text.length() > DETAIL_LIMIT
               ? text.substring(0, DETAIL_LIMIT)
               : text;
    }

    /// The common answer of a VERDICT verb's write: `404`, `409` and `422` are the controller's refusal, printed as
    /// `refused <code>` with exit 2; `400` is a usage error (64); any other status is outside the contract (69).
    /// Everything else (unreachable, 401/403, version skew) keeps its own exit.
    static Promise<Outcome> refusedOrFailed(Cause cause, String path) {
        return switch (cause) {
            case GateFault.Answered answered when isRefusal(answered.status()) -> Promise.success(refused(kebab(answered.error())));
            case GateFault.Answered answered when answered.status() == 400 -> CliError.Rejected.FACTORY.apply(answered.error()).promise();
            case GateFault.Answered answered -> CliError.UnexpectedStatus.FACTORY.apply(answered.status(), path).promise();
            default -> cause.promise();
        };
    }

    /// `POST fleet/reviews` failures: 422 is `refused <kebab-code> <detail>` (2); 503 and
    /// 409 `IN_FLIGHT` are `unavailable <kebab-code> <detail>` (69); 400 is usage (64); any other status is outside the
    /// contract (69). Only the first line of the server's text is printed.
    static Promise<Outcome> reviewFailed(Cause cause, String path) {
        return switch (cause) {
            case GateFault.Answered answered when answered.status() == 422 -> Promise.success(refused(firstLine(answered.error())));
            case GateFault.Answered answered when answered.status() == 503 || answered.status() == 409 -> Promise.success(unavailable(firstLine(answered.error())));
            case GateFault.Answered answered when answered.status() == 400 -> CliError.Rejected.FACTORY.apply(answered.error()).promise();
            case GateFault.Answered answered -> CliError.UnexpectedStatus.FACTORY.apply(answered.status(), path).promise();
            default -> cause.promise();
        };
    }

    static Outcome unavailable(String reason) {
        return Outcome.outcome("unavailable " + reason, ExitCode.UNREACHABLE);
    }

    private static String firstLine(String error) {
        return detail(kebab(error.strip().lines().findFirst().orElse("")));
    }

    /// 2026-11-04 item 3: a route answer the CLI does not name is 64 for a 400 and 69 for anything else, so a
    /// server answer never reaches exit 5; every other failure keeps its own exit.
    static Cause unmapped(Cause cause) {
        return switch (cause) {
            case GateFault.Answered answered when answered.status() == 400 -> CliError.Rejected.FACTORY.apply(answered.error());
            case GateFault.Answered answered -> CliError.UnexpectedStatus.FACTORY.apply(answered.status(),
                                                                                        answered.path());
            default -> cause;
        };
    }

    static boolean isRefusal(int status) {
        return status == 404 || status == 409 || status == 422;
    }

    static Outcome refused(String reason) {
        return Outcome.outcome("refused " + reason, ExitCode.REFUSED);
    }

    static Outcome printed(List<String> lines, ExitCode exit) {
        return lines.isEmpty()
               ? Outcome.outcome(exit)
               : Outcome.outcome(String.join("\n", lines), exit);
    }

    record unused() implements MergeLines {}
}
