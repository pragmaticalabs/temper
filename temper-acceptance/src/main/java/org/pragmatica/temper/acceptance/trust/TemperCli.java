package org.pragmatica.temper.acceptance.trust;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.protocol.exec.Completed;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The temper CLI as the T1/T2 procedure drives it (m1-design §8.1 step 3): `temper submit gate --sha S --fresh
/// --prio N`, then `temper wait <job> --timeout T`. `command` is how the CLI is started (`temper`, or `java -jar
/// …/temper-cli.jar`); `env` is overlaid on this JVM's environment (`TEMPER_URL`, `HOME` for the token file).
public record TemperCli(Processes processes, List<String> command, Map<String, String> env) {
    public static final Pattern SUBMITTED = Pattern.compile("^submit (\\d+) .*$");
    private static final TimeSpan SUBMIT_TIMEOUT = timeSpan(2).minutes();
    /// The wait itself is bounded by `--timeout`; the process gets a minute more to print its line.
    private static final TimeSpan WAIT_GRACE = timeSpan(1).minutes();

    public static TemperCli temperCli(Processes processes, List<String> command, Map<String, String> env) {
        return new TemperCli(processes, List.copyOf(command), Map.copyOf(env));
    }

    /// `submit gate --sha <sha> --fresh --prio <priority>`: the new job's id. Any exit but 0 (a refusal is 2) fails.
    public Promise<Long> submitGate(String sha, int priority) {
        return processes.run(argv("submit",
                                  "gate",
                                  "--sha",
                                  sha,
                                  "--fresh",
                                  "--prio",
                                  String.valueOf(priority)),
                             env,
                             SUBMIT_TIMEOUT)
                        .flatMap(TemperCli::submittedJob);
    }

    /// `wait <job> --timeout <timeout>`: its verdict line. The exit code is the verdict's (spec §8.1), so any exit
    /// that printed a `wait <job> ` line is an answer; without one the CLI failed.
    public Promise<String> waitFor(long job, TimeSpan timeout) {
        return processes.run(argv("wait",
                                  String.valueOf(job),
                                  "--timeout",
                                  timeout.millis() / 1000 + "s"),
                             env,
                             timeout.plus(WAIT_GRACE))
                        .flatMap(completed -> waitLine(job, completed).async());
    }

    /// `waitFor`, accepting only a job that ended. `temper wait` prints `wait <job> <STATE|VERDICT> …` (`Lines` in
    /// temper-cli): the verdict of a terminal job, or at `--timeout` the last state it saw, with exit 7. A line whose
    /// state is not terminal is [TrustError.WaitTimedOut] with that state, so an unfinished job is never judged.
    public Promise<String> finished(long job, TimeSpan timeout) {
        return waitFor(job, timeout).flatMap(line -> terminal(job, timeout, line).async());
    }

    static Result<String> terminal(long job, TimeSpan timeout, String line) {
        var state = stateOf(job, line);

        return isTerminal(state)
               ? success(line)
               : TrustError.WaitTimedOut.FACTORY.apply(timeout.duration().toHours(),
                                                       state)
                                                .result();
    }

    /// The word after `wait <job> `.
    private static String stateOf(long job, String line) {
        return line.substring(("wait " + job + " ").length())
                   .split(" ", 2) [0];
    }

    private static boolean isTerminal(String state) {
        return Stream.of(JobState.values())
                     .filter(JobState::isTerminal)
                     .map(JobState::name)
                     .anyMatch(state::equals);
    }

    private List<String> argv(String... args) {
        return Stream.concat(command.stream(),
                             Stream.of(args))
                     .toList();
    }

    private static Promise<Long> submittedJob(Completed completed) {
        return Processes.zero(completed)
                        .flatMap(TemperCli::jobOf)
                        .async();
    }

    /// The job id of the CLI's `submit <job> …` line.
    public static Result<Long> jobOf(String stdout) {
        return Option.from(stdout.lines()
                                 .map(SUBMITTED::matcher)
                                 .filter(Matcher::matches)
                                 .map(matcher -> matcher.group(1))
                                 .findFirst())
                     .toResult(TrustError.General.NO_JOB_ID)
                     .flatMap(HarnessArgs::jobId);
    }

    private static Result<String> waitLine(long job, Completed completed) {
        var prefix = "wait " + job + " ";
        var line = completed.stdoutText().lines().filter(candidate -> candidate.startsWith(prefix)).findFirst();

        return Option.from(line).toResult(TrustError.CommandFailed.FACTORY.apply("temper wait",
                                                                                 completed.rc(),
                                                                                 completed.stderrText()));
    }
}
