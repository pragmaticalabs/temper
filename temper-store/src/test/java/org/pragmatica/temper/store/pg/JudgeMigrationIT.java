package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Recipe;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.report.Outcome;
import org.pragmatica.temper.protocol.api.RecipeDto;
import org.pragmatica.temper.protocol.api.SourceDto;
import org.pragmatica.temper.store.api.DbConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// V6 (m2-design §1.7, rulings R-M2-10 and R-M2-11) on a database holding rows, migrated to every version below it, and the judge every write names
/// after it.
///
/// The migration adds `jobs.judge` and `results.judge` with a constant default and drops the default: Postgres 16 keeps
/// the value in the catalog, so the existing rows read `pre-judge` without an UPDATE. That is proven, not assumed
/// (the design's [U]): the results row keeps its `xmin` (an UPDATE writes a new row version, and the write-once trigger
/// would refuse it, failing the migration), and its record is the same text. `units.granted_judge` starts NULL on every
/// existing row (R-12, no conversion), so no job granted before V6 is ever judged across a deploy. Probe: write
/// `results.judge` by an UPDATE after the INSERT → [#finish_writesTheFinishingJudge_inTheResultsInsert] fails on the
/// trigger.
@Tag("pg")
class JudgeMigrationIT {
    /// The judge migration's version; every migration below it is applied first, whatever lands beneath it.
    private static final int JUDGE_MIGRATION = MigrationRunner.MIGRATIONS.stream()
                                                                         .filter(migration -> migration.name().equals("m2_judge"))
                                                                         .findFirst()
                                                                         .orElseThrow()
                                                                         .version();
    private static final Judge JUDGE = Judge.judge("1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d").unwrap();

    private DbConfig config;
    private Db raw;

    @BeforeEach
    void setUp() {
        config = PgFixture.freshDatabase();
        raw = PgFixture.db(config);
        MigrationRunner.migrate(raw,
                                MigrationRunner.MIGRATIONS.stream()
                                                          .filter(migration -> migration.version() < JUDGE_MIGRATION)
                                                          .map(migration -> MigrationRunner.load(migration).unwrap())
                                                          .toList())
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
    void judgeMigration_onEarlierRows_readsPreJudge_withoutAnyUpdateOfResults() {
        execute("INSERT INTO hosts (name, address, memory_budget_mb) VALUES ('bigboy', 'localhost', 20480)").unwrap();
        var finished = seedJob("PASSED", "PASSED", true);
        var queued = seedJob("QUEUED", "QUEUED", false);
        var granted = seedJob("QUEUED", "GRANTED", true);
        execute("INSERT INTO results (job_id, verdict, counts, record) VALUES (" + finished + ", 'PASSED', '{}', "
                + "'{\"job\": " + finished + ", \"verdict\": \"PASSED\"}')").unwrap();
        var xminBefore = text("SELECT xmin::text AS v FROM results");
        var recordBefore = text("SELECT record::text AS v FROM results");

        try (var store = Postgres.connect(config, JUDGE).await(PgFixture.WAIT).unwrap()) {
            assertThat(store).isNotNull();
        }

        assertThat(text("SELECT string_agg(version::text, ',' ORDER BY version) AS v FROM schema_migrations")).as("every listed migration").isEqualTo(String.join(",", MigrationRunner.MIGRATIONS.stream().map(migration -> String.valueOf(migration.version())).toList()));
        assertThat(text("SELECT xmin::text AS v FROM results")).as("no new row version: never UPDATEd").isEqualTo(xminBefore);
        assertThat(text("SELECT record::text AS v FROM results")).isEqualTo(recordBefore);
        assertThat(text("SELECT string_agg(DISTINCT judge, ',') AS v FROM jobs")).isEqualTo("pre-judge");
        assertThat(text("SELECT judge AS v FROM results")).isEqualTo("pre-judge");
        assertThat(grantedJudge(finished)).as("granted before V6: no conversion (R-12)").isNull();
        assertThat(grantedJudge(granted)).as("granted before V6, not started").isNull();
        assertThat(grantedJudge(queued)).as("never granted").isNull();
        assertThat(text("SELECT count(*)::text AS v FROM information_schema.columns WHERE column_default IS NOT NULL "
                        + "AND ((table_name = 'jobs' AND column_name = 'judge') OR (table_name = 'results' AND column_name = 'judge'))"))
            .as("the defaults are dropped").isEqualTo("0");
        assertThat(execute("UPDATE results SET judge = 'unjudged'").isFailure()).as("write-once survives V6").isTrue();
        assertThat(text("SELECT judge AS v FROM results")).isEqualTo("pre-judge");
        assertThat(execute("INSERT INTO results (job_id, verdict, counts, record) VALUES (" + queued
                           + ", 'PASSED', '{}', '{}')").isFailure()).as("an insert must name its judge").isTrue();
        assertThat(execute("UPDATE jobs SET judge = 'not-a-judge' WHERE id = " + queued).isFailure()).as("CHECK").isTrue();
        assertThat(execute("UPDATE units SET granted_judge = 'Pre-Judge'").isFailure()).as("CHECK").isTrue();
        assertThat(execute("UPDATE units SET granted_judge = 'pre-judge'").isFailure()).as("no unit is granted pre-judge").isTrue();
    }

    /// `jobs.judge` is the accepting store's, on accepted and REFUSED jobs alike.
    @Test
    void submit_andRecordRefused_writeTheSubmittingJudge() {
        try (var harness = new StoreHarness(PgFixture.freshDatabase(), JUDGE)) {
            var accepted = harness.submit(TestJobs.bySha(TestJobs.key(), false));
            var refused = harness.store.jobs()
                                       .recordRefused(TestJobs.bySha(TestJobs.key(), false),
                                                      Refusal.refusal(RefusalCode.R_M2, "-Dmaven.repo.local=/x"))
                                       .await(PgFixture.WAIT)
                                       .unwrap();

            assertThat(judgeOf(harness, "jobs", "id", accepted)).isEqualTo(JUDGE.value());
            assertThat(judgeOf(harness, "jobs", "id", refused)).isEqualTo(JUDGE.value());
        }
    }

    /// The grant names the granting store's judge on the unit; a requeue clears it with the attempt (R-M2-11).
    @Test
    void grant_writesTheGrantJudge_andARequeueClearsIt() {
        try (var harness = new StoreHarness(PgFixture.freshDatabase(), JUDGE)) {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            assertThat(unitJudge(harness, job)).as("queued").isNull();
            harness.grantHead();
            assertThat(unitJudge(harness, job)).isEqualTo(JUDGE.value());
            harness.store.units().requeue(TestJobs.HOST, TestJobs.main(job), 1).await(PgFixture.WAIT).unwrap();
            assertThat(unitJudge(harness, job)).as("requeued").isNull();
            harness.grantHead();
            assertThat(unitJudge(harness, job)).as("granted again").isEqualTo(JUDGE.value());
        }
    }

    /// `results.judge` and the record's `judge` are the finishing store's, written by the one INSERT; a job granted and
    /// finished under one judge carries no `judge-changed`.
    @Test
    void finish_writesTheFinishingJudge_inTheResultsInsert() {
        try (var harness = new StoreHarness(PgFixture.freshDatabase(), JUDGE)) {
            var job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                                  TestJobs.exited(0),
                                  List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED)));
            var record = harness.store.results().result(job).await(PgFixture.WAIT).unwrap().unwrap();

