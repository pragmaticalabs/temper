package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `ops/bb/bb-portgroup.py` run for real against a two-module reactor whose module `m1` binds fixed ports. The answer is
/// `1 <reason>` (port-group member: waits for the group) or `0 <reason>`.
///
/// Probe: make the bare-goal rule always answer member (drop the `runs_tests` check in bb-portgroup.py) ->
/// [#aBareGoal_orAnEarlyPhase_runsNoTests_andIsNotAMember] red.
class BbPortGroupIT {
    private static final Path SCRIPT = BbFixtures.OPS_BB.resolve("bb-portgroup.py");

    @TempDir
    Path dir;

    private Path modules;

    @BeforeEach
    void reactor() throws IOException {
        assumeTrue(pythonPresent(), "the classifier is python3");
        Files.createDirectories(dir.resolve("m1"));
        Files.createDirectories(dir.resolve("m2"));
        Files.writeString(dir.resolve("pom.xml"), pom("root", "<modules><module>m1</module><module>m2</module></modules>"));
        Files.writeString(dir.resolve("m1/pom.xml"), pom("m1", ""));
        Files.writeString(dir.resolve("m2/pom.xml"), pom("m2", ""));
        modules = Files.writeString(dir.resolve("port-modules"), "m1\n");
    }

    private static boolean pythonPresent() {
        try {
            return BbFixtures.run(List.of("python3", "--version"), new HashMap<>(java.util.Map.of("PATH", System.getenv("PATH"))), null, null).rc() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static String pom(String artifactId, String body) {
        return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>"
               + artifactId + "</artifactId><version>1</version>" + body + "</project>";
    }

    private String classify(String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("python3", SCRIPT.toString(), modules.toString(), dir.toString()));

        command.addAll(List.of(args));
        var run = BbFixtures.run(command, new HashMap<>(java.util.Map.of("PATH", System.getenv("PATH"))), null, null);

        assertThat(run.rc()).as(run.stderr()).isZero();
        return run.stdout().strip();
    }

    private void assertClass(String expected, String... args) throws Exception {
        assertThat(classify(args)).as(String.join(" ", args)).startsWith(expected + " ");
    }

    @Test
    void aBareGoal_orAnEarlyPhase_runsNoTests_andIsNotAMember() throws Exception {
        assertClass("0", "org.pragmatica-lite:jbct-maven-plugin:check");
        assertClass("0", "jbct:check");
        assertClass("0", "-B", "jbct:check");
        assertClass("0", "compile");
        assertClass("0", "validate");
        assertClass("0", "-B", "clean", "test-compile");
        assertClass("0", "-pl", "m1", "compile");
        assertClass("0", "clean");
    }

    @Test
    void aPhaseFromTestOn_orATestGoal_withNoPl_isTheWholeReactor_andAMember() throws Exception {
        for (var phase : List.of("test", "prepare-package", "package", "pre-integration-test", "integration-test", "post-integration-test", "verify", "install", "deploy")) {
            assertClass("1", "-B", "clean", phase);
        }

        assertClass("1", "org.apache.maven.plugins:maven-surefire-plugin:test");
        assertClass("1", "failsafe:integration-test");
    }

    @Test
    void skippedTests_areNotAMember_evenWithATestPhase() throws Exception {
        assertClass("0", "-DskipTests", "install");
        assertClass("0", "-Dmaven.test.skip=true", "verify");
    }

    /// The -pl module-list logic is unchanged by the phase rule.
    @Test
    void withPl_theSelectedModules_decide() throws Exception {
        assertClass("1", "-pl", "m1", "test");
        assertClass("0", "-pl", "m2", "install");
        assertClass("1", "-pl", "!m1", "install");
        assertClass("0", "-pl", "m2", "-am", "-amd", "install");
    }
}
