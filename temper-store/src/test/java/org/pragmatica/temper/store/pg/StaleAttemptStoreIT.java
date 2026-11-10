package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.AttemptRef;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HoldingRow;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.StoredAttempt;
import org.pragmatica.temper.store.api.UnitRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// m4-design §4.1, §4.3 (R-M4-4): every grant is a new attempt, and every write about one attempt is checked against
/// the unit's current attempt and host under its row lock. A hold unit is granted (attempt 1), waits in bb-hold,
/// is requeued (its holder died before the grant) and granted again to the SAME host (attempt 2), so the host check
/// alone cannot tell the attempts apart: each write of attempt 1 changes nothing, the same write of attempt 2 applies.
/// Each test gets its own database.
@Tag("pg")
class StaleAttemptStoreIT {
    private static final int STALE = 1;
    private static final int CURRENT = 2;
    private static final HostName ELSEWHERE = HostName.hostName("elsewhere").unwrap();

    private StoreHarness harness;
    private JobId job;
    private UnitRef ref;
    private Granted first;
    private Granted second;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        job = harness.submit(TestJobs.withUnits(List.of(TestJobs.specUnit("gate", true)), false));
        ref = TestJobs.ref(job, "gate");
        first = harness.grantHead().getFirst();
        hold(first.attempt());
        harness.store.units().requeue(TestJobs.HOST, ref, 1).await(PgFixture.WAIT).unwrap();
        harness.execute("UPDATE hosts SET shared_cpu = true WHERE name = $1", TestJobs.HOST.value());
        second = harness.grantHead().getFirst();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void grant_incrementsTheAttempt_andSnapshotsTheHostsSharedCpu() {
        assertThat(first.attempt()).isEqualTo(STALE);
        assertThat(second.attempt()).isEqualTo(CURRENT);
        assertThat(second.ref()).isEqualTo(ref);
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE attempt = 2 AND shared_cpu AND host = $1",
                                 TestJobs.HOST.value())).isEqualTo(1);
        assertThat(harness.store.units().active(TestJobs.HOST).await(PgFixture.WAIT).unwrap())
            .extracting(unit -> unit.attempt()).containsExactly(CURRENT);
    }

    /// The requeue clears the attempt's columns and keeps its number; the next grant increments it.
    @Test
    void requeue_keepsTheNumber_andClearsTheSharedCpuSnapshot() {
        harness.store.units().requeue(TestJobs.HOST, ref, CURRENT).await(PgFixture.WAIT).unwrap();

        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE attempt = 2 AND shared_cpu IS NULL AND state = 'QUEUED'"))
            .isEqualTo(1);
    }

    /// A requeue decided for attempt 1 (a reconcile's snapshot), or on another host, never lands on attempt 2's grant.
    /// Probe: drop `AND host = $3 AND attempt = $4` from `REQUEUE` → the unit is QUEUED (red).
    @Test
    void requeue_ofAStaleAttemptOrFromAnotherHost_changesNothing() {
        harness.store.units().requeue(TestJobs.HOST, ref, STALE).await(PgFixture.WAIT).unwrap();
        harness.store.units().requeue(ELSEWHERE, ref, CURRENT).await(PgFixture.WAIT).unwrap();

        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE attempt = 2 AND state = 'GRANTED' AND host = $1",
                                 TestJobs.HOST.value())).isEqualTo(1);
    }

    @Test
    void attempts_listsTheCurrentAttempt_hostAndState_ofKnownUnitsOnly() {
        var queued = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        var attempts = harness.store.units()
                              .attempts(List.of(ref, TestJobs.main(queued), TestJobs.ref(job, "nope")))
                              .await(PgFixture.WAIT)
                              .unwrap();

        assertThat(attempts).containsExactly(StoredAttempt.storedAttempt(ref, CURRENT, some(TestJobs.HOST), UnitState.GRANTED),
                                             StoredAttempt.storedAttempt(TestJobs.main(queued), 0, none(), UnitState.QUEUED));
    }

    @Test
    void holding_ofTheStaleAttempt_changesNothing_ofTheCurrentOne_waits() {
        hold(STALE);
        assertThat(unit().portGroup()).isEqualTo(none());

        hold(CURRENT);
        assertThat(unit().portGroup()).isEqualTo(some(PortGroup.WAITING));
    }

    /// The current attempt reported from a host the unit is not on changes nothing either.
    @Test
    void holding_fromAnotherHost_changesNothing() {
        harness.store.units()
               .holding(ELSEWHERE, ref, CURRENT, HoldingRow.holdingRow(TestJobs.marker(), TestJobs.facts()))
               .await(PgFixture.WAIT)
               .unwrap();

        assertThat(unit().portGroup()).isEqualTo(none());
        assertThat(facts(CURRENT)).isEmpty();
    }

    @Test
    void startUnknown_ofTheStaleAttempt_changesNothing_ofTheCurrentOne_marks() {
        harness.store.units().startUnknown(TestJobs.HOST, ref, STALE).await(PgFixture.WAIT).unwrap();
        assertThat(unit().state()).isEqualTo(UnitState.GRANTED);

        harness.store.units().startUnknown(TestJobs.HOST, ref, CURRENT).await(PgFixture.WAIT).unwrap();
        assertThat(unit().state()).isEqualTo(UnitState.UNKNOWN);
    }

    @Test
    void started_ofTheStaleAttempt_changesNothing_ofTheCurrentOne_runs() {
        start(STALE);
        assertThat(unit().state()).isEqualTo(UnitState.GRANTED);
        assertThat(unit().startedAt()).isEqualTo(none());
        assertThat(job().state()).isEqualTo(JobState.QUEUED);

        start(CURRENT);
        assertThat(unit().state()).isEqualTo(UnitState.RUNNING);
        assertThat(job().state()).isEqualTo(JobState.RUNNING);
    }

    @Test
    void refreshLeases_ofTheStaleAttempt_changesNothing_ofTheCurrentOne_extends() {
        lease(STALE);
        assertThat(longLeases()).isZero();

        lease(CURRENT);
        assertThat(longLeases()).isEqualTo(1);
    }

    /// R-M4-3: at most one stored result per unit. The stale report writes no testcase, no state, no event.
    @Test
    void complete_ofTheStaleAttempt_isSuperseded_andWritesNothing_theCurrentOneCompletes() {
        start(CURRENT);

        var stale = complete(TestJobs.HOST, STALE);

        assertThat(stale).isEqualTo(new CompleteOutcome.Superseded(ref, UnitState.RUNNING, STALE, CURRENT));
        assertThat(unit().state()).isEqualTo(UnitState.RUNNING);
        assertThat(harness.count("SELECT count(*) AS n FROM testcases WHERE job_id = $1", job.value())).isZero();
        assertThat(harness.count("SELECT count(*) AS n FROM events WHERE job_id = $1 AND kind = 'unit-finished'", job.value())).isZero();

        var current = complete(TestJobs.HOST, CURRENT);

        assertThat(current).isInstanceOf(CompleteOutcome.Completed.class);
        assertThat(unit().state()).isEqualTo(UnitState.PASSED);
        assertThat(harness.count("SELECT count(*) AS n FROM testcases WHERE job_id = $1", job.value())).isEqualTo(1);
    }

    /// The current attempt reported from a host the unit is not on is superseded too.
    @Test
    void complete_fromAnotherHost_isSuperseded_andWritesNothing() {
        start(CURRENT);

        assertThat(complete(ELSEWHERE, CURRENT)).isEqualTo(new CompleteOutcome.Superseded(ref, UnitState.RUNNING, CURRENT, CURRENT));
        assertThat(unit().state()).isEqualTo(UnitState.RUNNING);
    }

    /// F4: facts are kept per attempt; attempt 1's hold facts stay, attempt 2 records its own hold, start and end.
    @Test
    void unitFacts_areKeptPerAttempt() {
        hold(CURRENT);
        start(CURRENT);
        complete(TestJobs.HOST, CURRENT);

        assertThat(facts(STALE)).containsExactly("hold");
        assertThat(facts(CURRENT)).containsExactly("end", "hold", "start");
    }

    /// A unit's Testcontainers leftovers are its current attempt's: an older attempt's end facts are not read.
    @Test
    void tcLeftovers_ofAnOlderAttempt_areNotRead() {
        endFacts(STALE);
        assertThat(unit().tcLeftovers()).isEqualTo(none());

        endFacts(CURRENT);
        assertThat(unit().tcLeftovers()).isEqualTo(some(TestJobs.LEFTOVERS));
    }

    private void endFacts(int attempt) {
        harness.execute("INSERT INTO unit_facts (unit_id, attempt, phase, facts) SELECT id, $2, 'end', "
                        + "'{\"tcLeftovers\": {\"containers\": [{\"id\": \"tc-left\", \"session\": \"s-9\"}], \"unverified\": null}}'::jsonb "
                        + "FROM units WHERE job_id = $1",
                        job.value(),
                        attempt);
    }

    private void hold(int attempt) {
        harness.store.units()
               .holding(TestJobs.HOST, ref, attempt, HoldingRow.holdingRow(TestJobs.marker(), TestJobs.facts()))
               .await(PgFixture.WAIT)
               .unwrap();
    }

    private void start(int attempt) {
        harness.store.units().started(TestJobs.HOST, ref, attempt, TestJobs.startedRow()).await(PgFixture.WAIT).unwrap();
    }

    private void lease(int attempt) {
        harness.store.units()
               .refreshLeases(TestJobs.HOST, List.of(AttemptRef.attemptRef(ref, attempt)), TimeSpan.timeSpan(600).seconds())
               .await(PgFixture.WAIT)
               .unwrap();
    }

    private long longLeases() {
        return harness.count("SELECT count(*) AS n FROM units WHERE lease_until > now() + interval '500 seconds'");
    }

    private CompleteOutcome complete(HostName host, int attempt) {
        return harness.store.units()
                      .complete(host,
                                ref,
                                attempt,
                                TestJobs.exited(0),
                                List.of(TestJobs.row("gate", "a.ATest", "one", Outcome.PASSED)),
                                none(),
                                none(),
                                TestJobs::evaluate)
                      .await(PgFixture.WAIT)
                      .unwrap();
    }

    private List<String> facts(int attempt) {
        return Sql.rows(harness.raw.executor()
                                   .completeQuery("SELECT f.phase FROM unit_facts f JOIN units u ON u.id = f.unit_id "
                                                  + "WHERE u.job_id = $1 AND f.attempt = $2 ORDER BY f.phase",
                                                  job.value(),
                                                  attempt)
                                   .await(PgFixture.WAIT)
                                   .unwrap())
                  .stream()
                          .map(row -> row.getString("phase"))
                          .toList();
    }

    private JobRow job() {
        return harness.store.jobs().job(job).await(PgFixture.WAIT).unwrap().unwrap();
    }

    private UnitRow unit() {
        return job().units().getFirst();
    }
}
