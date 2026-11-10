package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.docker.DockerCli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The runner image is reused only when its content-hash label matches the build context shipped in this jar.
class RunnerImageTest {
    private static final String TAG = "temper/runner:v1";
    private static final List<String> LABEL = List.of("docker", "image", "inspect", "-f", "{{index .Config.Labels \"temper.runner.hash\"}}", TAG);
    private static final List<String> ID = List.of("docker", "image", "inspect", "-f", "{{.Id}}", TAG);
    private static final List<String> BUILD = List.of("docker", "build");

    private static RunnerImage image(FakeRunner runner, Path home) {
        return RunnerImage.runnerImage(DockerCli.dockerCli(runner, Map.of(), Fixtures.fastTimings()), WorkerPaths.workerPaths(home));
    }

    @Test
    void ensure_reusesAnImageBuiltFromTheSameResources(@TempDir Path home) {
        var hash = ok(RunnerImage.contentHash());
        var runner = new FakeRunner().on(LABEL, 0, hash + "\n", "")
                                     .on(ID, 0, "sha256:abc\n", "");

        assertThat(ok(image(runner, home).ensure("v1")
                                         .await())).isEqualTo("sha256:abc");
        assertThat(runner.callsStartingWith(BUILD)).isEmpty();
    }

    @Test
    void ensure_rebuilds_whenTheImageWasBuiltFromOtherResources_orIsMissing(@TempDir Path home) {
        var hash = ok(RunnerImage.contentHash());
        var stale = new FakeRunner().on(LABEL, 0, "0".repeat(64) + "\n", "")
                                    .on(ID, 0, "sha256:new\n", "");

        assertThat(ok(image(stale, home).ensure("v1")
                                        .await())).isEqualTo("sha256:new");
        assertThat(stale.callsStartingWith(BUILD)).singleElement()
                                                  .satisfies(argv -> assertThat(argv).contains("temper.runner.hash=" + hash,
                                                                                               "temper.runner=v1"));
        assertThat(home.resolve("runner/v1/Dockerfile")).exists();
        assertThat(home.resolve("runner/v1/RunSteps.java")).exists();

        var missing = new FakeRunner().on(LABEL, 1, "", "Error: No such image: " + TAG)
                                      .on(ID, 0, "sha256:new\n", "");

        ok(image(missing, home).ensure("v1")
                               .await());
        assertThat(missing.callsStartingWith(BUILD)).hasSize(1);
    }

    /// A failed build is never handed out again: a retry made after the failure (here chained on it) builds anew.
    @Test
    void ensure_aRetryAfterAFailedBuild_buildsAgain(@TempDir Path home) {
        var builds = new AtomicInteger();
        var runner = new FakeRunner().on(LABEL, 1, "", "Error: No such image: " + TAG)
                                     .on(ID, 0, "sha256:new\n", "")
                                     .on(BUILD, _ -> firstBuildFails(builds));
        var image = image(runner, home);

        assertThat(ok(image.ensure("v1")
                           .fold(_ -> image.ensure("v1"))
                           .await())).isEqualTo("sha256:new");
        assertThat(builds.get()).isEqualTo(2);
    }

    private static Result<Completed> firstBuildFails(AtomicInteger builds) {
        return builds.incrementAndGet() == 1
               ? Result.success(FakeRunner.completed(1, "", "build failed"))
               : Result.success(FakeRunner.completed(0, "", ""));
    }
}
