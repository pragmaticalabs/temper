package org.pragmatica.temper.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.tools.ToolProvider;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// Pins the reach of each hermetic launch net (#128, #134 review F1/F2) one form at a time, so a narrowed rule, pattern or pin
/// goes red here rather than silently passing every module. The bytecode fixtures are compiled into a temporary directory at
/// test time: placed in this module's test output they would fail this module's own `HermeticLaunchArchTest`.
class HermeticLaunchNetTest {
    /// Built from pieces so this file's own literals never contain a launch class name.
    private static final String JAVA = "java";

    @TempDir
    Path dir;

    // --- bytecode rules: each launch form is red on exactly the rule meant to catch it

    @Test
    void constructorCall_isCaughtByNoRawProcessBuilder() throws IOException {
        assertOnlyViolated(HermeticLaunchRules.noRawProcessBuilder, "Object run() { return new ProcessBuilder(\"true\"); }");
    }

    @Test
    void constructorReference_isCaughtByNoRawProcessBuilder() throws IOException {
        assertOnlyViolated(HermeticLaunchRules.noRawProcessBuilder,
                           "Object run() { java.util.function.Function<String[], ProcessBuilder> f = ProcessBuilder::new; return f.apply(new String[] {\"true\"}); }");
    }

    @Test
    void execCall_isCaughtByNoRuntimeExec() throws IOException {
        assertOnlyViolated(HermeticLaunchRules.noRuntimeExec, "Object run() throws Exception { return Runtime.getRuntime().exec(new String[] {\"true\"}); }");
    }

    @Test
    void execReference_isCaughtByNoRuntimeExec() throws IOException {
        assertOnlyViolated(HermeticLaunchRules.noRuntimeExec,
                           "interface Exec { Process run(String[] c) throws java.io.IOException; } Object run() { Exec e = Runtime.getRuntime()::exec; return e; }");
    }

    @Test
    void reflectionFromAClassLiteral_isCaughtByNoLaunchClassLiteral() throws IOException {
        assertOnlyViolated(HermeticLaunchRules.noLaunchClassLiteral,
                           "Object run() throws Exception { return ProcessBuilder.class.getConstructor(String[].class); }");
    }

    @Test
    void methodHandleLookupFromAClassLiteral_isCaughtByNoLaunchClassLiteral() throws IOException {
        assertOnlyViolated(HermeticLaunchRules.noLaunchClassLiteral,
                           "Object run() throws Exception { return java.lang.invoke.MethodHandles.lookup().findVirtual(Runtime.class, \"exec\", java.lang.invoke.MethodType.methodType(Process.class, String[].class)); }");
    }

    @Test
    void controlFixture_launchingNothing_violatesNoRule() throws IOException {
        assertOnlyViolated(null, "Object run() { return String.valueOf(Runtime.version()) + ProcessHandle.current().pid(); }");
    }

    // --- text net: every literal form of a launch class name, and what it must leave alone

    @Test
    void sourceFormLiteral_isCaught() {
        assertThat(caught("Class.forName(\"" + JAVA + ".lang.ProcessBuilder\")")).isTrue();
    }

    @Test
    void internalFormLiteral_isCaught() {
        assertThat(caught("lookup.findClass(\"" + JAVA + "/lang/ProcessBuilder\")")).isTrue();
    }

    @Test
    void mixedSeparatorLiteral_isCaught() {
        assertThat(caught("var n = \"" + JAVA + "/lang.ProcessImpl\";")).isTrue();
    }

    @Test
    void descriptorLiteral_isCaught() {
        assertThat(caught("MethodType.fromMethodDescriptorString(\"()L" + JAVA + "/lang/Runtime;\", null)")).isTrue();
    }

    @Test
    void longerClassNameAndRunTimeAssembly_areNotCaught() {
        assertThat(caught("throw new IllegalStateException(\"" + JAVA + ".lang.RuntimeException\")")).isFalse();
        assertThat(caught("Class.forName(\"" + JAVA + ".lang.\" + \"ProcessBuilder\")")).as("the accepted gap").isFalse();
    }

    @Test
    void textBlockLiteral_isCaught() {
        assertThat(caught("var n = \"\"\"\n    " + JAVA + ".lang.ProcessBuilder\"\"\";")).isTrue();
    }

    @Test
    void textBlockHoldingCommentMarkers_isStillALiteral() {
        assertThat(caught("var n = \"\"\"\n    // not a comment\n    /* nor this " + JAVA + "/lang/Runtime */\n    \"\"\";")).isTrue();
    }

    @Test
    void commentMarkersInsideStrings_doNotHideALaterLiteral() {
        assertThat(caught("var a = \"//\"; Class.forName(\"" + JAVA + ".lang.ProcessBuilder\");")).as("// in a string")
                                                                                                       .isTrue();
        assertThat(caught("var a = \"/*\"; Class.forName(\"" + JAVA + ".lang.ProcessBuilder\"); var b = \"*/\";"))
                  .as("/* and */ in strings")
                  .isTrue();
        assertThat(caught("var q = '\"'; Class.forName(\"" + JAVA + ".lang.ProcessBuilder\");")).as("a quote char literal")
                                                                                                      .isTrue();
    }

