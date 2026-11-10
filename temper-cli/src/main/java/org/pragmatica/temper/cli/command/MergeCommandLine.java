package org.pragmatica.temper.cli.command;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.cli.CliError;
import org.pragmatica.temper.cli.command.Args.Shape;
import org.pragmatica.temper.cli.config.TokenFile;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// The T2 verbs' command lines: `merge-gate` in its gate, queue and control-path forms, `merge` and
/// `verdict`. Arguments are judged here, so a bad one is 64 without a call: the repo pattern, a positive PR, exactly
/// 40 hex for a SHA after lower-casing (F5: a prefix is 64), a UUID go id.
///
/// `--token-file`: required on every VERDICT verb, absent → `token-file-required: <verb> needs --token-file
/// <verdict-token>` (64); `merge-gate` itself refuses it (64). No message here names the path given.
sealed interface MergeCommandLine {
    Pattern REPO = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    Pattern SHA40 = Pattern.compile("^[0-9a-f]{40}$");
    Pattern PR = Pattern.compile("^[1-9][0-9]{0,17}$");
    Pattern GO_ID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    Pattern BY = Pattern.compile("^[!-~]{1,64}$");
    Pattern BASE = Pattern.compile("^[!-~]{1,255}$");
    Pattern GLOB = Pattern.compile("^[A-Za-z0-9_.*/-]+$");
    Pattern PARENT_SEGMENT = Pattern.compile("(^|/)\\.\\.(/|$)");
    Set<String> VERDICTS = Set.of("ACCEPT", "FIX", "REJECT");
    String TOKEN_FILE = "--token-file";
    String QUEUE = "--queue";
    String PATHS = "--control-paths";
    Shape GATE = Shape.shape(Set.of(), Set.of(), 3, false);
    Shape QUEUE_VIEW = Shape.shape(Set.of("--repo", "--base"), Set.of(QUEUE), 0, false);
    Shape QUEUE_ADD = Shape.shape(Set.of("--base", "--owner", TOKEN_FILE), Set.of(QUEUE), 3, false);
    Shape QUEUE_REMOVE = Shape.shape(Set.of("--base", TOKEN_FILE), Set.of(QUEUE), 3, false);
    Shape QUEUE_MOVE = Shape.shape(Set.of("--to", "--base", TOKEN_FILE), Set.of(QUEUE), 3, false);
    Shape PATHS_LIST = Shape.shape(Set.of("--repo"), Set.of(PATHS), 1, false);
    Shape PATHS_EDIT = Shape.shape(Set.of("--repo", TOKEN_FILE), Set.of(PATHS), 2, false);
    Shape MERGE = Shape.shape(Set.of(TOKEN_FILE), Set.of(), 1, false);
    Pattern LANE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    int MAX_LANES = 50;
    Shape PR_AUTHOR_LIST = Shape.shape(Set.of(), Set.of(), 2, false);
    Shape REVIEW = Shape.shape(Set.of("--as", "--body-file", TOKEN_FILE), Set.of(), 3, false);

    Shape VERDICT = Shape.shape(Set.of("--base", "--by", "--report", "--carried-from", TOKEN_FILE), Set.of(), 4, false);

    /// `pr-author <repo> <PR>` reads with the ordinary token; `... add <lane>…` takes any number of lanes and the
    /// VERDICT `--token-file`. Any other third word falls to the two-argument shape and is a usage error.
    static Shape prAuthorShape(Args args) {
        var positionals = args.positionals();

        return positionals.size() > 2 && "add".equals(positionals.get(2))
               ? Shape.shape(Set.of(TOKEN_FILE), Set.of(), positionals.size(), false)
               : PR_AUTHOR_LIST;
    }

    static Result<CliCommand> prAuthor(Args args) {
        var positionals = args.positionals();

        return positionals.size() == 2
               ? Result.all(repo(positionals.get(0)),
                            pr(positionals.get(1)))
                       .map(CliCommand.PrAuthorList::new)
               : Result.all(repo(positionals.get(0)),
                            pr(positionals.get(1)),
                            lanes(positionals.subList(3,
                                                      positionals.size())),
                            tokenFile(args, "pr-author add"))
                       .map(CliCommand.PrAuthorAdd::new);
    }

    private static Result<java.util.List<String>> lanes(java.util.List<String> raw) {
        return raw.isEmpty() || raw.size() > MAX_LANES
               ? CommandLine.usage("pr-author add takes 1 to " + MAX_LANES + " lane names")
               : Result.allOf(raw.stream().map(MergeCommandLine::lane).toList());
    }

