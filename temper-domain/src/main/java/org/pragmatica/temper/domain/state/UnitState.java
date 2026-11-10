package org.pragmatica.temper.domain.state;

import org.pragmatica.temper.domain.verdict.Verdict;


/// Unit lifecycle state (`units.state`). UNKNOWN: the unit was granted and its worker reported the start, but the
/// start could not be recorded (ruling R-13, amended): it occupies its slot like a RUNNING unit, without start facts.
public enum UnitState {
    QUEUED,
    GRANTED,
    UNKNOWN,
    RUNNING,
    CANCELLING,
    PASSED,
    FAILED,
    EMPTY,
    VOID,
    ERROR,
    CANCELLED;
    public boolean isTerminal() {
        return switch (this) {
            case QUEUED, GRANTED, UNKNOWN, RUNNING, CANCELLING -> false;
            case PASSED, FAILED, EMPTY, VOID, ERROR, CANCELLED -> true;
        };
    }
    /// The terminal state a unit-level verdict puts the unit in.
    public static UnitState fromVerdict(Verdict verdict) {
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
