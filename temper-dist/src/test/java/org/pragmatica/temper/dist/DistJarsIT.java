package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The deployment jars of one build (m0-design §12.7 item 3), read as files from `target/dist`.
///
/// The handshake accepts a worker only when its version equals the controller's; both come from filtered resources
/// of their own jar. This proves, from the shaded jars a deploy ships, that the controller's expected worker version,
/// the worker's reported version and this reactor's `${project.version}` are one value. The same two resources carry
/// the build's judge (m2-design §1.7): both jars name this reactor's `${temper.judge}`, the commit `ops/build-dist.sh`
/// passed, or `unjudged` for a plain build. It is what `ops/deploy-jars.sh` reads.
class DistJarsIT {
    private static final String VERSION = System.getProperty("project.version");
    private static final String JUDGE = System.getProperty("temper.judge");
    private static final Path DIST = Path.of(System.getProperty("dist.dir"));
    private static final String CONTROLLER_VERSION = "org/pragmatica/temper/controller/controller-version.properties";
    private static final String WORKER_VERSION = "org/pragmatica/temper/worker/worker-version.properties";
    private static final String CLI_VERSION = "org/pragmatica/temper/cli/cli-version.properties";

    /// `artifact-<ver>.jar`, or `artifact-<ver>-all.jar` for the controller and the worker, whose runnable shaded jars
    /// are attached as `-all` (their main jars are their own classes only, F5).
    private static Path jar(String artifact) {
        return DIST.resolve(artifact + "-" + VERSION + (artifact.equals("temper-cli") ? "" : "-all") + ".jar");
    }

    private static String versionIn(Path jar, String resource) throws IOException {
        return lineIn(jar, resource, "version=");
    }

    private static String lineIn(Path jar, String resource, String prefix) throws IOException {
        try (var file = new JarFile(jar.toFile())) {
            var entry = file.getJarEntry(resource);

            assertThat(entry).as(resource + " in " + jar).isNotNull();
            try (var in = file.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                                                                            .filter(line -> line.startsWith(prefix))
                                                                            .map(line -> line.substring(prefix.length())
                                                                                             .trim())
                                                                            .findFirst()
                                                                            .orElse("");
            }
        }
    }

    @Test
    void controllerAndWorkerJars_carryThisBuildsVersion_soTheHandshakeAgrees() throws IOException {
        var expectedByController = versionIn(jar("temper-controller"), CONTROLLER_VERSION);
        var reportedByWorker = versionIn(jar("temper-worker"), WORKER_VERSION);

        assertThat(VERSION).isNotBlank();
        assertThat(expectedByController).isEqualTo(VERSION);
        assertThat(reportedByWorker).isEqualTo(VERSION);
        assertThat(expectedByController).isEqualTo(reportedByWorker);
    }

    @Test
    void controllerAndWorkerJars_carryThisBuildsJudge() throws IOException {
        var controllerJudge = lineIn(jar("temper-controller"), CONTROLLER_VERSION, "judge=");
        var workerJudge = lineIn(jar("temper-worker"), WORKER_VERSION, "judge=");

        assertThat(JUDGE).isNotBlank();
        assertThat(controllerJudge).isEqualTo(JUDGE);
        assertThat(workerJudge).isEqualTo(JUDGE);
    }

    /// D-6: the CLI compares its own build version with the controller's `Temper-Version` header, so the shipped CLI
    /// jar carries the same `version=` and `judge=` lines as the controller jar of the same build.
    @Test
    void cliJar_carriesThisBuildsVersionAndJudge() throws IOException {
        assertThat(versionIn(jar("temper-cli"), CLI_VERSION)).isEqualTo(VERSION);
        assertThat(lineIn(jar("temper-cli"), CLI_VERSION, "judge=")).isEqualTo(JUDGE);
    }

    static Stream<Arguments> fatJars() {
        return Stream.of(Arguments.of("temper-controller", "org.pragmatica.temper.controller.Main"),
                         Arguments.of("temper-worker", "org.pragmatica.temper.worker.Main"),
                         Arguments.of("temper-cli", "org.pragmatica.temper.cli.Main"));
    }

    /// Runnable with `java -jar`: a `Main-Class`, the main class itself, and its dependencies inside (fat).
    @ParameterizedTest(name = "{0}")
    @MethodSource("fatJars")
    void distJar_isRunnableAndFat(String artifact, String mainClass) throws IOException {
        var path = jar(artifact);

        assertThat(path).isRegularFile();
        assertThat(Files.size(path)).isGreaterThan(1024 * 1024);
        try (var file = new JarFile(path.toFile())) {
            assertThat(file.getManifest()
                           .getMainAttributes()
                           .getValue(Attributes.Name.MAIN_CLASS)).isEqualTo(mainClass);
            assertThat(file.getJarEntry(mainClass.replace('.', '/') + ".class")).isNotNull();
            assertThat(file.getJarEntry("org/pragmatica/lang/Result.class")).as("Pragmatica core shaded in")
                                                                            .isNotNull();
        }
    }
}
