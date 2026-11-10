package org.pragmatica.temper.cli.command;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.api.ResultRecordDto;

import static org.pragmatica.lang.Result.success;


/// Turns controller DTOs into [Outcome]s: the one-line form, or with `--json` the full DTO (spec §8.1); the
/// exit code is the same either way.
sealed interface Rendered {
    /// A terminal job with its result record: the verdict's exit code (spec §8.1).
    static Result<Outcome> verdict(ResultRecordDto record,
                                   Fn1<String, ResultRecordDto> line,
                                   boolean json,
                                   JsonMapper mapper) {
        return Result.all(verdictExit(record),
                          text(record,
                               line.apply(record),
                               json,
                               mapper))
                     .map((exit, text) -> Outcome.outcome(text, exit));
    }

    /// A job without a result record to show: the state's exit code (PENDING 9 while not terminal, Q-15).
    static Result<Outcome> state(JobView view, String verb, boolean json, JsonMapper mapper) {
        return state(view,
                     verb,
                     ExitCode.forState(view.state()),
                     json,
                     mapper);
    }

    /// A job shown with an exit code its state does not determine (`wait` timeout, 7).
    static Result<Outcome> state(JobView view, String verb, ExitCode exit, boolean json, JsonMapper mapper) {
        return text(view, Lines.state(verb, view), json, mapper).map(text -> Outcome.outcome(text, exit));
    }

    /// The one-line form, or the DTO as JSON with `--json`.
    static Result<String> text(Object dto, String line, boolean json, JsonMapper mapper) {
        return json
               ? mapper.writeAsString(dto)
                       .mapError(cause -> CliError.BadPayload.FACTORY.apply("--json output", cause))
               : success(line);
    }

    /// The verdict string is the upper-case terminal job state (`JobState.fromVerdict`).
    private static Result<ExitCode> verdictExit(ResultRecordDto record) {
        return Result.lift(thrown -> CliError.BadPayload.FACTORY.apply("result verdict " + record.verdict(),
                                                                       Causes.fromThrowable(thrown)),
                           () -> JobState.valueOf(record.verdict()))
                     .map(ExitCode::forState);
    }

    record unused() implements Rendered {}
}
