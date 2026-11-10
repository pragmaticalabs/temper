package org.pragmatica.temper.cli.command;

import java.util.stream.Collectors;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.SubmitAnswer;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Priority;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitTimeout;
import org.pragmatica.temper.domain.model.Values;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.RerunRequest;
import org.pragmatica.temper.protocol.api.SubmitResponse;

import static org.pragmatica.lang.Option.some;


/// `rerun <job>` (m2-design §5.5): parse the owner and priority locally with the controller's own domain factories (a
/// bad one is exit 64 without a round trip), POST the rerun, and print the answer. Exit codes as for `submit`: 0
/// accepted, 10 deduplicated (the id is still printed), 2 refused (`REFUSED(rerun)`), 64 usage or an unknown job. A
/// repeat deduplicates onto the first rerun whatever `--prio` says: priority is not part of the dedup key (spec §2.3),
/// as for `submit`.
public sealed interface RerunCommand {
    /// Shown when the rerun job cannot be read back after an accepted rerun.
    String UNKNOWN = "?";

    static Promise<Outcome> rerun(Controller api, JsonMapper json, CliCommand.Rerun command, Option<String> owner) {
        return request(command, owner).async()
                      .flatMap(request -> api.rerun(command.job(),
                                                    request))
                      .flatMap(answer -> answered(api, json, command, answer));
    }

    private static Result<RerunRequest> request(CliCommand.Rerun command, Option<String> owner) {
        return owner.toResult(CliError.Usage.FACTORY.apply("rerun needs an owner: --owner, TEMPER_OWNER or BB_OWNER"))
                    .map(submittedBy -> RerunRequest.rerunRequest(submittedBy,
                                                                  command.priority(),
                                                                  some(command.fresh()),
                                                                  command.unitTimeout(),
                                                                  some(command.dedicatedCpu())))
                    .flatMap(RerunCommand::parsed);
    }

    /// A check on the client with the controller's own factories (a bad owner, priority or unit timeout is exit 64
    /// without a round trip); the request goes out as given, and the controller parses it again.
    private static Result<RerunRequest> parsed(RerunRequest request) {
        return Result.all(OwnerTag.ownerTag(request.submittedBy()),
                          Values.optional(request.priority(),
                                          raw -> Priority.priority(some(raw))),
                          Values.optional(request.timeout(),
                                          UnitTimeout::unitTimeout))
                     .map((_, _, _) -> request)
                     .mapError(CliError.InvalidRequest.FACTORY);
    }

    private static Promise<Outcome> answered(Controller api,
                                             JsonMapper json,
                                             CliCommand.Rerun command,
                                             SubmitAnswer answer) {
        return switch (answer) {
            case SubmitAnswer.Accepted accepted -> accepted(api, json, command, accepted.response());
            case SubmitAnswer.Refused refused -> refused(json, command, refused.response());
        };
    }

    private static Promise<Outcome> refused(JsonMapper json, CliCommand.Rerun command, RefusedResponse response) {
        return Rendered.text(response,
                             Lines.refused("rerun", response),
                             command.json(),
                             json)
                       .map(text -> Outcome.outcome(text, ExitCode.REFUSED))
                       .async();
    }

    private static Promise<Outcome> accepted(Controller api,
                                             JsonMapper json,
                                             CliCommand.Rerun command,
                                             SubmitResponse response) {
        return command.json()
               ? acceptedJson(json, response)
               : acceptedLine(api, command, response);
    }

    private static Promise<Outcome> acceptedJson(JsonMapper json, SubmitResponse response) {
        return Rendered.text(response, "", true, json)
                       .map(text -> Outcome.outcome(text,
                                                    exit(response)))
                       .async();
    }

    /// The rerun's classes and SHA are read back from its job view. The rerun already succeeded and the id, state and
    /// exit code come from its answer, so a failed read-back is absorbed (FER, as `submit` does: degrade to `classes=?
    /// sha=?` instead of turning an accepted rerun into exit 69, which would invite a blind repeat).
    private static Promise<Outcome> acceptedLine(Controller api, CliCommand.Rerun command, SubmitResponse response) {
        return api.job(response.job())
                  .map(view -> Lines.rerunSubmitted(response,
                                                    command.job(),
                                                    classes(view),
                                                    shortSha(view)))
                  .recover(_ -> Lines.rerunSubmitted(response,
                                                     command.job(),
                                                     UNKNOWN,
                                                     UNKNOWN))
                  .map(line -> Outcome.outcome(line,
                                               exit(response)));
    }

    private static String classes(JobView view) {
        return switch (view.recipe()) {
            case RecipeDto.Rerun rerun -> classNames(rerun);
            case RecipeDto.Mvn _, RecipeDto.ModuleTest _, RecipeDto.Gate _, RecipeDto.Mutate _ -> UNKNOWN;
        };
    }

    private static String classNames(RecipeDto.Rerun rerun) {
        return rerun.classes()
                    .stream()
                    .map(RecipeDto.Rerun.TargetDto::className)
                    .collect(Collectors.joining(","));
    }

    private static String shortSha(JobView view) {
        return view.source()
                   .sha()
                   .flatMap(raw -> Sha.sha(raw).option())
                   .map(Sha::shortForm)
                   .or(UNKNOWN);
    }

    private static ExitCode exit(SubmitResponse response) {
        return response.deduplicated()
               ? ExitCode.DEDUPLICATED
               : ExitCode.ACCEPTED;
    }

    record unused() implements RerunCommand {}
}
