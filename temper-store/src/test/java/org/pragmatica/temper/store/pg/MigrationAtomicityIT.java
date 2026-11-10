package org.pragmatica.temper.store.pg;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.store.api.StoreError;
import org.pragmatica.temper.store.api.DbConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// [MigrationRunner] is all-or-nothing and serialised (m0-design §5.1): every pending file runs in ONE transaction
/// under `pg_advisory_xact_lock(7070)`. A failing set leaves the schema exactly as it was, and two controllers
/// starting on one fresh database both succeed with V1 applied once.
@Tag("pg")
class MigrationAtomicityIT {
    /// Test-only migration, numbered one past the last real one: real DDL on a new table and on a V1 table, then a
    /// runtime failure.
    private static final MigrationRunner.Loaded FAILING =
        new MigrationRunner.Loaded(new MigrationRunner.Migration(MigrationRunner.MIGRATIONS.stream().mapToInt(MigrationRunner.Migration::version).max().orElse(0) + 1,
                                                               "failing"),
                                   "CREATE TABLE partial_probe (id int);\n"
                                   + "ALTER TABLE jobs ADD COLUMN partial_probe int;\n"
                                   + "SELECT 1 / 0;\n",
                                   "test-only");
    private static final String TABLES = "SELECT string_agg(tablename, ',' ORDER BY tablename) AS v FROM pg_tables "
                                         + "WHERE schemaname = 'public'";
    private DbConfig config;
    private Db raw;

    @BeforeEach
    void setUp() {
        config = PgFixture.freshDatabase();
        raw = PgFixture.db(config);
    }

    @AfterEach
    void tearDown() {
        if (raw != null) {
            raw.close().await(PgFixture.WAIT);
        }
    }

    @Test
    void migrate_leavesNoSchema_whenFirstSetFails() {
        assertFailedOnDivision(migrate(withFailing()));
        assertThat(text(TABLES)).as("tables after a failed first migration").isNull();
        assertThat(count("SELECT count(*) AS n FROM pg_proc WHERE proname = 'temper_write_once'")).isZero();
    }

    @Test
    void migrate_leavesSchemaAndVersionUnchanged_whenLaterFileFails() {
        Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
        var tablesBefore = text(TABLES);

        assertFailedOnDivision(migrate(withFailing()));
        assertThat(text(TABLES)).isEqualTo(tablesBefore);
        assertThat(text("SELECT string_agg(version::text, ',' ORDER BY version) AS v FROM schema_migrations")).as("every listed migration").isEqualTo(String.join(",", MigrationRunner.MIGRATIONS.stream().map(migration -> String.valueOf(migration.version())).toList()));
        assertThat(count("SELECT count(*) AS n FROM information_schema.columns "
                         + "WHERE table_name = 'jobs' AND column_name = 'partial_probe'")).isZero();
    }

    /// Both runners are parked on the advisory lock (observed in `pg_locks`) before the holder releases it, so they
    /// really contend; with the lock they apply the migrations one after the other and the second finds nothing pending.
    @Test
    void migrate_appliesEachOnce_underTwoConcurrentRunners() {
        var release = Promise.<Unit> promise();
        var holder = raw.inTransaction(tx -> Sql.execute(tx, "SELECT pg_advisory_xact_lock($1)", MigrationRunner.LOCK)
                                                .flatMap(_ -> release));

        var first = PgFixture.db(config);
        var second = PgFixture.db(config);
        List<Result<Unit>> outcomes;

        try {
            awaitLocks(1, 0);
            var runs = List.of(MigrationRunner.migrate(first), MigrationRunner.migrate(second));

            awaitLocks(1, 2);
            release.succeed(unit());
            holder.await(PgFixture.WAIT).unwrap();
            outcomes = Promise.allOf(runs).await(PgFixture.WAIT).unwrap();
        } finally {
            release.succeed(unit());
            first.close().await(PgFixture.WAIT);
            second.close().await(PgFixture.WAIT);
        }
        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.isSuccess()).as(outcome.toString()).isTrue());
        assertThat(count("SELECT count(*) AS n FROM schema_migrations")).isEqualTo(MigrationRunner.MIGRATIONS.size());
        assertThat(count("SELECT count(*) AS n FROM refusals")).isEqualTo(2);
    }

    private Result<Unit> migrate(List<MigrationRunner.Loaded> loaded) {
        var db = PgFixture.db(config);
        var outcome = MigrationRunner.migrate(db, loaded).await(PgFixture.WAIT);

        db.close().await(PgFixture.WAIT);
        return outcome;
    }

    /// The failure is FAILING's last statement, so its DDL really ran inside the transaction before it failed.
    private static void assertFailedOnDivision(Result<Unit> outcome) {
        assertThat(outcome.isFailure()).as(outcome.toString()).isTrue();
        assertThat(outcome.toString()).contains("division by zero");
        outcome.onFailure(cause -> assertThat(cause).as("the script failure is wrapped as StoreError.Database")
                                                    .isInstanceOf(StoreError.Database.class));
    }

    /// Every listed migration then FAILING: contiguous, so the runner reaches the database instead of refusing a gap.
    private static List<MigrationRunner.Loaded> withFailing() {
        return Stream.concat(MigrationRunner.MIGRATIONS.stream().map(migration -> MigrationRunner.load(migration).unwrap()),
                             Stream.of(FAILING))
                     .toList();
    }

    /// Polls `pg_locks` until the migration lock has `granted` holders and `waiting` waiters in this database.
    private void awaitLocks(long granted, long waiting) {
        var deadline = System.nanoTime() + PgFixture.WAIT.nanos();

        while (!locksAre(granted, waiting)) {
            assertThat(System.nanoTime()).as("lock %d: %d granted, %d waiting", MigrationRunner.LOCK, granted, waiting)
                                         .isLessThan(deadline);
            pause();
        }
    }

    private boolean locksAre(long granted, long waiting) {
        var row = raw.executor()
                     .completeQuery("SELECT count(*) FILTER (WHERE granted) AS g, count(*) FILTER (WHERE NOT granted) AS w "
                                    + "FROM pg_locks WHERE locktype = 'advisory' AND classid = 0 AND objid::bigint = $1 AND objsubid = 1 "
                                    + "AND database = (SELECT oid FROM pg_database WHERE datname = current_database())",
                                    MigrationRunner.LOCK)
                     .await(PgFixture.WAIT)
                     .unwrap()
                     .index(0);

        return row.getLong("g") == granted && row.getLong("w") == waiting;
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long count(String sql) {
        return raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getLong("n");
    }

    private String text(String sql) {
        return raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getString("v");
    }
}
