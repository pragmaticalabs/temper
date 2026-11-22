package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// ZERO-TESTCASE REFUSAL (#86, CTO ruling 2026-11-22): a bb-build job whose maven ended 0 while a class selected by `-Dtest` / `-Dit.test`
/// ran 0 testcases is recorded as rc 19, in the ticket's `rc=` and in `.bb/rc`, with the per-class counts in build.log. Real `bb-build __run`
/// waiters on a pre-written ticket, with a stub maven that writes (or does not write) surefire/failsafe reports into the checkout.
///
/// bb-test launch tickets (BB_LAUNCH) go through the same counter on the ticket's own report dir.
///
/// Probe: drop the refusal (the `selected_testcases ... || rc=...` block in the job subshell) -> every row expecting 19 is red.
class BbZeroTestcaseIT {
    private static final String ME = "zt";
    private static final String TWO = "<testsuite><testcase classname='x' name='a'/><testcase classname='x' name='b'/></testsuite>";
    private static final String EMPTY = "<testsuite tests='0'/>";

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private Path co;
    private final List<Process> started = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs /proc, flock and GNU tools");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }
        tools = Files.createDirectories(dir.resolve("tools"));
        BbFixtures.executable(tools.resolve("bb-build"), Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        co = Files.createDirectories(dir.resolve("work").resolve(ME + "-co").resolve(".bb")).getParent();
    }

    @AfterEach
    void cleanUp() {
        for (var process : started) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private record Run(String rc, String fileRc, String log) {}

    /// Writes a report `TEST-<fqcn>.xml` with `body` into `<co>/<rel>` from the stub maven, i.e. after the run marker.
    private static String report(String rel, String fqcn, String body) {
        return "mkdir -p " + rel + " && printf '%s' \"" + body + "\" > " + rel + "/TEST-" + fqcn + ".xml";
    }

    private Run run(String mvnBody, String... args) throws Exception {
        return run(Map.of(), mvnBody, args);
    }

    /// A bb-test launch ticket (BB_LAUNCH, args `__launch m <repeat> <selector>...`): the stub writes its reports into the ticket's own
    /// `m/target/bb-test-reports/<ticket>/` (as the launcher's TEST-junit-jupiter.xml, classes by `classname`) and verdict_source=java.
    private Run launch(String reports, String... args) throws Exception {
        var all = new ArrayList<>(List.of("__launch", "m"));
        all.addAll(List.of(args));
        return run(Map.of("BB_LAUNCH", dir.resolve("stubmvn").toString()),
                   "D=m/target/bb-test-reports/$BB_TICKET; mkdir -p $D; " + reports + "; echo verdict_source=java > .bb/fields.$BB_TICKET",
                   all.toArray(String[]::new));
    }

    private static String cases(String... classes) {
        var body = new StringBuilder("<testsuite>");
        for (var c : classes) {
            body.append("<testcase classname='").append(c.replace("$", "\\$")).append("' name='t'/>");
        }
        return body.append("</testsuite>").toString();
    }

    private Run run(Map<String, String> extra, String mvnBody, String... args) throws Exception {
        BbFixtures.executable(dir.resolve("stubmvn"), "#!/bin/sh\n" + mvnBody + "\n");
        var id = System.nanoTime() + "-1";
        Files.writeString(queue.resolve("waiting").resolve(id),
                          "id=" + id + "\nowner=" + ME + "\nco=" + co + "\nsha=abc\nkey=\ncap=5\nlimit=600\ncreated="
                          + (System.currentTimeMillis() * 1_000_000L) + "\ngroup=0\nargs=" + String.join(" ", args) + " \n");
        var env = new HashMap<String, String>();
        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", ME);
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_LOADAVG", dir.resolve("loadavg").toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_MAX_REACTORS", "5");
        env.put("BB_TEST_MVN", dir.resolve("stubmvn").toString());
        env.putAll(extra);
        var command = new ArrayList<>(List.of("bash", tools.resolve("bb-build").toString(), "__run", id));
        command.addAll(List.of(args));
        var builder = BbFixtures.processClean(command, env);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        started.add(process);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("waiter exits").isTrue();
        var done = queue.resolve("done").resolve(id);
        assertThat(done).as("ticket finalized").exists();
        var rc = Files.readAllLines(done).stream().filter(l -> l.startsWith("rc=")).reduce((a, b) -> b).orElse("rc=").substring(3);
        return new Run(rc, Files.readString(co.resolve(".bb/rc")).trim(), Files.readString(co.resolve(".bb/build.log")));
    }

