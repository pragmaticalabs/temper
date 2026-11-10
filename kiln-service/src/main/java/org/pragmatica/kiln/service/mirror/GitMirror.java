package org.pragmatica.kiln.service.mirror;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.flow.Serial;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.kiln.core.model.ModelError.invalid;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// [MirrorGit] over the `git` CLI (kiln-f1-design §3.3), after temper's `GitCli`: argv only, never a shell; SHAs and
/// branch names are validated value objects and can never start with `-`. git inherits kiln's environment, with
/// `GIT_TERMINAL_PROMPT=0` so it never waits for a password. Fetches and the first clone are serialised in-process.
///
/// No failure reads as an answer (§3.4): a git call that fails to run or exits with a code this class does not name
/// fails the promise, so the `mirror` source reads failing and no count reads 0. The one exception is the ruled K-8
/// mapping: `diff --quiet` exiting with anything but 0 or 1 makes `behindTreeEmpty` UNKNOWN, never `false`.
///
/// A shallow mirror answers no question: its cut history would read reachable commits as unreachable. Every fetch and
/// every query first asks `rev-parse --is-shallow-repository` and fails with [MirrorError.General#SHALLOW_MIRROR] while
/// it says `true`; a plain fetch does not deepen the mirror, so it stays failing until the operator replaces it. The
/// check is one quick git call per query (a `reachable` set asks once), and it also sees a mirror swapped between
/// fetches.
public final class GitMirror implements MirrorGit {
    private static final Logger log = LoggerFactory.getLogger(GitMirror.class);
    private static final Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0");
    private static final TimeSpan QUICK = timeSpan(2).minutes();
    private static final TimeSpan FETCH = timeSpan(15).minutes();
    private static final TimeSpan CLONE = timeSpan(60).minutes();
    private static final String BRANCHES = "refs/heads";
    private static final String TAGS = "refs/tags";
    /// Pull-request heads, fetched and pruned like branches. A `--mirror` clone also brings `refs/pull/*/merge`, which
    /// no fetch refreshes afterwards, so that namespace is never named in a query: a stale or synthetic merge commit
    /// never counts as preserved or linked.
    private static final String PULL_HEADS = "refs/pull/*/head";
    private static final Pattern PULL_HEAD = Pattern.compile("^refs/pull/([0-9]+)/head$");
    /// `merge-base` exits 1 with no output when the two commits share no history.
    private static final int NO_MERGE_BASE = 1;
    /// `diff --quiet` exits 0 when the trees are equal and 1 when they differ; anything else is a failed diff.
    private static final int TREES_EQUAL = 0;
    private static final int TREES_DIFFER = 1;
    /// `offBaseCommitsOfHead` is cut at this many commits (kiln-f1-design §3.3).
    private static final int OFF_BASE_LIMIT = 500;
    /// SHAs whose reachability is asked at once, so a large set never starts hundreds of git processes together.
    private static final int REACH_BATCH = 8;
    private static final BehindFacts UNKNOWN = new BehindFacts(none(), none());
    private static final Ancestry NOT_IN_MIRROR = new Ancestry(false, false, Set.of(), Set.of());

    /// The origins kiln fetches from, with no control character anywhere: `https://<host>` with no userinfo (the repo is
    /// public, spec §5.1, and a credential in argv shows in `ps`), `file:///` with an empty host (git drops a host, so
    /// `file://host/x` would reach `/x`), or an absolute path. None can start with `-`, so git can never read the origin
    /// as an option, even where an argv lacks `--`.
    private static final Pattern ORIGIN = Pattern.compile("^(https://[^/?#@\\p{Cc}\\u2028\\u2029]+([/?#][^\\p{Cc}\\u2028\\u2029]*)?"
                                                         + "|(file://)?/[^\\p{Cc}\\u2028\\u2029]*)$");

    /// `rev-parse --verify --quiet` exits 1 when the object is not in the mirror; any other nonzero exit is a failure.
    private static final int ABSENT = 1;

    private final ProcessRunner runner;
    private final String origin;
    private final Path mirror;
    private final Serial serial;

