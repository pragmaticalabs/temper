package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/// `ops/dev/build`, `build-args`, `build-lock` and `build-report` (owner decisions D-2/D-3, docs/developer-guide.md
/// "Build tooling") on a fixture tree (root pom with `alpha` (sources, tests, and a second surefire execution
/// `one-carrier` reporting to its own directory, as temper-controller), `beta` (sources and tests), `gamma` (tests only,
/// run by a declared failsafe execution, as temper-dist) and `extra` under the `acceptance` profile) with a fake `mvn`
/// first on PATH. The fake records its arguments and pid, writes the scenario's log to stdout, copies the scenario's
/// TEST-*.xml into `<module>/target/` (so they are newer than the run's marker), runs the scenario's `during` script,
/// and exits with the scenario's code.
///
/// Each false green the tooling exists to stop is a test here: a flag that hides what the run did (refused, mvn never
/// runs); a module or test selection (PARTIAL, never GREEN); two builds in one tree (lock, also after a kill -9 of the
/// wrapper, a reused pid, and concurrent takeovers); a previous run's reports (older than the marker, ignored); a module
/// or declared execution whose tests never ran or were all skipped; a failure or error; an expected module outside the
/// reactor; `-DskipTests` (COMPILE-ONLY, never GREEN); a jbct check that examined nothing; the tree changing during the
/// run.
class BuildToolingIT {
    private static final Path OPS = Path.of("..", "ops", "dev").toAbsolutePath().normalize();
    private static final Path REPORT = OPS.resolve("build-report");
    /// The native java running this test: a JAVA_HOME whose bin/java links to it passes build-args' java check.
    private static final Path REAL_JAVA = realJava();
    private static final Map<String, String> NAMES = Map.of("fx-parent", "fx", "alpha", "fx alpha", "beta", "fx beta",
                                                            "gamma", "fx gamma", "extra", "fx extra");
    private static final Map<String, String> EXECUTIONS = Map.of("alpha", """
        <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><executions>
          <execution><id>default-test</id></execution>
          <execution><id>one-carrier</id><goals><goal>test</goal></goals><configuration>
            <reportsDirectory>${project.build.directory}/surefire-reports-one-carrier</reportsDirectory>
          </configuration></execution>
        </executions></plugin></plugins></build>""", "gamma", """
        <build><plugins><plugin><artifactId>maven-failsafe-plugin</artifactId><executions>
          <execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution>
        </executions></plugin></plugins></build>""", "beta", """
        <properties><beta.excludedGroups>none</beta.excludedGroups></properties>
        <build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration>
          <excludedGroups>${beta.excludedGroups}</excludedGroups><basedir>${project.basedir}</basedir>
        </configuration></plugin></plugins></build>
        <profiles><profile><id>beta-tier</id><activation><property><name>beta.tier</name></property></activation>
          <properties><beta.excludedGroups>slow</beta.excludedGroups></properties>
        </profile></profiles>""");
    private static final String GAMMA_FAILSAFE = "[INFO] --- failsafe:3.5.4:integration-test (default) @ gamma ---\n";
    /// The fake `mvn` waits (at most a minute) until the test creates `$FAKE/go`.
    private static final String WAIT_FOR_GO = """
        i=0
        while [ ! -f "$FAKE/go" ] && [ "$i" -lt 600 ]; do sleep 0.1; i=$((i + 1)); done
        """;

    @TempDir
    Path dir;
    private Path tree;
    private Path fake;
    private Path ops;

    record Outcome(int rc, String output) {}

    /// A module this scenario builds: its jbct checked count (`-1`: no Check results line) and whether surefire said
    /// `Tests are skipped.`
    record Built(String artifact, int jbct, boolean skipped) {
        static Built ok(String artifact) {
            return new Built(artifact, artifact.equals("gamma") || artifact.equals("fx-parent") ? -1 : 3, false);
        }
    }

    @BeforeEach
    void fixtureTree() throws Exception {
        tree = Files.createDirectories(dir.resolve("tree"));
        Files.writeString(tree.resolve("pom.xml"), """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <artifactId>fx-parent</artifactId>
                <name>fx</name>
                <modules>
                    <module>alpha</module>
                    <module>beta</module>
                    <module>gamma</module>
                </modules>
                <profiles>
                    <profile>
                        <id>acceptance</id>
                        <modules>
                            <module>extra</module>
                        </modules>
                    </profile>
                </profiles>
            </project>
            """);
        for (var artifact : List.of("alpha", "beta", "gamma", "extra")) {
            var module = Files.createDirectories(tree.resolve(artifact));
            Files.writeString(module.resolve("pom.xml"),
                              "<project><artifactId>" + artifact + "</artifactId><name>" + NAMES.get(artifact)
                              + "</name>" + EXECUTIONS.getOrDefault(artifact, "") + "</project>\n");
            if (!artifact.equals("gamma")) {
                Files.writeString(Files.createDirectories(module.resolve("src/main/java/fx")).resolve("A.java"), "class A {}\n");
            }
            Files.writeString(Files.createDirectories(module.resolve("src/test/java/fx")).resolve("ATest.java"), "class ATest {}\n");
        }
        Files.writeString(tree.resolve(".gitignore"), "target/\n");
        // build-args refuses a run in which no channel points maven at the tree's own repo
        Files.writeString(Files.createDirectories(tree.resolve(".mvn")).resolve("maven.config"), "-Dmaven.repo.local=.m2-local\n");
        fake = Files.createDirectories(dir.resolve("fake"));
        var bin = Files.createDirectories(fake.resolve("bin"));
        Files.writeString(bin.resolve("mvn"), """
            #!/bin/sh
            # a fixture launcher: build-args knows maven's own by the "-Dmaven.home=" it passes
            case "$1" in -v|--version) echo "Apache Maven 9.9.9 (fake)"; exit 0 ;; esac
            printf '%s\\n' "$@" > "$FAKE/args"
            env > "$FAKE/env"
            echo "$$" >> "$FAKE/runs"
            cat "$FAKE/log"
            if [ -d "$FAKE/reports" ]; then
              for module in "$FAKE"/reports/*; do
                mkdir -p "$(basename "$module")/target"
                cp -R "$module"/. "$(basename "$module")/target/"
              done
            fi
            if [ -f "$FAKE/during" ]; then . "$FAKE/during"; fi
            exit "$(cat "$FAKE/rc" 2> /dev/null || echo 0)"
            """);
        bin.resolve("mvn").toFile().setExecutable(true);
        ops = opsCopy(fake);
        var init = ran(HermeticProcess.builder("sh", "-c", "git init -q && git add -A && git -c user.name=t -c user.email=t@t commit -qm fixture"),
                       Map.of());
        assertThat(init.rc()).as(init.output()).isZero();
    }

