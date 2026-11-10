package org.pragmatica.temper.store.pg;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;
import org.pragmatica.temper.store.api.StoreError;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// #111: a grant's COMMIT is fenced (V18 `units_grant_fence`). A grant transaction whose GRANT statement ran before the
/// fence but whose COMMIT comes at or after it, by the database clock, fails whole: the unit stays QUEUED with its
/// attempt unchanged, no `checkouts` row is left, and another host can grant it. The fence is judged at COMMIT, so the
/// held case cannot pass by a check made when the transaction began or when its statement ran.
///
/// Probe P111: the V18 function returns without raising → [#commitPastTheFence_failsWhole_unitStaysQueued_andAnotherHostGrantsIt]
/// and [#fencePassedBeforeTheTransaction_grantsNothing] are red.
@Tag("pg")
class GrantFenceIT {
    private static final Fn1<GrantDecision, List<QueuedUnit>> HEAD = queued -> GrantDecision.grantDecision(option(queued.isEmpty()
                                                                                                                  ? null
                                                                                                                  : queued.getFirst()
                                                                                                                          .unitId()),
                                                                                                           Starvation.NONE);

    private static final HostName OTHER = HostName.hostName("cb").unwrap();
    private static final Duration FENCE_IN = Duration.ofSeconds(2);
    /// How far past the fence the held COMMIT is released (covers the JVM-to-database clock offset on one machine).
    private static final Duration PAST = Duration.ofSeconds(1);

    private StoreHarness harness;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        harness.store.hosts()
                     .upsert(HostConfig.hostConfig(OTHER,
                                                   "cb.example",
                                                   none(),
                                                   3,
                                                   18432,
                                                   false,
                                                   false,
                                                   none()).unwrap())
                     .await(PgFixture.WAIT)
                     .unwrap();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /// The issue's case: decided and written before the fence, the COMMIT held past it, then released.
    @Test
    void commitPastTheFence_failsWhole_unitStaysQueued_andAnotherHostGrantsIt() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var ref = UnitRef.unitRef(job, UnitName.MAIN);
        var attempt = harness.attempt(ref);
        var start = Instant.now();
        var fence = dbNow().plus(FENCE_IN);
        var secondRound = new AtomicReference<Instant>();
        var outcome = grant(TestJobs.HOST,
                            headThenWait(start.plus(FENCE_IN).plus(PAST),
                                         secondRound),
                            some(fence));

