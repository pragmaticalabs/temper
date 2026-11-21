package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// A bb-test ticket of port group 1 run through the REAL queue, waiter, bb-netns-run and docker (issue #76, CTO ruling 2026-11-21):
/// the container never sees the queue, so bb-test's __launch writes its fields to `.bb/fields.<id>` and the waiter copies the
/// grammar's lines into the ticket (bb-build: LAUNCH FIELDS). Gated like BbNetnsMountsIT (Linux, the host JDK, a reachable docker
/// daemon) and on the seeded console launcher. The fake HOME lives under `target/`: docker binds HOST paths (BbNetnsMountsIT).
///
/// Probes (each red, then reverted): drop the waiter's copy -> [#aPortGroupBbTest_runsInTheContainer_andItsVerdictReachesTheTicket];
/// re-add a rw queue mount -> this row stays green and BbNetnsMountsIT#testCodeCannotWriteTheQueue goes red.
class BbLaunchFieldsNetnsIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");
    private static final String JAVA_HOME = "/usr/lib/jvm/java-25-openjdk-amd64";
    private static final String SLOW_TEST = "package fx;\nimport org.junit.jupiter.api.Test;\n"
                                           + "public class SlowTest { @Test void sleeps() throws Exception { Thread.sleep(300_000); } }\n";

    private Path home;
    private Path co;
    private Path tools;
    private Map<String, String> env;
    private final List<String> tickets = new ArrayList<>();

    @BeforeEach
    void fixture() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-netns-run needs GNU stat and /usr/bin/bash");
        assumeTrue(Files.isDirectory(Path.of(JAVA_HOME)), "the container runs the host JDK");
        var probe = BbFixtures.run(List.of("docker", "version", "--format", "{{.Server.Version}}"), new HashMap<>(Map.of("PATH", System.getenv("PATH"))), null, null);

        assumeTrue(probe.rc() == 0, "needs a reachable docker daemon: " + probe.stderr());
        var seeded = Path.of(System.getProperty("user.home")).resolve(BbTestLaunchIT.SEED);

        assumeTrue(Files.exists(seeded), "the seeded console launcher is on build hosts only");
        home = Files.createTempDirectory(Files.createDirectories(Path.of("target").toAbsolutePath()), "launch-fields-home");
        // the launch in the container finds the launcher at its default path under $HOME (no host env enters the container)
        Files.copy(seeded, Files.createDirectories(home.resolve(BbTestLaunchIT.SEED).getParent()).resolve(seeded.getFileName()));
        tools = Files.createDirectories(home.resolve("tools/bb"));
        for (var f : List.of("bb-build", "bb-test", "bb-cp-record", "bb-testcount", "bb-netns-run")) {
            BbFixtures.executable(tools.resolve(f), Files.readString(BbFixtures.OPS_BB.resolve(f)));
        }
        for (var f : List.of("bb-queue-lib.sh", "bb-portgroup.py")) {
            Files.copy(BbFixtures.OPS_BB.resolve(f), tools.resolve(f));
        }
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(home.resolve("work/queue").resolve(d));
        }

        co = home.resolve("work/co/lf-co");
        Files.createDirectories(co.resolve("fx/src/main/java/fx"));
        Files.createDirectories(co.resolve("fx/src/test/java/fx"));
        Files.writeString(co.resolve("pom.xml"), BbTestLaunchIT.ROOT_POM);
        Files.writeString(co.resolve("fx/pom.xml"),
                          "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>fx</artifactId><version>1</version></project>");
        Files.writeString(co.resolve("fx/src/main/java/fx/Calc.java"), BbTestLaunchIT.CALC);
        Files.writeString(co.resolve("fx/src/test/java/fx/CalcTest.java"), BbTestLaunchIT.CALC_TEST);
        Files.writeString(co.resolve("fx/src/test/java/fx/SlowTest.java"), SLOW_TEST);
        Files.writeString(co.resolve(".gitignore"), "target/\n.bb/\n");
        for (var step : List.of(List.of("init", "-q"), List.of("add", "."), List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }

        var launcher = home.resolve(BbTestLaunchIT.SEED);
        var jdk = Path.of(System.getProperty("java.home"));
        var mvn = BbFixtures.executable(home.resolve("stub/mvn"),
                                        "#!/usr/bin/env bash\n"
                                       + "case \" $* \" in *dependency:build-classpath*) mkdir -p fx/target; echo '" + launcher + "' > fx/target/bb-cp.txt; exit 0;; esac\n"
                                       + "rm -rf fx/target/classes fx/target/test-classes; mkdir -p fx/target/classes fx/target/test-classes\n" + jdk
                                       + "/bin/javac -d fx/target/classes $(find fx/src/main -name '*.java') || exit 1\n" + jdk
                                       + "/bin/javac -cp fx/target/classes:" + launcher + " -d fx/target/test-classes $(find fx/src/test -name '*.java') || exit 1\n");

        env = new HashMap<>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", home.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "lf");
        env.put("BB_TEST_QUEUE_ROOT", home.resolve("work/queue").toString());
        env.put("BB_TEST_PRIORITIES", home.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", home.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
        var built = done(enqueue(Map.of(), tools.resolve("bb-build").toString(), co.toString(), "-B", "-pl", "fx", "test-compile"));

        assertThat(rc(built)).as("the green host build: " + built).isZero();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (var id : tickets) {
            BbFixtures.run(List.of("docker", "rm", "-f", "bb-" + id), new HashMap<>(Map.of("PATH", System.getenv("PATH"))), null, null);
            BbFixtures.kill(waitersOf(id));
        }
        if (home != null) {
            try (var files = Files.walk(home)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private List<Long> waitersOf(String id) {
        try {
            return BbFixtures.waiters(id);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String enqueue(Map<String, String> extra, String... command) throws Exception {
        var all = new HashMap<>(env);

        all.putAll(extra);
        var run = BbFixtures.run(List.of(command), all, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        assertThat(matcher.find()).as(run.stdout() + run.stderr()).isTrue();
        tickets.add(matcher.group(1));

        return matcher.group(1);
    }

    private String done(String id) throws Exception {
        var done = home.resolve("work/queue/done").resolve(id);

        for (var i = 0; i < 1800 && !Files.exists(done); i++) {
            Thread.sleep(100);
        }
        assertThat(done).as("ticket " + id + " reached done/").exists();

        return Files.readString(done);
    }

    private static String last(String ticket, String key) {
        return ticket.lines().filter(l -> l.startsWith(key + "=")).reduce((a, b) -> b).map(l -> l.substring(key.length() + 1)).orElse("");
    }

    private static int rc(String ticket) {
        return Integer.parseInt(last(ticket, "rc"));
    }

    private String log() throws IOException {
        return Files.readString(co.resolve(".bb/build.log"));
    }

    private String bbTest(String selector) throws Exception {
        return enqueue(Map.of("BB_PORT_GROUP", "1"), tools.resolve("bb-test").toString(), co.toString(), "fx", selector);
    }

    @Test
    void aPortGroupBbTest_runsInTheContainer_andItsVerdictReachesTheTicket() throws Exception {
        var ticket = done(bbTest("fx.CalcTest#passes"));

        assertThat(last(ticket, "iso")).as("a port-group ticket runs in bb-netns-run's container").isEqualTo("1");
        assertThat(rc(ticket)).as(ticket + log()).isZero();
        assertThat(last(ticket, "verdict_source")).isEqualTo("java");
        assertThat(ticket).doesNotContain("fields_rejected");
        assertThat(log()).doesNotContain("work/queue");
    }

    @Test
    void aContainerKilledMidRun_recordsAMissingVerdict_neverAPass() throws Exception {
        var id = bbTest("fx.SlowTest");
        var path = Map.of("PATH", System.getenv("PATH"));
        var java = false;

        for (var i = 0; i < 600 && !java; i++) {   // the launch has reached java: its console runs the selection inside the container
            Thread.sleep(100);
            java = BbFixtures.run(List.of("docker", "top", "bb-" + id), new HashMap<>(path), null, null).stdout().contains("SlowTest");
        }
        assertThat(java).as("bb-" + id + " reached java").isTrue();
        assertThat(BbFixtures.run(List.of("docker", "kill", "bb-" + id), new HashMap<>(path), null, null).rc()).isZero();
        var ticket = done(id);

        assertThat(rc(ticket)).as(ticket).isNotZero();
        assertThat(last(ticket, "verdict_source")).isEqualTo("missing");
        assertThat(ticket).doesNotContain("verdict_source=java");
    }
}