    private static Result<String> lane(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             LANE,
                             CliError.Usage.FACTORY.apply("lane must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}: " + raw));
    }

    static Result<CliCommand> review(Args args) {
        var positionals = args.positionals();

        return Result.all(repo(positionals.get(0)),
                          pr(positionals.get(1)),
                          sha(positionals.get(2)),
                          args.optionValue("--as")
                              .toResult(CliError.Usage.FACTORY.apply("review needs --as <verifier>"))
                              .flatMap(MergeCommandLine::lane),
                          tokenFile(args, "review"))
                     .map((repo, pr, sha, as, token) -> new CliCommand.Review(repo,
                                                                              pr,
                                                                              sha,
                                                                              as,
                                                                              args.optionValue("--body-file"),
                                                                              token));
    }

    /// The `merge-gate` form its flag and first word name; both flags at once is refused by the shape.
    static Shape mergeGateShape(Args args) {
        return args.hasFlag(QUEUE)
               ? queueShape(firstWord(args))
               : args.hasFlag(PATHS)
                 ? pathsShape(firstWord(args))
                 : GATE;
    }

    private static String firstWord(Args args) {
        return args.positionals()
                   .isEmpty()
               ? ""
               : args.positionals()
                     .getFirst();
    }

    private static Shape queueShape(String word) {
        return switch (word) {
            case "add" -> QUEUE_ADD;
            case "remove" -> QUEUE_REMOVE;
            case "move" -> QUEUE_MOVE;
            default -> QUEUE_VIEW;
        };
    }

    private static Shape pathsShape(String word) {
        return switch (word) {
            case "add", "remove" -> PATHS_EDIT;
            default -> PATHS_LIST;
        };
    }

    static Result<CliCommand> mergeGate(Args args) {
        return args.hasFlag(QUEUE)
               ? queue(args)
               : args.hasFlag(PATHS)
                 ? paths(args)
                 : gate(args);
    }

    private static Result<CliCommand> gate(Args args) {
        return Result.all(repo(args.positionals().get(0)),
                          pr(args.positionals().get(1)),
                          sha(args.positionals().get(2)))
                     .map(CliCommand.MergeGate::new);
    }

    private static Result<CliCommand> queue(Args args) {
        return switch (firstWord(args)) {
            case "" -> success(new CliCommand.MergeQueueView(args.optionValue("--repo"), args.optionValue("--base")));
            case "add" -> Result.all(repoAt(args), prAt(args), tokenFile(args, "merge-gate --queue add")).map((repo, pr, token) -> new CliCommand.MergeQueueAdd(repo,
                                                                                                                                                                pr,
                                                                                                                                                                args.optionValue("--base"),
                                                                                                                                                                args.optionValue("--owner"),
                                                                                                                                                                token));
            case "remove" -> Result.all(repoAt(args), prAt(args), tokenFile(args, "merge-gate --queue remove")).map((repo, pr, token) -> new CliCommand.MergeQueueRemove(repo,
                                                                                                                                                                         pr,
                                                                                                                                                                         args.optionValue("--base"),
                                                                                                                                                                         token));
            case "move" -> Result.all(repoAt(args), prAt(args), to(args), tokenFile(args, "merge-gate --queue move")).map((repo, pr, to, token) -> new CliCommand.MergeQueueMove(repo,
                                                                                                                                                                                 pr,
                                                                                                                                                                                 to,
                                                                                                                                                                                 args.optionValue("--base"),
                                                                                                                                                                                 token));
            default -> CommandLine.usage("merge-gate --queue takes add, remove or move");
        };
    }

    private static Result<String> repoAt(Args args) {
        return repo(args.positionals().get(1));
    }

    private static Result<Long> prAt(Args args) {
        return pr(args.positionals().get(2));
    }

    private static Result<Integer> to(Args args) {
        return args.optionValue("--to")
                   .toResult(CliError.Usage.FACTORY.apply("merge-gate --queue move needs --to <n>"))
                   .flatMap(raw -> Number.parseInt(raw).mapError(cause -> CliError.BadSetting.FACTORY.apply("--to",
                                                                                                            raw,
                                                                                                            cause)))
                   .flatMap(to -> Verify.ensure(to,
                                                Verify.Is::positive,
                                                CliError.Usage.FACTORY.apply("--to must be a position, 1 or more")));
    }

    private static Result<CliCommand> paths(Args args) {
        return switch (firstWord(args)) {
            case "list" -> pathsRepo(args).map(CliCommand.ControlPathsList::new);
            case "add" -> Result.all(pathsRepo(args), glob(args), tokenFile(args, "merge-gate --control-paths add")).map(CliCommand.ControlPathsAdd::new);
            case "remove" -> Result.all(pathsRepo(args),
                                        glob(args),
                                        tokenFile(args, "merge-gate --control-paths remove")).map(CliCommand.ControlPathsRemove::new);
            default -> CommandLine.usage("merge-gate --control-paths takes list, add or remove");
        };
    }

    private static Result<String> pathsRepo(Args args) {
        return args.optionValue("--repo")
                   .toResult(CliError.Usage.FACTORY.apply("merge-gate --control-paths needs --repo <owner/name>"))
                   .flatMap(MergeCommandLine::repo);
    }

    /// The glob the store's CHECK accepts: its characters, not absolute, no `..` segment.
    private static Result<String> glob(Args args) {
        var raw = args.positionals().get(1);

        return GLOB.matcher(raw).matches() && !raw.startsWith("/") && !PARENT_SEGMENT.matcher(raw).find()
               ? success(raw)
               : CommandLine.usage("glob must be a relative path pattern of [A-Za-z0-9_.*/-] without '..': " + raw);
    }

    static Result<CliCommand> merge(Args args) {
        var raw = args.positionals().getFirst().toLowerCase(Locale.ROOT);

        return Result.all(GO_ID.matcher(raw).matches()
                          ? success(raw)
                          : CommandLine.<String> usage("go id must be a UUID: " + args.positionals().getFirst()),
                          tokenFile(args, "merge"))
                     .map(CliCommand.MergeGo::new);
    }

    static Result<CliCommand> verdict(Args args) {
        var positionals = args.positionals();

        return Result.all(repo(positionals.get(0)),
                          pr(positionals.get(1)),
                          sha(positionals.get(2)),
                          verdictWord(positionals.get(3)),
                          base(args),
                          by(args),
                          carriedFrom(args,
                                      positionals.get(3)),
                          tokenFile(args, "verdict"))
                     .map((repo, pr, sha, verdict, base, by, carried, token) -> new CliCommand.RegisterVerdict(repo,
                                                                                                               pr,
                                                                                                               sha,
                                                                                                               verdict,
                                                                                                               base,
                                                                                                               by,
                                                                                                               args.optionValue("--report"),
                                                                                                               carried,
                                                                                                               token));
    }

    /// Exactly one of `--report` and `--carried-from`; a carry is ACCEPT only and names a full SHA.
    private static Result<Option<String>> carriedFrom(Args args, String verdict) {
        return args.isGiven("--report") == args.isGiven("--carried-from")
               ? CommandLine.usage("verdict needs exactly one of --report <path> and --carried-from <oldSha>")
               : args.optionValue("--carried-from")
                     .map(raw -> carried(raw, verdict))
                     .or(() -> success(none()));
    }

    private static Result<Option<String>> carried(String raw, String verdict) {
        return "ACCEPT".equals(verdict)
               ? sha(raw).map(Option::some)
               : CommandLine.usage("--carried-from carries an ACCEPT only");
    }

    private static Result<String> verdictWord(String raw) {
        return VERDICTS.contains(raw)
               ? success(raw)
               : CommandLine.usage("verdict must be ACCEPT, FIX or REJECT: " + raw);
    }

    private static Result<String> base(Args args) {
        return args.optionValue("--base")
                   .toResult(CliError.Usage.FACTORY.apply("verdict needs --base <branch>"))
                   .flatMap(raw -> Verify.ensure(raw,
                                                 Verify.Is::matches,
                                                 BASE,
                                                 CliError.Usage.FACTORY.apply("--base must be 1-255 printable characters")));
    }

    private static Result<String> by(Args args) {
        return args.optionValue("--by")
                   .toResult(CliError.Usage.FACTORY.apply("verdict needs --by <name>"))
                   .flatMap(raw -> Verify.ensure(raw,
                                                 Verify.Is::matches,
                                                 BY,
                                                 CliError.Usage.FACTORY.apply("--by must be 1-64 printable ASCII characters")));
    }

    /// R2-2: the VERDICT verbs' token comes only from `--token-file`.
    private static Result<TokenFile> tokenFile(Args args, String verb) {
        return args.optionValue(TOKEN_FILE)
                   .map(TokenFile::tokenFile)
                   .toResult(CliError.Usage.FACTORY.apply("token-file-required: " + verb
                                                         + " needs --token-file <verdict-token>"));
    }

    static Result<String> repo(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             REPO,
                             CliError.Usage.FACTORY.apply("repo must be owner/name: " + raw));
    }

    static Result<Long> pr(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::matches,
                             PR,
                             CliError.Usage.FACTORY.apply("PR must be a positive integer: " + raw))
                     .flatMap(Number::parseLong);
    }

    /// Exactly 40 hex after lower-casing (F5).
    static Result<String> sha(String raw) {
        return Verify.ensure(raw.toLowerCase(Locale.ROOT),
                             Verify.Is::matches,
                             SHA40,
                             CliError.Usage.FACTORY.apply("SHA must be exactly 40 hex digits: " + raw));
    }

    record unused() implements MergeCommandLine {}
}
