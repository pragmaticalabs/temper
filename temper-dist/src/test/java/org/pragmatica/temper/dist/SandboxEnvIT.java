package org.pragmatica.temper.dist;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.testsupport.HermeticProcess;
import org.pragmatica.temper.testsupport.InheritsHostEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/// The child environment of the two sandbox launchers is hermetic: a host variable of a stripped family (HermeticProcess.POISON,
/// e.g. BB_HOST=cloudbb-d1) never reaches the child, while a variable the sandbox itself sets does. Tested on the
/// environment each launcher builds (`prepared`), poisoned before the launcher sees the builder.
@InheritsHostEnvironment
class SandboxEnvIT {
    @TempDir
    Path dir;

    /// Mutation: drop `HermeticProcess.prepared(...)` from `DeploySandbox.prepared`.
    @Test
    void deploySandbox_childDoesNotSeeHostVariables() throws Exception {
        try (var sandbox = new DeploySandbox(dir)) {
            var builder = new ProcessBuilder("true");
            builder.environment().putAll(HermeticProcess.POISON);

            assertHermetic(sandbox.prepared(builder).environment(), sandbox.env);
        }
    }

    /// Mutation: drop `HermeticProcess.prepared(...)` from `DevSandbox.prepared`.
    @Test
    void devSandbox_childDoesNotSeeHostVariables() throws Exception {
        var sandbox = new DevSandbox(dir);
        var builder = new ProcessBuilder("true");
        builder.environment().putAll(HermeticProcess.POISON);

        assertHermetic(sandbox.prepared(builder).environment(), sandbox.env);
    }

    private static void assertHermetic(Map<String, String> child, Map<String, String> own) {
        HermeticProcess.POISON.keySet().forEach(key -> {
            if (own.containsKey(key)) {
                assertThat(child).containsEntry(key, own.get(key));
            } else {
                assertThat(child).doesNotContainKey(key);
            }
        });
    }
}
