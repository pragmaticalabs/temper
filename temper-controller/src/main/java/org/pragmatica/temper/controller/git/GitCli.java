package org.pragmatica.temper.controller.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.flow.Batches;
import org.pragmatica.temper.controller.flow.Collect;
import org.pragmatica.temper.controller.flow.Serial;
import org.pragmatica.temper.controller.submit.GitError;
import org.pragmatica.temper.controller.submit.GitRemote;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.TreeReader;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.refusal.ClassSource;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.refusal.TreeFacts;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// [GitRemote], [Mirror] and [TreeReader] over the `git` CLI (m0-design §6.3). argv only, never a shell; refs and
/// class names are validated value objects and can never start with `-`. git inherits the controller's environment
/// (it may need the ssh agent for the origin), with `GIT_TERMINAL_PROMPT=0` so it never waits for a password.
/// Mirror fetches and clones are serialised in-process.
public record GitCli(ProcessRunner runner, String origin, Path mirror, Serial serial) implements GitRemote, Mirror, TreeReader {
    private static final Logger log = LoggerFactory.getLogger(GitCli.class);
    private static final Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0");
    /// Poms fetched at once while reading a reactor level.
    private static final int POM_BATCH = 16;
    private static final TimeSpan QUICK = timeSpan(2).minutes();
    private static final TimeSpan FETCH = timeSpan(15).minutes();
    private static final TimeSpan CLONE = timeSpan(60).minutes();
    /// `git ls-remote --exit-code` exits 2 when no ref matched; `git grep` exits 1 when nothing matched.
    private static final int NO_MATCHING_REFS = 2;
    private static final int NO_MATCH = 1;
    private static final String BRANCHES = "refs/heads";
    /// Pull-request heads, fetched and pruned like branches (m1-design M1-3, R-M2-7); `refs/pull/*/merge` is never
    /// fetched. GitHub keeps a closed PR's head ref, so closing a PR does not remove it.
    private static final String PULL_HEADS = "refs/pull/*/head";
    /// Oldest PR first, the number compared as an integer (refname order would put #10 before #2): fork PRs are
    /// usually newer; an older fork can be force-pushed, so neither a fork answer nor a per-PR failure stops the walk
    /// ([org.pragmatica.temper.controller.submit.PullJudgement]).
    private static final Comparator<PullNumber> CANDIDATE_ORDER = Comparator.comparingLong(PullNumber::value);

    public static GitCli gitCli(ProcessRunner runner, String origin, Path mirror) {
        return new GitCli(runner, origin, mirror, new Serial());
    }

    @Override
    public Promise<Sha> lsRemote(Ref ref) {
        return git("ls-remote",
                   Stream.concat(Stream.of("git", "ls-remote", "--exit-code", "--", origin),
                                 LsRemote.patterns(ref).stream())
                         .toList(),
                   QUICK).flatMap(completed -> resolved(ref, completed));
    }

    @Override
    public Promise<Unit> fetch() {
        return serial.run(this::fetchOrClone);
    }

    @Override
    public Promise<Boolean> containedInBranch(Sha sha) {
        return git("cat-file", catFile(sha), QUICK).flatMap(catFile -> containedIfPresent(sha, catFile));
    }

    @Override
    public Promise<List<PullNumber>> pullHeadsContaining(Sha sha) {
        return git("cat-file", catFile(sha), QUICK).flatMap(catFile -> pullHeadsIfPresent(sha, catFile));
    }

    /// `rev-parse --verify --quiet refs/heads/<branch>^{commit}` after a fetch: exit 1 with no output is "no such branch"
    /// (none); any other failure is a [GitError].
    @Override
    public Promise<Option<Sha>> branchTip(Ref branch) {
        return fetch().flatMap(_ -> git("rev-parse",
                                        List.of("git",
                                                "--git-dir",
                                                mirror.toString(),
                                                "rev-parse",
                                                "--verify",
                                                "--quiet",
                                                BRANCHES + "/" + branch.value() + "^{commit}"),
                                        QUICK))
                    .flatMap(this::tip);
    }

    private Promise<Option<Sha>> tip(Completed completed) {
        return completed.rc() == NO_MATCH
               ? Promise.success(none())
               : succeeded("rev-parse", completed).flatMap(GitCli::parsedTip);
    }

    private static Promise<Option<Sha>> parsedTip(Completed ok) {
        return Sha.sha(ok.stdoutText().trim())
                  .map(Option::some)
                  .async();
    }

    /// An object a HEALTHY mirror does not have is "not contained" (a refusal). `cat-file -e` exits 128 both for a
    /// missing object and for a broken mirror, so a failed `cat-file` is only trusted once `rev-parse --git-dir`
    /// confirms the mirror; once the object exists, `for-each-ref` must succeed. Every git failure is a [GitError]
    /// (503), never a refusal.
    private Promise<Boolean> containedIfPresent(Sha sha, Completed catFile) {
        return catFile.rc() == 0
               ? refsContaining(sha, List.of("--count=1", "--format=%(refname)", BRANCHES)).map(completed -> !completed.stdoutText()
                                                                                                                       .isBlank())
               : healthyMirror().map(_ -> false);
    }

