package org.pragmatica.temper.domain.hold;

/// The bb-hold process that holds (or waits for) a unit's port-group ticket, as the worker observes it
/// (m1-design §1.4). `WAITING`: alive, no `granted` line yet. `HELD`: alive and granted. `DEAD`: no live
/// process with the recorded identity (pid, `/proc` start time, cmdline), so a recycled pid reads as dead.
public enum HolderStatus {
    WAITING,
    HELD,
    DEAD
}
