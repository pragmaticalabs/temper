package org.pragmatica.kiln.service.store.pg;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the sha256 of every shipped migration file. A deployed database stores these shas and startup refuses a
/// changed one, so a shipped file is immutable; the public-history squash d3140d89 edited comments in several and
/// broke every build against a live database. A new migration must add its pin here.
class ShippedMigrationsPinTest {
    private static final Map<String, String> PINS = Map.ofEntries(
        Map.entry("kiln/db/migration/V1__f1_schema.sql", "c0d814a422bd7af30367628f958d0f46a2adfa86b266fd30d49f0fe77420ad62"),
        Map.entry("kiln/db/migration/V2__temper_gate_answer_sha.sql", "23209c766a3535e584ddc47a081530b77d584bab5404b1f7adc270e16afbd7ff"),
        Map.entry("kiln/db/migration/V3__ci_change_feed.sql", "5c8343676faddbcaae7584cd2de5610f8ea0499f8250a525d2eb3a4d1272c433"),
        Map.entry("kiln/db/migration/V4__ci_feed_state.sql", "46d24f5b97dd516933429ad23a9dc648428f3676d7507dd3e68fadec786b5260"),
        Map.entry("kiln/db/migration/V5__work_ledger.sql", "9157d5053a8a9115dd27c3d476602f6a52ab2d3c69c7541a6dce5807c727c616"),
        Map.entry("kiln/db/migration/V6__base_tip.sql", "6415fe811d215fcd25aaa58917d20c04d3da8283ac3b7bbcfdeada486931834f"),
        Map.entry("kiln/db/migration/V7__pipeline_baseline.sql", "6b8339eac6b92204c5c134dad6ea3346af064ab3c4561dab2e36740be50acf82")
    );

    @Test
    void everyShippedMigration_isPinnedAtItsSha() throws IOException, URISyntaxException, NoSuchAlgorithmException {
        var dir = Path.of(MigrationRunner.class.getClassLoader().getResource("kiln/db/migration").toURI());
        var actual = new TreeMap<String, String>();

        try (var files = Files.list(dir)) {
            for (var file : files.toList()) {
                actual.put("kiln/db/migration/" + file.getFileName(), sha256(file));
            }
        }
        for (var entry : actual.entrySet()) {
            assertThat(PINS).as("%s has no pin: add its sha256 to PINS in ShippedMigrationsPinTest", entry.getKey())
                            .containsKey(entry.getKey());
            assertThat(entry.getValue()).as("%s changed after shipping: add a new migration; shipped ones are immutable", entry.getKey())
                                        .isEqualTo(PINS.get(entry.getKey()));
        }
        assertThat(actual.keySet()).as("a pinned migration file is gone: shipped migrations are immutable")
                                   .containsExactlyInAnyOrderElementsOf(PINS.keySet());
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
