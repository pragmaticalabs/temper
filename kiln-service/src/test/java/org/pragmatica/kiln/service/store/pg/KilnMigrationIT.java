package org.pragmatica.kiln.service.store.pg;

import java.util.List;

import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStoreError;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// The copied [MigrationRunner] against real Postgres (kiln-f1-design §7.1 WP5): an empty schema gets every §2 table
/// from V1, a second start is a no-op, two kilns starting at once apply V1 once (lock 7171); a changed file, a database
/// ahead of the build, a gapped build and a failing file are refused, the last two leaving the schema untouched.
@Tag("pg")
class KilnMigrationIT {
    private static final List<String> TABLES = List.of("base_run",
                                                       "base_tip",
                                                       "change",
                                                       "change_floor",
                                                       "check_cell",
                                                       "checkout",
                                                       "checkout_derived",
                                                       "feed_check",
                                                       "feed_issue",
                                                       "feed_run",
                                                       "feed_settle",
                                                       "gh_cursor",
                                                       "gh_etag",
                                                       "gh_issue",
                                                       "host_pass",
                                                       "kiln_epoch",
                                                       "non_git",
                                                       "pipeline_attempt",
                                                       "pipeline_pr",
                                                       "pipeline_run",
                                                       "pr",
                                                       "pr_mirror",
                                                       "required_checks",
                                                       "required_list_history",
                                                       "schema_migrations",
                                                       "source_state",
                                                       "stash",
                                                       "temper_digest",
                                                       "temper_gate",
                                                       "work_event",
                                                       "work_item");

    @Test
    void emptySchema_getsEveryTable_andASecondStartIsANoOp() {
        var config = KilnPgFixture.freshDatabase();
        var raw = KilnPgFixture.db(config);

        assertThat(tables(raw)).isEmpty();
        connectAndClose(config);
        connectAndClose(config);
        assertThat(tables(raw)).containsExactlyElementsOf(TABLES);
        assertThat(count(raw, "SELECT count(*) AS n FROM schema_migrations")).isEqualTo(MigrationRunner.MIGRATIONS.size());
        assertThat(count(raw, "SELECT count(*) AS n FROM schema_migrations WHERE version = 1 AND name = 'f1_schema'")).isEqualTo(1);
        StoreFixtures.await(raw.close());
    }

    /// Without the advisory lock the two transactions race on `CREATE TABLE` and one start fails.
    @Test
    void concurrentStarts_applyV1Once() {
        var config = KilnPgFixture.freshDatabase();
        var raw = KilnPgFixture.db(config);
        var starts = List.of(PgKilnStore.connect(config,
                                                 StoreFixtures.REPO,
                                                 StoreFixtures.highWater(),
                                                 ChangeDiff.CHANGES),
                             PgKilnStore.connect(config,
                                                 StoreFixtures.REPO,
                                                 StoreFixtures.highWater(),
                                                 ChangeDiff.CHANGES),
                             PgKilnStore.connect(config,
                                                 StoreFixtures.REPO,
                                                 StoreFixtures.highWater(),
                                                 ChangeDiff.CHANGES));
        var stores = Promise.allOf(starts).await(KilnPgFixture.WAIT).unwrap();

        assertThat(stores).allSatisfy(result -> assertThat(result.isSuccess()).as(result.toString())
                                                          .isTrue());
        assertThat(count(raw, "SELECT count(*) AS n FROM schema_migrations")).isEqualTo(MigrationRunner.MIGRATIONS.size());
        stores.forEach(result -> result.onSuccess(store -> StoreFixtures.await(store.close())));
        StoreFixtures.await(raw.close());
    }

