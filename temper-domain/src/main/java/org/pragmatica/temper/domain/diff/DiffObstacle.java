package org.pragmatica.temper.domain.diff;

/// Why `temper diff` refuses to compare (m2-design §3.1, §3.2): the sets would not be exact, so none is computed.
///
/// - `NOT_JUDGED`: a side is VOID, ERROR or CANCELLED, A or B (ruling R-M2-13: `side not judged: <verdict>`), or a
///   REFUSED job, which never ran [G: the ruling names the three verdicts; a REFUSED side is read as not judged too,
///   exit 8 rather than 9, since waiting cannot help].
/// - `PRUNED`: retention deleted rows of a side (F16): fewer primary rows are stored than its record counts.
/// - `NOT_TERMINAL`: a side has no result record yet, so its rows are partial.
/// - `NO_BASE`: `--base <sha>` names no Gate to compare against: `gate <sha>` answers NONE, or RUNNING without a vote.
/// - `BASE_CONFLICT`: `--base <sha>` names a CONFLICT (ruling R-M2-9 on Q-M2-26): either side of a flip says nothing.
///
/// The order is the order [DiffOutcome#decide] reports them in when both sides have one: what waiting cannot change first.
public enum DiffObstacle {
    NOT_JUDGED,
    PRUNED,
    NOT_TERMINAL,
    NO_BASE,
    BASE_CONFLICT
}
