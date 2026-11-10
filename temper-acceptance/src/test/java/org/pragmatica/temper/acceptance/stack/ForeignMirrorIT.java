package org.pragmatica.temper.acceptance.stack;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

/// Integration finding F1 (M1 WP7 verification, adapted from its `VerifierForeignMirrorProbeIT`): the worker's
/// `mirror.git` already exists, made from an unrelated origin A (a host whose live worker ran); the job comes from
/// origin B, whose commit A never had. The worker points the mirror at the job's origin before fetching, so the Gate
/// runs and PASSES instead of ending `ERROR(infra: git: Commit … is not in the mirror after a fetch)`.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class ForeignMirrorIT {
    @Test
    void gate_passes_onAWorkerWhoseMirrorWasMadeFromAnotherOrigin() throws Exception {
        try (var stack = AcceptanceStack.docker("foreign-mirror", List.of())) {
            var other = stack.dir.resolve("other-origin.git");
            var seed = stack.dir.resolve("other-seed");
            var mirror = stack.workerHome.resolve(".temper/mirror.git");
            Shell.ok("git", "init", "-q", "--bare", other.toString());
            Shell.ok("git", "init", "-q", seed.toString());
            Files.writeString(seed.resolve("README"), "other\n");
            Shell.ok("git", "-C", seed.toString(), "add", ".");
            Shell.ok("git", "-C", seed.toString(), "-c", "user.email=a@x", "-c", "user.name=a", "commit", "-q", "-m", "other");
            Shell.ok("git", "-C", seed.toString(), "push", "-q", other.toString(), "HEAD:refs/heads/main");
            Shell.ok("git", "clone", "-q", "--mirror", other.toString(), mirror.toString());

            var job = stack.submitGate("main");

            assertThat(job).as("A4: the acceptance job-id offset is applied").isGreaterThanOrEqualTo(AcceptanceStack.ACCEPTANCE_JOB_ID_OFFSET);
            var line = stack.awaited(job, Duration.ofMinutes(10));

            assertThat(stack.side(job).result().verdict()).as(line).isEqualTo("PASSED");
            assertThat(Shell.ok("git", "--git-dir", mirror.toString(), "config", "--get", "remote.origin.url"))
                .isEqualTo(stack.fixture.origin().toString());
        }
    }
}
