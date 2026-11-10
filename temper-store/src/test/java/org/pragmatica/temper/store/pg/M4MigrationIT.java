package org.pragmatica.temper.store.pg;

import java.util.List;

import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.GrantDecision;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.HoldingRow;
import org.pragmatica.temper.store.api.Starvation;
import org.pragmatica.temper.store.api.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// V11 (m4-design §6) on a database holding rows of every unit lifecycle shape, migrated to every version below it.
/// The attempt backfill (R-12): a unit ever granted is attempt 1, granted on the only host there was (`shared_cpu`
/// false); one never granted is attempt 0 with no CPU class; every unit is `external` (fail-safe). "Ever granted"
/// includes a unit held and then requeued before its start: the requeue cleared its grant time, but its hold facts
/// stay as attempt 1's, so its next grant is attempt 2 and records its own hold. Probe: drop the `unit_facts` clause of
/// the backfill predicate → [#v11_onEveryUnitShape_backfillsTheAttempt_andAHeldRequeuedUnitsNextHoldIsRecorded] fails.
/// The constraints V11 adds hold on the migrated schema: `unit_facts.attempt` has no default, and `hosts.lost_at` is set
/// exactly while the host is LOST (§2.5 step 5: recovery clears it).
@Tag("pg")
class M4MigrationIT {
    /// The M4 migration's version; every migration below it is applied first, whatever lands beneath it.
    private static final int M4_MIGRATION = MigrationRunner.MIGRATIONS.stream()
                                                                      .filter(migration -> migration.name()
                                                                                                    .equals("m4_hosts"))
                                                                      .findFirst()
                                                                      .orElseThrow()
                                                                      .version();

    private DbConfig config;
    private Db raw;

    @BeforeEach
    void setUp() {
        config = PgFixture.freshDatabase();
        raw = PgFixture.db(config);
        MigrationRunner.migrate(raw,
                                MigrationRunner.MIGRATIONS.stream()
                                                          .filter(migration -> migration.version() < M4_MIGRATION)
                                                          .map(migration -> MigrationRunner.load(migration).unwrap())
                                                          .toList())
                       .await(PgFixture.WAIT)
                       .unwrap();
        execute("INSERT INTO hosts (name, address, memory_budget_mb) VALUES ('bigboy', 'localhost', 20480)").unwrap();
    }

    @AfterEach
    void tearDown() {
        if (raw != null) {
            raw.close().await(PgFixture.WAIT);
        }
    }

    @Test
    void v11_onEveryUnitShape_backfillsTheAttempt_andAHeldRequeuedUnitsNextHoldIsRecorded() {
        var queued = seedUnit("QUEUED", "QUEUED", "NULL", "NULL", "NULL", "NULL");
        var granted = seedUnit("QUEUED", "GRANTED", "'bigboy'", "now()", "NULL", "NULL");
        var running = seedUnit("RUNNING", "RUNNING", "'bigboy'", "now()", "now()", "NULL");
        var passed = seedUnit("PASSED", "PASSED", "'bigboy'", "now()", "now()", "now()");
        var unknown = seedUnit("UNKNOWN", "UNKNOWN", "'bigboy'", "now()", "NULL", "NULL");
        var cancelled = seedUnit("CANCELLED", "CANCELLED", "NULL", "NULL", "NULL", "now()");
        var requeued = seedUnit("QUEUED", "QUEUED", "NULL", "NULL", "NULL", "NULL");
        var heldRequeued = seedUnit("QUEUED", "QUEUED", "NULL", "NULL", "NULL", "NULL");

        execute("INSERT INTO unit_facts (unit_id, phase, facts) VALUES (" + heldRequeued + ", 'hold', '{}')").unwrap();
        try (var store = Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap()) {
            assertThat(backfill(queued)).as("never granted").isEqualTo("0/null/external");
            assertThat(backfill(granted)).isEqualTo("1/false/external");
            assertThat(backfill(running)).isEqualTo("1/false/external");
            assertThat(backfill(passed)).isEqualTo("1/false/external");
            assertThat(backfill(unknown)).isEqualTo("1/false/external");
            assertThat(backfill(cancelled)).as("cancelled while queued").isEqualTo("0/null/external");
            assertThat(backfill(requeued)).as("requeued before any hold").isEqualTo("0/null/external");
            assertThat(backfill(heldRequeued)).as("held, then requeued: its hold facts are attempt 1's")
                      .isEqualTo("1/false/external");
            assertThat(text("SELECT string_agg(attempt::text, ',') AS v FROM unit_facts")).isEqualTo("1");
            var next = grant(store, heldRequeued);

            assertThat(next.attempt()).isEqualTo(2);
            store.units()
                 .holding(TestJobs.HOST,
                          next.ref(),
                          next.attempt(),
                          HoldingRow.holdingRow(TestJobs.marker(),
                                                TestJobs.facts()))
                 .await(PgFixture.WAIT)
                 .unwrap();
            assertThat(text("SELECT string_agg(attempt::text, ',' ORDER BY attempt) AS v FROM unit_facts WHERE unit_id = " + heldRequeued
                           + " AND phase = 'hold'")).as("the new attempt's hold facts are written")
                      .isEqualTo("1,2");
        }
    }

