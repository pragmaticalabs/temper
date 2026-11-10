package org.pragmatica.temper.cli.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.RefusalAddRequest;
import org.pragmatica.temper.protocol.api.RefusalDto;
import org.pragmatica.temper.protocol.api.RefusalMatcherDto;


/// `refusal list|add|expire` (m1-design §6): one request each, one [Lines#refusal] line per refusal, exit 0. A
/// code that exists is exit 2 ([CliError.Conflict]); a code or matcher the controller does not accept is 64. The
/// matcher file is read and decoded here, so an unreadable or malformed file is 64 without a round trip; the
/// controller alone judges the code and the matcher's fields.
public sealed interface RefusalCommands {
    static Promise<Outcome> refusal(Controller api, JsonMapper json, CliCommand.RefusalCommand command) {
        return switch (command) {
            case CliCommand.RefusalList list -> list(api, list);
            case CliCommand.RefusalAdd add -> register(api, json, add);
            case CliCommand.RefusalExpire expire -> expire(api, expire);
        };
    }

    /// `refusal list [--all]`: no refusals prints nothing.
    private static Promise<Outcome> list(Controller api, CliCommand.RefusalList command) {
        return api.refusals(command.all())
                  .map(RefusalCommands::listed);
    }

    /// `refusal add`: the matcher file, then the request.
    private static Promise<Outcome> register(Controller api, JsonMapper json, CliCommand.RefusalAdd command) {
        return matcher(json,
                       command.matcher()).map(matcher -> request(command, matcher))
                      .async()
                      .flatMap(api::newRefusal)
                      .map(RefusalCommands::shown);
    }

    private static Promise<Outcome> expire(Controller api, CliCommand.RefusalExpire command) {
        return api.expireRefusal(command.code())
                  .map(RefusalCommands::shown);
    }

    private static RefusalAddRequest request(CliCommand.RefusalAdd command, RefusalMatcherDto matcher) {
        return RefusalAddRequest.refusalAddRequest(command.code(),
                                                   matcher,
                                                   command.issue(),
                                                   command.reason(),
                                                   command.until());
    }

    private static Outcome listed(List<RefusalDto> refusals) {
        return refusals.isEmpty()
               ? Outcome.outcome(ExitCode.ACCEPTED)
               : Outcome.outcome(String.join("\n",
                                             refusals.stream().map(Lines::refusal).toList()),
                                 ExitCode.ACCEPTED);
    }

    private static Outcome shown(RefusalDto refusal) {
        return Outcome.outcome(Lines.refusal(refusal), ExitCode.ACCEPTED);
    }

    /// The `--matcher` file as the matcher DTO; an unknown kind fails the decode.
    private static Result<RefusalMatcherDto> matcher(JsonMapper json, String file) {
        return text(file).flatMap(text -> decoded(json, file, text));
    }

    private static Result<String> text(String file) {
        return Result.lift(thrown -> CliError.Unreadable.FACTORY.apply(file, Causes.fromThrowable(thrown)),
                           () -> Files.readString(Path.of(file)));
    }

    private static Result<RefusalMatcherDto> decoded(JsonMapper json, String file, String text) {
        return json.readString(text, RefusalMatcherDto.class)
                   .mapError(cause -> CliError.BadSetting.FACTORY.apply("--matcher", file, cause));
    }

    record unused() implements RefusalCommands {}
}
