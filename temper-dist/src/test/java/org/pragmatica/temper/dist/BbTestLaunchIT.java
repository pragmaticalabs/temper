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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                                       + "'\"${RECORD_EXTRA:+:$RECORD_EXTRA}\" > fx/target/bb-cp.txt; exit 0;;\n"
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
        for (var pid : stalled) {   // F2-bound rows: a stand-in a regressed bound left running (the probe) dies with the row
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private final List<Long> stalled = new ArrayList<>();

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

    // ---- RESOLVED JUNIT CHECK end to end (CTO ruling on vbt5/vbt6, temper#72). The junit version the build ACTUALLY resolved, read after the
    // run from the ticket's own test classpath, is the allowed one; a refusal (rc 15) replaces the verdict on every surface.

    private static final String OLD_API = "work/temper-m2-seed/org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar";

    private BbFixtures.Outcome bbResult(String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("python3", BbFixtures.OPS_BB.resolve("bb-result").toString()));

        cmd.addAll(List.of(args));

        return BbFixtures.run(cmd, env, null, null);
    }

    private void assertRefusedEverywhere(Done run) throws Exception {
        var why = "resolved junit-jupiter-api 5.11.4 (launcher wants 5.13.4)";

        assertThat(run.rc()).as("the ticket's rc: " + run.doneText()).isEqualTo(15);
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).as(".bb/rc holds the refusal code, never java's 0").isEqualTo("15");
        assertThat(run.doneText()).contains("\nrefused=junit version: " + why, "\nverdict_source=guard\n").doesNotContain("verdict_source=java");
        assertThat(Files.readString(co.resolve(".bb/refused"))).contains(why);
        var row = Files.readAllLines(dir.resolve("ticket-log.tsv")).stream().filter(l -> l.contains("\t" + run.id() + "\t")).toList();

        assertThat(row).as("one ledger row for the ticket").hasSize(1);
        assertThat(row.get(0).split("\t")[12]).as("the ledger's state column").isEqualTo("refused");
        var text = bbResult(run.id()).stdout();

        assertThat(text.lines().findFirst().orElseThrow()).contains("rc=15 verdict=REFUSED");
        assertThat(text).contains("refused: junit version: " + why, "tests: refused (not a verdict: ").doesNotContain(" run, ", "reports: ");
        assertThat(bbResult(run.id(), "--json").stdout()).contains("\"verdict\": \"refused\"", "\"status\": \"refused\"", "\"failing\": []")
                                                       .doesNotContain("\"counted\"");
        assertThat(co.resolve("fx/target/bb-test-reports").resolve(run.id())).as("the passing reports are not left where a count reads them")
                                                                            .doesNotExist();
    }

    @Test
    void aRunWhoseTestsPassed_onAnotherResolvedJupiter_isRefusedOnEverySurface() throws Exception {
        var old = Path.of(System.getProperty("user.home")).resolve(OLD_API);

        assumeTrue(Files.exists(old), "the seeded junit-jupiter-api 5.11.4 is on build hosts only");
        var extra = Map.of("RECORD_EXTRA", old.toString(), "BB_TEST_TICKET_LOG", dir.resolve("ticket-log.tsv").toString());

        assertThat(build(extra).rc()).as(log()).isZero();
        var run = bbTest(extra, "fx.CalcTest#passes");

        assertThat(Files.readString(co.resolve("fx/target/bb-test-reports").resolve(run.id() + ".refused").resolve("console.txt")))
                  .as("java ran and its tests passed; that outcome is kept aside, not as the verdict").contains("java exit 0");
        assertRefusedEverywhere(run);
    }

    // ---- vbt7 F2: a FIFO or device on the classpath would block the REAL java before the resolved check; it is refused first (rc 15) ----

    private Path special(String entry) throws Exception {
        if (entry.startsWith("/")) {
            return Path.of(entry);
        }
        var path = Files.createDirectories(dir.resolve("special")).resolve(entry);

        assertThat(BbFixtures.run(List.of("mkfifo", path.toString()), Map.of(), null, null).rc()).isZero();

        return path;
    }

    /// Probe: drop bb-test's pre-run entry check -> red: real java blocks on either FIFO (the ticket never reaches done/, cut by the
    /// timeout, measured on cloudbb-7) and runs past /dev/zero unrefused.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fifo.jar", "fifo", "/dev/zero"})
    @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFifoOrDeviceOnTheClasspath_isRefusedWithinSeconds_neverHangsTheTicket(String entry) throws Exception {
        var path = special(entry);
        var extra = Map.of("RECORD_EXTRA", path.toString(), "BB_TEST_TICKET_LOG", dir.resolve("ticket-log.tsv").toString());

        assertThat(build(extra).rc()).as(log()).isZero();
        var started = System.nanoTime();
        var run = bbTest(extra, "fx.CalcTest#passes");

        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("refused within seconds").isLessThan(30);
        assertThat(run.rc()).as(run.doneText()).isEqualTo(15);
        assertThat(run.doneText()).contains("\nrefused=unreadable classpath entry: " + path + " is a ", "\nverdict_source=guard\n")
                                  .doesNotContain("verdict_source=java");
        assertThat(bbResult(run.id()).stdout().lines().findFirst().orElseThrow()).contains("rc=15 verdict=REFUSED");
    }

    /// vbt7 F5: bb-build clears .bb/refused at the start of every run, so a refusal never outlives the next run on the checkout.
    /// Probe m4: drop "$B/refused" from bb-build's __run rm -> .bb/refused survives the green re-run, red.
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRefusedTicket_thenAGreenRerunOnTheSameCheckout_leavesNoRefusal() throws Exception {
        var extra = Map.of("RECORD_EXTRA", special("fifo.jar").toString());

        assertThat(build(extra).rc()).as(log()).isZero();
        var refused = bbTest(extra, "fx.CalcTest#passes");

        assertThat(refused.rc()).as(refused.doneText()).isEqualTo(15);
        assertThat(co.resolve(".bb/refused")).exists();
        green();
        assertThat(co.resolve(".bb/refused")).as("the green rebuild cleared the refusal").doesNotExist();
        var rerun = bbTest(Map.of(), "fx.CalcTest#passes");

        assertThat(rerun.rc()).as(rerun.doneText()).isZero();
        assertThat(co.resolve(".bb/refused")).doesNotExist();
        assertThat(bbResult(rerun.id()).stdout()).doesNotContain("REFUSED", "refused:");
    }

    // ---- F2-bound (CTO ruling via bto16): a stall the entry check cannot see is cut by a fail-closed timeout. The stand-ins ignore SIGTERM, so
    // only the KILL of the whole process group `timeout` leads ends them (and their child); the expiry is a refusal on every surface.

    private void assertTimeoutRefusal(Done run, String why, long seconds, List<Path> pidFiles) throws Exception {
        assertThat(seconds).as("cut at the bound (3 s) + the 10 s KILL grace, not by the 600 s stall").isLessThan(45);
        assertThat(run.rc()).as(run.doneText()).isEqualTo(15);
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).isEqualTo("15");
        assertThat(run.doneText()).contains("\nrefused=timeout: " + why, "\nverdict_source=guard\n").doesNotContain("verdict_source=java");
        assertThat(Files.readString(co.resolve(".bb/refused"))).contains("timeout: " + why);
        var row = Files.readAllLines(dir.resolve("ticket-log.tsv")).stream().filter(l -> l.contains("\t" + run.id() + "\t")).toList();

        assertThat(row).hasSize(1);
        assertThat(row.get(0).split("\t")[12]).as("the ledger's state column").isEqualTo("refused");
        var text = bbResult(run.id()).stdout();

        assertThat(text.lines().findFirst().orElseThrow()).contains("rc=15 verdict=REFUSED");
        assertThat(text).contains("refused: timeout: " + why);
        assertThat(bbResult(run.id(), "--json").stdout()).contains("\"verdict\": \"refused\"", "\"status\": \"refused\"");
        for (var file : pidFiles) {
            assertThat(file).as("the stand-in started").exists();
            var pid = Long.parseLong(Files.readString(file).strip());

            stalled.add(pid);
            for (var i = 0; i < 50 && ProcessHandle.of(pid).isPresent(); i++) {
                Thread.sleep(100);
            }
            assertThat(ProcessHandle.of(pid)).as("no process of the stalled group remains: " + file.getFileName()).isEmpty();
        }
    }

    /// Probe: drop `timeout -k 10 "$s"` from bb-test's bounded() -> red: the stand-in java sleeps 600 s, the row's 120 s timeout fails it.
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aJavaRunPastTheBound_isRefusedNamingTheTimeout_andItsWholeProcessGroupIsKilled() throws Exception {
        var pid = dir.resolve("java.pid");
        var java = BbFixtures.executable(dir.resolve("stub/stalled-java"),
                                         "#!/usr/bin/env bash\ntrap '' TERM\nsleep 600 &\necho $! > \"" + pid + ".child\"\necho $$ > \"" + pid
                                        + "\"\nwait\n");
        var extra = Map.of("BB_TEST_TICKET_LOG", dir.resolve("ticket-log.tsv").toString());

        assertThat(build(extra).rc()).as(log()).isZero();
        var started = System.nanoTime();
        var run = bbTest(Map.of("BB_TEST_TICKET_LOG", dir.resolve("ticket-log.tsv").toString(), "BB_TEST_JAVA", java.toString(),
                                "BB_JAVA_TIMEOUT", "3"),
                         "fx.CalcTest#passes");

        assertTimeoutRefusal(run, "java run 1/1 did not finish within BB_JAVA_TIMEOUT=3s; its process group was killed",
                             (System.nanoTime() - started) / 1_000_000_000L, List.of(pid, Path.of(pid + ".child")));
        assertThat(Files.readString(co.resolve("fx/target/bb-test-reports").resolve(run.id() + ".refused").resolve("console.txt")))
                  .as("java's own exit is recorded, never read as the verdict").contains("java exit 137");
    }

    /// The two classpath reads are bounded the same way (a python3 on PATH that stalls on one read's code, passing every other call through).
    /// Probe: as above -> red (both rows).
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"classpath entry check", "resolved junit read"})
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aClasspathReadPastTheBound_isRefusedNamingTheTimeout_andKilled(String read) throws Exception {
        var real = Stream.of(System.getenv("PATH").split(":")).map(d -> Path.of(d, "python3")).filter(Files::isExecutable).findFirst().orElseThrow();
        var pid = dir.resolve("read.pid");
        var bin = Files.createDirectories(dir.resolve("stall-bin"));

        BbFixtures.executable(bin.resolve("python3"),
                              "#!/usr/bin/env bash\nif [ \"$1 $2\" = \"-I -c\" ] && [ -n \"${HANG_READ:-}\" ] && [ \"${3%%$'\\n'*}\" = \"$HANG_READ\" ]; then\n"
                             + "  echo $$ > \"" + pid + "\"; trap '' TERM; exec sleep 600\nfi\nexec " + real + " \"$@\"\n");
        var log = dir.resolve("ticket-log.tsv").toString();

        assertThat(build(Map.of("BB_TEST_TICKET_LOG", log)).rc()).as(log()).isZero();
        var started = System.nanoTime();
        var run = bbTest(Map.of("BB_TEST_TICKET_LOG", log, "PATH", bin + ":" + System.getenv("PATH"), "HANG_READ", "# bb-test " + read,
                                "BB_CP_READ_TIMEOUT", "3"),
                         "fx.CalcTest#passes");

        assertTimeoutRefusal(run, "the " + read + " did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed",
                             (System.nanoTime() - started) / 1_000_000_000L, List.of(pid));
    }

    /// vbt8 L1: the stale-jar check (`bb-cp-record check`) is bounded by BB_CP_READ_TIMEOUT at BOTH calls: at enqueue (rc 15 on stderr,
    /// nothing queued) and at launch (a refused ticket). The stand-in is the real (python) bb-cp-record with a prefix that hangs a `check`, ignoring TERM,
    /// from the HANG_CHECK_AFTER-th call on. Probe: drop the bounded() wrap at the enqueue call (resp. at
    /// the launch call) -> red: the stand-in sleeps 600 s, the row's 120 s thread timeout fails it.
    private void hangingCheck(Path pid) throws Exception {
        var real = tools.resolve("bb-cp-record");
        var text = Files.readString(real);
        var nl = text.indexOf('\n');

        BbFixtures.executable(real,
                              text.substring(0, nl + 1)
                             + "import os, signal, sys\n"
                             + "if len(sys.argv) > 1 and sys.argv[1] == 'check' and os.environ.get('HANG_CHECK'):\n"
                             + "    c = os.environ['HANG_CHECK'] + '.n'\n"
                             + "    n = int(open(c).read()) if os.path.exists(c) else 0\n"
                             + "    open(c, 'w').write(str(n + 1))\n"
                             + "    if n >= int(os.environ.get('HANG_CHECK_AFTER', '0')):\n"
                             + "        open('" + pid + "', 'w').write(str(os.getpid()))\n"
                             + "        signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                             + "        os.execvp('sleep', ['sleep', '600'])\n"
                             + text.substring(nl + 1));
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStaleJarCheckPastTheBound_atEnqueue_isRefusedNamingTheTimeout_nothingQueued_andKilled() throws Exception {
        var pid = dir.resolve("check.pid");

        green();
        hangingCheck(pid);
        var all = new HashMap<>(env);

        all.putAll(Map.of("HANG_CHECK", dir.resolve("hang").toString(), "BB_CP_READ_TIMEOUT", "3"));
        var started = System.nanoTime();
        var run = BbFixtures.run(List.of(tools.resolve("bb-test").toString(), co.toString(), "fx", "fx.CalcTest#passes"), all, null, null);

        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("cut at the bound + KILL grace, not the 600 s stall").isLessThan(45);
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(15);
        assertThat(run.stderr()).contains("REFUSED (rc 15): timeout: the stale-jar check did not finish within BB_CP_READ_TIMEOUT=3s");
        assertThat(run.stdout()).doesNotContain("queued ticket=");
        var killed = Long.parseLong(Files.readString(pid).strip());

        stalled.add(killed);
        for (var i = 0; i < 50 && ProcessHandle.of(killed).isPresent(); i++) {
            Thread.sleep(100);
        }
        assertThat(ProcessHandle.of(killed)).as("the stalled check was killed").isEmpty();
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aStaleJarCheckPastTheBound_atLaunch_isRefusedNamingTheTimeout_andKilled() throws Exception {
        var pid = dir.resolve("check.pid");
        var log = dir.resolve("ticket-log.tsv").toString();

        assertThat(build(Map.of("BB_TEST_TICKET_LOG", log)).rc()).as(log()).isZero();
        hangingCheck(pid);
        var started = System.nanoTime();
        var run = bbTest(Map.of("BB_TEST_TICKET_LOG", log, "HANG_CHECK", dir.resolve("hang").toString(), "HANG_CHECK_AFTER", "1",
                                "BB_CP_READ_TIMEOUT", "3"),
                         "fx.CalcTest#passes");

        assertTimeoutRefusal(run, "the stale-jar check did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed",
                             (System.nanoTime() - started) / 1_000_000_000L, List.of(pid));
    }

    /// vbt9: the three remaining calls are bounded too, each with a planted FIFO (a read that blocks forever) and a 3 s bound.
    /// Probe (per row): replace that call's bound by the bare command -> red: the read blocks on the FIFO, the row's 120 s thread timeout fails it.
    private void passingCheck() throws Exception {   // the stale-jar check passes, so the launch reaches the call under test; CHECK_FIFO=<path>: each check (re)plants a FIFO there
        var real = tools.resolve("bb-cp-record");
        var text = Files.readString(real);
        var nl = text.indexOf('\n');

        BbFixtures.executable(real,
                              text.substring(0, nl + 1)
                             + "import os, sys\nif len(sys.argv) > 1 and sys.argv[1] == 'check' and os.environ.get('CHECK_FIFO'):\n"
                             + "    f = os.environ['CHECK_FIFO']\n    os.path.lexists(f) and os.remove(f)\n    os.mkfifo(f)\n"
                             + "if len(sys.argv) > 1 and sys.argv[1] == 'check' and os.environ.get('CHECK_OK'):\n    sys.exit(0)\n"
                             + text.substring(nl + 1));
    }

    private void mkfifo(Path path) throws Exception {
        Files.deleteIfExists(path);
        assertThat(BbFixtures.run(List.of("mkfifo", path.toString()), Map.of(), null, null).rc()).isZero();
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aClasspathRecordThatBlocksOnRead_isRefusedNamingTheTimeout_atLaunch() throws Exception {
        var log = dir.resolve("ticket-log.tsv").toString();

        assertThat(build(Map.of("BB_TEST_TICKET_LOG", log)).rc()).as(log()).isZero();
        passingCheck();
        mkfifo(co.resolve(".bb/cp/fx.cp"));
        var started = System.nanoTime();
        var run = bbTest(Map.of("BB_TEST_TICKET_LOG", log, "CHECK_OK", "1", "BB_CP_READ_TIMEOUT", "3"), "fx.CalcTest#passes");

        assertTimeoutRefusal(run, "the classpath record read did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed",
                             (System.nanoTime() - started) / 1_000_000_000L, List.of());
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aTestCountThatBlocksOnRead_isRefusedNamingTheTimeout_neverAPass() throws Exception {
        var log = dir.resolve("ticket-log.tsv").toString();
        var java = BbFixtures.executable(dir.resolve("stub/fifo-java"),
                                         "#!/usr/bin/env bash\nwhile [ $# -gt 0 ]; do\n  [ \"$1\" != --reports-dir ] || mkfifo \"$2/TEST-fifo.xml\"\n  shift\ndone\nexit 0\n");

        assertThat(build(Map.of("BB_TEST_TICKET_LOG", log)).rc()).as(log()).isZero();
        var started = System.nanoTime();
        var run = bbTest(Map.of("BB_TEST_TICKET_LOG", log, "BB_TEST_JAVA", java.toString(), "BB_CP_READ_TIMEOUT", "3"), "fx.CalcTest#passes");

        assertTimeoutRefusal(run, "the testcount read of run 1/1 did not finish within BB_CP_READ_TIMEOUT=3s; its process group was killed",
                             (System.nanoTime() - started) / 1_000_000_000L, List.of());
        assertThat(Files.readString(co.resolve("fx/target/bb-test-reports").resolve(run.id() + ".refused").resolve("console.txt")))
                  .as("the count line says it timed out, java's exit 0 is not a verdict").contains("java exit 0", "count timed out after 3s");
        assertThat(run.doneText()).doesNotContain("verdict_source=java");
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPortGroupClassifierThatBlocksOnRead_isRefusedNamingTheTimeout_atEnqueue_nothingQueued() throws Exception {
        green();
        mkfifo(tools.resolve("bb-portgroup.py"));
        var all = new HashMap<>(env);

        all.remove("BB_PORT_GROUP");
        all.put("BB_CP_READ_TIMEOUT", "3");
        var started = System.nanoTime();
        var run = BbFixtures.run(List.of(tools.resolve("bb-test").toString(), co.toString(), "fx", "fx.CalcTest#passes"), all, null, null);

        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("cut at the bound + KILL grace, not blocked forever").isLessThan(45);
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(15);
        assertThat(run.stderr()).contains("REFUSED (rc 15): timeout: the port-group classifier did not finish within BB_CP_READ_TIMEOUT=3s");
        assertThat(run.stdout()).doesNotContain("queued ticket=");
    }

    /// vbt9 READ INVARIANT: the refusal's own .bb/refused write is bounded too (10 s fixed). A FIFO planted as .bb/refused (an open that
    /// blocks forever) must not hang a refusal: the ticket's refused= (written first) and rc 15 land, the FIFO is left unwritten.
    /// Probe: replace that bounded write by the bare printf redirect -> red: the refusal blocks on the FIFO, the 120 s thread timeout fails it.
    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aRefusedFileThatBlocksOnWrite_doesNotHangTheRefusal() throws Exception {
        var log = dir.resolve("ticket-log.tsv").toString();

        assertThat(build(Map.of("BB_TEST_TICKET_LOG", log)).rc()).as(log()).isZero();
        passingCheck();
        mkfifo(co.resolve(".bb/cp/fx.cp"));
        var started = System.nanoTime();   // planted by the launch-time check: bb-build's enqueue clears .bb/refused, so a FIFO planted before it never reaches the write
        var run = bbTest(Map.of("BB_TEST_TICKET_LOG", log, "CHECK_OK", "1", "CHECK_FIFO", co.resolve(".bb/refused").toString(), "BB_CP_READ_TIMEOUT", "3"),
                         "fx.CalcTest#passes");

        assertThat((System.nanoTime() - started) / 1_000_000_000L).as("cut at the read bound + the write bound, not blocked forever").isLessThan(60);
        assertThat(run.rc()).as(run.doneText()).isEqualTo(15);
        assertThat(Files.readString(co.resolve(".bb/rc")).strip()).isEqualTo("15");
        assertThat(run.doneText()).contains("\nrefused=timeout: the classpath record read did not finish within BB_CP_READ_TIMEOUT=3s", "\nverdict_source=guard\n");
        assertThat(BbFixtures.run(List.of("test", "-p", co.resolve(".bb/refused").toString()), Map.of(), null, null).rc())
                  .as("the FIFO the launch-time check planted is still there: the write met it and was cut").isZero();
    }

    /// A bound that is not a positive integer is refused at enqueue (rc 2), never read as "no bound" (timeout 0 would disable it).
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"BB_JAVA_TIMEOUT=0", "BB_JAVA_TIMEOUT=1h", "BB_CP_READ_TIMEOUT=0", "BB_CP_READ_TIMEOUT="})
    void aBoundThatIsNotAPositiveInteger_isRefusedAtEnqueue(String setting) throws Exception {
        var all = new HashMap<>(env);
        var eq = setting.indexOf('=');

        all.put(setting.substring(0, eq), setting.substring(eq + 1));
        var run = BbFixtures.run(List.of(tools.resolve("bb-test").toString(), co.toString(), "fx", "fx.CalcTest#passes"), all, null, null);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(2);
        assertThat(run.stderr()).contains("must be positive integers");
        assertThat(run.stdout()).doesNotContain("queued ticket=");
    }

    // ---- the vbt5/vbt6 bypasses, built by REAL maven offline from this tree's .m2-local (a copy of the host seed ~/work/temper-m2-seed).
    // Artifacts each fixture resolves: org.junit.jupiter:junit-jupiter-api 5.11.4 (+ junit-platform-commons 1.11.4, opentest4j 1.3.0,
    // apiguardian-api 1.1.2, org.junit:junit-bom 5.11.4 pom) for a bypass, the same at 5.13.4 / 1.13.4 for the matching build;
    // maven-compiler-plugin 3.15.0, maven-resources-plugin 3.3.1, maven-dependency-plugin (the seed's) and maven 3.9 at ~/tools/maven.

    private static final String PLUGINS = "<build><pluginManagement><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>"
                                           + "<version>3.15.0</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId>"
                                           + "<version>3.3.1</version></plugin></plugins></pluginManagement></build>";

    private static final String TO_ROOT = "<parent><groupId>g</groupId><artifactId>root</artifactId><version>1</version></parent>";

    private static String pom(String parent, String coordinates, String properties, String body) {
        return "<project><modelVersion>4.0.0</modelVersion>" + parent + coordinates + "<properties><maven.compiler.release>21</maven.compiler.release>"
               + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>" + properties + "</properties>" + body + PLUGINS + "</project>";
    }

    private static String api(String groupId, String version) {
        return "<dependencies><dependency><groupId>" + groupId + "</groupId><artifactId>junit-jupiter-api</artifactId>"
               + (version == null ? "" : "<version>" + version + "</version>") + "<scope>test</scope></dependency></dependencies>";
    }

    private static String bom(String version) {
        return "<dependencyManagement><dependencies><dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>" + version
               + "</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>";
    }

    private static final String FX = pom(TO_ROOT, "<artifactId>fx</artifactId>", "", api("org.junit.jupiter", "${junit.version}"));

    /// Real maven builds `fx` (pom `fxPom`, plus `files` relative to the checkout) with `args` before `test-compile`; bb-test then runs
    /// fx.CalcTest#passes, which passes whatever junit-jupiter-api it was compiled against.
    private Done realBuildThenPassingTest(String fxPom, Map<String, String> files, String... args) throws Exception {
        var mvn = Path.of(System.getProperty("user.home")).resolve("tools/maven/bin/mvn");
        var repo = Path.of("").toAbsolutePath().getParent().resolve(".m2-local");

        assumeTrue(Files.isExecutable(mvn) && Files.exists(repo.resolve("org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar")),
                   "real maven and the seeded tree repo are on build hosts only");
        Files.writeString(co.resolve("pom.xml"),
                          pom("", "<groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>fx</module></modules>",
                              "<junit.version>5.13.4</junit.version>", ""));
        Files.writeString(co.resolve("fx/pom.xml"), fxPom);
        Files.writeString(Files.createDirectories(co.resolve(".mvn")).resolve("maven.config"), "-Dmaven.repo.local=" + repo + "\n");
        for (var f : files.entrySet()) {
            Files.writeString(Files.createDirectories(co.resolve(f.getKey()).getParent()).resolve(co.resolve(f.getKey()).getFileName()), f.getValue());
        }
        for (var step : List.of(List.of("add", "-A"), List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "real poms"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }
        var real = Map.of("BB_TEST_MVN", mvn.toString(), "JAVA_HOME", System.getProperty("java.home"));
        var cmd = new ArrayList<>(List.of(tools.resolve("bb-build").toString(), co.toString(), "-B"));

        cmd.addAll(List.of(args));
        cmd.add("test-compile");
        var built = enqueue(real, cmd.toArray(String[]::new));

        assertThat(built.rc()).as(log()).isZero();
        assertThat(log()).doesNotContain("bb-cp-record: record failed");

        return bbTest(real, "fx.CalcTest#passes");
    }

    private static void assertOlderRefused(Done run) {
        assertThat(run.rc()).as(run.doneText()).isEqualTo(15);
        assertThat(run.doneText()).contains("\nrefused=junit version: resolved ", "junit-jupiter-api 5.11.4 (launcher wants 5.13.4)",
                                            "\nverdict_source=guard\n");
    }

    @Test
    void real_theMatchingResolvedJunit_passes_byJavasExitStatus() throws Exception {
        var run = realBuildThenPassingTest(FX, Map.of(), "-pl", "fx");

        assertThat(run.rc()).as(run.doneText() + log()).isZero();
        assertThat(run.doneText()).contains("verdict_source=java").doesNotContain("refused=");
        assertThat(log()).contains("bb-test: resolved junit on the test classpath: ", "junit-jupiter-api 5.13.4", "junit-platform-commons 1.13.4");
    }

    @Test
    void real_aPlainDefineOnTheBuildLine_isRefused() throws Exception {
        assertOlderRefused(realBuildThenPassingTest(FX, Map.of(), "-pl", "fx", "-Djunit.version=5.11.4"));
    }

    @Test
    void real_aLongDefineWithEquals_isRefused() throws Exception {
        assertOlderRefused(realBuildThenPassingTest(FX, Map.of(), "-pl", "fx", "--define=junit.version=5.11.4"));
    }

    @Test
    void real_aLongDefineWithASpace_isRefused() throws Exception {
        assertOlderRefused(realBuildThenPassingTest(FX, Map.of(), "-pl", "fx", "--define", "junit.version=5.11.4"));
    }

    @Test
    void real_anOffPathParent_isRefused() throws Exception {
        var fx = pom("<parent><groupId>g</groupId><artifactId>outside</artifactId><version>1</version><relativePath>../../outside/pom.xml</relativePath></parent>",
                     "<artifactId>fx</artifactId>", "", api("org.junit.jupiter", "${junit.version}"));
        Files.createDirectories(dir.resolve("work/outside"));
        Files.writeString(dir.resolve("work/outside/pom.xml"),
                          pom("", "<groupId>g</groupId><artifactId>outside</artifactId><version>1</version><packaging>pom</packaging>",
                              "<junit.version>5.11.4</junit.version>", ""));

        assertOlderRefused(realBuildThenPassingTest(fx, Map.of(), "-pl", "fx"));
    }

    @Test
    void real_aJunitBomByLiteral_isRefused() throws Exception {
        assertOlderRefused(realBuildThenPassingTest(pom(TO_ROOT, "<artifactId>fx</artifactId>", "", bom("5.11.4") + api("org.junit.jupiter", null)),
                                                    Map.of(), "-pl", "fx"));
    }

    @Test
    void real_aJunitBomByProperty_isRefused() throws Exception {
        assertOlderRefused(realBuildThenPassingTest(pom(TO_ROOT, "<artifactId>fx</artifactId>", "<bom.v>5.11.4</bom.v>", bom("${bom.v}") + api("org.junit.jupiter", null)),
                                                    Map.of(), "-pl", "fx"));
    }

    @Test
    void real_aPropertyGroupId_isRefused() throws Exception {
        assertOlderRefused(realBuildThenPassingTest(pom(TO_ROOT, "<artifactId>fx</artifactId>", "<jg>org.junit.jupiter</jg>", api("${jg}", "5.11.4")),
                                                    Map.of(), "-pl", "fx"));
    }

    @Test
    void real_aSettingsFileGivenWithS_isRefused() throws Exception {
        var settings = "<settings><profiles><profile><id>old</id><properties><junit.version>5.11.4</junit.version></properties></profile></profiles>"
                       + "<activeProfiles><activeProfile>old</activeProfile></activeProfiles></settings>";

        assertOlderRefused(realBuildThenPassingTest(FX, Map.of("alt-settings.xml", settings), "-pl", "fx", "-s", "alt-settings.xml"));
    }

    @Test
    void real_anotherPomGivenWithF_isRefused() throws Exception {
        var alt = pom(TO_ROOT, "<artifactId>fx</artifactId>", "", api("org.junit.jupiter", "5.11.4"));

        assertOlderRefused(realBuildThenPassingTest(FX, Map.of("fx/pom-alt.xml", alt), "-f", "fx/pom-alt.xml"));
    }
}