    @Test
    void v11_unitFactsInsert_mustNameItsAttempt() {
        var unit = seedUnit("RUNNING", "RUNNING", "'bigboy'", "now()", "now()", "NULL");

        migrateAll();
        assertThat(execute("INSERT INTO unit_facts (unit_id, phase, facts) VALUES (" + unit + ", 'start', '{}')").isFailure()).as("no default attempt")
                  .isTrue();
        assertThat(execute("INSERT INTO unit_facts (unit_id, attempt, phase, facts) VALUES (" + unit
                          + ", 1, 'start', '{}')").isSuccess()).isTrue();
    }

    @Test
    void v11_hostLostAt_isSetExactlyWhileLost() {
        migrateAll();
        assertThat(execute("UPDATE hosts SET state = 'LOST'").isFailure()).as("LOST without lost_at").isTrue();
        assertThat(execute("UPDATE hosts SET state = 'UP', lost_at = now(), lost_reason = 'link-down'").isFailure()).as("lost_at kept after recovery")
                  .isTrue();
        assertThat(execute("UPDATE hosts SET state = 'LOST', lost_at = now(), lost_reason = 'host-key'").isSuccess()).isTrue();
        assertThat(execute("UPDATE hosts SET state = 'UP', lost_at = NULL, lost_reason = NULL").isSuccess()).as("recovery clears it")
                  .isTrue();
    }

    private void migrateAll() {
        Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
    }

    /// Grants exactly `unit` on the test host.
    private static Granted grant(Store store, long unit) {
        return store.units()
                    .grant(TestJobs.HOST,
                           _ -> GrantDecision.grantDecision(some(unit),
                                                            Starvation.NONE),
                           StoreHarness.LEASE,
                           1,
                           none())
                    .await(PgFixture.WAIT)
                    .unwrap()
                    .getFirst();
    }

    /// `attempt/shared_cpu/side_effects` of a unit.
    private String backfill(long unit) {
        return text("SELECT attempt || '/' || coalesce(shared_cpu::text, 'null') || '/' || side_effects AS v FROM units WHERE id = " + unit);
    }

    /// One job of one gate unit as the schema below V11 holds it; the SQL expressions are its host, grant, start and
    /// end. Returns the unit's id.
    private long seedUnit(String jobState,
                          String unitState,
                          String host,
                          String grantedAt,
                          String startedAt,
                          String endedAt) {
        var recipe = Json.write(RecipeDto.from(Recipe.gate())).unwrap();
        var sha = TestJobs.sha().value();
        var source = Json.write(new SourceDto.Pushed(none(), some(sha))).unwrap();
        var job = Sql.query(raw.executor(),
                            "INSERT INTO jobs (submitted_by, priority, source, source_sha, recipe, recipe_kind, recipe_canonical, dedup_key, state, judge) "
                           + "VALUES ('v2174', 0, $1::jsonb, $2, $3::jsonb, 'gate', $6, $4, $5, 'unjudged') RETURNING id",
                            source,
                            sha,
                            recipe,
                            TestJobs.key().value(),
                            jobState,
                            Recipe.gate().canonical())
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0)
                     .getLong("id");

        return Sql.query(raw.executor(),
                         "INSERT INTO units (job_id, name, kind, host, state, args, requests_tests, memory_mb, timeout_s, granted_at, "
                        + "started_at, ended_at) VALUES ($1, 'gate', 'GATE_UNSHARDED', " + host
                        + ", $2, '[[\"mvn\",\"install\"]]', true, "
                        + "1024, 10800, " + grantedAt
                        + ", " + startedAt
                        + ", " + endedAt
                        + ") RETURNING id",
                         job,
                         unitState)
                  .await(PgFixture.WAIT)
                  .unwrap()
                  .index(0)
                  .getLong("id");
    }

    private Result<?> execute(String sql) {
        return raw.inTransaction(tx -> Sql.execute(tx, sql))
                  .await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return raw.executor()
                  .completeQuery(sql)
                  .await(PgFixture.WAIT)
                  .unwrap()
                  .index(0)
                  .getString("v");
    }
}
