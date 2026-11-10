package org.pragmatica.temper.acceptance.trust;

import java.nio.file.Path;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Every way the T1/T2 tooling can fail before it has a comparison to report.
public sealed interface TrustError extends Cause {
    enum General implements TrustError {
        BB_REPORT_NOT_FINISHED("bb-report: no rc yet (the build is still running or never started)"),
        NO_JOB_ID("The CLI's submit line names no job");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// bb-report output that does not have the shape its script prints; `detail` names what is missing or wrong.
    record MalformedBbReport(String detail, String message) implements TrustError {
        public static final Fn1<MalformedBbReport, String> FACTORY = Causes.forOneValue("Malformed bb-report output: %s",
                                                                                        MalformedBbReport::new);
    }

    /// A bb-report exit code outside its contract (0 report, 1 no rc yet, 3 EMPTY RUN).
    record UnknownBbReportExit(int exit, String message) implements TrustError {
        public static final Fn1<UnknownBbReportExit, Integer> FACTORY = Causes.forOneValue("Unknown bb-report exit code %d",
                                                                                           UnknownBbReportExit::new);
    }

    /// A command-line argument that is missing or does not parse.
    record Usage(String detail, String message) implements TrustError {
        public static final Fn1<Usage, String> FACTORY = Causes.forOneValue("Usage: %s", Usage::new);
    }

    /// A file, process or controller call that failed; the underlying failure is the origin.
    @SuppressWarnings("JBCT-CAUSE-04")
    record Io(String operation, Cause origin, String message) implements TrustError, Cause.Wrapped {
        public static final Fn2<Io, String, Cause> FACTORY = Causes.forTwoValues("Failed to %s", Io::new);
    }

    /// A report or evidence directory that another harness run created first; the origin is the refused creation.
    /// A live run's claim and a re-run's report move on to the next free number (N3). A live run's own report name
    /// is taken only by a hand-made file during the run (its claim excludes every other run); that one fails (69).
    @SuppressWarnings("JBCT-CAUSE-04")
    record Taken(Path path, Cause origin, String message) implements TrustError, Cause.Wrapped {
        public static final Fn2<Taken, Path, Cause> FACTORY = Causes.forTwoValues("%s exists already", Taken::new);
    }

    /// A process that exited non-zero where only 0 is an answer; its output tail is data, not prose.
    record CommandFailed(String command, int rc, String output, String message) implements TrustError {
        public static final Fn3<CommandFailed, String, Integer, String> FACTORY = Causes.forThreeValues("%s exited %d: %s",
                                                                                                        CommandFailed::new);
    }

    /// A job that never got to run: its SHA is NOT COMPARABLE (capacity), never a failure, and the job is left as it
    /// is (owner ruling, option A).
    sealed interface Capacity extends TrustError {}

    /// T1's queue phase ([QueueWait]): at `--queue-hours` the job is still QUEUED; `waitingFor` is `QUEUED`, or
    /// `QUEUED (waiting for the port group since <instant>)` when a granted unit's bb-hold still waits behind another
    /// bb build.
    record StillWaiting(long job, String waitingFor, long hours, String message) implements Capacity {
        public static final Fn3<StillWaiting, Long, String, Long> FACTORY = Causes.forThreeValues("job %d still %s after %dh",
                                                                                                  StillWaiting::new);
    }

    /// A `--rerun` read the job once and found it still QUEUED (`waitingFor` as in [StillWaiting]): a re-judge only
    /// of a job in a final state (maintainer rule), so the SHA stays NOT COMPARABLE (capacity).
    record QueuedAtRerun(long job, String waitingFor, String message) implements Capacity {
        public static final Fn2<QueuedAtRerun, Long, String> FACTORY = Causes.forTwoValues("job %d still %s at rerun",
                                                                                           QueuedAtRerun::new);
    }

    /// A `--rerun` read the job once and found it out of the queue but not in a final state (`state`: RUNNING,
    /// UNKNOWN, …): it is not judged (maintainer rule: a re-judge only when the job reached a final state).
    record NotFinishedAtRerun(long job, String state, String message) implements TrustError {
        public static final Fn2<NotFinishedAtRerun, Long, String> FACTORY = Causes.forTwoValues("job %d not finished at rerun (state %s)",
                                                                                                NotFinishedAtRerun::new);
    }

    /// T1's run phase: `temper wait` hit `--run-hours` (exit 7) and printed `state`, the last non-terminal state it
    /// saw. The job did not end, so it is not judged.
    record WaitTimedOut(long hours, String state, String message) implements TrustError {
        public static final Fn2<WaitTimedOut, Long, String> FACTORY = Causes.forTwoValues("wait timed out after %dh at state %s",
                                                                                          WaitTimedOut::new);
    }

    /// A controller answer outside the route's contract.
    record UnexpectedStatus(String path, int status, String message) implements TrustError {
        public static final Fn2<UnexpectedStatus, String, Integer> FACTORY = Causes.forTwoValues("Unexpected answer from %s: HTTP %d",
                                                                                                 UnexpectedStatus::new);
    }
}
