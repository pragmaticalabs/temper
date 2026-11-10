package org.pragmatica.temper.cli.merge;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.cli.CliContext;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// git on the Mac, through the protocol [ProcessRunner] with an isolated environment: only
/// `PATH` and `HOME`, plus `GIT_TERMINAL_PROMPT=0`, `GIT_CONFIG_NOSYSTEM=1` and `GIT_CONFIG_GLOBAL=/dev/null`, so no
/// `insteadOf` in the system or the user's config (and no `GIT_CONFIG_*`, `GIT_DIR` or proxy variable of the caller's
/// environment) can redirect the explicit URL (F4). Every call is bounded by the [Budget]'s next bound.
record Git(ProcessRunner runner, Map<String, String> env) {
    static final String DEFAULT_PATH = "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin";

    static final Map<String, String> HARDENING = Map.of("GIT_TERMINAL_PROMPT",
                                                        "0",
                                                        "GIT_CONFIG_NOSYSTEM",
                                                        "1",
                                                        "GIT_CONFIG_GLOBAL",
                                                        "/dev/null");

    static Git git(ProcessRunner runner, CliContext context) {
        return new Git(runner,
                       Map.of("PATH",
                              context.env("PATH").or(DEFAULT_PATH),
                              "HOME",
                              context.home().toString(),
                              "GIT_TERMINAL_PROMPT",
                              HARDENING.get("GIT_TERMINAL_PROMPT"),
                              "GIT_CONFIG_NOSYSTEM",
                              HARDENING.get("GIT_CONFIG_NOSYSTEM"),
                              "GIT_CONFIG_GLOBAL",
                              HARDENING.get("GIT_CONFIG_GLOBAL")));
    }

    /// `git <args>` that must exit 0; its stdout.
    Promise<String> output(Budget budget, String step, List<String> args) {
        return completed(budget, step, args).flatMap(completed -> zero(step, completed))
                        .map(Completed::stdoutText);
    }

    /// `git -C <repo> <args>` that must exit 0; its stdout.
    Promise<String> output(Budget budget, String step, Path repo, List<String> args) {
        return output(budget, step, inRepo(repo, args));
    }

    /// `git -C <repo> <args>` whose exit code is the answer (`merge-base --is-ancestor`).
    Promise<Integer> exitCode(Budget budget, String step, Path repo, List<String> args) {
        return completed(budget, step, inRepo(repo, args)).map(Completed::rc);
    }

    /// `git -C <repo> <args>` that may exit 0 or 1 (`merge-base --all` exits 1 when there is no merge base).
    Promise<String> outputAllowingOne(Budget budget, String step, Path repo, List<String> args) {
        return completed(budget,
                         step,
                         inRepo(repo, args)).flatMap(completed -> zeroOrOne(step, completed))
                        .map(Completed::stdoutText);
    }

    /// `git -C <repo> <args>` whose exit code and stdout are both the answer (`merge-tree --write-tree`).
    Promise<Completed> completedIn(Budget budget, String step, Path repo, List<String> args) {
        return completed(budget, step, inRepo(repo, args));
    }

    private static List<String> inRepo(Path repo, List<String> args) {
        return Stream.concat(Stream.of("-C",
                                       repo.toString()),
                             args.stream())
                     .toList();
    }

    private Promise<Completed> completed(Budget budget, String step, List<String> args) {
        return budget.bound()
                     .async()
                     .flatMap(bound -> ran(bound, step, args));
    }

    private Promise<Completed> ran(Duration bound, String step, List<String> args) {
        return Command.command(Stream.concat(Stream.of("git"),
                                             args.stream()).toList(),
                               env,
                               TimeSpan.fromDuration(bound))
                      .async()
                      .flatMap(runner::run)
                      .mapError(cause -> GateFault.GitFailed.FACTORY.apply(step, cause));
    }

    private static Promise<Completed> zero(String step, Completed completed) {
        return completed.requireZero()
                        .mapError(cause -> GateFault.GitFailed.FACTORY.apply(step, cause))
                        .async();
    }

    private static Promise<Completed> zeroOrOne(String step, Completed completed) {
        return completed.rc() == 1
               ? Promise.success(completed)
               : zero(step, completed);
    }
}
