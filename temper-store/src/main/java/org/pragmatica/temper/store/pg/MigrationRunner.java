package org.pragmatica.temper.store.pg;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.postgres.PgResultSet;
import org.pragmatica.postgres.net.Transaction;
import org.pragmatica.temper.protocol.digest.Digests;
import org.pragmatica.temper.store.api.StoreError;


/// Applies `db/migration/V<n>__<name>.sql` at controller start (m0-design §5.1). Everything runs in ONE
/// transaction under `pg_advisory_xact_lock(7070)`, so two starting controllers never apply a file twice, and a
/// failing file leaves the schema exactly as it was (Postgres DDL is transactional). An applied file whose sha256
/// changed aborts startup with `StoreError.MigrationTampered`. Files apply strictly in version order: a build whose
/// migrations are not contiguous from V1 (`MigrationGap`), a database carrying a version the build lacks
/// (`MigrationNotInBuild`), and a pending version below the highest applied one (`MigrationOutOfOrder`) all abort
/// startup, so a deploy between two branch merges never applies V12 before V11.
sealed interface MigrationRunner {
    long LOCK = 7070L;

    /// Every migration, in order. A new file is added here; `MigrationRunnerTest` fails when a file under
    /// `db/migration` is missing from this list.
    List<Migration> MIGRATIONS = List.of(new Migration(1, "m0_schema"),
                                         new Migration(2, "m1"),
                                         new Migration(3, "m1_manifest"),
                                         new Migration(4, "m1_unknown"),
                                         new Migration(5, "m1_supersede_recipe"),
                                         new Migration(6, "m2_judge"),
                                         new Migration(7, "m2_selectors"),
                                         new Migration(8, "m2_known"),
                                         new Migration(9, "m2_rerun"),
                                         new Migration(10, "m3_tasks"),
                                         new Migration(11, "m4_hosts"),
                                         new Migration(12, "m4_host_recovery"),
                                         new Migration(13, "t1_fleet"),
                                         new Migration(14, "t2_merge_gate"),
                                         new Migration(15, "t3_tickets"),
                                         new Migration(16, "gh_identities"),
                                         new Migration(17, "s1_alarms"),
                                         new Migration(18, "i111_grant_fence"));

    record Migration(int version, String name) {
        public String resource() {
            return "db/migration/V" + version + "__" + name + ".sql";
        }
    }

    record Loaded(Migration migration, String text, String sha256) {}

    static Promise<Unit> migrate(Db db) {
        return Result.allOf(MIGRATIONS.stream().map(MigrationRunner::load).toList())
                     .async()
                     .flatMap(loaded -> migrate(db, loaded));
    }

    /// Applies the pending migrations of `loaded` in one transaction. [#migrate(Db)] passes [#MIGRATIONS];
    /// `MigrationAtomicityIT` passes a deliberately failing set to pin the all-or-nothing guarantee.
    static Promise<Unit> migrate(Db db, List<Loaded> loaded) {
        return contiguous(loaded).async()
                         .flatMap(_ -> db.inTransaction(tx -> apply(tx, loaded)));
    }

    /// `loaded` itself, when it is V1, V2, … in list order without a hole; checked before the database is touched.
    static Result<List<Loaded>> contiguous(List<Loaded> loaded) {
        return Option.from(IntStream.range(0,
                                           loaded.size())
                                    .filter(index -> version(loaded.get(index)) != index + 1)
                                    .boxed()
                                    .findFirst())
                     .map(index -> StoreError.MigrationGap.FACTORY.apply(index + 1,
                                                                         index + 1,
                                                                         version(loaded.get(index)))
                                                                  .<List<Loaded>> result())
                     .or(() -> Result.success(loaded));
    }

    static Result<Loaded> load(Migration migration) {
        return read(migration.resource()).flatMap(text -> loaded(migration, text));
    }

    private static Result<Loaded> loaded(Migration migration, String text) {
        return Digests.sha256Hex(text).map(sha -> new Loaded(migration, text, sha));
    }

    private static Result<String> read(String resource) {
        return Option.option(MigrationRunner.class.getClassLoader().getResourceAsStream(resource))
                     .toResult(StoreError.MigrationMissing.FACTORY.apply(resource))
                     .flatMap(in -> readAndClose(resource, in));
    }

    /// Reads, then closes; a failure of either is the resource's failure.
    private static Result<String> readAndClose(String resource, InputStream in) {
        return Result.all(Result.lift(unreadable(resource),
                                      in::readAllBytes),
                          Result.lift(unreadable(resource),
                                      in::close))
                     .map((bytes, _) -> new String(bytes, StandardCharsets.UTF_8));
    }

