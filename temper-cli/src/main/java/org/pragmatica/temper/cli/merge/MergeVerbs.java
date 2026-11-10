package org.pragmatica.temper.cli.merge;

import java.util.regex.Pattern;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.cli.config.CliConfig;
import org.pragmatica.temper.cli.config.TokenFile;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.protocol.json.TemperJson;


/// The T2 verbs' entry: the token each verb uses, then the verb. `merge-gate` (and the queue view
/// and control-path list) use the ordinary CLI token, READ being enough; every VERDICT verb uses only its
/// `--token-file` (R2-2). Neither path is ever printed (R3-1, [TokenFile]).
public sealed interface MergeVerbs {
    /// `added_by` (`^[!-~]{1,64}$`).
    Pattern OWNER = Pattern.compile("^[!-~]{1,64}$");
    String UNKNOWN_OWNER = "unknown";

    static Promise<Outcome> run(CliCommand.MergeCommand command,
                                CliContext context,
                                MergePorts ports,
                                Fn1<HttpTransport, CliConfig> transport) {
        return config(command, context).async()
                     .flatMap(config -> dispatched(command,
                                                   context,
                                                   ports,
                                                   config,
                                                   MergeApi.mergeApi(transport.apply(config),
                                                                     TemperJson.mapper())))
                     .mapError(MergeLines::unmapped);
    }

    private static Result<CliConfig> config(CliCommand.MergeCommand command, CliContext context) {
        return command instanceof CliCommand.VerdictScoped scoped
               ? TokenFile.verdict(context, scoped.tokenFile())
               : TokenFile.ordinary(context);
    }

    private static Promise<Outcome> dispatched(CliCommand.MergeCommand command,
                                               CliContext context,
                                               MergePorts ports,
                                               CliConfig config,
                                               MergeApi api) {
        var git = Git.git(ports.runner(), context);

        return switch (command) {
            case CliCommand.MergeGate gate -> MergeGateCommand.gate(gate,
                                                                    Evaluator.evaluator(api, git, ports, context.home()),
                                                                    api,
                                                                    ports.started(),
                                                                    MergeLines.asOf(context.clock()));
            case CliCommand.MergeQueueView view -> QueueView.view(view,
                                                                  Evaluator.evaluator(api, git, ports, context.home()),
                                                                  api,
                                                                  ports);
            case CliCommand.ControlPathsList list -> EditRuns.pathsList(list, api);
            case CliCommand.MergeGo merge -> MergeRun.merge(merge, api, git, ports, context.home(), context.console());
            case CliCommand.RegisterVerdict verdict -> VerdictRun.verdict(verdict, api, git, ports, context.home());
            case CliCommand.MergeQueueAdd add -> owner(config.ownerOr(add.owner())).async().flatMap(owner -> EditRuns.queueAdd(add,
                                                                                                                               api,
                                                                                                                               owner));
            case CliCommand.PrAuthorList list -> ReviewRuns.authors(list, api);
            case CliCommand.PrAuthorAdd add -> owner(config.owner()).async().flatMap(owner -> ReviewRuns.addAuthors(add,
                                                                                                                    api,
                                                                                                                    owner));
            case CliCommand.Review review -> ReviewRuns.review(review, api, context.home());
            case CliCommand.MergeQueueRemove remove -> EditRuns.queueRemove(remove, api);
            case CliCommand.MergeQueueMove move -> EditRuns.queueMove(move, api);
            case CliCommand.ControlPathsAdd add -> owner(config.owner()).async().flatMap(owner -> EditRuns.pathAdd(add,
                                                                                                                   api,
                                                                                                                   owner));
            case CliCommand.ControlPathsRemove remove -> EditRuns.pathRemove(remove, api);
        };
    }

    /// Owner as `submit` (`--owner`, `TEMPER_OWNER`, `BB_OWNER`), `unknown` when none; printable ASCII, 1-64.
    private static Result<String> owner(Option<String> given) {
        var owner = given.or(UNKNOWN_OWNER);

        return Verify.ensure(owner,
                             Verify.Is::matches,
                             OWNER,
                             CliError.Usage.FACTORY.apply("--owner must be 1-64 printable ASCII characters"));
    }

    record unused() implements MergeVerbs {}
}
