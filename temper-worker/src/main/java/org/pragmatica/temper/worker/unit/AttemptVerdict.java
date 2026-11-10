package org.pragmatica.temper.worker.unit;

/// What a `StartUnit` does with what an earlier attempt of the unit left on this host (m4-design §4.4): attempt numbers
/// only grow and the controller is the authority, so a lower attempt is superseded and replaced; a higher one can only
/// come from a restored database and is never killed blind.
public enum AttemptVerdict {
    /// The same attempt: a retried start after a link drop, adopted.
    ADOPT,
    /// A lower attempt: removed, then the start proceeds. A container without a `temper.attempt` label (pre-M4) is
    /// attempt 1, so only a `StartUnit` above 1 removes it; `StartUnit(1)` adopts it.
    REPLACE,
    /// A higher attempt: the start is refused, `ERROR(infra: stale-attempt)`.
    REFUSE;
    public static AttemptVerdict verdict(int requested, int held) {
        return switch (Integer.signum(Integer.compare(held, requested))) {
            case 0 -> ADOPT;
            case -1 -> REPLACE;
            default -> REFUSE;
        };
    }
}
