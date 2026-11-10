package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.JobRow;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;
import org.pragmatica.temper.store.api.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;


/// m4-design §9.2 (R-M4-8, probe P11): placement is a filter, not a fit. A `--dedicated-cpu` job's unit is never a
/// candidate of a `sharedCpu` host's grant: the policy never sees it there (so it is never that host's head, and cannot
/// start its starvation clock, F9), its grant transaction never locks it, and it is never granted there. It waits QUEUED
/// until a dedicated host grants it. Host A is the shared host (`cloudbb-3`); host B is bigboy (dedicated); "B is DOWN"
/// is a host whose grant is not called.
///
/// Probe P11: drop the CPU-class filter from `PgUnitStore.CANDIDATES` (`$1` still bound) → A's policy sees the
/// dedicated unit (red) and A's held transaction locks it so B's grant gets nothing (red); [#grant_refusesADedicatedUnitOnASharedHost_evenWhenNamed]
/// stays green: [PgUnitStore#GRANT] still refuses. Probe P19: drop the filter from `GRANT` → that test is red.
/// Probe m5: `Rows` reads `dedicated_cpu` as false → [#jobRow_readsTheJobsCpuClass] is red.
@Tag("pg")
class DedicatedPlacementIT {
    private static final HostName SHARED = HostName.hostName("cloudbb-3").unwrap();

    private static final Fn1<GrantDecision, List<QueuedUnit>> HEAD = queued -> GrantDecision.grantDecision(option(queued.isEmpty()
                                                                                                                  ? null
                                                                                                                  : queued.getFirst()
                                                                                                                          .unitId()),
                                                                                                           Starvation.NONE);

    private StoreHarness harness;
    private Store second;
    private JobId dedicated;
    private JobId plain;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        second = PgStore.pgStore(PgFixture.db(harness.config), Judge.UNJUDGED);
        harness.store.hosts()
                     .upsert(HostConfig.hostConfig(SHARED,
                                                   "203.0.113.164",
                                                   none(),
                                                   3,
                                                   18432,
                                                   false,
                                                   true,
                                                   none()).unwrap())
                     .await(PgFixture.WAIT)
                     .unwrap();
        dedicated = harness.submit(TestJobs.bySha(TestJobs.key(), false).dedicated(true));
        plain = harness.submit(TestJobs.bySha(TestJobs.key(), false));
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

    private long unitOf(JobId job) {
        return harness.count("SELECT id AS n FROM units WHERE job_id = $1", job.value());
    }

    private JobRow row(JobId job) {
        return harness.store.jobs()
                            .job(job)
                            .await(PgFixture.WAIT)
                            .unwrap()
                            .fold(() -> fail("expected a value"),
                                  found -> found);
    }

    private List<Granted> grant(Store through, HostName host, Fn1<GrantDecision, List<QueuedUnit>> policy) {
        return through.units()
                      .grant(host,
                             policy,
                             StoreHarness.LEASE,
                             5,
                             none())
                      .await(PgFixture.WAIT)
                      .unwrap();
    }

