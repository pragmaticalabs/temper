package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// A11 unit tier (m1-design §1.5 rules 3 to 5, §9.1): a unit waiting in bb-hold is neither re-run nor declared
/// lost on reconnect; a holder that died before its grant requeues (nothing ran); one that died after its grant
/// without a container is a visible loss.
class ReconcilerHoldTest {
    private static final Duration TIMEOUT = Duration.ofHours(3);
    private static final UnitRef GATE = UnitRef.unitRef(JobId.jobId(1).unwrap(), UnitName.unitName("gate").unwrap());

    private static DbUnit db(UnitState state, boolean started) {
        return DbUnit.dbUnit(GATE, state, TIMEOUT, started);
    }

    private static DiskUnit disk(ContainerState container, HolderStatus holder, Option<String> ticket) {
        return DiskUnit.diskUnit(GATE, 1, true, none(), container, some(holder), ticket, none());
    }

    private static List<ReconcileAction> decide(DbUnit db, DiskUnit disk) {
        return Reconciler.decide(InstanceId.DEFAULT, List.of(db), List.of(disk), List.of());
    }

    @Test
    void decide_reattaches_holderWaiting() {
        assertThat(decide(db(UnitState.GRANTED, false), disk(ContainerState.ABSENT, HolderStatus.WAITING, none())))
            .containsExactly(new ReconcileAction.Reattach(GATE, TIMEOUT));
    }

    @Test
    void decide_reattaches_holderHeldBeforeTheContainerAppears() {
        assertThat(decide(db(UnitState.GRANTED, false), disk(ContainerState.ABSENT, HolderStatus.HELD, some("T1"))))
            .containsExactly(new ReconcileAction.Reattach(GATE, TIMEOUT));
    }

    @Test
    void decide_reattachesAndCancels_waitingUnitBeingCancelled() {
        assertThat(decide(db(UnitState.CANCELLING, false), disk(ContainerState.ABSENT, HolderStatus.WAITING, none())))
            .containsExactly(new ReconcileAction.Reattach(GATE, TIMEOUT), new ReconcileAction.Cancel(GATE));
    }

    @Test
    void decide_requeues_holderDeadBeforeTheGrant() {
        assertThat(decide(db(UnitState.GRANTED, false), disk(ContainerState.ABSENT, HolderStatus.DEAD, none())))
            .containsExactly(new ReconcileAction.Requeue(GATE));
    }

    @Test
    void decide_completesCancelled_holderDeadBeforeTheGrantOfACancelledUnit() {
        assertThat(decide(db(UnitState.CANCELLING, false), disk(ContainerState.ABSENT, HolderStatus.DEAD, none())))
            .containsExactly(new ReconcileAction.CompleteCancelled(GATE));
    }

    @Test
    void decide_marksLost_holderDeadAfterTheGrantWithoutAContainer() {
        assertThat(decide(db(UnitState.GRANTED, false), disk(ContainerState.ABSENT, HolderStatus.DEAD, some("T1"))))
            .containsExactly(new ReconcileAction.MarkLost(GATE));
    }

    /// A started unit whose holder is dead without a grant line contradicts itself; nothing proves it never ran.
    @Test
    void decide_marksLost_startedUnitWhoseHolderDiedWithoutAGrantLine() {
        assertThat(decide(db(UnitState.RUNNING, true), disk(ContainerState.ABSENT, HolderStatus.DEAD, none())))
            .containsExactly(new ReconcileAction.MarkLost(GATE));
    }

    /// Rules 1 and 2 come first: a held unit whose container runs or exited is the M0 case.
    @Test
    void decide_containerStateWins_overTheHolder() {
        assertThat(decide(db(UnitState.RUNNING, true), disk(ContainerState.RUNNING, HolderStatus.DEAD, some("T1"))))
            .containsExactly(new ReconcileAction.Reattach(GATE, TIMEOUT));
        assertThat(decide(db(UnitState.RUNNING, true), disk(ContainerState.EXITED, HolderStatus.DEAD, some("T1"))))
            .containsExactly(new ReconcileAction.Collect(GATE));
    }

    /// A direct unit (no holder) with a marker and no container stays the M0 loss.
    @Test
    void decide_marksLost_directUnitWithMarkerOnly() {
        assertThat(decide(db(UnitState.GRANTED, false), DiskUnit.diskUnit(GATE, 1, true, none(), ContainerState.ABSENT, none(), none(), none())))
            .containsExactly(new ReconcileAction.MarkLost(GATE));
    }
}
