package org.pragmatica.temper.cli.merge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// G8 and the base re-check of `temper merge`, in the gate cache
/// `~/.cache/temper/gate/<owner>/<name>.git` (a bare repository, made when absent). Each fetch writes its own
/// `refs/gate/<run>/{base,head}` (`<run>` = `<pid>-<nanos>`), reads them and deletes them, so concurrent gates do not
/// collide; the analysis afterwards works on the fetched SHAs, whose objects stay.
///
/// "Non-empty" is decided by trees, never SHAs: a commit `c` on `rev-list --first-parent from..to` changes the base
/// iff `c^{tree}` differs from `c^1^{tree}`, so an empty `know:` or `[skip ci]` commit does not count, and a change
/// and its revert count twice.
sealed interface BaseCheck {
    Pattern SHA40 = Pattern.compile("^[0-9a-f]{40}$");
    /// The cleanup of a run's refs has its own short bound: it runs after a spent budget too.
    Duration CLEANUP = Duration.ofSeconds(5);
    String AMBIGUOUS = "base-ambiguous";

    /// The fetched base tip and PR head.
    record Fetched(String base, String head) {}

    /// The non-empty commits of a first-parent range: how many, and the oldest.
    record Moved(int count, Option<String> first) {
        static final Moved NONE = new Moved(0, none());
    }

    static Path cache(Path home, String repo) {
        var parts = repo.split("/", 2);

        return home.resolve(".cache")
                   .resolve("temper")
                   .resolve("gate")
                   .resolve(parts[0])
                   .resolve(parts[1] + ".git");
    }

    static String runId(long nanos) {
        return ProcessHandle.current().pid() + "-" + nanos;
    }

    /// Fetch `refs/heads/<base>` and `refs/pull/<pr>/head` from `url` into the cache under this run's refs, read both,
    /// then delete the run's refs whatever happened.
    static Promise<Fetched> fetched(Git git, Budget budget, Path cache, String url, String base, long pr, String run) {
        return ensured(git, budget, cache).flatMap(_ -> git.output(budget,
                                                                   "fetch",
                                                                   cache,
                                                                   List.of("fetch",
                                                                           "-q",
                                                                           "--no-tags",
                                                                           "--force",
                                                                           url,
                                                                           "+" + HeadCheck.branchRef(base)
                                                                          + ":" + runRef(run, "base"),
                                                                           "+" + HeadCheck.pullRef(pr)
                                                                          + ":" + runRef(run, "head"))))
                      .flatMap(_ -> git.output(budget,
                                               "rev-parse",
                                               cache,
                                               List.of("rev-parse",
                                                       runRef(run, "base"),
                                                       runRef(run, "head"))))
                      .flatMap(text -> fetchedOf(text).async())
                      .fold(result -> cleaned(git, budget, cache, run).flatMap(_ -> result.async()));
    }

    private static String runRef(String run, String name) {
        return "refs/gate/" + run + "/" + name;
    }

    /// rev-parse answers one line per ref; anything else is the step's failure.
    private static Result<Fetched> fetchedOf(String text) {
        var lines = text.lines().toList();

        return lines.size() == 2
               ? Result.success(new Fetched(lines.get(0), lines.get(1)))
               : GateFault.GitFailed.FACTORY.apply("rev-parse",
                                                   Causes.cause("expected two SHAs, got: " + text.strip()))
                                            .result();
    }

    private static Promise<Unit> ensured(Git git, Budget budget, Path cache) {
        return Files.isDirectory(cache)
               ? Promise.success(unit())
               : created(cache).async()
                        .flatMap(_ -> git.output(budget,
                                                 "init",
                                                 List.of("init",
                                                         "--bare",
                                                         "-q",
                                                         cache.toString())))
                        .map(_ -> unit());
    }

    private static Result<Path> created(Path cache) {
        return Result.lift(thrown -> GateFault.GitFailed.FACTORY.apply("init", Causes.fromThrowable(thrown)),
                           () -> Files.createDirectories(cache.getParent()));
    }

    /// FER: a run ref that could not be deleted names only this run and holds objects the cache keeps anyway; the
    /// check's own answer stands, so the cleanup failure is dropped.
    private static Promise<Unit> cleaned(Git git, Budget budget, Path cache, String run) {
        var bounded = Budget.budget(budget.nanos(), CLEANUP);

        return Promise.all(deleted(git, bounded, cache, runRef(run, "base")),
                           deleted(git, bounded, cache, runRef(run, "head"))).map((_, _) -> unit());
    }

    private static Promise<Unit> deleted(Git git, Budget budget, Path cache, String ref) {
        return git.exitCode(budget,
                            "update-ref",
                            cache,
                            List.of("update-ref", "-d", ref))
                  .fold(_ -> Promise.success(unit()));
    }

    /// The fetch against G1's ls-remote: the base must be the value ls-remote showed, the head must be SHA.
    static List<Reason> mismatches(HeadCheck.Remote remote, Fetched fetched, String sha, long pr, String base) {
        return Stream.concat(remote.base()
                                   .filter(fetched.base()::equals)
                                   .isPresent()
                             ? Stream.empty()
                             : Stream.of(mismatch(HeadCheck.branchRef(base),
                                                  remote.base(),
                                                  fetched.base())),
                             sha.equals(fetched.head())
                             ? Stream.empty()
                             : Stream.of(mismatch(HeadCheck.pullRef(pr),
                                                  remote.pull(),
                                                  fetched.head())))
                     .toList();
    }

