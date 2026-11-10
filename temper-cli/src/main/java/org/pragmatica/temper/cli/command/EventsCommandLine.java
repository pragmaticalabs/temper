package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.command.Args.Shape;
import org.pragmatica.temper.cli.transport.FleetPosition;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;


/// The `events` grammar: three forms, chosen by the option that names each (`--until`,
/// `--create`, else `--subscriber … --follow`). A list (`--until a,b`, `--match k=v,k=v`) is comma-separated; `--match`
/// may also be repeated.
sealed interface EventsCommandLine {
    Shape UNTIL = Shape.shape(Set.of("--until", "--match", "--after", "--timeout"),
                              Set.of("--include-catch-up"),
                              0,
                              false);

    Shape FOLLOW = Shape.shape(Set.of("--subscriber", "--format"), Set.of("--follow"), 0, false);
    Shape CREATE = Shape.shape(Set.of("--create", "--start", "--bind-to"), Set.of(), 0, false);
    Set<String> BIND_TO = Set.of("caller", "read");
    /// A kind as the controller names it: lower kebab case.
    Pattern KIND = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    Pattern START = Pattern.compile("^(head|seq:[0-9]{1,19})$");
    String HEAD = "head";
    String FORMS = "events needs --until <kind,…>, --subscriber <name> --follow, or --create <name>";

    static Shape shape(Args args) {
        return args.isGiven("--until")
               ? UNTIL
               : args.isGiven("--create")
                 ? CREATE
                 : FOLLOW;
    }

    static Result<CliCommand> events(Args args) {
        return args.isGiven("--until")
               ? until(args)
               : args.isGiven("--create")
                 ? create(args)
                 : follow(args);
    }

    private static Result<CliCommand> until(Args args) {
        return Result.all(kinds(args.optionValue("--until").or("")),
                          matches(args),
                          after(args),
                          timeout(args))
                     .map((kinds, match, after, timeout) -> new CliCommand.EventsUntil(kinds,
                                                                                       match,
                                                                                       after,
                                                                                       args.hasFlag("--include-catch-up"),
                                                                                       timeout));
    }

    private static Result<List<String>> kinds(String raw) {
        return Result.allOf(List.of(raw.split(",", -1)).stream().map(EventsCommandLine::kind));
    }

    private static Result<String> kind(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             KIND,
                             CliError.Usage.FACTORY.apply("--until takes event kinds, comma-separated (pr-head-moved,check-concluded): '" + raw
                                                         + "'"));
    }

    private static Result<List<EventMatch>> matches(Args args) {
        return args.optionValue("--match")
                   .map(EventsCommandLine::pairs)
                   .or(() -> success(List.of()));
    }

    private static Result<List<EventMatch>> pairs(String raw) {
        return Result.allOf(List.of(raw.split(",", -1)).stream().map(EventMatch::eventMatch));
    }

    private static Result<Option<FleetPosition>> after(Args args) {
        return args.optionValue("--after")
                   .map(raw -> FleetPosition.fleetPosition(raw).map(Option::some))
                   .or(() -> success(none()));
    }

    private static Result<Option<Duration>> timeout(Args args) {
        return args.optionValue("--timeout")
                   .map(raw -> CommandLine.duration(raw).map(Option::some))
                   .or(() -> success(none()));
    }

    private static Result<CliCommand> follow(Args args) {
        return Result.all(args.optionValue("--subscriber").toResult(CliError.Usage.FACTORY.apply(FORMS)),
                          following(args),
                          isText(args))
                     .map((subscriber, _, text) -> new CliCommand.EventsFollow(subscriber, text));
    }

    private static Result<Unit> following(Args args) {
        return args.hasFlag("--follow")
               ? unitResult()
               : CommandLine.usage("events --subscriber <name> needs --follow");
    }

    private static Result<Boolean> isText(Args args) {
        return switch (args.optionValue("--format")
                           .or("ndjson")) {
            case "ndjson" -> success(false);
            case "text" -> success(true);
            case String other -> CommandLine.usage("--format takes ndjson or text, got '" + other + "'");
        };
    }

    private static Result<CliCommand> create(Args args) {
        return Result.all(args.optionValue("--create").toResult(CliError.Usage.FACTORY.apply(FORMS)),
                          start(args),
                          bindTo(args))
                     .map(CliCommand.EventsCreate::new);
    }

    private static Result<String> start(Args args) {
        return Verify.ensure(args.optionValue("--start").or(HEAD),
                             Verify.Is::matches,
                             START,
                             CliError.Usage.FACTORY.apply("--start takes head or seq:<n>"));
    }

    /// `caller` (the token that creates it) or `read` (the controller's configured read token, so a read-only
    /// follower can poll and ack).
    private static Result<String> bindTo(Args args) {
        return Verify.ensure(args.optionValue("--bind-to").or("caller"),
                             BIND_TO::contains,
                             CliError.Usage.FACTORY.apply("--bind-to takes caller or read"));
    }

    record unused() implements EventsCommandLine {}
}
