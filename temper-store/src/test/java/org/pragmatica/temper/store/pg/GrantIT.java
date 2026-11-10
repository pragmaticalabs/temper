package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.store.api.AttemptRef;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;
import org.pragmatica.temper.store.api.Store;
import org.pragmatica.temper.store.api.StoreError;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Grant (spec §6.1, m0-design §5.3): at most one grant per unit (the conditional UPDATE), and SKIP LOCKED keeps a
/// second claimer from blocking on candidates the first one holds.
@Tag("pg")
class GrantIT {
    private static final Fn1<GrantDecision, List<QueuedUnit>> HEAD = queued -> GrantDecision.grantDecision(option(queued.isEmpty()
                                                                                                                  ? null
                                                                                                                  : queued.getFirst()
                                                                                                                          .unitId()),
                                                                                                           Starvation.NONE);

    private StoreHarness harness;
    private Store second;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        second = PgStore.pgStore(PgFixture.db(harness.config), Judge.UNJUDGED);
    }

    @AfterEach
    void tearDown() {
        if (second != null) {
            second.close();
        }

        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void grant_twoConcurrentClaimers_grantEveryUnitExactlyOnce() {
        var jobs = IntStream.range(0, 30).mapToObj(_ -> harness.submit(TestJobs.bySha(TestJobs.key(), false))).toList();
        var granted = new ArrayList<Granted>();

        for (var round = 0; round < 100; round++) {
            var both = Promise.allOf(List.of(harness.store.units()
                                                          .grant(TestJobs.HOST,
                                                                 HEAD,
                                                                 StoreHarness.LEASE,
                                                                 3,
                                                                 none()),
                                             second.units().grant(TestJobs.HOST,
                                                                  HEAD,
                                                                  StoreHarness.LEASE,
                                                                  3,
                                                                  none())))
                              .await(PgFixture.WAIT)
                              .unwrap();
            var batch = both.stream().flatMap(result -> result.unwrap()
                                                              .stream()).toList();

            if (batch.isEmpty()) {
                break;
            }

            granted.addAll(batch);
        }

        var unitIds = granted.stream().map(Granted::unitId).toList();

        assertThat(unitIds).doesNotHaveDuplicates().hasSize(jobs.size());
        assertThat(granted.stream().map(grant -> grant.ref()
                                                      .job()).toList()).containsExactlyInAnyOrderElementsOf(jobs);
        assertThat(count("SELECT count(*) AS n FROM units WHERE state = 'GRANTED' AND host = 'bigboy'")).isEqualTo(jobs.size());
    }

    /// The first claimer holds every candidate row lock (its policy waits); the second must return at once with
    /// nothing instead of blocking until the first commits. With plain FOR UPDATE it would wait out the first
    /// claimer's 10 s hold.
    @Test
    void grant_secondClaimer_skipsLockedCandidates_withoutBlocking() throws InterruptedException {
        IntStream.range(0, 3).forEach(_ -> harness.submit(TestJobs.bySha(TestJobs.key(), false)));
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Fn1<GrantDecision, List<QueuedUnit>> slow = queued -> {
            holding.countDown();
            await(release);

            return HEAD.apply(queued);
        };
        var first = harness.store.units().grant(TestJobs.HOST, slow, StoreHarness.LEASE, 1, none());

        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        var started = Instant.now();
        var secondGrants = second.units()
                                 .grant(TestJobs.HOST,
                                        HEAD,
                                        StoreHarness.LEASE,
                                        3,
                                        none())
                                 .await(TimeSpan.timeSpan(30).seconds());
        var elapsed = Duration.between(started, Instant.now());

        release.countDown();
        assertThat(secondGrants.unwrap()).isEmpty();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        assertThat(first.await(PgFixture.WAIT).unwrap()).hasSize(1);
    }

    @Test
    void grant_returnsWhatStartUnitNeeds_andRespectsMaxGrants() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var granted = harness.store.units()
                                   .grant(TestJobs.HOST,
                                          HEAD,
                                          StoreHarness.LEASE,
                                          1,
                                          none())
                                   .await(PgFixture.WAIT)
                                   .unwrap();

        assertThat(granted).hasSize(1);
        var one = granted.getFirst();

        assertThat(one.ref()).isEqualTo(UnitRef.unitRef(job, UnitName.MAIN));
        assertThat(one.steps()).isEqualTo(TestJobs.unit("main").steps());
        assertThat(one.timeout()).isEqualTo(Duration.ofMinutes(60));
        assertThat(one.memoryMb()).isEqualTo(6144);
        assertThat(one.sha()).isEqualTo(harness.store.jobs()
                                                     .job(job)
                                                     .await(PgFixture.WAIT)
                                                     .unwrap()
                                                     .flatMap(row -> row.sha())
                                                     .unwrap());
        assertThat(count("SELECT count(*) AS n FROM units WHERE state = 'QUEUED'")).isEqualTo(1);
        assertThat(count("SELECT count(*) AS n FROM units WHERE lease_until BETWEEN now() + interval '25 seconds' "
                        + "AND now() + interval '35 seconds'")).isEqualTo(1);
    }

    @Test
    void grant_ordersByPriorityThenSubmitTime() {
        var low = harness.submit(TestJobs.byRef("feature/a", TestJobs.sha(), false, -20));
        var high = harness.submit(TestJobs.byRef("feature/b", TestJobs.sha(), false, 10));
        var seen = new ArrayList<List<Long>>();
        Fn1<GrantDecision, List<QueuedUnit>> recording = queued -> {
            seen.add(queued.stream().map(QueuedUnit::unitId).toList());

            return HEAD.apply(queued);
        };
        var granted = harness.store.units()
                                   .grant(TestJobs.HOST,
                                          recording,
                                          StoreHarness.LEASE,
                                          2,
                                          none())
                                   .await(PgFixture.WAIT)
                                   .unwrap();

        assertThat(granted.stream().map(grant -> grant.ref()
                                                      .job()).toList()).containsExactly(high, low);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).hasSize(1);
    }

    /// The conditional UPDATE alone: re-granting a unit that is no longer QUEUED changes nothing.
    @Test
    void grantUpdate_isConditionalOnQueued() {
        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var unitId = harness.grantHead().getFirst().unitId();
        var regranted = harness.raw.executor()
                                   .completeQuery(PgUnitStore.GRANT,
                                                  unitId,
                                                  "bigboy",
                                                  30.0,
                                                  Judge.UNJUDGED.value())
                                   .await(PgFixture.WAIT)
                                   .unwrap();

        assertThat(regranted.affectedRows()).isZero();
    }

    @Test
    void grant_decisionOutsideCandidates_grantsNothing() {
        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var granted = harness.store.units()
                                   .grant(TestJobs.HOST,
                                          _ -> GrantDecision.grantDecision(some(Long.MAX_VALUE),
                                                                           Starvation.NONE),
                                          StoreHarness.LEASE,
                                          5,
                                          none())
                                   .await(PgFixture.WAIT)
                                   .unwrap();
        var none = harness.store.units()
                                .grant(TestJobs.HOST,
                                       _ -> GrantDecision.grantDecision(none(),
                                                                        Starvation.NONE),
                                       StoreHarness.LEASE,
                                       5,
                                       none())
                                .await(PgFixture.WAIT)
                                .unwrap();

        assertThat(granted).isEmpty();
        assertThat(none).isEmpty();
        assertThat(count("SELECT count(*) AS n FROM units WHERE state = 'QUEUED'")).isEqualTo(1);
    }

    @Test
    void activeLeasesAndRequeue_followTheUnitState() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        harness.grantHead();
        var active = harness.store.units().active(TestJobs.HOST).await(PgFixture.WAIT).unwrap();

        assertThat(active).hasSize(1);
        assertThat(active.getFirst().state()).isEqualTo(UnitState.GRANTED);
        assertThat(active.getFirst().memoryMb()).isEqualTo(6144);
        harness.store.units()
                     .refreshLeases(TestJobs.HOST,
                                    List.of(AttemptRef.attemptRef(TestJobs.main(job),
                                                                  1)),
                                    TimeSpan.timeSpan(600).seconds())
                     .await(PgFixture.WAIT)
                     .unwrap();
        assertThat(count("SELECT count(*) AS n FROM units WHERE lease_until > now() + interval '500 seconds'")).isEqualTo(1);
        harness.store.units().requeue(TestJobs.HOST, TestJobs.main(job), 1).await(PgFixture.WAIT).unwrap();
        assertThat(harness.store.units().active(TestJobs.HOST).await(PgFixture.WAIT).unwrap()).isEmpty();
        assertThat(count("SELECT count(*) AS n FROM units WHERE state = 'QUEUED' AND host IS NULL AND lease_until IS NULL")).isEqualTo(1);
        harness.grantHead();
        harness.start(job);
        harness.store.units().requeue(TestJobs.HOST, TestJobs.main(job), 1).await(PgFixture.WAIT).unwrap();
        assertThat(count("SELECT count(*) AS n FROM units WHERE state = 'RUNNING'")).as("a started unit is never requeued")
                  .isEqualTo(1);
    }

    /// Ruling R-3: the grant policy sees each queued unit's kind, and the host snapshot each active unit's.
    @Test
    void grantAndActive_carryTheUnitKind() {
        harness.submit(TestJobs.withUnits(List.of(TestJobs.gateUnit()),
                                          false));
        var seen = new ArrayList<UnitKind>();
        Fn1<GrantDecision, List<QueuedUnit>> recording = queued -> {
            queued.forEach(unit -> seen.add(unit.kind()));

            return HEAD.apply(queued);
        };

        harness.store.units()
                     .grant(TestJobs.HOST,
                            recording,
                            StoreHarness.LEASE,
                            1,
                            none())
                     .await(PgFixture.WAIT)
                     .unwrap();
        assertThat(seen).containsExactly(UnitKind.GATE_UNSHARDED);
        assertThat(harness.store.units().active(TestJobs.HOST).await(PgFixture.WAIT).unwrap()).extracting(ActiveUnit::kind)
                  .containsExactly(UnitKind.GATE_UNSHARDED);
    }

    @Test
    void requeue_unknownUnit_fails() {
        harness.store.units()
                     .requeue(TestJobs.HOST,
                              TestJobs.main(JobId.jobId(999_999).unwrap()),
                              1)
                     .await(PgFixture.WAIT)
                     .onSuccess(_ -> fail("expected UnitNotFound"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(StoreError.UnitNotFound.class));
    }

    private long count(String sql) {
        return harness.raw.executor()
                          .completeQuery(sql)
                          .await(PgFixture.WAIT)
                          .unwrap()
                          .index(0)
                          .getLong("n");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
