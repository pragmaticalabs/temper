package org.pragmatica.temper.store.pg;

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
    private static final Map<String, String> PINS = Map.ofEntries(Map.entry("db/migration/V1__m0_schema.sql",
                                                                            "eed3057f59aa65993e81bc2eec24877887ca3649b91dae33d10a8cd90f7b840a"),
                                                                  Map.entry("db/migration/V2__m1.sql",
                                                                            "e3eb01e55e59d8b8f70c53ace48494b3288a4fb267d3976de82d862f41036fbe"),
                                                                  Map.entry("db/migration/V3__m1_manifest.sql",
                                                                            "bba5cdde1fddf44f9eabfb218b6e16c30e91bf22c98347d4a72de3a2d0b388da"),
                                                                  Map.entry("db/migration/V4__m1_unknown.sql",
                                                                            "f4460b103262c14cbdccf5a25b15f63935fa732b151e3566a6e1c23b8356ea82"),
                                                                  Map.entry("db/migration/V5__m1_supersede_recipe.sql",
                                                                            "0d98f82aaa0f38dddf0ce530370cb121f0bd4eef7a7d97e0fe80b23fc10fab63"),
                                                                  Map.entry("db/migration/V6__m2_judge.sql",
                                                                            "057961082c762b8e01917dd2c8f91966865324246476ce80968548b6c59d63e9"),
                                                                  Map.entry("db/migration/V7__m2_selectors.sql",
                                                                            "131c4b7aec8fc83dc4b5f99e79faa440c81501fab9c83881b528d8302ac77275"),
                                                                  Map.entry("db/migration/V8__m2_known.sql",
                                                                            "6234d9b0aff5ce918c4bba72ccc1a102ba8ee54f0e92964397b50b1c210ea8a5"),
                                                                  Map.entry("db/migration/V9__m2_rerun.sql",
                                                                            "ab9654b9d74512bf335f60d04fd86a3025b923ed4ee96cba863f54dfea4c9b8a"),
                                                                  Map.entry("db/migration/V10__m3_tasks.sql",
                                                                            "b1186f1fcac3d14870883d938367df427d794c86652fd9bb6ba1eb4efe547e67"),
                                                                  Map.entry("db/migration/V11__m4_hosts.sql",
                                                                            "262c778862e245c5fb073d10084afe02693d923afbd2bc4c20d06e90c52e2f20"),
                                                                  Map.entry("db/migration/V12__m4_host_recovery.sql",
                                                                            "eb46573d49c81be6c36034f54c9ef598fd45ee7743cdada34ddae337cfb30909"),
                                                                  Map.entry("db/migration/V13__t1_fleet.sql",
                                                                            "3422074d4e20373f736461df56a2c2e8e61e634f21c5be37a252e007f5d8e244"),
                                                                  Map.entry("db/migration/V14__t2_merge_gate.sql",
                                                                            "89cc7e8929ca633b1eeb299c799f486705dd7fa3a7dd20f8f8a6f209362929f5"),
                                                                  Map.entry("db/migration/V15__t3_tickets.sql",
                                                                            "97c556276d4a5d9972d4cae3c141acaf7023840c3575ba56514ba84c601888e9"),
                                                                  Map.entry("db/migration/V16__gh_identities.sql",
                                                                            "c9af4f87403ff7c69df82e889a106c0a0b548dce4ba76d6538cdf509a339debf"),
                                                                  Map.entry("db/migration/V17__s1_alarms.sql",
                                                                            "bd25f40d814dcecd6d81ebffdd60341c62e6fbfa6a28b7b0cfe43ebc7061a3ab"),
                                                                  Map.entry("db/migration/V18__i111_grant_fence.sql",
                                                                            "8f85ada430ee01b00a15e90396d2b09a18de2cfd6a518b679dabb7030f31f042"),
                                                                  Map.entry("db/migration/V19__portgroup_long.sql",
                                                                            "ee969530a937290ac1e4c3aaf6cb5b8be0f1cc992db9ec9c7d843e5a0b41fa58"),
                                                                  Map.entry("db/migration/V20__lane_registry.sql",
                                                                            "9ea9f3ccb9281d7fca4e0dfe954a96dc8ddc5bebca345d4316c0e3abfa0e431a"));

    @Test
    void everyShippedMigration_isPinnedAtItsSha() throws IOException, URISyntaxException, NoSuchAlgorithmException {
        var dir = Path.of(MigrationRunner.class.getClassLoader().getResource("db/migration").toURI());
        var actual = new TreeMap<String, String>();

        try (var files = Files.list(dir)) {
            for (var file : files.toList()) {
                actual.put("db/migration/" + file.getFileName(), sha256(file));
            }
        }

        for (var entry : actual.entrySet()) {
            assertThat(PINS).as("%s has no pin: add its sha256 to PINS in ShippedMigrationsPinTest",
                                entry.getKey())
                      .containsKey(entry.getKey());
            assertThat(entry.getValue()).as("%s changed after shipping: add a new migration; shipped ones are immutable",
                                            entry.getKey())
                      .isEqualTo(PINS.get(entry.getKey()));
        }

        assertThat(actual.keySet()).as("a pinned migration file is gone: shipped migrations are immutable")
                  .containsExactlyInAnyOrderElementsOf(PINS.keySet());
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