    /// The reachability answers of the current fetch generation. Each fetch, failed or not, replaces the map (a failed
    /// fetch may still have moved some refs); a query writes only into the map it started with.
    private final AtomicReference<Map<Sha, Boolean>> answers = new AtomicReference<>(new ConcurrentHashMap<>());

    private GitMirror(ProcessRunner runner, String origin, Path mirror, Serial serial) {
        this.runner = runner;
        this.origin = origin;
        this.mirror = mirror;
        this.serial = serial;
    }

    /// `origin` is the configured `[mirror] origin`, passed to every fetch, so a changed origin takes effect without
    /// re-cloning; `mirror` the bare mirror's path. The origin is validated here as well as in the config loader, so no
    /// caller can hand git an option-shaped origin.
    public static Result<GitMirror> gitMirror(ProcessRunner runner, String origin, Path mirror) {
        return Verify.ensure(origin, Verify.Is::present, MirrorError.General.INVALID_ORIGIN)
                     .flatMap(present -> Verify.ensure(present,
                                                       Verify.Is::matches,
                                                       ORIGIN,
                                                       MirrorError.General.INVALID_ORIGIN))
                     .map(valid -> new GitMirror(runner,
                                                 valid,
                                                 mirror,
                                                 new Serial()));
    }

    @Override
    public Promise<Unit> fetch() {
        return serial.run(this::fetchedGeneration);
    }

    /// `behindBy = rev-list --count B..base` and `behindTreeEmpty = diff --quiet B base`, `B = merge-base(head, base)`.
    /// `behindTreeEmpty` means tree-equal (maintainer ruling, K-8): the tree at `B` equals the base tip's tree, since a build
    /// reads only the tree. It is not "behind by empty commits only": a change and its revert read `true`
    /// (`BehindTreeEmptyIT.behind_revertPair_isTreeEqual`).
    @Override
    public Promise<BehindFacts> behind(Sha head, BranchName base) {
        return whole().flatMap(_ -> inMirror(head))
                    .flatMap(present -> behindIfPresent(present, head, base));
    }

    @Override
    public Promise<Map<Sha, Boolean>> reachable(Set<Sha> shas) {
        var generation = answers.get();

        return whole().flatMap(_ -> reachableFrom(List.copyOf(shas), 0, generation, Map.of()));
    }

    /// Accepted residual (kiln-f1-design §3.3, ruled): `offBaseCommitsOfHead` is cut at [#OFF_BASE_LIMIT] commits, so a
    /// PR head deeper than that under HEAD is not among them and rule 3 can miss that link. The cut only removes members,
    /// so it never creates a wrong link; a missed link reads UNOWNED or STALE-UNOWNED, which F2's GC never deletes.
    @Override
    public Promise<Ancestry> ancestry(Sha head, List<BranchName> bases) {
        return whole().flatMap(_ -> inMirror(head))
                    .flatMap(present -> ancestryIfPresent(present, head, bases));
    }

    // ---- fetch -------------------------------------------------------------------------------------------------
    private Promise<Unit> fetchedGeneration() {
        return fetchOrClone().flatMap(_ -> whole())
                           .withResult(_ -> answers.set(new ConcurrentHashMap<>()));
    }

    private Promise<Unit> fetchOrClone() {
        return Files.isDirectory(mirror)
               ? gitOk("fetch",
                       List.of("git",
                               "--git-dir",
                               mirror.toString(),
                               "fetch",
                               "--prune",
                               "--quiet",
                               "--",
                               origin,
                               "+" + BRANCHES + "/*:" + BRANCHES + "/*",
                               "+" + TAGS + "/*:" + TAGS + "/*",
                               "+" + PULL_HEADS + ":" + PULL_HEADS),
                       FETCH).mapToUnit()
               : gitOk("clone",
                       List.of("git", "clone", "--mirror", "--quiet", "--", origin, mirror.toString()),
                       CLONE).mapToUnit();
    }

