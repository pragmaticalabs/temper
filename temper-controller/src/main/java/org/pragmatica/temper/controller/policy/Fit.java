package org.pragmatica.temper.controller.policy;

import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.QueuedUnit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Whether a unit fits a host (the fit rule of [GrantPolicy], spec §6.1), as the rules it fails. [GrantPolicy#fits]
/// and [GrantExplain] both read this one value, so the grant and its explanation cannot disagree about a fit. Every
/// rule is evaluated (no short-circuit), so an explanation names all of them; the policy reads only [#fits], which is
/// the conjunction the rules always were.
public record Fit(List<Failure> failures) {
    public Fit {
        failures = List.copyOf(failures);
    }

    /// One fit rule a unit fails on a host.
    public sealed interface Failure extends GrantExplain.Reason {}

    /// `activeMb + unitMb > budgetMb`: temper's active memory plus this unit's exceeds the host's budget.
    public record Memory(int activeMb, int unitMb, int budgetMb) implements Failure {}

    /// `availableMb - pendingMb - unitMb < floorMb`: the host's free memory (the worker's MemAvailable), less the memory
    /// of temper's units it does not reflect yet ([HostSnapshot#pendingMemoryMb]) and this unit's, would fall below the
    /// host's floor (`[[hosts]] memFloorGiB`). Never reported while the floor is 0 (off).
    public record MemFloor(long availableMb, int pendingMb, int unitMb, long floorMb) implements Failure {}

    /// `freeBytes < floorBytes`: the host's free disk is below the floor.
    public record DiskFloor(long freeBytes, long floorBytes) implements Failure {}

    /// The unit goes through bb-hold and `units >= maxHolding` of temper's units already wait for or hold the group.
    public record PortGroup(int units, int maxHolding) implements Failure {}

    public static Fit fit(HostSnapshot host, QueuedUnit unit) {
        return new Fit(Stream.of(memory(host, unit),
                                 memFloor(host, unit),
                                 diskFloor(host),
                                 portGroup(host, unit))
                             .flatMap(Option::stream)
                             .toList());
    }

    /// No rule fails.
    public boolean fits() {
        return failures.isEmpty();
    }

    private static Option<Failure> memory(HostSnapshot host, QueuedUnit unit) {
        return host.activeMemoryMb() + unit.memoryMb() <= host.memoryBudgetMb()
               ? none()
               : some(new Memory(host.activeMemoryMb(), unit.memoryMb(), host.memoryBudgetMb()));
    }

    /// A floor of 0 is off, so a host without one keeps exactly the rules it had (its MemAvailable is not read).
    private static Option<Failure> memFloor(HostSnapshot host, QueuedUnit unit) {
        return host.memFloorMb() == 0 || host.memAvailableMb() - host.pendingMemoryMb() - unit.memoryMb() >= host.memFloorMb()
               ? none()
               : some(new MemFloor(host.memAvailableMb(), host.pendingMemoryMb(), unit.memoryMb(), host.memFloorMb()));
    }

    private static Option<Failure> diskFloor(HostSnapshot host) {
        return host.freeBytes() >= host.floorBytes()
               ? none()
               : some(new DiskFloor(host.freeBytes(), host.floorBytes()));
    }

    private static Option<Failure> portGroup(HostSnapshot host, QueuedUnit unit) {
        return ! host.holds(unit) || host.portGroupFree()
               ? none()
               : some(new PortGroup(host.portGroupUnits(), host.maxHolding()));
    }
}
