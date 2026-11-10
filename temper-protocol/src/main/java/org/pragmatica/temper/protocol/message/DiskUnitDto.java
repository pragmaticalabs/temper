package org.pragmatica.temper.protocol.message;

import org.pragmatica.lang.Option;


/// A unit directory found by the worker's startup scan (m0-design §7.2), reported in `Hello` for reconcile.
/// `holder` (protocol v2, m1-design §1.5) is the bb-hold holder's status for a unit started through bb-hold;
/// `none` means the unit is not a hold unit. `ticket` is the ticket of the `granted` line in `bbhold.err`, none
/// before a grant: it splits reconcile rule 4 (a dead holder, never granted: requeue) from rule 5 (granted, the
/// container gone: lost). `attempt` (protocol v3, m4-design §7) is the unit's `.temper/attempt`; none for a unit
/// directory without one (written before M4), which the controller reads as attempt 1.
public record DiskUnitDto(long job,
                          String unit,
                          boolean hasMarker,
                          Option<Integer> rc,
                          ContainerStatus container,
                          Option<HolderStatusDto> holder,
                          Option<String> ticket,
                          Option<String> instance,
                          Option<Integer> attempt) {
    public static DiskUnitDto diskUnitDto(long job,
                                          String unit,
                                          boolean hasMarker,
                                          Option<Integer> rc,
                                          ContainerStatus container,
                                          Option<HolderStatusDto> holder,
                                          Option<String> ticket,
                                          Option<String> instance,
                                          Option<Integer> attempt) {
        return new DiskUnitDto(job, unit, hasMarker, rc, container, holder, ticket, instance, attempt);
    }
}
