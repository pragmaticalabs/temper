package org.pragmatica.kiln.service.store.pg;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import org.pragmatica.kiln.service.store.KilnStoreError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Applies `kiln/db/migration/V<n>__<name>.sql` at kiln start, copied from temper-store's runner with two changes
/// (kiln-f1-design §1): the resources live under `kiln/db/migration/`, so no temper migration can ever be found on
/// kiln's classpath, and the lock is `pg_advisory_xact_lock(7171)`, not temper's 7070. Everything runs in ONE
/// transaction under that lock, so two starting kilns never apply a file twice, and a failing file leaves the schema
/// exactly as it was (Postgres DDL is transactional). An applied file whose sha256 changed aborts startup with
/// `KilnStoreError.MigrationTampered`. Files apply strictly in version order: a build whose migrations are not
/// contiguous from V1 (`MigrationGap`), a database carrying a version the build lacks (`MigrationNotInBuild`), and a
/// pending version below the highest applied one (`MigrationOutOfOrder`) all abort startup.
sealed interface MigrationRunner {
    Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);
    long LOCK = 7171L;

    /// Every migration, in order. A new file is added here; `MigrationRunnerTest` fails when a file under
    /// `kiln/db/migration` is missing from this list.
    List<Migration> MIGRATIONS = List.of(new Migration(1, "f1_schema"),
                                         new Migration(2, "temper_gate_answer_sha"),
                                         new Migration(3, "ci_change_feed"),
                                         new Migration(4, "ci_feed_state"),
                                         new Migration(5, "work_ledger"),
                                         new Migration(6, "base_tip"),
                                         new Migration(7, "pipeline_baseline"));

    /// sha256 of the PRE-squash bytes of shipped migrations whose comments the public-history squash d3140d89 edited
    /// (the bytes at commit 4edb67d2). A database that applied those bytes carries these shas; an applied row equal to
    /// the legacy sha of its version is accepted, never rewritten. Any other mismatch is still tampering. Shipped files
    /// are immutable (`ShippedMigrationsPinTest`), so this list never grows.
    Map<Integer, String> LEGACY_SHA256 = Map.of(3,
                                                "46cf1140530e0296849643d3526a8b8d0c42aa855ee7b12263a2658f417d7236",
                                                6,
                                                "19a13d493bbdb11e5dc13e5db5e35def959ec254cc0dd183d27cc63242026745");

    record Migration(int version, String name) {
        public String resource() {
            return "kiln/db/migration/V" + version + "__" + name + ".sql";
        }
    }

    record Loaded(Migration migration, String text, String sha256) {}

    static Promise<Unit> migrate(Db db) {
        return Result.allOf(MIGRATIONS.stream().map(MigrationRunner::load).toList())
                     .async()
                     .flatMap(loaded -> migrate(db, loaded));
    }

    /// Applies the pending migrations of `loaded` in one transaction. [#migrate(Db)] passes [#MIGRATIONS];
    /// `KilnMigrationIT` passes a gapped and a deliberately failing set to pin the refusal and the all-or-nothing guarantee.
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
                     .map(index -> KilnStoreError.MigrationGap.FACTORY.apply(index + 1,
                                                                             index + 1,
                                                                             version(loaded.get(index)))
                                                                      .<List<Loaded>> result())
                     .or(() -> Result.success(loaded));
    }

    static Result<Loaded> load(Migration migration) {
        return read(migration.resource()).flatMap(text -> loaded(migration, text));
    }

    private static Result<Loaded> loaded(Migration migration, String text) {
        return sha256Hex(migration, text).map(sha -> new Loaded(migration, text, sha));
    }

    /// temper hashes through `protocol.digest`; kiln uses `temper-protocol` for `exec` only (Q-9), so it hashes here.
    private static Result<String> sha256Hex(Migration migration, String text) {
        return Result.lift(unreadable(migration.resource()),
                           () -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> digest.digest(text.getBytes(StandardCharsets.UTF_8)))
                     .map(HexFormat.of()::formatHex);
    }

    private static Result<String> read(String resource) {
        return Option.option(MigrationRunner.class.getClassLoader().getResourceAsStream(resource))
                     .toResult(KilnStoreError.MigrationMissing.FACTORY.apply(resource))
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
        return thrown -> KilnStoreError.MigrationUnreadable.FACTORY.apply(resource, Causes.fromThrowable(thrown));
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
        return untampered(loaded, applied).onSuccess(_ -> logLegacy(loaded, applied))
                         .flatMap(_ -> allInBuild(loaded, applied))
                         .map(_ -> unapplied(loaded, applied))
                         .flatMap(unapplied -> afterApplied(unapplied, applied));
    }

    private static Result<Unit> untampered(List<Loaded> loaded, Map<Integer, String> applied) {
        return Option.from(loaded.stream().filter(migration -> changed(migration, applied)).findFirst())
                     .map(migration -> KilnStoreError.MigrationTampered.FACTORY.apply(migration.migration().version()).<Unit> result())
                     .or(Result::unitResult);
    }

    private static boolean changed(Loaded migration, Map<Integer, String> applied) {
        return Option.option(applied.get(version(migration)))
                     .filter(sha -> !sha.equals(migration.sha256()))
                     .filter(sha -> !isLegacy(migration, sha))
                     .isPresent();
    }

    private static boolean isLegacy(Loaded migration, String sha) {
        return sha.equals(LEGACY_SHA256.get(version(migration)));
    }

    private static void logLegacy(List<Loaded> loaded, Map<Integer, String> applied) {
        loaded.stream()
              .filter(migration -> Option.option(applied.get(version(migration)))
                                         .filter(sha -> !sha.equals(migration.sha256()))
                                         .isPresent())
              .forEach(migration -> LOG.info("migration V{} applied with its pre-squash bytes (legacy sha256 accepted, row left as is)",
                                             version(migration)));
    }

    private static Result<Unit> allInBuild(List<Loaded> loaded, Map<Integer, String> applied) {
        var versions = loaded.stream().map(MigrationRunner::version).collect(Collectors.toSet());

        return Option.from(applied.keySet().stream().sorted().filter(version -> !versions.contains(version)).findFirst())
                     .map(version -> KilnStoreError.MigrationNotInBuild.FACTORY.apply(version).<Unit> result())
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
                     .map(migration -> KilnStoreError.MigrationOutOfOrder.FACTORY.apply(version(migration),
                                                                                        highest).<List<Loaded>> result())
                     .or(() -> Result.success(unapplied));
    }

    private static int version(Loaded migration) {
        return migration.migration()
                        .version();
    }

    private static Promise<Unit> applyOne(Transaction tx, Loaded migration) {
        return tx.completeScript(migration.text())
                 .mapError(KilnStoreError.Database.FACTORY)
                 .flatMap(_ -> Sql.execute(tx,
                                           "INSERT INTO schema_migrations (version, name, sha256) VALUES ($1, $2, $3)",
                                           migration.migration().version(),
                                           migration.migration().name(),
                                           migration.sha256()));
    }

    record unused() implements MigrationRunner {}
}
