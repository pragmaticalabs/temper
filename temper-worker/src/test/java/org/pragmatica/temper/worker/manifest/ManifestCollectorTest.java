package org.pragmatica.temper.worker.manifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.worker.Fixtures;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.config.WorkerPaths;
import org.pragmatica.temper.worker.unit.UnitFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// The manifest scan's inputs (m1-design §2.5, WP2 handoff): test-classes go into the class index AND the
/// module's test classes; main classes into the index only; `notReached` is a module with test sources and no test
/// output; class files are read without following symlinks and only under the workspace; any failure is
/// `Unsupported`, never a silent pass.
class ManifestCollectorTest {
    @TempDir
    Path home;

    private WorkerPaths paths;
    private UnitFiles files;
    private Path workspace;
    private final UnitRef ref = Fixtures.ref(42, "gate");

    @BeforeEach
    void setUp() throws Exception {
        paths = WorkerPaths.workerPaths(home.resolve(".temper"));
        files = UnitFiles.unitFiles(paths);
        workspace = Files.createDirectories(paths.workspace(ref.job()));
        ok(files.store(ref,
                       UnitFile.MANIFEST_SPEC,
                       ok(TemperJson.mapper()
                                    .writeAsString(ManifestSpecDto.manifestSpecDto(ManifestSpec.manifestSpec(List.of("mvn"),
                                                                                                             Reach.TEST,
                                                                                                             Map.of(),
                                                                                                             none(),
                                                                                                             none()))))));
        effectivePom(project("core"), project("api"));
        // core: two test classes; FooTest inherits its only test from an abstract base compiled into MAIN classes
        classFile("core/target/test-classes", "org.acme.BarTest", "java.lang.Object", false, "test");
        classFile("core/target/test-classes", "org.acme.FooTest", "org.acme.BaseTest", false);
        classFile("core/target/classes", "org.acme.BaseTest", "java.lang.Object", true, "inherited");
        classFile("core/target/classes", "org.acme.MainTest", "java.lang.Object", false, "test");
        // api: test sources, never compiled
        Files.createDirectories(workspace.resolve("api/src/test/java/org/acme"));
        Files.writeString(workspace.resolve("api/src/test/java/org/acme/ApiTest.java"), "class ApiTest {}");
    }

    private String project(String module) {
        return ManifestFixtures.project(workspace, module);
    }

    private void effectivePom(String... projects) {
        ok(UnitFiles.storeAtomic(paths.effectivePom(ref), "<projects>" + String.join("", projects) + "</projects>"));
    }

    private Path classFile(String directory, String name, String superName, boolean isAbstract, String... testMethods) throws Exception {
        var file = workspace.resolve(directory)
                            .resolve(name.replace('.', '/') + ".class");

        Files.createDirectories(file.getParent());
        return Files.write(file, ManifestFixtures.classBytes(name, superName, isAbstract, testMethods));
    }

    private ManifestOutcomeDto collect() {
        return ok(ManifestCollector.manifestCollector(paths, files, TemperJson.mapper())
                                   .collect(ref)
                                   .await()).or(() -> new ManifestOutcomeDto.Unsupported("no manifest"));
    }

    private static ManifestEntryDto entry(String className) {
        return new ManifestEntryDto("core", className, "surefire");
    }

