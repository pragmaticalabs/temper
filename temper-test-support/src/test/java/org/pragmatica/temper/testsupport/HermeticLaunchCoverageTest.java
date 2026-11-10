package org.pragmatica.temper.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Build-level checks that the hermetic launch rule (#121, #128) is applied everywhere. Home: this module's tests, because it
/// is the first reactor module, so every full build runs it, and it needs only the root pom and the module directories.
class HermeticLaunchCoverageTest {
    private static final String ARCH_TEST = "HermeticLaunchArchTest.java";

    /// Reactor modules that have `src/test/java` but no `HermeticLaunchArchTest`, each with its reason. The second test below
    /// fails if an exempt module's tests name a launch API, so an exemption cannot go stale.
    private static final Map<String, String> EXEMPT = Map.of("github-app-auth",
                                                             "no test launches a child process",
                                                             "temper-domain",
                                                             "no test launches a child process",
                                                             "temper-store",
                                                             "no test launches a child process",
                                                             "kiln-core",
                                                             "no test launches a child process",
                                                             "temper-merge",
                                                             "no test launches a child process");

    private static final Pattern LAUNCH_API = Pattern.compile("ProcessBuilder|Runtime\\s*\\.\\s*getRuntime|HermeticProcess");

    /// A launch class name in source form (`java.lang.ProcessBuilder`), JVM internal form (`java/lang/ProcessBuilder`) or inside
    /// a descriptor (`Ljava/lang/ProcessBuilder;`): every name a `Class.forName`, `MethodHandles.Lookup.findClass` or
    /// descriptor-based lookup could take. Matched against the contents of string and text-block literals only (`lex`), so
    /// a comment naming a class is not flagged. `\\b` keeps `java.lang.RuntimeException` out.
    static final Pattern LAUNCH_CLASS_NAME = Pattern.compile("java[./]lang[./](ProcessBuilder|Runtime|ProcessImpl)\\b");

    /// The only accepted `HermeticLaunchArchTest` body, comments and whitespace aside: the whole own test output
    /// (`OwnTestOutput`), no import options, every shared rule (`ArchTests.in(HermeticLaunchRules.class)`), nothing disabled.
    private static final String ARCH_TEST_BODY = """
        @AnalyzeClasses(locations = OwnTestOutput.class)
        class HermeticLaunchArchTest {
            @ArchTest
            static final ArchTests launches = ArchTests.in(HermeticLaunchRules.class);
        }
        """;

    private static final String SUPPORT_PACKAGE = "org.pragmatica.temper.testsupport";

    private static final Set<String> ARCH_TEST_IMPORTS = Set.of("com.tngtech.archunit.junit.AnalyzeClasses",
                                                                "com.tngtech.archunit.junit.ArchTest",
                                                                "com.tngtech.archunit.junit.ArchTests");

    @Test
    void everyReactorModuleWithTestsHasTheArchTestOrAReasonedExemption() throws IOException {
        var root = reactorRoot();
        var missing = reactorModules(root).stream()
                                    .filter(module -> Files.isDirectory(root.resolve(module).resolve("src/test/java")))
                                    .filter(module -> !hasArchTest(root.resolve(module)))
                                    .filter(module -> !EXEMPT.containsKey(module))
                                    .toList();

        assertThat(missing).as("modules with tests but neither a HermeticLaunchArchTest nor an EXEMPT entry").isEmpty();
    }

    @Test
    void exemptModulesStillLaunchNothingAndStillHaveNoArchTest() throws IOException {
        var root = reactorRoot();
        var modules = reactorModules(root);

        assertThat(modules).containsAll(EXEMPT.keySet());
        for (var module : EXEMPT.keySet()) {
            assertThat(hasArchTest(root.resolve(module))).as(module + " has the ArchTest: drop its EXEMPT entry")
                      .isFalse();
            assertThat(sourcesWhere(root.resolve(module),
                                    text -> LAUNCH_API.matcher(text).find())).as(module + " names a launch API: add the ArchTest")
                      .isEmpty();
        }
    }

    /// A file name alone proves nothing (#134 review F1): an ArchTest reverted to a package filter, emptied, or given import
    /// options compiles and passes while checking less. Each one must be the canonical body, and its module must carry the
    /// ArchUnit JUnit engine (`archunit-junit5`), without which the `@ArchTest` fields compile and never run.
    ///
    /// Why a source pin and not one central evaluation of every module's `target/test-classes`: this module is the first in the
    /// reactor, so in a clean build no other test output exists yet when this runs, and under `-T` a later module may be
    /// mid-compile; a central check would examine stale classes or none and still pass. Sources exist before any compile.
    /// What the pin leaves to the run itself: the import is non-empty (the two launch rules fail on an empty import).
    @Test
    void everyArchTestAppliesEverySharedRuleToItsWholeTestOutput() throws IOException {
        var root = reactorRoot();
        var deviations = new ArrayList<String>();

        for (var module : reactorModules(root)) {
            var archTests = archTests(root.resolve(module));

            for (var file : archTests) {
                deviation(file).ifPresent(reason -> deviations.add(module + ": " + root.relativize(file) + ": " + reason));
            }
            if (!archTests.isEmpty() && !Files.readString(root.resolve(module).resolve("pom.xml"))
                                              .contains("<artifactId>archunit-junit5</artifactId>")) {
                deviations.add(module + ": pom.xml lacks archunit-junit5, so the ArchTest never runs");
            }
        }

        assertThat(deviations).as("HermeticLaunchArchTest files that do not apply every shared rule to the whole test output")
                              .isEmpty();
    }

    /// Reflection is invisible to bytecode rules; the cheap net is the text: a reflective launch must spell out the class name.
    /// Every string and text-block literal of every test source is scanned, escapes undecoded. What this misses (a name
    /// assembled from pieces, reflection with no name or class literal) is the accepted gap stated on `HermeticLaunchRules`.
    @Test
    void noTestSourceNamesAProcessLaunchClassAsAString() throws IOException {
        var root = reactorRoot();
        var offenders = reactorModules(root).stream()
                                      .flatMap(module -> sourcesWhere(root.resolve(module),
                                                                      HermeticLaunchCoverageTest::namesALaunchClass).stream())
                                      .toList();

        assertThat(offenders).as("test sources that load a launch class by name").isEmpty();
    }

    private static boolean hasArchTest(Path module) {
        return !archTests(module).isEmpty();
    }

    private static List<Path> archTests(Path module) {
        var tests = module.resolve("src/test/java");

        if (!Files.isDirectory(tests)) {
            return List.of();
        }

        try (Stream<Path> files = Files.walk(tests)) {
            return files.filter(path -> path.getFileName()
                                            .toString()
                                            .equals(ARCH_TEST))
                        .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Why `file` is not the canonical ArchTest, if it is not. Comments and whitespace are ignored; the package is free; the
    /// imports must be exactly the ArchUnit three plus, outside the support package, `HermeticLaunchRules` and `OwnTestOutput`.
    static java.util.Optional<String> deviation(Path file) throws IOException {
        var text = lex(Files.readString(file)).code();
        var pkg = "";
        var imports = new TreeSet<String>();
        var body = new StringBuilder();

        for (var line : text.split("\\n")) {
            var trimmed = line.trim();

            if (trimmed.startsWith("package ")) {
                pkg = trimmed.substring(8, trimmed.length() - 1).trim();
            } else if (trimmed.startsWith("import ")) {
                imports.add(trimmed.substring(7, trimmed.length() - 1).trim());
            } else {
                body.append(trimmed);
            }
        }

        var expectedImports = new TreeSet<>(ARCH_TEST_IMPORTS);

        if (!pkg.equals(SUPPORT_PACKAGE)) {
            expectedImports.add(SUPPORT_PACKAGE + ".HermeticLaunchRules");
            expectedImports.add(SUPPORT_PACKAGE + ".OwnTestOutput");
        }
        if (!imports.equals(expectedImports)) {
            return java.util.Optional.of("imports " + imports + ", expected " + expectedImports);
        }
        if (!squeeze(body.toString()).equals(squeeze(ARCH_TEST_BODY))) {
            return java.util.Optional.of("body is not the canonical one (OwnTestOutput import, ArchTests.in(HermeticLaunchRules.class))");
        }

        return java.util.Optional.empty();
    }

    private static String squeeze(String text) {
        return text.replaceAll("\\s+", "");
    }

    private static List<String> sourcesWhere(Path module, Predicate<String> test) {
        var tests = module.resolve("src/test/java");

        if (!Files.isDirectory(tests)) {
            return List.of();
        }

        try (Stream<Path> files = Files.walk(tests)) {
            return files.filter(path -> path.toString()
                                            .endsWith(".java"))
                        .filter(path -> test.test(read(path)))
                        .map(Path::toString)
                        .toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Whether a string or text-block literal of `source` contains a launch class name. Comments are not literals.
    static boolean namesALaunchClass(String source) {
        return lex(source).literals()
                          .stream()
                          .anyMatch(literal -> LAUNCH_CLASS_NAME.matcher(literal)
                                                                .find());
    }

    record Lexed(String code, List<String> literals) {}

    /// `source` split by one left-to-right scan: `code` is the source with every comment removed (a block comment keeps its
    /// line breaks, a line comment its terminating one), `literals` the raw contents of every string and text-block literal,
    /// escapes undecoded. States: code, line comment, block comment, string, char and text block; `//` and `/*` start a
    /// comment only in the code state, and a backslash in a literal skips the next character. Unicode escapes (`\u0022`)
    /// are not translated first, unlike javac.
    static Lexed lex(String source) {
        var code = new StringBuilder();
        var literals = new ArrayList<String>();
        var length = source.length();
        var at = 0;

        while (at < length) {
            var start = at;

            if (source.startsWith("//", at)) {
                at = source.indexOf('\n', at);
                at = at < 0 ? length : at;
            } else if (source.startsWith("/*", at)) {
                var end = source.indexOf("*/", at + 2);
                at = end < 0 ? length : end + 2;
                source.substring(start, at)
                      .chars()
                      .filter(c -> c == '\n')
                      .forEach(c -> code.append('\n'));
            } else if (source.startsWith("\"\"\"", at)) {
                at += 3;
                while (at < length && !source.startsWith("\"\"\"", at)) {
                    at += source.charAt(at) == '\\' ? 2 : 1;
                }
                literals.add(source.substring(start + 3, Math.min(at, length)));
                at = Math.min(at + 3, length);
                code.append(source, start, at);
            } else if (source.charAt(at) == '"' || source.charAt(at) == '\'') {
                var quote = source.charAt(at++);
                while (at < length && source.charAt(at) != quote && source.charAt(at) != '\n') {
                    at += source.charAt(at) == '\\' ? 2 : 1;
                }
                if (quote == '"') {
                    literals.add(source.substring(start + 1, Math.min(at, length)));
                }
                at = Math.min(at + 1, length);
                code.append(source, start, at);
            } else {
                code.append(source.charAt(at++));
            }
        }

        return new Lexed(code.toString(), literals);
    }

    /// Reactor modules from the root pom, including those declared in profiles (temper-acceptance).
    private static List<String> reactorModules(Path root) throws IOException {
        var matcher = Pattern.compile("<module>([^<]+)</module>").matcher(Files.readString(root.resolve("pom.xml")));
        var modules = new java.util.ArrayList<String>();

        while (matcher.find()) {
            modules.add(matcher.group(1).trim());
        }

        return modules;
    }

    /// The directory above this module whose pom declares `temper-test-support` as a module.
    private static Path reactorRoot() throws IOException {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null) {
            var pom = dir.resolve("pom.xml");

            if (Files.exists(pom) && Files.readString(pom).contains("<module>temper-test-support</module>")) {
                return dir;
            }

            dir = dir.getParent();
        }

        throw new IllegalStateException("no reactor root above " + Path.of("").toAbsolutePath());
    }
}
