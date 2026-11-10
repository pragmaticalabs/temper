package org.pragmatica.temper.cli.command;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.config.LeaseFiles;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.SubmitAnswer;
import org.pragmatica.temper.cli.transport.TaskApi;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.RefusedResponse;
import org.pragmatica.temper.protocol.api.SubmitRequest;
import org.pragmatica.temper.protocol.api.SubmitResponse;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// `submit`: parse the request locally with the controller's own domain factories (a bad SHA, owner or
/// timeout is exit 64 without a round trip), POST it, and print the answer. Exit 0 accepted, 10 deduplicated
/// (the id is still printed), 2 refused (spec §8.1). With `--task <id>` (m3-design §6.4) the submit goes under that
/// task's lease (its lease file), linked to the task at the lease's epoch; a lost lease is exit 12.
public sealed interface SubmitCommand {
    /// Shown when the job's SHA cannot be read back after an accepted submit.
    String UNKNOWN_SHA = "?";

    static Promise<Outcome> submit(Controller api,
                                   TaskApi tasks,
                                   JsonMapper json,
                                   CliCommand.Submit command,
                                   Option<String> owner,
                                   CliContext context) {
        return request(command, owner).async()
                      .flatMap(request -> sent(api, tasks, command, request, context))
                      .flatMap(answer -> answered(api, json, command, answer));
    }

    private static Promise<SubmitAnswer> sent(Controller api,
                                              TaskApi tasks,
                                              CliCommand.Submit command,
                                              SubmitRequest request,
                                              CliContext context) {
        return command.task()
                      .map(task -> underLease(tasks, request, task, context))
                      .or(() -> api.submit(request));
    }

    private static Promise<SubmitAnswer> underLease(TaskApi tasks,
                                                    SubmitRequest request,
                                                    long task,
                                                    CliContext context) {
        return LeaseFiles.held(context, task)
                         .async()
                         .flatMap(held -> submittedUnder(tasks, request, task, held));
    }

    /// A `409 lease-lost` deletes the lease file (the lease is gone); an accepted submit keeps it.
    private static Promise<SubmitAnswer> submittedUnder(TaskApi tasks,
                                                        SubmitRequest request,
                                                        long task,
                                                        LeaseFiles.Held held) {
        return tasks.submit(request.forTask(task,
                                            held.epoch()),
                            held.token())
                    .withFailure(cause -> LeaseFiles.forgetIfLost(held, cause));
    }

    private static Result<SubmitRequest> request(CliCommand.Submit command, Option<String> owner) {
        return owner.toResult(CliError.Usage.FACTORY.apply("submit needs an owner: --owner, TEMPER_OWNER or BB_OWNER"))
                    .map(submittedBy -> SubmitRequest.submitRequest(submittedBy,
                                                                    command.priority(),
                                                                    some(command.keep()),
                                                                    some(command.fresh()),
                                                                    command.source(),
                                                                    command.recipe(),
                                                                    command.unitTimeout(),
                                                                    none(),
                                                                    none(),
                                                                    some(command.dedicatedCpu())))
                    .flatMap(SubmitCommand::parsed);
    }

    /// Parse, don't validate, on the client too: the request goes out only if the domain accepts it.
    private static Result<SubmitRequest> parsed(SubmitRequest request) {
        return request.toDomain()
                      .mapError(CliError.InvalidRequest.FACTORY)
                      .map(_ -> request);
    }

    private static Promise<Outcome> answered(Controller api,
                                             JsonMapper json,
                                             CliCommand.Submit command,
                                             SubmitAnswer answer) {
        return switch (answer) {
            case SubmitAnswer.Accepted accepted -> accepted(api, json, command, accepted.response());
            case SubmitAnswer.Refused refused -> refused(json, command, refused.response());
        };
    }

    private static Promise<Outcome> refused(JsonMapper json, CliCommand.Submit command, RefusedResponse response) {
        return Rendered.text(response,
                             Lines.refused(response),
                             command.json(),
                             json)
                       .map(text -> Outcome.outcome(text, ExitCode.REFUSED))
                       .async();
    }

    private static Promise<Outcome> accepted(Controller api,
                                             JsonMapper json,
                                             CliCommand.Submit command,
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

    private static Promise<Outcome> acceptedLine(Controller api, CliCommand.Submit command, SubmitResponse response) {
        return shortSha(api, response.job()).map(sha -> Outcome.outcome(Lines.submitted(response,
                                                                                        sha,
                                                                                        recipe(command.recipe())),
                                                                        exit(response)));
    }

    /// The SHA the controller resolved for the job. The submit already succeeded and the id, state and exit
    /// code come from its answer; the SHA is a convenience, so a failed read-back is absorbed (FER: degrade
    /// to `sha=?` instead of turning an accepted submit into exit 69, which would invite a blind resubmit).
    private static Promise<String> shortSha(Controller api, long job) {
        return api.job(job)
                  .map(SubmitCommand::shortSha)
                  .recover(_ -> UNKNOWN_SHA);
    }

    static String shortSha(JobView view) {
        return view.source()
                   .sha()
                   .flatMap(raw -> Sha.sha(raw).option())
                   .map(Sha::shortForm)
                   .or(UNKNOWN_SHA);
    }

    private static ExitCode exit(SubmitResponse response) {
        return response.deduplicated()
               ? ExitCode.DEDUPLICATED
               : ExitCode.ACCEPTED;
    }

    static String recipe(RecipeDto recipe) {
        return switch (recipe) {
            case RecipeDto.Mvn _ -> "mvn";
            case RecipeDto.ModuleTest _ -> "module-test";
            case RecipeDto.Gate _ -> "gate";
            case RecipeDto.Mutate _ -> "mutate";
            case RecipeDto.Rerun _ -> "rerun";
        };
    }

    record unused() implements SubmitCommand {}
}
