package org.pragmatica.temper.cli.transport;

import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.protocol.message.BuildVersion;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// D-6: the CLI sends its build version ([org.pragmatica.temper.cli.config.CliVersion]) as the `Temper-Version`
/// header on every request, and every answer of the controller carries the controller's; the CLI compares the two
/// ([BuildVersion#compare]) before anything decodes the body.
///
/// - SAME: the answer passes.
/// - SKEWED (both judged, a `-dirty` build included): [CliError.VersionSkew], exit 13. There is no override: a CLI of
///   another build may misdecode the controller's answers (DEPLOY F2). The controller refuses a mutating request of a
///   skewed CLI with 412 before it acts; a 412 is [CliError.VersionSkew] too, so a refused write also exits 13.
/// - UNJUDGED (either side `+unjudged`, a dev or test build): the answer passes with a warning on stderr.
/// - PREDATES (no header, or a version without `+`): the answer passes with [#PREDATES_WARNING] (G1/G2, transition
///   rules; END CONDITION: when the rollback set no longer holds any build older than D-6, this becomes the refusal,
///   exit 13; the deploy runbook).
///
/// A warning is printed once per invocation.
public sealed interface VersionCheck {
    /// The transition rules' warning (G1/G2), exactly as the maintainer ruled it.
    String PREDATES_WARNING = "controller predates build identity; skew not checked";
    int PRECONDITION_FAILED = 412;

    /// `transport` with every call naming `cliVersion` and every answer checked against it; warnings go to `console`. A
    /// CLI version that could not be read fails every call before it is sent.
    static HttpTransport versionChecked(HttpTransport transport, Result<String> cliVersion, Console console) {
        var warned = new AtomicBoolean();

        return call -> exchanged(transport, call, cliVersion, console, warned);
    }

    /// The comparison alone: success with no warning (SAME), success with a warning (UNJUDGED, PREDATES), or
    /// [CliError.VersionSkew].
    static Result<Option<String>> verdict(String cliVersion, Option<String> controllerVersion) {
        return switch (BuildVersion.compare(cliVersion, controllerVersion)) {
            case SAME -> success(none());
            case PREDATES -> success(some(PREDATES_WARNING));
            case UNJUDGED -> success(some(unjudgedWarning(cliVersion, controllerVersion)));
            case SKEWED -> skew(cliVersion, controllerVersion);
        };
    }

    private static String unjudgedWarning(String cliVersion, Option<String> controllerVersion) {
        return "warning: CLI version " + cliVersion
             + " differs from controller version " + controllerVersion.or("")
             + " (an unjudged build cannot be compared)";
    }

    private static Result<Option<String>> skew(String cliVersion, Option<String> controllerVersion) {
        return CliError.VersionSkew.FACTORY.apply(cliVersion,
                                                  controllerVersion.or("(none)"))
                                           .result();
    }

    private static Promise<HttpReply> exchanged(HttpTransport transport,
                                                HttpCall call,
                                                Result<String> cliVersion,
                                                Console console,
                                                AtomicBoolean warned) {
        return cliVersion.async()
                         .flatMap(own -> sent(transport, call, own, console, warned));
    }

    private static Promise<HttpReply> sent(HttpTransport transport,
                                           HttpCall call,
                                           String cliVersion,
                                           Console console,
                                           AtomicBoolean warned) {
        return transport.exchange(call.withVersion(cliVersion))
                        .flatMap(reply -> checked(reply, cliVersion, console, warned));
    }

    private static Promise<HttpReply> checked(HttpReply reply,
                                              String cliVersion,
                                              Console console,
                                              AtomicBoolean warned) {
        return refusedWrite(reply, cliVersion).flatMap(_ -> verdict(cliVersion,
                                                                    reply.temperVersion()))
                           .map(warning -> warnedOnce(warning, console, warned))
                           .map(_ -> reply)
                           .async();
    }

    /// A 412 is the controller's refusal of a skewed CLI's mutating request, made before it acted.
    private static Result<HttpReply> refusedWrite(HttpReply reply, String cliVersion) {
        return reply.status() == PRECONDITION_FAILED
               ? CliError.VersionSkew.FACTORY.apply(cliVersion,
                                                    reply.temperVersion().or("(none)"))
                                             .result()
               : success(reply);
    }

    private static Unit warnedOnce(Option<String> warning, Console console, AtomicBoolean warned) {
        return warning.filter(_ -> warned.compareAndSet(false, true))
                      .map(console::problem)
                      .or(unit());
    }

    record unused() implements VersionCheck {}
}
