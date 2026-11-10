package org.pragmatica.temper.cli.command;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.cli.Console;
import org.pragmatica.temper.cli.Outcome;
import org.pragmatica.temper.cli.alarm.AlarmCheck;
import org.pragmatica.temper.cli.alarm.AlarmSink;
import org.pragmatica.temper.cli.alarm.Condition;
import org.pragmatica.temper.cli.alarm.Delivered;
import org.pragmatica.temper.cli.alarm.Evaluation;
import org.pragmatica.temper.cli.alarm.GithubApi;
import org.pragmatica.temper.cli.alarm.GithubIssueSink;
import org.pragmatica.temper.cli.config.AlarmConfig;
import org.pragmatica.temper.cli.config.CliConfig;
import org.pragmatica.temper.cli.transport.Controller;
import org.pragmatica.temper.cli.transport.HttpTransport;
import org.pragmatica.temper.cli.transport.VersionCheck;
import org.pragmatica.temper.domain.exit.ExitCode;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


/// `alarm-check`: one stateless check of the maintenance alarm, run by `ops/temper-alarm.timer` on the controller
/// host. It evaluates every condition ([AlarmCheck]), reports each undetermined one on stderr, delivers the others
/// (and `check-blind`, firing when any was undetermined) to the configured sink, and prints one line per delivered
/// condition: `alarm <code> FIRING|CLEAR <what the sink did>[: <detail>]`.
///
/// Exit, in this precedence: 2 the sink failed to deliver ([org.pragmatica.temper.cli.CliError.SinkFailed],
/// `SinkRejected`), each firing condition then on stderr as `temper: alarm <code> FIRING UNDELIVERED: <detail>`; 64 a
/// configuration error, or a condition that could not be evaluated (a path that cannot be measured, a controller answer
/// outside the contract), whose own issue is then left as it is; 3 at least one condition firing, delivered; 0 every
/// condition clear. Firing is 3, not 1, so a JVM crash (exit 1) never passes as a delivered alarm. 64 wins over 3,
/// since a check that cannot see is the more urgent fault, and 2 wins over both, since then nobody was told.
public sealed interface AlarmCommand {
    /// The controller must answer within this, or it is down.
    Duration CONTROLLER_TIMEOUT = Duration.ofSeconds(10);
    /// The whole GitHub delivery (the list read, then per condition at most 50 comment pages and two writes). With
    /// [AlarmCheck#WORST_CASE] (90 s) and JVM start, a run ends within about 90 + 120 + 10 = 220 s, under the unit's
    /// `TimeoutStartSec=420` and the 10-minute timer.
    Duration DELIVERY_DEADLINE = Duration.ofSeconds(120);

    /// One instant, `started`, decides everything about the skip (the 7-day bound, whether it holds, the printed line),
    /// so a run that crosses UTC midnight never mixes two days. The skip line is printed as soon as the configuration
    /// parsed, so every later exit (0, 3, 2, 64) carries it.
    static Promise<Outcome> alarmCheck(CliConfig config, CliContext context) {
        var client = HttpTransport.httpClient();
        var started = context.clock().instant();

        return AlarmConfig.alarmConfig(context, started)
                          .flatMap(alarm -> skipPrinted(context.console(),
                                                        alarm,
                                                        started))
                          .mapWith(alarm -> sink(alarm, client, context),
                                   Setup::setup)
                          .async()
                          .flatMap(setup -> checked(controller(client, config, context),
                                                    setup,
                                                    context,
                                                    config.token(),
                                                    config.endpoint().api().toString(),
                                                    started));
    }

    /// The skip line on stdout, if a skip is configured; stdout gone is exit 5, as for any other line.
    private static Result<AlarmConfig> skipPrinted(Console console, AlarmConfig alarm, Instant started) {
        return skipLine(alarm, started).map(console::line)
                       .or(Result::unitResult)
                       .map(_ -> alarm);
    }

    /// The configuration with the sink it names, built (and its token read) before the controller is asked anything.
    record Setup(AlarmConfig alarm, AlarmSink sink) {
        public static Setup setup(AlarmConfig alarm, AlarmSink sink) {
            return new Setup(alarm, sink);
        }
    }

    /// `secret` (the controller token) is kept out of the `check-blind` detail.
    /// The delivery's time is read after the evaluation, so it follows the controller retries.
    static Promise<Outcome> checked(Controller api,
                                    Setup setup,
                                    CliContext context,
                                    String secret,
                                    String apiBase,
                                    Instant started) {
        return AlarmCheck.evaluate(api,
                                   setup.alarm(),
                                   context.clock(),
                                   started,
                                   context.pacer(),
                                   secret,
                                   apiBase)
                         .withSuccess(evaluation -> reported(context.console(),
                                                             evaluation,
                                                             secret))
                         .flatMap(evaluation -> delivered(setup,
                                                          evaluation,
                                                          context.clock(),
                                                          context.console()));
    }

