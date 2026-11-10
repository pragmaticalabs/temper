package org.pragmatica.temper.store.pg;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.store.api.StoreError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Every migration file is listed in `MigrationRunner.MIGRATIONS` and loads with a sha256; the listed set is contiguous
/// from V1, and the pending set refuses a changed, a missing or an out-of-order version.
class MigrationRunnerTest {
    @Test
    void everyMigrationFile_isListed() throws IOException, URISyntaxException {
        var dir = Path.of(MigrationRunner.class.getClassLoader().getResource("db/migration").toURI());

        try (var files = Files.list(dir)) {
            assertThat(files.map(file -> "db/migration/" + file.getFileName()).toList())
                .containsExactlyInAnyOrderElementsOf(MigrationRunner.MIGRATIONS.stream().map(MigrationRunner.Migration::resource).toList());
        }
    }

    @Test
    void load_missingResource_isMigrationMissing() {
        MigrationRunner.load(new MigrationRunner.Migration(99, "absent"))
                       .onSuccess(loaded -> fail("expected MigrationMissing, got " + loaded))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StoreError.MigrationMissing.FACTORY.apply("db/migration/V99__absent.sql")));
    }

    @Test
    void everyListedMigration_loads() {
        MigrationRunner.MIGRATIONS.forEach(migration -> assertThat(MigrationRunner.load(migration).unwrap().sha256()).hasSize(64));
    }

    @Test
    void contiguous_isTheListedMigrations() {
        var loaded = MigrationRunner.MIGRATIONS.stream().map(migration -> MigrationRunner.load(migration).unwrap()).toList();

        assertThat(MigrationRunner.contiguous(loaded).unwrap()).isEqualTo(loaded);
    }

    @Test
    void contiguous_isMigrationGap_whenAVersionIsSkipped() {
        assertRefused(MigrationRunner.contiguous(migrations(1, 2, 4)), StoreError.MigrationGap.FACTORY.apply(3, 3, 4));
    }

    @Test
    void contiguous_isMigrationGap_whenV1IsAbsent() {
        assertRefused(MigrationRunner.contiguous(migrations(2, 3)), StoreError.MigrationGap.FACTORY.apply(1, 1, 2));
    }

    @Test
    void contiguous_isMigrationGap_whenOutOfListOrder() {
        assertRefused(MigrationRunner.contiguous(migrations(1, 3, 2)), StoreError.MigrationGap.FACTORY.apply(2, 2, 3));
    }

    @Test
    void pending_isEverything_onAFreshDatabase() {
        assertThat(versions(MigrationRunner.pending(migrations(1, 2, 3), Map.of()).unwrap())).containsExactly(1, 2, 3);
    }

    @Test
    void pending_isTheUnappliedTail_whenAppliedIsAPrefix() {
        assertThat(versions(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 2)).unwrap())).containsExactly(3);
    }

    @Test
    void pending_isEmpty_whenEverythingIsApplied() {
        assertThat(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 2, 3)).unwrap()).isEmpty();
    }

    @Test
    void pending_isMigrationTampered_whenAnAppliedFileChanged() {
        var tampered = Map.of(1, "sha1", 2, "changed");

        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), tampered), StoreError.MigrationTampered.FACTORY.apply(2));
    }

    @Test
    void pending_isMigrationNotInBuild_whenTheDatabaseIsAhead() {
        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 2, 3, 4)), StoreError.MigrationNotInBuild.FACTORY.apply(4));
    }

    @Test
    void pending_isMigrationOutOfOrder_whenAPendingVersionIsBelowTheHighestApplied() {
        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 3)), StoreError.MigrationOutOfOrder.FACTORY.apply(2, 3));
    }

    /// A duplicate entry (a merge that listed one version twice) is refused, not taken for the next version.
    @Test
    void contiguous_isMigrationGap_whenAVersionIsListedTwice() {
        assertRefused(MigrationRunner.contiguous(migrations(1, 2, 2)), StoreError.MigrationGap.FACTORY.apply(3, 3, 2));
        assertRefused(MigrationRunner.contiguous(migrations(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10)), StoreError.MigrationGap.FACTORY.apply(11, 11, 10));
    }

    /// The gap message names what the list holds where, and what to do; it never claims a present version is missing.
    @Test
    void migrationGap_message_namesExpectedPositionFoundAndTheNextStep() {
        assertThat(StoreError.MigrationGap.FACTORY.apply(3, 3, 2).message())
            .isEqualTo("Migration list: expected V3 at position 3, found V2; the build's migration list is not contiguous; fix the build");
        assertThat(StoreError.MigrationGap.FACTORY.apply(2, 2, 3).message()).contains("expected V2 at position 2, found V3");
    }

    @Test
    void notInBuildAndOutOfOrder_messages_nameTheNextStep() {
        assertThat(StoreError.MigrationNotInBuild.FACTORY.apply(4).message())
            .isEqualTo("Migration V4 is applied but not in this build; deploy a build containing it, or restore the pre-deploy dump (the deploy runbook, Rollback)");
        assertThat(StoreError.MigrationOutOfOrder.FACTORY.apply(2, 3).message())
            .isEqualTo("Migration V2 is pending but V3 is already applied; deploy a build whose migrations match the database, or restore the pre-deploy dump (the deploy runbook, Rollback)");
    }

    /// Several applied versions missing from the build: the LOWEST is reported, whatever order the map iterates in
    /// (a `HashMap` puts 17 before 5: both land in buckets 1 and 5 of 16).
    @Test
    void pending_isMigrationNotInBuild_forTheLowestMissingVersion() {
        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 2, 3, 5, 17)), StoreError.MigrationNotInBuild.FACTORY.apply(5));
    }

    /// Synthetic migrations whose sha256 is `sha<version>`, matching [#applied].
    private static List<MigrationRunner.Loaded> migrations(int... versions) {
        return IntStream.of(versions)
                        .mapToObj(version -> new MigrationRunner.Loaded(new MigrationRunner.Migration(version, "m" + version), "", "sha" + version))
                        .toList();
    }

    private static Map<Integer, String> applied(int... versions) {
        return IntStream.of(versions).boxed().collect(Collectors.toMap(version -> version, version -> "sha" + version));
    }

    private static List<Integer> versions(List<MigrationRunner.Loaded> loaded) {
        return loaded.stream().map(migration -> migration.migration().version()).toList();
    }

    private static void assertRefused(Result<?> outcome, Cause expected) {
        outcome.onSuccess(value -> fail("expected " + expected + ", got " + value))
               .onFailure(cause -> assertThat(cause).isEqualTo(expected));
    }
}