            assertThat(judgeOf(harness, "results", "job_id", job)).isEqualTo(JUDGE.value());
            assertThat(record.judge()).isEqualTo(some(JUDGE.value()));
            assertThat(record.annotations()).noneMatch(annotation -> annotation.startsWith(Judge.CHANGED_ANNOTATION));
            assertThat(harness.store.results().recordJson(job).await(PgFixture.WAIT).unwrap().unwrap())
                .containsPattern("\"judge\":\\s*\"" + JUDGE.value() + "\"");
        }
    }

    /// A cancel that finishes the job at once (nothing active) writes its result with the cancelling store's judge.
    @Test
    void cancelNow_writesTheCancellingJudge() {
        try (var harness = new StoreHarness(PgFixture.freshDatabase(), JUDGE)) {
            var job = harness.submit(TestJobs.bySha(TestJobs.key(), false));

            harness.store.jobs().requestCancel(job).await(PgFixture.WAIT).unwrap();

            assertThat(judgeOf(harness, "results", "job_id", job)).isEqualTo(JUDGE.value());
        }
    }

    /// A supersede that finishes its victim at once (nothing active) writes the victim's result with the superseding
    /// store's judge.
    @Test
    void supersedeCancel_writesTheSupersedingJudge() {
        try (var harness = new StoreHarness(PgFixture.freshDatabase(), JUDGE)) {
            var ref = "judge/supersede";
            var victim = harness.submit(TestJobs.gateByRef(ref, TestJobs.sha(), false));

            harness.submit(TestJobs.gateByRef(ref, TestJobs.sha(), false));

            assertThat(judgeOf(harness, "results", "job_id", victim)).isEqualTo(JUDGE.value());
        }
    }

    /// A job as the schema below the judge migration holds it (V5's `recipe_canonical` included): the gate recipe, its
    /// unit in `unitState`, granted when `granted`.
    private long seedJob(String jobState, String unitState, boolean granted) {
        var recipe = Json.write(RecipeDto.from(Recipe.gate())).unwrap();
        var sha = TestJobs.sha().value();
        var source = Json.write(new SourceDto.Pushed(none(), some(sha))).unwrap();
        var id = Sql.query(raw.executor(),
                           "INSERT INTO jobs (submitted_by, priority, source, source_sha, recipe, recipe_kind, recipe_canonical, dedup_key, state) "
                           + "VALUES ('v2174', 0, $1::jsonb, $2, $3::jsonb, 'gate', $6, $4, $5) RETURNING id",
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

        execute("INSERT INTO units (job_id, name, kind, host, state, args, requests_tests, memory_mb, timeout_s, granted_at) "
                + "VALUES (" + id + ", 'gate', 'GATE_UNSHARDED', " + (granted
                                                                       ? "'bigboy'"
                                                                       : "NULL") + ", '" + unitState
                + "', '[[\"mvn\",\"install\"]]', true, 16384, 10800, " + (granted
                                                                            ? "now()"
                                                                            : "NULL") + ")").unwrap();
        return id;
    }

    private String grantedJudge(long job) {
        return text("SELECT granted_judge AS v FROM units WHERE job_id = " + job);
    }

    private static String judgeOf(StoreHarness harness, String table, String key, JobId job) {
        return harness.raw.executor()
                          .completeQuery("SELECT judge FROM " + table + " WHERE " + key + " = $1", job.value())
                          .await(PgFixture.WAIT)
                          .unwrap()
                          .index(0)
                          .getString("judge");
    }

    private static String unitJudge(StoreHarness harness, JobId job) {
        return harness.raw.executor()
                          .completeQuery("SELECT granted_judge FROM units WHERE job_id = $1", job.value())
                          .await(PgFixture.WAIT)
                          .unwrap()
                          .index(0)
                          .getString("granted_judge");
    }

    private Result<?> execute(String sql) {
        return raw.inTransaction(tx -> Sql.execute(tx, sql)).await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getString("v");
    }
}
