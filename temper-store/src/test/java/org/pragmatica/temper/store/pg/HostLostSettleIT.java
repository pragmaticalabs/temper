package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.util.List;

import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.LostSettle;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.LostSettled;
import org.pragmatica.temper.store.api.Starvation;
import org.pragmatica.temper.store.api.UnitRow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// m4-design §2.3 (R-M4-2, R-M4-5): a LOST host's active units are settled by rules L1–L4, each lost attempt is a
/// `unit_attempts` row and a `unit-lost` event, the job state rules hold, and a repeat settle changes nothing. The
/// units are created through the real store calls; only the states no store call reaches from a test (a pre-M4
/// `external` row, §5.2) are set by SQL. Each test gets its own database.
@Tag("pg")
class HostLostSettleIT {
    private static final HostName LOST = TestJobs.HOST;
    private static final HostName OTHER = HostName.hostName("cloudbb-3").unwrap();
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private StoreHarness harness;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        harness.store.hosts()
                     .upsert(HostConfig.hostConfig(OTHER,
                                                   "localhost",
                                                   none(),
                                                   3,
                                                   18432,
                                                   false).unwrap())
                     .await(PgFixture.WAIT)
                     .unwrap();
        harness.lost(LOST, NOW);
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /// L4 on a GRANTED unit: QUEUED again, the attempt's columns cleared, its number kept, one requeue counted; the
    /// QUEUED job stays QUEUED.
    @Test
    void settleLost_requeuesAGrantedUnit_andRecordsTheLostAttempt() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        assertThat(settle()).containsExactly(LostSettled.lostSettled(TestJobs.main(job),
                                                                     1,
                                                                     LostSettle.Outcome.REQUEUED,
                                                                     none()));
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND state = 'QUEUED' AND host IS NULL AND granted_at IS NULL "
                                + "AND lease_until IS NULL AND granted_judge IS NULL AND shared_cpu IS NULL AND attempt = 1 "
                                + "AND host_lost_requeues = 1",
                                 job.value())).isEqualTo(1);
        assertThat(lostAttempts(job)).isEqualTo(1);
        assertThat(harness.count("SELECT count(*) AS n FROM unit_attempts a JOIN units u ON u.id = a.unit_id WHERE u.job_id = $1 "
                                + "AND a.attempt = 1 AND a.host = $2 AND a.started_at IS NULL AND a.ended_at = $3 AND NOT a.shared_cpu "
                                + "AND a.outcome = 'host-lost'",
                                 job.value(),
                                 LOST.value(),
                                 Sql.utc(NOW))).isEqualTo(1);
        assertThat(unitLostEvents(job)).isEqualTo(1);
        assertThat(job(job).state()).isEqualTo(JobState.QUEUED);
    }

    /// L4 on a RUNNING unit: the start is cleared from the unit and kept in the lost attempt; the RUNNING job stays
    /// RUNNING (R-M4-16, Q-M4-5); the next grant is attempt 2.
    @Test
    void settleLost_requeuesARunningUnit_andTheJobStaysRunning() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        harness.start(job);
        assertThat(settle()).extracting(LostSettled::outcome).containsExactly(LostSettle.Outcome.REQUEUED);
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND state = 'QUEUED' AND started_at IS NULL "
                                + "AND container_id IS NULL AND image_id IS NULL AND marker_epoch_ns IS NULL",
                                 job.value())).isEqualTo(1);
        assertThat(harness.count("SELECT count(*) AS n FROM unit_attempts a JOIN units u ON u.id = a.unit_id "
                                + "WHERE u.job_id = $1 AND a.started_at IS NOT NULL",
                                 job.value())).isEqualTo(1);
        assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
        assertThat(harness.grantHead().getFirst().attempt()).isEqualTo(2);
    }

    /// L4 on an UNKNOWN unit: nothing of the job was recorded as running, so the UNKNOWN job is QUEUED again.
    @Test
    void settleLost_requeuesAnUnknownUnit_andTheUnknownJobIsQueuedAgain() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var granted = harness.grantHead().getFirst();

        harness.store.units().startUnknown(LOST, granted.ref(), granted.attempt()).await(PgFixture.WAIT).unwrap();
        assertThat(job(job).state()).isEqualTo(JobState.UNKNOWN);
        assertThat(settle()).extracting(LostSettled::outcome).containsExactly(LostSettle.Outcome.REQUEUED);
        assertThat(unit(job).state()).isEqualTo(UnitState.QUEUED);
        assertThat(job(job).state()).isEqualTo(JobState.QUEUED);
    }

    /// An UNKNOWN job with another UNKNOWN unit left stays UNKNOWN [G: m4-design §2.3 "whose only unit"].
    @Test
    void settleLost_keepsTheJobUnknown_whileAnotherUnitIsUnknown() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.unit("a"), TestJobs.unit("b")),
                                                    false));
        var first = harness.grantHead().getFirst();
        var second = grant(OTHER).getFirst();

        harness.store.units().startUnknown(LOST, first.ref(), first.attempt()).await(PgFixture.WAIT).unwrap();
        harness.store.units().startUnknown(OTHER, second.ref(), second.attempt()).await(PgFixture.WAIT).unwrap();
        settle();
        assertThat(unit(job, "a").state()).isEqualTo(UnitState.QUEUED);
        assertThat(job(job).state()).isEqualTo(JobState.UNKNOWN);
    }

    /// L4 with no UNKNOWN unit left but a sibling active (GRANTED elsewhere): something of the job is under way, so the
    /// UNKNOWN job is RUNNING, not QUEUED.
    @Test
    void settleLost_requeuesAnUnknownUnit_andTheJobRuns_whileASiblingIsActive() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.unit("a"), TestJobs.unit("b")),
                                                    false));
        var first = harness.grantHead().getFirst();

        grant(OTHER);
        harness.store.units().startUnknown(LOST, first.ref(), first.attempt()).await(PgFixture.WAIT).unwrap();
        assertThat(job(job).state()).isEqualTo(JobState.UNKNOWN);
        assertThat(settle()).extracting(LostSettled::outcome).containsExactly(LostSettle.Outcome.REQUEUED);
        assertThat(unit(job, "a").state()).isEqualTo(UnitState.QUEUED);
        assertThat(unit(job, "b").state()).isEqualTo(UnitState.GRANTED);
        assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
    }

    /// L2 on an UNKNOWN unit whose sibling waits QUEUED: the job is not finished, a unit of it has ended, so the UNKNOWN
    /// job is RUNNING.
    @Test
    void settleLost_endsAnUnknownExternalUnit_andTheJobRuns_whileASiblingIsQueued() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.unit("a"), TestJobs.unit("b")),
                                                    false));

        external(job, "a");
        var first = harness.grantHead().getFirst();

        harness.store.units().startUnknown(LOST, first.ref(), first.attempt()).await(PgFixture.WAIT).unwrap();
        assertThat(job(job).state()).isEqualTo(JobState.UNKNOWN);
        assertThat(settle()).extracting(LostSettled::outcome).containsExactly(LostSettle.Outcome.EXTERNAL);
        assertThat(unit(job, "a").state()).isEqualTo(UnitState.ERROR);
        assertThat(unit(job, "b").state()).isEqualTo(UnitState.QUEUED);
        assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
    }

    /// m4-design §4.6: a late report of the lost attempt, after L4, is NotActive and stores nothing.
    @Test
    void complete_afterRequeue_isNotActive_andStoresNothing() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        harness.start(job);
        settle();
        assertThat(lateComplete(job)).isInstanceOf(CompleteOutcome.NotActive.class);
        assertThat(unit(job).state()).isEqualTo(UnitState.QUEUED);
        assertNothingStored(job);
    }

    /// m4-design §4.6: a late report of the lost attempt, after the unit was granted again to the same (recovered)
    /// host as attempt 2, is Superseded and stores nothing.
    @Test
    void complete_afterRegrantToTheSameHost_isSuperseded_andStoresNothing() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        harness.start(job);
        settle();
        harness.recovered(LOST);
        assertThat(harness.grantHead().getFirst().attempt()).isEqualTo(2);
        assertThat(lateComplete(job)).isEqualTo(new CompleteOutcome.Superseded(TestJobs.main(job),
                                                                               UnitState.GRANTED,
                                                                               1,
                                                                               2));
        assertThat(unit(job).state()).isEqualTo(UnitState.GRANTED);
        assertNothingStored(job);
    }

    /// A stale tick: the unit was listed as attempt 1, then requeued, the host recovered and granted it again as
    /// attempt 2 before the tick's transaction ran. Attempt 2 is untouched.
    @Test
    void settle_staleTick_leavesARegrantedAttemptOnTheRecoveredHost() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        var stale = listed();

        settle();
        harness.recovered(LOST);
        harness.grantHead();
        assertThat(settleListed(stale)).isEmpty();
        assertUntouched(job, 2, 1);
    }

    /// The attempt guard alone: the host is still LOST, but the unit's attempt is no longer the listed one (set by SQL:
    /// no store call regrants on a LOST host). Nothing is settled.
    @Test
    void settle_staleTick_leavesAnotherAttemptOnALostHost() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        var stale = listed();

        harness.execute("UPDATE units SET attempt = 2 WHERE job_id = $1", job.value());
        assertThat(settleListed(stale)).isEmpty();
        assertUntouched(job, 2, 0);
    }

    /// The attempt guard alone on an ending rule (L2): ending is keyed on the lock, so without the guard the stale tick
    /// would end attempt 2 `ERROR(host-lost)`. Nothing is settled.
    @Test
    void settle_staleTick_leavesAnotherAttemptOfAnExternalUnit() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        external(job, "main");
        harness.grantHead();
        var stale = listed();

        harness.execute("UPDATE units SET attempt = 2 WHERE job_id = $1", job.value());
        assertThat(settleListed(stale)).isEmpty();
        assertUntouched(job, 2, 0);
    }

    /// The host guard alone: the listed attempt is still on the host, but the host recovered before the transaction;
    /// its recovery, not the stale tick, deals with the unit.
    @Test
    void settle_staleTick_leavesTheUnitOfARecoveredHost() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        var stale = listed();

        harness.recovered(LOST);
        assertThat(settleListed(stale)).isEmpty();
        assertUntouched(job, 1, 0);
    }

    /// L1: a CANCELLING unit is CANCELLED, never requeued; the job finishes CANCELLED. No lost attempt is recorded: the
    /// cancel decided the unit's end [G].
    @Test
    void settleLost_cancelsACancellingUnit_andTheJobFinishesCancelled() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        harness.start(job);
        harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();
        assertThat(settle()).containsExactly(LostSettled.lostSettled(TestJobs.main(job),
                                                                     1,
                                                                     LostSettle.Outcome.CANCELLED,
                                                                     some(JobState.CANCELLED)));
        assertThat(unit(job).state()).isEqualTo(UnitState.CANCELLED);
        assertThat(unit(job).killedBy()).isEqualTo(some(KillCause.CANCEL));
        assertThat(job(job).state()).isEqualTo(JobState.CANCELLED);
        assertThat(lostAttempts(job)).isZero();
        assertThat(unitLostEvents(job)).isZero();
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND host_lost_requeues = 0",
                                 job.value())).isEqualTo(1);
    }

    /// m4-design §5.2: two units on one host, one `none`, one `external`: the first is QUEUED with one requeue, the
    /// second is `ERROR(host-lost)` with no new attempt; the job waits for the requeued unit.
    @Test
    void settleLost_requeuesTheNoneUnit_andEndsTheExternalUnit() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.unit("plain"), TestJobs.unit("pushes")),
                                                    false));

        external(job, "pushes");
        harness.grantHead();
        harness.grantHead();
        startAll(job, "plain", "pushes");
        assertThat(settle()).containsExactly(LostSettled.lostSettled(TestJobs.ref(job, "plain"),
                                                                     1,
                                                                     LostSettle.Outcome.REQUEUED,
                                                                     none()),
                                             LostSettled.lostSettled(TestJobs.ref(job, "pushes"),
                                                                     1,
                                                                     LostSettle.Outcome.EXTERNAL,
                                                                     none()));
        assertThat(unit(job, "plain").state()).isEqualTo(UnitState.QUEUED);
        assertThat(unit(job, "pushes").state()).isEqualTo(UnitState.ERROR);
        assertThat(unit(job, "pushes").reason()).isEqualTo(some(LostSettle.HOST_LOST));
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND name = 'plain' AND host_lost_requeues = 1",
                                 job.value())).isEqualTo(1);
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND name = 'pushes' AND host_lost_requeues = 0 "
                                + "AND attempt = 1",
                                 job.value())).isEqualTo(1);
        assertThat(lostAttempts(job)).isEqualTo(2);
        assertThat(unitLostEvents(job)).isEqualTo(2);
        assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
    }

    /// L2 on a single-unit job: the job finishes `ERROR(host-lost)` through the job decision.
    @Test
    void settleLost_endsAnExternalUnit_andTheJobFinishesErrorHostLost() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        external(job, "main");
        harness.grantHead();
        harness.start(job);
        assertThat(settle()).containsExactly(LostSettled.lostSettled(TestJobs.main(job),
                                                                     1,
                                                                     LostSettle.Outcome.EXTERNAL,
                                                                     some(JobState.ERROR)));
        assertThat(job(job).state()).isEqualTo(JobState.ERROR);
        assertThat(job(job).reason()).isEqualTo(some(LostSettle.HOST_LOST));
        assertThat(harness.count("SELECT count(*) AS n FROM events WHERE job_id = $1 AND kind IN ('unit-lost','unit-finished','finished')",
                                 job.value())).isEqualTo(3);
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND ended_at = $2",
                                 job.value(),
                                 Sql.utc(NOW))).isEqualTo(1);
    }

    /// R-M4-16 (Q-M4-4): a sibling of a unit that hit L2 keeps running to its own end; the job is not finished.
    @Test
    void settleLost_leavesASiblingOnAnotherHostRunning() {
        var job = harness.submit(TestJobs.withUnits(List.of(TestJobs.unit("a"), TestJobs.unit("b")),
                                                    false));

        external(job, "a");
        var lost = harness.grantHead().getFirst();
        var elsewhere = grant(OTHER).getFirst();

        harness.store.units()
                     .started(LOST,
                              lost.ref(),
                              lost.attempt(),
                              TestJobs.startedRow())
                     .await(PgFixture.WAIT)
                     .unwrap();
        harness.store.units()
                     .started(OTHER,
                              elsewhere.ref(),
                              elsewhere.attempt(),
                              TestJobs.startedRow())
                     .await(PgFixture.WAIT)
                     .unwrap();
        assertThat(settle()).extracting(LostSettled::ref).containsExactly(lost.ref());
        assertThat(unit(job,
                        elsewhere.ref().unit().value()).state()).isEqualTo(UnitState.RUNNING);
        assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
    }

    /// Level, not edge: a repeat settles nothing and writes nothing; QUEUED and terminal units, and units on other
    /// hosts, are never touched.
    @Test
    void settleLost_repeat_changesNothing() {
        var requeued = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        var ended = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        external(ended, "main");
        harness.grantHead();
        var elsewhere = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        grant(OTHER);
        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        assertThat(settle()).hasSize(2);
        var attempts = harness.count("SELECT count(*) AS n FROM unit_attempts");
        var events = harness.count("SELECT count(*) AS n FROM events");

        assertThat(settle()).isEmpty();
        assertThat(harness.count("SELECT count(*) AS n FROM unit_attempts")).isEqualTo(attempts);
        assertThat(harness.count("SELECT count(*) AS n FROM events")).isEqualTo(events);
        assertThat(unit(requeued).state()).isEqualTo(UnitState.QUEUED);
        assertThat(unit(ended).state()).isEqualTo(UnitState.ERROR);
        assertThat(unit(elsewhere).state()).isEqualTo(UnitState.GRANTED);
    }

    /// The planner's declaration reaches the row: a submitted `none` unit is written `none` (V11 has no default).
    @Test
    void submit_writesThePlannersSideEffects() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND side_effects = 'none'",
                                 job.value())).isEqualTo(1);
    }

    private List<LostSettled> settle() {
        return harness.store.units()
                            .settleLost(LOST, NOW, TestJobs::evaluate)
                            .await(PgFixture.WAIT)
                            .unwrap();
    }

    private List<ActiveUnit> listed() {
        return harness.store.units()
                            .active(LOST)
                            .await(PgFixture.WAIT)
                            .unwrap();
    }

    private List<LostSettled> settleListed(List<ActiveUnit> units) {
        return LostSettlement.settleAll(harness.raw,
                                        LostSettlement.Loss.loss(LOST, NOW, TestJobs::evaluate, Judge.UNJUDGED),
                                        units)
                             .await(PgFixture.WAIT)
                             .unwrap();
    }

    private void assertUntouched(JobId job, int attempt, long lostAttempts) {
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND state = 'GRANTED' AND host = $2 AND attempt = $3",
                                 job.value(),
                                 LOST.value(),
                                 attempt)).isEqualTo(1);
        assertThat(lostAttempts(job)).isEqualTo(lostAttempts);
        assertThat(unitLostEvents(job)).isEqualTo(lostAttempts);
    }

    private CompleteOutcome lateComplete(JobId job) {
        return harness.store.units()
                            .complete(LOST,
                                      TestJobs.main(job),
                                      1,
                                      TestJobs.exited(0),
                                      TestJobs.passing(),
                                      none(),
                                      none(),
                                      TestJobs::evaluate)
                            .await(PgFixture.WAIT)
                            .unwrap();
    }

    private void assertNothingStored(JobId job) {
        assertThat(harness.count("SELECT count(*) AS n FROM testcases WHERE job_id = $1", job.value())).isZero();
        assertThat(harness.count("SELECT count(*) AS n FROM results WHERE job_id = $1", job.value())).isZero();
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND (ended_at IS NOT NULL OR rc IS NOT NULL)",
                                 job.value())).isZero();
        assertThat(job(job).state()).isEqualTo(JobState.RUNNING);
    }

    private List<Granted> grant(HostName host) {
        return harness.store.units()
                            .grant(host,
                                   queued -> GrantDecision.grantDecision(option(queued.isEmpty()
                                                                                ? null
                                                                                : queued.getFirst()
                                                                                        .unitId()),
                                                                         Starvation.NONE),
                                   StoreHarness.LEASE,
                                   1,
                                   none())
                            .await(PgFixture.WAIT)
                            .unwrap();
    }

    /// A pre-M4 row's shape after the V11 backfill (m4-design §5.2): no submittable recipe declares `external`.
    private void external(JobId job, String unit) {
        harness.execute("UPDATE units SET side_effects = 'external' WHERE job_id = $1 AND name = $2", job.value(), unit);
    }

    private void startAll(JobId job, String... units) {
        for (var unit : units) {
            UnitRef ref = TestJobs.ref(job, unit);

            harness.store.units()
                         .started(LOST,
                                  ref,
                                  harness.attempt(ref),
                                  TestJobs.startedRow())
                         .await(PgFixture.WAIT)
                         .unwrap();
        }
    }

    private long lostAttempts(JobId job) {
        return harness.count("SELECT count(*) AS n FROM unit_attempts a JOIN units u ON u.id = a.unit_id WHERE u.job_id = $1",
                             job.value());
    }

    private long unitLostEvents(JobId job) {
        return harness.count("SELECT count(*) AS n FROM events WHERE job_id = $1 AND kind = 'unit-lost'", job.value());
    }

    private JobRow job(JobId job) {
        return harness.store.jobs()
                            .job(job)
                            .await(PgFixture.WAIT)
                            .unwrap()
                            .unwrap();
    }

    private UnitRow unit(JobId job) {
        return job(job).units()
                  .getFirst();
    }

    private UnitRow unit(JobId job, String name) {
        return job(job).units()
                  .stream()
                  .filter(unit -> unit.name()
                                      .value()
                                      .equals(name))
                  .findFirst()
                  .orElseThrow();
    }
}
