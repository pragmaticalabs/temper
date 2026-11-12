package org.pragmatica.kiln.service.store.pg;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.kiln.service.store.KilnStoreError;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// The copy's two changes (kiln-f1-design §1): resources under `kiln/db/migration/` with no temper migration on kiln's
/// classpath, and lock 7171. Then, as temper's test: every migration file is listed in `MigrationRunner.MIGRATIONS` and loads with a sha256; the listed set is contiguous
/// from V1, and the pending set refuses a changed, a missing or an out-of-order version.
class MigrationRunnerTest {
    @Test
    void everyMigrationFile_isListed() throws IOException, URISyntaxException {
        var dir = Path.of(MigrationRunner.class.getClassLoader().getResource("kiln/db/migration").toURI());

        try (var files = Files.list(dir)) {
            assertThat(files.map(file -> "kiln/db/migration/" + file.getFileName()).toList()).containsExactlyInAnyOrderElementsOf(MigrationRunner.MIGRATIONS.stream()
                                                                                                                                                            .map(MigrationRunner.Migration::resource)
                                                                                                                                                            .toList());
        }
    }

    @Test
    void kilnsCopy_usesItsOwnLockAndResources_andNoTemperMigrationIsOnTheClasspath() {
        assertThat(MigrationRunner.LOCK).isEqualTo(7171L);
        assertThat(MigrationRunner.MIGRATIONS).extracting(MigrationRunner.Migration::resource)
                  .containsExactly("kiln/db/migration/V1__f1_schema.sql",
                                   "kiln/db/migration/V2__temper_gate_answer_sha.sql",
                                   "kiln/db/migration/V3__ci_change_feed.sql",
                                   "kiln/db/migration/V4__ci_feed_state.sql",
                                   "kiln/db/migration/V5__work_ledger.sql",
                                   "kiln/db/migration/V6__base_tip.sql",
                                   "kiln/db/migration/V7__pipeline_baseline.sql");
        assertThat(MigrationRunner.class.getClassLoader().getResource("db/migration")).isNull();
        assertThat(MigrationRunner.class.getClassLoader().getResource("db/migration/V1__m0_schema.sql")).isNull();
    }

    @Test
    void load_missingResource_isMigrationMissing() {
        MigrationRunner.load(new MigrationRunner.Migration(99, "absent"))
                       .onSuccess(loaded -> fail("expected MigrationMissing, got " + loaded))
                       .onFailure(cause -> assertThat(cause).isEqualTo(KilnStoreError.MigrationMissing.FACTORY.apply("kiln/db/migration/V99__absent.sql")));
    }

    @Test
    void everyListedMigration_loads() {
        MigrationRunner.MIGRATIONS.forEach(migration -> assertThat(MigrationRunner.load(migration).unwrap().sha256()).hasSize(64));
    }

    @Test
    void contiguous_isTheListedMigrations() {
        var loaded = MigrationRunner.MIGRATIONS.stream()
                                               .map(migration -> MigrationRunner.load(migration).unwrap())
                                               .toList();

        assertThat(MigrationRunner.contiguous(loaded).unwrap()).isEqualTo(loaded);
    }

    @Test
    void contiguous_isMigrationGap_whenAVersionIsSkipped() {
        assertRefused(MigrationRunner.contiguous(migrations(1, 2, 4)),
                      KilnStoreError.MigrationGap.FACTORY.apply(3, 3, 4));
    }

    @Test
    void contiguous_isMigrationGap_whenV1IsAbsent() {
        assertRefused(MigrationRunner.contiguous(migrations(2, 3)), KilnStoreError.MigrationGap.FACTORY.apply(1, 1, 2));
    }

