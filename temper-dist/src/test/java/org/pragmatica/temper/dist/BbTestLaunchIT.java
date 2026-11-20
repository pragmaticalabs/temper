package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// A REAL bb-test launch (design §2): a fixture module with a passing, a failing and a flag-printing test is built by `bb-build` through the
/// real queue (the stand-in maven compiles it with this JVM's javac and writes its classpath), the build hook records the classpath, and `bb-test`
/// then runs the pinned JUnit Console Launcher from the host seed (no stand-in for the launcher or for the queue). Fails, never skips, when the
/// seeded launcher jar is missing: the seed is on every build host.
///
/// Probes (each red, reverted): launcher exit status not the ticket's rc -> [#aFailingSelection_isRedAndAPassingOneGreen_byTheJavaExitStatus];
/// dedup left on -> [#twoIdenticalRuns_areTwoTickets_neverDeduped]; reports into surefire-reports ->
/// [#reportsLandInTheTicketsOwnDir_neverInSurefireReports_andCountExactly]; hook changes rc ->
/// [#aGreenBuild_staysGreen_andRecordsTheClasspath], [#aRedBuild_staysRed_andTheHookIsSkipped]; requireDocker dropped or forced ->
/// [#requireDocker_isOnByDefault], [#requireDockerOptOut_dropsTheFlag_andIsRecordedInTheTicket]; hook unbounded ->
/// [#aHungCpRecord_isCutAtTheTimeout_theBuildStaysGreen_andNothingIsLeftRunning].
class BbTestLaunchIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");

    private static final String SEED = "work/temper-m2-seed/org/junit/platform/junit-platform-console-standalone/1.13.4/junit-platform-console-standalone-1.13.4.jar";

    private static final String ROOT_POM = "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><properties><junit.version>5.13.4</junit.version></properties></project>";

    private static final String CALC = "package fx;\npublic class Calc { public static int add(int a, int b) { return a + b; } }\n";

    private static final String CALC_TEST = """
        package fx;
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.assertEquals;
        public class CalcTest {
            @Test void passes() { assertEquals(3, Calc.add(1, 2)); }
            @Test void fails() { assertEquals(4, Calc.add(1, 2)); }
            @Test void flag() { System.out.println("REQUIRE_DOCKER=" + System.getProperty("aether.requireDocker")); }
        }
        """;

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();
    private Path co;
    private Path tools;
    private Path launcher;
    private Map<String, String> env;

    private record Done(String id, int rc, String doneText) {}

    @BeforeEach
    void fixture() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        launcher = Path.of(System.getProperty("user.home")).resolve(SEED);
        assumeTrue(Files.exists(launcher), "the seeded console launcher is on build hosts only (CI runners have no ~/work/temper-m2-seed)");
        tools = dir.resolve("tools");
        for (var f : List.of("bb-build", "bb-test", "bb-cp-record", "bb-testcount")) {
            BbFixtures.executable(tools.resolve(f),
                                  Files.readString(BbFixtures.OPS_BB.resolve(f)));
        }

        for (var f : List.of("bb-queue-lib.sh", "bb-portgroup.py")) {
            Files.copy(BbFixtures.OPS_BB.resolve(f), tools.resolve(f), StandardCopyOption.REPLACE_EXISTING);
        }

        co = dir.resolve("work").resolve("lt-co");
        Files.createDirectories(co.resolve("fx/src/main/java/fx"));
        Files.createDirectories(co.resolve("fx/src/test/java/fx"));
        Files.writeString(co.resolve("pom.xml"), ROOT_POM);
        Files.writeString(co.resolve("fx/pom.xml"),
                          "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>fx</artifactId><version>1</version></project>");
        Files.writeString(co.resolve("fx/src/main/java/fx/Calc.java"), CALC);
        Files.writeString(co.resolve("fx/src/test/java/fx/CalcTest.java"), CALC_TEST);
        Files.writeString(co.resolve(".gitignore"), "target/\n.bb/\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }

        var jdk = Path.of(System.getProperty("java.home"));
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"),
                                        "#!/usr/bin/env bash\n"
                                       + "case \" $* \" in *dependency:build-classpath*)\n"
                                       + "  [ -z \"$HANG_RECORD\" ] || { echo $$ > \"$HANG_RECORD\"; exec sleep 600; }\n"
                                       + "  mkdir -p fx/target; echo '" + launcher
                                       + "' > fx/target/bb-cp.txt; exit 0;;\n"
                                       + "esac\n"
                                       + "rm -rf fx/target/classes fx/target/test-classes; mkdir -p fx/target/classes fx/target/test-classes\n" + jdk
                                       + "/bin/javac -d fx/target/classes $(find fx/src/main -name '*.java') || exit 1\n" + jdk
                                       + "/bin/javac -cp fx/target/classes:" + launcher
                                       + " -d fx/target/test-classes $(find fx/src/test -name '*.java') || exit 1\n");

        env = new HashMap<>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME",
                Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "lt");
        env.put("BB_TEST_QUEUE_ROOT",
                dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_TEST_JAVA",
                jdk.resolve("bin/java").toString());
        env.put("BB_TEST_LAUNCHER_JAR", launcher.toString());
        env.put("BB_PORT_GROUP", "0");
    }

    @AfterEach
    void killWaiters() throws Exception {
        for (var id : tickets) {
            BbFixtures.kill(BbFixtures.waiters(id));
        }
    }

    private Done enqueue(Map<String, String> extra, String... command) throws Exception {
        var all = new HashMap<>(env);
        var cmd = new ArrayList<String>();

        all.putAll(extra);
        cmd.addAll(List.of(command));
        var run = BbFixtures.run(cmd, all, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        assertThat(matcher.find()).as(run.stdout() + run.stderr()).isTrue();
        var id = matcher.group(1);
        var done = dir.resolve("q/done").resolve(id);

        tickets.add(id);
        for (var i = 0; i < 1200 && !Files.exists(done); i++) {
            Thread.sleep(100);
        }

        assertThat(done).as("the ticket reached done/: " + run.stdout()).exists();
        var text = Files.readString(done);
        var rc = text.lines().filter(l -> l.startsWith("rc=")).reduce((a, b) -> b).orElseThrow();

        return new Done(id,
                        Integer.parseInt(rc.substring(3)),
                        text);
    }

    private Done build(Map<String, String> extra) throws Exception {
        return enqueue(extra,
                       tools.resolve("bb-build").toString(),
                       co.toString(),
                       "-B",
                       "-pl",
                       "fx",
                       "test-compile");
    }

    private Done bbTest(Map<String, String> extra, String... selectors) throws Exception {
        var cmd = new ArrayList<>(List.of(tools.resolve("bb-test").toString(),
                                          co.toString(),
                                          "fx"));

        cmd.addAll(List.of(selectors));

        return enqueue(extra, cmd.toArray(String[]::new));
    }

    private Done green() throws Exception {
        var built = build(Map.of());

        assertThat(built.rc()).as(log()).isZero();

        return built;
    }

    private String log() throws Exception {
        return Files.readString(co.resolve(".bb/build.log"));
    }

    private BbFixtures.Outcome count(Path reports) throws Exception {
        return BbFixtures.run(List.of(tools.resolve("bb-testcount").toString(),
                                      reports.toString()),
                              Map.of(),
                              null,
                              null);
    }

    @Test
    void aGreenBuild_staysGreen_andRecordsTheClasspath() throws Exception {
        green();
        assertThat(co.resolve(".bb/cp/manifest")).exists();
        assertThat(co.resolve(".bb/cp/fx.cp")).exists();
        assertThat(co.resolve("fx/target/bb-cp.txt")).doesNotExist();
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).isEqualTo("0");
    }

    @Test
    void aRedBuild_staysRed_andTheHookIsSkipped() throws Exception {
        Files.writeString(co.resolve("fx/src/main/java/fx/Calc.java"),
                          "package fx;\npublic class Calc { this does not compile }\n");
        var built = build(Map.of());

        assertThat(built.rc()).isNotZero();
        assertThat(co.resolve(".bb/cp")).doesNotExist();
        assertThat(log()).doesNotContain("bb-cp-record");
    }

    @Test
    void aFailingSelection_isRedAndAPassingOneGreen_byTheJavaExitStatus() throws Exception {
        green();
        assertThat(bbTest(Map.of(), "fx.CalcTest#fails").rc()).isNotZero();
        assertThat(bbTest(Map.of(), "fx.CalcTest#passes").rc()).isZero();
        assertThat(bbTest(Map.of(), "fx.NoSuchTest").rc()).as("a selection that matches nothing is red").isNotZero();
    }

    @Test
    void aLaunchThatReachesJava_writesVerdictSourceJava_soItsRcIsNeverReadAsAGuard() throws Exception {
        green();
        var run = bbTest(Map.of(), "fx.CalcTest#fails");

        assertThat(run.doneText()).as("vbt4 L2: java ran, its exit status is the rc")
                  .contains("\nverdict_source=java\n")
                  .doesNotContain("verdict_source=guard");
    }

    @Test
    void twoIdenticalRuns_areTwoTickets_neverDeduped() throws Exception {
        green();
        var first = bbTest(Map.of(), "fx.CalcTest#passes");
        var second = bbTest(Map.of(), "fx.CalcTest#passes");

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.rc()).isZero();
        assertThat(second.rc()).isZero();
        assertThat(dir.resolve("q/done").resolve(first.id())).exists();
        assertThat(dir.resolve("q/done").resolve(second.id())).exists();
        assertThat(co.resolve("fx/target/bb-test-reports").resolve(first.id())).isDirectory();
        assertThat(co.resolve("fx/target/bb-test-reports").resolve(second.id())).isDirectory();
    }

    @Test
    void reportsLandInTheTicketsOwnDir_neverInSurefireReports_andCountExactly() throws Exception {
        green();
        var run = bbTest(Map.of(), "fx.CalcTest");
        var reports = co.resolve("fx/target/bb-test-reports").resolve(run.id());

        assertThat(run.rc()).as("the failing test makes the class red").isEqualTo(1);
        assertThat(reports).isDirectory();
        try (Stream<Path> files = Files.list(reports)) {
            assertThat(files.map(p -> p.getFileName()
                                       .toString()).toList()).contains("TEST-junit-jupiter.xml");
        }

        assertThat(co.resolve("fx/target/surefire-reports")).doesNotExist();
        var counted = count(reports);

        assertThat(counted.rc()).as(counted.stderr()).isZero();
        assertThat(counted.stdout().trim()).startsWith("testcases=3 passed=2 failures=1 errors=0 skipped=0 files=")
                  .endsWith(" duplicates=0");
        assertThat(log()).contains("testcases=3 passed=2 failures=1");
    }

    @Test
    void requireDocker_isOnByDefault() throws Exception {
        green();
        var run = bbTest(Map.of(), "fx.CalcTest#flag");

        assertThat(run.rc()).as(log()).isZero();
        assertThat(log()).contains("REQUIRE_DOCKER=true");
        assertThat(run.doneText()).doesNotContain("requireDocker=0");
        assertThat(log()).contains("requireDocker=true;");
        assertThat(run.doneText().lines().toList()).contains("requireDockerEffective=true");
    }

    @Test
    void requireDockerOptOut_dropsTheFlag_andIsRecordedInTheTicket() throws Exception {
        green();
        var run = bbTest(Map.of("BB_REQUIRE_DOCKER", "0"), "fx.CalcTest#flag");

        assertThat(run.rc()).as(log()).isZero();
        assertThat(log()).contains("REQUIRE_DOCKER=null");
        assertThat(run.doneText().lines().toList()).contains("requireDocker=0", "requireDockerEffective=false");
        assertThat(log()).contains("requireDocker=false;").doesNotContain("requireDocker=true;");
    }

    @Test
    void aHungCpRecord_isCutAtTheTimeout_theBuildStaysGreen_andNothingIsLeftRunning() throws Exception {
        var pidFile = dir.resolve("hung.pid");
        var started = System.nanoTime();
        var built = build(Map.of("HANG_RECORD", pidFile.toString(), "BB_CP_RECORD_TIMEOUT", "2"));

        assertThat(built.rc()).as(log()).isZero();
        assertThat(System.nanoTime() - started).as("bounded by the timeout, not by the 600 s sleep")
                  .isLessThan(120_000_000_000L);
        assertThat(log()).contains("cp-record skipped: timeout");
        assertThat(co.resolve(".bb/cp")).doesNotExist();
        assertThat(co.resolve(".bb/cp.new")).doesNotExist();
        assertThat(pidFile).exists();
        var pid = Long.parseLong(Files.readString(pidFile).strip());

        for (var i = 0; i < 50 && ProcessHandle.of(pid).isPresent(); i++) {
            Thread.sleep(100);
        }

        assertThat(ProcessHandle.of(pid)).as("the hung maven of the record was killed with it").isEmpty();
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).isEqualTo("0");
    }
}