    /// As [#containedIfPresent], over the pull-request heads; every one of them, in [#CANDIDATE_ORDER].
    private Promise<List<PullNumber>> pullHeadsIfPresent(Sha sha, Completed catFile) {
        return catFile.rc() == 0
               ? refsContaining(sha, List.of("--format=%(refname)", PULL_HEADS)).map(completed -> candidates(completed.stdoutText()))
               : healthyMirror().map(_ -> List.of());
    }

    private static List<PullNumber> candidates(String refNames) {
        return Arrays.stream(refNames.split("\n"))
                     .flatMap(name -> PullNumber.fromHeadRef(name).stream())
                     .sorted(CANDIDATE_ORDER)
                     .toList();
    }

    private Promise<Completed> healthyMirror() {
        return gitOk("rev-parse",
                     List.of("git", "--git-dir", mirror.toString(), "rev-parse", "--git-dir"),
                     QUICK);
    }

    /// The pattern ending `selection` is `refs/heads` or `refs/pull/*/head` only: a `--mirror` clone also brings `refs/pull/*/merge`,
    /// which no fetch refreshes afterwards, so a stale or synthetic merge commit never counts as pushed.
    private Promise<Completed> refsContaining(Sha sha, List<String> selection) {
        return gitOk("for-each-ref",
                     Stream.concat(Stream.of("git",
                                             "--git-dir",
                                             mirror.toString(),
                                             "for-each-ref",
                                             "--contains",
                                             sha.value()),
                                   selection.stream())
                           .toList(),
                     QUICK);
    }

    /// The classes the refusal rules name and the reactor (m1-design §1.6), read together once the commit is in the
    /// mirror. A failure reading the classes stays a [GitError] (503); the reactor never fails (none when unknown).
    @Override
    public Promise<TreeFacts> facts(Sha sha, Set<String> simpleClassNames) {
        return ensureObject(sha).flatMap(_ -> treeFacts(sha, simpleClassNames));
    }

    /// Fork-join: the reactor read starts before the class sources are awaited.
    private Promise<TreeFacts> treeFacts(Sha sha, Set<String> simpleClassNames) {
        var reactor = reactor(sha);

        return sources(sha, simpleClassNames).mapWith(_ -> reactor, TreeFacts::treeFacts);
    }

    // ---- ls-remote ---------------------------------------------------------------------------------------------
    private Promise<Sha> resolved(Ref ref, Completed completed) {
        return completed.rc() == NO_MATCHING_REFS
               ? Refusal.refusal(RefusalCode.SOURCE, "ref " + ref + " matches no pushed branch or tag").promise()
               : succeeded("ls-remote", completed).flatMap(ok -> LsRemote.resolve(ref,
                                                                                  ok.stdoutText())
                                                                         .async());
    }

    // ---- mirror ------------------------------------------------------------------------------------------------
    private Promise<Unit> fetchOrClone() {
        return Files.isDirectory(mirror)
               ? gitOk("fetch",
                       List.of("git",
                               "--git-dir",
                               mirror.toString(),
                               "fetch",
                               "--prune",
                               "--quiet",
                               "origin",
                               "+refs/heads/*:refs/heads/*",
                               "+refs/tags/*:refs/tags/*",
                               "+" + PULL_HEADS + ":" + PULL_HEADS),
                       FETCH).mapToUnit()
               : gitOk("clone",
                       List.of("git", "clone", "--mirror", "--quiet", "--", origin, mirror.toString()),
                       CLONE).mapToUnit();
    }

    /// Fetches first when the mirror lacks the commit (a ref resolved by `ls-remote` was never fetched).
    private Promise<Unit> ensureObject(Sha sha) {
        return git("cat-file", catFile(sha), QUICK).flatMap(this::presentOrFetched);
    }

    private List<String> catFile(Sha sha) {
        return List.of("git", "--git-dir", mirror.toString(), "cat-file", "-e", sha.value() + "^{commit}");
    }

    private Promise<Unit> presentOrFetched(Completed catFile) {
        return catFile.rc() == 0
               ? Promise.unitPromise()
               : fetch();
    }

    // ---- tree --------------------------------------------------------------------------------------------------
    /// `Collect.first` here and in [#contents]: the failure is the [GitError] of the git call that failed, which the
    /// submit answers as 503; an aggregate `CompositeCause` would answer 500.
    private Promise<Map<String, List<ClassSource>>> sources(Sha sha, Set<String> simpleClassNames) {
        return Collect.first(simpleClassNames.stream().map(name -> declaring(sha, name)).toList()).map(GitCli::byClass);
    }

