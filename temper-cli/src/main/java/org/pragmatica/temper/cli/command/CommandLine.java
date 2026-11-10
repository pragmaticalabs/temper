package org.pragmatica.temper.cli.command;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.parse.TimeSpan;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.command.Args.Shape;
import org.pragmatica.temper.domain.model.ShaPrefix;
import org.pragmatica.temper.domain.subscribe.Selector;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Pure, hand-rolled command-line parsing (m0-design §8): `String[]` to [CliCommand]. Every failure is
/// [CliError.Usage] (exit 64), printed with [#USAGE].
public sealed interface CommandLine {
    String USAGE = """
        usage: temper <command> [options]
          submit gate (--ref R | --sha S) [common]
          submit module-test --pl a,b [--test T] [--it-test T] (--ref R | --sha S) [common]
          submit mvn (--ref R | --sha S) [common] -- <mvn args>
                 common: [--prio N] [--keep] [--fresh] [--dedicated-cpu] [--unit-timeout PT90S] [--owner O] [--json]
          wait <job> [--timeout 6h] [--json]
          result <job> [--json]
          watch [--terminal] --subscriber <name>
          subscribe <job|sha|ref> --subscriber <name>
          unsubscribe <job|sha|ref> --subscriber <name>
          jobs (--sha S | --ref R) [--json]
          cancel <job> [--json]
          log <job> [--unit U] [--tail N]
          digest [--since C] [--max N] [--json]
          gate <sha> [--wait] [--timeout 6h] [--json]
          diff <jobA> <jobB> [--json]  |  diff <job> --base <sha> [--json]
          rerun <job> [--prio N] [--fresh] [--dedicated-cpu] [--unit-timeout PT90M] [--owner O] [--json]
          refusal list [--all]
          refusal add <code> --matcher <file.json> --reason "…" [--issue N] [--until "…"]
          refusal expire <code>
          known list [--all] [--json]
          known add <testId> --issue N --reason "…" --until "<condition>" [--owner O]
          known expire <id>
          task create --slug S --title T --repo R --base B --branch BR --brief PATH[@SHA] [--pr N] [--issue N,…]
                      [--design] [--critical] [--prio N] [--after ID,…] [--round-cap N] [--owner O] [--json]
          task list [--stage S] [--status ST] [--json]  |  task show <id> [--json]  |  task events <id> [--json]
          task next [--stage S] [--json]
          task claim (<id> | --stage S) [--worker NAME] [--checkout DIR] [--json]
          task heartbeat <id>  |  task wait <id> (--job J,… | --ci)  |  task release <id> --reason R
          task park <id> <request.json> [--lease]
          task rule <id> --option X --text "…" [--know SHA] [--extra-rounds K]
          task close <id> --merged|--abandoned  |  task bounce <id> --item "…"
          events --until <kind,…> [--match k=v …] [--after <epoch>:<seq>] [--include-catch-up] [--timeout 30m]
          events --subscriber <name> --follow [--format text]
          events --create <name> [--start head|seq:<n>] [--bind-to caller|read]
          alarm-check
          tickets [--host <host>] [--done 0..200] [--json]
          merge-gate <repo> <PR> <SHA40>
          merge-gate --queue [--repo R] [--base B]
          merge-gate --queue add <repo> <PR> [--base B] [--owner O] --token-file <verdict-token>
          merge-gate --queue remove <repo> <PR> [--base B] --token-file <verdict-token>
          merge-gate --queue move <repo> <PR> --to N [--base B] --token-file <verdict-token>
          merge-gate --control-paths list --repo R  |  merge-gate --control-paths add|remove --repo R <glob> --token-file <verdict-token>
          merge <go-id> --token-file <verdict-token>
          verdict <repo> <PR> <SHA40> ACCEPT|FIX|REJECT --base B --by X (--report PATH | --carried-from SHA40) --token-file <verdict-token>
          pr-author <repo> <PR>  |  pr-author <repo> <PR> add <lane>… --token-file <verdict-token>
          review <repo> <PR> <SHA40> --as <verifier> [--body-file PATH] --token-file <verdict-token>
        tickets lists the bb tickets per host: owner liveness (SESSION-ALIVE, DETACHED(…), DEAD(…), STALE-SOURCE(…),
                UNKNOWN(no session field); ` agent=<name>` follows it, the session's liveness is not the agent's), the
                process fact, then the done tickets (default 10). Exit 0 all fresh, 4 the Mac source is stale or a host
                is UNREACHABLE (all still printed), 64 usage or unknown host, 69 controller unavailable, 77 token
                refused, 13 version skew
        merge-gate prints GO (exit 0, with the merge command) or NO with one reason per line (exit 1); 77 token refused,
                13 version skew; it reads only the ordinary token (READ is enough) and refuses --token-file. The VERDICT
                verbs (merge, verdict, the queue and control-path edits) read their token only from --token-file: no
                default path, TEMPER_TOKEN_FILE ignored
        merge   Enforced: the head by GitHub (--match-head-commit); single use and sibling invalidation by temper before the merge call; the base by temper, re-checked about a second before the merge call. GitHub's merge API has no base precondition: a base push landing between the re-check and the merge call is not caught.
                exit 0 merged, 2 refused (the GO is used: gate again), 3 the gh merge failed
        pr-author prints one line per author (`<repo>#<PR> <lane> added_by=<x> at=<ISO>`); add only appends. review posts the
                PR review for the verdict registered for <SHA40>; the event (APPROVE or REQUEST_CHANGES) is derived by the
                controller, never given. --body-file: UTF-8, at most 32 KiB. exit 0 posted, 2 refused (`refused <code>`),
                69 `unavailable <code>` (retry later), 64 usage, 77 token refused
        events --until is the agent form: run it in the background and be woken by its exit; it parses nothing. It
                prints the first matching event line and exits 0; 3 no heartbeat for 110 s, 4 a source the wait
                depends on is stale, 5 --timeout reached, 6 the controller's self-check failed, 7 the epoch changed
                (start again without --after). Catch-up rows wake it only with --include-catch-up. The next wait
                passes the line's epoch and seq as --after <epoch>:<seq>; without --after it starts at the head
        events --follow prints each event line, then acks it: delivery is at-least-once to stdout (a repeat carries
                the same seq), and a reader that dies loses what was printed and acked; use --until unless something
                reads stdout line by line. --create needs the full token; --follow and its acks the read token
        submit … --task <id> submits under that task's lease and links the job to it
        task claim writes the lease token to ~/.config/temper/leases/<task>-<epoch> (0600); the lease verbs and
                submit --task read it from there or from TEMPER_LEASE_FILE; exit 12 when the controller says lease-lost
        config: TEMPER_URL or ~/.config/temper/config (url = "http://host:7070"); token in ~/.config/temper/token;
                owner (submit, rerun, known add) from --owner, TEMPER_OWNER or BB_OWNER
        digest without --since reads ~/.config/temper/digest.cursor (none: start at the head), prints, then saves
                the new cursor there (0600); with --since C (a cursor it printed) it neither reads nor writes the file;
                --max N (1..1000, default 10) events per call; more=true means run it again
        alarm-check reads [alarm] in ~/.config/temper/config (TOML; every key optional except repo):
                sink = "github-issue" | "stdout"      repo = "owner/name"
                token-file = "~/.config/temper/alarm-token"   github-api = "https://api.github.com"
                repeat = "6h"   host = "<this host's name>"   stale-after = "26h"   min-free-gib = 50
                paths = ["~/.temper", "~/.local/share/temper/evidence"]   dump-dir = "~/.local/share/temper/evidence/pgdump"
                skip = ["dump-failed", "dump-stale"]  skip-until = "YYYY-MM-DD" (required with skip, at most 7 days
                ahead; past it the skipped codes are evaluated again and check-blind fires until the skip is removed)
                exit 0 all clear, 3 firing (delivered), 2 sink failed, 64 config error or a check not evaluated""";

