package org.pragmatica.temper.domain.state;

import org.pragmatica.temper.domain.model.SideEffects;


/// What a host loss does to one active unit on the LOST host (m4-design §2.3, rulings R-M4-2, R-M4-5, R-M4-13): the
/// first matching rule wins. The store applies the outcome under the unit's row lock; this decides it.
///
/// | # | Unit | Outcome |
/// |---|---|---|
/// | L1 | CANCELLING | [Outcome#CANCELLED]: the cancel's goal is reached by fencing; nothing is run again |
/// | L2 | `side_effects = external` | [Outcome#EXTERNAL]: `ERROR(host-lost)`, never requeued, a human decides |
/// | L3 | [#REQUEUE_BOUND] requeues already | [Outcome#BOUND]: `ERROR(host-lost)` |
/// | L4 | otherwise | [Outcome#REQUEUED]: the attempt ends `host-lost`, the unit is QUEUED again |
///
/// So a unit's steps run at most `1 + REQUEUE_BOUND` times; the database holds the same bound
/// (`units.host_lost_requeues BETWEEN 0 AND 1`, V11).
public sealed interface LostSettle {
    /// Requeues after a host loss a unit may have (R-M4-13).
    int REQUEUE_BOUND = 1;
    /// The error reason of a unit ended by L2 or L3, and the outcome of every lost attempt in `unit_attempts`.
    String HOST_LOST = "host-lost";

    enum Outcome {
        /// L1: the unit ends CANCELLED.
        CANCELLED,
        /// L2: the unit ends `ERROR(host-lost)`, its effects outside the unit could repeat.
        EXTERNAL,
        /// L3: the unit ends `ERROR(host-lost)`, its requeue bound is reached.
        BOUND,
        /// L4: the unit is QUEUED for a new attempt.
        REQUEUED
    }

    /// `state` is an active state (GRANTED, UNKNOWN, RUNNING, CANCELLING); `requeues` is `units.host_lost_requeues`.
    static Outcome settle(UnitState state, SideEffects sideEffects, int requeues) {
        return state == UnitState.CANCELLING
               ? Outcome.CANCELLED
               : notCancelled(sideEffects, requeues);
    }

    private static Outcome notCancelled(SideEffects sideEffects, int requeues) {
        return sideEffects == SideEffects.EXTERNAL
               ? Outcome.EXTERNAL
               : requeueOrBound(requeues);
    }

    private static Outcome requeueOrBound(int requeues) {
        return requeues >= REQUEUE_BOUND
               ? Outcome.BOUND
               : Outcome.REQUEUED;
    }

    record unused() implements LostSettle {}
}
