package org.pragmatica.kiln.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

import static org.assertj.core.api.Assertions.assertThat;

/// kiln-f1-design §1 guard for THIS module (kiln-service): none of its classes, main or test, refers to temper's JSON codec
/// (`protocol.json`: kiln never decodes with `TemperJson`, spec §4.5) or temper's store (D-1: kiln never touches
/// temper's store), and its pom does not depend on `temper-store`. Every `kiln-*` module carries its own copy of this
/// class (the structural test below checks that), so the guard reads only its own module's output: a `-pl` build or
/// the reactor order cannot leave it without classes to read.
///
/// The check reads the compiled classes, not the sources: javac resolves every spelling of a name (a spaced dot, a
/// comment inside it, a unicode escape, a line break) to one internal name in the constant pool, where a text scan of
/// the source sees none of them. Every UTF-8 constant is searched, so class references, descriptors, signatures and
/// string constants (`Class.forName("...")`, a constant concatenation included, in the dotted form too) are all found.
/// Accepted residuals, none of them a link to the forbidden classes: a class name built at run time
/// (`Class.forName(pkg + "json.X")` with a non-constant part) is not runtime-linked and leaves no whole name to find; an
/// unused import and a compile-time constant javac inlines leave no trace in a class.
///
/// `target/classes` and `target/test-classes` must each hold at least one class, so a module not yet compiled fails the
/// check instead of passing it with nothing read. The forbidden names are joined at run time, so this class's own
/// constants never match.
class NoTemperJsonTest {
    private static final List<String> FORBIDDEN = Stream.of(List.of("protocol", "json"), List.of("store"))
                                                        .flatMap(NoTemperJsonTest::bothForms)
                                                        .toList();
    private static final Path ROOT = Path.of("..");
    private static final List<String> OUTPUTS = List.of("target/classes", "target/test-classes");
    private static final String GUARD = "NoTemperJsonTest.java";

    private static Stream<String> bothForms(List<String> tail) {
        var parts = new ArrayList<>(List.of("org", "pragmatica", "temper"));

        parts.addAll(tail);
        return Stream.of(String.join("/", parts) + "/", String.join(".", parts) + ".");
    }

    @Test
    void moduleClasses_neverReferToTemperJsonOrTemperStore() throws IOException {
        var offenders = new ArrayList<String>();

        for (var output : OUTPUTS) {
            var classes = classFiles(Path.of(output));

            assertThat(classes).as("class files under %s", output)
                               .isNotEmpty();
            for (var file : classes) {
                forbiddenNames(file).forEach(name -> offenders.add(file + ": " + name));
            }
        }
        assertThat(offenders).as("kiln-service classes referring to temper's JSON codec or store")
                             .isEmpty();
    }

    @Test
    void modulePom_neverDependsOnTemperStore() throws Exception {
        assertThat(dependencyArtifacts(Path.of("pom.xml"))).as("artifactIds of every kiln-service pom dependency")
                                                           .contains("kiln-core", "temper-protocol")
                                                           .doesNotContain("temper-store");
    }

    /// Every `kiln-*` module has its own guard declaring the class-scan test, so a module added later cannot go
    /// unguarded.
    @Test
    void everyKilnModule_hasItsOwnGuard() throws IOException {
        var modules = kilnModules();

        assertThat(modules).extracting(module -> module.getFileName()
                                                       .toString())
                           .contains("kiln-core", "kiln-service", "kiln-service");
        for (var module : modules) {
            assertThat(guards(module)).as("%s declaring the class-scan test in %s/src/test/java", GUARD, module)
                                      .isNotEmpty();
        }
    }

    /// The instrument itself: the constant pool is read (this class's own reference to AssertJ is found), and the
    /// predicate matches both forbidden packages in either form but not a package that merely starts the same.
    @Test
    void instrument_readsTheConstantPool_andMatchesOnlyTheForbiddenPackages() throws IOException {
        var self = Path.of("target/test-classes/org/pragmatica/kiln/service/NoTemperJsonTest.class");
        var temper = String.join("/", "org", "pragmatica", "temper");

        assertThat(utf8Constants(self)).contains("org/assertj/core/api/Assertions");
        assertThat(forbiddenNames(self)).isEmpty();
        assertThat(forbidden("L" + temper + "/store/api/Store;")).isTrue();
        assertThat(forbidden(temper + "/protocol/json/TemperJson")).isTrue();
        assertThat(forbidden(temper.replace('/', '.') + ".protocol.json.TemperJson")).isTrue();
        assertThat(forbidden(temper.replace('/', '.') + ".store.pg.Db")).isTrue();
        assertThat(forbidden(temper + "/protocol/exec/ProcessRunner")).isFalse();
        assertThat(forbidden(temper + "/storex/X")).isFalse();
        assertThat(forbidden(temper + "/protocol/jsonx/X")).isFalse();
    }

    private static List<Path> kilnModules() throws IOException {
        try (var entries = Files.list(ROOT)) {
            return entries.filter(Files::isDirectory)
                          .filter(dir -> dir.getFileName()
                                            .toString()
                                            .startsWith("kiln-"))
                          .sorted()
                          .toList();
        }
    }

    private static List<Path> guards(Path module) throws IOException {
        var tests = module.resolve("src/test/java");

        if (!Files.isDirectory(tests)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(tests)) {
            return files.filter(file -> file.getFileName()
                                            .toString()
                                            .equals(GUARD))
                        .filter(NoTemperJsonTest::declaresTheClassScan)
                        .toList();
        }
    }

    /// A guard file counts only if it declares the class-scan test, so an empty or gutted copy does not.
    private static boolean declaresTheClassScan(Path file) {
        try {
            var source = Files.readString(file);

            return source.contains("@Test") && source.contains("void moduleClasses_neverReferToTemperJsonOrTemperStore()");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> classFiles(Path output) throws IOException {
        if (!Files.isDirectory(output)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(output)) {
            return files.filter(file -> file.toString()
                                            .endsWith(".class"))
                        .toList();
        }
    }

    private static List<String> forbiddenNames(Path classFile) throws IOException {
        return utf8Constants(classFile).stream()
                                       .filter(NoTemperJsonTest::forbidden)
                                       .toList();
    }

    private static List<String> utf8Constants(Path classFile) throws IOException {
        var pool = ClassFile.of()
                            .parse(Files.readAllBytes(classFile))
                            .constantPool();

        return StreamSupport.stream(pool.spliterator(), false)
                            .filter(Utf8Entry.class::isInstance)
                            .map(entry -> ((Utf8Entry) entry).stringValue())
                            .toList();
    }

    private static boolean forbidden(String constant) {
        return FORBIDDEN.stream()
                        .anyMatch(constant::contains);
    }

    /// Every `<dependency>`'s artifactId, wherever it sits (dependencies, dependency management, profiles, plugins);
    /// an `<exclusion>` is not a dependency.
    private static List<String> dependencyArtifacts(Path pom) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();

        factory.setNamespaceAware(true);

        var document = factory.newDocumentBuilder()
                              .parse(pom.toFile());
        var nodes = (NodeList) XPathFactory.newInstance()
                                           .newXPath()
                                           .evaluate("//*[local-name()='dependency']/*[local-name()='artifactId']",
                                                     document,
                                                     XPathConstants.NODESET);
        var artifacts = new ArrayList<String>();

        for (var i = 0; i < nodes.getLength(); i++) {
            artifacts.add(nodes.item(i)
                               .getTextContent()
                               .trim());
        }
        return artifacts;
    }
}