        assertThat(Duration.between(start,
                                    secondRound.get())).as("the GRANT statement ran before the fence")
                  .isLessThan(FENCE_IN);
        assertFenced(outcome);
        assertThat(queuedAt(job, attempt)).as("QUEUED, no host, attempt unchanged").isEqualTo(1);
        assertThat(checkouts(job)).as("no half-written grant").isZero();
        assertThat(grant(OTHER,
                         HEAD,
                         none()).unwrap()).extracting(Granted::ref)
                  .as("placeable on another host")
                  .containsExactly(ref);
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND host = 'cb' AND state = 'GRANTED'",
                                 job.value())).isEqualTo(1);
    }

    /// Control: the same two-round transaction committing well before the fence lands.
    @Test
    void commitBeforeTheFence_lands() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var secondRound = new AtomicReference<Instant>();
        var outcome = grant(TestJobs.HOST,
                            headThenWait(Instant.now(), secondRound),
                            some(dbNow().plus(Duration.ofMinutes(1))));

        assertThat(outcome.unwrap()).extracting(Granted::ref).containsExactly(UnitRef.unitRef(job, UnitName.MAIN));
        assertThat(harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND host = 'bigboy' AND state = 'GRANTED'",
                                 job.value())).isEqualTo(1);
        assertThat(checkouts(job)).isEqualTo(1);
    }

    @Test
    void fencePassedBeforeTheTransaction_grantsNothing() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var attempt = harness.attempt(UnitRef.unitRef(job, UnitName.MAIN));

        assertFenced(grant(TestJobs.HOST, HEAD, some(dbNow().minusSeconds(1))));
        assertThat(queuedAt(job, attempt)).isEqualTo(1);
        assertThat(checkouts(job)).isZero();
    }

    /// The setting is transaction-local: once a fenced transaction ends, its pooled session reads '' and grants unfenced
    /// again (a NULL-only check would fail every later grant on that session).
    @Test
    void afterAFencedTransaction_unfencedGrantsLand() {
        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        assertFenced(grant(TestJobs.HOST, HEAD, some(dbNow().minusSeconds(1))));
        var granted = 0;

        for (int i = 0; i < 3; i++) {
            granted += grant(TestJobs.HOST, HEAD, none()).unwrap().size();
        }

        assertThat(granted).isEqualTo(3);
    }

    /// Fails closed (maintainer ruling on #114): a QUEUED -> GRANTED commit that never set the fence is refused, so a future
    /// GRANT path that forgets `set_config` cannot grant unfenced.
    @Test
    void grantWithNoFenceSet_isRefused_unitStaysQueued_noCheckout() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var attempt = harness.attempt(UnitRef.unitRef(job, UnitName.MAIN));
        var outcome = rawGrant(job, null);

        assertThat(outcome.isFailure()).as("the COMMIT of an unfenced GRANT fails").isTrue();
        outcome.onFailure(cause -> assertThat(cause.message()).contains("grant fence unset"));
        assertThat(queuedAt(job, attempt)).as("QUEUED, attempt unchanged").isEqualTo(1);
        assertThat(checkouts(job)).isZero();
    }

    @Test
    void grantWithEmptyFence_isRefused() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        assertThat(rawGrant(job, "").isFailure()).isTrue();
        assertThat(queuedAt(job,
                            harness.attempt(UnitRef.unitRef(job, UnitName.MAIN)))).isEqualTo(1);
    }

    /// 'none' is the explicit "unfenced, deliberately" sentinel and passes.
    @Test
    void grantWithNoneSentinel_lands() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

        assertThat(rawGrant(job, "none").isSuccess()).isTrue();
        assertThat(checkouts(job)).isEqualTo(1);
    }

    @Test
    void grantWithMalformedFence_isRefused() {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var attempt = harness.attempt(UnitRef.unitRef(job, UnitName.MAIN));

        for (var bad : List.of("None", "tomorrow", "12x", "-5", "1.5", " 1")) {
            var outcome = rawGrant(job, bad);

            assertThat(outcome.isFailure()).as("malformed fence '%s'", bad).isTrue();
            outcome.onFailure(cause -> assertThat(cause.message()).contains("grant fence malformed"));
        }

        assertThat(queuedAt(job, attempt)).isEqualTo(1);
        assertThat(checkouts(job)).isZero();
    }

    /// The `>=` edge, pinned on the predicate itself with fixed timestamps (the database clock cannot be frozen, so a
    /// commit "exactly at" the fence is not reproducible; the trigger calls this same function with clock_timestamp()).
    @Test
    void fencePredicate_atTheFenceIsPassed_oneMillisecondBeforeIsNot() {
        var fence = "1700000000123";

        assertThat(harness.count("SELECT CASE WHEN temper_grant_fence_passed(timestamptz '2023-11-14 22:13:20.123+00', " + fence
                                + ") THEN 1 ELSE 0 END AS n")).as("at the fence: refused")
                  .isEqualTo(1);
        assertThat(harness.count("SELECT CASE WHEN temper_grant_fence_passed(timestamptz '2023-11-14 22:13:20.122+00', " + fence
                                + ") THEN 1 ELSE 0 END AS n")).as("1 ms before: lands")
                  .isZero();
        assertThat(harness.count("SELECT CASE WHEN temper_grant_fence_passed(timestamptz '2023-11-14 22:13:20.124+00', " + fence
                                + ") THEN 1 ELSE 0 END AS n")).as("1 ms after: refused")
                  .isEqualTo(1);
    }

    /// #114 review nit: V18's trigger contains no comparison of its own; it must DELEGATE to `temper_grant_fence_passed`.
    /// A trigger that inlined `clock_timestamp() > fence` (or `>=`) would pass every other test here, since the clock cannot
    /// be frozen and the two differ only at one exact microsecond. So the predicate is swapped for one that answers
    /// "not passed" and a fence long past must then LAND: only a trigger that asks the function can. The function is
    /// restored from the migration's own text, in `finally`.
    @Test
    void trigger_delegatesTheComparisonToThePredicate() throws Exception {
        var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));
        var original = predicateDdl();

        replacePredicate("CREATE OR REPLACE FUNCTION temper_grant_fence_passed(at timestamptz, fence_ms bigint) RETURNS boolean "
                         + "LANGUAGE sql IMMUTABLE AS $$ SELECT false $$");
        try {
            var outcome = grant(TestJobs.HOST, HEAD, some(dbNow().minusSeconds(60)));

            assertThat(outcome.unwrap()).as("the stubbed predicate said not passed, and the trigger obeyed it")
                      .extracting(Granted::ref)
                      .containsExactly(UnitRef.unitRef(job, UnitName.MAIN));
        } finally {
            replacePredicate(original);
        }
        harness.submit(TestJobs.bySha(TestJobs.key(), false));
        assertFenced(grant(OTHER, HEAD, some(dbNow().minusSeconds(60))));
    }

    private void replacePredicate(String ddl) {
        harness.raw.executor().completeScript(ddl).await(PgFixture.WAIT).unwrap();
    }

    /// The migration's `CREATE FUNCTION temper_grant_fence_passed ... $$;` statement, as a CREATE OR REPLACE.
    private static String predicateDdl() throws java.io.IOException {
        try (var in = GrantFenceIT.class.getResourceAsStream("/db/migration/V18__i111_grant_fence.sql")) {
            var sql = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var start = sql.indexOf("CREATE FUNCTION temper_grant_fence_passed");
            var end = sql.indexOf("$$;", sql.indexOf("$$", start) + 2) + 2;

            return sql.substring(start, end).replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION");
        }
    }

    /// One statement, one implicit transaction: the CTE sets the (local) fence when `fence` is non-null, joined so it runs;
    /// a null `fence` leaves the setting untouched. The checkout row is what a real grant adds with the state change.
    private Result<?> rawGrant(JobId job, String fence) {
        var set = fence == null
                  ? "SELECT 1 AS x"
                  : "SELECT set_config('temper.grant_fence', '" + fence + "', true) AS x";
        var sql = "WITH s AS (" + set
                + "), c AS (INSERT INTO checkouts (job_id, host) SELECT $1::bigint, 'bigboy' ON CONFLICT DO NOTHING) "
                + "UPDATE units SET state = 'GRANTED', host = 'bigboy', granted_at = now(), attempt = attempt + 1 "
                + "FROM s WHERE job_id = $1 AND state = 'QUEUED'";

        return harness.raw.executor()
                          .completeQuery(sql,
                                         job.value())
                          .await(PgFixture.WAIT);
    }

    private Result<List<Granted>> grant(HostName host,
                                        Fn1<GrantDecision, List<QueuedUnit>> policy,
                                        Option<Instant> fence) {
        return harness.store.units()
                            .grant(host, policy, StoreHarness.LEASE, 2, fence)
                            .await(PgFixture.WAIT);
    }

    /// Round one grants the head; round two records when it ran, waits until `until` and ends the rounds. So the GRANT
    /// statement runs at once and the COMMIT comes after `until`.
    private static Fn1<GrantDecision, List<QueuedUnit>> headThenWait(Instant until,
                                                                     AtomicReference<Instant> secondRound) {
        var rounds = new AtomicInteger();

        return queued -> {
            if (rounds.getAndIncrement() == 0) {
                return HEAD.apply(queued);
            }

            secondRound.set(Instant.now());
            sleepUntil(until);

            return GrantDecision.grantDecision(none(), Starvation.NONE);
        };
    }

    private static void sleepUntil(Instant until) {
        var left = Duration.between(Instant.now(), until);

        if (left.isPositive()) {
            try {
                Thread.sleep(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void assertFenced(Result<List<Granted>> outcome) {
        assertThat(outcome.isFailure()).as("the COMMIT at or past the fence fails").isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StoreError.Database.class,
                                                                            database -> assertThat(database.origin()
                                                                                                           .message()).contains("grant fence passed")));
    }

    private Instant dbNow() {
        return Instant.ofEpochMilli(harness.count("SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint AS n"));
    }

    private long queuedAt(JobId job, int attempt) {
        return harness.count("SELECT count(*) AS n FROM units WHERE job_id = $1 AND state = 'QUEUED' AND host IS NULL AND attempt = $2",
                             job.value(),
                             attempt);
    }

    private long checkouts(JobId job) {
        return harness.count("SELECT count(*) AS n FROM checkouts WHERE job_id = $1", job.value());
    }
}