    /// The dedicated job is the queue's head (submitted first); the shared host's policy never sees it, grants the plain
    /// unit only, and the dedicated one stays QUEUED, never attempted, while no dedicated host grants (B DOWN).
    @Test
    void sharedHost_grantsThePlainUnitOnly_neverSeesTheDedicatedOne() {
        var seen = new CopyOnWriteArrayList<Long>();
        Fn1<GrantDecision, List<QueuedUnit>> recording = queued -> {
            queued.forEach(unit -> seen.add(unit.unitId()));

            return HEAD.apply(queued);
        };
        var granted = grant(harness.store, SHARED, recording);
        var again = grant(harness.store, SHARED, recording);

        assertThat(granted).extracting(one -> one.ref()
                                                 .job()).containsExactly(plain);
        assertThat(again).isEmpty();
        assertThat(seen).as("every candidate the shared host's policy saw")
                  .contains(unitOf(plain))
                  .doesNotContain(unitOf(dedicated));
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND state = 'QUEUED' AND host IS NULL "
                                + "AND attempt = 0",
                                 dedicated.value())).as("dedicated unit still QUEUED, never attempted")
                  .isEqualTo(1);
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND shared_cpu AND host = $2",
                                 plain.value(),
                                 SHARED.value())).as("the plain unit's snapshot names the shared class")
                  .isEqualTo(1);
    }

    /// While the shared host's grant transaction holds every candidate it read, a dedicated host's grant takes the
    /// dedicated unit at once: the shared host never locked it. B comes UP → the dedicated unit is granted on B, with a
    /// dedicated-class snapshot.
    @Test
    void sharedHostsGrant_neverLocksTheDedicatedUnit_dedicatedHostGrantsIt() throws InterruptedException {
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Fn1<GrantDecision, List<QueuedUnit>> slow = queued -> {
            holding.countDown();
            await(release);

            return HEAD.apply(queued);
        };
        var shared = harness.store.units().grant(SHARED, slow, StoreHarness.LEASE, 1, none());

        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        var started = Instant.now();
        var onDedicated = second.units()
                                .grant(TestJobs.HOST,
                                       HEAD,
                                       StoreHarness.LEASE,
                                       5,
                                       none())
                                .await(TimeSpan.timeSpan(30).seconds())
                                .unwrap();
        var elapsed = Duration.between(started, Instant.now());

        release.countDown();
        assertThat(onDedicated).extracting(one -> one.ref()
                                                     .job()).containsExactly(dedicated);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        assertThat(shared.await(PgFixture.WAIT).unwrap()).extracting(one -> one.ref()
                                                                               .job()).containsExactly(plain);
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND shared_cpu = false AND host = $2",
                                 dedicated.value(),
                                 TestJobs.HOST.value())).isEqualTo(1);
    }

    /// m4-design §9.2, second line of defence: the grant UPDATE itself re-checks the class, so a dedicated unit named
    /// to a shared host (a policy, or a candidate read that missed the filter) stays QUEUED and unattempted.
    @Test
    void grant_refusesADedicatedUnitOnASharedHost_evenWhenNamed() {
        var unit = unitOf(dedicated);

        assertThat(harness.count("WITH g AS (" + PgUnitStore.GRANT + ") SELECT count(*) AS n FROM g",
                                 unit,
                                 SHARED.value(),
                                 30.0,
                                 Judge.UNJUDGED.value())).as("rows the shared host's GRANT returned")
                  .isZero();
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE id = $1 AND state = 'QUEUED' AND host IS NULL "
                                + "AND attempt = 0",
                                 unit)).isEqualTo(1);
        assertThat(harness.count("WITH f AS (SELECT set_config('temper.grant_fence', '" + PgUnitStore.NO_FENCE
                                + "', true)), g AS (" + PgUnitStore.GRANT
                                + ") SELECT count(*) AS n FROM g, f",
                                 unit,
                                 TestJobs.HOST.value(),
                                 30.0,
                                 Judge.UNJUDGED.value())).as("the same GRANT on a dedicated host")
                  .isEqualTo(1);
    }

    /// The job's class is read back with its row (`jobs().job`, the row `RerunJob` inherits the class from, §9.3).
    @Test
    void jobRow_readsTheJobsCpuClass() {
        assertThat(row(dedicated).dedicatedCpu()).isTrue();
        assertThat(row(plain).dedicatedCpu()).isFalse();
    }

    /// A dedicated host's grant reads every class: the plain unit is a candidate there too.
    @Test
    void dedicatedHost_grantsBothClasses() {
        assertThat(grant(harness.store, TestJobs.HOST, HEAD)).extracting(one -> one.ref()
                                                                                   .job())
                  .containsExactlyInAnyOrder(dedicated, plain);
    }

    /// m4-design §9.1, §10: the upsert writes `shared_cpu` and `retire_at`, and a restart with them removed clears them
    /// (config + restart is how they move, F8).
    @Test
    void upsert_writesAndClearsSharedCpuAndRetireAt() {
        var retire = Instant.parse("2026-11-13T00:00:00Z");

        harness.store.hosts()
                     .upsert(HostConfig.hostConfig(SHARED,
                                                   "203.0.113.164",
                                                   none(),
                                                   3,
                                                   18432,
                                                   false,
                                                   true,
                                                   some(retire)).unwrap())
                     .await(PgFixture.WAIT)
                     .unwrap();
        assertThat(harness.count("SELECT count(*) AS n FROM hosts WHERE name = $1 AND shared_cpu AND retire_at = $2::timestamptz",
                                 SHARED.value(),
                                 retire.toString())).isEqualTo(1);
        harness.store.hosts()
                     .upsert(HostConfig.hostConfig(SHARED,
                                                   "203.0.113.164",
                                                   none(),
                                                   3,
                                                   18432,
                                                   false).unwrap())
                     .await(PgFixture.WAIT)
                     .unwrap();
        assertThat(harness.count("SELECT count(*) AS n FROM hosts WHERE name = $1 AND NOT shared_cpu AND retire_at IS NULL",
                                 SHARED.value())).isEqualTo(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