    // ---- refusals -----------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"-q", "--quiet", "-qB", "-Djbct.skip=true", "-Djbct.skipFormat=true",
                            "-Dmaven.test.failure.ignore=true", "-Dsurefire.skipAfterFailureCount=1", "-DskipITs",
                            "-DskipITs=true", "-fn", "--fail-never"})
    void build_refusesAFlagThatHidesWhatTheRunDid_andNeverRunsMaven(String flag) throws Exception {
        var outcome = build(List.of("install", flag), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: " + flag);
        assertThat(fake.resolve("args")).doesNotExist();
        assertThat(tree.resolve(".wp-logs/build.lock")).doesNotExist();
    }

    @Test
    void build_refusesASeparateDashD_skipOfJbct() throws Exception {
        var outcome = build(List.of("install", "-D", "jbct.skip=true"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: -Djbct.skip=true");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @ParameterizedTest(name = "{0}={1} is refused")
    @CsvSource(delimiter = '|', value = {"MAVEN_OPTS | -Xmx1g -Djbct.skip=true", "MAVEN_ARGS | -q"})
    void build_refusesAFlagHiddenInTheEnvironment(String variable, String value) throws Exception {
        var outcome = build(List.of("install"), Map.of(variable, value));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("(" + variable + ")");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @Test
    void build_refusesAFlagInMavenConfig() throws Exception {
        Files.writeString(Files.createDirectories(tree.resolve(".mvn")).resolve("maven.config"),
                          "-Dmaven.repo.local=.m2-local\n-Dmaven.test.failure.ignore=true\n");
        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("(.mvn/maven.config)");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    /// A rerun reports a test that failed and then passed as passed: the same false green as -fn.
    @ParameterizedTest(name = "install {0} is refused as {1}")
    @CsvSource(delimiter = '|', value = {"-Dsurefire.rerunFailingTestsCount=2 | -Dsurefire.rerunFailingTestsCount=2",
                                         "-Dfailsafe.rerunFailingTestsCount=1 | -Dfailsafe.rerunFailingTestsCount=1",
                                         "-Dsurefire.rerunFailingTestsCount | -Dsurefire.rerunFailingTestsCount",
                                         "-D surefire.rerunFailingTestsCount=2 | -Dsurefire.rerunFailingTestsCount=2",
                                         "--define failsafe.rerunFailingTestsCount=2 | -Dfailsafe.rerunFailingTestsCount=2",
                                         "--define=surefire.rerunFailingTestsCount=2 | -Dsurefire.rerunFailingTestsCount=2",
                                         "--def=failsafe.rerunFailingTestsCount=3 | -Dfailsafe.rerunFailingTestsCount=3",
                                         "-BDsurefire.rerunFailingTestsCount=2 | -Dsurefire.rerunFailingTestsCount=2"})
    void build_refusesARerunOfFailingTests_inEverySpelling(String arguments, String shown) throws Exception {
        var all = new ArrayList<>(List.of("install"));
        all.addAll(Arrays.asList(arguments.split(" ")));

        var outcome = build(all, Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: " + shown + " (command line)").contains("rerun");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @ParameterizedTest(name = "{0}={1} is refused")
    @CsvSource(delimiter = '|', value = {"MAVEN_OPTS | -Xmx1g -Dfailsafe.rerunFailingTestsCount=2",
                                         "MAVEN_ARGS | --define surefire.rerunFailingTestsCount=2",
                                         "JAVA_TOOL_OPTIONS | -Dsurefire.rerunFailingTestsCount=1",
                                         ".mvn/maven.config | -Dfailsafe.rerunFailingTestsCount=2",
                                         ".mvn/jvm.config | -Dsurefire.rerunFailingTestsCount=2"})
    void build_refusesARerunOfFailingTests_fromTheEnvironmentOrMvnConfig(String source, String value) throws Exception {
        var environment = Map.<String, String>of();
        if (source.startsWith(".mvn/")) {
            Files.writeString(Files.createDirectories(tree.resolve(".mvn")).resolve(source.substring(5)), value + "\n");
        } else {
            environment = Map.of(source, value);
        }

        var outcome = build(List.of("install"), environment);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: -D").contains("rerunFailingTestsCount").contains("(" + source + ")");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    // ---- what a pom can make of a -D or -P (ruling 1), JVM option channels (3), hidden tree state (4) ----------

    /// A -D or -P that build-args does not know to be harmless can change what a pom tests: PARTIAL, naming it.
    @ParameterizedTest(name = "install {0} is PARTIAL")
    @ValueSource(strings = {"-Dfoo=bar", "-Dfoo", "--define=x.y=1", "-Pother", "-P!acceptance", "-Pacceptance,other",
                            "--activate-profiles=other", "-BPother"})
    void build_isPartial_onAPropertyOrProfileOutsideTheAllowlist(String arguments) throws Exception {
        green();
        var all = new ArrayList<>(List.of("install"));
        all.addAll(Arrays.asList(arguments.split(" ")));

        var outcome = build(all, Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("outside the allowlist of ops/dev/build-args: ")
                                    .doesNotContain("GREEN");
    }

    @ParameterizedTest(name = "{0}={1} is PARTIAL")
    @CsvSource(delimiter = '|', value = {"MAVEN_OPTS | -Xmx1g -Dfoo=1", "JAVA_TOOL_OPTIONS | -Dfoo=1", "MAVEN_ARGS | -Pother"})
    void build_isPartial_onAPropertyOrProfileOutsideTheAllowlist_fromTheEnvironment(String variable, String value) throws Exception {
        green();

        var outcome = build(List.of("install"), Map.of(variable, value));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("outside the allowlist").contains("(" + variable + ")");
    }

    /// The allowlist: these change nothing about what the run tests.
    @Test
    void build_isGreen_withAllowedPropertiesAndOptions() throws Exception {
        Files.writeString(Files.createDirectories(tree.resolve(".mvn")).resolve("maven.config"), "-Dmaven.repo.local=.m2-local\n");
        green();

        var outcome = build(List.of("install", "-e", "-U", "-Dmaven.repo.local=.m2-local", "-Pacceptance"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("GREEN");
    }

    /// beta's surefire configuration reads ${beta.excludedGroups}; profile beta-tier sets it and -Dbeta.tier activates it.
    @ParameterizedTest(name = "install {0} is a test selection: {1}")
    @CsvSource(delimiter = '|', value = {"-Dbeta.excludedGroups=slow | -Dbeta.excludedGroups=slow (command line) is read by surefire/failsafe configuration",
                                         "-Dbeta.tier | -Dbeta.tier (command line) activates profile beta-tier",
                                         "-Pbeta-tier | -Pbeta-tier (command line) sets beta.excludedGroups"})
    void build_isPartial_whenAPropertyOrProfileReachesTestConfiguration(String argument, String reason) throws Exception {
        green();
        deleteReports("beta");

        var outcome = build(List.of("install", argument), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("test selection: " + reason).contains("beta: 0 fresh testcases")
                                    .doesNotContain("RED");
    }

    @ParameterizedTest(name = "{0} set is refused")
    @ValueSource(strings = {"JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_DEBUG_OPTS", "MAVEN_BASEDIR"})
    void build_refusesAJavaLauncherVariable(String variable) throws Exception {
        green();

        var outcome = build(List.of("install"), Map.of(variable, "-Dtest=PassTest"));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: " + variable + "=-Dtest=PassTest (environment)");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    /// Set but empty, they carry nothing and are not refused; maven runs without them all the same.
    @Test
    void build_runsMavenWithoutTheJavaLauncherVariables() throws Exception {
        green();

        var outcome = build(List.of("install"), Map.of("JDK_JAVA_OPTIONS", "", "_JAVA_OPTIONS", "", "MAVEN_DEBUG_OPTS", "",
                                                       "MAVEN_BASEDIR", "", "MAVEN_SKIP_RC", ""));

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(Files.readAllLines(fake.resolve("env"))).noneMatch(line -> line.startsWith("JDK_JAVA_OPTIONS="))
                                                           .noneMatch(line -> line.startsWith("_JAVA_OPTIONS="))
                                                           .noneMatch(line -> line.startsWith("MAVEN_DEBUG_OPTS="))
                                                           .noneMatch(line -> line.startsWith("MAVEN_BASEDIR="))
                                                           .contains("MAVEN_SKIP_RC=1");
    }

    @ParameterizedTest(name = "{0}: {1} is refused")
    @CsvSource(delimiter = '|', value = {"MAVEN_OPTS | @.wp-logs/opts", "MAVEN_OPTS | -XX:VMOptionsFile=/tmp/x",
                                         "MAVEN_OPTS | -XX:Flags=/tmp/x", "JAVA_TOOL_OPTIONS | @x",
                                         "JAVA_TOOL_OPTIONS | -XX:VMOptionsFile=x", "MAVEN_ARGS | @args",
                                         ".mvn/jvm.config | -XX:Flags=x", ".mvn/jvm.config | @x",
                                         ".mvn/maven.config | @x", "command line | @args", "command line | -XX:VMOptionsFile=x"})
    void build_refusesOptionsReadFromAFile(String source, String word) throws Exception {
        green();
        var environment = Map.<String, String>of();
        var arguments = new ArrayList<>(List.of("install"));
        if (source.startsWith(".mvn/")) {
            Files.writeString(Files.createDirectories(tree.resolve(".mvn")).resolve(source.substring(5)), word + "\n");
        } else if (source.equals("command line")) {
            arguments.add(word);
        } else {
            environment = Map.of(source, word);
        }

        var outcome = build(arguments, environment);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: " + word + " (" + source + ") reads more options from a file");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @ParameterizedTest(name = "git update-index {0} is refused")
    @ValueSource(strings = {"--assume-unchanged", "--skip-worktree"})
    void build_refusesATrackedFileGitIsToldNotToSee(String flag) throws Exception {
        green();
        git("update-index", flag, "beta/src/test/java/fx/ATest.java");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: git is told to ignore changes to tracked files").contains("  beta/src/test/java/fx/ATest.java");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    /// .mvn/maven.config is skip-worktree by convention: allowed, and its content is in the tree state.
    @Test
    void build_allowsASkipWorktreeMavenConfig_andSeesItChangeDuringTheBuild() throws Exception {
        git("update-index", "--skip-worktree", ".mvn/maven.config");
        green();

        var quiet = build(List.of("install"), Map.of());

        assertThat(quiet.rc()).as(quiet.output()).isZero();

        Files.writeString(fake.resolve("during"), "echo '-Dmaven.repo.local=/elsewhere' > .mvn/maven.config\n");
        var changed = build(List.of("install"), Map.of());

        assertThat(git("status", "--porcelain")).as("git status cannot see it").doesNotContain("maven.config");
        assertThat(changed.rc()).as(changed.output()).isEqualTo(1);
        assertThat(changed.output()).contains("RED").contains("tree changed during build");
    }

    /// A gitignored file under a module's src/ is compiled, yet git status does not show it: dirty, and in the tree state.
    @Test
    void build_countsAnIgnoredSourceAsDirty_andSeesItChangeDuringTheBuild() throws Exception {
        Files.writeString(tree.resolve(".gitignore"), "target/\n*.gen.java\n");
        git("add", ".gitignore");
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "ignore generated");
        Files.writeString(tree.resolve("alpha/src/main/java/fx/B.gen.java"), "class B {}\n");
        green();

        var outcome = build(List.of("--name", "ignored", "--", "install"), Map.of());

        assertThat(outcome.output()).contains("(dirty)");
        assertThat(tree.resolve(".wp-logs/ignored.marker")).content().contains("dirty=yes");

        Files.writeString(fake.resolve("during"), "echo 'class B { int x; }' > alpha/src/main/java/fx/B.gen.java\n");
        var changed = build(List.of("install"), Map.of());

        assertThat(changed.rc()).as(changed.output()).isEqualTo(1);
        assertThat(changed.output()).contains("RED").contains("tree changed during build");
    }

    // ---- provenance (ruling 6) ----------------------------------------------------------------------------------

    /// The verdict is read while the wrapper still holds the lock, and the JSON says who ran it on which log.
    @Test
    void build_reportRunsUnderTheWrappersLock_andTheJsonCarriesProvenance() throws Exception {
        green();
        var outcome = build(List.of("--name", "prov", "--", "install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        var json = Files.readString(tree.resolve(".wp-logs/prov.json"));
        var marker = Files.readString(tree.resolve(".wp-logs/prov.marker"));
        var wrapper = marker.replaceAll("(?s).*\nwrapper=(\\d+)\n.*", "$1");
        var sha = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tree.resolve(".wp-logs/prov.log")));
        assertThat(json).contains("\"wrapper_pid\": \"" + wrapper + "\"").contains("\"report_parent_pid\": \"" + wrapper + "\"")
                        .contains("\"log_sha256\": \"" + java.util.HexFormat.of().formatHex(sha) + "\"")
                        .contains("\"pid\": \"" + wrapper + "\"");
        assertThat(tree.resolve(".wp-logs/build.lock")).as("released after the report").doesNotExist();
    }

    /// build-report run by hand on a real run's marker and log: the lock is gone, so is the wrapper. RED.
    @Test
    void buildReport_byHand_isRed_onProvenance() throws Exception {
        green();
        var first = build(List.of("--name", "real", "--", "install"), Map.of());
        assertThat(first.rc()).as(first.output()).isZero();

        var outcome = report(tree.resolve(".wp-logs/real.marker"), tree.resolve(".wp-logs/real.log"), "--mvn-exit", "0");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("provenance: the build lock");
    }

    @ParameterizedTest(name = "a marker without {0} is RED")
    @ValueSource(strings = {"head", "dirty", "tree", "start", "args", "wrapper", "wrapper_lstart", "select_modules",
                            "mvn", "maven_version", "java_home", "select_tests", "select_other"})
    void buildReport_isRed_onAMarkerMissingAField(String field) throws Exception {
        green();
        Files.writeString(fake.resolve("during"), "sed '/^" + field + "=/d' .wp-logs/m.marker > .wp-logs/m.cut && touch -r .wp-logs/m.marker .wp-logs/m.cut"
                                                + " && mv .wp-logs/m.cut .wp-logs/m.marker\n");

        var outcome = build(List.of("--name", "m", "--", "install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED");
        assertThat(Files.readString(tree.resolve(".wp-logs/m.json"))).contains("marker has no " + field + " (not written by ops/dev/build?)");
    }

    // ---- round 2: classes that ran nothing (1), the launcher chain and the test JVM's own properties (2), clean (3),
    // ---- the repo (4), known-skips by module and method (5) ----------------------------------------------------

    /// A failing assumption in @BeforeAll leaves a report without a single testcase: the class did not run.
    @Test
    void build_isPartial_whenATestClassReportHasNoTestcase() throws Exception {
        green();
        reportFile("beta", "TEST-fx.GoneIT.xml", suite(0).replace("fx.ATest", "fx.GoneIT"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("1 test classes ran no testcase").contains("beta fx.GoneIT");
    }

    @Test
    void build_isPartial_whenTheLogSaysATestClassRanNothing() throws Exception {
        green();
        logAfterAlphasTests("[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in fx.ClassIT\n");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("1 test classes ran no testcase").contains("alpha fx.ClassIT");
    }

    /// An outer @Nested class reports `Tests run: 0` (temper-domain's RefusalsTest): its testcases are its inner classes'.
    @Test
    void build_isGreen_whenTheClassThatRanNothingIsAnOuterNestedClassWhoseInnerClassesRan() throws Exception {
        green();
        logAfterAlphasTests("[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in fx.Outer\n");
        reportFile("alpha", "TEST-fx.Outer$Inner.xml", suite(1).replace("fx.ATest", "fx.Outer$Inner"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("GREEN");
    }

    /// known-skips is keyed by module, class and (optionally) method: an entry covers no other module, a method entry no
    /// other method and not the class running nothing.
    @ParameterizedTest(name = "{0} with entry ''{1}'': GREEN {2}")
    @CsvSource(delimiter = '|', value = {"skipped testcase | alpha fx.ATest any r | true",
                                         "skipped testcase | alpha fx.ATest#x any r | true",
                                         "skipped testcase | alpha fx.ATest#y any r | false",
                                         "skipped testcase | beta fx.ATest any r | false",
                                         "class ran nothing | beta fx.GoneIT any r | true",
                                         "class ran nothing | beta fx.GoneIT#x any r | false",
                                         "class ran nothing | alpha fx.GoneIT any r | false"})
    void build_knownSkips_matchModuleClassAndMethod(String scenario, String entry, boolean green) throws Exception {
        var ops = opsWithKnownSkips(entry + "\n");
        green();
        if (scenario.equals("skipped testcase")) {
            reports("alpha", suite(2, "<skipped/>"));
        } else {
            reportFile("beta", "TEST-fx.GoneIT.xml", suite(0).replace("fx.ATest", "fx.GoneIT"));
        }

        var outcome = ran(buildCommand(ops, List.of("install")), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(green ? 0 : 1);
        assertThat(outcome.output()).contains(green ? "GREEN" : "PARTIAL");
    }

    /// What the test JVM itself recorded is judged, whatever channel set it (a launcher script, an mvn first on PATH).
    @ParameterizedTest(name = "-D{0}={1} in a test JVM is {2}")
    @CsvSource(delimiter = '|', value = {"test | PassTest | PARTIAL | beta: its test JVM had -Dtest=PassTest",
                                         "it.test | X | PARTIAL | beta: its test JVM had -Dit.test=X",
                                         "beta.excludedGroups | slow | PARTIAL | -Dbeta.excludedGroups=slow (is read by surefire/failsafe configuration)",
                                         "beta.tier | '' | PARTIAL | -Dbeta.tier= (activates profile beta-tier)",
                                         "beta.excludedGroups | none | GREEN | GREEN",
                                         "maven.test.failure.ignore | true | RED | beta: its test JVM had -Dmaven.test.failure.ignore=true (a test failure exits 0)",
                                         "surefire.rerunFailingTestsCount | 2 | RED | -Dsurefire.rerunFailingTestsCount=2 (a test that fails",
                                         "jbct.skip | true | RED | beta: its test JVM had -Djbct.skip=true",
                                         "maven.repo.local | /tmp/elsewhere | RED | -Dmaven.repo.local=/tmp/elsewhere (resolves plugins and dependencies outside",
                                         "maven.repo.local | .m2-local | GREEN | GREEN",
                                         "surefire.test.class.path | /x | GREEN | GREEN"})
    void buildReport_judgesTheSystemPropertiesATestJvmRecorded(String name, String value, String verdict, String fragment)
        throws Exception {
        green();
        reports("beta", withProperties(suite(1), name, value));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(verdict.equals("GREEN") ? 0 : 1);
        assertThat(outcome.output()).contains(verdict);
        assertThat(Files.readString(newestJson())).contains(fragment);
    }

    @Test
    void build_recordsTheMavenItRuns_inTheMarkerAndTheJson() throws Exception {
        green();

        var jdk = linkedJdk();

        var outcome = build(List.of("--name", "launcher", "--", "install"), Map.of("JAVA_HOME", jdk.toString()));

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        var mvn = fake.resolve("bin/mvn").toRealPath().toString();
        assertThat(tree.resolve(".wp-logs/launcher.marker")).content().contains("mvn=" + mvn + "\n")
                                                            .contains("maven_version=Apache Maven 9.9.9 (fake)\n")
                                                            .contains("java_home=" + jdk + "\n")
                                                            .contains("java=" + REAL_JAVA + "\n")
                                                            .contains("settings=" + fake.toRealPath().resolve("conf/settings.xml") + " absent; ");
        assertThat(tree.resolve(".wp-logs/launcher.json")).content().contains("\"mvn\": \"" + mvn + "\"")
                                                          .contains("\"maven_version\": \"Apache Maven 9.9.9 (fake)\"")
                                                          .contains("\"java_home\": \"" + jdk + "\"");
    }

    /// Compiled classes from another state of the sources are not in the tree state: clean always runs first.
    @ParameterizedTest(name = "{0} runs as -B {1}")
    @CsvSource(delimiter = '|', value = {"install | clean install", "clean install | clean install",
                                         "install clean | clean install clean", "-pl alpha install | clean -pl alpha install"})
    void build_runsCleanFirst(String arguments, String ran) throws Exception {
        green();

        build(Arrays.asList(arguments.split(" ")), Map.of());

        var expected = new ArrayList<>(List.of("-B"));
        expected.addAll(Arrays.asList(ran.split(" ")));
        assertThat(Files.readAllLines(fake.resolve("args"))).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"-Dmaven.repo.local=/tmp/any-poisoned-repo", "--define=maven.repo.local=../other/.m2-local",
                            "-Dmaven.repo.local", "-Dmaven.repo.local=.m2-local/nested"})
    void build_refusesARepoOtherThanTheTreesOwn(String flag) throws Exception {
        green();

        var outcome = build(List.of("install", flag), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("resolves plugins and dependencies outside " + tree.toRealPath() + "/.m2-local");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @Test
    void build_refusesARepoThatIsASymlinkOutOfTheTree_andOneFromMavenOpts() throws Exception {
        Files.createSymbolicLink(tree.resolve(".m2-local"), Files.createDirectories(dir.resolve("shared-m2")));
        green();

        var linked = build(List.of("install", "-Dmaven.repo.local=.m2-local"), Map.of());
        Files.writeString(tree.resolve(".mvn/maven.config"), "\n");  // the fixture's own setting would be refused first
        var opts = build(List.of("install"), Map.of("MAVEN_OPTS", "-Dmaven.repo.local=" + dir.resolve("shared-m2")));

        assertThat(linked.rc()).as(linked.output()).isEqualTo(2);
        assertThat(opts.rc()).as(opts.output()).isEqualTo(2);
        assertThat(opts.output()).contains("(MAVEN_OPTS) resolves plugins and dependencies outside");
    }

    @ParameterizedTest(name = "{0} is allowed")
    @ValueSource(strings = {".m2-local", "./.m2-local", "alpha/../.m2-local"})
    void build_allowsTheTreesOwnRepo(String value) throws Exception {
        green();

        var outcome = build(List.of("install", "-Dmaven.repo.local=" + value), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("GREEN");
    }

    // ---- the lock -----------------------------------------------------------------------------------------------

    @Test
    void build_refusesWhileALiveBuildHoldsTheLock_namingIt() throws Exception {
        var live = ProcessHandle.current().pid();
        lockHeldBy(live);
        green();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("held by pid " + live).contains("args=verify");
        assertThat(fake.resolve("args")).doesNotExist();
        assertThat(tree.resolve(".wp-logs/build.lock/pid")).content().isEqualTo(live + "\n");
    }

    /// pid 1 is alive and not this user's: `kill -0` answers EPERM, which a liveness check must not read as dead.
    @Test
    void build_refusesWhileAnotherUsersLiveProcessHoldsTheLock() throws Exception {
        assertThat(ProcessHandle.of(1).map(ProcessHandle::isAlive)).contains(true);
        var root = ran(HermeticProcess.builder("sh", "-c", "[ \"$(id -u)\" = 0 ]"), Map.of()).rc() == 0;
        assumeFalse(root, "as root, kill -0 1 succeeds: nothing to tell apart");
        lockHeldBy(1);
        green();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("held by pid 1 ");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    /// The shared contract: a lock directory without a usable pid is a lock still being written, held for 10 s after it
    /// was made; older, it is stale.
    @ParameterizedTest(name = "pid file ''{0}'' in an old lock is not held")
    @ValueSource(strings = {"<none>", "", "abc"})
    void build_takesOverAnOldLockWithoutAUsablePid(String content) throws Exception {
        var lock = pidlessLock(content);
        Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
        green();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("taking over").contains("pid none is not running");
        assertThat(tree.resolve(".wp-logs/build.lock")).doesNotExist();
    }

    @ParameterizedTest(name = "pid file ''{0}'' in a fresh lock is held")
    @ValueSource(strings = {"<none>", "", "abc"})
    void build_refusesAFreshLockWithoutAUsablePid(String content) throws Exception {
        pidlessLock(content);
        green();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("held by no pid yet");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    private Path pidlessLock(String content) throws IOException {
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        if (!content.equals("<none>")) {
            Files.writeString(lock.resolve("pid"), content + "\n");
        }
        return lock;
    }

    @Test
    void build_takesOverTheLockOfADeadBuild_andSaysSo() throws Exception {
        var gone = HermeticProcess.builder("true").start();
        gone.waitFor();
        lockHeldBy(gone.pid());
        green();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("taking over").contains("pid " + gone.pid() + " is not running");
        assertThat(tree.resolve(".wp-logs/build.lock")).as("released after the run").doesNotExist();
    }

    // ---- verdicts -----------------------------------------------------------------------------------------------

    /// The count is of <testcase> elements: the suite's `tests=` attribute claims 99.
    @Test
    void build_isGreen_whenEveryBuiltModuleRanTestsAndJbctExaminedItsSources() throws Exception {
        green();

        var outcome = build(List.of("--name", "happy", "--expect", "alpha,gamma", "--", "install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).startsWith("mvn -B clean install").contains("GREEN").contains("not built: extra (profile acceptance)");
        assertThat(Files.readAllLines(fake.resolve("args"))).containsExactly("-B", "clean", "install");
        var json = Files.readString(tree.resolve(".wp-logs/happy.json"));
        assertThat(json).contains("\"verdict\": \"GREEN\"").contains("\"testcases\": 3").doesNotContain("\"testcases\": 99");
        assertThat(tree.resolve(".wp-logs/happy.log")).content().contains("BUILD SUCCESS");
        assertThat(tree.resolve(".wp-logs/happy.marker")).content().containsPattern("head=[0-9a-f]{40}");
        assertThat(tree.resolve(".wp-logs/build.lock")).doesNotExist();
    }

    @Test
    void build_isRed_whenMavenExitsNonZero() throws Exception {
        green();
        Files.writeString(fake.resolve("rc"), "1\n");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("mvn exited 1");
        assertThat(tree.resolve(".wp-logs/build.lock")).doesNotExist();
    }

    @ParameterizedTest(name = "a testcase with <{0}/> is RED")
    @ValueSource(strings = {"failure", "error"})
    void build_isRed_onAFailureOrError(String kind) throws Exception {
        green();
        reports("beta", suite(1, "<" + kind + " message=\"boom\"/>"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED")
                                    .contains("beta: " + (kind.equals("failure") ? "1 failure(s), 0 error(s)" : "0 failure(s), 1 error(s)"));
    }

    /// A rerun configured where build-args cannot see it (a pom's rerunFailingTestsCount) still leaves its trace in the
    /// report: a testcase that passed on a rerun (`flaky*`) or failed every rerun (`rerun*`) is RED, by name.
    @ParameterizedTest(name = "a testcase with <{0}/> in {1} is RED")
    @CsvSource({"flakyFailure, beta", "flakyError, beta", "rerunFailure, beta", "rerunError, beta", "flakyFailure, gamma"})
    void build_isRed_onATestcaseRerunAfterFailing(String kind, String artifact) throws Exception {
        green();
        var child = "<" + kind + " message=\"once\" type=\"java.lang.AssertionError\"><stackTrace>at x</stackTrace></" + kind + ">";
        if (artifact.equals("gamma")) {
            reports("gamma", "failsafe-reports", suite(1, child));
        } else {
            reports(artifact, suite(1, child));
        }

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").doesNotContain("GREEN")
                                    .contains(artifact + ": testcases rerun after failing (1 <" + kind + ">)");
    }

    /// temper-controller's second surefire execution (`one-carrier`) reports to `target/surefire-reports-one-carrier`
    /// (docs/developer-guide.md §5.1): a failure there is a failure of the run.
    @Test
    void build_isRed_onAFailureInASecondSurefireExecutionsReportDir() throws Exception {
        green();
        reports("alpha", "surefire-reports-one-carrier", suite(0, "<failure message=\"boom\"/>"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("alpha: 1 failure(s), 0 error(s)");
    }

    @Test
    void build_isRed_whenABuiltModuleWithTestsRanNone() throws Exception {
        green();
        deleteReports("gamma");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("gamma: 0 fresh testcases");
    }

    /// A previous run's reports stay in target/ until `clean`: one older than the marker, failing, is neither counted
    /// nor a failure; it does not stand in for this run's missing tests either.
    @Test
    void buildReport_ignoresReportsOlderThanTheMarker() throws Exception {
        green();
        deleteReports("alpha");
        var stale = Files.createDirectories(tree.resolve("alpha/target/surefire-reports")).resolve("TEST-fx.Old.xml");
        Files.writeString(stale, suite(4, "<failure message=\"old\"/>"));
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));

        var outcome = build(List.of("--name", "stale", "--", "install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("alpha: 0 fresh testcases").doesNotContain("failure(s)");
        assertThat(Files.readString(tree.resolve(".wp-logs/stale.json"))).contains("\"stale_report_files\": 1");
    }

    @Test
    void build_isRed_whenAnExpectedModuleWasNotBuilt() throws Exception {
        green();

        var outcome = build(List.of("--expect", "extra", "--", "install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("extra: expected but not built");
    }

    @Test
    void build_isCompileOnly_neverGreen_whenTestsAreSkipped() throws Exception {
        scenario(List.of(Built.ok("fx-parent"), new Built("alpha", 3, true), new Built("beta", 3, true),
                         new Built("gamma", -1, true)), true);

        var outcome = build(List.of("install", "-DskipTests"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("COMPILE-ONLY").contains("alpha: tests skipped").doesNotContain("GREEN");
    }

    @ParameterizedTest(name = "jbct checked {0} is RED")
    @ValueSource(ints = {0, -1})
    void build_isRed_whenJbctExaminedNothingInAModuleWithSources(int checked) throws Exception {
        scenario(List.of(Built.ok("fx-parent"), Built.ok("alpha"), new Built("beta", checked, false), Built.ok("gamma")), true);
        passingReports();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("beta: jbct examined 0 files");
    }

    @Test
    void buildReport_isRed_onFreshReportsInAModuleThisLogDidNotBuild() throws Exception {
        green();
        reports("extra", suite(1));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("extra: 1 fresh report(s) but this log did not build it");
    }

    @Test
    void buildReport_refusesALogOlderThanTheMarker() throws Exception {
        var logs = Files.createDirectories(tree.resolve(".wp-logs"));
        var log = Files.writeString(logs.resolve("old.log"), "[INFO] BUILD SUCCESS\n");
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        var marker = Files.writeString(logs.resolve("old.marker"), "head=x\n");

        var outcome = report(marker, log, "--mvn-exit", "0");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("older than marker");
    }

    @Test
    void buildReport_refusesAnExpectedModuleTheRootPomDoesNotName() throws Exception {
        var logs = Files.createDirectories(tree.resolve(".wp-logs"));
        var marker = Files.writeString(logs.resolve("x.marker"), "head=x\n");
        var log = Files.writeString(logs.resolve("x.log"), "[INFO] BUILD SUCCESS\n");

        var outcome = report(marker, log, "--mvn-exit", "0", "--expect", "temper-nothing");

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("--expect temper-nothing is not a module");
    }

    // ---- refusals, round 2 (verifier findings 1, 5, 6) ---------------------------------------------------------------

    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"-Dmaven.main.skip=true", "-Dmaven.main.skip", "-Dmaven.resources.skip=true",
                            "-Dmaven.compiler.failOnError=false", "-T", "-T4", "-T1C", "-BT4", "--threads=2", "--thr=2",
                            "-b", "-s", "--settings=s.xml", "-gs", "--global-settings=g.xml", "-l", "--log-file=x.log"})
    void build_refusesACompileSkipParallelismOrOtherSettings_andNeverRunsMaven(String flag) throws Exception {
        var outcome = build(List.of("install", flag, "value"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: " + flag);
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @ParameterizedTest(name = "install {0} is refused as {1}")
    @CsvSource(delimiter = '|', value = {"--define jbct.skip=true | -Djbct.skip=true",
                                         "--define=jbct.skip=true | -Djbct.skip=true",
                                         "--def=maven.main.skip | -Dmaven.main.skip",
                                         "-D maven.main.skip=true | -Dmaven.main.skip=true",
                                         "--threads 4 | --threads", "-s settings.xml | -s",
                                         "--global-settings g.xml | --global-settings"})
    void build_refusesASeparatedOrLongFormOfARefusedFlag(String arguments, String shown) throws Exception {
        var all = new ArrayList<>(List.of("install"));
        all.addAll(Arrays.asList(arguments.split(" ")));

        var outcome = build(all, Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: " + shown).contains("(command line)");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @ParameterizedTest(name = "{0}={1} is refused")
    @CsvSource(delimiter = '|', value = {"MAVEN_ARGS | --define=jbct.skip", "MAVEN_ARGS | -T 2",
                                         "MAVEN_ARGS | --settings s.xml", "MAVEN_OPTS | -Dmaven.main.skip=true",
                                         "JAVA_TOOL_OPTIONS | -Xss1m -Dmaven.main.skip=true",
                                         "JAVA_TOOL_OPTIONS | -Dmaven.test.failure.ignore=true"})
    void build_refusesARefusedFlagInMavenArgsOrAJvmOption(String variable, String value) throws Exception {
        var outcome = build(List.of("install"), Map.of(variable, value));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: ").contains("(" + variable + ")");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    @Test
    void build_refusesARefusedPropertyInJvmConfig() throws Exception {
        Files.writeString(Files.createDirectories(tree.resolve(".mvn")).resolve("jvm.config"), "-Xmx2g\n-Dmaven.main.skip=true\n");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: -Dmaven.main.skip=true (.mvn/jvm.config)");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    // ---- PARTIAL: a module or test selection is never GREEN (finding 2) ---------------------------------------------

    /// The fake builds every module and runs every test whatever the flags say: the verdict comes from the flags alone.
    @ParameterizedTest(name = "install {0} is PARTIAL")
    @ValueSource(strings = {"-pl alpha", "--projects=alpha", "-plalpha", "--proj alpha", "-rf :beta", "--resume-from beta",
                            "-am", "-amd", "--also-make", "-N", "-BN", "-f alpha/pom.xml", "--file=pom.xml",
                            "-Dtest=ATest", "-D test=ATest", "--define=it.test=X", "-Dgroups=fast", "-DexcludedGroups=slow",
                            "-Dsurefire.failIfNoSpecifiedTests=false", "-Dfailsafe.failIfNoSpecifiedTests=false",
                            "-Dsurefire.includesFile=x", "-Dacceptance.groups=x"})
    void build_isPartial_neverGreen_onAModuleOrTestSelection(String arguments) throws Exception {
        green();
        var all = new ArrayList<>(List.of("install"));
        all.addAll(Arrays.asList(arguments.split(" ")));

        var outcome = build(all, Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).startsWith("mvn -B").contains("PARTIAL").containsPattern("(module|test) selection: ")
                                    .doesNotContain("GREEN");
    }

    @ParameterizedTest(name = "{0}={1} is PARTIAL")
    @CsvSource(delimiter = '|', value = {"MAVEN_ARGS | -pl alpha", "MAVEN_OPTS | -Dtest=ATest",
                                         "JAVA_TOOL_OPTIONS | -Dit.test=X"})
    void build_isPartial_onASelectionFromTheEnvironment(String variable, String value) throws Exception {
        green();

        var outcome = build(List.of("install"), Map.of(variable, value));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("(" + variable + ")");
    }

    @Test
    void build_isPartial_onASelectionInMavenConfig() throws Exception {
        Files.writeString(Files.createDirectories(tree.resolve(".mvn")).resolve("maven.config"), "-Dmaven.repo.local=.m2-local\n-pl\nalpha\n");
        green();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("-pl (.mvn/maven.config)");
    }

    /// A profile that adds modules is not a selection: -Pacceptance with every module built is GREEN.
    @Test
    void build_isGreen_withAProfileThatAddsAModule() throws Exception {
        scenario(List.of(Built.ok("fx-parent"), Built.ok("alpha"), Built.ok("beta"), Built.ok("gamma"), Built.ok("extra")), true);
        passingReports();
        reports("extra", suite(1));

        var outcome = build(List.of("install", "-Pacceptance"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("GREEN").doesNotContain("not built");
    }

    /// The evidence agrees without the flags: a default-reactor module the log did not build makes the run PARTIAL.
    @Test
    void build_isPartial_whenADefaultReactorModuleWasNotBuilt() throws Exception {
        scenario(List.of(Built.ok("fx-parent"), Built.ok("alpha"), Built.ok("gamma")), true);
        passingReports();
        deleteReports("beta");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("beta: in the default reactor but not built");
    }

    /// The verifier's case: -Dtest=X runs one test class, every other module reports nothing. Missing tests are what a
    /// test selection causes: PARTIAL, not GREEN, and not RED either.
    @Test
    void build_isPartial_onATestSelectionThatLeftModulesWithoutTests() throws Exception {
        green();
        deleteReports("beta");
        deleteReports("gamma");

        var outcome = build(List.of("install", "-Dtest=ATest", "-Dsurefire.failIfNoSpecifiedTests=false"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("beta: 0 fresh testcases").contains("test selection: -Dtest=ATest");
    }

    /// A module selection does not excuse the selected module: it must still run its tests.
    @Test
    void build_isRed_whenASelectedModuleRanNoTests() throws Exception {
        green();
        deleteReports("beta");

        var outcome = build(List.of("install", "-pl", "beta"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("beta: 0 fresh testcases");
    }

    @Test
    void build_isRed_onAFailureUnderATestSelection() throws Exception {
        green();
        reports("beta", suite(0, "<failure message=\"boom\"/>"));

        var outcome = build(List.of("install", "-Dtest=ATest"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("beta: 1 failure(s)");
    }

    // ---- per execution and skips (findings 2, 3) --------------------------------------------------------------------

    @Test
    void build_isRed_whenADeclaredExecutionRanNoTests_thoughItsModuleDid() throws Exception {
        green();
        deleteReports("alpha", "surefire-reports-one-carrier");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED")
                                    .contains("alpha: surefire execution one-carrier: 0 fresh testcases in target/surefire-reports-one-carrier");
    }

    /// `mvn test` never reaches failsafe's phase: gamma's declared integration-test execution did not run.
    @Test
    void build_isPartial_whenADeclaredExecutionDidNotRun() throws Exception {
        green();
        Files.writeString(fake.resolve("log"), Files.readString(fake.resolve("log")).replace(GAMMA_FAILSAFE, ""));
        deleteReports("gamma");
        reports("gamma", suite(1));

        var outcome = build(List.of("test"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("gamma: failsafe execution default did not run");
    }

    @Test
    void build_isRed_whenEveryFreshTestcaseOfAModuleWasSkipped() throws Exception {
        green();
        reports("beta", suite(0, "<skipped/>", "<skipped/>"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("beta: all 2 fresh testcases skipped").contains("SKIPPED 2");
    }

    @Test
    void build_isRed_whenEveryFreshTestcaseOfADeclaredExecutionWasSkipped() throws Exception {
        green();
        reports("alpha", "surefire-reports-one-carrier", suite(0, "<skipped/>"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("alpha: surefire execution one-carrier: all 1 fresh testcases skipped");
    }

    /// A skipped testcase (an assumption, @Disabled, Docker down) is a test that did not run: PARTIAL, by count and
    /// class, unless ops/dev/known-skips lists its class for this platform.
    @Test
    void build_isPartial_withASkippedTestcase_namingItsCount() throws Exception {
        green();
        reports("alpha", suite(2, "<skipped/>"));
        reportFile("beta", "TEST-fx.PgIT.xml", suite(0, "<skipped/>", "<skipped message=\"docker\"/>").replace("fx.ATest", "fx.PgIT"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("tests 4, SKIPPED 1").contains("SKIPPED testcases: 3 (alpha 1, beta 2)")
                                    .contains("3 testcases skipped, not in ops/dev/known-skips: fx.ATest 1, fx.PgIT 2");
    }

    /// A copy of ops/dev whose known-skips lists fx.ATest for any platform and fx.PgIT for the other one only.
    @Test
    void build_isGreen_whenEverySkippedClassIsAKnownSkipForThisPlatform() throws Exception {
        var other = System.getProperty("os.name").startsWith("Mac") ? "Linux" : "Darwin";
        var ops = opsWithKnownSkips("alpha fx.ATest any needs a thing this fixture lacks\nbeta fx.PgIT " + other + " skips elsewhere\n");
        green();
        reports("alpha", suite(2, "<skipped/>"));

        var known = ran(buildCommand(ops, List.of("install")), Map.of());

        assertThat(known.rc()).as(known.output()).isZero();
        assertThat(known.output()).contains("GREEN").contains("SKIPPED testcases: 1 (alpha 1)");

        reportFile("beta", "TEST-fx.PgIT.xml", suite(0, "<skipped/>").replace("fx.ATest", "fx.PgIT"));
        var elsewhere = ran(buildCommand(ops, List.of("install")), Map.of());

        assertThat(elsewhere.rc()).as(elsewhere.output()).isEqualTo(1);
        assertThat(elsewhere.output()).contains("PARTIAL").contains("1 testcases skipped, not in ops/dev/known-skips: fx.PgIT 1");
    }

    @ParameterizedTest(name = "known-skips line ''{0}'' is invalid input")
    @ValueSource(strings = {"fx.ATest any because: no module", "alpha fx.ATest Windows because", "alpha fx.ATest any",
                            "alpha any because"})
    void buildReport_refusesAMalformedKnownSkip(String line) throws Exception {
        var ops = opsWithKnownSkips("# a comment\n\n" + line + "\n");
        green();

        var outcome = ran(buildCommand(ops, List.of("install")), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("known-skips:3: want `<module> <class>[#method] <any|Linux|Darwin> <reason>`");
    }

    /// The checked-in list parses, and names only classes with a platform and a reason.
    @Test
    void knownSkips_checkedIn_isWellFormed() throws Exception {
        for (var line : Files.readAllLines(OPS.resolve("known-skips"))) {
            if (!line.isBlank() && !line.startsWith("#")) {
                assertThat(line).matches("temper-[a-z]+ [\\w.$]+(#\\w+)? (any|Linux|Darwin) \\S.*");
            }
        }
    }

    @Test
    void build_isRed_whenAnExpectedModuleRanNoNonSkippedTestcase() throws Exception {
        scenario(List.of(Built.ok("fx-parent"), new Built("alpha", 3, true), new Built("beta", 3, true),
                         new Built("gamma", -1, true)), true);

        var outcome = build(List.of("--expect", "beta", "--", "install", "-DskipTests"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("beta: expected but ran no non-skipped testcase");
    }

    // ---- the tree (finding 7) ---------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} during the build is RED")
    @ValueSource(strings = {"echo '// edited' >> alpha/src/main/java/fx/A.java", "echo new > alpha/notes.txt",
                            "echo more >> alpha/draft.txt", "git add -A > /dev/null"})
    void build_isRed_whenTheTreeChangedDuringTheBuild(String change) throws Exception {
        green();
        Files.writeString(tree.resolve("alpha/draft.txt"), "untracked before the run\n");
        Files.writeString(fake.resolve("during"), change + "\n");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("tree changed during build");
    }

    // ---- the lock, round 2 (finding 4) ------------------------------------------------------------------------------

    /// kill -9 of the wrapper leaves maven running: the lock names maven too, so a second build is refused until maven
    /// ends; then the lock is stale and taken over.
    @Test
    void build_lockStaysHeldByMaven_afterTheWrapperIsKilled() throws Exception {
        green();
        Files.writeString(fake.resolve("during"), WAIT_FOR_GO);
        var wrapper = started(List.of("install"), dir.resolve("first.out"), Map.of());
        long maven = -1;
        try {
            var lock = lockOnceMavenRuns();
            maven = mavenPid(lock);
            assertThat(lock).containsExactly(String.valueOf(wrapper.pid()), String.valueOf(maven));
            var starts = Files.readAllLines(tree.resolve(".wp-logs/build.lock/lstart"));
            assertThat(starts).hasSize(2).allSatisfy(start -> assertThat(start).matches("\\S.* \\d\\d:\\d\\d:\\d\\d \\d{4}"));
            assertThat(starts.get(0)).isEqualTo(lstartOf(wrapper.pid()));
            assertThat(starts.get(1)).isEqualTo(lstartOf(maven));

            wrapper.destroyForcibly().waitFor();
            assertThat(ProcessHandle.of(maven).map(ProcessHandle::isAlive)).contains(true);
            var reader = ran(HermeticProcess.builder("bash", "-c", ". \"$0\" && lock_held \"$1\"", OPS.resolve("lib.sh").toString(),
                                                tree.toString()), Map.of());
            assertThat(reader.rc()).as("preflight/prune's reader: " + reader.output()).isZero();
            assertThat(reader.output()).contains("by live pid " + maven);
            var second = build(List.of("install"), Map.of());

            assertThat(second.rc()).as(second.output()).isEqualTo(3);
            assertThat(second.output()).contains("held by pid " + maven + " (maven)");

            Files.writeString(fake.resolve("go"), "");
            awaitExit(maven);
            var third = build(List.of("install"), Map.of());

            assertThat(third.rc()).as(third.output()).isZero();
            assertThat(third.output()).contains("taking over").contains("pid " + maven + " (maven) is not running");
        } finally {
            killRecorded(maven);
        }
    }

    /// A pid the lock names, alive but started at another time, is another process reusing the pid: not a holder.
    @Test
    void build_takesOverALockWhosePidWasReused() throws Exception {
        var live = ProcessHandle.current().pid();
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        Files.writeString(lock.resolve("pid"), live + "\n");
        Files.writeString(lock.resolve("lstart"), "Thu Jan 1 00:00:00 1970\n");
        green();

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("taking over").contains("pid " + live + " (wrapper, reused: started");
    }

    /// A lock as a test writes it: `pid` and `lstart` lines (null: no such file), `L` standing for a live pid (this JVM),
    /// `D` for a dead one, `X` in `lstart` for a start time no live process has; `old` lock directories are a minute
    /// old, past the 10 s grace of a lock without a usable pid.
    record LockCase(String name, List<String> pid, List<String> lstart, boolean old, boolean held, boolean psFails) {
        LockCase(String name, List<String> pid, List<String> lstart, boolean old, boolean held) {
            this(name, pid, lstart, old, held, false);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<LockCase> lockCases() {
        return Stream.of(new LockCase("wrapper live", List.of("L"), List.of("L"), true, true),
                         new LockCase("wrapper live, no lstart file", List.of("L"), null, true, true),
                         new LockCase("wrapper dead, maven live", List.of("D", "L"), List.of("D", "L"), true, true),
                         new LockCase("both dead", List.of("D", "D"), List.of("D", "D"), true, false),
                         new LockCase("pid file missing, within grace", null, null, false, true),
                         new LockCase("pid file missing, past grace", null, null, true, false),
                         new LockCase("non-integer pid, within grace", List.of("abc"), null, false, true),
                         new LockCase("non-integer pid, past grace", List.of("abc"), null, true, false),
                         new LockCase("live pid reused (lstart differs)", List.of("L"), List.of("X"), true, false),
                         new LockCase("live pid with a trailing blank: no pid", List.of("D", "L "), List.of("D", "L"), true, false),
                         new LockCase("live pid with a leading blank: no pid", List.of("D", " L"), List.of("D", "L"), true, false),
                         new LockCase("live pid with a CR: no pid", List.of("D\r", "L\r"), List.of("D", "L"), true, false),
                         new LockCase("padded pid only, within grace", List.of("L "), null, false, true),
                         new LockCase("lstart with blanks and CR", List.of("D", "L"), List.of("D", "  L \r"), true, true),
                         new LockCase("ps fails, wrapper live: held (fails closed)", List.of("L"), List.of("L"), true, true, true),
                         new LockCase("ps fails, reused-looking start: held", List.of("L"), List.of("X"), true, true, true),
                         new LockCase("ps fails, both dead", List.of("D", "D"), List.of("D", "D"), true, false, true),
                         new LockCase("ps fails, another user's live pid 1", List.of("1"), List.of("X"), true, true, true),
                         new LockCase("lstart None (written when ps failed), live", List.of("L"), List.of("None"), true, true),
                         new LockCase("lstart None, dead", List.of("D"), List.of("None"), true, false),
                         new LockCase("lstart empty line, live", List.of("L"), List.of(""), true, true),
                         new LockCase("pid 0 is no pid, past grace", List.of("0"), null, true, false));
    }

    /// The two readers of the shared lock contract, lib.sh's `lock_held` (preflight, wt-prune) and build-lock's `take`
    /// (exit 3 when held), give the same verdict on every case, and the expected one. A pid line is a bare pid
    /// (DevPreflightIT pins lib.sh's `/^[0-9]+$/`): padded, it names no pid; a recorded start time compares blanks
    /// squeezed.
    @ParameterizedTest(name = "{0}")
    @MethodSource("lockCases")
    void lockReaders_agreeOnEveryCaseOfTheSharedContract(LockCase lockCase) throws Exception {
        var live = String.valueOf(ProcessHandle.current().pid());
        var gone = HermeticProcess.builder("true").start();
        gone.waitFor();
        var dead = String.valueOf(gone.pid());
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        if (lockCase.pid() != null) {
            Files.writeString(lock.resolve("pid"), lines(lockCase.pid(), token -> token.equals("L") ? live : token.equals("D") ? dead : token));
        }
        if (lockCase.lstart() != null) {
            var liveStart = lstartOf(ProcessHandle.current().pid());
            Files.writeString(lock.resolve("lstart"), lines(lockCase.lstart(), token -> switch (token) {
                case "L" -> liveStart;
                case "None", "" -> token;
                default -> "Thu Jan  1 00:00:00 1970";
            }));
        }
        if (lockCase.old()) {
            Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minus(1, ChronoUnit.MINUTES)));
        }

        var path = Map.of("PATH", (lockCase.psFails() ? failingPs() + ":" : "") + System.getenv("PATH"));
        var bash = ran(HermeticProcess.builder("bash", "-c", ". \"$0\" && lock_held \"$1\"", OPS.resolve("lib.sh").toString(),
                                          tree.toString()), path);
        var python = ran(HermeticProcess.builder("python3", OPS.resolve("build-lock").toString(), "take", lock.toString(), live),
                         path);

        assertThat(bash.rc()).as("lib.sh lock_held: " + bash.output()).isIn(0, 1);
        assertThat(python.rc()).as("build-lock take: " + python.output()).isIn(0, 3);
        assertThat(bash.rc() == 0).as("lib.sh lock_held held: " + bash.output()).isEqualTo(lockCase.held());
        assertThat(python.rc() == 3).as("build-lock held: " + python.output()).isEqualTo(lockCase.held());
    }

    /// A `ps` that fails as it can in a sandbox or under fork pressure: build-lock must still not take over a live lock,
    /// and must never record a start it could not read.
    @Test
    void buildLock_underAFailingPs_recordsNoStart_andRefusesALiveHolder() throws Exception {
        var path = Map.of("PATH", failingPs() + ":" + System.getenv("PATH"));
        var lock = tree.resolve(".wp-logs/build.lock");
        Files.createDirectories(lock.getParent());
        var own = ran(HermeticProcess.builder("python3", OPS.resolve("build-lock").toString(), "take", lock.toString(),
                                         String.valueOf(ProcessHandle.current().pid())), path);

        assertThat(own.rc()).as(own.output()).isZero();
        assertThat(lock.resolve("lstart")).content().isEqualTo("\n");

        var other = ran(HermeticProcess.builder("python3", OPS.resolve("build-lock").toString(), "take", lock.toString(), "1"), Map.of());

        assertThat(other.rc()).as("a working ps later: the unknown start still holds. " + other.output()).isEqualTo(3);
    }

    private Path failingPs() throws IOException {
        var bin = Files.createDirectories(dir.resolve("failing-ps"));
        Files.writeString(bin.resolve("ps"), "#!/bin/sh\nexit 1\n");
        bin.resolve("ps").toFile().setExecutable(true);
        return bin;
    }

    /// Each line's token (the line without blanks and CR around it) replaced, the blanks and CR kept.
    private static String lines(List<String> lines, UnaryOperator<String> token) {
        var text = new StringBuilder();
        for (var line : lines) {
            var bare = line.strip();
            text.append(line.replace(bare, token.apply(bare))).append('\n');
        }
        return text.toString();
    }

    /// Builds race to take over one stale lock, round after round: exactly one runs maven per round, every other one is
    /// refused with exit 3, no lock directory ends up inside another, and none is left behind. Six racers pause inside
    /// the takeover (the window a guard must close); twenty without a pause are the verifier's race harness.
    @ParameterizedTest(name = "{0} racers, pause ''{1}'', {2} round(s)")
    @CsvSource({"6, 0.5, 1", "20, '', 3"})
    void build_concurrentTakeoversOfAStaleLock_letExactlyOneRun_andNeverNest(int racers, String pause, int rounds)
        throws Exception {
        green();
        Files.writeString(fake.resolve("during"), "sleep 1\n");
        for (var round = 0; round < rounds; round++) {
            var gone = HermeticProcess.builder("true").start();
            gone.waitFor();
            lockHeldBy(gone.pid());
            Files.deleteIfExists(fake.resolve("runs"));
            var builds = new ArrayList<Process>();
            for (var i = 0; i < racers; i++) {
                builds.add(started(List.of("--name", "race-" + round + "-" + i, "--", "install"),
                                   dir.resolve("race-" + round + "-" + i + ".out"),
                                   pause.isEmpty() ? Map.of() : Map.of("BUILD_LOCK_TEST_PAUSE", pause)));
            }
            var codes = new ArrayList<Integer>();
            var outputs = new StringBuilder();
            for (var i = 0; i < builds.size(); i++) {
                assertThat(builds.get(i).waitFor(120, TimeUnit.SECONDS)).isTrue();
                codes.add(builds.get(i).exitValue());
                outputs.append(Files.readString(dir.resolve("race-" + round + "-" + i + ".out")));
            }

            assertThat(codes).as(outputs.toString()).containsOnly(0, 3).containsOnlyOnce(0);
            assertThat(Files.readAllLines(fake.resolve("runs"))).as(outputs.toString()).hasSize(1);
            try (var left = Files.list(tree.resolve(".wp-logs"))) {
                assertThat(left.map(path -> path.getFileName().toString()).filter(name -> name.startsWith("build.")))
                    .containsExactly("build.guard");
            }
        }
    }

    /// Ctrl-C or kill of the wrapper stops maven too (a background job ignores INT), then releases the lock.
    @Test
    void build_stopsMavenAndReleasesTheLock_whenTheWrapperIsTerminated() throws Exception {
        green();
        Files.writeString(fake.resolve("during"), WAIT_FOR_GO);
        var wrapper = started(List.of("install"), dir.resolve("term.out"), Map.of());
        long maven = -1;
        try {
            maven = mavenPid(lockOnceMavenRuns());

            wrapper.destroy();

            assertThat(wrapper.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(wrapper.exitValue()).isEqualTo(143);
            awaitExit(maven);
            assertThat(ProcessHandle.of(maven).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(tree.resolve(".wp-logs/build.lock")).doesNotExist();
        } finally {
            killRecorded(maven);
        }
    }

    /// A build that loses its lock to another (here: the lock now names another live pid) must not remove that lock
    /// when it ends, and cannot be GREEN: the other build could have written into the tree meanwhile.
    @Test
    void build_leavesALockItNoLongerOwns_andIsRed() throws Exception {
        var live = ProcessHandle.current().pid();
        green();
        Files.writeString(fake.resolve("during"), "printf '%s\\n' \"$OTHER\" > .wp-logs/build.lock/pid\n");

        var outcome = build(List.of("install"), Map.of("OTHER", String.valueOf(live)));

        assertThat(outcome.rc()).as("another build held the lock while reports were written").isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("provenance: the build lock");
        assertThat(tree.resolve(".wp-logs/build.lock/pid")).content().isEqualTo(live + "\n");
    }

    /// A lock that appears between the stale lock's removal and the new lock's rename (a build without the guard) wins:
    /// the rename fails instead of nesting, and the loser exits 3 without running maven.
    @Test
    void build_losesTheRace_whenALockAppearsDuringItsTakeover() throws Exception {
        var gone = HermeticProcess.builder("true").start();
        gone.waitFor();
        lockHeldBy(gone.pid());
        green();
        var lock = tree.resolve(".wp-logs/build.lock");
        var loser = started(List.of("install"), dir.resolve("loser.out"), Map.of("BUILD_LOCK_TEST_PAUSE", "3"));
        for (var i = 0; i < 100 && Files.exists(lock); i++) {
            Thread.sleep(50);
        }
        assertThat(lock).as("the stale lock is removed before the pause").doesNotExist();
        var live = ProcessHandle.current().pid();
        lockHeldBy(live);

        assertThat(loser.waitFor(30, TimeUnit.SECONDS)).isTrue();

        var output = Files.readString(dir.resolve("loser.out"));
        assertThat(loser.exitValue()).as(output).isEqualTo(3);
        assertThat(output).contains("was taken by another build during the takeover");
        assertThat(lock.resolve("pid")).content().isEqualTo(live + "\n");
        try (var inside = Files.list(lock)) {
            assertThat(inside.map(path -> path.getFileName().toString())).containsExactlyInAnyOrder("pid", "info");
        }
        assertThat(fake.resolve("args")).doesNotExist();
    }

    // ---- guards the first verifier round left untested --------------------------------------------------------------

    @Test
    void build_isRed_whenHeadMovedDuringTheBuild() throws Exception {
        green();
        Files.writeString(fake.resolve("during"), "git -c user.name=t -c user.email=t@t commit -q --allow-empty -m moved\n");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("HEAD moved during the build");
    }

    @Test
    void build_isRed_whenTheLogHasNoBuildSuccess_thoughMavenExitedZero() throws Exception {
        green();
        Files.writeString(fake.resolve("log"), Files.readString(fake.resolve("log")).replace("[INFO] BUILD SUCCESS\n", ""));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("the log has no BUILD SUCCESS");
    }

    @Test
    void build_isRed_whenAModuleFailedInTheReactorSummary_thoughTheBuildSucceeded() throws Exception {
        green();
        Files.writeString(fake.resolve("log"), Files.readString(fake.resolve("log"))
                                                    .replace("[INFO] fx beta ......... SUCCESS", "[INFO] fx beta ......... FAILURE"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("RED").contains("beta: FAILURE");
    }

    // ---- fixtures -----------------------------------------------------------------------------------------------

    /// The default reactor built, every module green: alpha 2 testcases (in a suite claiming tests="99") plus 1 in its
    /// one-carrier execution, beta 1, gamma 1 (failsafe).
    // ---- round 3: @Nested classes whose @BeforeAll assumption failed (1), the mvn that runs and env-activated
    // ---- profiles (2), per reports directory (3), per-module declared values (4), the poms' own JVM properties (5), the
    // ---- repo set at all (6) -----------------------------------------------------------------------------------------

    /// Surefire counts an outer class's own testcases into one @Nested class's line: a line with N > 0 for a class with
    /// no testcase of its own is that class not running (ManifestOracleIT's shape), not a miscount.
    @ParameterizedTest(name = "log ''{0}'', testcases of {1}: {2}")
    @CsvSource(delimiter = '|', value = {"3 in fx.Outer$Docker | fx.Outer | PARTIAL",
                                         "42 in fx.Outer$Rerun | fx.Outer$Rerun | GREEN",
                                         "0 in fx.Outer$Docker | fx.Outer | PARTIAL",
                                         "0 in fx.Outer | fx.Outer$Inner | GREEN",
                                         "1 in fx.Outer | fx.Outer$Inner | PARTIAL"})
    void build_judgesEachClassLineAgainstTheTestcasesOfItsClass(String line, String classname, String verdict) throws Exception {
        green();
        logAfterAlphasTests("[INFO] Tests run: " + line.replace(" in ", ", Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in ") + "\n");
        reportFile("alpha", "TEST-fx.Outer.xml", suite(2).replace("fx.ATest", classname));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(verdict.equals("GREEN") ? 0 : 1);
        assertThat(outcome.output()).contains(verdict);
        if (verdict.equals("PARTIAL")) {
            assertThat(outcome.output()).contains("1 test classes ran no testcase").contains("alpha " + line.split(" in ")[1]);
        }
    }

    /// One class in two executions (temper-controller's StartFailureLivenessTest): each reports directory on its own.
    @Test
    void build_isPartial_whenAClassRanNothingInOneExecution_thoughItRanInAnother() throws Exception {
        green();
        reports("alpha", suite(2).replace("fx.ATest", "fx.ExecTest"));
        var oneCarrier = "[INFO] --- surefire:3.5.4:test (one-carrier) @ alpha ---\n";
        Files.writeString(fake.resolve("log"), Files.readString(fake.resolve("log")).replace(oneCarrier, oneCarrier
            + "[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.1 s -- in fx.ExecTest\n"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("alpha fx.ExecTest");
    }

    /// An mvn first on PATH can add any flag: only a Maven installation ops/dev/maven-homes lists runs.
    @Test
    void build_refusesAnMvnUnderNoListedMavenHome() throws Exception {
        green();
        Files.writeString(ops.resolve("maven-homes"), "# none\n/opt/elsewhere a home this fixture lacks\n");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: mvn " + fake.resolve("bin/mvn")).contains("under no Maven home ops/dev/maven-homes lists");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    /// The realpath counts: an mvn on PATH that links into a listed home runs; no maven-homes at all runs nothing.
    @Test
    void build_judgesTheMvnByItsRealpath_andRefusesWithoutMavenHomes() throws Exception {
        green();
        var home = Files.createDirectories(dir.resolve("home/bin"));
        Files.move(fake.resolve("bin/mvn"), home.resolve("mvn"));
        Files.createSymbolicLink(fake.resolve("bin/mvn"), home.resolve("mvn"));
        Files.writeString(ops.resolve("maven-homes"), dir.resolve("home").toRealPath() + " a home the PATH entry links into\n");

        var linked = build(List.of("install"), Map.of());

        assertThat(linked.rc()).as(linked.output()).isZero();

        Files.delete(ops.resolve("maven-homes"));
        var none = build(List.of("install"), Map.of());

        assertThat(none.rc()).as(none.output()).isEqualTo(2);
        assertThat(none.output()).contains("under no Maven home ops/dev/maven-homes lists");
    }

    /// A test-selecting profile activated by an environment variable is refused when the activation HOLDS: env.X while
    /// X is set (even empty), !env.X while X is unset.
    @ParameterizedTest(name = "activation {0}, BETA_ENV {1}: {2}")
    @CsvSource(delimiter = '|', value = {"env.BETA_ENV | set | refused: BETA_ENV= (environment) activates profile beta-tier (env.BETA_ENV)",
                                         "env.BETA_ENV | unset | ''",
                                         "!env.BETA_ENV | set | ''",
                                         "!env.BETA_ENV | unset | refused: BETA_ENV unset (environment) activates profile beta-tier (!env.BETA_ENV)"})
    void build_refusesAnEnvironmentVariableThatActivatesATestSelectingProfile(String activation, String state, String refusal) throws Exception {
        green();
        betaPom(EXECUTIONS.get("beta").replace("<name>beta.tier</name>", "<name>" + activation + "</name>"));

        var outcome = build(List.of("install"), state.equals("set") ? Map.of("BETA_ENV", "") : Map.of());

        if (refusal.isEmpty()) {
            assertThat(outcome.rc()).as(outcome.output()).isZero();
            assertThat(outcome.output()).doesNotContain("refused");
        } else {
            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
            assertThat(outcome.output()).contains(refusal + ", which sets beta.excludedGroups");
        }
    }

    /// A test-selecting profile whose activation can hold with nothing in the run naming it (a file, the OS, the JDK,
    /// activeByDefault, a negated or JVM-defined property) is refused, whether its condition holds here or not.
    @ParameterizedTest(name = "activation {0} is refused")
    @ValueSource(strings = {"<file><missing>${basedir}/fx.quick</missing></file>", "<file><exists>${basedir}/fx.quick</exists></file>",
                            "<os><family>windows</family></os>", "<jdk>1.4</jdk>", "<activeByDefault>true</activeByDefault>",
                            "<property><name>!beta.tier</name></property>", "<property><name>beta.tier</name><value>!x</value></property>",
                            "<property><name>java.version</name></property>"})
    void build_refusesATestSelectingProfileWithAnActivationTheRunDoesNotName(String condition) throws Exception {
        green();
        betaPom(EXECUTIONS.get("beta").replace("<property><name>beta.tier</name></property>", condition));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: profile beta-tier (pom) selects tests (sets beta.excludedGroups) and its activation "
                                              + condition.replace("<name>", "").replace("</name>", "")
                                                         .replace("<value>", "=").replace("</value>", "")
                                              + " can hold with nothing in the run naming it");
    }

    /// activeByDefault false activates nothing: the profile stays judged by what activates it.
    @Test
    void build_acceptsATestSelectingProfileThatIsNotActiveByDefault() throws Exception {
        green();
        betaPom(EXECUTIONS.get("beta").replace("<activation>", "<activation><activeByDefault>false</activeByDefault>"));

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
    }

    /// Modules are read recursively, root -> agg -> m: m's surefire configuration reading ${env.FX_ONLY} is refused,
    /// and build-report judges m (in the default reactor, not built: PARTIAL).
    @Test
    void build_readsNestedModules() throws Exception {
        nested("", "", "<configuration><test>${env.FX_ONLY}</test></configuration>");

        var refused = build(List.of("install"), Map.of("FX_ONLY", "1"));

        assertThat(refused.rc()).as(refused.output()).isEqualTo(2);
        assertThat(refused.output()).contains("refused: FX_ONLY=1 (environment) is read by surefire/failsafe configuration (${env.FX_ONLY})");

        var unbuilt = build(List.of("install"), Map.of());

        assertThat(unbuilt.rc()).as(unbuilt.output()).isEqualTo(1);
        assertThat(unbuilt.output()).contains("PARTIAL").contains("m: in the default reactor but not built");
    }

    /// A nested module's effective value is its own, else its parent aggregator's, else the root's: m inherits
    /// agg's slow over the root's none, so -Dnest.groups=slow is a run as without it and none a test selection.
    @ParameterizedTest(name = "-Dnest.groups={0}: selection {1}")
    @CsvSource(delimiter = '|', value = {"slow | false", "none | true"})
    void build_judgesANestedModuleByItsAggregatorsValue(String value, boolean selects) throws Exception {
        Files.writeString(tree.resolve("pom.xml"), Files.readString(tree.resolve("pom.xml"))
            .replace("<name>fx</name>", "<name>fx</name><properties><nest.groups>none</nest.groups></properties>"));
        nested("<parent><artifactId>fx-parent</artifactId></parent><properties><nest.groups>slow</nest.groups></properties>",
               "<parent><artifactId>agg</artifactId></parent>", "<configuration><excludedGroups>${nest.groups}</excludedGroups></configuration>");

        var outcome = build(List.of("install", "-Dnest.groups=" + value), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        var selection = "test selection: -Dnest.groups=" + value + " (command line) is read by surefire/failsafe configuration";
        if (selects) {
            assertThat(outcome.output()).contains(selection);
        } else {
            assertThat(outcome.output()).doesNotContain("-Dnest.groups=" + value + " (command line)");
        }
    }

    /// root -> agg -> agg/m, m's surefire plugin carrying `surefire`; then green().
    private void nested(String agg, String m, String surefire) throws IOException {
        Files.writeString(tree.resolve("pom.xml"), Files.readString(tree.resolve("pom.xml"))
            .replace("<module>gamma</module>", "<module>gamma</module><module>agg</module>"));
        var module = Files.createDirectories(tree.resolve("agg/m"));
        Files.writeString(module.getParent().resolve("pom.xml"),
                          "<project>" + agg + "<artifactId>agg</artifactId><modules><module>m</module></modules></project>\n");
        Files.writeString(module.resolve("pom.xml"), "<project>" + m + "<artifactId>m</artifactId><build><plugins><plugin>"
            + "<artifactId>maven-surefire-plugin</artifactId>" + surefire + "</plugin></plugins></build></project>\n");
        Files.writeString(Files.createDirectories(module.resolve("src/test/java/fx")).resolve("MTest.java"), "class MTest {}\n");
        green();
    }

    /// A value the poms declare is accepted only when it is the effective one of EVERY module that reads the property:
    /// a module's own <properties>, else its parent root pom's.
    @ParameterizedTest(name = "alpha declares {0}, -Dbeta.excludedGroups={1}: {2}")
    @CsvSource(delimiter = '|', value = {"<properties><beta.excludedGroups>slow</beta.excludedGroups></properties> | slow | PARTIAL",
                                         "<properties><beta.excludedGroups>slow</beta.excludedGroups></properties> | none | PARTIAL",
                                         "<properties><beta.excludedGroups>none</beta.excludedGroups></properties> | none | GREEN",
                                         "<parent><artifactId>fx-parent</artifactId></parent> | none | GREEN",
                                         "<parent><artifactId>fx-parent</artifactId></parent> | slow | PARTIAL",
                                         "'' | none | PARTIAL"})
    void build_acceptsADeclaredValueOnlyWhenEveryReaderDeclaresIt(String declares, String value, String verdict) throws Exception {
        Files.writeString(tree.resolve("pom.xml"), Files.readString(tree.resolve("pom.xml"))
            .replace("<name>fx</name>", "<name>fx</name><properties><beta.excludedGroups>none</beta.excludedGroups></properties>"));
        Files.writeString(tree.resolve("alpha/pom.xml"), "<project>" + declares + "<artifactId>alpha</artifactId><name>fx alpha</name>"
            + EXECUTIONS.get("alpha").replace("<executions>", "<configuration><groups>${beta.excludedGroups}</groups></configuration><executions>")
            + "</project>\n");
        green();

        var outcome = build(List.of("install", "-Dbeta.excludedGroups=" + value), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(verdict.equals("GREEN") ? 0 : 1);
        assertThat(outcome.output()).contains(verdict);
    }

    /// The root pom's surefire configuration reaches every module: each one's effective value counts.
    @ParameterizedTest(name = "-Droot.groups={0}: {1}")
    @CsvSource(delimiter = '|', value = {"a | PARTIAL", "b | PARTIAL"})
    void build_judgesAPropertyTheRootConfigurationReads_inEveryModule(String value, String verdict) throws Exception {
        Files.writeString(tree.resolve("pom.xml"), Files.readString(tree.resolve("pom.xml")).replace("<name>fx</name>",
            "<name>fx</name><properties><root.groups>a</root.groups></properties><build><plugins><plugin>"
            + "<artifactId>maven-surefire-plugin</artifactId><configuration><groups>${root.groups}</groups></configuration>"
            + "</plugin></plugins></build>"));
        betaPom("<properties><root.groups>b</root.groups></properties>");
        green();

        var outcome = build(List.of("install", "-Droot.groups=" + value), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains(verdict).contains("-Droot.groups=" + value + " (command line) is read by surefire/failsafe");
    }

    /// A property the poms' own systemPropertyVariables give the test JVM is recorded interpolated
    /// (`${project.basedir}/x.jar`): not a selection in the XML. The command line setting it still is.
    @ParameterizedTest(name = "set by {0}")
    @ValueSource(strings = {"<systemPropertyVariables><beta.jar>${beta.jar}</beta.jar></systemPropertyVariables>",
                            "<argLine>-Xmx64m -Dbeta.jar=${beta.jar}</argLine>"})
    void build_ignoresInTheXmlAPropertyThePomsOwnConfigurationSets_butJudgesItOnTheCommandLine(String sets) throws Exception {
        betaPom(EXECUTIONS.get("beta").replace("<basedir>", sets + "<basedir>")
                    .replaceFirst("<properties>", "<properties><beta.jar>\\${project.basedir}/x.jar</beta.jar>"));
        green();
        reports("beta", withProperties(suite(1), "beta.jar", tree.resolve("beta/x.jar").toString()));

        var pom = build(List.of("install"), Map.of());

        assertThat(pom.rc()).as(pom.output()).isZero();
        assertThat(pom.output()).contains("GREEN");

        var cli = build(List.of("install", "-Dbeta.jar=/other.jar"), Map.of());

        assertThat(cli.rc()).as(cli.output()).isEqualTo(1);
        assertThat(cli.output()).contains("PARTIAL").contains("-Dbeta.jar=/other.jar (command line) is read by surefire/failsafe configuration");
    }

    /// Without maven.repo.local maven resolves and installs into ~/.m2: some channel must set it to <tree>/.m2-local.
    @ParameterizedTest(name = "maven.config ''{0}'', MAVEN_OPTS ''{1}'': refused {2}")
    @CsvSource(delimiter = '|', value = {"-o | '' | true", "'' | '' | true", "-Dbeta.excludedGroups=none | '' | true",
                                         "'' | -Dmaven.repo.local=.m2-local | false",
                                         "-Dmaven.repo.local=.m2-local | '' | false"})
    void build_refusesARunInWhichNoChannelSetsTheTreesRepo(String config, String mavenOpts, boolean refused) throws Exception {
        green();
        Files.writeString(tree.resolve(".mvn/maven.config"), config + "\n");

        var outcome = build(List.of("install"), mavenOpts.isEmpty() ? Map.of() : Map.of("MAVEN_OPTS", mavenOpts));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(refused ? 2 : 0);
        if (refused) {
            assertThat(outcome.output()).contains("refused: no channel sets -Dmaven.repo.local");
            assertThat(fake.resolve("args")).doesNotExist();
        }
    }

    // ---- round 8: the java maven runs, quotes and comments as maven reads them, settings.xml, ${env.X} ------------

    /// The mvn script runs $JAVA_HOME/bin/java (else the java on PATH): a script there can add any flag (-Pquick).
    @ParameterizedTest(name = "a script java from {0} is refused")
    @ValueSource(strings = {"JAVA_HOME", "PATH"})
    void build_refusesAJavaThatIsAScript(String from) throws Exception {
        green();
        var jdk = Files.createDirectories(dir.resolve("scriptjdk/bin"));
        Files.writeString(jdk.resolve("java"), "#!/bin/sh\nexec " + REAL_JAVA + " \"$@\" -Pquick\n");
        jdk.resolve("java").toFile().setExecutable(true);
        if (from.equals("PATH")) {
            Files.copy(jdk.resolve("java"), fake.resolve("bin/java"));
            fake.resolve("bin/java").toFile().setExecutable(true);
        }

        var outcome = build(List.of("install"), Map.of("JAVA_HOME", from.equals("JAVA_HOME") ? jdk.getParent().toString() : ""));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("(" + from + ") resolves to").contains("not a native executable");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    /// Homebrew's bin/mvn is a wrapper: `JAVA_HOME="${JAVA_HOME:-<default>}" exec "<home>/libexec/bin/mvn"`. Its default
    /// JDK is the java maven runs, and the launcher's home is the one whose conf/settings.xml maven reads.
    @ParameterizedTest(name = "a wrapper whose {0}")
    @ValueSource(strings = {"default JAVA_HOME is a script", "launcher's conf/settings.xml selects tests", "chain is clean"})
    void build_followsAWrapperToMavensLauncher(String scenario) throws Exception {
        green();
        var cellar = dir.resolve("cellar");
        var launcher = Files.createDirectories(cellar.resolve("libexec/bin")).resolve("mvn");
        Files.move(fake.resolve("bin/mvn"), launcher);
        var script = scenario.startsWith("default");
        var jdk = script ? Files.createDirectories(dir.resolve("scriptjdk/bin")).getParent() : linkedJdk();
        if (script) {
            Files.writeString(jdk.resolve("bin/java"), "#!/bin/sh\nexec " + REAL_JAVA + " \"$@\"\n");
        }
        Files.writeString(Files.createDirectories(cellar.resolve("bin")).resolve("mvn"),
                          "#!/bin/sh\nJAVA_HOME=\"${JAVA_HOME:-" + jdk + "}\" exec \"" + launcher + "\"  \"$@\"\n");
        cellar.resolve("bin/mvn").toFile().setExecutable(true);
        Files.createSymbolicLink(fake.resolve("bin/mvn"), cellar.resolve("bin/mvn"));
        Files.writeString(ops.resolve("maven-homes"), cellar.toRealPath() + " the fixture's wrapped maven\n");
        var testProfile = settings("<profile><id>quick</id><activation><activeByDefault>true</activeByDefault></activation>"
                                   + "<properties><test>PassTest</test></properties></profile>", "");
        Files.writeString(Files.createDirectories(cellar.resolve("conf")).resolve("settings.xml"), testProfile);  // not maven's
        if (scenario.startsWith("launcher")) {
            Files.writeString(Files.createDirectories(cellar.resolve("libexec/conf")).resolve("settings.xml"), testProfile);
        }

        var outcome = build(List.of("install"), Map.of("JAVA_HOME", ""));

        switch (scenario) {
            case "default JAVA_HOME is a script" -> {
                assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
                assertThat(outcome.output()).contains("(the default JAVA_HOME of " + cellar.toRealPath().resolve("bin/mvn") + ")")
                                            .contains("not a native executable");
            }
            case "launcher's conf/settings.xml selects tests" -> {
                assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
                assertThat(outcome.output()).contains("refused: profile quick in " + cellar.toRealPath().resolve("libexec/conf/settings.xml"))
                                            .contains("can be active and sets test=PassTest");
            }
            default -> {
                assertThat(outcome.rc()).as(outcome.output()).isZero();
                assertThat(outcome.output()).contains("GREEN");
            }
        }
    }

    /// A profile setting surefire's own parameter is test-selecting, whatever activates it: an env activation with the
    /// variable set is refused, and a -P of it is a test selection.
    @ParameterizedTest(name = "a profile setting {0}")
    @ValueSource(strings = {"test", "groups", "excludedGroups", "includes", "excludes", "skipTests", "skipExec"})
    void build_judgesAProfileThatSetsASurefireParameter(String name) throws Exception {
        green();
        betaPom("<profiles><profile><id>fxonly</id><activation><property><name>env.FX_ONLY</name></property></activation>"
                + "<properties><" + name + ">PassTest</" + name + "></properties></profile></profiles>");

        var env = build(List.of("install"), Map.of("FX_ONLY", "1"));

        assertThat(env.rc()).as(env.output()).isEqualTo(2);
        assertThat(env.output()).contains("refused: FX_ONLY=1 (environment) activates profile fxonly (env.FX_ONLY), which sets " + name);

        var profile = build(List.of("install", "-Pfxonly"), Map.of());

        assertThat(profile.rc()).as(profile.output()).isEqualTo(1);
        assertThat(profile.output()).contains("PARTIAL").contains("test selection: -Pfxonly (command line) sets " + name);
    }

    /// Maven strips one pair of surrounding quotes from a word (CleanArgument) and from an option value (commons-cli);
    /// the JVM reads quotes in JAVA_TOOL_OPTIONS. Each spelling is judged as maven reads it (run against 3.9.12, 3.10.0).
    @ParameterizedTest(name = "{0} {1} is refused as {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "command line | \"-q\" | -q", "command line | \"-T4\" | -T4", "command line | \"-fn\" | -fn",
        "command line | -D\"jbct.skip=true | -D\"jbct.skip=true", "command line | -D\"jbct.skip=true\" | -Djbct.skip=true",
        "MAVEN_ARGS | \"-q\" | -q", ".mvn/maven.config | \"-q\" | -q",
        "JAVA_TOOL_OPTIONS | \"-Dmaven.test.failure.ignore=true\" | -Dmaven.test.failure.ignore=true",
        "JAVA_TOOL_OPTIONS | -D'maven.test.failure.ignore=a b' | -Dmaven.test.failure.ignore=a b"})
    void build_refusesAQuotedRefusedFlag(String channel, String word, String shown) throws Exception {
        green();

        var outcome = quoted(channel, word);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
        assertThat(outcome.output()).contains("refused: " + shown + " (" + channel + ")");
        assertThat(fake.resolve("args")).doesNotExist();
    }

    /// An unterminated quote takes the following words (lines, in maven.config) into one argument, as maven does; a
    /// maven.config line is one argument, spaces and all.
    @ParameterizedTest(name = "{0} {1} is a selection")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "command line | \"-Pbeta-tier\" | test selection: -Pbeta-tier (command line)",
        "command line | -P\"beta-tier\" | test selection: -Pbeta-tier (command line)",
        "MAVEN_ARGS | \"-Pbeta-tier\" | test selection: -Pbeta-tier (MAVEN_ARGS)",
        ".mvn/maven.config | \"-Pbeta-tier\" | test selection: -Pbeta-tier (.mvn/maven.config)",
        ".mvn/maven.config | \"-Dbeta.tier=1\\n-q | test selection: -Dbeta.tier=1 -q (.mvn/maven.config) activates profile beta-tier",
        ".mvn/maven.config | -Dbeta.tier=1 -q | test selection: -Dbeta.tier=1 -q (.mvn/maven.config) activates profile beta-tier"})
    void build_judgesAQuotedSelectionAsMavenReadsIt(String channel, String word, String reason) throws Exception {
        green();

        var outcome = quoted(channel, word);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains(reason);
    }

    /// maven.config holds one argument per line, and a line starting with # is a comment (3.9.12 and 3.10.0 alike),
    /// dropped before quotes are read: it does not join an argument an unterminated quote opened.
    @ParameterizedTest(name = "maven.config line ''{0}''")
    @ValueSource(strings = {"# -Pbeta-tier was here", "#-q", "# -Dmaven.test.failure.ignore=true",
                            "\"-Dbeta.excludedGroups=none\n# a note"})
    void build_readsAHashLineOfMavenConfigAsAComment(String line) throws Exception {
        green();
        Files.writeString(tree.resolve(".mvn/maven.config"), "-Dmaven.repo.local=.m2-local\n" + line + "\n");

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("GREEN");
    }

    /// The settings.xml maven reads unless -s: a profile that can be active and sets a refused or test-selecting
    /// property is refused; a harmless one is not. -Duser.home stays outside the allowlist (PARTIAL), its settings
    /// judged anyway.
    @ParameterizedTest(name = "user settings {0}{1}: {2}")
    @CsvSource(delimiter = '|', value = {
        "<profile><id>s</id><activation><activeByDefault>true</activeByDefault></activation><properties><beta.excludedGroups>slow</beta.excludedGroups></properties></profile> | '' | refused: profile s in",
        "<profile><id>s</id><activation><property><name>x</name></property></activation><properties><groups>fast</groups></properties></profile> | '' | refused: profile s in",
        "<profile><id>s</id><properties><maven.test.failure.ignore>true</maven.test.failure.ignore></properties></profile> | <activeProfiles><activeProfile>s</activeProfile></activeProfiles> | refused: profile s in",
        "'' | <activeProfiles><activeProfile>beta-tier</activeProfile></activeProfiles> | refused: activeProfile beta-tier in",
        "<profile><id>s</id><activation><activeByDefault>true</activeByDefault></activation><properties><gpg.executable>gpg</gpg.executable></properties></profile> | '' | PARTIAL",
        "<profile><id>s</id><activation><activeByDefault>true</activeByDefault></activation><properties><beta.excludedGroups>none</beta.excludedGroups></properties></profile> | '' | PARTIAL",
        "<profile><id>s</id><activation><activeByDefault>false</activeByDefault></activation><properties><test>PassTest</test></properties></profile> | '' | PARTIAL"})
    void build_judgesTheProfilesOfTheUserSettings(String profiles, String activeProfiles, String expected) throws Exception {
        green();
        var home = Files.createDirectories(dir.resolve("home/.m2")).getParent();
        Files.writeString(home.resolve(".m2/settings.xml"), settings(profiles, activeProfiles));

        var outcome = build(List.of("install"), Map.of("MAVEN_OPTS", "-Duser.home=" + home));

        if (expected.startsWith("refused")) {
            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(2);
            assertThat(outcome.output()).contains(expected + " " + home.resolve(".m2/settings.xml"));
            assertThat(fake.resolve("args")).doesNotExist();
        } else {
            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
            assertThat(outcome.output()).contains("PARTIAL").contains("-Duser.home=" + home + " (MAVEN_OPTS)")
                                        .doesNotContain("test selection");
        }
    }

    /// A settings profile that sets a test-selecting property but cannot be active is a test selection when -P names it.
    @Test
    void build_judgesAPOfAnInactiveTestSelectingSettingsProfile() throws Exception {
        green();
        var home = Files.createDirectories(dir.resolve("home/.m2")).getParent();
        Files.writeString(home.resolve(".m2/settings.xml"), settings("<profile><id>s</id><properties><test>PassTest</test></properties></profile>", ""));

        var outcome = build(List.of("install", "-Ps"), Map.of("MAVEN_OPTS", "-Duser.home=" + home));

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(outcome.output()).contains("PARTIAL").contains("test selection: -Ps (command line) sets test=PassTest");
    }

    /// surefire/failsafe configuration reading ${env.X}, directly or through a property: X set is refused.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration><excludedGroups>${env.FX_SKIP}</excludedGroups></configuration></plugin></plugins></build>",
        "<properties><fx.skip>${env.FX_SKIP}</fx.skip></properties><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration><excludedGroups>${fx.skip}</excludedGroups></configuration></plugin></plugins></build>"})
    void build_refusesAnEnvironmentVariableTestConfigurationReads(String pom) throws Exception {
        green();
        betaPom(pom);

        var set = build(List.of("install"), Map.of("FX_SKIP", "slow"));

        assertThat(set.rc()).as(set.output()).isEqualTo(2);
        assertThat(set.output()).contains("refused: FX_SKIP=slow (environment) is read by surefire/failsafe configuration (${env.FX_SKIP})");

        var unset = build(List.of("install"), Map.of());

        assertThat(unset.rc()).as(unset.output()).isZero();
    }

    /// An execution the root pom declares in <build><plugins>: the root (no src/test) is not judged by it, and a module
    /// inheriting it is judged in that execution's reportsDirectory.
    @ParameterizedTest(name = "alpha''s execution two ran {0} testcase(s): {1}")
    @CsvSource(delimiter = '|', value = {"1 | GREEN", "0 | RED"})
    void build_judgesARootDeclaredExecutionInTheModulesInheritingIt(int ran, String verdict) throws Exception {
        Files.writeString(tree.resolve("pom.xml"), Files.readString(tree.resolve("pom.xml")).replace("<name>fx</name>",
            "<name>fx</name><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><executions><execution>"
            + "<id>two</id><goals><goal>test</goal></goals><configuration>"
            + "<reportsDirectory>${project.build.directory}/surefire-reports-two</reportsDirectory></configuration>"
            + "</execution></executions></plugin></plugins></build>"));
        Files.writeString(tree.resolve("alpha/pom.xml"), Files.readString(tree.resolve("alpha/pom.xml"))
            .replace("<project>", "<project><parent><artifactId>fx-parent</artifactId></parent>"));
        green();
        var log = Files.readString(fake.resolve("log"));
        for (var artifact : List.of("fx-parent", "alpha")) {
            var header = "[INFO] --- surefire:3.5.4:test (default-test) @ " + artifact + " ---\n";
            log = log.replace(header, header + "[INFO] --- surefire:3.5.4:test (two) @ " + artifact + " ---\n");
        }
        Files.writeString(fake.resolve("log"), log);
        if (ran > 0) {
            reports("alpha", "surefire-reports-two", suite(ran));
        }

        var outcome = build(List.of("install"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(verdict.equals("GREEN") ? 0 : 1);
        assertThat(outcome.output()).contains(verdict).doesNotContain("fx-parent: surefire execution two");
        if (ran == 0) {
            assertThat(outcome.output()).contains("alpha: surefire execution two: 0 fresh testcases in target/surefire-reports-two");
        }
    }

    /// A value the poms declare for a property on surefire's own list (acceptance.excludedGroups) is a run as without
    /// it; another value is a test selection.
    @ParameterizedTest(name = "-Dacceptance.excludedGroups={0}: {1}")
    @CsvSource(delimiter = '|', value = {"host | GREEN", "docker | PARTIAL"})
    void build_acceptsTheDeclaredValueOfATestSelectionProperty(String value, String verdict) throws Exception {
        betaPom("<properties><acceptance.excludedGroups>host</acceptance.excludedGroups></properties><build><plugins><plugin>"
                + "<artifactId>maven-surefire-plugin</artifactId><configuration><excludedGroups>${acceptance.excludedGroups}"
                + "</excludedGroups></configuration></plugin></plugins></build>");
        green();

        var outcome = build(List.of("install", "-Dacceptance.excludedGroups=" + value, "-Pacceptance"), Map.of());

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(verdict.equals("GREEN") ? 0 : 1);
        assertThat(outcome.output()).contains(verdict);
    }

    private Outcome quoted(String channel, String word) throws IOException, InterruptedException {
        return switch (channel) {
            case "command line" -> build(List.of("install", word), Map.of());
            case ".mvn/maven.config" -> {
                Files.writeString(tree.resolve(".mvn/maven.config"), "-Dmaven.repo.local=.m2-local\n" + word.replace("\\n", "\n") + "\n");
                yield build(List.of("install"), Map.of());
            }
            default -> build(List.of("install"), Map.of(channel, word));
        };
    }

    private static String settings(String profiles, String activeProfiles) {
        return "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\"><profiles>" + profiles + "</profiles>"
               + activeProfiles + "</settings>\n";
    }

    /// A JDK home whose bin/java links to the native java running this test.
    private Path linkedJdk() throws IOException {
        var jdk = Files.createDirectories(dir.resolve("jdk/bin"));
        Files.createSymbolicLink(jdk.resolve("java"), REAL_JAVA);
        return jdk.getParent();
    }

    private static Path realJava() {
        try {
            return Path.of(System.getProperty("java.home"), "bin", "java").toRealPath();
        } catch (IOException cause) {
            throw new IllegalStateException(cause);
        }
    }

    private void betaPom(String body) throws IOException {
        Files.writeString(tree.resolve("beta/pom.xml"), "<project><artifactId>beta</artifactId><name>fx beta</name>" + body + "</project>\n");
    }

    private void green() throws IOException {
        scenario(List.of(Built.ok("fx-parent"), Built.ok("alpha"), Built.ok("beta"), Built.ok("gamma")), true);
        passingReports();
    }

    private void passingReports() throws IOException {
        reports("alpha", suite(2).replace("tests=\"2\"", "tests=\"99\""));
        reports("alpha", "surefire-reports-one-carrier", suite(1));
        reports("beta", suite(1));
        reports("gamma", "failsafe-reports", suite(1));
    }

    private void scenario(List<Built> modules, boolean success) throws IOException {
        var log = new StringBuilder("[INFO] Reactor Build Order:\n");
        var index = 0;
        for (var module : modules) {
            log.append("[INFO] Building ").append(NAMES.get(module.artifact())).append(" 0.1.0-SNAPSHOT  [")
               .append(++index).append('/').append(modules.size()).append("]\n");
            var executions = new ArrayList<>(List.of("[INFO] --- surefire:3.5.4:test (default-test) @ " + module.artifact() + " ---\n"));
            if (module.artifact().equals("alpha")) {
                executions.add("[INFO] --- surefire:3.5.4:test (one-carrier) @ alpha ---\n");
            }
            if (module.artifact().equals("gamma")) {
                executions.add(GAMMA_FAILSAFE);
            }
            for (var execution : executions) {
                log.append(execution);
                if (module.skipped()) {
                    log.append("[INFO] Tests are skipped.\n");
                }
            }
            log.append("[INFO] --- jbct:1.0.0-rc4:check (jbct-check) @ ").append(module.artifact()).append(" ---\n");
            if (module.jbct() >= 0) {
                log.append("[INFO] Check results (checked ").append(module.jbct())
                   .append(" file(s)): 0 format issue(s), 0 lint error(s), 0 warning(s)\n");
            }
        }
        log.append("[INFO] Reactor Summary for fx 0.1.0-SNAPSHOT:\n[INFO] \n");
        for (var module : modules) {
            log.append("[INFO] ").append(NAMES.get(module.artifact())).append(" ......... ")
               .append(success ? "SUCCESS" : "FAILURE").append(" [  1.000 s]\n");
        }
        log.append(success ? "[INFO] BUILD SUCCESS\n" : "[INFO] BUILD FAILURE\n");
        Files.writeString(fake.resolve("log"), log.toString());
    }

    private void reports(String artifact, String xml) throws IOException {
        reports(artifact, "surefire-reports", xml);
    }

    private void reports(String artifact, String reportDir, String xml) throws IOException {
        Files.writeString(Files.createDirectories(fake.resolve("reports").resolve(artifact).resolve(reportDir))
                               .resolve("TEST-fx.ATest.xml"), xml);
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        var outcome = ran(HermeticProcess.builder(command), Map.of());
        assertThat(outcome.rc()).as(outcome.output()).isZero();
        return outcome.output();
    }

    private void logAfterAlphasTests(String lines) throws IOException {
        var header = "[INFO] --- surefire:3.5.4:test (default-test) @ alpha ---\n";
        Files.writeString(fake.resolve("log"), Files.readString(fake.resolve("log")).replace(header, header + lines));
    }

    /// The suite with a <properties> block holding one system property, as surefire records the test JVM's.
    private static String withProperties(String suite, String name, String value) {
        return suite.replaceFirst("(<testsuite[^>]*>\n)", "$1  <properties><property name=\"" + name + "\" value=\"" + value
                                                          + "\"/></properties>\n");
    }

    private Path newestJson() throws IOException {
        try (var files = Files.list(tree.resolve(".wp-logs"))) {
            return files.filter(path -> path.toString().endsWith(".json"))
                        .max(Comparator.comparing(path -> path.toFile().lastModified()))
                        .orElseThrow();
        }
    }

    private void reportFile(String artifact, String file, String xml) throws IOException {
        Files.writeString(Files.createDirectories(fake.resolve("reports").resolve(artifact).resolve("surefire-reports"))
                               .resolve(file), xml);
    }

    /// The fixture's ops/dev: a copy (the scripts find each other, known-skips and maven-homes next to themselves)
    /// whose maven-homes lists only `home`, where the fake `mvn` lives.
    private Path opsCopy(Path home) throws IOException {
        var copy = Files.createDirectories(dir.resolve("ops-copy"));
        for (var script : List.of("build", "build-args", "build-lock", "build-report", "build_rules.py", "lib.sh",
                                  "known-skips")) {
            Files.copy(OPS.resolve(script), copy.resolve(script), StandardCopyOption.REPLACE_EXISTING);
            copy.resolve(script).toFile().setExecutable(true);
        }
        Files.writeString(copy.resolve("maven-homes"), home.toRealPath() + " the fixture's fake maven\n");
        return copy;
    }

    /// The fixture's ops/dev with its own known-skips.
    private Path opsWithKnownSkips(String knownSkips) throws IOException {
        Files.writeString(ops.resolve("known-skips"), knownSkips);
        return ops;
    }

    private void deleteReports(String artifact) throws IOException {
        deleteTree(fake.resolve("reports").resolve(artifact));
    }

    private void deleteReports(String artifact, String reportDir) throws IOException {
        deleteTree(fake.resolve("reports").resolve(artifact).resolve(reportDir));
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /// A surefire suite of `passed` passing testcases, plus one testcase per `children` entry carrying it.
    private static String suite(int passed, String... children) {
        var cases = new ArrayList<String>();
        for (var i = 0; i < passed; i++) {
            cases.add("  <testcase name=\"t" + i + "\" classname=\"fx.ATest\" time=\"0.001\"/>");
        }
        for (var child : children) {
            cases.add("  <testcase name=\"x\" classname=\"fx.ATest\" time=\"0.001\">" + child + "</testcase>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"fx.ATest\" tests=\"" + cases.size()
               + "\" failures=\"0\" errors=\"0\" skipped=\"0\">\n" + String.join("\n", cases) + "\n</testsuite>\n";
    }

    private void lockHeldBy(long pid) throws IOException {
        var lock = Files.createDirectories(tree.resolve(".wp-logs/build.lock"));
        Files.writeString(lock.resolve("pid"), pid + "\n");
        Files.writeString(lock.resolve("info"), "start=2026-10-24T00:00:00Z\nargs=verify\n");
    }

    private Outcome build(List<String> arguments, Map<String, String> environment) throws IOException, InterruptedException {
        return ran(buildCommand(arguments), environment);
    }

    private ProcessBuilder buildCommand(List<String> arguments) {
        return buildCommand(ops, arguments);
    }

    private static ProcessBuilder buildCommand(Path ops, List<String> arguments) {
        var command = new ArrayList<>(List.of("bash", ops.resolve("build").toString()));
        if (!arguments.isEmpty() && !arguments.getFirst().startsWith("--")) {
            command.add("--");
        }
        command.addAll(arguments);
        return HermeticProcess.builder(command);
    }

    /// The child's environment: the host's families stripped (HermeticProcess: BB_*, TEMPER_*, CLAUDE*, GIT_* ...) and the
    /// maven option variables; PATH, HOME, locale stay inherited (the fake mvn goes first on PATH), JAVA_HOME is this JVM's.
    private void hermetic(ProcessBuilder builder, Map<String, String> scenario) {
        var environment = builder.environment();
        environment.remove("MAVEN_OPTS");
        environment.remove("MAVEN_ARGS");
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        environment.remove("BUILD_LOCK_TEST_PAUSE");
        environment.put("JAVA_HOME", System.getProperty("java.home"));
        environment.putAll(scenario);
        environment.put("PATH", fake.resolve("bin") + ":" + environment.getOrDefault("PATH", "/usr/bin:/bin"));
        environment.put("FAKE", fake.toString());
    }

    /// A build running in the background, its output in `out`; `ran` does the same in the foreground.
    private Process started(List<String> arguments, Path out, Map<String, String> scenario) throws IOException {
        var builder = buildCommand(arguments).directory(tree.toFile()).redirectErrorStream(true)
                                             .redirectOutput(out.toFile());
        hermetic(builder, scenario);
        return builder.start();
    }

    /// The lock's `pid` file once maven has recorded itself in it, as its second line (at most 20 s).
    private List<String> lockOnceMavenRuns() throws Exception {
        var pid = tree.resolve(".wp-logs/build.lock/pid");
        for (var i = 0; i < 200; i++) {
            if (Files.exists(pid)) {
                var lines = Files.readAllLines(pid);
                if (lines.size() > 1) {
                    return lines;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("maven never recorded itself in the lock");
    }

    private String lstartOf(long pid) throws IOException, InterruptedException {
        var ps = ran(HermeticProcess.builder("sh", "-c", "LC_ALL=C TZ=UTC ps -o lstart= -p " + pid), Map.of());
        return String.join(" ", ps.output().trim().split("\\s+"));
    }

    private static long mavenPid(List<String> lock) {
        return Long.parseLong(lock.get(1));
    }

    private static void awaitExit(long pid) throws Exception {
        var handle = ProcessHandle.of(pid);
        if (handle.isPresent()) {
            handle.get().onExit().get(20, TimeUnit.SECONDS);
        }
    }

    /// Kills only the fake maven this test started and recorded.
    private static void killRecorded(long pid) {
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
    }

    private Outcome report(Path marker, Path log, String... more) throws IOException, InterruptedException {
        var command = new ArrayList<>(List.of("python3", REPORT.toString(), "--tree", tree.toString(),
                                              "--marker", marker.toString(), "--log", log.toString()));
        command.addAll(List.of(more));
        return ran(HermeticProcess.builder(command), Map.of());
    }

    /// Runs in the fixture tree with the fake `mvn` first on PATH; MAVEN_OPTS and MAVEN_ARGS are only the scenario's,
    /// never the ones of the build running this test.
    private Outcome ran(ProcessBuilder builder, Map<String, String> scenario) throws IOException, InterruptedException {
        builder.directory(tree.toFile()).redirectErrorStream(true);
        hermetic(builder, scenario);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        return new Outcome(process.waitFor(), output);
    }
}
