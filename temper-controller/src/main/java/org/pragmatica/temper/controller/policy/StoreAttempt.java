package org.pragmatica.temper.controller.policy;

import org.pragmatica.temper.domain.model.UnitRef;


/// A unit's current attempt as the store has it (`UnitStore.attempts`, m4-design §4.5), seen from the reconciling
/// host: `here` when the unit's current attempt is on this host, active or ended (not QUEUED, not on another host).
/// Read at the link, so the reconcile policy never sees a store type.
public record StoreAttempt(UnitRef ref, int attempt, boolean here) {
    public static StoreAttempt storeAttempt(UnitRef ref, int attempt, boolean here) {
        return new StoreAttempt(ref, attempt, here);
    }

    /// Rule 0 (m4-design §4.5): the disk holds an attempt the store does not count on this host. Any other attempt
    /// than the store's is superseded: a lower one wherever the current one is, and a higher one too (only a grant the
    /// store lost, e.g. a restored database, leaves one; nothing in the store owns or times it, and its result could
    /// never be stored). The same attempt is superseded once it is no longer this host's (the store requeued it).
    public boolean supersedes(int onDisk) {
        return onDisk != attempt || !here;
    }
}
