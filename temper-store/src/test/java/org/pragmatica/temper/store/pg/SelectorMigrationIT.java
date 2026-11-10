package org.pragmatica.temper.store.pg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.domain.gate.Judge;
import org.pragmatica.temper.store.api.DbConfig;

import static org.assertj.core.api.Assertions.assertThat;

/// V7 (m2-design §2.3, WP-C) on a database migrated up to the migration before it, holding untyped selectors: under
/// R-12 they are reset to the empty set (every job), not converted, and the subscriber then reads through the store with
/// its cursor unchanged. The migrations before V7 are taken from `MigrationRunner.MIGRATIONS`, never listed here.
@Tag("pg")
class SelectorMigrationIT {
    private static final int V7 = 7;
    private DbConfig config;
    private Db raw;

    @BeforeEach
    void setUp() {
        config = PgFixture.freshDatabase();
        raw = PgFixture.db(config);
        MigrationRunner.migrate(raw,
                                MigrationRunner.MIGRATIONS.stream()
                                                          .filter(migration -> migration.version() < V7)
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
    void v7_resetsUntypedSelectors_keepsCursorsAndEmptyRows() {
        execute("INSERT INTO subscribers (name, cursor, selectors) VALUES ('cto', 41, '[\"4711\", \"main\"]'), ('all', 7, '[]')").unwrap();

        try (var store = Postgres.connect(config, Judge.UNJUDGED).await(PgFixture.WAIT).unwrap()) {
            var cto = store.events().subscriber("cto").await(PgFixture.WAIT).unwrap();
            var all = store.events().subscriber("all").await(PgFixture.WAIT).unwrap();

            assertThat(cto.selectors()).as("reset, not converted (R-12)").isEmpty();
            assertThat(cto.cursor()).isEqualTo(41);
            assertThat(all.selectors()).isEmpty();
            assertThat(all.cursor()).isEqualTo(7);
            assertThat(text("SELECT count(*)::text AS v FROM schema_migrations WHERE version = " + V7)).isEqualTo("1");
        }
    }

    private Result<?> execute(String sql) {
        return raw.inTransaction(tx -> Sql.execute(tx, sql)).await(PgFixture.WAIT);
    }

    private String text(String sql) {
        return raw.executor().completeQuery(sql).await(PgFixture.WAIT).unwrap().index(0).getString("v");
    }
}
