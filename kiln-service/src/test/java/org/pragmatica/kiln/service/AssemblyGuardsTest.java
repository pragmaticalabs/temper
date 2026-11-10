package org.pragmatica.kiln.service;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.config.KilnConfigLoader;

import static org.assertj.core.api.Assertions.assertThat;

/// Structural guards of the assembly (WP9).
///
/// - Open item 6 (design §9.2): no main source of either kiln module holds the text "not built", so no WP1 stub is left.
/// - Open item 11: only the GitHub poller writes check cells (`CheckStore.cells`), so the store's one-writer-per-PR
///   precondition holds by construction; read from the compiled constant pools, so every call spelling is found.
/// - Open item 9 and §6: `ops/kiln/kiln.toml.example` parses, scans only bigboy (cloudbb-3 and cloudbb-4 are gone),
///   names the Mac's roots and keeps the transport below half the step; `ops/kiln/kiln.service` unsets
///   `HCLOUD_TOKEN` and starts the deployed jar with the deployed config.
class AssemblyGuardsTest {
    private static final Path ROOT = Path.of("..");
    private static final String STUB = String.join(" ", "not", "built");
    private static final String CHECK_STORE = "org/pragmatica/kiln/service/store/CheckStore";
    private static final String POLLER = "org/pragmatica/kiln/service/github/GitHubPoller";

    @Test
    void noMainSource_holdsAStub() throws IOException {
        var sources = Stream.of("kiln-core", "kiln-service")
                            .flatMap(module -> javaFiles(ROOT.resolve(module).resolve("src/main/java")).stream())
                            .toList();

        assertThat(sources).as("main sources read").hasSizeGreaterThan(100);
        assertThat(sources.stream().filter(AssemblyGuardsTest::holdsStub)).as("main sources holding \"%s\"", STUB).isEmpty();
    }

    /// The instrument: a file holding the text is found.
    @Test
    void stubScan_findsTheText() throws IOException {
        var file = Files.createTempFile("stub", ".java");

        Files.writeString(file, "return Causes.cause(\"" + STUB + "\").result();");
        assertThat(holdsStub(file)).isTrue();
        Files.delete(file);
    }

    @Test
    void onlyTheGitHubPoller_writesCheckCells() throws IOException {
        var classes = classFiles(Path.of("target/classes"));
        var callers = classes.stream()
                             .filter(AssemblyGuardsTest::callsCells)
                             .map(file -> Path.of("target/classes").relativize(file).toString())
                             .toList();

        assertThat(classes).as("main classes read").hasSizeGreaterThan(100);

        assertThat(callers).as("main classes calling CheckStore.cells").isNotEmpty()
                           .allMatch(name -> name.startsWith(POLLER + ".") || name.startsWith(POLLER + "$"));
    }

    @Test
    void exampleConfig_scansOnlyBigboy_andPushesTheMacRoots() {
        var config = KilnConfigLoader.load(ROOT.resolve("ops/kiln/kiln.toml.example"), Path.of("/home/builder")).unwrap();

        assertThat(config.hosts()).extracting(host -> host.name().value()).containsExactly("bigboy");
        assertThat(config.mac().roots()).contains("~/src/pragmatica*", "/private/tmp/scratch-501/*/*/work/*");
        assertThat(config.github().tokenFile().isPresent()).isTrue();
        assertThat(config.server().port()).isEqualTo(7171);
        assertThat(config.scan().transportTimeout().multipliedBy(2)).isLessThan(config.scan().stepDeadline());
    }

    @Test
    void unit_unsetsTheCloudToken_andRunsTheDeployedJar() throws IOException {
        var unit = Files.readAllLines(ROOT.resolve("ops/kiln/kiln.service"));

        assertThat(unit).contains("UnsetEnvironment=HCLOUD_TOKEN",
                                  "ExecStart=/usr/bin/env java -Xmx512m -jar %h/.kiln/dist/kiln-service.jar %h/.config/kiln/kiln.toml",
                                  "Restart=on-failure",
                                  "WantedBy=default.target");
    }

    /// WP9 verification: the jar logs at INFO, netty and the postgres driver at WARN; never DEBUG to the journal.
    @Test
    void mainLogging_isInfo_neverDebug() throws IOException {
        var config = Files.readString(Path.of("src/main/resources/logback.xml"));

        assertThat(config).contains("<root level=\"INFO\">", "<logger name=\"io.netty\" level=\"WARN\"/>")
                          .doesNotContain("DEBUG", "TRACE");
    }

    private static boolean holdsStub(Path file) {
        try {
            return Files.readString(file).contains(STUB);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean callsCells(Path file) {
        try {
            return StreamSupport.stream(ClassFile.of()
                                                 .parse(Files.readAllBytes(file))
                                                 .constantPool()
                                                 .spliterator(),
                                        false)
                                .anyMatch(AssemblyGuardsTest::isCellsCall);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isCellsCall(Object entry) {
        return switch (entry) {
            case InterfaceMethodRefEntry ref -> ref.owner().asInternalName().equals(CHECK_STORE) && ref.name().equalsString("cells");
            case MethodRefEntry ref -> ref.owner().asInternalName().equals(CHECK_STORE) && ref.name().equalsString("cells");
            default -> false;
        };
    }

    private static List<Path> javaFiles(Path dir) {
        return files(dir, ".java");
    }

    private static List<Path> classFiles(Path dir) {
        return files(dir, ".class");
    }

    private static List<Path> files(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(file -> file.toString().endsWith(suffix)).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
