package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.temper.domain.model.UnitKind;


/// A QUEUED unit as the grant policy sees it (m0-design §3.10). Declared here rather than in
/// `controller.policy` because the store calls the policy and cannot depend on the controller. `kind` tells the fit
/// whether the unit is a Gate, which waits in bb-hold even on a port-isolated host (ruling R-3). `dedicatedCpu` is its
/// job's CPU class (m4-design §9.2): the grant never reads such a unit for a `sharedCpu` host, so the policy never sees
/// it there; the digest's explanation, which reads every queued unit, names it `shared-cpu` on such a host.
public record QueuedUnit(long unitId,
                         int priority,
                         Instant submittedAt,
                         int memoryMb,
                         boolean needsPortGroup,
                         UnitKind kind,
                         boolean dedicatedCpu) {
    /// A unit of a job without `--dedicated-cpu`.
    public static QueuedUnit queuedUnit(long unitId,
                                        int priority,
                                        Instant submittedAt,
                                        int memoryMb,
                                        boolean needsPortGroup,
                                        UnitKind kind) {
        return new QueuedUnit(unitId, priority, submittedAt, memoryMb, needsPortGroup, kind, false);
    }

    public static QueuedUnit queuedUnit(long unitId,
                                        int priority,
                                        Instant submittedAt,
                                        int memoryMb,
                                        boolean needsPortGroup,
                                        UnitKind kind,
                                        boolean dedicatedCpu) {
        return new QueuedUnit(unitId, priority, submittedAt, memoryMb, needsPortGroup, kind, dedicatedCpu);
    }
}
