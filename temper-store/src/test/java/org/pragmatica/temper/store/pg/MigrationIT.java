package org.pragmatica.temper.store.pg;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.store.api.StoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// `MigrationRunner` against real Postgres: V1 and V2 apply once, a second start is a no-op, a changed file aborts; a
/// non-contiguous build, a database ahead of the build and a hole below the highest applied version abort too.
@Tag("pg")
class MigrationIT {
    @Test
    void connect_appliesOnce_thenIsANoOp_andDetectsTampering() {
        var config = PgFixture.freshDatabase();
        var raw = PgFixture.db(config);

        Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
        Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
        assertThat(count(raw, "SELECT count(*) AS n FROM schema_migrations")).isEqualTo(MigrationRunner.MIGRATIONS.size());
        assertThat(count(raw, "SELECT count(*) AS n FROM refusals")).isEqualTo(2);

        raw.executor().completeQuery("UPDATE schema_migrations SET sha256 = 'x' WHERE version = 1").await(PgFixture.WAIT).unwrap();
        Postgres.connect(config, Judge.UNJUDGED)
             .await(PgFixture.WAIT)
             .onSuccess(_ -> fail("expected MigrationTampered"))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.MigrationTampered.FACTORY.apply(1)));
        raw.close().await(PgFixture.WAIT);
    }

    @Test
    void connect_refusesMigrationNotInBuild_whenTheDatabaseIsAhead() {
        var config = PgFixture.freshDatabase();
        var raw = PgFixture.db(config);
        var ahead = MigrationRunner.MIGRATIONS.size() + 1;

        Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
        raw.executor().completeQuery("INSERT INTO schema_migrations (version, name, sha256) VALUES ($1, 'ahead', 'x')", ahead)
           .await(PgFixture.WAIT).unwrap();
        Postgres.connect(config, Judge.UNJUDGED)
             .await(PgFixture.WAIT)
             .onSuccess(_ -> fail("expected MigrationNotInBuild"))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.MigrationNotInBuild.FACTORY.apply(ahead)));
        raw.close().await(PgFixture.WAIT);
    }

    @Test
    void connect_refusesMigrationOutOfOrder_whenAnAppliedVersionHasAHoleBelowIt() {
        var config = PgFixture.freshDatabase();
        var raw = PgFixture.db(config);
        var highest = MigrationRunner.MIGRATIONS.getLast().version();

        Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap().close();
        raw.executor().completeQuery("DELETE FROM schema_migrations WHERE version = 2").await(PgFixture.WAIT).unwrap();
        Postgres.connect(config, Judge.UNJUDGED)
             .await(PgFixture.WAIT)
             .onSuccess(_ -> fail("expected MigrationOutOfOrder"))
             .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.MigrationOutOfOrder.FACTORY.apply(2, highest)));
        assertThat(count(raw, "SELECT count(*) AS n FROM schema_migrations")).isEqualTo(MigrationRunner.MIGRATIONS.size() - 1);
        raw.close().await(PgFixture.WAIT);
    }

    /// A gapped build is refused before the database is touched: not even `schema_migrations` is created.
    @Test
    void migrate_refusesMigrationGap_beforeTouchingTheDatabase() {
        var config = PgFixture.freshDatabase();
        var raw = PgFixture.db(config);
        var gapped = List.of(MigrationRunner.load(MigrationRunner.MIGRATIONS.get(0)).unwrap(),
                             MigrationRunner.load(MigrationRunner.MIGRATIONS.get(2)).unwrap());

        MigrationRunner.migrate(raw, gapped)
                       .await(PgFixture.WAIT)
                       .onSuccess(_ -> fail("expected MigrationGap"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.MigrationGap.FACTORY.apply(2, 2, 3)));
        assertThat(count(raw, "SELECT count(*) AS n FROM pg_tables WHERE schemaname = 'public'")).isZero();
        raw.close().await(PgFixture.WAIT);
    }

    private static long count(Db db, String sql) {
        return db.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getLong("n");
    }
}
