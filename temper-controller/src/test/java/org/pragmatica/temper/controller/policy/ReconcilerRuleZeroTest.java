package org.pragmatica.temper.controller.policy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Rule 0 (m4-design §4.5, §12.1): a disk unit whose attempt the store no longer counts on this host is discarded,
/// first, and the rules below never see it. Probe P9: remove rule 0 → every row here that expects a `Discard` is red.
class ReconcilerRuleZeroTest {
    private static final Duration TIMEOUT = Duration.ofMinutes(60);
    private static final UnitRef A = UnitRef.unitRef(JobId.jobId(1).unwrap(), UnitName.MAIN);
    private static final UnitRef B = UnitRef.unitRef(JobId.jobId(2).unwrap(), UnitName.MAIN);

    private static DbUnit db(UnitRef ref, UnitState state, boolean started) {
        return DbUnit.dbUnit(ref, state, TIMEOUT, started);
    }

    private static DiskUnit exited(UnitRef ref, int attempt) {
        return DiskUnit.diskUnit(ref, attempt, true, some(0), ContainerState.EXITED, none(), none(), none());
    }

    private static DiskUnit running(UnitRef ref, int attempt) {
        return DiskUnit.diskUnit(ref, attempt, true, Option.none(), ContainerState.RUNNING, none(), none(), none());
    }

    private static List<ReconcileAction> decide(List<DbUnit> db, List<DiskUnit> disk, List<StoreAttempt> stored) {
        return Reconciler.decide(InstanceId.DEFAULT, db, disk, stored);
    }

    @Test
    void lowerAttempt_isDiscarded_andTheCurrentOneThatNeverStartedIsRequeued() {
        assertThat(decide(List.of(db(A, UnitState.GRANTED, false)),
                          List.of(exited(A, 1)),
                          List.of(StoreAttempt.storeAttempt(A, 2, true))))
            .containsExactly(new ReconcileAction.Discard(A, 1), new ReconcileAction.Requeue(A));
    }

    @Test
    void lowerAttempt_isDiscarded_andAStartedCurrentAttemptWithNoDirectoryIsLost() {
        assertThat(decide(List.of(db(A, UnitState.RUNNING, true)),
                          List.of(running(A, 1)),
                          List.of(StoreAttempt.storeAttempt(A, 2, true))))
            .containsExactly(new ReconcileAction.Discard(A, 1), new ReconcileAction.MarkLost(A));
    }

    @Test
    void attemptGrantedElsewhere_isDiscarded_neverKilledAsAnOrphan() {
        assertThat(decide(List.of(),
                          List.of(running(A, 1)),
                          List.of(StoreAttempt.storeAttempt(A, 2, false))))
            .containsExactly(new ReconcileAction.Discard(A, 1));
    }

    @Test
    void sameAttempt_requeuedByTheStore_isDiscarded() {
        assertThat(decide(List.of(),
                          List.of(exited(A, 1)),
                          List.of(StoreAttempt.storeAttempt(A, 1, false))))
            .containsExactly(new ReconcileAction.Discard(A, 1));
    }

    @Test
    void sameAttempt_currentHere_keepsRulesOneToSix() {
        assertThat(decide(List.of(db(A, UnitState.RUNNING, true)),
                          List.of(exited(A, 3)),
                          List.of(StoreAttempt.storeAttempt(A, 3, true))))
            .containsExactly(new ReconcileAction.Collect(A));
    }

    /// A pre-M4 directory reads as attempt 1 at the link: current 1 keeps the rules, current 2 discards it.
    @Test
    void preM4Directory_isAttemptOne() {
        assertThat(decide(List.of(db(A, UnitState.RUNNING, true)),
                          List.of(running(A, 1)),
                          List.of(StoreAttempt.storeAttempt(A, 1, true))))
            .containsExactly(new ReconcileAction.Reattach(A, TIMEOUT));
        assertThat(decide(List.of(db(A, UnitState.RUNNING, true)),
                          List.of(running(A, 1)),
                          List.of(StoreAttempt.storeAttempt(A, 2, true))))
            .startsWith(new ReconcileAction.Discard(A, 1));
    }

    /// The unit ended in this very attempt here and its container still runs: an orphan of the current attempt, not a
    /// superseded one.
    @Test
    void terminalInTheSameAttemptHere_isKilledAsAnOrphan_notDiscarded() {
        assertThat(decide(List.of(),
                          List.of(running(A, 2)),
                          List.of(StoreAttempt.storeAttempt(A, 2, true))))
            .containsExactly(new ReconcileAction.KillOrphan(A));
    }

    /// An attempt above the store's (a grant the store lost, e.g. a restored database) is owned and timed by nothing
    /// in the store: discarded like a lower one, and the store's current attempt, not on this disk, decided as never ran.
    @Test
    void attemptAboveTheStores_isDiscarded_notKilledAsAnOrphan() {
        assertThat(decide(List.of(),
                          List.of(running(A, 3)),
                          List.of(StoreAttempt.storeAttempt(A, 2, false))))
            .containsExactly(new ReconcileAction.Discard(A, 3));
        assertThat(decide(List.of(db(A, UnitState.GRANTED, false)),
                          List.of(running(A, 3)),
                          List.of(StoreAttempt.storeAttempt(A, 2, true))))
            .containsExactly(new ReconcileAction.Discard(A, 3), new ReconcileAction.Requeue(A));
    }

    /// A unit the store does not know at all keeps the orphan rule.
    @Test
    void unknownUnit_isStillKilledAsAnOrphan() {
        assertThat(decide(List.of(), List.of(running(B, 4)), List.of()))
            .containsExactly(new ReconcileAction.KillOrphan(B));
    }

    @Test
    void discards_comeBeforeEveryOtherAction() {
        var actions = decide(List.of(db(B, UnitState.RUNNING, true), db(A, UnitState.GRANTED, false)),
                             List.of(exited(B, 5), exited(A, 1)),
                             List.of(StoreAttempt.storeAttempt(B, 5, true), StoreAttempt.storeAttempt(A, 2, true)));

        assertThat(actions).containsExactly(new ReconcileAction.Discard(A, 1),
                                            new ReconcileAction.Collect(B),
                                            new ReconcileAction.Requeue(A));
    }

    @Test
    void notOnDisk_isRuleSixsNeverRanDecision() {
        assertThat(Reconciler.notOnDisk(db(A, UnitState.GRANTED, false))).containsExactly(new ReconcileAction.Requeue(A));
        assertThat(Reconciler.notOnDisk(db(A, UnitState.CANCELLING, false))).containsExactly(new ReconcileAction.CompleteCancelled(A));
        assertThat(Reconciler.notOnDisk(db(A, UnitState.RUNNING, true))).containsExactly(new ReconcileAction.MarkLost(A));
    }

    @Test
    void storeAttempt_supersedes_everyOtherAttempt_andItsOwnOnceNotHere() {
        var current = StoreAttempt.storeAttempt(A, 2, true);
        var requeued = StoreAttempt.storeAttempt(A, 2, false);

        assertThat(current.supersedes(1)).isTrue();
        assertThat(current.supersedes(2)).isFalse();
        assertThat(current.supersedes(3)).isTrue();
        assertThat(requeued.supersedes(2)).isTrue();
    }
}