    /// `wait` and `gate --wait` without `--timeout` (spec §8.2, m2-design §1.5).
    Duration DEFAULT_WAIT = Duration.ofHours(6);
    /// spec §8.2 verbs that exist but are not run yet.
    Set<String> LATER_VERBS = Set.of("evidence", "hosts", "prio");
    /// Verbs whose second word names the operation (`refusal|known list|add|expire`, m1-design §6, m2-design §4.4).
    Set<String> GROUPS = Set.of("refusal", "known", "task");

    Set<String> FLAGS = Set.of("--keep",
                               "--fresh",
                               "--dedicated-cpu",
                               "--json",
                               "--terminal",
                               "--all",
                               "--wait",
                               "--ledger",
                               "--design",
                               "--critical",
                               "--ci",
                               "--merged",
                               "--abandoned",
                               "--lease",
                               "--follow",
                               "--include-catch-up",
                               "--queue",
                               "--control-paths");

    Set<String> VALUED = Set.of("--ref",
                                "--sha",
                                "--bundle",
                                "--patch",
                                "--base",
                                "--spec",
                                "--pl",
                                "--test",
                                "--it-test",
                                "--prio",
                                "--unit-timeout",
                                "--owner",
                                "--timeout",
                                "--subscriber",
                                "--unit",
                                "--tail",
                                "--since",
                                "--max",
                                "--matcher",
                                "--reason",
                                "--issue",
                                "--until",
                                "--task",
                                "--slug",
                                "--title",
                                "--repo",
                                "--branch",
                                "--brief",
                                "--pr",
                                "--after",
                                "--round-cap",
                                "--stage",
                                "--status",
                                "--worker",
                                "--checkout",
                                "--job",
                                "--option",
                                "--text",
                                "--know",
                                "--extra-rounds",
                                "--item",
                                "--match",
                                "--create",
                                "--start",
                                "--bind-to",
                                "--format",
                                "--token-file",
                                "--to",
                                "--by",
                                "--report",
                                "--carried-from",
                                "--host",
                                "--done",
                                "--as",
                                "--body-file");