    @Test
    void changedFile_isMigrationTampered() {
        var config = KilnPgFixture.freshDatabase();
        var raw = KilnPgFixture.db(config);

        connectAndClose(config);
        StoreFixtures.await(Sql.execute(raw.executor(), "UPDATE schema_migrations SET sha256 = 'x' WHERE version = 1"));
        PgKilnStore.connect(config,
                            StoreFixtures.REPO,
                            StoreFixtures.highWater(),
                            ChangeDiff.CHANGES)
                   .await(KilnPgFixture.WAIT)
                   .onSuccess(_ -> fail("expected MigrationTampered"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(KilnStoreError.MigrationTampered.FACTORY.apply(1)));
        StoreFixtures.await(raw.close());
    }

    @Test
    void databaseAheadOfTheBuild_isMigrationNotInBuild() {
        var config = KilnPgFixture.freshDatabase();
        var raw = KilnPgFixture.db(config);
        var ahead = MigrationRunner.MIGRATIONS.size() + 1;

        connectAndClose(config);
        StoreFixtures.await(Sql.execute(raw.executor(),
                                        "INSERT INTO schema_migrations (version, name, sha256) VALUES ($1, 'ahead', 'x')",
                                        ahead));
        PgKilnStore.connect(config,
                            StoreFixtures.REPO,
                            StoreFixtures.highWater(),
                            ChangeDiff.CHANGES)
                   .await(KilnPgFixture.WAIT)
                   .onSuccess(_ -> fail("expected MigrationNotInBuild"))
                   .onFailure(cause -> assertThat(cause).isEqualTo(KilnStoreError.MigrationNotInBuild.FACTORY.apply(ahead)));
        StoreFixtures.await(raw.close());
    }

    /// A gapped build is refused before the database is touched: not even `schema_migrations` is created.
    @Test
    void gappedBuild_isMigrationGap_beforeTouchingTheDatabase() {
        var config = KilnPgFixture.freshDatabase();
        var raw = KilnPgFixture.db(config);
        var gapped = List.of(MigrationRunner.load(MigrationRunner.MIGRATIONS.getFirst()).unwrap(),
                             new MigrationRunner.Loaded(new MigrationRunner.Migration(3, "f1_later"),
                                                        "CREATE TABLE later (id int)",
                                                        "sha3"));

        MigrationRunner.migrate(raw, gapped)
                       .await(KilnPgFixture.WAIT)
                       .onSuccess(_ -> fail("expected MigrationGap"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(KilnStoreError.MigrationGap.FACTORY.apply(2, 2, 3)));
        assertThat(tables(raw)).isEmpty();
        StoreFixtures.await(raw.close());
    }

    /// All or nothing: a failing V2 rolls V1 back with it, so the schema stays empty.
    @Test
    void failingFile_leavesTheSchemaAsItWas() {
        var config = KilnPgFixture.freshDatabase();
        var raw = KilnPgFixture.db(config);
        var failing = List.of(MigrationRunner.load(MigrationRunner.MIGRATIONS.getFirst()).unwrap(),
                              new MigrationRunner.Loaded(new MigrationRunner.Migration(2, "f1_broken"),
                                                         "CREATE TABLE pr (number int)",
                                                         "sha2"));

        MigrationRunner.migrate(raw, failing).await(KilnPgFixture.WAIT).onSuccess(_ -> fail("expected V2 to fail"));
        assertThat(tables(raw)).isEmpty();
        StoreFixtures.await(raw.close());
    }

    private static void connectAndClose(DbConfig config) {
        StoreFixtures.await(StoreFixtures.await(KilnPostgres.connect(config,
                                                                     StoreFixtures.REPO,
                                                                     StoreFixtures.highWater())).close());
    }

    private static List<String> tables(Db db) {
        return Sql.rows(StoreFixtures.await(Sql.query(db.executor(),
                                                      "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")))
                  .stream()
                  .map(row -> row.getString("tablename"))
                  .toList();
    }

    private static long count(Db db, String sql) {
        return StoreFixtures.await(Sql.query(db.executor(),
                                             sql))
                            .index(0)
                            .getLong("n");
    }
}
