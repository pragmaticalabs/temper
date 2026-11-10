package org.pragmatica.temper.store.api;

/// A GO to create: what it was computed for, the verdict row it rests on, and the temper epoch
/// the gate's `fleetSeq` belongs to.
public record NewGo(GoBasis basis, long verdictId, String fleetEpoch) {
    public static NewGo newGo(GoBasis basis, long verdictId, String fleetEpoch) {
        return new NewGo(basis, verdictId, fleetEpoch);
    }
}