    /// Options that may be given more than once; their values join, comma-separated.
    Set<String> REPEATED = Set.of("--match");

    Shape SUBMIT = Shape.shape(Set.of("--ref",
                                      "--sha",
                                      "--bundle",
                                      "--patch",
                                      "--base",
                                      "--spec",
                                      "--pl",
                                      "--test",
                                      "--it-test",
                                      "--prio",
                                      "--unit-timeout",
                                      "--owner",
                                      "--task"),
                               Set.of("--keep", "--fresh", "--dedicated-cpu", "--json"),
                               1,
                               true);

    Shape WAIT = Shape.shape(Set.of("--timeout"), Set.of("--json"), 1, false);
    Shape RESULT = Shape.shape(Set.of(), Set.of("--json"), 1, false);
    Shape WATCH = Shape.shape(Set.of("--subscriber"), Set.of("--terminal"), 0, false);
    Shape SUBSCRIBE = Shape.shape(Set.of("--subscriber"), Set.of(), 1, false);
    Shape JOBS = Shape.shape(Set.of("--sha", "--ref"), Set.of("--json"), 0, false);
    Shape CANCEL = Shape.shape(Set.of(), Set.of("--json"), 1, false);
    Shape LOG = Shape.shape(Set.of("--unit", "--tail"), Set.of(), 1, false);
    Shape DIGEST = Shape.shape(Set.of("--since", "--max"), Set.of("--json"), 0, false);
    Shape GATE = Shape.shape(Set.of("--timeout"), Set.of("--wait", "--json"), 1, false);
    /// `diff <jobA> <jobB>`, and `diff <job> --base <sha>` (m2-design §3.4): the form is the one `--base` names.
    Shape DIFF_JOBS = Shape.shape(Set.of(), Set.of("--json"), 2, false);
    Shape DIFF_BASE = Shape.shape(Set.of("--base"), Set.of("--json"), 1, false);

    Shape RERUN = Shape.shape(Set.of("--prio", "--unit-timeout", "--owner"),
                              Set.of("--fresh", "--dedicated-cpu", "--json"),
                              1,
                              false);

    Shape REFUSAL_LIST = Shape.shape(Set.of(), Set.of("--all"), 0, false);

    Shape REFUSAL_ADD = Shape.shape(Set.of("--matcher", "--reason", "--issue", "--until"), Set.of(), 1, false);

    Shape REFUSAL_EXPIRE = Shape.shape(Set.of(), Set.of(), 1, false);
    Shape TICKETS = Shape.shape(Set.of("--host", "--done"), Set.of("--json"), 0, false);
    Shape ALARM_CHECK = Shape.shape(Set.of(), Set.of(), 0, false);
    /// `--ledger` is accepted so that `known list --ledger`, a spec §8.2 form the ledger's removal from M2 left for
    /// later (ruling R-M2-3), is `known - USAGE not in M0` rather than an unknown option.
    Shape KNOWN_LIST = Shape.shape(Set.of(), Set.of("--all", "--ledger", "--json"), 0, false);

    Shape KNOWN_ADD = Shape.shape(Set.of("--issue", "--reason", "--until", "--owner"), Set.of(), 1, false);

    Shape KNOWN_EXPIRE = Shape.shape(Set.of(), Set.of(), 1, false);

