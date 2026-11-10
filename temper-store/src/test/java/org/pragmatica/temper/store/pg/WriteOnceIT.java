package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.postgres.SqlError;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.report.Outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Write-once `results` and `testcases` (spec §5.4, §10): UPDATE, DELETE and TRUNCATE are rejected by triggers
/// with SQLSTATE 23000; the only DELETE path is retention's `SET LOCAL temper.retention = 'on'`. Every case gets its
/// own freshly completed job, so a mutation one case leaks cannot pass or fail another case.
@Tag("pg")
class WriteOnceIT {
    private static StoreHarness harness;
    private JobId job;

    @BeforeAll
    static void setUp() {
        harness = new StoreHarness();
    }

    @BeforeEach
    void freshJob() {
        job = harness.run(TestJobs.bySha(TestJobs.key(), false),
                          TestJobs.exited(0),
                          List.of(TestJobs.row("a.ATest", "one", Outcome.PASSED), TestJobs.row("a.ATest", "two", Outcome.PASSED)));
    }

    @AfterAll
    static void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE results SET verdict = 'FAILED' WHERE job_id = %d",
                            "UPDATE results SET record = '{}'::jsonb WHERE job_id = %d",
                            "DELETE FROM results WHERE job_id = %d",
                            "TRUNCATE results CASCADE",
                            "UPDATE testcases SET outcome = 'failed' WHERE job_id = %d",
                            "DELETE FROM testcases WHERE job_id = %d",
                            "TRUNCATE testcases",
                            "TRUNCATE jobs CASCADE"})
    void writeOnce_rejectsMutation(String template) {
        var outcome = execute(template.formatted(job.value()));

        assertThat(outcome.isFailure()).as(template).isTrue();
        outcome.onFailure(cause -> assertWriteOnceRejection(template, cause));
        assertThat(count("results", job)).isEqualTo(1);
        assertThat(count("testcases", job)).isEqualTo(2);
        assertThat(harness.store.results().result(job).await(PgFixture.WAIT).unwrap().map(record -> record.verdict()).or(""))
            .isEqualTo("PASSED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"testcases", "results"})
    void retentionPath_mayDelete(String table) {
        var other = harness.run(TestJobs.bySha(TestJobs.key(), false),
                                TestJobs.exited(0),
                                List.of(TestJobs.row("b.BTest", "one", Outcome.PASSED)));
        var before = count(table, other);
        var deleted = harness.raw.inTransaction(tx -> tx.completeQuery("SET LOCAL temper.retention = 'on'")
                                                        .flatMap(_ -> tx.completeQuery("DELETE FROM " + table + " WHERE job_id = $1",
                                                                                       other.value())))
                                 .await(PgFixture.WAIT);

        assertThat(deleted.isSuccess()).as(deleted.toString()).isTrue();
        assertThat(before).isPositive();
        assertThat(count(table, other)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"off", "yes", "ON"})
    void retentionFlag_mustBeExactlyOn(String value) {
        var outcome = harness.raw.inTransaction(tx -> tx.completeQuery("SET LOCAL temper.retention = '" + value + "'")
                                                        .flatMap(_ -> tx.completeQuery("DELETE FROM results WHERE job_id = $1",
                                                                                       job.value())))
                                 .await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).isTrue();
        assertThat(count("results", job)).isEqualTo(1);
    }

    private static Result<?> execute(String sql) {
        return harness.raw.inTransaction(tx -> Sql.execute(tx, sql)).await(PgFixture.WAIT);
    }

    private static long count(String table, JobId owner) {
        return harness.raw.executor()
                          .completeQuery("SELECT count(*) AS n FROM " + table + " WHERE job_id = $1", owner.value())
                          .await(PgFixture.WAIT)
                          .unwrap()
                          .index(0)
                          .getLong("n");
    }

    /// SQLSTATE 23000 in `code()` (the released postgres-async 1.0.0-rc4 fixed the #2181 swap into `level()`); the error
    /// class pins integrity_constraint_violation.
    private static void assertWriteOnceRejection(String sql, Cause cause) {
        var origin = cause.source().or(cause);

        assertThat(origin).as(sql).isInstanceOf(SqlError.ServerErrorIntegrityConstraintViolation.class);
        var response = ((SqlError.ServerError) origin).response();

        assertThat(response.code()).as(sql).isEqualTo("23000");
        assertThat(response.message()).as(sql).contains("is forbidden (write-once)");
    }
}
