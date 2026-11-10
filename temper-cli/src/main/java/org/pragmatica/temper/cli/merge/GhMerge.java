package org.pragmatica.temper.cli.merge;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// The merge call of `temper merge`: `gh pr merge <pr> --repo <repo> --squash
/// --match-head-commit <sha40>`, the only head guarantee at merge time. Injectable so tests count the calls.
@FunctionalInterface
public interface GhMerge {
    TimeSpan TIMEOUT = TimeSpan.timeSpan(2).minutes();

    /// gh's exit code and the first line of its stderr; `timedOutAfter` (seconds) when gh did not finish within its
    /// bound, which says nothing about whether the PR merged.
    record GhExit(int code, String firstStderrLine, Option<Long> timedOutAfter) {
        public static GhExit ghExit(int code, String firstStderrLine) {
            return new GhExit(code, firstStderrLine, Option.none());
        }

        public static GhExit timedOut(long seconds) {
            return new GhExit(-1, "", Option.some(seconds));
        }

        boolean isMerged() {
            return code == 0;
        }
    }

    /// Never fails: a gh that could not start is `GhExit(-1, <first line of the failure>)`; one that did not finish
    /// within [#TIMEOUT] is [GhExit#timedOut].
    Promise<GhExit> merge(String repo, long pr, String sha);

    /// The real `gh`, inheriting the caller's environment (gh's own credentials) with the git hardening on top.
    static GhMerge gh(ProcessRunner runner) {
        return (repo, pr, sha) -> Command.command(List.of("gh",
                                                          "pr",
                                                          "merge",
                                                          Long.toString(pr),
                                                          "--repo",
                                                          repo,
                                                          "--squash",
                                                          "--match-head-commit",
                                                          sha),
                                                  Git.HARDENING,
                                                  TIMEOUT)
                                         .map(Command::inheritingEnvironment)
                                         .async()
                                         .flatMap(runner::run)
                                         .map(GhMerge::exited)
                                         .fold(GhMerge::orUnstarted);
    }

    private static GhExit exited(Completed completed) {
        return GhExit.ghExit(completed.rc(), firstLine(completed.stderrText()));
    }

    /// FER: a gh that could not run merged nothing, so it is reported as a failed merge (`merge-failed gh exit -1`),
    /// which records `merge-failed` and exits 3; the failure's first line is its diagnosis. A timed-out gh may have
    /// merged: it is reported as unknown, and the caller records nothing.
    private static Promise<GhExit> orUnstarted(Result<GhExit> result) {
        return Promise.success(result.fold(GhMerge::unfinished, exit -> exit));
    }

    private static GhExit unfinished(Cause cause) {
        return cause instanceof ExecError.TimedOut
               ? GhExit.timedOut(TIMEOUT.nanos() / 1_000_000_000L)
               : GhExit.ghExit(-1, firstLine(cause.message()));
    }

    static String firstLine(String text) {
        return Option.from(text.lines().findFirst())
                     .map(String::strip)
                     .or("");
    }
}