    // ---- presence ----------------------------------------------------------------------------------------------
    /// Whether the mirror is whole: `rev-parse --is-shallow-repository` prints `false`. A shallow mirror is a failure.
    private Promise<Unit> whole() {
        return gitOk("rev-parse",
                     List.of("git", "--git-dir", mirror.toString(), "rev-parse", "--is-shallow-repository"),
                     QUICK).flatMap(GitMirror::wholeness);
    }

    private static Promise<Unit> wholeness(Completed ok) {
        return switch (ok.stdoutText()
                         .trim()) {
            case "false" -> Promise.unitPromise();
            case "true" -> MirrorError.General.SHALLOW_MIRROR.promise();
            default -> parsed("rev-parse",
                              invalid("shallow flag").apply(ok.stdoutText()).result());
        };
    }

    /// Whether the mirror holds `sha` as a commit: `rev-parse --verify --quiet` exits 0 for present and 1 for absent.
    /// Any other exit (128 for a corrupt object or a broken mirror, a signal, a timeout) is a failure, never "absent".
    private Promise<Boolean> inMirror(Sha sha) {
        return git("rev-parse",
                   List.of("git",
                           "--git-dir",
                           mirror.toString(),
                           "rev-parse",
                           "--verify",
                           "--quiet",
                           sha.value() + "^{commit}"),
                   QUICK).flatMap(GitMirror::presence);
    }

    private static Promise<Boolean> presence(Completed revParse) {
        return revParse.rc() == ABSENT
               ? Promise.success(false)
               : succeeded("rev-parse", revParse).map(_ -> true);
    }

    // ---- behind ------------------------------------------------------------------------------------------------
    private Promise<BehindFacts> behindIfPresent(boolean present, Sha head, BranchName base) {
        return present
               ? mergeBase(head, base).flatMap(found -> behindFromBase(found, base))
               : Promise.success(UNKNOWN);
    }

    /// No merge base (unrelated histories) leaves both facts UNKNOWN: there is no `B` to count from.
    private Promise<BehindFacts> behindFromBase(Option<Sha> mergeBase, BranchName base) {
        return mergeBase.map(found -> behindFrom(found, base))
                        .or(() -> Promise.success(UNKNOWN));
    }

    /// Fork-join: the count and the tree comparison read the same immutable commits. The diff starts before the count
    /// is awaited and is joined with `mapWith`, so a failure is the [MirrorError] itself: rc4's `all` and `allOrCancel`
    /// hand back a `CompositeCause` even for one failure.
    private Promise<BehindFacts> behindFrom(Sha mergeBase, BranchName base) {
        var treeEmpty = behindTreeEmpty(mergeBase, base);

        return behindBy(mergeBase, base).mapWith(_ -> treeEmpty, GitMirror::behindFacts);
    }

    private static BehindFacts behindFacts(Integer behindBy, Option<Boolean> treeEmpty) {
        return new BehindFacts(some(behindBy), treeEmpty);
    }

    private Promise<Option<Sha>> mergeBase(Sha head, BranchName base) {
        return git("merge-base",
                   List.of("git", "--git-dir", mirror.toString(), "merge-base", head.value(), branchRef(base)),
                   QUICK).flatMap(GitMirror::mergeBaseOf);
    }

    private static Promise<Option<Sha>> mergeBaseOf(Completed completed) {
        return completed.rc() == NO_MERGE_BASE && completed.stdout().length == 0
               ? Promise.success(none())
               : succeeded("merge-base", completed).flatMap(ok -> parsed("merge-base",
                                                                         Sha.sha(ok.stdoutText().trim())))
                          .map(Option::some);
    }

    private Promise<Integer> behindBy(Sha mergeBase, BranchName base) {
        return gitOk("rev-list",
                     List.of("git",
                             "--git-dir",
                             mirror.toString(),
                             "rev-list",
                             "--count",
                             mergeBase.value() + ".." + branchRef(base)),
                     QUICK).flatMap(ok -> parsed("rev-list",
                                                 Number.parseInt(ok.stdoutText().trim())));
    }

    private Promise<Option<Boolean>> behindTreeEmpty(Sha mergeBase, BranchName base) {
        return git("diff",
                   List.of("git",
                           "--git-dir",
                           mirror.toString(),
                           "diff",
                           "--quiet",
                           "--no-ext-diff",
                           mergeBase.value(),
                           branchRef(base)),
                   QUICK).map(GitMirror::treeEmpty);
    }

