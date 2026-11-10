package org.pragmatica.temper.dist;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// `BbFixtures.run` launches through the shared hermetic helper (issue #121). The failsafe JVM of this module carries
/// BB_HOST=cloudbb-d1 and SSH_AUTH_SOCK=/poison/agent (pom), so a child that inherited the environment would show them.
class BbFixturesRunIT {
    /// Mutation: `run` starts its child some other way than through `HermeticProcess.builder(command, env)`.
    @Test
    void run_childDoesNotSeePoisonedHostVariables_andSeesTheExplicitOnes() throws Exception {
        assertThat(System.getenv()).as("the failsafe JVM is poisoned (pom environmentVariables)")
                                   .containsEntry("BB_HOST", "cloudbb-d1")
                                   .containsKey("SSH_AUTH_SOCK");

        var outcome = BbFixtures.run(java.util.List.of("env"), Map.of("BB_OWNER", "own"), null, null);

        assertThat(outcome.rc()).isZero();
        assertThat(outcome.stdout().lines()).contains("BB_OWNER=own")
                                            .noneMatch(line -> line.startsWith("BB_HOST=") || line.startsWith("SSH_AUTH_SOCK="));
    }
}