    Map<String, Grammar> VERBS = Map.ofEntries(Map.entry("submit", Grammar.grammar(SUBMIT, CommandLine::submitCommand)),
                                               Map.entry("wait", Grammar.grammar(WAIT, CommandLine::waitCommand)),
                                               Map.entry("result", Grammar.grammar(RESULT, CommandLine::resultCommand)),
                                               Map.entry("watch", Grammar.grammar(WATCH, CommandLine::watchCommand)),
                                               Map.entry("subscribe",
                                                         Grammar.grammar(SUBSCRIBE, CommandLine::subscribeCommand)),
                                               Map.entry("jobs", Grammar.grammar(JOBS, CommandLine::jobsCommand)),
                                               Map.entry("unsubscribe",
                                                         Grammar.grammar(SUBSCRIBE, CommandLine::unsubscribeCommand)),
                                               Map.entry("cancel", Grammar.grammar(CANCEL, CommandLine::cancelCommand)),
                                               Map.entry("log", Grammar.grammar(LOG, CommandLine::tailCommand)),
                                               Map.entry("digest", Grammar.grammar(DIGEST, CommandLine::digestCommand)),
                                               Map.entry("gate", Grammar.grammar(GATE, CommandLine::gateCommand)),
                                               Map.entry("diff",
                                                         Grammar.grammar(CommandLine::diffShape,
                                                                         CommandLine::diffCommand)),
                                               Map.entry("rerun", Grammar.grammar(RERUN, CommandLine::rerunCommand)),
                                               Map.entry("refusal list",
                                                         Grammar.grammar(REFUSAL_LIST,
                                                                         args -> success(refusalListCommand(args)))),
                                               Map.entry("refusal add",
                                                         Grammar.grammar(REFUSAL_ADD, CommandLine::refusalAddCommand)),
                                               Map.entry("refusal expire",
                                                         Grammar.grammar(REFUSAL_EXPIRE,
                                                                         args -> success(refusalExpireCommand(args)))),
                                               Map.entry("known list",
                                                         Grammar.grammar(KNOWN_LIST,
                                                                         args -> success(knownListCommand(args)))),
                                               Map.entry("known add",
                                                         Grammar.grammar(KNOWN_ADD, CommandLine::knownAddCommand)),
                                               Map.entry("known expire",
                                                         Grammar.grammar(KNOWN_EXPIRE, CommandLine::knownExpireCommand)),
                                               Map.entry("task create",
                                                         Grammar.grammar(TaskCommandLine.CREATE, TaskCommandLine::create)),
                                               Map.entry("task list",
                                                         Grammar.grammar(TaskCommandLine.LIST, TaskCommandLine::list)),
                                               Map.entry("task show",
                                                         Grammar.grammar(TaskCommandLine.SHOW, TaskCommandLine::show)),
                                               Map.entry("task events",
                                                         Grammar.grammar(TaskCommandLine.SHOW, TaskCommandLine::events)),
                                               Map.entry("task next",
                                                         Grammar.grammar(TaskCommandLine.NEXT, TaskCommandLine::next)),
                                               Map.entry("task claim",
                                                         Grammar.grammar(TaskCommandLine::claimShape,
                                                                         TaskCommandLine::claim)),
                                               Map.entry("task heartbeat",
                                                         Grammar.grammar(TaskCommandLine.ONE, TaskCommandLine::heartbeat)),
                                               Map.entry("task wait",
                                                         Grammar.grammar(TaskCommandLine.WAIT, TaskCommandLine::waitOn)),
                                               Map.entry("task release",
                                                         Grammar.grammar(TaskCommandLine.RELEASE,
                                                                         TaskCommandLine::release)),
                                               Map.entry("task park",
                                                         Grammar.grammar(TaskCommandLine.PARK, TaskCommandLine::park)),
                                               Map.entry("task rule",
                                                         Grammar.grammar(TaskCommandLine.RULE, TaskCommandLine::rule)),
                                               Map.entry("task close",
                                                         Grammar.grammar(TaskCommandLine.CLOSE, TaskCommandLine::close)),
                                               Map.entry("task bounce",
                                                         Grammar.grammar(TaskCommandLine.BOUNCE, TaskCommandLine::bounce)),
                                               Map.entry("events",
                                                         Grammar.grammar(EventsCommandLine::shape,
                                                                         EventsCommandLine::events)),
                                               Map.entry("merge-gate",
                                                         Grammar.grammar(MergeCommandLine::mergeGateShape,
                                                                         MergeCommandLine::mergeGate)),
                                               Map.entry("merge",
                                                         Grammar.grammar(MergeCommandLine.MERGE, MergeCommandLine::merge)),
                                               Map.entry("verdict",
                                                         Grammar.grammar(MergeCommandLine.VERDICT,
                                                                         MergeCommandLine::verdict)),
                                               Map.entry("pr-author",
                                                         Grammar.grammar(MergeCommandLine::prAuthorShape,
                                                                         MergeCommandLine::prAuthor)),
                                               Map.entry("review",
                                                         Grammar.grammar(MergeCommandLine.REVIEW,
                                                                         MergeCommandLine::review)),
                                               Map.entry("tickets",
                                                         Grammar.grammar(TICKETS, TicketsCommandLine::tickets)),
                                               Map.entry("alarm-check",
                                                         Grammar.grammar(ALARM_CHECK,
                                                                         _ -> success(new CliCommand.AlarmCheck()))));

    /// Submit forms that are valid spec §8.2 but not run yet: recipes and source kinds.
    Set<String> LATER_RECIPES = Set.of("mutate");
    Set<String> MODULE_TEST_OPTIONS = Set.of("--pl", "--test", "--it-test");
    Set<String> LATER_SOURCE_OPTIONS = Set.of("--bundle", "--patch", "--base", "--spec");

    static Result<CliCommand> parse(String[] argv) {
        return argv.length == 0
               ? usage("missing command")
               : parse(Arrays.asList(argv), verbWords(argv));
    }

    private static Result<CliCommand> parse(List<String> words, int verbWords) {
        return scan(Args.EMPTY,
                    words.subList(verbWords, words.size())).flatMap(args -> command(String.join(" ",
                                                                                                words.subList(0,
                                                                                                              verbWords)),
                                                                                    args));
    }