    private static Result<AlarmSink> sink(AlarmConfig alarm, HttpClient client, CliContext context) {
        return switch (alarm.sink()) {
            case AlarmConfig.Sink.Stdout _ -> success(AlarmSink.stdout());
            case AlarmConfig.Sink.GithubIssue github -> AlarmConfig.token(context, github.tokenFile()).map(token -> githubSink(alarm,
                                                                                                                               github,
                                                                                                                               client,
                                                                                                                               token));
        };
    }

    private static AlarmSink githubSink(AlarmConfig alarm,
                                        AlarmConfig.Sink.GithubIssue github,
                                        HttpClient client,
                                        String token) {
        return GithubIssueSink.githubIssueSink(GithubApi.githubApi(client, github.api(), github.repo(), token),
                                               alarm.host(),
                                               github.repeat(),
                                               DELIVERY_DEADLINE);
    }

    /// The controller's answers pass the D-6 version check ([VersionCheck]), as every other command's. A skew is not
    /// exit 13 here (the lead's ruling): it leaves the controller checks undetermined, so `check-blind` fires and the exit
    /// is 64, which a timer cannot miss.
    private static Controller controller(HttpClient client, CliConfig config, CliContext context) {
        return Controller.controller(VersionCheck.versionChecked(HttpTransport.jdkHttpTransport(client,
                                                                                                config.endpoint().api(),
                                                                                                config.token(),
                                                                                                CONTROLLER_TIMEOUT),
                                                                 context.version(),
                                                                 context.console()),
                                     TemperJson.mapper());
    }

    /// Each undetermined condition on stderr, before anything is delivered (`withSuccess` runs it before the next step).
    private static Unit reported(Console console, Evaluation evaluation, String secret) {
        evaluation.undetermined()
                  .forEach(cause -> console.problem("alarm-check: not evaluated: " + Evaluation.redacted(Console.oneLine(cause),
                                                                                                         secret)));

        return unit();
    }

    /// A failed delivery (exit 2, the deadline cut included) first names each firing condition on stderr; the failure
    /// itself is printed by the caller, so the lambda leaves its cause to it.
    private static Promise<Outcome> delivered(Setup setup,
                                              Evaluation evaluation,
                                              InstantSource clock,
                                              Console console) {
        var now = clock.instant();

        return setup.sink()
                    .deliver(now,
                             evaluation.conditions())
                    .withFailure(_ -> undelivered(console, evaluation))
                    .map(delivered -> Outcome.outcome(lines(delivered),
                                                      exitCode(evaluation)));
    }

    /// `temper: alarm <code> FIRING UNDELIVERED: <detail>` on stderr for each firing condition: the journal names what
    /// nobody was told. Clear conditions print nothing.
    private static Unit undelivered(Console console, Evaluation evaluation) {
        evaluation.conditions()
                  .stream()
                  .filter(Condition.Firing.class::isInstance)
                  .map(Condition.Firing.class::cast)
                  .forEach(firing -> console.problem("alarm " + firing.code()
                                                                      .wire()
                                                    + " FIRING UNDELIVERED: " + oneLine(firing.detail())));

        return unit();
    }

    /// A configured skip is never silent: `SKIPPED: <codes> until <date>` while it holds, `SKIP EXPIRED: …` after.
    static Option<String> skipLine(AlarmConfig alarm, Instant now) {
        return alarm.skip()
                    .map(skip -> skipText(skip,
                                          AlarmConfig.today(now)));
    }

    private static String skipText(AlarmConfig.Skip skip, LocalDate today) {
        return skip.expiredOn(today)
               ? "SKIP EXPIRED: " + skip.codesText()
                + " on " + skip.until()
                + " (evaluated again; check-blind until removed)"
               : "SKIPPED: " + skip.codesText() + " until " + skip.until();
    }

    private static String lines(List<Delivered> delivered) {
        return String.join("\n",
                           delivered.stream().map(AlarmCommand::line).toList());
    }

    static String line(Delivered delivered) {
        var head = "alarm " + delivered.condition().code().wire();

        return switch (delivered.condition()) {
            case Condition.Firing firing -> head + " FIRING " + delivered.action() + ": " + oneLine(firing.detail());
            case Condition.Clear _ -> head + " CLEAR " + delivered.action();
        };
    }

    /// A multi-line detail (a dump failure's cause chain) on the line, its lines joined by " | ".
    private static String oneLine(String detail) {
        return detail.lines()
                     .map(String::strip)
                     .filter(Verify.Is::notBlank)
                     .collect(Collectors.joining(" | "));
    }

    private static ExitCode exitCode(Evaluation evaluation) {
        return evaluation.undetermined()
                         .isEmpty()
               ? deliveredExitCode(evaluation)
               : ExitCode.USAGE;
    }

    private static ExitCode deliveredExitCode(Evaluation evaluation) {
        return evaluation.firing()
               ? ExitCode.ALARM_FIRING
               : ExitCode.ALARM_CLEAR;
    }

    record unused() implements AlarmCommand {}
}
