package org.pragmatica.temper.domain.task;

/// A task's counters (m3-design §5.2): `round` counts VERIFY→FIX transitions only (T9, HR2) against `roundCap`;
/// `ciRounds` counts CI→FIX (T12, ruling R-M3-10); `visit` numbers the stage visits, and `visitExpiries` and
/// `visitFailures` count lease expiries and worker releases within the current visit (§6.2, ruling R-M3-11).
public record Counters(int round, int roundCap, int ciRounds, int visit, int visitExpiries, int visitFailures) {
    public static Counters counters(int round,
                                    int roundCap,
                                    int ciRounds,
                                    int visit,
                                    int visitExpiries,
                                    int visitFailures) {
        return new Counters(round, roundCap, ciRounds, visit, visitExpiries, visitFailures);
    }

    /// A created task: no round, no visit yet (TRIAGED is visit 0).
    public static Counters initial(int roundCap) {
        return new Counters(0, roundCap, 0, 0, 0, 0);
    }

    /// A new visit: the visit number moves on, its expiries and failures start at 0.
    Counters nextVisit() {
        return new Counters(round, roundCap, ciRounds, visit + 1, 0, 0);
    }

    Counters plusExpiry() {
        return new Counters(round, roundCap, ciRounds, visit, visitExpiries + 1, visitFailures);
    }

    Counters plusFailure() {
        return new Counters(round, roundCap, ciRounds, visit, visitExpiries, visitFailures + 1);
    }

    Counters plusRound() {
        return new Counters(round + 1, roundCap, ciRounds, visit, visitExpiries, visitFailures);
    }

    Counters plusCiRound() {
        return new Counters(round, roundCap, ciRounds + 1, visit, visitExpiries, visitFailures);
    }

    Counters plusRoundCap(int extra) {
        return new Counters(round, roundCap + extra, ciRounds, visit, visitExpiries, visitFailures);
    }

    /// T9 applies while `round < roundCap`; at the cap the failing VERIFY parks (T9′).
    boolean hasRoundLeft() {
        return round < roundCap;
    }
}
