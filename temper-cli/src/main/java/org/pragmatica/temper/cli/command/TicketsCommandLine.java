package org.pragmatica.temper.cli.command;

import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.cli.CliError;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// `tickets [--host <host>] [--done <0..200>] [--json]`. The host is a temper `HostName`
/// (`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`) and `--done` 0..200, the route's own bounds: a bad one is 64 without a call.
sealed interface TicketsCommandLine {
    Pattern HOST = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    int MAX_DONE = 200;

    static Result<CliCommand> tickets(Args args) {
        return Result.all(host(args), done(args)).map((host, done) -> new CliCommand.Tickets(host,
                                                                                             done,
                                                                                             args.hasFlag("--json")));
    }

    private static Result<Option<String>> host(Args args) {
        return args.optionValue("--host")
                   .map(raw -> Verify.ensure(raw,
                                             Verify.Is::matches,
                                             HOST,
                                             CliError.Usage.FACTORY.apply("--host must be a host name: " + raw))
                                     .map(Option::some))
                   .or(() -> success(none()));
    }

    private static Result<Option<Integer>> done(Args args) {
        return args.optionValue("--done")
                   .map(TicketsCommandLine::count)
                   .or(() -> success(none()));
    }

    private static Result<Option<Integer>> count(String raw) {
        return Number.parseInt(raw)
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply("--done", raw, cause))
                     .flatMap(count -> count >= 0 && count <= MAX_DONE
                                       ? success(count)
                                       : CommandLine.<Integer> usage("--done must be 0..200: " + raw))
                     .map(Option::some);
    }

    record unused() implements TicketsCommandLine {}
}