    /// The ruled mapping (K-8): 0 is empty, 1 is a diff, and any other exit is a failed diff, so UNKNOWN, never false.
    private static Option<Boolean> treeEmpty(Completed diff) {
        return switch (diff.rc()) {
            case TREES_EQUAL -> some(true);
            case TREES_DIFFER -> some(false);
            default -> diffFailed(diff);
        };
    }

    private static Option<Boolean> diffFailed(Completed diff) {
        log.warn("git diff --quiet exited {}; behindTreeEmpty reads UNKNOWN", diff.rc());

        return none();
    }

    // ---- reachable ---------------------------------------------------------------------------------------------
    /// The SHAs in batches of [#REACH_BATCH]: the next batch starts once the previous one answered. The first failure
    /// in a batch fails the whole answer, so no SHA ever reads unreachable because git failed.
    private Promise<Map<Sha, Boolean>> reachableFrom(List<Sha> shas,
                                                     int start,
                                                     Map<Sha, Boolean> generation,
                                                     Map<Sha, Boolean> done) {
        return start >= shas.size()
               ? Promise.success(done)
               : reachableBatch(shas.subList(start,
                                             Math.min(shas.size(), start + REACH_BATCH)),
                                generation).flatMap(found -> reachableFrom(shas,
                                                                           start + REACH_BATCH,
                                                                           generation,
                                                                           merged(done, found)));
    }

    private Promise<Map<Sha, Boolean>> reachableBatch(List<Sha> shas, Map<Sha, Boolean> generation) {
        return Promise.allOf(shas.stream().map(sha -> remembered(sha, generation)).toList())
                      .flatMap(results -> Result.firstFailureOf(results).async())
                      .map(GitMirror::byKey);
    }

    private Promise<Map.Entry<Sha, Boolean>> remembered(Sha sha, Map<Sha, Boolean> generation) {
        return option(generation.get(sha)).map(Promise::success)
                     .or(() -> reachableNow(sha).withSuccess(found -> generation.put(sha, found)))
                     .map(found -> Map.entry(sha, found));
    }

    /// Presence first: a SHA the mirror lacks is unreachable without asking `for-each-ref`.
    private Promise<Boolean> reachableNow(Sha sha) {
        return inMirror(sha).flatMap(present -> containedIfPresent(present, sha));
    }

    private Promise<Boolean> containedIfPresent(boolean present, Sha sha) {
        return present
               ? refsContaining(sha, List.of("--count=1", "--format=%(refname)", BRANCHES, TAGS, PULL_HEADS)).map(ok -> !ok.stdoutText()
                                                                                                                           .isBlank())
               : Promise.success(false);
    }