    /// A group verb takes its operation as the second word, when there is one.
    private static int verbWords(String[] argv) {
        return GROUPS.contains(argv[0]) && argv.length > 1
               ? 2
               : 1;
    }

    private static String operations(String group) {
        return VERBS.keySet()
                    .stream()
                    .filter(key -> key.startsWith(group + " "))
                    .map(key -> key.substring(group.length() + 1))
                    .sorted()
                    .collect(Collectors.joining("|"));
    }

    static <T> Result<T> usage(String detail) {
        return CliError.Usage.FACTORY.apply(detail).result();
    }

    private static Result<CliCommand> command(String verb, Args args) {
        return LATER_VERBS.contains(verb)
               ? success(new CliCommand.NotInM0(verb))
               : GROUPS.contains(verb)
                 ? usage("'" + verb + "' needs an operation: " + operations(verb))
                 : option(VERBS.get(verb)).toResult(CliError.Usage.FACTORY.apply("unknown command '" + verb + "'"))
                         .flatMap(grammar -> grammar.accepted(verb, args));
    }

    /// One verb's accepted shape, chosen from its arguments where the verb has two forms (`diff`), and the parse of its
    /// arguments once the shape is satisfied.
    record Grammar(Fn1<Shape, Args> shape, Fn1<Result<CliCommand>, Args> command) {
        static Grammar grammar(Shape shape, Fn1<Result<CliCommand>, Args> command) {
            return new Grammar(_ -> shape, command);
        }

        static Grammar grammar(Fn1<Shape, Args> shape, Fn1<Result<CliCommand>, Args> command) {
            return new Grammar(shape, command);
        }

        Result<CliCommand> accepted(String verb, Args args) {
            return args.restrict(verb,
                                 shape.apply(args))
                       .flatMap(command);
        }
    }

    // --- tokens ---
    /// One scanning step: the arguments so far and the tokens still to read.
    record Step(Args args, List<String> rest) {
        static Step step(Args args, List<String> rest) {
            return new Step(args, rest);
        }
    }

    private static Result<Args> scan(Args args, List<String> rest) {
        return rest.isEmpty()
               ? success(args)
               : step(args,
                      rest.getFirst(),
                      rest.subList(1, rest.size())).flatMap(next -> scan(next.args(), next.rest()));
    }

    private static Result<Step> step(Args args, String head, List<String> tail) {
        return switch (head) {
            case "--" -> success(Step.step(args.withPassthrough(tail), List.of()));
            case String flag when FLAGS.contains(flag) -> success(Step.step(args.withFlag(flag), tail));
            case String option when VALUED.contains(option) -> valued(args, option, tail);
            case String unknown when unknown.startsWith("-") -> usage("unknown option '" + optionName(unknown) + "'");
            default -> success(Step.step(args.withPositional(head), tail));
        };
    }

    /// The name of an `--x=value` token: its value is never echoed (2026-11-04 item 4, R3-1).
    private static String optionName(String token) {
        return token.split("=", 2) [0];
    }

    private static Result<Step> valued(Args args, String option, List<String> tail) {
        return tail.isEmpty()
               ? usage(option + " needs a value")
               : withValue(args, option, tail.getFirst()).map(next -> Step.step(next,
                                                                                tail.subList(1, tail.size())));
    }

    private static Result<Args> withValue(Args args, String option, String value) {
        return REPEATED.contains(option)
               ? success(args.withRepeated(option, value))
               : args.withOption(option, value);
    }

    // --- submit ---
    private static Result<CliCommand> submitCommand(Args args) {
        return isLaterForm(args)
               ? success(new CliCommand.NotInM0("submit"))
               : pushedSubmit(args);
    }

    private static Result<CliCommand> pushedSubmit(Args args) {
        return Result.all(source(args), recipe(args), priority(args), submitTask(args)).map((source, recipe, priority, task) -> new CliCommand.Submit(source,
                                                                                                                                                      recipe,
                                                                                                                                                      priority,
                                                                                                                                                      args.hasFlag("--keep"),
                                                                                                                                                      args.hasFlag("--fresh"),
                                                                                                                                                      args.optionValue("--unit-timeout"),
                                                                                                                                                      args.optionValue("--owner"),
                                                                                                                                                      args.hasFlag("--json"),
                                                                                                                                                      task,
                                                                                                                                                      args.hasFlag("--dedicated-cpu")));
    }

    /// `--task <id>` (m3-design §6.4): a positive task id.
    private static Result<Option<Long>> submitTask(Args args) {
        return args.optionValue("--task")
                   .map(raw -> taskId(raw).map(Option::some))
                   .or(() -> success(none()));
    }

