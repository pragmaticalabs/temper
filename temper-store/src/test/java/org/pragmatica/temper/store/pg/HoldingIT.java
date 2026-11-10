package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.MarkerEpoch;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.ResultRecordDto;
import org.pragmatica.temper.protocol.api.ResultRecordDto.PortGroupDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.HoldingRow;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.UnitRow;
import org.pragmatica.temper.store.api.UnitStartedRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Holding-state persistence (m1-design §1.3, §1.5): a hold unit stays GRANTED with `port_group = 'waiting'` while
/// bb-hold waits, becomes `held` with its ticket on start, is reported in `active()` for the grant policy and
/// reconcile, and a requeue (holder dead before the grant) clears the attempt. Each test gets its own database.
@Tag("pg")
class HoldingIT {
    private static final int RACERS = 8;
    private static final Instant STARTED = Instant.parse("2026-10-13T10:01:00Z");
    private StoreHarness harness;
    private JobId job;
    private UnitRef ref;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        job = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", true)), false));
        ref = TestJobs.ref(job, "gate");
        harness.grantHead();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void holding_grantedUnit_waits_staysGranted_withMarkerAndHoldFacts() {
        hold();
        var unit = unit();

        assertThat(unit.state()).isEqualTo(UnitState.GRANTED);
        assertThat(unit.portGroup()).isEqualTo(some(PortGroup.WAITING));
        assertThat(unit.portGroupSince().isPresent()).isTrue();
        assertThat(unit.ticket()).isEqualTo(none());
        assertThat(unit.markerEpochNs()).isEqualTo(some(TestJobs.marker().nanos()));
        assertThat(facts("hold")).isEqualTo(1);

        var active = active();

        assertThat(active.state()).isEqualTo(UnitState.GRANTED);
        assertThat(active.needsPortGroup()).isTrue();
        assertThat(active.portGroup()).isEqualTo(some(PortGroup.WAITING));
    }

    @Test
    void holding_repeated_changesNothing() {
        hold();
        var since = unit().portGroupSince();

        hold();
        assertThat(unit().portGroupSince()).isEqualTo(since);
        assertThat(facts("hold")).isEqualTo(1);
    }

    @Test
    void started_afterHolding_withTicket_isHeldFromTheStart_andTheRecordShowsIt() {
        hold();
        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow(some("T42"))).await(PgFixture.WAIT).unwrap();
        var unit = unit();

        assertThat(unit.state()).isEqualTo(UnitState.RUNNING);
        assertThat(unit.portGroup()).isEqualTo(some(PortGroup.HELD));
        assertThat(unit.ticket()).isEqualTo(some("T42"));
        assertThat(unit.portGroupSince()).isEqualTo(some(STARTED));
        assertThat(active().portGroup()).isEqualTo(some(PortGroup.HELD));
        assertThat(active().ticket()).isEqualTo(some("T42"));

