package org.pragmatica.temper.cli.merge;

import java.nio.file.Path;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.domain.exit.ExitCode;

import static org.pragmatica.lang.Result.success;


/// `temper verdict <repo> <PR> <SHA> ACCEPT|FIX|REJECT --base <b> --by <x> (--report <path> | --carried-from <oldSha>)
/// --token-file <path>`.
///
/// - `--report`: absolute after `~/` expansion, read by [ReportCheck]; its deciding (last) `VERDICT:` line must name
///   exactly the arguments, else `refused report-mismatch` (exit 2) with no call; the line is sent as `verdictLine`.
/// - `--carried-from`: [CarryCheck] on the Mac first (exit 2, no call, on the first refusal); the server refuses a
///   missing ACCEPT (422 `CARRY_NO_ACCEPT` → `refused carry-no-accept`).
/// - Either form prints the first 12 of the 201's `reportSha256` (for a carry, the carried row's; ruling 11-03 item 4).
record VerdictRun(MergeApi api,
                  Git git,
                  MergePorts ports,
                  Path home,
                  CliCommand.RegisterVerdict command,
                  Budget budget) {
    static Promise<Outcome> verdict(CliCommand.RegisterVerdict command,
                                    MergeApi api,
                                    Git git,
                                    MergePorts ports,
                                    Path home) {
        var run = new VerdictRun(api, git, ports, home, command, ports.unbounded());

        return command.carriedFrom()
                      .map(run::carried)
                      .or(run::reported);
    }

    private Promise<Outcome> reported() {
        return command.report()
                      .toResult(CliError.Usage.FACTORY.apply("verdict needs --report <path> or --carried-from <oldSha>"))
                      .flatMap(this::reportPath)
                      .flatMap(path -> ReportCheck.read(path).map(report -> new Read(path, report)))
                      .async()
                      .flatMap(this::matched);
    }

    record Read(Path path, ReportCheck.Report report) {}

    /// `~/` is the home; the result must be absolute.
    private Result<Path> reportPath(String raw) {
        var path = raw.startsWith("~/")
                   ? home.resolve(raw.substring(2))
                   : Path.of(raw);

        return path.isAbsolute()
               ? success(path)
               : CliError.Usage.FACTORY.apply("--report must be an absolute path (or start with ~/)").result();
    }

    private Promise<Outcome> matched(Read read) {
        return ReportCheck.names(read.report(),
                                 new ReportCheck.VerdictLine(command.verdict(),
                                                             command.repo(),
                                                             Long.toString(command.pr()),
                                                             command.sha(),
                                                             command.base()))
               ? registered(new MergeWire.ReportVerdict(command.repo(),
                                                        command.pr(),
                                                        command.sha(),
                                                        command.base(),
                                                        command.verdict(),
                                                        command.by(),
                                                        read.path().toString(),
                                                        read.report().sha256(),
                                                        read.report().verdictLine().or("")))
               : Promise.success(MergeLines.refused(ReportCheck.MISMATCH));
    }

    private Promise<Outcome> carried(String oldSha) {
        return CarryCheck.refusal(git,
                                  budget,
                                  BaseCheck.cache(home,
                                                  command.repo()),
                                  ports.url(command.repo()),
                                  command.pr(),
                                  command.base(),
                                  command.sha(),
                                  oldSha,
                                  BaseCheck.runId(ports.nanos().getAsLong()))
                         .fold(result -> carryChecked(result, oldSha));
    }

    /// FER, fail closed: a git step that failed refuses the carry (`refused source-unavailable git <step>`), no call.
    private Promise<Outcome> carryChecked(Result<Option<String>> result, String oldSha) {
        return result.fold(cause -> Promise.success(MergeLines.refused(Reason.gitUnavailable(GateFault.stepOf(cause)).line())),
                           refusal -> refusal.map(reason -> Promise.success(MergeLines.refused(reason)))
                                             .or(() -> registered(new MergeWire.CarriedVerdict(command.repo(),
                                                                                               command.pr(),
                                                                                               command.sha(),
                                                                                               command.base(),
                                                                                               command.verdict(),
                                                                                               command.by(),
                                                                                               oldSha))));
    }

    private Promise<Outcome> registered(Object body) {
        return budget.bound()
                     .async()
                     .flatMap(bound -> api.verdict(body, bound))
                     .fold(result -> result.fold(cause -> MergeLines.refusedOrFailed(cause, MergeApi.VERDICTS),
                                                 registered -> Promise.success(Outcome.outcome(MergeLines.verdict(registered.id(),
                                                                                                                  command.repo(),
                                                                                                                  command.pr(),
                                                                                                                  command.sha(),
                                                                                                                  command.verdict(),
                                                                                                                  command.base(),
                                                                                                                  command.by(),
                                                                                                                  registered.reportSha256(),
                                                                                                                  registered.invalidated()),
                                                                                               ExitCode.ACCEPTED))));
    }
}
