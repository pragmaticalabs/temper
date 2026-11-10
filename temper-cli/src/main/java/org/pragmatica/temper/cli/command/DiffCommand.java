package org.pragmatica.temper.cli.command;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.api.DiffAnswer;
import org.pragmatica.temper.protocol.api.DiffRefusal;
import org.pragmatica.temper.protocol.api.DiffView;


/// `diff <jobA> <jobB> [--json]` and `diff <job> --base <sha> [--json]` (m2-design §3.4): one `GET /diff`, printed as
/// its sections and counts line ([Lines#diff]) or its refusal line ([Lines#diffRefused]), or with `--json` as the
/// `DiffAnswer`. The exit code (ruling R-M2-9 on Q-M2-6) is the same either way:
///
/// - 0: nothing newly failing (skipped → failed included), no added failing test, and side B ran tests; 1 otherwise,
///   an EMPTY B included; a skip change never raises it ([ExitCode#forDiff(int, int, int)], ruling R-M2-13);
/// - 8: a side not judged (VOID, ERROR, CANCELLED), a pruned side, no base or a conflicting base; 9: a side not
///   terminal ([ExitCode#forDiff(org.pragmatica.temper.domain.diff.DiffObstacle)]);
/// - 64: usage, an unknown job (404) or a rejected query (400); 69: unreachable. Nothing is retried.
public sealed interface DiffCommand {
    static Promise<Outcome> diff(Controller api, JsonMapper json, CliCommand.Diff command) {
        return answer(api, command).flatMap(answer -> rendered(answer, command.json(), json).async());
    }

    private static Promise<DiffAnswer> answer(Controller api, CliCommand.Diff command) {
        return switch (command.against()) {
            case CliCommand.Diff.Against.Job other -> api.diff(other.job(), command.job());
            case CliCommand.Diff.Against.Base base -> api.diffBase(command.job(), base.sha());
        };
    }

    private static Result<Outcome> rendered(DiffAnswer answer, boolean asJson, JsonMapper json) {
        return switch (answer) {
            case DiffView view -> outcome(answer, Lines.diff(view), exit(view), asJson, json);
            case DiffRefusal refusal -> outcome(answer,
                                                Lines.diffRefused(refusal),
                                                ExitCode.forDiff(refusal.reason()),
                                                asJson,
                                                json);
        };
    }

    private static ExitCode exit(DiffView view) {
        return ExitCode.forDiff(view.counts().newlyFailing(),
                                view.counts().addedFailing(),
                                view.b().testcases());
    }

    private static Result<Outcome> outcome(DiffAnswer answer,
                                           String text,
                                           ExitCode exit,
                                           boolean asJson,
                                           JsonMapper json) {
        return Rendered.text(answer, text, asJson, json).map(output -> Outcome.outcome(output, exit));
    }

    record unused() implements DiffCommand {}
}
