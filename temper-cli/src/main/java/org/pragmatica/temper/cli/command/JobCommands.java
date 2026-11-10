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
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.CancelResponse;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.RerunView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// `result`, `cancel` and `log`: one request each (m0-design §8 table).
public sealed interface JobCommands {
    /// `result <job>`: the verdict's exit code; 9 while the job is not terminal; a REFUSED job (no result
    /// record) exits 2 with its refusal code as `reason`.
    static Promise<Outcome> result(Controller api, JsonMapper json, CliCommand.ShowResult command) {
        return api.result(command.job())
                  .flatMap(found -> shown(api, json, command, found));
    }

    /// `cancel <job>`: 0 when the cancel took (CANCELLING, or CANCELLED for a job that had not started);
    /// a job that was already terminal prints that state and exits with its code [G].
    static Promise<Outcome> cancel(Controller api, JsonMapper json, CliCommand.Cancel command) {
        return api.job(command.job())
                  .flatMap(view -> cancelUnlessTerminal(api, json, command, view));
    }

    private static Promise<Outcome> cancelUnlessTerminal(Controller api,
                                                         JsonMapper json,
                                                         CliCommand.Cancel command,
                                                         JobView view) {
        return view.state()
                   .isTerminal()
               ? Rendered.state(view,
                                "cancel",
                                command.json(),
                                json)
                         .async()
               : requested(api, json, command);
    }

    private static Promise<Outcome> requested(Controller api, JsonMapper json, CliCommand.Cancel command) {
        return api.cancel(command.job())
                  .flatMap(response -> cancelled(json, command, response));
    }

    /// `log <job>`: the raw tail, printed as-is (the one multi-line output); 503 (host down) is exit 5.
    static Promise<Outcome> log(Controller api, CliCommand.Log command) {
        return api.log(command.job(),
                       command.unit(),
                       command.tail())
                  .map(text -> Outcome.outcome(withoutFinalNewline(text),
                                               ExitCode.ACCEPTED));
    }

    private static Promise<Outcome> shown(Controller api,
                                          JsonMapper json,
                                          CliCommand.ShowResult command,
                                          Option<ResultRecordDto> found) {
        return found.map(record -> verdict(api, json, command, record))
                    .or(() -> pending(api, json, command));
    }

    /// `--json` prints the record as stored. The line adds the job's reruns (m2-design §5.3), a live read of its view,
    /// for a record that can have any: only a `FAILED(tests)` Gate or ModuleTest is ever rerun.
    private static Promise<Outcome> verdict(Controller api,
                                            JsonMapper json,
                                            CliCommand.ShowResult command,
                                            ResultRecordDto record) {
        return ! command.json() && rerunnable(record)
               ? reruns(api, command.job()).flatMap(reruns -> rendered(json, command, record, reruns))
               : rendered(json, command, record, some(List.of()));
    }

    private static boolean rerunnable(ResultRecordDto record) {
        return record.verdict()
                     .equals("FAILED")
               && record.reason()
                        .filter("tests"::equals)
                        .isPresent()
               && (record.recipe() instanceof RecipeDto.Gate || record.recipe() instanceof RecipeDto.ModuleTest);
    }

    /// The record was read and decides the line and the exit code; the reruns are a live addition, so a failed read
    /// of them is absorbed (FER: the line shows `rerun=?` instead of turning a shown verdict into exit 69).
    private static Promise<Option<List<RerunView>>> reruns(Controller api, long job) {
        return api.job(job)
                  .map(view -> some(view.reruns()))
                  .recover(_ -> none());
    }

    private static Promise<Outcome> rendered(JsonMapper json,
                                             CliCommand.ShowResult command,
                                             ResultRecordDto record,
                                             Option<List<RerunView>> reruns) {
        return Rendered.verdict(record,
                                shown -> Lines.result(shown, reruns),
                                command.json(),
                                json)
                       .async();
    }

    /// No result record: the job is not terminal yet, or it was refused (no evaluation).
    private static Promise<Outcome> pending(Controller api, JsonMapper json, CliCommand.ShowResult command) {
        return api.job(command.job())
                  .flatMap(view -> Rendered.state(view,
                                                  "result",
                                                  command.json(),
                                                  json)
                                           .async());
    }

    private static Promise<Outcome> cancelled(JsonMapper json, CliCommand.Cancel command, CancelResponse response) {
        return Result.all(Rendered.text(response,
                                        Lines.cancelled(command.job(),
                                                        response),
                                        command.json(),
                                        json),
                          cancelExit(command.job(),
                                     response))
                     .map(Outcome::outcome)
                     .async();
    }

    /// The answer to a cancel of a job that was not terminal when read. CANCELLING, CANCELLED (a job that had
    /// not started) and SUPERSEDED (already on its way to CANCELLED) mean the cancel took: 0. Another terminal
    /// state means the job finished in between: its own code. QUEUED, UNKNOWN or RUNNING would mean the controller
    /// ignored the cancel, which its contract (m0-design §4.3) rules out: exit 69 rather than a false 0.
    private static Result<ExitCode> cancelExit(long job, CancelResponse response) {
        return switch (response.state()) {
            case CANCELLING, CANCELLED, SUPERSEDED -> success(ExitCode.ACCEPTED);
            case QUEUED, UNKNOWN, RUNNING -> CliError.CancelIgnored.FACTORY.apply(job, response.state()).result();
            case JobState other -> success(ExitCode.forState(other));
        };
    }

    /// The console adds the final line break back.
    private static String withoutFinalNewline(String text) {
        return text.endsWith("\n")
               ? text.substring(0, text.length() - 1)
               : text;
    }

    record unused() implements JobCommands {}
}
