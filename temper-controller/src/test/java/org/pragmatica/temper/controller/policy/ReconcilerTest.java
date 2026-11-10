package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.ActiveUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class ReconcilerTest {
    /// Rules 1-6 and the orphan rule, with no store attempt read (rule 0 never applies).
    private static List<ReconcileAction> decided(InstanceId own, List<DbUnit> db, List<DiskUnit> disk) {
        return Reconciler.decide(own, db, disk, List.of());
    }

    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    private static final UnitRef A = UnitRef.unitRef(JobId.jobId(1).unwrap(), UnitName.MAIN);
    private static final UnitRef B = UnitRef.unitRef(JobId.jobId(2).unwrap(), UnitName.MAIN);
    private static final UnitRef C = UnitRef.unitRef(JobId.jobId(3).unwrap(), UnitName.MAIN);

    private static DbUnit db(UnitRef ref, UnitState state, boolean started) {
        return DbUnit.dbUnit(ref, state, TIMEOUT, started);
    }

    @Test
    void decide_collects_rcOnDisk() {
        var actions = decided(InstanceId.DEFAULT, List.of(db(A, UnitState.RUNNING, true)),
                                        List.of(DiskUnit.diskUnit(A, 1, true, some(0), ContainerState.EXITED, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.Collect(A));
    }

    @Test
    void decide_collects_containerExitedWithoutRc() {
        var actions = decided(InstanceId.DEFAULT, List.of(db(A, UnitState.RUNNING, true)),
                                        List.of(DiskUnit.diskUnit(A, 1, true, none(), ContainerState.EXITED, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.Collect(A));
    }

    @Test
    void decide_reattaches_containerRunning() {
        var actions = decided(InstanceId.DEFAULT, List.of(db(A, UnitState.RUNNING, true)),
                                        List.of(DiskUnit.diskUnit(A, 1, true, none(), ContainerState.RUNNING, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.Reattach(A, TIMEOUT));
    }

    /// R-13, amended: the container runs but its start was never recorded (dropped, or the reply lost with the link):
    /// the unit is marked UNKNOWN before it is re-attached, so it never shows QUEUED.
    @Test
    void decide_marksUnknownAndReattaches_grantedWithoutStart_containerRunning() {
        var actions = decided(InstanceId.DEFAULT, List.of(db(A, UnitState.GRANTED, false)),
                                        List.of(DiskUnit.diskUnit(A, 1, true, none(), ContainerState.RUNNING, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.StartUnknown(A), new ReconcileAction.Reattach(A, TIMEOUT));
    }

    /// An UNKNOWN unit started (only its start was not recorded): with nothing on disk it is lost, never requeued to
    /// run twice.
    @Test
    void decide_marksLost_unknownUnitWithNothingOnDisk() {
        assertThat(decided(InstanceId.DEFAULT, List.of(DbUnit.dbUnit(ActiveUnit.activeUnit(A, 1, UnitState.UNKNOWN, 6144,
                                                                                                      TIMEOUT, none(), none(), false,
                                                                                                      none(), none(), UnitKind.MVN, some(Instant.now()), 1))),
                                     List.of()))
            .containsExactly(new ReconcileAction.MarkLost(A));
    }

    @Test
    void decide_reattachesAndCancels_cancellingContainerRunning() {
        var actions = decided(InstanceId.DEFAULT, List.of(db(A, UnitState.CANCELLING, true)),
                                        List.of(DiskUnit.diskUnit(A, 1, true, none(), ContainerState.RUNNING, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.Reattach(A, TIMEOUT), new ReconcileAction.Cancel(A));
    }

    @Test
    void decide_marksLost_markerWithoutContainerOrRc() {
        var actions = decided(InstanceId.DEFAULT, List.of(db(A, UnitState.RUNNING, true)),
                                        List.of(DiskUnit.diskUnit(A, 1, true, none(), ContainerState.ABSENT, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.MarkLost(A));
    }

    @Test
    void decide_requeues_grantedWithoutMarker() {
        var actions = decided(InstanceId.DEFAULT, List.of(db(A, UnitState.GRANTED, false)),
                                        List.of(DiskUnit.diskUnit(A, 1, false, none(), ContainerState.ABSENT, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.Requeue(A));
    }

    @Test
    void decide_requeues_grantedUnknownToDisk() {
        assertThat(decided(InstanceId.DEFAULT, List.of(db(A, UnitState.GRANTED, false)), List.of()))
            .containsExactly(new ReconcileAction.Requeue(A));
    }

    @Test
    void decide_completesCancelled_cancellingNeverStarted() {
        assertThat(decided(InstanceId.DEFAULT, List.of(db(A, UnitState.CANCELLING, false)), List.of()))
            .containsExactly(new ReconcileAction.CompleteCancelled(A));
    }

    @Test
    void decide_marksLost_startedButNothingOnDisk() {
        assertThat(decided(InstanceId.DEFAULT, List.of(db(A, UnitState.RUNNING, true)), List.of()))
            .containsExactly(new ReconcileAction.MarkLost(A));
    }

    @Test
    void decide_killsOrphan_runningContainerNotActive() {
        var actions = decided(InstanceId.DEFAULT, List.of(),
                                        List.of(DiskUnit.diskUnit(B, 1, true, none(), ContainerState.RUNNING, none(), none(), none()),
                                                DiskUnit.diskUnit(A, 1, true, some(0), ContainerState.EXITED, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.KillOrphan(B));
    }

    /// F2: a running container labelled for another controller instance (the acceptance controller on the live
    /// host, or the live one seen by the acceptance controller) is ignored; this instance's own orphan, labelled or
    /// not, is still killed.
    @Test
    void decide_ignoresAnotherInstancesContainer_andStillKillsItsOwnOrphans() {
        var own = InstanceId.instanceId("acceptance").unwrap();
        var actions = decided(own,
                                        List.of(),
                                        List.of(DiskUnit.diskUnit(A, 1, true, none(), ContainerState.RUNNING, none(), none(), some("temper")),
                                                DiskUnit.diskUnit(B, 1, true, none(), ContainerState.RUNNING, none(), none(), some("acceptance")),
                                                DiskUnit.diskUnit(C, 1, true, none(), ContainerState.RUNNING, none(), none(), none())));

        assertThat(actions).containsExactly(new ReconcileAction.KillOrphan(B), new ReconcileAction.KillOrphan(C));
    }
}
