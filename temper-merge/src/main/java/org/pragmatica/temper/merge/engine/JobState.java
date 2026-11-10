package org.pragmatica.temper.merge.engine;

/// The states of one merge job (#101). Only the runner advances a job; a terminal state is never left.
///
/// - `SUBMITTED`: accepted, nothing read yet; the next step reads the PR (head, draft, open).
/// - `WAITING_CHECKS`: the head equals the submitted sha; waiting until every required check is green at that sha.
/// - `ENQUEUING`: every required check is green; persisted BEFORE the enqueue is sent, so a restart that finds a job
///   here knows the enqueue may have been sent and never sends a second one.
/// - `QUEUED`: enqueued with `expectedHeadOid`; watching for the merge or the removal.
/// - `AWAITING_RUN`: removed from the queue; waiting for the merge-group run to complete, because GitHub serves a job's
///   log only once the whole run has completed.
/// - terminal: `MERGED`, `REMOVED` (never re-enqueued), `REFUSED` (a precondition did not hold; nothing was enqueued),
///   `TIMEOUT` (a phase's deadline passed; the reason names the phase and its last observation), `FAILED` (a write
///   GitHub did not confirm).
public enum JobState {
    SUBMITTED,
    WAITING_CHECKS,
    ENQUEUING,
    QUEUED,
    AWAITING_RUN,
    MERGED,
    REMOVED,
    REFUSED,
    TIMEOUT,
    FAILED;
    public boolean terminal() {
        return switch (this) {
            case MERGED, REMOVED, REFUSED, TIMEOUT, FAILED -> true;
            case SUBMITTED, WAITING_CHECKS, ENQUEUING, QUEUED, AWAITING_RUN -> false;
        };
    }
}
