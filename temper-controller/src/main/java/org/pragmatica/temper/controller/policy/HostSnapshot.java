package org.pragmatica.temper.controller.policy;

import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.store.api.QueuedUnit;


/// What the grant policy knows about one host (m0-design §3.10, m1-design §1.3): its cap, the reactors outside
/// temper (worker facts), temper's active units and their memory (the store, GRANTED included, so a unit waiting in
/// bb-hold counts too), the memory budget, the free disk (the smaller of the docker root and the workspace
/// filesystem), the floor, whether port isolation holds, how many of temper's active units go through bb-hold
/// (`portGroupUnits`, counted by [#holdsPortGroup]), and how many may wait for or hold the group at once
/// (`[portGroup] maxHolding`, Q-M1-12).
///
/// The memory floor (`[[hosts]] memFloorGiB`, `memFloorMb` here, 0 = off): `memAvailableMb` is the worker's
/// MemAvailable from the latest facts, and `pendingMemoryMb` the memory of temper's units on the host that those facts
/// cannot reflect yet. The scheduler counts an active unit there unless its start is recorded and the facts were sampled
/// after it (`Scheduler.pendingMemoryMb`): a GRANTED unit (its container has not started: it waits for its launch or
/// in bb-hold), and a unit whose start is recorded but that the latest heartbeat sampled before. Every unit granted
/// earlier in the same round adds its memory too ([#withGranted]), as it adds to `activeMemoryMb` for the budget. So
/// two units that fit the floor only together are never both granted, in one round or in later ticks that still read
/// the same facts: the first stays in `pending` until a sample taken after its start arrives. Both instants are the
/// worker's clock (the facts' `at`, the unit's recorded `start`). The floor is a margin, not a reservation; what it
/// does not see:
/// - memory a unit's JVMs have not touched yet: a unit that just started counts less than its `--memory` until it grows;
/// - a start inside a facts probe: `at` is stamped after MemAvailable was read (`FactsProbe`), so a start recorded
///   between the read and the stamp counts as reflected;
/// - a hold unit's start, written when its bb-hold grant is seen (`GrantWatcher`), before its container exists, and an
///   adopted unit's start, which is its marker (`UnitStarter`): a sample after that instant may still predate the
///   container;
/// - a backward step of the worker's clock between a sample and a start. Without facts the host is skipped (no round, as for the disk floor, which reads the same
/// facts); old facts are used as they are, as the disk floor uses them.
public record HostSnapshot(int cap,
                           int foreignReactors,
                           int activeUnits,
                           int memoryBudgetMb,
                           int activeMemoryMb,
                           long freeBytes,
                           long floorBytes,
                           boolean portIsolated,
                           int portGroupUnits,
                           int maxHolding,
                           long memAvailableMb,
                           int pendingMemoryMb,
                           long memFloorMb) {
    /// A host without a memory floor (off), as before `memFloorGiB`.
    public static HostSnapshot hostSnapshot(int cap,
                                            int foreignReactors,
                                            int activeUnits,
                                            int memoryBudgetMb,
                                            int activeMemoryMb,
                                            long freeBytes,
                                            long floorBytes,
                                            boolean portIsolated,
                                            int portGroupUnits,
                                            int maxHolding) {
        return hostSnapshot(cap,
                            foreignReactors,
                            activeUnits,
                            memoryBudgetMb,
                            activeMemoryMb,
                            freeBytes,
                            floorBytes,
                            portIsolated,
                            portGroupUnits,
                            maxHolding,
                            0,
                            0,
                            0);
    }

    public static HostSnapshot hostSnapshot(int cap,
                                            int foreignReactors,
                                            int activeUnits,
                                            int memoryBudgetMb,
                                            int activeMemoryMb,
                                            long freeBytes,
                                            long floorBytes,
                                            boolean portIsolated,
                                            int portGroupUnits,
                                            int maxHolding,
                                            long memAvailableMb,
                                            int pendingMemoryMb,
                                            long memFloorMb) {
        return new HostSnapshot(cap,
                                foreignReactors,
                                activeUnits,
                                memoryBudgetMb,
                                activeMemoryMb,
                                freeBytes,
                                floorBytes,
                                portIsolated,
                                portGroupUnits,
                                maxHolding,
                                memAvailableMb,
                                pendingMemoryMb,
                                memFloorMb);
    }

    /// Host reactors as the cap rule counts them (spec §6.1, A7): foreign launchers plus temper's active units.
    public int reactors() {
        return foreignReactors + activeUnits;
    }

    /// Whether a unit goes through bb-hold on a host: it needs the port group, and it is a Gate or the host is not
    /// port-isolated. An unsharded Gate keeps bb-hold even on a port-isolated host until E1 passes (m0-design §12.4),
    /// so on such a host it still counts against `maxHolding` (ruling R-3). The launcher routes by the same rule.
    public static boolean holdsPortGroup(UnitKind kind, boolean needsPortGroup, boolean portIsolated) {
        return needsPortGroup && (kind == UnitKind.GATE_UNSHARDED || !portIsolated);
    }

    /// Whether `unit` would go through bb-hold on this host.
    public boolean holds(QueuedUnit unit) {
        return holdsPortGroup(unit.kind(), unit.needsPortGroup(), portIsolated);
    }

    /// Whether one more bb-hold unit may be granted: fewer than `maxHolding` of temper's units wait for or hold the
    /// group.
    public boolean portGroupFree() {
        return portGroupUnits < maxHolding;
    }

    /// The snapshot after `unit` was granted in the same round: its memory counts against the budget and, until the
    /// facts can reflect it, against the memory floor.
    public HostSnapshot withGranted(QueuedUnit unit) {
        return new HostSnapshot(cap,
                                foreignReactors,
                                activeUnits + 1,
                                memoryBudgetMb,
                                activeMemoryMb + unit.memoryMb(),
                                freeBytes,
                                floorBytes,
                                portIsolated,
                                portGroupUnits + portGroupCount(unit),
                                maxHolding,
                                memAvailableMb,
                                pendingMemoryMb + unit.memoryMb(),
                                memFloorMb);
    }

    private int portGroupCount(QueuedUnit unit) {
        return holds(unit)
               ? 1
               : 0;
    }
}
