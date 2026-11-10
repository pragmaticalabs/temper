package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.SideEffects;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.LostSettle;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.LostAttempt;
import org.pragmatica.temper.store.api.LostSettled;
import org.pragmatica.temper.store.api.ResultRecord;
import org.pragmatica.temper.store.api.Starvation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;


/// m4-design §2.4, ruling R-M4-13: a unit's steps run at most twice. One unit is lost on host A (requeued, attempt 1),
/// granted to host B as attempt 2 and lost again: the second loss ends it `ERROR(host-lost)`, and the database itself
/// refuses a second requeue (`host_lost_requeues BETWEEN 0 AND 1`). Each test gets its own database.
@Tag("pg")
class RequeueBoundIT {
    private static final HostName A = TestJobs.HOST;
    private static final HostName B = HostName.hostName("cloudbb-4").unwrap();
    private static final Instant FIRST_LOSS = Instant.parse("2026-10-26T12:00:00Z");
    private static final Instant SECOND_LOSS = Instant.parse("2026-10-26T12:30:00Z");

    private StoreHarness harness;
    private JobId job;
    private UnitRef ref;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        harness.store.hosts()
                     .upsert(HostConfig.hostConfig(B,
                                                   "localhost",
                                                   none(),
                                                   3,
                                                   18432,
                                                   false).unwrap())
                     .await(PgFixture.WAIT)
                     .unwrap();
        harness.execute("UPDATE hosts SET shared_cpu = true WHERE name = $1", B.value());
        job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        ref = TestJobs.main(job);
        runOn(A);
        harness.lost(A, FIRST_LOSS);
        assertThat(settle(A, FIRST_LOSS)).containsExactly(LostSettled.lostSettled(ref,
                                                                                  1,
                                                                                  LostSettle.Outcome.REQUEUED,
                                                                                  none()));
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /// The second loss ends the unit and the job; both lost attempts are recorded, each on its own host; the unit
    /// never got a third attempt.
    @Test
    void secondLoss_endsTheUnitErrorHostLost_andTheJobWithIt() {
        assertThat(runOn(B).attempt()).isEqualTo(2);
        harness.lost(B, SECOND_LOSS);
        assertThat(settle(B, SECOND_LOSS)).containsExactly(LostSettled.lostSettled(ref,
                                                                                   2,
                                                                                   LostSettle.Outcome.BOUND,
                                                                                   some(JobState.ERROR)));
        assertThat(job().units().getFirst().state()).isEqualTo(UnitState.ERROR);
        assertThat(job().units().getFirst().reason()).isEqualTo(some(LostSettle.HOST_LOST));
        assertThat(job().state()).isEqualTo(JobState.ERROR);
        assertThat(job().reason()).isEqualTo(some(LostSettle.HOST_LOST));
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE id = (SELECT id FROM units WHERE job_id = $1) "
                                + "AND attempt = 2 AND host_lost_requeues = 1",
                                 job.value())).isEqualTo(1);
        assertThat(lostAttempt(1, A, FIRST_LOSS, false)).isEqualTo(1);
        assertThat(lostAttempt(2, B, SECOND_LOSS, true)).isEqualTo(1);
        assertThat(harness.count("SELECT count(*) AS n FROM unit_attempts")).isEqualTo(2);
        assertThat(harness.count("SELECT count(*) AS n FROM events WHERE job_id = $1 AND kind = 'unit-lost'",
                                 job.value())).isEqualTo(2);
        assertThat(grant(A)).isEmpty();
    }

    /// m4-design §11: the job read carries each lost attempt with its host, oldest first, read with the row; the unit
    /// its current attempt's CPU class; the result record the ended attempt, its CPU class and both lost attempts.
    @Test
    void views_carryTheLostAttempts_andTheRecordTheAttempt() {
        runOn(B);
        harness.lost(B, SECOND_LOSS);
        settle(B, SECOND_LOSS);
        var unit = job().units().getFirst();

        assertThat(unit.attempt()).isEqualTo(2);
        assertThat(unit.sharedCpu()).as("granted to B, a shared-CPU host").isEqualTo(some(true));
        assertThat(unit.sideEffects()).isEqualTo(SideEffects.NONE);
        assertThat(unit.lostAttempts()).extracting(LostAttempt::attempt, LostAttempt::host, LostAttempt::endedAt)
                  .containsExactly(tuple(1, A, FIRST_LOSS),
                                   tuple(2, B, SECOND_LOSS));
        assertThat(unit.lostAttempts()).as("granted now, read back as UTC instants (no session time zone shift)")
                  .allMatch(lost -> Duration.between(lost.grantedAt(),
                                                     Instant.now())
                                            .abs()
                                            .toMinutes() < 5);
        assertThat(unit.lostAttempts()).allMatch(lost -> lost.startedAt()
                                                             .isPresent());
        var record = harness.store.results().result(job).await(PgFixture.WAIT).unwrap().unwrap().units().getFirst();

        assertThat(record.attempt()).isEqualTo(some(2));
        assertThat(record.sharedCpu()).isEqualTo(some(true));
        assertThat(record.lostAttempts()).extracting(ResultRecord.LostRun::attempt, ResultRecord.LostRun::host)
                  .containsExactly(tuple(1,
                                         A.value()),
                                   tuple(2,
                                         B.value()));
    }

    /// A unit never lost reads no lost attempts and, before its first grant, no CPU class.
    @Test
    void views_unitNeverLost_noLostAttempts() {
        var other = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var unit = harness.store.jobs().job(other).await(PgFixture.WAIT).unwrap().unwrap().units().getFirst();

        assertThat(unit.lostAttempts()).isEmpty();
        assertThat(unit.sharedCpu()).isEqualTo(none());
        assertThat(unit.attempt()).isZero();
    }

    /// The bound is in the database too: a hand-written second requeue fails the column's CHECK.
    @Test
    void secondRequeue_byHand_failsTheCheck() {
        var failure = harness.raw.executor()
                                 .completeQuery("UPDATE units SET host_lost_requeues = host_lost_requeues + 1 WHERE job_id = $1",
                                                job.value())
                                 .await(PgFixture.WAIT);

        assertThat(failure.isFailure()).isTrue();
        assertThat(failure.toString()).contains("host_lost_requeues");
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND host_lost_requeues = 1",
                                 job.value())).isEqualTo(1);
    }

    /// The lost attempt rows are write-once (V11 trigger).
    @Test
    void lostAttempt_isWriteOnce() {
        var failure = harness.raw.executor()
                                 .completeQuery("UPDATE unit_attempts SET outcome = 'host-lost'")
                                 .await(PgFixture.WAIT);

        assertThat(failure.isFailure()).isTrue();
    }

    private Granted runOn(HostName host) {
        var granted = grant(host).getFirst();

        harness.store.units()
                     .started(host,
                              ref,
                              granted.attempt(),
                              TestJobs.startedRow())
                     .await(PgFixture.WAIT)
                     .unwrap();

        return granted;
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

    private List<LostSettled> settle(HostName host, Instant now) {
        return harness.store.units()
                            .settleLost(host, now, TestJobs::evaluate)
                            .await(PgFixture.WAIT)
                            .unwrap();
    }

    private long lostAttempt(int attempt, HostName host, Instant ended, boolean sharedCpu) {
        return harness.count("SELECT count(*) AS n FROM unit_attempts a JOIN units u ON u.id = a.unit_id WHERE u.job_id = $1 "
                            + "AND a.attempt = $2 AND a.host = $3 AND a.ended_at = $4 AND a.shared_cpu = $5 AND a.started_at IS NOT NULL",
                             job.value(),
                             attempt,
                             host.value(),
                             Sql.utc(ended),
                             sharedCpu);
    }

    private JobRow job() {
        return harness.store.jobs()
                            .job(job)
                            .await(PgFixture.WAIT)
                            .unwrap()
                            .unwrap();
    }
}