    @Test
    void testClassesAreEntries_mainClassesOnlyResolveSupertypes_andAnUncompiledModuleIsNotReached() {
        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                     computed -> assertThat(computed).extracting(ManifestOutcomeDto.Computed::entries,
                                                                                                 ManifestOutcomeDto.Computed::notReached)
                                                                                     .containsExactly(List.of(entry("org.acme.BarTest"),
                                                                                                              entry("org.acme.FooTest")),
                                                                                                      List.of("api")));
    }

    @Test
    void theOutcome_isRecordedForAResend() {
        var computed = collect();

        assertThat(ok(ManifestCollector.manifestCollector(paths, files, TemperJson.mapper())
                                       .recorded(ref))).isEqualTo(some(computed));
    }

    /// `module-info.class` and `package-info.class` are not classes: never read (a malformed one changes nothing).
    @Test
    void moduleAndPackageInfo_areNotRead() throws Exception {
        Files.write(workspace.resolve("core/target/test-classes/module-info.class"), new byte[] {1, 2, 3});
        Files.write(workspace.resolve("core/target/test-classes/org/acme/package-info.class"), new byte[] {1, 2, 3});

        assertThat(collect()).isInstanceOf(ManifestOutcomeDto.Computed.class);
    }

    /// A module with neither test sources nor test output has nothing to reach: it is not `notReached`.
    @Test
    void aModuleWithoutTestSources_isNotNotReached() {
        effectivePom(project("core"), project("api"), project("docs"));

        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                     computed -> assertThat(computed.notReached()).containsExactly("api"));
    }

    /// Test sources are counted as regular files only: a source directory holding nothing but a symlink has no tests
    /// to reach.
    @Test
    void aTestSourceDirectoryWithOnlyASymlink_isNotNotReached() throws Exception {
        var sources = Files.createDirectories(workspace.resolve("docs/src/test/java"));

        Files.createSymbolicLink(sources.resolve("Linked.java"), workspace.resolve("api/src/test/java/org/acme/ApiTest.java"));
        effectivePom(project("core"), project("api"), project("docs"));

        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                     computed -> assertThat(computed.notReached()).containsExactly("api"));
    }

    /// R-11: a JUnit 3 test class (a `junit.framework.TestCase` subclass) makes the manifest `Unsupported`, the
    /// reason naming it, and the refusal survives the recorded copy a resend sends.
    @Test
    void aJunit3Class_isUnsupported_andStaysSoInTheRecordedCopy() throws Exception {
        classFile("core/target/test-classes", "org.acme.LegacyTest", "junit.framework.TestCase", false);

        var outcome = collect();

        assertThat(outcome).isEqualTo(new ManifestOutcomeDto.Unsupported("JUnit 3/4 tests are not supported (Jupiter only): module core, class org.acme.LegacyTest"));
        assertThat(ok(ManifestCollector.manifestCollector(paths, files, TemperJson.mapper())
                                       .recorded(ref))).isEqualTo(some(outcome));
    }

    /// A symlinked class file is not read: its class is no entry even though the file it points at is valid.
    @Test
    void aSymlinkedClassFile_isNotRead() throws Exception {
        var elsewhere = home.resolve("elsewhere/org/acme/LinkedTest.class");

        Files.createDirectories(elsewhere.getParent());
        Files.write(elsewhere, ManifestFixtures.classBytes("org.acme.LinkedTest", "java.lang.Object", false, "test"));
        Files.createSymbolicLink(workspace.resolve("core/target/test-classes/org/acme/LinkedTest.class"), elsewhere);

        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                     computed -> assertThat(computed.entries()).doesNotContain(entry("org.acme.LinkedTest")));
    }

    /// A test output directory that resolves outside the workspace is refused, not scanned.
    @Test
    void anOutputDirectoryEscapingTheWorkspace_isUnsupported() throws Exception {
        var outside = Files.createDirectories(home.resolve("outside"));
        var target = workspace.resolve("core/target");

        Files.write(Files.createDirectories(outside.resolve("org/acme"))
                         .resolve("EvilTest.class"),
                    ManifestFixtures.classBytes("org.acme.EvilTest", "java.lang.Object", false, "test"));
        Files.walk(target)
             .sorted(Comparator.reverseOrder())
             .forEach(path -> path.toFile()
                                  .delete());
        Files.createDirectories(target);
        Files.createSymbolicLink(target.resolve("test-classes-real"), outside);
        Files.createSymbolicLink(workspace.resolve("core/target/test-classes"), outside);

        // test-classes itself is a symlink: not a directory to scan, so not read either way
        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Computed.class,
                                                     computed -> assertThat(computed.entries()).doesNotContain(entry("org.acme.EvilTest")));

        Files.delete(workspace.resolve("core/target/test-classes"));
        Files.delete(target.resolve("test-classes-real"));
        Files.delete(target);
        Files.createSymbolicLink(target, outside.getParent());
        Files.createDirectories(outside.getParent()
                                       .resolve("test-classes"));

        // an intermediate component is a symlink out of the workspace: refused
        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Unsupported.class,
                                                     refused -> assertThat(refused.reason()).startsWith("Path outside the workspace"));
    }

    /// A test source directory reached through a symlink out of the workspace is refused, not walked.
    @Test
    void aTestSourceDirectoryEscapingTheWorkspace_isUnsupported() throws Exception {
        var outside = Files.createDirectories(home.resolve("outside-ext/src/test/java"));

        Files.writeString(outside.resolve("ExtTest.java"), "class ExtTest {}");
        Files.createSymbolicLink(workspace.resolve("ext"), home.resolve("outside-ext"));
        effectivePom(project("core"), project("ext"));

        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Unsupported.class,
                                                     refused -> assertThat(refused.reason()).startsWith("Path outside the workspace"));
    }

    /// Q-M1-7: a unit that asked for a manifest never passes without one. A spec that cannot be read and an outcome
    /// that cannot be recorded are `Unsupported`; a resend with no recorded outcome says so.
    @Test
    void anUnreadableSpec_orAnUnrecordableOutcome_isUnsupported() throws Exception {
        ok(files.store(ref, UnitFile.MANIFEST_SPEC, "not json"));

        assertThat(collect()).isInstanceOf(ManifestOutcomeDto.Unsupported.class);

        setUp();
        Files.delete(paths.manifestFile(ref));
        Files.createDirectories(paths.manifestFile(ref)
                                     .resolve("occupied"));

        assertThat(collect()).isInstanceOf(ManifestOutcomeDto.Unsupported.class);
    }

    /// The reason carries no `manifest: ` prefix of its own (F8): the store prefixes an `Unsupported` reason, so the unit
    /// reason reads `manifest: the outcome…`, not `manifest: manifest: …`.
    @Test
    void aResend_withNoRecordedOutcome_isUnsupported() {
        assertThat(ok(ManifestCollector.manifestCollector(paths, files, TemperJson.mapper())
                                       .recorded(ref))).isEqualTo(some(new ManifestOutcomeDto.Unsupported(WorkerError.General.MANIFEST_NOT_RECORDED.message())));
        assertThat(WorkerError.General.MANIFEST_NOT_RECORDED.message()).isEqualTo("the outcome was not recorded when the exit was processed")
                                                                     .doesNotStartWith("manifest");
    }

    @Test
    void aMalformedClassFile_isUnsupported() throws Exception {
        Files.writeString(workspace.resolve("core/target/test-classes/org/acme/BrokenTest.class"), "not a class");

        assertThat(collect()).isInstanceOfSatisfying(ManifestOutcomeDto.Unsupported.class,
                                                     refused -> assertThat(refused.reason()).isEqualTo("Malformed class file org/acme/BrokenTest.class"));
    }

    @Test
    void aMissingEffectivePom_isUnsupported() throws Exception {
        Files.delete(paths.effectivePom(ref));

        assertThat(collect()).isInstanceOf(ManifestOutcomeDto.Unsupported.class);
    }

    @Test
    void aUnitWithoutAManifestSpec_hasNoManifest() throws Exception {
        Files.delete(files.path(ref, UnitFile.MANIFEST_SPEC));

        assertThat(ok(ManifestCollector.manifestCollector(paths, files, TemperJson.mapper())
                                       .collect(ref)
                                       .await())).isEqualTo(none());
    }
}
