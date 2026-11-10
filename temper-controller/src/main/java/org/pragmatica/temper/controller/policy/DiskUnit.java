package org.pragmatica.temper.controller.policy;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.UnitRef;


/// A unit directory from the worker's `Hello` scan (m0-design §7.2): marker present, rc on disk, container state, and
/// the attempt the directory holds (protocol v3, m4-design §7; a directory written before M4 holds attempt 1).
/// `holder` (m1-design §1.5) is the bb-hold holder's status for a unit started through bb-hold, none for a direct
/// unit; `ticket` is the ticket of the `granted` line in `bbhold.err`, none before a grant; `instance` is a running
/// container's `temper.instance` label (F2), none when it carries none. The wire form is read
/// by the link that receives `Hello` (`ReconcileRun`), so the policy never sees a protocol type.
public record DiskUnit(UnitRef ref,
                       int attempt,
                       boolean hasMarker,
                       Option<Integer> rc,
                       ContainerState container,
                       Option<HolderStatus> holder,
                       Option<String> ticket,
                       Option<String> instance) {
    public static DiskUnit diskUnit(UnitRef ref,
                                    int attempt,
                                    boolean hasMarker,
                                    Option<Integer> rc,
                                    ContainerState container,
                                    Option<HolderStatus> holder,
                                    Option<String> ticket,
                                    Option<String> instance) {
        return new DiskUnit(ref, attempt, hasMarker, rc, container, holder, ticket, instance);
    }

    /// Labelled for another controller instance: not this controller's to reconcile (F2). An unlabelled container is
    /// this instance's (R-12: no compatibility path for containers started before the label existed).
    public boolean belongsToAnother(InstanceId own) {
        return instance.filter(label -> !label.equals(own.value()))
                       .isPresent();
    }

    /// The hold was granted: bb-hold printed its `granted` line.
    public boolean granted() {
        return ticket.isPresent();
    }
}
