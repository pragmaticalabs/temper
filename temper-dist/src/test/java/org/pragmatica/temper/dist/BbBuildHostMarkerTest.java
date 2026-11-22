package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// [BbFixtures#buildHost()], the #84 build-host marker (CTO, 2026-11-22): an executable regular file `tools/bb-build` under the
/// REAL user home. A HOME a fixture sets must not count: most Bb*IT classes point HOME at a fake home holding a fake
/// `tools/bb-build`, so a marker read from HOME would call any child of such a fixture a build host.
///
/// Probe (red, then reverted): read `System.getenv("HOME")` in [BbFixtures#buildHost()] ->
/// [#aFixtureHomeDoesNotCount_onlyTheRealUserHome].
class BbBuildHostMarkerTest {
    @TempDir
    Path dir;

    @Test
    void anExecutableBbBuildUnderTools_isABuildHost() throws Exception {
        assertThat(BbFixtures.buildHost(positive("positive"))).isTrue();
    }

    @Test
    void noBbBuild_isNotABuildHost() throws Exception {
        assertThat(BbFixtures.buildHost(Files.createDirectories(dir.resolve("absent/tools")).getParent())).isFalse();
    }

    @Test
    void aBbBuildThatIsNotExecutable_isNotABuildHost() throws Exception {
        var home = dir.resolve("plain");

        Files.createDirectories(home.resolve("tools"));
        Files.writeString(home.resolve("tools/bb-build"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(home.resolve("tools/bb-build"), PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(BbFixtures.buildHost(home)).isFalse();
    }

    @Test
    void aBbBuildDirectory_isNotABuildHost() throws Exception {
        var home = dir.resolve("directory");

        Files.createDirectories(home.resolve("tools/bb-build"));
        Files.setPosixFilePermissions(home.resolve("tools/bb-build"), PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThat(BbFixtures.buildHost(home)).isFalse();
    }

    // A child JVM with the test classpath and NO -Duser.home answers buildHost() with HOME set to a positive and then a negative
    // fixture. Both answers must equal this JVM's: whichever the real home is, one of the two fixtures disagrees with it, so a
    // predicate fed HOME goes red on a build host and off it alike.
    @Test
    void aFixtureHomeDoesNotCount_onlyTheRealUserHome() throws Exception {
        var real = BbFixtures.buildHost();
        var positive = positive("fixture-positive");
        var negative = Files.createDirectories(dir.resolve("fixture-negative"));

        assertThat(BbFixtures.buildHost(positive)).isTrue();
        assertThat(BbFixtures.buildHost(negative)).isFalse();
        assertThat(childAnswer(positive)).as("child with HOME=%s", positive).isEqualTo("BUILD-HOST " + real);
        assertThat(childAnswer(negative)).as("child with HOME=%s", negative).isEqualTo("BUILD-HOST " + real);
    }

    private Path positive(String name) throws Exception {
        var home = dir.resolve(name);

        BbFixtures.executable(home.resolve("tools/bb-build"), "#!/bin/sh\n");

        return home;
    }

    private static String childAnswer(Path home) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var outcome = BbFixtures.run(List.of(java, "-cp", System.getProperty("java.class.path"), Probe.class.getName()),
                                     Map.of("HOME", home.toString()), null, null);

        assertThat(outcome.rc()).as(outcome.stderr()).isZero();

        return outcome.stdout().strip();
    }

    static final class Probe {
        public static void main(String[] args) {
            System.out.println("BUILD-HOST " + BbFixtures.buildHost());
        }
    }
}