    private static Reason mismatch(String ref, Option<String> listed, String fetched) {
        return Reason.reason(Reason.Gate.G8,
                             "fetch-mismatch " + ref
                            + " " + listed.map(BaseCheck::sha12)
                                          .or("missing")
                            + " " + sha12(fetched));
    }

    /// The merge-base of `sha` and `base` (exactly one, else `base-ambiguous`), then the non-empty first-parent
    /// commits from it to `base` (`base-moved`).
    static Promise<List<Reason>> analysed(Git git, Budget budget, Path cache, String sha, String base) {
        return mergeBases(git, budget, cache, sha, base).flatMap(bases -> fromSingleBase(git, budget, cache, bases, base));
    }

    private static Promise<List<Reason>> fromSingleBase(Git git,
                                                        Budget budget,
                                                        Path cache,
                                                        List<String> bases,
                                                        String base) {
        return bases.size() == 1
               ? movedReasons(git, budget, cache, bases.getFirst(), base)
               : Promise.success(List.of(Reason.reason(Reason.Gate.G8, AMBIGUOUS)));
    }

    private static Promise<List<Reason>> movedReasons(Git git, Budget budget, Path cache, String from, String to) {
        return moved(git, budget, cache, from, to).map(BaseCheck::movedReasons);
    }

    private static List<Reason> movedReasons(Moved moved) {
        return moved.first()
                    .map(first -> List.of(Reason.reason(Reason.Gate.G8,
                                                        movedLine(moved.count(),
                                                                  first))))
                    .or(List.of());
    }

    static String movedLine(int count, String first) {
        return "base-moved " + count + " non-empty commits, first " + sha12(first);
    }

    static Promise<List<String>> mergeBases(Git git, Budget budget, Path cache, String sha, String base) {
        return git.outputAllowingOne(budget,
                                     "merge-base",
                                     cache,
                                     List.of("merge-base", "--all", sha, base))
                  .map(text -> text.lines()
                                   .filter(line -> !line.isBlank())
                                   .toList());
    }

    /// The non-empty commits on `rev-list --first-parent from..to`, the oldest of them `first`.
    static Promise<Moved> moved(Git git, Budget budget, Path cache, String from, String to) {
        return git.output(budget,
                          "rev-list",
                          cache,
                          List.of("rev-list", "--first-parent", from + ".." + to))
                  .map(text -> text.lines()
                                   .toList())
                  .flatMap(commits -> nonEmptyOf(git, budget, cache, commits));
    }

    private static Promise<Moved> nonEmptyOf(Git git, Budget budget, Path cache, List<String> commits) {
        return commits.isEmpty()
               ? Promise.success(Moved.NONE)
               : treesCompared(git, budget, cache, commits);
    }

    /// One `rev-parse c^{tree} c^1^{tree} …` for every commit, newest first as rev-list lists them.
    private static Promise<Moved> treesCompared(Git git, Budget budget, Path cache, List<String> commits) {
        return git.output(budget,
                          "rev-parse",
                          cache,
                          Stream.concat(Stream.of("rev-parse"),
                                        commits.stream()
                                               .flatMap(commit -> Stream.of(commit + "^{tree}", commit + "^1^{tree}")))
                                .toList())
                  .map(text -> movedOf(commits,
                                       text.lines().toList()));
    }

    private static Moved movedOf(List<String> commits, List<String> trees) {
        var changed = IntStream.range(0,
                                      commits.size())
                               .filter(index -> !trees.get(2 * index)
                                                      .equals(trees.get(2 * index + 1)))
                               .mapToObj(commits::get)
                               .toList();

        return changed.isEmpty()
               ? Moved.NONE
               : new Moved(changed.size(), some(changed.getLast()));
    }

    /// R2-c: kiln's tip (pr-fresh `baseTip.sha`) must be an ancestor of the fetched base; a missing object, or a value
    /// that is not a SHA, is not an ancestor. An untracked base is G7 `source-unavailable base-untracked`.
    static Promise<List<Reason>> rewound(Git git, Budget budget, Path cache, MergeWire.BaseTip tip, String base) {
        return tip.sha()
                  .map(kiln -> isAncestor(git, budget, cache, kiln, base).map(BaseCheck::rewoundReasons))
                  .or(() -> Promise.success(List.of()));
    }

    private static List<Reason> rewoundReasons(boolean ancestor) {
        return ancestor
               ? List.of()
               : List.of(Reason.reason(Reason.Gate.G8, "base-rewound"));
    }

    static List<Reason> untracked(Option<MergeWire.BaseTip> tip) {
        return tip.filter(MergeWire.BaseTip::tracked)
                  .isPresent()
               ? List.of()
               : List.of(Reason.reason(Reason.Gate.G7, "source-unavailable base-untracked"));
    }

    static Promise<Boolean> isAncestor(Git git, Budget budget, Path cache, String ancestor, String of) {
        return SHA40.matcher(ancestor).matches()
               ? git.exitCode(budget,
                              "merge-base",
                              cache,
                              List.of("merge-base", "--is-ancestor", ancestor, of))
                    .map(rc -> rc == 0)
               : Promise.success(false);
    }

    static String sha12(String sha) {
        return sha.length() > 12
               ? sha.substring(0, 12)
               : sha;
    }

    record unused() implements BaseCheck {}
}