    private static Map<String, List<ClassSource>> byClass(List<Map.Entry<String, List<ClassSource>>> entries) {
        return entries.stream()
                      .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---- reactor -----------------------------------------------------------------------------------------------
    /// FER (degrade forward): a reactor that cannot be read, for any reason (a missing or malformed pom, a git
    /// failure, a `.mvn/maven.config` that selects another reactor), is none. Guarantee: the refusals and the
    /// port-group rule then judge as M0 did, which refuses or holds more, never less (m1-design §1.6).
    private Promise<Option<ReactorModules>> reactor(Sha sha) {
        return ReactorPomFetch.reactor(path -> shown(sha, path),
                                       POM_BATCH)
                              .onFailure(cause -> log.warn("reactor of {} unknown: {}",
                                                           sha,
                                                           cause.message()))
                              .recover(_ -> none());
    }

    /// The file at `path` in `sha`, once the commit is in the mirror; none only when the tree has no such file
    /// (`git ls-tree` lists nothing). Any git failure, the listing's or the show's, is a [GitError]: nothing is absorbed.
    @Override
    public Promise<Option<byte[]>> file(Sha sha, String path) {
        return ensureObject(sha).flatMap(_ -> listed(sha, path))
                           .flatMap(present -> contentIf(present, sha, path));
    }

    private Promise<Boolean> listed(Sha sha, String path) {
        return gitOk("ls-tree",
                     List.of("git", "--git-dir", mirror.toString(), "ls-tree", "--name-only", sha.value(), "--", path),
                     QUICK).map(completed -> !completed.stdoutText()
                                                       .isBlank());
    }

    private Promise<Option<byte[]>> contentIf(boolean present, Sha sha, String path) {
        return present
               ? content(sha, path)
               : Promise.success(none());
    }

    private Promise<Option<byte[]>> content(Sha sha, String path) {
        return gitOk("show",
                     List.of("git", "--git-dir", mirror.toString(), "show", sha.value() + ":" + path),
                     QUICK).map(completed -> some(completed.stdout()));
    }

    /// The file at `path` in `sha`; none when the tree has no such file, or git cannot show it.
    private Promise<Option<byte[]>> shown(Sha sha, String path) {
        return git("show",
                   List.of("git", "--git-dir", mirror.toString(), "show", sha.value() + ":" + path),
                   QUICK).map(GitCli::shown);
    }

    private static Option<byte[]> shown(Completed completed) {
        return completed.rc() == 0
               ? some(completed.stdout())
               : none();
    }

    private Promise<Map.Entry<String, List<ClassSource>>> declaring(Sha sha, String simpleClassName) {
        return grep(sha, simpleClassName).flatMap(paths -> contents(sha, paths))
                   .map(sources -> Map.entry(simpleClassName, sources));
    }

    /// `git grep -l -w -F -e 'class Name' <sha> -- '*.java'`: whole-word, so `class NameHelper` does not match.
    private Promise<List<String>> grep(Sha sha, String simpleClassName) {
        return git("grep",
                   List.of("git",
                           "--git-dir",
                           mirror.toString(),
                           "grep",
                           "-l",
                           "-w",
                           "-F",
                           "-e",
                           "class " + simpleClassName,
                           sha.value(),
                           "--",
                           "*.java"),
                   QUICK).flatMap(completed -> grepped(sha, completed));
    }

    private Promise<List<String>> grepped(Sha sha, Completed completed) {
        return completed.rc() == NO_MATCH
               ? Promise.success(List.of())
               : succeeded("grep", completed).map(ok -> paths(sha, ok.stdoutText()));
    }

    /// Output lines are `<sha>:<path>`.
    private static List<String> paths(Sha sha, String output) {
        var prefix = sha.value() + ":";

        return Arrays.stream(output.split("\n"))
                     .map(String::trim)
                     .filter(line -> line.startsWith(prefix))
                     .map(line -> line.substring(prefix.length()))
                     .toList();
    }

    private Promise<List<ClassSource>> contents(Sha sha, List<String> paths) {
        return Collect.first(paths.stream().map(path -> show(sha, path)).toList());
    }

    private Promise<ClassSource> show(Sha sha, String path) {
        return gitOk("show",
                     List.of("git", "--git-dir", mirror.toString(), "show", sha.value() + ":" + path),
                     QUICK).map(completed -> ClassSource.classSource(path, completed.stdoutText()));
    }

    // ---- process -----------------------------------------------------------------------------------------------
    private Promise<Completed> gitOk(String operation, List<String> argv, TimeSpan timeout) {
        return git(operation, argv, timeout).flatMap(completed -> succeeded(operation, completed));
    }

    private static Promise<Completed> succeeded(String operation, Completed completed) {
        return completed.requireZero()
                        .mapError(cause -> GitError.Failed.FACTORY.apply(operation, cause))
                        .async();
    }

    private Promise<Completed> git(String operation, List<String> argv, TimeSpan timeout) {
        return Command.command(argv, ENV, timeout)
                      .map(Command::inheritingEnvironment)
                      .async()
                      .flatMap(runner::run)
                      .mapError(cause -> GitError.Failed.FACTORY.apply(operation, cause));
    }
}
