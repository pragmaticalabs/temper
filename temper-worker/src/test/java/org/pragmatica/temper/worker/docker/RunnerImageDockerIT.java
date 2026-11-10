package org.pragmatica.temper.worker.docker;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.unit.DockerRunArgs;
import org.pragmatica.temper.worker.unit.RunnerImage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The runner image (spec §4.6, m0-design §7.6), built for real from the jar's resources: the pinned base, Maven
/// 3.9.12 verified by sha512, the compiled launcher, and jcmd for the timeout dumps. Needs a docker daemon and,
/// the first time, network access to the registry and archive.apache.org.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class RunnerImageDockerIT {
    @Test
    void ensure_buildsTheRunnerImage_withThePinnedToolchain() throws Exception {
        var version = DockerSupport.runnerVersion();
        var paths = WorkerPaths.workerPaths(DockerSupport.scratch("runner"));
        var imageId = ok(RunnerImage.runnerImage(DockerSupport.docker(), paths)
                                    .ensure(version)
                                    .await());
        var image = DockerRunArgs.image(version);

        assertThat(imageId).startsWith("sha256:");
        assertThat(DockerSupport.run("docker", "image", "inspect", "-f", "{{index .Config.Labels \"temper.runner\"}}", image)).isEqualTo(version);
        assertThat(DockerSupport.run("docker", "run", "--rm", image, "mvn", "-v")).contains("Apache Maven 3.9.12");
        assertThat(DockerSupport.run("docker", "run", "--rm", image, "java", "-version")).contains("version \"25");
        assertThat(DockerSupport.run("docker", "run", "--rm", image, "ls", "/opt/temper")).contains("RunSteps.class");
        assertThat(DockerSupport.run("docker", "run", "--rm", image, "which", "jcmd")).contains("jcmd");
        assertThat(DockerSupport.run("docker", "run", "--rm", "--user", "1001:1001", image, "java", "-cp", "/opt/temper", "RunSteps", "1", "env"))
            .as("the launcher runs as an arbitrary uid and passes only the allow-list")
            .contains("PATH=")
            .doesNotContain("LANG=", "JAVA_VERSION=");
    }

    /// The tools projects' own tests exec (T1 2026-10-14: pragmatica's script-gate and git-backup tests), runnable as
    /// an arbitrary uid like a unit: python3, git new enough for `--initial-branch` with its HTTP transport, jq, curl and
    /// the openssh client (pragmatica rc5's provision-test-pg.sh and pg-firewall.sh, 2026-10-20).
    @Test
    void ensure_providesTheToolsProjectTestsExec() throws Exception {
        var version = DockerSupport.runnerVersion();
        var paths = WorkerPaths.workerPaths(DockerSupport.scratch("runner-tools"));

        ok(RunnerImage.runnerImage(DockerSupport.docker(), paths)
                      .ensure(version)
                      .await());

        var image = DockerRunArgs.image(version);

        assertThat(asUnit(image, "python3", "--version")).startsWith("Python 3.");
        assertThat(asUnit(image, "python3", "-c", "import xml.etree.ElementTree, pathlib, datetime; print('stdlib ok')")).isEqualTo("stdlib ok");
        assertThat(asUnit(image, "git", "--version")).startsWith("git version 2.");
        assertThat(asUnit(image, "sh", "-c", "git init -q --initial-branch=main /tmp/r && git -C /tmp/r symbolic-ref HEAD")).isEqualTo("refs/heads/main");
        assertThat(asUnit(image, "sh", "-c", "test -x \"$(git --exec-path)/git-remote-http\" && echo http ok")).isEqualTo("http ok");
        assertThat(asUnit(image, "sh", "-c", "echo '{\"id\":99}' | jq -r .id")).isEqualTo("99");
        assertThat(asUnit(image, "sh", "-c", "command -v ssh")).isEqualTo("/usr/bin/ssh");
        assertThat(asUnit(image, "sh", "-c", "command -v ssh-keygen")).isEqualTo("/usr/bin/ssh-keygen");
        assertThat(asUnit(image, "sh", "-c", "command -v curl")).isEqualTo("/usr/bin/curl");
    }

    private static String asUnit(String image, String... command) throws Exception {
        var argv = new ArrayList<>(List.of("docker", "run", "--rm", "--user", "1001:1001", "-e", "HOME=/tmp", image));

        argv.addAll(List.of(command));

        return DockerSupport.run(argv.toArray(String[]::new));
    }
}
