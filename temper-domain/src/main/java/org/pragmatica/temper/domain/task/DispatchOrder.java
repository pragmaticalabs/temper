package org.pragmatica.temper.domain.task;

/// The deterministic dispatcher order of READY tasks (m3-design §8, HR7, ruling R-M3-12), first difference wins:
/// `critical` descending, [#rank] ascending, `priority` descending, `ready_since` ascending, `id` ascending. The store
/// sorts by these keys in SQL (`tasks_ready` covers them); the stage rank is this one function, from which the store's
/// `CASE` is derived.
public sealed interface DispatchOrder {
    /// FIX, CI and GATE (the stages next to MERGE-READY) rank 0, VERIFY 1, DESIGN and CODE 2. TRIAGED and the HELD
    /// stages are never claimed; they rank last.
    static int rank(Stage stage) {
        return switch (stage) {
            case FIX, CI, GATE -> 0;
            case VERIFY -> 1;
            case DESIGN, CODE -> 2;
            case TRIAGED, MERGE_READY, NEEDS_RULING, MERGED, ABANDONED -> 3;
        };
    }

    record unused() implements DispatchOrder {}
}
