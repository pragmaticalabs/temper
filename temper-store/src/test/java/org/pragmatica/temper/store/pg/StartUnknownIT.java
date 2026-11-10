package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.CancelOutcome;
import org.pragmatica.temper.store.api.HoldingRow;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.UnitRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Ruling R-13, amended: a unit whose start could not be recorded runs, so it and its job show UNKNOWN, never QUEUED,
/// until a start or its exit facts arrive. UNKNOWN is active: it holds its slot, it is cancelled like a running unit,
/// and its exit completes it normally, without start facts. Each test gets its own database.
@Tag("pg")
class StartUnknownIT {
    private StoreHarness harness;
    private JobId job;
    private UnitRef ref;
    private int attempt;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        ref = TestJobs.main(job);
        attempt = harness.grantHead().getFirst().attempt();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void startUnknown_grantedUnit_unitAndJobAreUnknown_withTheEvent() {
        markUnknown();

        assertThat(job().state()).isEqualTo(JobState.UNKNOWN);
        assertThat(unit().state()).isEqualTo(UnitState.UNKNOWN);
        assertThat(unit().startedAt()).isEqualTo(none());
        assertThat(events("start-unknown")).isEqualTo(1);
    }

    /// The grant policy's input: an UNKNOWN unit is active on its host with its memory, and it is never granted again.
    @Test
    void startUnknown_unitHoldsItsSlot_andIsNotGrantedAgain() {
        markUnknown();
        var other = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        var granted = harness.grantHead();
        var active = harness.store.units().active(TestJobs.HOST).await(PgFixture.WAIT).unwrap();

        assertThat(granted).hasSize(1);
        assertThat(granted.getFirst().ref()).isEqualTo(TestJobs.main(other));
        assertThat(active).extracting(unit -> unit.ref()).containsExactly(ref, TestJobs.main(other));
        assertThat(active.getFirst().state()).isEqualTo(UnitState.UNKNOWN);
        assertThat(active.getFirst().memoryMb()).isPositive();
    }

    @Test
    void startUnknown_thenExit_completesNormally_withoutStartFacts() {
        markUnknown();

        harness.complete(job, TestJobs.exited(0), List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));