    private static Fn1<Cause, Throwable> unreadable(String resource) {
        return thrown -> StoreError.MigrationUnreadable.FACTORY.apply(resource, Causes.fromThrowable(thrown));
    }

    private static Promise<Unit> apply(Transaction tx, List<Loaded> loaded) {
        return Sql.execute(tx,
                           "SELECT pg_advisory_xact_lock($1)",
                           LOCK)
                  .flatMap(_ -> Sql.execute(tx,
                                            "CREATE TABLE IF NOT EXISTS schema_migrations(version int PRIMARY KEY, name text NOT NULL, "
                                           + "sha256 text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())"))
                  .flatMap(_ -> Sql.query(tx, "SELECT version, sha256 FROM schema_migrations"))
                  .flatMap(resultSet -> pendingOf(loaded, resultSet))
                  .flatMap(pending -> applyAll(tx, pending));
    }

    private static Promise<List<Loaded>> pendingOf(List<Loaded> loaded, PgResultSet resultSet) {
        return pending(loaded, applied(resultSet)).async();
    }

    private static Promise<Unit> applyAll(Transaction tx, List<Loaded> pending) {
        return Sql.sequence(pending, migration -> applyOne(tx, migration)).mapToUnit();
    }

    private static Map<Integer, String> applied(PgResultSet resultSet) {
        return Sql.rows(resultSet)
                  .stream()
                  .collect(Collectors.toMap(row -> row.getInt("version"),
                                            row -> row.getString("sha256")));
    }

    /// The migrations still to apply, given the applied versions and their sha256. The first applied migration whose
    /// file changed, the first applied version `loaded` lacks, and the first pending version below the highest applied
    /// one each fail startup.
    static Result<List<Loaded>> pending(List<Loaded> loaded, Map<Integer, String> applied) {
        return untampered(loaded, applied).flatMap(_ -> allInBuild(loaded, applied))
                         .map(_ -> unapplied(loaded, applied))
                         .flatMap(unapplied -> afterApplied(unapplied, applied));
    }

    private static Result<Unit> untampered(List<Loaded> loaded, Map<Integer, String> applied) {
        return Option.from(loaded.stream().filter(migration -> changed(migration, applied)).findFirst())
                     .map(migration -> StoreError.MigrationTampered.FACTORY.apply(migration.migration().version()).<Unit> result())
                     .or(Result::unitResult);
    }

    private static boolean changed(Loaded migration, Map<Integer, String> applied) {
        return Option.option(applied.get(migration.migration().version()))
                     .filter(sha -> !sha.equals(migration.sha256()))
                     .isPresent();
    }

    private static Result<Unit> allInBuild(List<Loaded> loaded, Map<Integer, String> applied) {
        var versions = loaded.stream().map(MigrationRunner::version).collect(Collectors.toSet());

        return Option.from(applied.keySet().stream().sorted().filter(version -> !versions.contains(version)).findFirst())
                     .map(version -> StoreError.MigrationNotInBuild.FACTORY.apply(version).<Unit> result())
                     .or(Result::unitResult);
    }

    private static List<Loaded> unapplied(List<Loaded> loaded, Map<Integer, String> applied) {
        return loaded.stream()
                     .filter(migration -> !applied.containsKey(migration.migration().version()))
                     .toList();
    }

    private static Result<List<Loaded>> afterApplied(List<Loaded> unapplied, Map<Integer, String> applied) {
        var highest = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);

        return Option.from(unapplied.stream().filter(migration -> version(migration) < highest).findFirst())
                     .map(migration -> StoreError.MigrationOutOfOrder.FACTORY.apply(version(migration),
                                                                                    highest).<List<Loaded>> result())
                     .or(() -> Result.success(unapplied));
    }

    private static int version(Loaded migration) {
        return migration.migration()
                        .version();
    }

    private static Promise<Unit> applyOne(Transaction tx, Loaded migration) {
        return tx.completeScript(migration.text())
                 .mapError(StoreError.Database.FACTORY)
                 .flatMap(_ -> Sql.execute(tx,
                                           "INSERT INTO schema_migrations (version, name, sha256) VALUES ($1, $2, $3)",
                                           migration.migration().version(),
                                           migration.migration().name(),
                                           migration.sha256()));
    }

    record unused() implements MigrationRunner {}
}
