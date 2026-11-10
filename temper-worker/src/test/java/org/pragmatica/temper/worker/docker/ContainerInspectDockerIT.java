package org.pragmatica.temper.worker.docker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.protocol.message.ContainerStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// A waiting port-group unit has its network `temper-<job>-<unit>` but no container of that name yet. Every
/// container inspect must answer "no container" for it, not inspect the network (cloudbb-3, 2026-10-28: the untyped
/// `{{.State.Status}}` template failed on the network, the cancel failed and the worker would not start).
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class ContainerInspectDockerIT {
    @Test
    void containerInspects_aNameHeldOnlyByANetwork_isAbsent() throws Exception {
        var name = "temper-it-inspect-" + System.nanoTime() + "-main";
        var docker = DockerSupport.docker();

        DockerSupport.run("docker", "network", "create", name);
        try {
            assertThat(ok(docker.status(name).await())).isEqualTo(ContainerStatus.ABSENT);
            assertThat(ok(docker.containerId(name).await())).isEqualTo(Option.none());
            assertThat(ok(docker.instanceLabel(name).await())).isEqualTo(Option.none());
            assertThat(ok(docker.attemptLabel(name).await())).isEqualTo(Option.none());
        } finally {
            DockerSupport.run("docker", "network", "rm", name);
        }
    }
}
