package org.pragmatica.temper.cli;

import java.net.http.HttpClient;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.cli.command.AlarmCommand;
import org.pragmatica.temper.cli.command.CliCommand;
import org.pragmatica.temper.cli.command.CommandLine;
import org.pragmatica.temper.cli.command.DiffCommand;
import org.pragmatica.temper.cli.command.DigestCommand;
import org.pragmatica.temper.cli.command.FollowCommand;
import org.pragmatica.temper.cli.command.GateCommand;
import org.pragmatica.temper.cli.command.JobCommands;
import org.pragmatica.temper.cli.command.JobsCommand;
import org.pragmatica.temper.cli.command.KnownCommands;
import org.pragmatica.temper.cli.command.Lines;
import org.pragmatica.temper.cli.command.RefusalCommands;
import org.pragmatica.temper.cli.command.RerunCommand;
import org.pragmatica.temper.cli.command.SubmitCommand;
import org.pragmatica.temper.cli.command.SubscribeCommands;
import org.pragmatica.temper.cli.command.TaskVerbs;
import org.pragmatica.temper.cli.command.UntilCommand;
import org.pragmatica.temper.cli.command.WaitCommand;
import org.pragmatica.temper.cli.command.WatchCommand;
import org.pragmatica.temper.cli.config.CliConfig;
import org.pragmatica.temper.cli.merge.MergePorts;
import org.pragmatica.temper.cli.merge.MergeVerbs;
import org.pragmatica.temper.cli.tickets.TicketsVerb;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.FleetApi;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.cli.transport.Subscription;
import org.pragmatica.temper.cli.transport.TaskApi;
import org.pragmatica.temper.cli.transport.VersionCheck;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.unitResult;


/// One CLI invocation: parse, load the configuration, run the command against the controller, print, and
/// return the exit code (spec §8.1). Runs in-process for tests through [CliContext].
public sealed interface Cli {
    /// Blocks until the command finishes: this is the process's entry point.
    @TerminalOperation
    static int run(String[] args, CliContext context) {
        return run(args, context, MergePorts.production());
    }

    /// The same, with the T2 verbs' git base, process runner, `gh` port and budget given (only
    /// tests give other ones).
    @TerminalOperation
    static int run(String[] args, CliContext context, MergePorts ports) {
        return execute(args, context, ports).await()
                      .fold(cause -> failed(context, cause),
                            outcome -> finished(context, outcome))
                      .code();
    }

    static Promise<Outcome> execute(String[] args, CliContext context) {
        return execute(args, context, MergePorts.production());
    }

    static Promise<Outcome> execute(String[] args, CliContext context, MergePorts ports) {
        return CommandLine.parse(args)
                          .async()
                          .flatMap(command -> dispatch(command, context, ports));
    }

    private static Promise<Outcome> dispatch(CliCommand command, CliContext context, MergePorts ports) {
        return switch (command) {
            case CliCommand.NotInM0 later -> Promise.success(notInM0(later));
            case CliCommand.HttpCommand http -> configured(context).flatMap(config -> exchanged(http, config, context));
            case CliCommand.Watch watch -> configured(context).flatMap(config -> watch(watch, config, context));
            case CliCommand.RefusalCommand refusal -> configured(context).flatMap(config -> refusal(refusal,
                                                                                                    config,
                                                                                                    context));
            case CliCommand.Jobs jobs -> configured(context).flatMap(config -> jobs(jobs, config, context));
            case CliCommand.SelectorCommand selectors -> configured(context).flatMap(config -> selectors(selectors,
                                                                                                         config,
                                                                                                         context));
            case CliCommand.KnownCommand known -> configured(context).flatMap(config -> known(known, config, context));
            case CliCommand.TaskCommand task -> configured(context).flatMap(config -> task(task, config, context));
            case CliCommand.EventsCommand events -> configured(context).flatMap(config -> events(events, config, context));
            case CliCommand.AlarmCheck _ -> configured(context).flatMap(config -> AlarmCommand.alarmCheck(config,
                                                                                                          context));
            case CliCommand.Tickets tickets -> configured(context).flatMap(config -> TicketsVerb.tickets(tickets,
                                                                                                         transport(HttpTransport.httpClient(),
                                                                                                                   config,
                                                                                                                   context),
                                                                                                         TemperJson.mapper()));
            case CliCommand.MergeCommand merge -> MergeVerbs.run(merge,
                                                                 context,
                                                                 ports,
                                                                 config -> transport(HttpTransport.httpClient(),
                                                                                     config,
                                                                                     context));
        };
    }

    private static Promise<CliConfig> configured(CliContext context) {
        return CliConfig.cliConfig(context).async();
    }

    private static Promise<Outcome> exchanged(CliCommand.HttpCommand command, CliConfig config, CliContext context) {
        var json = TemperJson.mapper();

        return exchanged(command, config, context, controller(HttpTransport.httpClient(), config, context, json), json);
    }

    private static Controller controller(HttpClient client, CliConfig config, CliContext context, JsonMapper json) {
        return Controller.controller(transport(client, config, context), json);
    }

    /// Every controller call goes through the D-6 version check ([VersionCheck]).
    private static HttpTransport transport(HttpClient client, CliConfig config, CliContext context) {
        return VersionCheck.versionChecked(HttpTransport.jdkHttpTransport(client,
                                                                          config.endpoint().api(),
                                                                          config.token()),
                                           context.version(),
                                           context.console());
    }

