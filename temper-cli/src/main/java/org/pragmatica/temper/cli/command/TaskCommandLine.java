package org.pragmatica.temper.cli.command;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.command.Args.Shape;

import static org.pragmatica.lang.Result.success;


/// The `task` group's grammar (m3-design §11.2). A list option (`--issue`, `--after`, `--job`) takes its values
/// comma-separated in one argument [G: the parser takes each option once].
sealed interface TaskCommandLine {
    Shape CREATE = Shape.shape(Set.of("--slug",
                                      "--title",
                                      "--repo",
                                      "--base",
                                      "--branch",
                                      "--brief",
                                      "--pr",
                                      "--issue",
                                      "--prio",
                                      "--after",
                                      "--round-cap",
                                      "--owner"),
                               Set.of("--design", "--critical", "--json"),
                               0,
                               false);

    Shape LIST = Shape.shape(Set.of("--stage", "--status"), Set.of("--json"), 0, false);
    Shape SHOW = Shape.shape(Set.of(), Set.of("--json"), 1, false);
    Shape NEXT = Shape.shape(Set.of("--stage"), Set.of("--json"), 0, false);
    Shape CLAIM_DIRECTED = Shape.shape(Set.of("--worker", "--checkout"), Set.of("--json"), 1, false);

    Shape CLAIM_HEAD = Shape.shape(Set.of("--stage", "--worker", "--checkout"), Set.of("--json"), 0, false);

    Shape ONE = Shape.shape(Set.of(), Set.of(), 1, false);
    Shape WAIT = Shape.shape(Set.of("--job"), Set.of("--ci"), 1, false);
    Shape RELEASE = Shape.shape(Set.of("--reason"), Set.of(), 1, false);
    Shape PARK = Shape.shape(Set.of(), Set.of("--lease"), 2, false);

    Shape RULE = Shape.shape(Set.of("--option", "--text", "--know", "--extra-rounds"), Set.of(), 1, false);

    Shape CLOSE = Shape.shape(Set.of(), Set.of("--merged", "--abandoned"), 1, false);
    Shape BOUNCE = Shape.shape(Set.of("--item"), Set.of(), 1, false);

    /// `claim` takes `<id>` or `--stage S`: the form is the one a positional names.
    static Shape claimShape(Args args) {
        return args.positionals()
                   .isEmpty()
               ? CLAIM_HEAD
               : CLAIM_DIRECTED;
    }

    static Result<CliCommand> create(Args args) {
        return Result.all(required(args, "--slug"),
                          required(args, "--title"),
                          required(args, "--repo"),
                          required(args, "--base"),
                          required(args, "--branch"),
                          required(args, "--brief"),
                          optionalNumber(args, "--pr"),
                          numbers(args, "--issue"),
                          optionalNumber(args, "--prio"),
                          ids(args, "--after"),
                          optionalNumber(args, "--round-cap")).map((slug, title, repo, base, branch, brief, pr, issues, prio, after, roundCap) -> new CliCommand.TaskCommand.Create(slug,
                                                                                                                                                                                    title,
                                                                                                                                                                                    repo,
                                                                                                                                                                                    base,
                                                                                                                                                                                    branch,
                                                                                                                                                                                    brief,
                                                                                                                                                                                    pr,
                                                                                                                                                                                    issues,
                                                                                                                                                                                    args.hasFlag("--design"),
                                                                                                                                                                                    args.hasFlag("--critical"),
                                                                                                                                                                                    prio,
                                                                                                                                                                                    after,
                                                                                                                                                                                    roundCap,
                                                                                                                                                                                    args.optionValue("--owner"),
                                                                                                                                                                                    args.hasFlag("--json")));
    }

    static Result<CliCommand> list(Args args) {
        return success(new CliCommand.TaskCommand.ListTasks(args.optionValue("--stage"),
                                                            args.optionValue("--status"),
                                                            args.hasFlag("--json")));
    }

    static Result<CliCommand> show(Args args) {
        return task(args).map(id -> new CliCommand.TaskCommand.Show(id, args.hasFlag("--json")));
    }

    static Result<CliCommand> events(Args args) {
        return task(args).map(id -> new CliCommand.TaskCommand.Events(id, args.hasFlag("--json")));
    }

    static Result<CliCommand> next(Args args) {
        return success(new CliCommand.TaskCommand.Next(args.optionValue("--stage"), args.hasFlag("--json")));
    }

