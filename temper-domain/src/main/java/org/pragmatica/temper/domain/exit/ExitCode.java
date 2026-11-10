package org.pragmatica.temper.domain.exit;

import org.pragmatica.temper.domain.diff.DiffObstacle;
import org.pragmatica.temper.domain.diff.DiffOutcome;
import org.pragmatica.temper.domain.gate.GateAnswer;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.verdict.Verdict;


/// CLI exit codes (spec §8.1, stable; pinned by tests). `UNAUTHORIZED` (77) covers HTTP 401 (Q-15 ruling):
/// every route without the bearer token answers 401. `CONFLICT` (11) is `gate`'s answer when the newest judge's Gates
/// at a SHA disagree (m2-design §1.5, rulings R-M2-5 and R-M2-9): a code of its own, so that a script never reads a
/// flipping gate as green (0) or as a plain red (1). `LEASE_LOST` (12) is a lease-token call the controller refused
/// with `409 lease-lost` (m3-design §6.4, §11.2): the caller's epoch is no longer the task's, and nothing it sends
/// under that lease is accepted again. `VERSION_SKEW` (13) is a CLI that refused a controller of another build (D-6):
/// both judged and different, so the CLI may misdecode its answers; a code of its own, because `EMPTY` (3) is a
/// verdict and `alarm-check`'s 3 is a delivered alarm.
///
/// `alarm-check` (the maintenance alarm) has its own three: every condition clear (0), the sink failed to deliver (2),
/// and at least one condition firing and delivered to the sink (3). Firing is not 1 because the JVM itself exits 1 (a
/// crash, an `OutOfMemoryError`), and the systemd unit counts 3 alone as a success besides 0.
///
/// `events --until` has its own five, besides 0 for the matching line: no heartbeat received for
/// 110 s (3), a source the wait depends on is stale (4), `--timeout` reached (5), the controller's self-check failed (6),
/// and the temper epoch changed under `--after` (7). An agent is woken by the exit and reads the code alone, so each
/// says why the wait ended.
///
/// `temper merge` exits `MERGE_FAILED` (3) when `gh pr merge` itself failed after every temper
/// check passed and `merging` was recorded: a code of its own, apart from a refusal (2), because the GO was spent on a
/// merge call that GitHub refused.
public enum ExitCode {
    PASSED(0),
    ACCEPTED(0),
    FAILED(1),
    REFUSED(2),
    EMPTY(3),
    VOID(4),
    ERROR(5),
    CANCELLED(6),
    WAIT_TIMEOUT(7),
    NONE(8),
    PENDING(9),
    DEDUPLICATED(10),
    CONFLICT(11),
    LEASE_LOST(12),
    VERSION_SKEW(13),
    USAGE(64),
    UNREACHABLE(69),
    UNAUTHORIZED(77),
    ALARM_CLEAR(0),
    ALARM_UNDELIVERED(2),
    ALARM_FIRING(3),
    NO_HEARTBEAT(3),
    SOURCE_STALE(4),
    EVENTS_TIMEOUT(5),
    SELF_CHECK_FAILED(6),
    EPOCH_CHANGED(7),
    MERGE_FAILED(3);
    private final int code;
    ExitCode(int code) {
        this.code = code;
    }
    public int code() {
        return code;
    }
    public static ExitCode forVerdict(Verdict verdict) {
        return switch (verdict) {
            case Verdict.Passed _ -> PASSED;
            case Verdict.Failed _ -> FAILED;
            case Verdict.Empty _ -> EMPTY;
            case Verdict.Void _ -> VOID;
            case Verdict.Error _ -> ERROR;
            case Verdict.Cancelled _ -> CANCELLED;
        };
    }
    /// Exit code for a `gate` answer (m2-design §1.5): PASSED 0, FAILED 1, EMPTY 3, CONFLICT 11, NONE 8, RUNNING 9.
    public static ExitCode forGate(GateAnswer answer) {
        return switch (answer) {
            case PASSED -> PASSED;
            case FAILED -> FAILED;
            case EMPTY -> EMPTY;
            case CONFLICT -> CONFLICT;
            case NONE -> NONE;
            case RUNNING -> PENDING;
        };
    }
    /// Exit code for `diff` (m2-design §3.4): the compared sets' ([#forDiff(int, int, int)]) or the obstacle's
    /// ([#forDiff(DiffObstacle)]).
    public static ExitCode forDiff(DiffOutcome outcome) {
        return switch (outcome) {
            case DiffOutcome.Compared compared -> forDiff(compared.diff().newlyFailing().size(),
                                                          compared.diff().addedFailing(),
                                                          compared.diff().testcasesB());
            case DiffOutcome.Refused refused -> forDiff(refused.obstacle());
        };
    }
    /// Exit code for a compared `diff` (m2-design §3.4; rulings R-M2-9 on Q-M2-6 and R-M2-13): 0 when nothing is newly
    /// failing (skipped → failed included), no added test fails and side B ran tests; 1 otherwise, an EMPTY B included.
    /// A skip change never raises it.
    public static ExitCode forDiff(int newlyFailing, int addedFailing, int testcasesB) {
        return newlyFailing == 0 && addedFailing == 0 && testcasesB > 0
               ? PASSED
               : FAILED;
    }
    /// Exit code for a `diff` that was not computed (m2-design §3.4): 9 while a side is not terminal, since waiting can
    /// change it; 8 for a side not judged (R-M2-13), a pruned side, no base or a conflicting base.
    public static ExitCode forDiff(DiffObstacle obstacle) {
        return switch (obstacle) {
            case NOT_TERMINAL -> PENDING;
            case NOT_JUDGED, PRUNED, NO_BASE, BASE_CONFLICT -> NONE;
        };
    }
    /// Exit code for a job state; any non-terminal state is `PENDING` (9).
    public static ExitCode forState(JobState state) {
        return switch (state) {
            case PASSED -> PASSED;
            case FAILED -> FAILED;
            case REFUSED -> REFUSED;
            case EMPTY -> EMPTY;
            case VOID -> VOID;
            case ERROR -> ERROR;
            case CANCELLED -> CANCELLED;
            case QUEUED, UNKNOWN, RUNNING, CANCELLING, SUPERSEDED -> PENDING;
        };
    }
}