    private static Result<Long> taskId(String raw) {
        return Number.parseLong(raw)
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply("--task", raw, cause))
                     .flatMap(id -> Verify.ensure(id,
                                                  Verify.Is::positive,
                                                  CliError.Usage.FACTORY.apply("--task must be positive: " + raw)));
    }

    private static boolean isLaterForm(Args args) {
        return LATER_RECIPES.contains(args.positionals().getFirst()) || LATER_SOURCE_OPTIONS.stream().anyMatch(args::isGiven);
    }

    /// Exactly one of `--ref` and `--sha`. A SHA must be the full 40 hex digits (Q-9); the domain parse in
    /// the submit step reports a short one.
    private static Result<SourceDto> source(Args args) {
        return args.isGiven("--ref") == args.isGiven("--sha")
               ? usage("submit needs exactly one of --ref R and --sha S")
               : success(new SourceDto.Pushed(args.optionValue("--ref"), args.optionValue("--sha")));
    }

    private static Result<RecipeDto> recipe(Args args) {
        return switch (args.positionals()
                           .getFirst()) {
            case "gate" -> gate(args);
            case "module-test" -> moduleTest(args);
            case "mvn" -> mvn(args);
            case String other -> usage("unknown recipe '" + other + "' (temper runs gate, module-test and mvn)");
        };
    }

    /// The root `clean install` (spec §3.2): no module selection and no mvn arguments.
    private static Result<RecipeDto> gate(Args args) {
        return MODULE_TEST_OPTIONS.stream().anyMatch(args::isGiven) || !args.passthrough()
                                                                            .isEmpty()
               ? usage("gate takes no --pl, --test, --it-test or '--' mvn arguments")
               : success(new RecipeDto.Gate());
    }

    private static Result<RecipeDto> moduleTest(Args args) {
        return args.passthrough()
                   .isEmpty()
               ? moduleTestRecipe(args)
               : usage("module-test takes no '--' mvn arguments (use mvn)");
    }

    private static Result<RecipeDto> moduleTestRecipe(Args args) {
        return args.optionValue("--pl")
                   .toResult(CliError.Usage.FACTORY.apply("module-test needs --pl a,b"))
                   .map(pl -> new RecipeDto.ModuleTest(modulePaths(pl),
                                                       args.optionValue("--test"),
                                                       args.optionValue("--it-test")));
    }

    /// `--pl a,b` as the list the recipe carries; each entry is judged by the domain parse.
    private static List<String> modulePaths(String commaSeparated) {
        return List.of(commaSeparated.split(",", -1));
    }

    private static Result<RecipeDto> mvn(Args args) {
        return MODULE_TEST_OPTIONS.stream().anyMatch(args::isGiven)
               ? usage("--pl, --test and --it-test belong to module-test; pass mvn arguments after '--'")
               : success(new RecipeDto.Mvn(args.passthrough()));
    }

    private static Result<Option<Integer>> priority(Args args) {
        return args.optionValue("--prio")
                   .map(CommandLine::priorityValue)
                   .or(() -> success(none()));
    }

    private static Result<Option<Integer>> priorityValue(String raw) {
        return Number.parseInt(raw)
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply("--prio", raw, cause))
                     .map(Option::some);
    }

    // --- job commands ---
    private static Result<CliCommand> waitCommand(Args args) {
        return Result.all(job(args), timeout(args)).map((job, timeout) -> new CliCommand.Wait(job,
                                                                                              timeout,
                                                                                              args.hasFlag("--json")));
    }

    private static Result<CliCommand> resultCommand(Args args) {
        return job(args).map(job -> new CliCommand.ShowResult(job, args.hasFlag("--json")));
    }

    private static Result<CliCommand> cancelCommand(Args args) {
        return job(args).map(job -> new CliCommand.Cancel(job, args.hasFlag("--json")));
    }

    private static Result<CliCommand> tailCommand(Args args) {
        return Result.all(job(args), tail(args)).map((job, tail) -> new CliCommand.Log(job,
                                                                                       args.optionValue("--unit"),
                                                                                       tail));
    }

    private static Result<CliCommand> digestCommand(Args args) {
        return Result.all(since(args), max(args)).map((since, max) -> new CliCommand.Digest(since,
                                                                                            max,
                                                                                            args.hasFlag("--json")));
    }

    /// The SHA is judged here as the controller judges it (7 to 40 hex, `ShaPrefix`), so a bad one is 64 without a call;
    /// `--timeout` without `--wait` bounds nothing and is a usage error.
    private static Result<CliCommand> gateCommand(Args args) {
        return Result.all(gateSha(args), gateTimeout(args)).map((sha, timeout) -> new CliCommand.Gate(sha,
                                                                                                      args.hasFlag("--wait"),
                                                                                                      timeout,
                                                                                                      args.hasFlag("--json")));
    }

    private static Result<ShaPrefix> gateSha(Args args) {
        return shaPrefix(args.positionals().getFirst());
    }

    private static Result<ShaPrefix> shaPrefix(String raw) {
        return ShaPrefix.shaPrefix(raw).mapError(cause -> CliError.BadSetting.FACTORY.apply("sha (7 to 40 hex)",
                                                                                            raw,
                                                                                            cause));
    }

    private static Result<Duration> gateTimeout(Args args) {
        return args.isGiven("--timeout") && !args.hasFlag("--wait")
               ? usage("--timeout bounds --wait; give both or neither")
               : timeout(args);
    }

    /// The parent's id is positive; `--prio` is an integer whose range the controller's domain parse judges.
    private static Result<CliCommand> rerunCommand(Args args) {
        return Result.all(job(args), priority(args)).map((job, priority) -> new CliCommand.Rerun(job,
                                                                                                 priority,
                                                                                                 args.hasFlag("--fresh"),
                                                                                                 args.optionValue("--unit-timeout"),
                                                                                                 args.optionValue("--owner"),
                                                                                                 args.hasFlag("--json"),
                                                                                                 args.hasFlag("--dedicated-cpu")));
    }

    private static Shape diffShape(Args args) {
        return args.isGiven("--base")
               ? DIFF_BASE
               : DIFF_JOBS;
    }

    /// Job ids are judged here (positive), the `--base` SHA as `gate`'s (7 to 40 hex), so a bad one is 64 without a call.
    private static Result<CliCommand> diffCommand(Args args) {
        return args.optionValue("--base")
                   .map(sha -> baseDiff(args, sha))
                   .or(() -> jobsDiff(args));
    }

    private static Result<CliCommand> jobsDiff(Args args) {
        return Result.all(jobAt(args, 0), jobAt(args, 1)).map((a, b) -> new CliCommand.Diff(new CliCommand.Diff.Against.Job(a),
                                                                                            b,
                                                                                            args.hasFlag("--json")));
    }

    private static Result<CliCommand> baseDiff(Args args, String sha) {
        return Result.all(jobAt(args, 0), shaPrefix(sha)).map((job, base) -> new CliCommand.Diff(new CliCommand.Diff.Against.Base(base),
                                                                                                 job,
                                                                                                 args.hasFlag("--json")));
    }

    private static Result<CliCommand> watchCommand(Args args) {
        return args.optionValue("--subscriber")
                   .toResult(CliError.Usage.FACTORY.apply("watch needs --subscriber <name>"))
                   .map(subscriber -> new CliCommand.Watch(subscriber,
                                                           args.hasFlag("--terminal")));
    }

    // --- selectors (m2-design §2.4) ---
    private static Result<CliCommand> subscribeCommand(Args args) {
        return Result.all(selectorSubscriber(args, "subscribe"), selector(args)).map(CliCommand.Subscribe::new);
    }

    private static Result<CliCommand> unsubscribeCommand(Args args) {
        return Result.all(selectorSubscriber(args, "unsubscribe"), selector(args)).map(CliCommand.Unsubscribe::new);
    }

    /// Exactly one of `--sha` and `--ref`; the controller judges the value (400, exit 64).
    private static Result<CliCommand> jobsCommand(Args args) {
        return args.isGiven("--sha") == args.isGiven("--ref")
               ? usage("jobs needs exactly one of --sha S and --ref R")
               : success(new CliCommand.Jobs(args.optionValue("--sha"),
                                             args.optionValue("--ref"),
                                             args.hasFlag("--json")));
    }

    private static Result<String> selectorSubscriber(Args args, String verb) {
        return required(args, "--subscriber", verb + " needs --subscriber <name>");
    }

    /// Classified here (`Selector.classified`), so a short SHA is 64 without a call; the controller judges the name.
    private static Result<Selector> selector(Args args) {
        var raw = args.positionals().getFirst();

        return Selector.classified(raw).mapError(cause -> CliError.BadSetting.FACTORY.apply("selector", raw, cause));
    }

    // --- refusals (m1-design §6) ---
    private static CliCommand refusalListCommand(Args args) {
        return new CliCommand.RefusalList(args.hasFlag("--all"));
    }

    /// The code and the matcher are judged by the controller (400, exit 64); the matcher file is read when the
    /// command runs.
    private static Result<CliCommand> refusalAddCommand(Args args) {
        var code = args.positionals().getFirst();

        return Result.all(required(args, "--matcher", "refusal add needs --matcher <file.json>"),
                          required(args, "--reason", "refusal add needs --reason \"…\""),
                          issue(args)).map((matcher, reason, issue) -> new CliCommand.RefusalAdd(code,
                                                                                                 matcher,
                                                                                                 issue,
                                                                                                 reason,
                                                                                                 args.optionValue("--until")));
    }

    private static CliCommand refusalExpireCommand(Args args) {
        return new CliCommand.RefusalExpire(args.positionals().getFirst());
    }

    // --- known-failure registry (m2-design §4.4) ---
    private static CliCommand knownListCommand(Args args) {
        return args.hasFlag("--ledger")
               ? new CliCommand.NotInM0("known")
               : new CliCommand.KnownList(args.hasFlag("--all"), args.hasFlag("--json"));
    }

    /// `--issue`, `--reason` and `--until` are required (`--until` because an entry must not outlive its condition);
    /// the test id and the texts are judged by the controller (400, exit 64).
    private static Result<CliCommand> knownAddCommand(Args args) {
        var testId = args.positionals().getFirst();

        return Result.all(required(args, "--issue", "known add needs --issue N").flatMap(CommandLine::knownIssue),
                          required(args, "--reason", "known add needs --reason \"…\""),
                          required(args, "--until", "known add needs --until \"<condition>\""))
                     .map((issue, reason, until) -> new CliCommand.KnownAdd(testId,
                                                                            issue,
                                                                            reason,
                                                                            until,
                                                                            args.optionValue("--owner")));
    }

    private static Result<CliCommand> knownExpireCommand(Args args) {
        return positiveId(args, 0, "known id").map(CliCommand.KnownExpire::new);
    }

    private static Result<String> required(Args args, String option, String detail) {
        return args.optionValue(option)
                   .toResult(CliError.Usage.FACTORY.apply(detail));
    }

    private static Result<Option<Integer>> issue(Args args) {
        return args.optionValue("--issue")
                   .map(CommandLine::issueNumber)
                   .or(() -> success(none()));
    }

    private static Result<Option<Integer>> issueNumber(String raw) {
        return issueValue(raw).map(Option::some);
    }

    private static Result<Integer> issueValue(String raw) {
        return Number.parseInt(raw).mapError(cause -> CliError.BadSetting.FACTORY.apply("--issue", raw, cause));
    }

    /// A known entry's issue is positive (as the controller judges it); a sign is a usage error here, not a 400.
    private static Result<Integer> knownIssue(String raw) {
        return issueValue(raw).flatMap(issue -> Verify.ensure(issue,
                                                              Verify.Is::positive,
                                                              CliError.Usage.FACTORY.apply("--issue must be positive: " + raw)));
    }

    private static Result<Long> job(Args args) {
        return jobAt(args, 0);
    }

    private static Result<Long> jobAt(Args args, int position) {
        return positiveId(args, position, "job id");
    }

    /// The positional at `position` as a positive id; `label` names it in the usage error (`job id`, `known id`).
    private static Result<Long> positiveId(Args args, int position, String label) {
        var raw = args.positionals().get(position);

        return Number.parseLong(raw)
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply(label, raw, cause))
                     .flatMap(id -> Verify.ensure(id,
                                                  Verify.Is::positive,
                                                  CliError.Usage.FACTORY.apply(label + " must be positive: " + raw)));
    }

    private static Result<Duration> timeout(Args args) {
        return args.optionValue("--timeout")
                   .map(CommandLine::duration)
                   .or(() -> success(DEFAULT_WAIT));
    }

    /// A positive `--timeout` (`10s`, `30m`, `6h`).
    static Result<Duration> duration(String raw) {
        return TimeSpan.timeSpan(raw)
                       .mapError(cause -> CliError.BadSetting.FACTORY.apply("--timeout", raw, cause))
                       .map(TimeSpan::duration)
                       .flatMap(value -> Verify.ensure(value,
                                                       Verify.Is::greaterThan,
                                                       Duration.ZERO,
                                                       CliError.Usage.FACTORY.apply("--timeout must be positive: " + raw)));
    }

    /// A cursor as `digest` printed it; a bare `seq` is not one (the controller refuses it too).
    private static Result<Option<String>> since(Args args) {
        return Verify.ensureOption(args.optionValue("--since"),
                                   Verify.Is::matches,
                                   DigestView.CURSOR,
                                   CliError.Usage.FACTORY.apply("--since takes a cursor as temper digest printed it (<seq>.<token>)"));
    }

    /// 1..1000, as the controller validates `max`.
    private static Result<Option<Integer>> max(Args args) {
        return args.optionValue("--max")
                   .map(CommandLine::maxValue)
                   .or(() -> success(none()));
    }

    private static Result<Option<Integer>> maxValue(String raw) {
        return Number.parseInt(raw)
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply("--max", raw, cause))
                     .flatMap(max -> Verify.ensure(max,
                                                   Verify.Is::between,
                                                   1,
                                                   DigestView.MAX_EVENTS,
                                                   CliError.Usage.FACTORY.apply("--max must be 1.." + DigestView.MAX_EVENTS
                                                                               + ": " + raw)))
                     .map(Option::some);
    }

    private static Result<Option<Integer>> tail(Args args) {
        return args.optionValue("--tail")
                   .map(CommandLine::tailLines)
                   .or(() -> success(none()));
    }

    private static Result<Option<Integer>> tailLines(String raw) {
        return Number.parseInt(raw)
                     .mapError(cause -> CliError.BadSetting.FACTORY.apply("--tail", raw, cause))
                     .flatMap(lines -> Verify.ensure(lines,
                                                     Verify.Is::positive,
                                                     CliError.Usage.FACTORY.apply("--tail must be positive: " + raw)))
                     .map(Option::some);
    }

    record unused() implements CommandLine {}
}
