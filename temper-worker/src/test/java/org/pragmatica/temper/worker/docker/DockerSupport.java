package org.pragmatica.temper.worker.docker;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.config.WorkerTimings;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Helpers shared by the docker-tagged tests.
public final class DockerSupport {
    private DockerSupport() {}

    /// The runner version the docker tests build: derived from the shipped Dockerfile and launcher, so the image is
    /// rebuilt exactly when either changes.
    public static String runnerVersion() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");

        for (var name : List.of("/runner/Dockerfile", "/runner/RunSteps.java")) {
            try (var in = DockerSupport.class.getResourceAsStream(name)) {
                digest.update(in.readAllBytes());
            }
        }
        return "it-" + HexFormat.of()
                                .formatHex(digest.digest())
                                .substring(0, 12);
    }

    /// Production-like bounds with a short Testcontainers reap; the image build may take minutes on first use.
    public static WorkerTimings timings() {
        return new WorkerTimings(timeSpan(10).seconds(),
                                 timeSpan(5).seconds(),
                                 timeSpan(5).seconds(),
                                 timeSpan(5).seconds(),
                                 timeSpan(500).millis(),
                                 timeSpan(60).seconds(),
                                 timeSpan(20).minutes(),
                                 timeSpan(2).minutes(),
                                 timeSpan(60).seconds(),
                                 timeSpan(2).minutes(),
                                 timeSpan(10).minutes(),
                                 WorkerTimings.HoldTimings.holdTimings());
    }

    /// The real docker CLI with this JVM's PATH/HOME/DOCKER_HOST only.
    public static DockerCli docker() {
        return DockerCli.dockerCli(ProcessRunner.processRunner(), WorkerConfig.toolEnv(System.getenv()), timings());
    }

    /// Runs a command to completion and returns its merged output; fails the test on a non-zero exit.
    public static String run(String... argv) throws IOException, InterruptedException {
        var process = HermeticProcess.builder(argv).redirectErrorStream(true)
                                              .start();
        var output = new String(process.getInputStream()
                                       .readAllBytes(), StandardCharsets.UTF_8);

        if (process.waitFor() != 0) {
            throw new AssertionError(String.join(" ", argv) + " failed:\n" + output);
        }
        return output.trim();
    }

    /// A directory under the module's `target/` (docker Desktop/colima share the user's home, not the JVM temp dir).
    public static Path scratch(String name) throws IOException {
        return Files.createDirectories(Path.of("target", "docker-it", name + "-" + System.nanoTime())
                                           .toAbsolutePath());
    }
}
