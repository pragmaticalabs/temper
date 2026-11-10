package org.pragmatica.temper.controller.review;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.http.ApiError;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The server's repeat of the Mac's `carry-*` checks (r1 H1), on the controller's own
/// bare mirror: the carried SHA must be exactly a `git merge` of the carried-from SHA and the tip of the PR's base,
/// with no hand edit. `fetch` is the mirror's serialised fetch; `pull` re-reads `GET pulls/{n}` after it, and the
/// PR's actual `base.ref` (never the row's `base`, r2 P1) is the proof's input.
///
/// Each git command has `command` (30 s), the fetch `fetchWait` (90 s) and the whole proof `step` (120 s); a timeout
/// is 503 `CARRY_PROOF_UNAVAILABLE … timeout`. Needs git >= 2.38 (`merge-tree --write-tree`), asked at each proof:
/// below it, 503 (fail-closed).
public record CarryProof(ProcessRunner runner,
                         Path mirror,
                         Supplier<Promise<Unit>> fetch,
                         TimeSpan command,
                         TimeSpan fetchWait,
                         TimeSpan step) {
    private static final Map<String, String> ENV = Map.of("GIT_TERMINAL_PROMPT", "0");
    private static final int CONFLICTS = 1;
    private static final int MIN_MAJOR = 2;
    private static final int MIN_MINOR = 38;
    private static final int SHA12 = 12;

    /// What the proof established: parent 2 of the merge (the base tip it used) and the base ref it was proven on.
    public record Proven(String baseTip, String baseRef) {}

    public static CarryProof carryProof(ProcessRunner runner, Path mirror, Supplier<Promise<Unit>> fetch) {
        return new CarryProof(runner,
                              mirror,
                              fetch,
                              timeSpan(30).seconds(),
                              timeSpan(90).seconds(),
                              timeSpan(120).seconds());
    }

    /// Steps 5a-5e for `sha` carried from `carriedFrom` on a verdict row whose base is `rowBase`.
    public Promise<Proven> prove(ReviewGitHub github,
                                 String repo,
                                 long pr,
                                 String sha,
                                 String carriedFrom,
                                 String rowBase) {
        return proof(github, repo, pr, sha, carriedFrom, rowBase).timeout(step)
                    .mapError(CarryProof::timedOut);
    }

    private Promise<Proven> proof(ReviewGitHub github,
                                  String repo,
                                  long pr,
                                  String sha,
                                  String carriedFrom,
                                  String rowBase) {
        return version().flatMap(_ -> fetch.get()
                                           .timeout(fetchWait)
                                           .mapError(CarryProof::fetchFailed))
                      .flatMap(_ -> github.pull(repo, pr)
                                          .mapError(CarryProof::unreadable))
                      .flatMap(pull -> standing(pull, sha, rowBase))
                      .flatMap(pull -> objects(sha, carriedFrom).map(_ -> pull))
                      .flatMap(pull -> parents(sha, carriedFrom).flatMap(second -> onBase(pull.baseRef(),
                                                                                          second).flatMap(_ -> merged(sha,
                                                                                                                      carriedFrom,
                                                                                                                      second))
                                                                                         .map(_ -> new Proven(second,
                                                                                                              pull.baseRef()))));
    }

    /// The re-read applies the step-4 checks again, then holds the row's base against the PR's actual one.
    private static Promise<ReviewGitHub.PullView> standing(ReviewGitHub.PullView pull, String sha, String rowBase) {
        return ! pull.open()
               ? ApiError.General.NOT_OPEN.promise()
               : !pull.headSha()
                      .equals(sha)
                 ? ApiError.Unprocessable.FACTORY.apply("HEAD_MOVED").promise()
                 : !pull.baseRef()
                        .equals(rowBase)
                   ? ApiError.Unprocessable.FACTORY.apply("BASE_MOVED").promise()
                   : Promise.success(pull);
    }

    private Promise<Unit> version() {
        return run("version", List.of("git", "--version")).flatMap(done -> done.rc() == 0 && supported(done.stdoutText())
                                                                           ? Promise.unitPromise()
                                                                           : unavailable("git").promise());
    }

    /// `git version 2.43.0` → at least 2.38.
    static boolean supported(String output) {
        var parts = Arrays.stream(output.strip().split("[ .]"))
                          .filter(part -> part.matches("\\d+"))
                          .map(Integer::parseInt)
                          .toList();

        return parts.size() >= 2
               && (parts.get(0) > MIN_MAJOR || (parts.get(0) == MIN_MAJOR && parts.get(1) >= MIN_MINOR));
    }

    private Promise<Unit> objects(String sha, String carriedFrom) {
        return git("cat-file",
                   List.of("cat-file", "-e", sha + "^{commit}")).flatMap(done -> done.rc() == 0
                                                                                 ? Promise.unitPromise()
                                                                                 : unavailable("missing " + sha.substring(0,
                                                                                                                          SHA12)).promise())
                  .flatMap(_ -> git("cat-file",
                                    List.of("cat-file", "-e", carriedFrom + "^{commit}")))
                  .flatMap(done -> done.rc() == 0
                                   ? Promise.unitPromise()
                                   : refused("carried-from-unknown").promise());
    }

    /// `rev-list --parents -n 1 <sha>`: exactly two parents, the first the carried-from SHA. Answers parent 2.
    private Promise<String> parents(String sha, String carriedFrom) {
        return ok("rev-list", List.of("rev-list", "--parents", "-n", "1", sha)).flatMap(done -> {
            var tokens = done.stdoutText()
                             .strip()
                             .split("\\s+");

            return tokens.length != 3
                   ? refused("not-merge").<String> promise()
                   : !tokens[1].equals(carriedFrom)
                     ? refused("parent1").<String> promise()
                     : Promise.success(tokens[2]);
        });
    }

    /// Parent 2 is a commit of the base's first-parent line (reachability alone would pass a side branch).
    private Promise<Unit> onBase(String baseRef, String second) {
        return ok("rev-list", List.of("rev-list", "--first-parent", "refs/heads/" + baseRef)).flatMap(done -> done.stdoutText()
                                                                                                                  .lines()
                                                                                                                  .anyMatch(second::equals)
                                                                                                              ? Promise.unitPromise()
                                                                                                              : refused("parent2-off-base").promise());
    }

    /// `merge-tree --write-tree <carried> <parent2>` (exit 1: conflicts) must name the tree of `sha`.
    private Promise<Unit> merged(String sha, String carriedFrom, String second) {
        return git("merge-tree",
                   List.of("merge-tree", "--write-tree", carriedFrom, second)).flatMap(done -> done.rc() == CONFLICTS
                                                                                               ? refused("conflicted").<Completed> promise()
                                                                                               : done.rc() == 0
                                                                                                 ? Promise.success(done)
                                                                                                 : unavailable("git").<Completed> promise())
                  .flatMap(done -> ok("rev-parse",
                                      List.of("rev-parse", sha + "^{tree}")).map(tree -> done.stdoutText()
                                                                                             .lines()
                                                                                             .findFirst()
                                                                                             .orElse("")
                                                                                             .strip()
                                                                                             .equals(tree.stdoutText()
                                                                                                         .strip())))
                  .flatMap(same -> same
                                   ? Promise.unitPromise()
                                   : refused("hand-resolved").promise());
    }

    private Promise<Completed> ok(String operation, List<String> args) {
        return git(operation, args).flatMap(done -> done.rc() == 0
                                                    ? Promise.success(done)
                                                    : unavailable("git").<Completed> promise());
    }

    private Promise<Completed> git(String operation, List<String> args) {
        return run(operation,
                   Stream.concat(Stream.of("git", "--git-dir", mirror.toString()),
                                 args.stream()).toList());
    }

    private Promise<Completed> run(String operation, List<String> argv) {
        return Command.command(argv, ENV, command)
                      .map(Command::inheritingEnvironment)
                      .async()
                      .flatMap(runner::run)
                      .mapError(cause -> timedOutOr(cause, operation));
    }

    private static Cause timedOutOr(Cause cause, String operation) {
        return cause instanceof ExecError.TimedOut
               ? ApiError.Transient.FACTORY.apply("CARRY_PROOF_UNAVAILABLE timeout")
               : ApiError.Transient.FACTORY.apply("CARRY_PROOF_UNAVAILABLE git");
    }

    private static Cause timedOut(Cause cause) {
        return cause instanceof ApiError
               ? cause
               : ApiError.Transient.FACTORY.apply("CARRY_PROOF_UNAVAILABLE timeout");
    }

    private static Cause fetchFailed(Cause cause) {
        return cause.message()
                    .toLowerCase()
                    .contains("time")
               ? ApiError.Transient.FACTORY.apply("CARRY_PROOF_UNAVAILABLE timeout")
               : ApiError.Transient.FACTORY.apply("CARRY_PROOF_UNAVAILABLE git");
    }

    private static Cause unreadable(Cause cause) {
        return ApiError.Transient.FACTORY.apply("UNKNOWN github-read");
    }

    private static Cause unavailable(String what) {
        return ApiError.Transient.FACTORY.apply("CARRY_PROOF_UNAVAILABLE " + what);
    }

    private static Cause refused(String reason) {
        return ApiError.Unprocessable.FACTORY.apply("CARRY_NOT_MECHANICAL " + reason);
    }
}
