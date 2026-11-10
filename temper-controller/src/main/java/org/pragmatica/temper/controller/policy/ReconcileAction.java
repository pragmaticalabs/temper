package org.pragmatica.temper.controller.policy;

import java.time.Duration;

import org.pragmatica.temper.domain.model.UnitRef;


/// What reconcile does with one unit after a handshake (spec §6.4, m0-design §3.10).
public sealed interface ReconcileAction {
    UnitRef ref();

    /// The unit exited (rc on disk, or the container exited): ask the worker to (re)send its report.
    record Collect(UnitRef ref) implements ReconcileAction {}

    /// The container is still running, or the unit's bb-hold holder is alive (waiting or holding, m1-design §1.5):
    /// re-arm its timer with the controller's authoritative timeout (the worker re-arms a holder's `GrantWatcher`).
    record Reattach(UnitRef ref, Duration timeout) implements ReconcileAction {}

    /// The container runs but the store has the unit GRANTED with no recorded start (a dropped start, ruling R-13
    /// amended, or a start reply lost with the link): mark it UNKNOWN, never QUEUED (`UnitStore.startUnknown`).
    record StartUnknown(UnitRef ref) implements ReconcileAction {}

    /// A marker with neither container nor rc: the unit ran and its outcome is gone → `ERROR(lost)`.
    record MarkLost(UnitRef ref) implements ReconcileAction {}

    /// GRANTED and never started (no marker): back to QUEUED (Q-11).
    record Requeue(UnitRef ref) implements ReconcileAction {}

    /// CANCELLING and never started (no marker): finish it as CANCELLED. Not in the §3.10 sketch: requeueing a
    /// cancelled unit would be wrong, and the store finishes such a unit through `complete`.
    record CompleteCancelled(UnitRef ref) implements ReconcileAction {}

    /// Rule 0 (m4-design §4.5): the disk holds `attempt`, which the store no longer counts on this host: the worker
    /// kills its container if it runs and removes the container, its network and the unit directory, only when it
    /// still holds exactly that attempt (`DiscardUnit`). The host gets no grant before every discard succeeded.
    record Discard(UnitRef ref, int attempt) implements ReconcileAction {}

    /// A running container the store does not consider active on this host, and not superseded (rule 0): kill it
    /// (`CancelUnit`).
    record KillOrphan(UnitRef ref) implements ReconcileAction {}

    /// The store says CANCELLING and the container still runs: re-send `CancelUnit`.
    record Cancel(UnitRef ref) implements ReconcileAction {}
}