        complete();
        assertThat(record().units().getFirst().portGroup()).isEqualTo(some(PortGroupDto.portGroupDto("T42", STARTED)));
    }

    /// Protocol v2's `UnitStarted` carries no ticket: a held unit without a known ticket reports no `portGroup`.
    @Test
    void started_afterHolding_withoutTicket_isHeld_recordHasNoPortGroup() {
        hold();
        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow(none())).await(PgFixture.WAIT).unwrap();

        assertThat(unit().portGroup()).isEqualTo(some(PortGroup.HELD));
        assertThat(unit().ticket()).isEqualTo(none());
        complete();
        assertThat(record().units().getFirst().portGroup()).isEqualTo(none());
    }

    /// R-9's premise: the worker may re-send `UnitStarted`, so only the first start is recorded. A repeat with other
    /// values changes nothing the first one wrote.
    @Test
    void started_twice_keepsTheFirstStart() {
        hold();
        start(TestJobs.startedRow(some("T42")));
        start(laterStart());
        var unit = unit();

        assertThat(unit.state()).isEqualTo(UnitState.RUNNING);
        assertThat(unit.startedAt()).isEqualTo(some(STARTED));
        assertThat(unit.containerId()).isEqualTo(some("c0ffee"));
        assertThat(unit.imageId()).isEqualTo(some("sha256:abc"));
        assertThat(unit.markerEpochNs()).isEqualTo(some(1760349600000000000L));
        assertThat(unit.ticket()).isEqualTo(some("T42"));
        assertThat(unit.portGroup()).isEqualTo(some(PortGroup.HELD));
        assertThat(unit.portGroupSince()).isEqualTo(some(STARTED));
        assertThat(facts("start")).isEqualTo(1);
    }

    /// R-13's per-attempt timeout: an attempt that timed out is not cancelled, so its write can race its retries'
    /// writes. Eight starts of one unit race here; the row locks (`JobLock`, `UnitLock`) serialize them, so exactly one
    /// lands whole: the unit's start columns and its start facts come from the same write, with one `started` event.
    /// On a GRANTED unit the winner moves it to RUNNING, so the state guard alone rejects every loser; the
    /// `startedAt` guard decides only on a CANCELLING unit (the next test).
    @Test
    void started_racingDuplicates_exactlyOneLandsWhole() {
        startConcurrently();
        var unit = unit();

        assertThat(unit.state()).isEqualTo(UnitState.RUNNING);
        assertOneStartLandedWhole();
        assertThat(harness.count("SELECT count(*) AS n FROM events WHERE job_id = $1 AND kind = 'started'", job.value()))
            .isEqualTo(1);
    }

    /// The race where the `startedAt` guard alone decides: cancelled before its start, the unit is CANCELLING, and a
    /// start keeps it CANCELLING, so every loser still passes the state guard. Without `startedAt` each loser
    /// would overwrite the start columns while the write-once start facts keep the first write's.
    @Test
    void started_racingDuplicates_onACancellingUnit_exactlyOneLandsWhole() {
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        startConcurrently();

        assertThat(unit().state()).isEqualTo(UnitState.CANCELLING);
        assertOneStartLandedWhole();
    }

    /// R-9's other half: a started unit that is then cancelled is CANCELLING with `startedAt` set, and reconcile's
    /// Collect makes the worker re-send `UnitStarted`. The re-sent start changes nothing the first one wrote.
    @Test
    void started_afterStartAndCancel_keepsTheFirstStart() {
        hold();
        start(TestJobs.startedRow(some("T42")));
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        start(laterStart());
        var unit = unit();

        assertThat(unit.state()).isEqualTo(UnitState.CANCELLING);
        assertThat(unit.startedAt()).isEqualTo(some(STARTED));
        assertThat(unit.containerId()).isEqualTo(some("c0ffee"));
        assertThat(unit.ticket()).isEqualTo(some("T42"));
        assertThat(unit.portGroupSince()).isEqualTo(some(STARTED));
        assertThat(facts("start")).isEqualTo(1);
    }

    /// A late `UnitStarted` (re-sent after the unit finished) never touches a terminal unit, started or not.
    @Test
    void started_afterCompletion_leavesTheTerminalUnitUntouched() {
        hold();
        start(TestJobs.startedRow(some("T42")));
        complete();
        var before = unit();

        start(laterStart());
        assertThat(unit()).isEqualTo(before);
        assertThat(facts("start")).isEqualTo(1);
    }

    @Test
    void started_afterCompletionWithoutAStart_leavesTheTerminalUnitUntouched() {
        hold();
        complete();
        var before = unit();

        start(laterStart());
        assertThat(unit()).isEqualTo(before);
        assertThat(unit().startedAt()).isEqualTo(none());
        assertThat(facts("start")).isZero();
    }

    /// Ruling R-13: a start from a host the unit is not on (requeued and granted elsewhere meanwhile) is not recorded.
    @Test
    void started_fromAnotherHost_isNotRecorded() {
        hold();
        var before = unit();

        harness.store.units()
                     .started(HostName.hostName("elsewhere").unwrap(), ref, 1, TestJobs.startedRow(some("T42")))
                     .await(PgFixture.WAIT)
                     .unwrap();
        assertThat(unit()).isEqualTo(before);
        assertThat(unit().state()).isEqualTo(UnitState.GRANTED);
        assertThat(unit().startedAt()).isEqualTo(none());
        assertThat(facts("start")).isZero();
    }

    @Test
    void started_withoutHold_hasNoPortGroup() {
        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow(none())).await(PgFixture.WAIT).unwrap();

        assertThat(unit().portGroup()).isEqualTo(none());
        assertThat(unit().portGroupSince()).isEqualTo(none());
    }

    @Test
    void holding_afterStart_isANoOp() {
        harness.store.units().started(TestJobs.HOST, ref, 1, TestJobs.startedRow(none())).await(PgFixture.WAIT).unwrap();
        hold();

        assertThat(unit().portGroup()).isEqualTo(none());
        assertThat(facts("hold")).isZero();
    }

    @Test
    void holding_cancellingUnitNotStarted_waits() {
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        hold();

        assertThat(unit().state()).isEqualTo(UnitState.CANCELLING);
        assertThat(unit().portGroup()).isEqualTo(some(PortGroup.WAITING));
    }

    @Test
    void holding_unknownUnit_fails() {
        harness.store.units()
                     .holding(TestJobs.HOST, TestJobs.ref(job, "nope"), 1, HoldingRow.holdingRow(TestJobs.marker(), TestJobs.facts()))
                     .await(PgFixture.WAIT)
                     .onSuccess(_ -> fail("expected UnitNotFound"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.UnitNotFound.class));
    }

    /// Reconcile rule 4: the holder died before its grant → requeue; the next attempt holds from scratch.
    @Test
    void requeue_ofAWaitingUnit_clearsTheAttempt_andTheNextHoldStartsOver() {
        hold();
        harness.store.units().requeue(TestJobs.HOST, ref, 1).await(PgFixture.WAIT).unwrap();
        var requeued = unit();

        assertThat(requeued.state()).isEqualTo(UnitState.QUEUED);
        assertThat(requeued.portGroup()).isEqualTo(none());
        assertThat(requeued.portGroupSince()).isEqualTo(none());
        assertThat(requeued.markerEpochNs()).isEqualTo(none());
        assertThat(requeued.host()).isEqualTo(none());

        harness.grantHead();
        hold();
        assertThat(unit().portGroup()).isEqualTo(some(PortGroup.WAITING));
        assertThat(facts("hold")).as("one per attempt (m4-design §6)").isEqualTo(2);
    }

    private void hold() {
        harness.store.units().holding(TestJobs.HOST, ref, harness.attempt(ref), HoldingRow.holdingRow(TestJobs.marker(), TestJobs.facts())).await(PgFixture.WAIT).unwrap();
    }

    private void start(UnitStartedRow row) {
        harness.store.units().started(TestJobs.HOST, ref, harness.attempt(ref), row).await(PgFixture.WAIT).unwrap();
    }

    private static UnitStartedRow laterStart() {
        return UnitStartedRow.unitStartedRow("deadbeef", "sha256:def", MarkerEpoch.markerEpoch("1760349700000000000").unwrap(),
                                             STARTED.plusSeconds(60), TestJobs.facts(), some("T99"));
    }

    /// Eight distinct starts of the unit, all in flight at once; start `i` has container `c<i>` and its facts
    /// `foreignReactors = i`.
    private void startConcurrently() {
        var racing = IntStream.range(0, RACERS)
                              .mapToObj(i -> harness.store.units().started(TestJobs.HOST, ref, 1, racer(i)))
                              .toList();

        racing.forEach(write -> write.await(PgFixture.WAIT).unwrap());
    }

    private static UnitStartedRow racer(int i) {
        return UnitStartedRow.unitStartedRow("c" + i, "sha256:" + i,
                                             MarkerEpoch.markerEpoch(String.valueOf(1760349600000000000L + i)).unwrap(),
                                             STARTED.plusSeconds(i), racerFacts(i), none());
    }

    private static HostFacts racerFacts(int i) {
        return HostFacts.hostFacts(1.5, 1.2, 32, i, 1, none(), none(), 1_000_000L, 100L << 30, 200L << 30,
                                   Instant.parse("2026-10-13T10:00:00.123456Z"), List.of());
    }

    /// One start fact, and the unit's start columns written by the same racer as that fact.
    private void assertOneStartLandedWhole() {
        assertThat(facts("start")).isEqualTo(1);
        assertThat(harness.count("SELECT count(*) AS n FROM units u JOIN unit_facts f ON f.unit_id = u.id AND f.phase = 'start' "
                                 + "WHERE u.job_id = $1 AND u.container_id = 'c' || (f.facts ->> 'foreignReactors')",
                                 job.value()))
            .as("the unit's start and its start facts come from one write")
            .isEqualTo(1);
    }

    private void complete() {
        harness.store.units()
                     .complete(TestJobs.HOST, ref, 1, TestJobs.exited(0), List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)), none(), none(),
                               TestJobs::evaluate)
                     .await(PgFixture.WAIT)
                     .unwrap();
    }

    private UnitRow unit() {
        return harness.store.jobs().job(job).await(PgFixture.WAIT).unwrap().unwrap().units().getFirst();
    }

    private ActiveUnit active() {
        return harness.store.units().active(TestJobs.HOST).await(PgFixture.WAIT).unwrap().getFirst();
    }

    private long facts(String phase) {
        return harness.count("SELECT count(*) AS n FROM unit_facts f JOIN units u ON u.id = f.unit_id WHERE u.job_id = $1 AND f.phase = $2",
                             job.value(),
                             phase);
    }

    private ResultRecordDto record() {
        return harness.store.results().result(job).await(PgFixture.WAIT).unwrap().map(WireRecords::resultRecordDto).unwrap();
    }
}