    /// `claim <id>` or `claim --stage S`, never both and never neither.
    static Result<CliCommand> claim(Args args) {
        return args.positionals()
                   .isEmpty()
               ? required(args, "--stage").map(stage -> claimOf(args, Option.none(), Option.some(stage)))
               : task(args).map(id -> claimOf(args, Option.some(id), Option.none()));
    }

    private static CliCommand claimOf(Args args, Option<Long> task, Option<String> stage) {
        return new CliCommand.TaskCommand.Claim(task,
                                                stage,
                                                args.optionValue("--worker"),
                                                args.optionValue("--checkout"),
                                                args.hasFlag("--json"));
    }

    static Result<CliCommand> heartbeat(Args args) {
        return task(args).map(CliCommand.TaskCommand.Heartbeat::new);
    }

    /// `--job J,…` or `--ci`, or both.
    static Result<CliCommand> waitOn(Args args) {
        return Result.all(task(args), ids(args, "--job")).flatMap((id, jobs) -> awaiting(id, jobs, args.hasFlag("--ci")));
    }

    private static Result<CliCommand> awaiting(long task, List<Long> jobs, boolean ci) {
        return jobs.isEmpty() && !ci
               ? CommandLine.usage("task wait needs --job J,… or --ci")
               : success(new CliCommand.TaskCommand.Wait(task, jobs, ci));
    }

    static Result<CliCommand> release(Args args) {
        return Result.all(task(args), required(args, "--reason")).map(CliCommand.TaskCommand.Release::new);
    }

    static Result<CliCommand> park(Args args) {
        return Result.all(task(args), requestFile(args)).map((id, file) -> new CliCommand.TaskCommand.Park(id,
                                                                                                           file,
                                                                                                           args.hasFlag("--lease")));
    }

    private static Result<String> requestFile(Args args) {
        return Option.from(args.positionals().stream().skip(1).findFirst()).toResult(CliError.Usage.FACTORY.apply("task park needs <request.json>"));
    }

    static Result<CliCommand> rule(Args args) {
        return Result.all(task(args),
                          required(args, "--option"),
                          required(args, "--text"),
                          optionalNumber(args, "--extra-rounds")).map((id, option, text, extra) -> new CliCommand.TaskCommand.Rule(id,
                                                                                                                                   option,
                                                                                                                                   text,
                                                                                                                                   args.optionValue("--know"),
                                                                                                                                   extra));
    }

    /// Exactly one of `--merged` and `--abandoned`.
    static Result<CliCommand> close(Args args) {
        return args.hasFlag("--merged") == args.hasFlag("--abandoned")
               ? CommandLine.usage("task close needs exactly one of --merged and --abandoned")
               : task(args).map(id -> new CliCommand.TaskCommand.Close(id, args.hasFlag("--merged")));
    }

    static Result<CliCommand> bounce(Args args) {
        return Result.all(task(args), required(args, "--item")).map(CliCommand.TaskCommand.Bounce::new);
    }

    // ---- values ------------------------------------------------------------------------------------------------------
    private static Result<Long> task(Args args) {
        return positive(args.positionals().getFirst(),
                        "task id");
    }

    private static Result<String> required(Args args, String option) {
        return args.optionValue(option)
                   .toResult(CliError.Usage.FACTORY.apply("task needs " + option));
    }

    private static Result<Option<Integer>> optionalNumber(Args args, String option) {
        return args.optionValue(option)
                   .map(raw -> integer(raw, option).map(Option::some))
                   .or(() -> success(Option.none()));
    }

    private static Result<Integer> integer(String raw, String option) {
        return Number.parseInt(raw).mapError(cause -> CliError.BadSetting.FACTORY.apply(option, raw, cause));
    }

    private static Result<List<Integer>> numbers(Args args, String option) {
        return Result.allOf(values(args, option).stream().map(raw -> integer(raw, option)).toList());
    }

    private static Result<List<Long>> ids(Args args, String option) {
        return Result.allOf(values(args, option).stream().map(raw -> positive(raw, option)).toList());
    }

    private static List<String> values(Args args, String option) {
        return args.optionValue(option)
                   .map(raw -> Arrays.stream(raw.split(","))
                                     .map(String::strip)
                                     .filter(value -> !value.isEmpty())
                                     .toList())
                   .or(List.of());
    }

    private static Result<Long> positive(String raw, String label) {
        return Number.parseLong(raw)
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply(label, raw, cause))
                     .flatMap(id -> Verify.ensure(id,
                                                  Verify.Is::positive,
                                                  CliError.Usage.FACTORY.apply(label + " must be positive: " + raw)));
    }

    record unused() implements TaskCommandLine {}
}
