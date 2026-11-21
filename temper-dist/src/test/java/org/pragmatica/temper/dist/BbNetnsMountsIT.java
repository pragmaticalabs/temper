package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `ops/bb/bb-netns-run` run against the REAL docker (issue #76): what the container may write of the host. A port-group ticket's
/// maven runs test code; before #76 the whole $HOME was mounted read-write, so test code could rewrite ~/tools/bb-*, other
/// checkouts and the queue. The ruling: the ticket's own checkout rw (its .m2-local lives inside), ~/tools and the maven dir ro,
/// nothing else of $HOME (a throwaway HOME in the container). The docker socket stays mounted: it is root-equivalent, so host
/// integrity against code that deliberately uses it is not claimed; the harm closed here is accidental writes through $HOME.
///
/// The stand-in below plays the test code: it tries to write each place and reports WROTE|DENIED; the host files are then checked
/// directly. The fake HOME lives under `target/` (a docker bind mounts a HOST path: a JUnit temp dir under /tmp would be the
/// container's own when this IT itself runs inside a netns container, where the checkout is the one path that is the host's).
///
/// Probes (each red, then reverted): re-add `-v "$HOME:$HOME"` -> [#testCodeCannotWriteTheToolsDir], [#testCodeCannotWriteAnotherCheckout],
/// [#testCodeCannotWriteTheQueue], [#testCodeCannotWriteTheMavenInstall]; make the ~/tools mount rw -> [#testCodeCannotWriteTheToolsDir].
class BbNetnsMountsIT {
    private static final Path SCRIPT = BbFixtures.OPS_BB.resolve("bb-netns-run");
    private static final String JAVA_HOME = "/usr/lib/jvm/java-25-openjdk-amd64";
    private static final String STAND_IN = """
        #!/usr/bin/bash
        w() { if { echo x >> "$2"; } 2> /dev/null; then echo "WROTE $1"; else echo "DENIED $1"; fi; }
        w tools-new "$HOME/tools/new"
        w tools-existing "$HOME/tools/marker"
        w maven-dir "$HOME/apache-maven-0/bin/new"
        w other-checkout "$HOME/work/co/other/new"
        w queue "$HOME/work/queue/new"
        w own-checkout "$PWD/new"
        mkdir -p "$PWD/.m2-local" 2> /dev/null; w m2-local "$PWD/.m2-local/x"
        w home "$HOME/.testcontainers.properties"
        echo "MAVEN RAN $*"
        "$JAVA_HOME/bin/java" -version 2>&1 | head -1
        """;

    private static Path home;
    private static Path co;
    private static BbFixtures.Outcome run;

    @BeforeAll
    static void runTheStandIn() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-netns-run needs GNU stat and /usr/bin/bash");
        assumeTrue(Files.isDirectory(Path.of(JAVA_HOME)), "the container runs the host JDK");
        var probe = BbFixtures.run(List.of("docker", "version", "--format", "{{.Server.Version}}"), new HashMap<>(Map.of("PATH", System.getenv("PATH"))), null, null);

        assumeTrue(probe.rc() == 0, "needs a reachable docker daemon: " + probe.stderr());
        var target = Files.createDirectories(Path.of("target").toAbsolutePath());

        home = Files.createTempDirectory(target, "netns-mounts-home");
        co = Files.createDirectories(home.resolve("work/co/t1"));
        Files.createDirectories(home.resolve("work/co/other"));
        Files.createDirectories(home.resolve("work/queue"));
        Files.createDirectories(home.resolve("tools"));
        Files.writeString(home.resolve("tools/marker"), "kept\n");
        // the real layout: ~/tools/maven is a symlink to a maven install that lives outside ~/tools
        BbFixtures.executable(home.resolve("apache-maven-0/bin/mvn"), STAND_IN);
        Files.createSymbolicLink(home.resolve("tools/maven"), home.resolve("apache-maven-0"));
        // the container runs as the host uid; a directory the test created is writable by it, but make the intent explicit
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("JAVA_HOME", JAVA_HOME);
        run = BbFixtures.run(List.of(SCRIPT.toString(), "ns76mounts" + System.nanoTime(), co.toString(), home.resolve("tools/maven/bin/mvn").toString(), "-B", "verify"),
                             env, co, null);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (home != null) {
            try (var files = Files.walk(home)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static String out() {
        return run.stdout() + run.stderr();
    }

    @Test
    void testCodeCannotWriteTheToolsDir() throws Exception {
        assertThat(out()).contains("DENIED tools-new").contains("DENIED tools-existing");
        assertThat(home.resolve("tools/new")).doesNotExist();
        assertThat(Files.readString(home.resolve("tools/marker"))).isEqualTo("kept\n");
    }

    @Test
    void testCodeCannotWriteTheMavenInstall() throws Exception {
        assertThat(out()).contains("DENIED maven-dir");
        assertThat(home.resolve("apache-maven-0/bin/new")).doesNotExist();
    }

    @Test
    void testCodeCannotWriteAnotherCheckout() throws Exception {
        assertThat(out()).contains("DENIED other-checkout");
        assertThat(home.resolve("work/co/other/new")).doesNotExist();
    }

    @Test
    void testCodeCannotWriteTheQueue() throws Exception {
        assertThat(out()).contains("DENIED queue");
        assertThat(home.resolve("work/queue/new")).doesNotExist();
    }

    @Test
    void theTicketsOwnCheckoutAndItsM2LocalStayWritable() throws Exception {
        assertThat(out()).contains("WROTE own-checkout").contains("WROTE m2-local");
        assertThat(co.resolve("new")).exists();
        assertThat(co.resolve(".m2-local/x")).exists();
    }

    @Test
    void homeIsWritableButThrowaway() throws Exception {
        assertThat(out()).contains("WROTE home");
        assertThat(home.resolve(".testcontainers.properties")).doesNotExist();
    }

    @Test
    void theCanaryAndAMavenStandInStillRun() throws Exception {
        assertThat(run.rc()).as(out()).isZero();
        assertThat(run.stdout()).contains("MAVEN RAN -B verify").contains("openjdk version");
    }
}