    private static void assertRefused(Run run, String counts, String empty) {
        assertThat(run.rc()).as(run.log()).isEqualTo("19");
        assertThat(run.fileRc()).isEqualTo("19");
        assertThat(run.log()).contains("bb-build: selected testcases: " + counts + "\n",
                                       "[ERROR] bb-build: refused rc 0 -> 19: selected class(es) ran 0 testcases: " + empty + "\n");
    }

    @Test
    void aSelectedClassWithTestcases_isRc0_andTheCountIsPrinted() throws Exception {
        var run = run(report("m/target/failsafe-reports", "org.x.BbFooIT", TWO), "-B", "install", "-Dit.test=BbFooIT", "-Dtest=none",
                      "-Dsurefire.failIfNoSpecifiedTests=false", "-Dfailsafe.failIfNoSpecifiedTests=false");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.fileRc()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: BbFooIT=2\n").doesNotContain("refused");
    }

    @Test
    void aSelectedClassWithNoReport_isRefusedWith19() throws Exception {
        assertRefused(run("exit 0", "-Dit.test=NoSuchClassIT", "-Dfailsafe.failIfNoSpecifiedTests=false"), "NoSuchClassIT=0", "NoSuchClassIT");
    }

    @Test
    void aSelectedClassWhoseReportIsOlderThanTheRunMarker_isRefusedWith19() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.FooTest", TWO) + " && touch -d @1000000000 m/target/surefire-reports/TEST-org.x.FooTest.xml",
                      "-Dtest=FooTest");

        assertRefused(run, "FooTest=0", "FooTest");
    }

    @Test
    void aSelectedClassWhoseReportHasNoTestcase_isRefusedWith19() throws Exception {
        assertRefused(run(report("m/target/surefire-reports", "org.x.FooTest", EMPTY), "-Dtest=FooTest"), "FooTest=0", "FooTest");
    }

    @Test
    void twoSelectors_oneEmpty_isRefusedNamingOnlyTheEmptyOne() throws Exception {
        var run = run(report("a/target/surefire-reports", "org.x.FooTest", TWO), "-Dtest=FooTest,BarTest#someMethod", "-Dit.test=!Excluded");

        assertRefused(run, "FooTest=2 BarTest#someMethod=0", "BarTest#someMethod");
    }

    @Test
    void aWildcardSelector_isOneClass_passingWithAnyTestcase() throws Exception {
        var run = run(report("a/b/target/failsafe-reports", "org.x.BbOneIT", TWO) + " && " + report("c/target/failsafe-reports", "org.y.BbTwoIT", TWO),
                      "-Dit.test=Bb*IT");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: Bb*IT=4\n");
    }

    @Test
    void aWildcardSelectorMatchingNothing_isRefused() throws Exception {
        assertRefused(run(report("a/target/failsafe-reports", "org.x.BbOneIT", TWO), "-Dit.test=Zz*IT"), "Zz*IT=0", "Zz*IT");
    }

    @Test
    void aPackageQualifiedSelector_matchesThePackagePath() throws Exception {
        var run = run(report("a/target/surefire-reports", "org.x.FooTest", TWO), "-Dtest=org.x.FooTest,**/y/FooTest");

        assertRefused(run, "org.x.FooTest=2 **/y/FooTest=0", "**/y/FooTest");
    }

    @Test
    void noSelector_isUnchanged() throws Exception {
        var run = run("exit 0", "-B", "install");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).doesNotContain("selected testcases");
    }

    @Test
    void onlyExclusionsAndNone_selectNothing_andAreUnchanged() throws Exception {
        var run = run("exit 0", "-Dtest=none", "-Dit.test=!StreamPublishReshuffleTest");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).doesNotContain("selected testcases");
    }

    @Test
    void aFailedBuild_keepsItsOwnRc() throws Exception {
        var run = run("exit 3", "-Dit.test=NoSuchClassIT");

        assertThat(run.rc()).as(run.log()).isEqualTo("3");
        assertThat(run.fileRc()).isEqualTo("3");
        assertThat(run.log()).doesNotContain("selected testcases");
    }

    @Test
    void fixtureReportsOutsideTargetReportDirs_doNotCount() throws Exception {
        var run = run(report("m/src/test/resources/target/surefire-reports", "org.x.FooTest", TWO)
                      + " && " + report("m/target/fixtures", "org.x.FooTest", TWO)
                      + " && " + report("m/surefire-reports", "org.x.FooTest", TWO),
                      "-Dtest=FooTest");

        assertRefused(run, "FooTest=0", "FooTest");
    }

    @Test
    void aMissingRunMarker_isNeverAPass() throws Exception {
        var run = run("rm -f .bb/run.marker; " + report("m/target/surefire-reports", "org.x.FooTest", TWO), "-Dtest=FooTest");

        assertThat(run.rc()).as(run.log()).isEqualTo("19");
        assertThat(run.log()).contains("[ERROR] bb-build: refused rc 0 -> 19: the selected-testcase count failed (rc 1)");
    }

    // ---- (a) `none` only as the exact whole value (CTO ruling 2026-11-22) ----

    @Test
    void noneAsBothSelectors_selectsNoClass_isRefusedWith19() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.FooTest", TWO), "-Dtest=none", "-Dit.test=none");

        assertThat(run.rc()).as(run.log()).isEqualTo("19");
        assertThat(run.fileRc()).isEqualTo("19");
        assertThat(run.log()).contains("[ERROR] bb-build: refused rc 0 -> 19: -Dtest=none and -Dit.test=none select no class\n");
    }

    @Test
    void capitalisedNone_isAClassToken_andRanNothing_isRefused() throws Exception {
        var run = run(report("m/target/failsafe-reports", "org.x.BbFooIT", TWO), "-Dtest=None", "-Dit.test=BbFooIT");

        assertRefused(run, "None=0 BbFooIT=2", "None");
    }

    @Test
    void noneInsideACommaList_isAClassToken_andRanNothing_isRefused() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.FooTest", TWO), "-Dtest=none,FooTest");

        assertRefused(run, "none=0 FooTest=2", "none");
    }

    @Test
    void itTestNone_withACountedTestSelector_isRc0() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.FooTest", TWO), "-Dtest=FooTest", "-Dit.test=none");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: FooTest=2\n");
    }

    // ---- (b) bb-test launch tickets go through the same counter (CTO ruling 2026-11-22) ----

    @Test
    void aLaunchOfTwoClasses_oneRanNothing_isRefusedWith19() throws Exception {
        var run = launch(report("$D", "junit-jupiter", cases("fx.ATest", "fx.ATest")), "1", "fx.ATest", "fx.BTest#m");

        assertRefused(run, "fx.ATest=2 fx.BTest#m=0", "fx.BTest#m");
    }

    @Test
    void aLaunchOfTwoClasses_bothRan_isRc0() throws Exception {
        var run = launch(report("$D", "junit-jupiter", cases("fx.ATest", "fx.BTest", "fx.BTest$Inner")), "1", "fx.ATest", "fx.BTest");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.fileRc()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: fx.ATest=1 fx.BTest=2\n").doesNotContain("refused");
    }

    @Test
    void aRepeatedLaunch_whoseSecondRunRanNothing_isRefusedNamingTheRun() throws Exception {
        var run = launch(report("$D/run1", "junit-jupiter", cases("fx.ATest")) + " && mkdir -p $D/run2", "2", "fx.ATest");

        assertRefused(run, "run1:fx.ATest=1 run2:fx.ATest=0", "run2:fx.ATest");
    }

    @Test
    void aLaunchCountsOnlyItsOwnTicketsReports() throws Exception {
        var run = launch(report("m/target/bb-test-reports/other-ticket", "junit-jupiter", cases("fx.ATest"))
                         + " && " + report("m/target/surefire-reports", "fx.ATest", TWO), "1", "fx.ATest");

        assertRefused(run, "fx.ATest=0", "fx.ATest");
    }
}
