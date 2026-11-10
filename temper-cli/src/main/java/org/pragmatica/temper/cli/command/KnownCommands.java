package org.pragmatica.temper.cli.command;

import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.KnownAddRequest;
import org.pragmatica.temper.protocol.api.KnownDto;


/// `known list|add|expire` (m2-design §4.4): one request each, one [Lines#known] line per entry, exit 0. An active
/// entry for the test is exit 2 ([CliError.Conflict]); a field the controller does not accept (400) or an unknown id
/// (404) is 64; an unreachable controller 69. `add` needs an owner, `--owner` or the configured one, recorded as the
/// entry's `createdBy`: attribution, not authority.
public sealed interface KnownCommands {
    static Promise<Outcome> known(Controller api,
                                  JsonMapper json,
                                  CliCommand.KnownCommand command,
                                  Option<String> configuredOwner) {
        return switch (command) {
            case CliCommand.KnownList list -> list(api, json, list);
            case CliCommand.KnownAdd add -> register(api, add, configuredOwner);
            case CliCommand.KnownExpire expire -> expire(api, expire);
        };
    }

    /// `known list [--all] [--json]`: no entries prints nothing (`[]` with `--json`).
    private static Promise<Outcome> list(Controller api, JsonMapper json, CliCommand.KnownList command) {
        return api.known(command.all())
                  .flatMap(entries -> listed(entries,
                                             command.json(),
                                             json).async());
    }

    private static Result<Outcome> listed(List<KnownDto> entries, boolean asJson, JsonMapper json) {
        return asJson
               ? Rendered.text(entries, "", true, json).map(text -> Outcome.outcome(text, ExitCode.ACCEPTED))
               : Result.success(listed(entries));
    }

    /// `known add`: the owner, then the request.
    private static Promise<Outcome> register(Controller api,
                                             CliCommand.KnownAdd command,
                                             Option<String> configuredOwner) {
        return request(command, configuredOwner).async()
                      .flatMap(api::newKnown)
                      .map(KnownCommands::shown);
    }

    private static Promise<Outcome> expire(Controller api, CliCommand.KnownExpire command) {
        return api.expireKnown(command.id())
                  .map(KnownCommands::shown);
    }

    private static Result<KnownAddRequest> request(CliCommand.KnownAdd command, Option<String> configuredOwner) {
        return command.owner()
                      .orElse(configuredOwner)
                      .toResult(CliError.Usage.FACTORY.apply("known add needs an owner: --owner, TEMPER_OWNER or BB_OWNER"))
                      .map(owner -> KnownAddRequest.knownAddRequest(command.testId(),
                                                                    command.issue(),
                                                                    command.reason(),
                                                                    command.until(),
                                                                    owner));
    }

    private static Outcome listed(List<KnownDto> entries) {
        return entries.isEmpty()
               ? Outcome.outcome(ExitCode.ACCEPTED)
               : Outcome.outcome(String.join("\n",
                                             entries.stream().map(Lines::known).toList()),
                                 ExitCode.ACCEPTED);
    }

    private static Outcome shown(KnownDto entry) {
        return Outcome.outcome(Lines.known(entry), ExitCode.ACCEPTED);
    }

    record unused() implements KnownCommands {}
}
