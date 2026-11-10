package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;
import org.pragmatica.temper.store.api.DbConfig;
import org.pragmatica.temper.store.api.Pending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// V2 applied on top of a V1 database that already holds M0 rows (m1-design §7): the rows survive unchanged and
/// read through the M1 store, the new columns are empty, the event floor starts at 0 so the existing events read
/// without a gap, and the V1 guarantees (write-once) still hold. A V1 database with manifest rows (which M0 never
/// wrote) fails V2 as a whole instead of guessing their unit.
@Tag("pg")
class MigrationV2IT {
    private DbConfig config;
    private Db raw;

    @BeforeEach
    void setUp() {
        config = PgFixture.freshDatabase();
        raw = PgFixture.db(config);
        MigrationRunner.migrate(raw, List.of(MigrationRunner.load(MigrationRunner.MIGRATIONS.getFirst()).unwrap()))
                       .await(PgFixture.WAIT)
                       .unwrap();
    }

    @AfterEach
    void tearDown() {
        if (raw != null) {
            raw.close().await(PgFixture.WAIT);
        }
    }

    @Test
    void v2_onV1Data_keepsTheRows_floorZero_existingEventsReadWithoutGap() {
        var job = seedV1Job();
        var recordBefore = text("SELECT record::text AS v FROM results");
        var counts = rowCounts();

        try (var store = Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap()) {
            assertThat(text("SELECT string_agg(version::text, ',' ORDER BY version) AS v FROM schema_migrations")).as("every listed migration").isEqualTo(String.join(",", MigrationRunner.MIGRATIONS.stream().map(migration -> String.valueOf(migration.version())).toList()));
            assertThat(rowCounts()).isEqualTo(counts);
            assertThat(text("SELECT record::text AS v FROM results")).isEqualTo(recordBefore);

            var row = store.jobs().job(job).await(PgFixture.WAIT).unwrap().unwrap();

            assertThat(row.verdict()).isEqualTo(some("PASSED"));
            assertThat(row.units().getFirst().portGroup()).isEqualTo(none());
            assertThat(row.units().getFirst().ticket()).isEqualTo(none());
            assertThat(store.events().floor().await(PgFixture.WAIT).unwrap()).isZero();
            assertThat(store.events().pending(0, Set.of(), 10).await(PgFixture.WAIT).unwrap())
                .isInstanceOfSatisfying(Pending.Events.class, events -> assertThat(events.rows()).hasSize(3));
            assertThat(store.events().subscriber("m0.sub").await(PgFixture.WAIT).unwrap().cursor()).isEqualTo(1);
            assertThat(store.events().subscriber("m1.sub").await(PgFixture.WAIT).unwrap().cursor()).isEqualTo(3);
            assertThat(store.results().manifest(job).await(PgFixture.WAIT).unwrap()).isEqualTo(none());
            assertThat(store.refusals().active().await(PgFixture.WAIT).unwrap()).hasSize(2);
            assertThat(text("SELECT string_agg(host, ',') AS v FROM checkouts")).as("checkouts backfilled from units.host").isEqualTo("bigboy");
        }
        assertThat(execute("UPDATE results SET verdict = 'FAILED'").isFailure()).as("write-once survives V2").isTrue();
        assertThat(execute("UPDATE units SET port_group = 'bogus'").isFailure()).as("port_group CHECK").isTrue();
        assertThat(execute("INSERT INTO event_floor (id, floor_seq) VALUES (2, 0)").isFailure()).as("one floor row").isTrue();
        assertThat(execute("INSERT INTO unit_facts (unit_id, attempt, phase, facts) SELECT id, 1, 'hold', '{}' FROM units").isSuccess()).isTrue();
    }