    @Test
    void nameInACommentOrJavadoc_isNotCaught() {
        assertThat(caught("// see \"" + JAVA + ".lang.ProcessBuilder\"\nint x;")).as("line comment").isFalse();
        assertThat(caught("/// markdown doc: `\"" + JAVA + "/lang/Runtime\"`\nint x;")).as("markdown javadoc").isFalse();
        assertThat(caught("/** {@code \"" + JAVA + ".lang.ProcessBuilder\"} */ int x;")).as("javadoc").isFalse();
        assertThat(caught("/* \"" + JAVA + ".lang.ProcessImpl\" */ int x;")).as("block comment").isFalse();
    }

    // --- ArchTest pin: the canonical body passes, each weakening is named

    @Test
    void canonicalArchTest_hasNoDeviation() throws IOException {
        assertThat(deviation(archTest("@AnalyzeClasses(locations = OwnTestOutput.class)", "ArchTests.in(HermeticLaunchRules.class)"))).isEmpty();
    }

    @Test
    void packageFilteredArchTest_deviates() throws IOException {
        assertThat(deviation(archTest("@AnalyzeClasses(packages = \"org.pragmatica\")", "ArchTests.in(HermeticLaunchRules.class)"))).isPresent();
    }

    @Test
    void importOptionsOnTheArchTest_deviate() throws IOException {
        assertThat(deviation(archTest("@AnalyzeClasses(locations = OwnTestOutput.class, importOptions = ImportOption.DoNotIncludeTests.class)",
                                      "ArchTests.in(HermeticLaunchRules.class)"))).isPresent();
    }

    @Test
    void emptiedArchTest_deviates() throws IOException {
        var file = dir.resolve("HermeticLaunchArchTest.java");
        Files.writeString(file, "package fx;\n\nclass HermeticLaunchArchTest {\n}\n");

        assertThat(deviation(file)).isPresent();
    }

    @Test
    void disabledArchTest_deviates() throws IOException {
        assertThat(deviation(archTest("@org.junit.jupiter.api.Disabled @AnalyzeClasses(locations = OwnTestOutput.class)",
                                      "ArchTests.in(HermeticLaunchRules.class)"))).isPresent();
    }

    @Test
    void disabledSwallowedBetweenLineComments_deviates() throws IOException {
        assertThat(deviation(archTest("@AnalyzeClasses(locations = OwnTestOutput.class)\n// /*\n@org.junit.jupiter.api.Disabled\n// */",
                                      "ArchTests.in(HermeticLaunchRules.class)"))).isPresent();
    }

    @Test
    void hostTagSwallowedBetweenLineComments_deviates() throws IOException {
        assertThat(deviation(archTest("@AnalyzeClasses(locations = OwnTestOutput.class)\n// /*\n@org.junit.jupiter.api.Tag(\"host\")\n// */",
                                      "ArchTests.in(HermeticLaunchRules.class)"))).isPresent();
    }

    @Test
    void commentsHoldingTheOtherCommentMarker_doNotDeviate() throws IOException {
        assertThat(deviation(archTest("// a line comment with /* in it\n@AnalyzeClasses(locations = OwnTestOutput.class) /* a block with // in it */",
                                      "ArchTests.in(HermeticLaunchRules.class)"))).isEmpty();
    }

    private static boolean caught(String source) {
        return HermeticLaunchCoverageTest.namesALaunchClass(source);
    }

    private static Optional<String> deviation(Path file) throws IOException {
        return HermeticLaunchCoverageTest.deviation(file);
    }

    private Path archTest(String annotation, String rules) throws IOException {
        var file = dir.resolve("HermeticLaunchArchTest.java");
        Files.writeString(file, """
            package fx;

            import com.tngtech.archunit.junit.AnalyzeClasses;
            import com.tngtech.archunit.junit.ArchTest;
            import com.tngtech.archunit.junit.ArchTests;
            import org.pragmatica.temper.testsupport.HermeticLaunchRules;
            import org.pragmatica.temper.testsupport.OwnTestOutput;

            /// a comment is free
            %s
            class HermeticLaunchArchTest {
                @ArchTest
                static final ArchTests launches = %s;
            }
            """.formatted(annotation, rules));
        return file;
    }

    /// `expected` is the one rule that must be violated (null: none may be); the other launch rules must stay green, so each
    /// form is attributed to the rule that owns it.
    private void assertOnlyViolated(ArchRule expected, String member) throws IOException {
        var classes = compile("package fx; public class Probe { " + member + " }");

        for (var rule : List.of(HermeticLaunchRules.noRawProcessBuilder,
                                HermeticLaunchRules.noRuntimeExec,
                                HermeticLaunchRules.noLaunchClassLiteral)) {
            assertThat(rule.evaluate(classes)
                           .hasViolation()).as(rule.getDescription())
                                           .isEqualTo(rule == expected);
        }
    }

    private JavaClasses compile(String source) throws IOException {
        var src = Files.createDirectories(dir.resolve("src/fx"))
                       .resolve("Probe.java");
        var out = Files.createDirectories(dir.resolve("out"));
        Files.writeString(src, source);

        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("a JDK compiler").isNotNull();
        assertThat(compiler.run(null, null, null, "-d", out.toString(), src.toString())).as("fixture compiles").isZero();

        return new ClassFileImporter().importPath(out);
    }
}