    @Test
    void contiguous_isMigrationGap_whenOutOfListOrder() {
        assertRefused(MigrationRunner.contiguous(migrations(1, 3, 2)),
                      KilnStoreError.MigrationGap.FACTORY.apply(2, 2, 3));
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

        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), tampered),
                      KilnStoreError.MigrationTampered.FACTORY.apply(2));
    }

    @Test
    void pending_isMigrationNotInBuild_whenTheDatabaseIsAhead() {
        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 2, 3, 4)),
                      KilnStoreError.MigrationNotInBuild.FACTORY.apply(4));
    }

    @Test
    void pending_isMigrationOutOfOrder_whenAPendingVersionIsBelowTheHighestApplied() {
        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 3)),
                      KilnStoreError.MigrationOutOfOrder.FACTORY.apply(2, 3));
    }

    /// A duplicate entry (a merge that listed one version twice) is refused, not taken for the next version.
    @Test
    void contiguous_isMigrationGap_whenAVersionIsListedTwice() {
        assertRefused(MigrationRunner.contiguous(migrations(1, 2, 2)),
                      KilnStoreError.MigrationGap.FACTORY.apply(3, 3, 2));
        assertRefused(MigrationRunner.contiguous(migrations(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10)),
                      KilnStoreError.MigrationGap.FACTORY.apply(11, 11, 10));
    }

    /// The gap message names what the list holds where, and what to do; it never claims a present version is missing.
    @Test
    void migrationGap_message_namesExpectedPositionFoundAndTheNextStep() {
        assertThat(KilnStoreError.MigrationGap.FACTORY.apply(3, 3, 2).message()).isEqualTo("Migration list: expected V3 at position 3, found V2; the build's migration list is not contiguous; fix the build");
        assertThat(KilnStoreError.MigrationGap.FACTORY.apply(2, 2, 3).message()).contains("expected V2 at position 2, found V3");
    }

    @Test
    void notInBuildAndOutOfOrder_messages_nameTheNextStep() {
        assertThat(KilnStoreError.MigrationNotInBuild.FACTORY.apply(4).message()).isEqualTo("Migration V4 is applied but not in this build; deploy a build containing it, or drop the kiln database (every table is a cache but the work ledger: first pg_dump --data-only -t work_item -t work_event, and load it after V5 reapplies)");
        assertThat(KilnStoreError.MigrationOutOfOrder.FACTORY.apply(2, 3).message()).isEqualTo("Migration V2 is pending but V3 is already applied; deploy a build whose migrations match the database, or drop the kiln database (every table is a cache but the work ledger: first pg_dump --data-only -t work_item -t work_event, and load it after V5 reapplies)");
    }

    /// Several applied versions missing from the build: the LOWEST is reported, whatever order the map iterates in
    /// (a `HashMap` puts 17 before 5: both land in buckets 1 and 5 of 16).
    @Test
    void pending_isMigrationNotInBuild_forTheLowestMissingVersion() {
        assertRefused(MigrationRunner.pending(migrations(1, 2, 3), applied(1, 2, 3, 5, 17)),
                      KilnStoreError.MigrationNotInBuild.FACTORY.apply(5));
    }

    /// The public-history squash d3140d89 edited comments of shipped V3; a database that applied the old bytes
    /// carries the legacy sha, which is accepted (and not queued for re-apply), the current sha is accepted, any other is tampering.
    @Test
    void pending_acceptsTheLegacyShaOfAShippedMigration() {
        var current = loadedShipped(3);
        var applied = Map.of(3, "46cf1140530e0296849643d3526a8b8d0c42aa855ee7b12263a2658f417d7236");

        assertThat(MigrationRunner.pending(List.of(current), applied).unwrap()).isEmpty();
    }

    /// The live pre-squash shas, written out independently of MigrationRunner.LEGACY_SHA256, so dropping or editing any
    /// version's entry there turns this red (deriving the expectation from the list itself would prove nothing).
    private static final Map<Integer, String> PRE_SQUASH_SHA256 = Map.of(
        3, "46cf1140530e0296849643d3526a8b8d0c42aa855ee7b12263a2658f417d7236",
        6, "19a13d493bbdb11e5dc13e5db5e35def959ec254cc0dd183d27cc63242026745");

    @Test
    void pending_acceptsThePreSquashShaOfEveryEditedMigration() {
        PRE_SQUASH_SHA256.forEach((version, sha) -> assertThat(MigrationRunner.pending(List.of(loadedShipped(version)), Map.of(version, sha)).unwrap())
                                                         .as("V%d pre-squash sha", version)
                                                         .isEmpty());
        assertThat(MigrationRunner.LEGACY_SHA256).isEqualTo(PRE_SQUASH_SHA256);
    }

    @Test
    void pending_acceptsTheCurrentShaOfAShippedMigration() {
        var current = loadedShipped(3);

        assertThat(MigrationRunner.pending(List.of(current), Map.of(3, current.sha256())).unwrap()).isEmpty();
    }

    @Test
    void pending_isMigrationTampered_whenTheShaIsNeitherCurrentNorLegacy() {
        var current = loadedShipped(3);

        assertRefused(MigrationRunner.pending(List.of(current), Map.of(3, "0".repeat(64))), KilnStoreError.MigrationTampered.FACTORY.apply(3));
    }

    /// A legacy sha of one version is no excuse for another version.
    @Test
    void pending_isMigrationTampered_whenALegacyShaIsAppliedToAnotherVersion() {
        var other = loadedShipped(1);

        assertRefused(MigrationRunner.pending(List.of(other), Map.of(1, "46cf1140530e0296849643d3526a8b8d0c42aa855ee7b12263a2658f417d7236")), KilnStoreError.MigrationTampered.FACTORY.apply(1));
    }

    private static MigrationRunner.Loaded loadedShipped(int version) {
        return MigrationRunner.load(MigrationRunner.MIGRATIONS.get(version - 1)).unwrap();
    }

    /// Synthetic migrations whose sha256 is `sha<version>`, matching [#applied].
    private static List<MigrationRunner.Loaded> migrations(int... versions) {
        return IntStream.of(versions)
                        .mapToObj(version -> new MigrationRunner.Loaded(new MigrationRunner.Migration(version,
                                                                                                      "m" + version),
                                                                        "",
                                                                        "sha" + version))
                        .toList();
    }

    private static Map<Integer, String> applied(int... versions) {
        return IntStream.of(versions)
                        .boxed()
                        .collect(Collectors.toMap(version -> version,
                                                  version -> "sha" + version));
    }

    private static List<Integer> versions(List<MigrationRunner.Loaded> loaded) {
        return loaded.stream()
                     .map(migration -> migration.migration()
                                                .version())
                     .toList();
    }

    private static void assertRefused(Result<?> outcome, Cause expected) {
        outcome.onSuccess(value -> fail("expected " + expected + ", got " + value))
               .onFailure(cause -> assertThat(cause).isEqualTo(expected));
    }
}