    @Test
    void v2_onV1WithManifestRows_failsWhole_leavesV1Untouched() {
        seedV1Job();
        execute("INSERT INTO manifests (job_id, module, class) SELECT id, 'mod', 'a.ATest' FROM jobs").unwrap();

        var outcome = Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).as(outcome.toString()).isTrue();
        assertThat(text("SELECT string_agg(version::text, ',' ORDER BY version) AS v FROM schema_migrations")).isEqualTo("1");
        assertThat(text("SELECT to_regclass('public.event_floor')::text AS v")).isNull();
        assertThat(text("SELECT count(*)::text AS v FROM manifests")).isEqualTo("1");
    }

    /// V3 (rulings R-2, R-7) on a V2 database holding a computed manifest: its rows become required, and its
    /// `entry_count` is backfilled from them, so the manifest reads as written, not pruned.
    @Test
    void v3_onV2ManifestRows_backfillsRequiredAndEntryCount() {
        var job = seedV1Job();
        MigrationRunner.migrate(raw, List.of(MigrationRunner.load(MigrationRunner.MIGRATIONS.getFirst()).unwrap(),
                                             MigrationRunner.load(MigrationRunner.MIGRATIONS.get(1)).unwrap()))
                       .await(PgFixture.WAIT)
                       .unwrap();
        execute("UPDATE units SET manifest_spec = '{}'::jsonb").unwrap();
        execute("INSERT INTO manifests (job_id, unit_id, module, class, runner) SELECT job_id, id, 'mod', c, 'surefire' "
                + "FROM units, unnest(ARRAY['a.ATest', 'b.BTest']) c").unwrap();
        execute("INSERT INTO manifest_units (unit_id, status) SELECT id, 'computed' FROM units").unwrap();

        try (var store = Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap()) {
            var manifest = store.results().manifest(job).await(PgFixture.WAIT).unwrap().unwrap();

            assertThat(text("SELECT string_agg(entry_count::text, ',') AS v FROM manifest_units")).isEqualTo("2");
            assertThat(text("SELECT count(*)::text AS v FROM manifests WHERE NOT optional")).isEqualTo("2");
            assertThat(manifest.entries()).hasSize(2);
            assertThat(manifest.optional()).isEmpty();
            assertThat(manifest.pruned()).isFalse();
        }
    }

    /// One finished M0 job written the way M0 wrote it: a PASSED unit with facts, a testcase, the result row, three
    /// events and a subscriber at cursor 1.
    private JobId seedV1Job() {
        var recipe = Json.write(RecipeDto.from(Recipe.mvn(List.of("verify")).unwrap())).unwrap();
        var source = Json.write(new SourceDto.Pushed(none(), some(TestJobs.sha().value()))).unwrap();
        var id = Sql.query(raw.executor(),
                           "INSERT INTO jobs (submitted_by, priority, source, source_sha, recipe, recipe_kind, dedup_key, state, finished_at) "
                           + "VALUES ('v2174', 0, $1::jsonb, $2, $3::jsonb, 'mvn', $4, 'PASSED', now()) RETURNING id",
                           source,
                           TestJobs.sha().value(),
                           recipe,
                           TestJobs.key().value())
                    .await(PgFixture.WAIT)
                    .unwrap()
                    .index(0)
                    .getLong("id");

        execute("INSERT INTO hosts (name, address, memory_budget_mb) VALUES ('bigboy', 'localhost', 20480)").unwrap();
        execute("INSERT INTO units (job_id, name, kind, host, state, reason, args, requests_tests, memory_mb, timeout_s, started_at, ended_at, rc, marker_epoch_ns) "
                + "VALUES (" + id + ", 'main', 'MVN', 'bigboy', 'PASSED', NULL, '[[\"mvn\",\"verify\"]]', true, 6144, 3600, now(), now(), 0, 1)").unwrap();
        execute("INSERT INTO unit_facts (unit_id, phase, facts) SELECT id, 'start', '{}' FROM units").unwrap();
        execute("INSERT INTO testcases (job_id, unit_id, class, name, outcome, time_s) SELECT job_id, id, 'a.ATest', 'one', 'passed', 0.1 FROM units").unwrap();
        execute("INSERT INTO results (job_id, verdict, counts, record) VALUES (" + id + ", 'PASSED', '{}', '{\"job\": " + id + ", \"verdict\": \"PASSED\"}')")
            .unwrap();
        execute("INSERT INTO events (job_id, kind, payload) VALUES (" + id + ", 'queued', '{}'), (" + id + ", 'started', '{}'), (" + id
                + ", 'finished', '{}')").unwrap();
        execute("INSERT INTO subscribers (name, cursor) VALUES ('m0.sub', 1)").unwrap();
        return JobId.jobId(id).unwrap();
    }

    private List<String> rowCounts() {
        return List.of("jobs", "units", "unit_facts", "testcases", "results", "events", "subscribers", "refusals", "hosts")
                   .stream()
                   .map(table -> table + "=" + text("SELECT count(*)::text AS v FROM " + table))
                   .toList();
    }

    private Result<?> execute(String sql) {
        return raw.inTransaction(tx -> Sql.execute(tx, sql)).await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getString("v");
    }
}