    private static Promise<Outcome> exchanged(CliCommand.HttpCommand command,
                                              CliConfig config,
                                              CliContext context,
                                              Controller api,
                                              JsonMapper json) {
        return switch (command) {
            case CliCommand.Submit submit -> SubmitCommand.submit(api,
                                                                  taskApi(HttpTransport.httpClient(),
                                                                          config,
                                                                          context,
                                                                          json),
                                                                  json,
                                                                  submit,
                                                                  config.ownerOr(submit.owner()),
                                                                  context);
            case CliCommand.Wait wait -> WaitCommand.await(api, json, wait, context.clock(), context.pacer());
            case CliCommand.ShowResult show -> JobCommands.result(api, json, show);
            case CliCommand.Cancel cancel -> JobCommands.cancel(api, json, cancel);
            case CliCommand.Log log -> JobCommands.log(api, log);
            case CliCommand.Digest digest -> DigestCommand.digest(api, json, digest, context);
            case CliCommand.Gate gate -> GateCommand.gate(api, json, gate, context.clock(), context.pacer());
            case CliCommand.Diff diff -> DiffCommand.diff(api, json, diff);
            case CliCommand.Rerun rerun -> RerunCommand.rerun(api,
                                                              json,
                                                              rerun,
                                                              config.ownerOr(rerun.owner()));
        };
    }

    private static Promise<Outcome> refusal(CliCommand.RefusalCommand command, CliConfig config, CliContext context) {
        var json = TemperJson.mapper();

        return RefusalCommands.refusal(controller(HttpTransport.httpClient(), config, context, json), json, command);
    }

    private static Promise<Outcome> jobs(CliCommand.Jobs command, CliConfig config, CliContext context) {
        var json = TemperJson.mapper();

        return JobsCommand.jobs(controller(HttpTransport.httpClient(), config, context, json), json, command);
    }

    private static Promise<Outcome> selectors(CliCommand.SelectorCommand command,
                                              CliConfig config,
                                              CliContext context) {
        return SubscribeCommands.selectors(controller(HttpTransport.httpClient(), config, context, TemperJson.mapper()),
                                           command);
    }

    private static TaskApi taskApi(HttpClient client, CliConfig config, CliContext context, JsonMapper json) {
        return TaskApi.taskApi(transport(client, config, context), json);
    }

    private static Promise<Outcome> task(CliCommand.TaskCommand command, CliConfig config, CliContext context) {
        var json = TemperJson.mapper();

        return TaskVerbs.task(taskApi(HttpTransport.httpClient(), config, context, json),
                              json,
                              command,
                              config,
                              context,
                              ProcessRunner.processRunner());
    }

    private static Promise<Outcome> known(CliCommand.KnownCommand command, CliConfig config, CliContext context) {
        var json = TemperJson.mapper();

        return KnownCommands.known(controller(HttpTransport.httpClient(), config, context, json),
                                   json,
                                   command,
                                   config.owner());
    }

    /// `submit - USAGE not in M0` (exit 64): needs neither configuration nor the controller.
    private static Outcome notInM0(CliCommand.NotInM0 later) {
        return Outcome.outcome(Lines.notInM0(later.verb()),
                               ExitCode.USAGE);
    }

    private static Promise<Outcome> watch(CliCommand.Watch watch, CliConfig config, CliContext context) {
        var api = controller(HttpTransport.httpClient(), config, context, TemperJson.mapper());

        return WatchCommand.watch(Subscription.polled(api,
                                                      watch.subscriber(),
                                                      watch.terminal()),
                                  context.console(),
                                  context.pacer())
                           .map(_ -> Outcome.outcome(ExitCode.ACCEPTED));  // only satisfies the type: a watch ends by failing
    }

    /// `events`: a `--follow` ends only by failing, like `watch`. Both forms time on the monotonic
    /// clock.
    private static Promise<Outcome> events(CliCommand.EventsCommand command, CliConfig config, CliContext context) {
        var api = FleetApi.fleetApi(transport(HttpTransport.httpClient(), config, context), TemperJson.mapper());

        return switch (command) {
            case CliCommand.EventsUntil until -> UntilCommand.until(api,
                                                                    until,
                                                                    context.console(),
                                                                    context.monotonicClock(),
                                                                    context.pacer());
            case CliCommand.EventsFollow follow -> FollowCommand.follow(api,
                                                                        follow,
                                                                        context.console(),
                                                                        context.monotonicClock(),
                                                                        context.pacer()).map(_ -> Outcome.outcome(ExitCode.ACCEPTED));
            case CliCommand.EventsCreate create -> FollowCommand.create(api, create);
        };
    }

    /// Prints the outcome; if stdout is gone the line was not delivered, which is exit 5.
    private static ExitCode finished(CliContext context, Outcome outcome) {
        return outcome.output()
                      .map(context.console()::line)
                      .or(unitResult())
                      .fold(cause -> failed(context, cause),
                            _ -> outcome.exit());
    }

    /// Reports each failure on stderr, one line each (usage errors add the usage text), and maps it to its exit
    /// code.
    private static ExitCode failed(CliContext context, Cause cause) {
        cause.stream().forEach(member -> report(context, member));
        usageText(cause).onPresent(context.console()::detail);

        return CliError.exitCodeOf(cause);
    }

    private static Unit report(CliContext context, Cause cause) {
        return context.console()
                      .problem(Console.oneLine(cause));
    }

    /// The usage text, when any of the failures is a usage error.
    private static Option<String> usageText(Cause cause) {
        return cause.stream()
                    .anyMatch(CliError.Usage.class::isInstance)
               ? some(CommandLine.USAGE)
               : none();
    }

    record unused() implements Cli {}
}
