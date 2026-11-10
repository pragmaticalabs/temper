package org.pragmatica.temper.domain.state;

import org.pragmatica.temper.domain.verdict.Verdict;


/// Job lifecycle state (`jobs.state`). A RUNNING job without `keep` that is superseded passes through
/// SUPERSEDED on its way to CANCELLED (spec §2.4). UNKNOWN: a unit of the job runs but its start could not be
/// recorded (ruling R-13, amended): the job is neither QUEUED nor known to run, until a start or its exit facts arrive.
public enum JobState {
    REFUSED,
    QUEUED,
    UNKNOWN,
    RUNNING,
    CANCELLING,
    SUPERSEDED,
    PASSED,
    FAILED,
    EMPTY,
    VOID,
    ERROR,
    CANCELLED;
    public boolean isTerminal() {
        return switch (this) {
            case QUEUED, UNKNOWN, RUNNING, CANCELLING, SUPERSEDED -> false;
            case REFUSED, PASSED, FAILED, EMPTY, VOID, ERROR, CANCELLED -> true;
        };
    }
    /// The terminal state a verdict puts the job in.
    public static JobState fromVerdict(Verdict verdict) {
        return switch (verdict) {
            case Verdict.Void _ -> VOID;
            case Verdict.Error _ -> ERROR;
            case Verdict.Cancelled _ -> CANCELLED;
            case Verdict.Empty _ -> EMPTY;
            case Verdict.Failed _ -> FAILED;
            case Verdict.Passed _ -> PASSED;
        };
    }
}