        assertThat(job().state()).isEqualTo(JobState.PASSED);
        assertThat(unit().state()).isEqualTo(UnitState.PASSED);
        assertThat(unit().startedAt()).isEqualTo(none());
        assertThat(harness.store.results().result(job).await(PgFixture.WAIT).unwrap().isPresent()).isTrue();
    }

    /// A late start (a timed-out attempt's write, or R-9's re-sent start) is still recorded: UNKNOWN → RUNNING.
    @Test
    void startUnknown_thenStart_recordsTheStart_running() {
        markUnknown();

        harness.start(job);

        assertThat(job().state()).isEqualTo(JobState.RUNNING);
        assertThat(unit().state()).isEqualTo(UnitState.RUNNING);
        assertThat(unit().startedAt().isPresent()).isTrue();
        assertThat(events("started")).isEqualTo(1);
    }

    @Test
    void startUnknown_thenCancel_killsTheUnit_thenItsExitCancels() {
        markUnknown();

        var outcome = harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();

        assertThat(outcome).isEqualTo(new CancelOutcome.Cancelling(job, List.of(ref)));
        assertThat(unit().state()).isEqualTo(UnitState.CANCELLING);
        harness.complete(job, TestJobs.exited(137), List.of());
        assertThat(job().state()).isEqualTo(JobState.CANCELLED);
    }

    /// A newer submit of the same ref supersedes an UNKNOWN job like a RUNNING one: its unit is killed.
    @Test
    void startUnknown_byRefJob_isSupersededByANewerSha() {
        var byRef = harness.submit(TestJobs.byRef("main", TestJobs.sha(), false, 5));
        var running = TestJobs.main(byRef);

        var current = harness.grantHead().getFirst().attempt();
        harness.store.units().startUnknown(TestJobs.HOST, running, current).await(PgFixture.WAIT).unwrap();
        harness.submit(TestJobs.byRef("main", TestJobs.sha(), false, 5));

        var superseded = harness.store.jobs().job(byRef).await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(superseded.superseded()).isTrue();
        assertThat(superseded.state()).isEqualTo(JobState.CANCELLING);
    }

    @Test
    void startUnknown_repeated_marksOnce() {
        markUnknown();
        markUnknown();

        assertThat(events("start-unknown")).isEqualTo(1);
    }

    @Test
    void startUnknown_afterTheStart_changesNothing() {
        harness.start(job);
        markUnknown();

        assertThat(unit().state()).isEqualTo(UnitState.RUNNING);
        assertThat(job().state()).isEqualTo(JobState.RUNNING);
        assertThat(events("start-unknown")).isZero();
    }

    @Test
    void startUnknown_afterTheExit_changesNothing() {
        harness.complete(job, TestJobs.exited(0), List.of());
        var before = job();

        markUnknown();
        assertThat(job()).isEqualTo(before);
        assertThat(events("start-unknown")).isZero();
    }

    @Test
    void startUnknown_fromAnotherHost_changesNothing() {
        harness.store.units().startUnknown(HostName.hostName("elsewhere").unwrap(), ref, attempt).await(PgFixture.WAIT).unwrap();

        assertThat(unit().state()).isEqualTo(UnitState.GRANTED);
        assertThat(job().state()).isEqualTo(JobState.QUEUED);
    }

    @Test
    void startUnknown_cancellingUnit_staysCancelling() {
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        markUnknown();

        assertThat(unit().state()).isEqualTo(UnitState.CANCELLING);
        assertThat(job().state()).isEqualTo(JobState.CANCELLING);
    }

    /// A hold unit starts only after its grant, so an UNKNOWN one holds the group; when it took it is not known.
    @Test
    void startUnknown_waitingHoldUnit_holdsTheGroup_sinceUnknown() {
        var hold = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", true)), false));
        var gate = TestJobs.ref(hold, "gate");

        var current = harness.grantHead().getFirst().attempt();
        harness.store.units().holding(TestJobs.HOST, gate, current, HoldingRow.holdingRow(TestJobs.marker(), TestJobs.facts())).await(PgFixture.WAIT).unwrap();
        harness.store.units().startUnknown(TestJobs.HOST, gate, current).await(PgFixture.WAIT).unwrap();

        var unit = harness.store.jobs().job(hold).await(PgFixture.WAIT).unwrap().unwrap().units().getFirst();

        assertThat(unit.state()).isEqualTo(UnitState.UNKNOWN);
        assertThat(unit.portGroup()).isEqualTo(some(PortGroup.HELD));
        assertThat(unit.portGroupSince()).isEqualTo(none());
    }

    /// Verification F4: the mark of a dropped start is scoped to its attempt. The unit is requeued (nothing on the
    /// worker's disk) and granted again to the same host, where its new attempt waits in bb-hold; the first attempt's
    /// late mark then changes nothing.
    @Test
    void startUnknown_lateMarkAfterRequeueAndRegrantOnTheSameHost_changesNothing() {
        var hold = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", true)), false));
        var gate = TestJobs.ref(hold, "gate");
        var first = harness.grantHead().getFirst().attempt();
        harness.store.units().requeue(TestJobs.HOST, gate, 1).await(PgFixture.WAIT).unwrap();
        var second = harness.grantHead();
        harness.store.units().holding(TestJobs.HOST, gate, second.getFirst().attempt(), HoldingRow.holdingRow(TestJobs.marker(), TestJobs.facts()))
               .await(PgFixture.WAIT).unwrap();

        harness.store.units().startUnknown(TestJobs.HOST, gate, first).await(PgFixture.WAIT).unwrap();

        var row = harness.store.jobs().job(hold).await(PgFixture.WAIT).unwrap().unwrap();
        assertThat(second).as("the second attempt, same host").hasSize(1);
        assertThat(second.getFirst().attempt()).isEqualTo(first + 1);
        assertThat(row.units().getFirst().state()).isEqualTo(UnitState.GRANTED);
        assertThat(row.units().getFirst().portGroup()).isEqualTo(some(PortGroup.WAITING));
        assertThat(row.state()).isEqualTo(JobState.QUEUED);
        assertThat(harness.count("SELECT count(*) AS n FROM events WHERE job_id = $1 AND kind = 'start-unknown'", hold.value())).isZero();
    }

    /// The control: the same mark for the current attempt lands.
    @Test
    void startUnknown_currentAttempt_afterRequeueAndRegrant_marks() {
        var direct = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var unit = TestJobs.main(direct);
        harness.grantHead();
        harness.store.units().requeue(TestJobs.HOST, unit, 1).await(PgFixture.WAIT).unwrap();
        var current = harness.grantHead().getFirst().attempt();

        harness.store.units().startUnknown(TestJobs.HOST, unit, current).await(PgFixture.WAIT).unwrap();

        assertThat(harness.store.jobs().job(direct).await(PgFixture.WAIT).unwrap().unwrap().state()).isEqualTo(JobState.UNKNOWN);
    }

    @Test
    void startUnknown_unknownUnit_fails() {
        harness.store.units()
                     .startUnknown(TestJobs.HOST, TestJobs.ref(job, "nope"), attempt)
                     .await(PgFixture.WAIT)
                     .onSuccess(_ -> fail("expected UnitNotFound"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.UnitNotFound.class));
    }

    private void markUnknown() {
        harness.store.units().startUnknown(TestJobs.HOST, ref, attempt).await(PgFixture.WAIT).unwrap();
    }

    private JobRow job() {
        return harness.store.jobs().job(job).await(PgFixture.WAIT).unwrap().unwrap();
    }

    private UnitRow unit() {
        return job().units().getFirst();
    }

    private long events(String kind) {
        return harness.count("SELECT count(*) AS n FROM events WHERE job_id = $1 AND kind = $2", job.value(), kind);
    }
}