    private static Map<Sha, Boolean> byKey(List<Map.Entry<Sha, Boolean>> entries) {
        return entries.stream()
                      .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Map<Sha, Boolean> merged(Map<Sha, Boolean> done, Map<Sha, Boolean> found) {
        return Stream.concat(done.entrySet().stream(),
                             found.entrySet().stream())
                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---- ancestry ----------------------------------------------------------------------------------------------
    private Promise<Ancestry> ancestryIfPresent(boolean present, Sha head, List<BranchName> bases) {
        return present
               ? presentAncestry(head, bases)
               : Promise.success(NOT_IN_MIRROR);
    }

    /// Fork-join: the three reads are independent queries over the same immutable commits, all started before the
    /// first is awaited and joined as in [#behindFrom]. A configured base the mirror lacks fails the `rev-list`, so the
    /// whole answer fails rather than reading HEAD as off every base.
    private Promise<Ancestry> presentAncestry(Sha head, List<BranchName> bases) {
        var heads = pullHeadsContaining(head);
        var offBase = offBaseCommits(head, bases);

        return atBase(head, bases).mapWith(_ -> heads, Linked::new)
                     .mapWith(_ -> offBase, GitMirror::ancestryOf);
    }

    /// The two ancestry facts known before the off-base commits join them.
    private record Linked(boolean atBase, Set<PrNumber> headsContainingHead) {}

    private static Ancestry ancestryOf(Linked linked, Set<Sha> offBaseCommitsOfHead) {
        return new Ancestry(true, linked.atBase(), linked.headsContainingHead(), offBaseCommitsOfHead);
    }

    /// No base, no base tip: `for-each-ref` with no pattern would read every ref, so it is never asked.
    private Promise<Boolean> atBase(Sha head, List<BranchName> bases) {
        return bases.isEmpty()
               ? Promise.success(false)
               : refsContaining(head,
                                Stream.concat(Stream.of("--count=1",
                                                        "--format=%(refname)"),
                                              bases.stream().map(GitMirror::branchRef))
                                      .toList()).map(ok -> !ok.stdoutText()
                                                              .isBlank());
    }

    private Promise<Set<PrNumber>> pullHeadsContaining(Sha head) {
        return refsContaining(head, List.of("--format=%(refname)", PULL_HEADS)).flatMap(ok -> parsed("for-each-ref",
                                                                                                     pullNumbers(ok.stdoutText())));
    }

    private Promise<Set<Sha>> offBaseCommits(Sha head, List<BranchName> bases) {
        return gitOk("rev-list",
                     Stream.concat(Stream.of("git",
                                             "--git-dir",
                                             mirror.toString(),
                                             "rev-list",
                                             "--max-count=" + OFF_BASE_LIMIT,
                                             head.value(),
                                             "--not"),
                                   bases.stream().map(GitMirror::branchRef))
                           .toList(),
                     QUICK).flatMap(ok -> parsed("rev-list", shas(ok.stdoutText())));
    }

    private static Result<Set<PrNumber>> pullNumbers(String refNames) {
        return Result.allOf(lines(refNames).map(GitMirror::pullNumber)).map(Set::copyOf);
    }

    /// Only `refs/pull/<n>/head` is asked for, so a line of any other shape is unreadable output.
    private static Result<PrNumber> pullNumber(String refName) {
        return Verify.ensure(refName,
                             Verify.Is::matches,
                             PULL_HEAD,
                             invalid("pull head"))
                     .map(matched -> PULL_HEAD.matcher(matched).replaceFirst("$1"))
                     .flatMap(Number::parseLong)
                     .flatMap(PrNumber::prNumber);
    }

    private static Result<Set<Sha>> shas(String revList) {
        return Result.allOf(lines(revList).map(Sha::sha)).map(Set::copyOf);
    }

    private static Stream<String> lines(String output) {
        return Arrays.stream(output.split("\n"))
                     .map(String::trim)
                     .filter(line -> !line.isEmpty());
    }

    // ---- process -----------------------------------------------------------------------------------------------
    /// The pattern ending `selection` is one of `refs/heads`, `refs/tags`, `refs/pull/*/head` or a base's
    /// `refs/heads/<base>`, never `refs/pull/*/merge`.
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

    /// A base is always named in full, so a tag or a PR ref of the same name can never stand in for it.
    private static String branchRef(BranchName base) {
        return BRANCHES + "/" + base.value();
    }

    private static <T> Promise<T> parsed(String operation, Result<T> result) {
        return result.mapError(cause -> MirrorError.Failed.FACTORY.apply(operation, cause))
                     .async();
    }

    private Promise<Completed> gitOk(String operation, List<String> argv, TimeSpan timeout) {
        return git(operation, argv, timeout).flatMap(completed -> succeeded(operation, completed));
    }

    private static Promise<Completed> succeeded(String operation, Completed completed) {
        return completed.requireZero()
                        .mapError(cause -> MirrorError.Failed.FACTORY.apply(operation, cause))
                        .async();
    }

    private Promise<Completed> git(String operation, List<String> argv, TimeSpan timeout) {
        return Command.command(argv, ENV, timeout)
                      .map(Command::inheritingEnvironment)
                      .async()
                      .flatMap(runner::run)
                      .mapError(cause -> MirrorError.Failed.FACTORY.apply(operation, cause));
    }
}
