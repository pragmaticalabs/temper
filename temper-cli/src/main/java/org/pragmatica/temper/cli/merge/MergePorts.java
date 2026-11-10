package org.pragmatica.temper.cli.merge;

import java.time.Duration;
import java.util.function.LongSupplier;

import org.pragmatica.temper.protocol.exec.ProcessRunner;


/// What the T2 verbs reach outside the controller: the GitHub base the explicit fetch URL is
/// built on, the process runner git runs through, the `gh pr merge` port, the merge-gate budget and the nanosecond
/// clock that measures it. Only tests construct other values (a `file://` base, a `GhMerge` spy, a short budget);
/// there is no flag or environment variable that changes any of them.
public record MergePorts(String gitHub,
                         ProcessRunner runner,
                         GhMerge gh,
                         Duration budget,
                         LongSupplier nanos,
                         Duration call) {
    public static final String GITHUB = "https://github.com/";
    /// Elapsed past this at any step is `source-unavailable budget-exceeded`.
    public static final Duration BUDGET = Duration.ofSeconds(45);

    public static MergePorts mergePorts(String gitHub,
                                        ProcessRunner runner,
                                        GhMerge gh,
                                        Duration budget,
                                        LongSupplier nanos) {
        return mergePorts(gitHub, runner, gh, budget, nanos, Budget.CALL);
    }

    /// `call` caps every single HTTP and git call (35 s); only tests shorten it.
    public static MergePorts mergePorts(String gitHub,
                                        ProcessRunner runner,
                                        GhMerge gh,
                                        Duration budget,
                                        LongSupplier nanos,
                                        Duration call) {
        return new MergePorts(gitHub, runner, gh, budget, nanos, call);
    }

    public static MergePorts production() {
        var runner = ProcessRunner.processRunner();

        return mergePorts(GITHUB, runner, GhMerge.gh(runner), BUDGET, System::nanoTime);
    }

    /// The explicit fetch URL of `repo` (already validated against the repo pattern): built from the argument only.
    String url(String repo) {
        return gitHub + repo + ".git";
    }

    Budget started() {
        return Budget.budget(nanos, budget, call);
    }

    /// `temper merge` and `verdict`: no overall budget, each call still capped.
    Budget unbounded() {
        return Budget.budget(nanos, Budget.UNBOUNDED, call);
    }
}
