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

    /// The stub maven's log line of a plugin's test goal execution (the ALL cell's "P ran" evidence, header: P RAN).
    private static String goal(String plugin, String goal) {
        return "echo '[INFO] --- " + plugin + ":3.2.5:" + goal + " (default) @ m ---'";
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

    /// CTO 2026-11-22: a bare `Outer` counts its @Nested classes. Surefire reports a @Nested class in its own TEST-Outer$Inner.xml with
    /// classname Outer$Inner; an Outer whose only tests are nested must not be a false 19.
    @Test
    void aBareOuterSelector_countsItsNestedClassReports() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.OuterTest\\$Inner", cases("org.x.OuterTest$Inner")), "-Dtest=OuterTest",
                      "-Dsurefire.failIfNoSpecifiedTests=false");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: OuterTest=1\n").doesNotContain("refused");
    }

    @Test
    void aDoublyNestedReport_countsForEveryDollarPrefix_OuterAndOuterInner() throws Exception {
        var deeper = report("m/target/surefire-reports", "org.x.OuterTest\\$Inner\\$Deeper", cases("org.x.OuterTest$Inner$Deeper"));

        for (var selector : List.of("OuterTest$Inner", "OuterTest")) {
            var run = run(deeper, "-Dtest=" + selector, "-Dsurefire.failIfNoSpecifiedTests=false");

            assertThat(run.rc()).as(selector + run.log()).isEqualTo("0");
            assertThat(run.log()).contains("bb-build: selected testcases: " + selector + "=1\n").doesNotContain("refused");
        }
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

    /// CTO ruling 2026-11-22: no selector is never gated, but both whole counts are logged, so a reader can see a zero.
    @Test
    void nosel_noSelector_isNotGated_rcKept_bothWholeCountsLogged_zeroIncluded() throws Exception {
        var some = run(report("m/target/surefire-reports", "org.x.FooTest", ONE), "-B", "install");
        var none = run("exit 0", "-B", "install");

        assertThat(some.rc()).as(some.log()).isEqualTo("0");
        assertThat(some.fileRc()).isEqualTo("0");
        assertThat(some.log()).contains("bb-build: testcases (no selector, not gated): surefire(all)=1 failsafe(all)=0\n").doesNotContain("refused");
        assertThat(none.rc()).as(none.log()).isEqualTo("0");
        assertThat(none.fileRc()).isEqualTo("0");
        assertThat(none.log()).contains("bb-build: testcases (no selector, not gated): surefire(all)=0 failsafe(all)=0\n").doesNotContain("refused");
    }

    // ---- -Dtest=none with NO -Dit.test: failsafe runs every IT, i.e. select all; run-level count (CTO ruling 2026-11-22) ----

    private static final String ONE = "<testsuite><testcase classname='x' name='a'/></testsuite>";

    @Test
    void testNone_noItTest_oneExecutedFailsafeTestcase_isRc0() throws Exception {
        var run = run(report("m/target/failsafe-reports", "org.x.BbFooIT", ONE), "-B", "install", "-Dtest=none");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.fileRc()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: failsafe(all)=1\n");
    }

    @Test
    void testNone_noItTest_zeroFailsafeTestcases_isRefusedWith19_surefireDoesNotCount() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.FooTest", TWO) + " && " + report("m/target/failsafe-reports", "org.x.BbFooIT", EMPTY),
                      "-B", "install", "-Dtest=none");

        assertRefused(run, "failsafe(all)=0", "failsafe(all)");
    }

    @Test
    void testNone_noItTest_onlySkippedFailsafeTestcases_isRefusedWith19() throws Exception {
        var run = run(report("m/target/failsafe-reports", "org.x.BbFooIT", SKIPPED), "-Dtest=none");

        assertRefused(run, "failsafe(all)=0(+2 skipped)", "failsafe(all)");
    }

    @Test
    void testNone_itTestOfExclusionsOnly_alsoSelectsAllIts_andIsCountedRunLevel() throws Exception {
        assertRefused(run(goal("failsafe", "integration-test") + "; echo '[INFO] No tests to run.'", "-Dtest=none", "-Dit.test=!StreamPublishReshuffleTest"),
                      "failsafe(all)=0", "failsafe(all)");

        var run = run(report("m/target/failsafe-reports", "org.x.BbFooIT", TWO), "-Dtest=none", "-Dit.test=!StreamPublishReshuffleTest");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: failsafe(all)=2\n");
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
    void uncountable_aMissingRunMarker_isNeverAPass() throws Exception {
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

    // ---- (d) EXECUTED testcases only: a <testcase> with a <skipped> child does not count ----

    private static final String SKIPPED = "<testsuite><testcase classname='x' name='a'><skipped/></testcase><testcase classname='x' name='b'><skipped/></testcase></testsuite>";

    @Test
    void aSelectedClassWhoseEveryTestcaseWasSkipped_isRefusedWith19() throws Exception {
        assertRefused(run(report("m/target/surefire-reports", "org.x.FooTest", SKIPPED), "-Dtest=FooTest"), "FooTest=0(+2 skipped)", "FooTest");
    }

    @Test
    void aSelectedClassWithOneExecutedAndOneSkippedTestcase_isRc0() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.FooTest", "<testsuite><testcase classname='x' name='a'/><testcase classname='x' name='b'><skipped/></testcase></testsuite>"),
                      "-Dtest=FooTest");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: FooTest=1(+1 skipped)\n");
    }

    // ---- THE TABLE (bb-build header, CTO ruling 2026-11-22): a row per cell, named after it (nosel_, uncountable_, queueoff_ are above/below) ----

    @Test
    void pos_everyTokenOfS_countsInItsOwnPluginsReports_oneEmptyIsRefused() throws Exception {
        var run = run(report("m/target/surefire-reports", "org.x.FooTest", TWO) + " && " + report("m/target/failsafe-reports", "org.x.BbFooIT", TWO)
                      + " && " + report("m/target/surefire-reports", "org.x.BbBarIT", TWO),
                      "-Dtest=FooTest", "-Dit.test=BbFooIT,BbBarIT");

        assertRefused(run, "FooTest=2 BbFooIT=2 BbBarIT=0", "BbBarIT");
    }

    @Test
    void all_emptySOfAPluginThatRan_isCountedWhole_aSkippedGoalDidNotRun() throws Exception {
        assertRefused(run(goal("surefire", "test") + "; echo '[INFO] No tests to run.'", "-Dit.test=none"), "surefire(all)=0", "surefire(all)");
        assertRefused(run(report("m/target/surefire-reports", "org.x.FooTest", TWO) + "; " + goal("failsafe", "integration-test"), "-Dtest=FooTest"),
                      "FooTest=2 failsafe(all)=0", "failsafe(all)");

        var skipped = run(report("m/target/surefire-reports", "org.x.FooTest", TWO) + "; " + goal("failsafe", "integration-test")
                          + "; echo '[INFO] Tests are skipped.'", "-Dtest=FooTest");

        assertThat(skipped.rc()).as(skipped.log()).isEqualTo("0");
        assertThat(skipped.log()).contains("bb-build: selected testcases: FooTest=2\n");

        var ran = run(goal("surefire", "test") + "; " + report("m/target/surefire-reports", "org.x.FooTest", ONE), "-Dit.test=none");

        assertThat(ran.rc()).as(ran.log()).isEqualTo("0");
        assertThat(ran.log()).contains("bb-build: selected testcases: surefire(all)=1\n");
    }

    @Test
    void none_exactNoneExcludesItsPlugin_evenOneThatRan() throws Exception {
        var run = run(goal("surefire", "test") + "; " + report("m/target/failsafe-reports", "org.x.BbFooIT", TWO), "-Dtest=none", "-Dit.test=BbFooIT");

        assertThat(run.rc()).as(run.log()).isEqualTo("0");
        assertThat(run.log()).contains("bb-build: selected testcases: BbFooIT=2\n").doesNotContain("refused");
    }

    @Test
    void idle_aSelectionThatCountedNothing_isRefusedWith19() throws Exception {
        var run = run("exit 0", "-Dtest=!SlowTest");

        assertThat(run.rc()).as(run.log()).isEqualTo("19");
        assertThat(run.fileRc()).isEqualTo("19");
        assertThat(run.log()).contains("[ERROR] bb-build: refused rc 0 -> 19: the selection counted nothing: no plugin it selects ran\n")
                             .doesNotContain("selected testcases");
    }

    /// The two REAL lines maven 3 prints when a selector matches nothing, copied verbatim from build.log of 2026-11-22 (#92): surefire from
    /// `-pl temper-dist -am -Dtest='NoSuchTest#nope'` (ticket 1795380035733014647-2776658), failsafe from
    /// `-pl temper-dist -am -Dit.test=NoSuchIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false` (ticket 1795380028515472824-2768577).
    /// Both with surefire/failsafe 3.5.4; bb-build's REJECT regex must keep matching them.
    private static final String NO_TESTS_GOAL = "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.5.4:test (default-test) on project temper-test-support:"
                                                + " No tests matching pattern \"NoSuchTest#nope\" were executed! (Set -Dsurefire.failIfNoSpecifiedTests=false to ignore this error.) -> [Help 1]";
    private static final String NO_TESTS_FAILSAFE = "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-failsafe-plugin:3.5.4:integration-test (default) on project temper-dist:"
                                                    + " No tests matching pattern \"NoSuchIT\" were executed! (Set -Dfailsafe.failIfNoSpecifiedTests=false to ignore this error.) -> [Help 1]";
    private static final String CAUSE = "Caused by: org.junit.platform.commons.PreconditionViolationException: Could not find method with name [nope] in class [fx.ATest].";
    private static final String FIELDS = "echo verdict_source=java > .bb/fields.$BB_TICKET; ";

    @Test
    void reject_aSelectionTheRunnerRejected_isRc19_withTheRunnersLine() throws Exception {
        var build = run("echo '" + NO_TESTS_GOAL + "'; exit 1", "-Dtest=FooTest#nope");

        assertThat(build.rc()).as(build.log()).isEqualTo("19");
        assertThat(build.fileRc()).isEqualTo("19");
        assertThat(build.log()).contains("[ERROR] bb-build: refused rc 1 -> 19: the runner rejected the selection: " + NO_TESTS_GOAL.substring(8) + "\n");

        var failsafe = run("echo '" + NO_TESTS_FAILSAFE + "'; exit 1", "-Dit.test=NoSuchIT");

        assertThat(failsafe.rc()).as(failsafe.log()).isEqualTo("19");
        assertThat(failsafe.log()).contains("[ERROR] bb-build: refused rc 1 -> 19: the runner rejected the selection: " + NO_TESTS_FAILSAFE.substring(8) + "\n");

        var launch = launch(FIELDS + "echo 'org.junit.platform.commons.JUnitException: TestEngine with ID x failed to discover tests'; echo '" + CAUSE
                            + "'; echo 'bb-test: run 1/1 java exit 255; requireDocker=true; bb-testcount: no testcase'; exit 255", "1", "fx.ATest#nope");

        assertThat(launch.rc()).as(launch.log()).isEqualTo("19");
        assertThat(launch.log()).contains("[ERROR] bb-build: refused rc 255 -> 19: the runner rejected the selection: " + CAUSE + "\n");
    }

    @Test
    void reject_aGenuineFailureOrAnotherFailedGoal_keepsItsRc() throws Exception {
        var failure = run("echo '[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project m:"
                          + " There are test failures.'; exit 1", "-Dtest=FooTest");
        var mixed = run("echo '" + NO_TESTS_GOAL + "'; echo '[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile"
                        + " (default-compile) on project n: Compilation failure'; exit 1", "-Dtest=FooTest#nope");
        var launch = launch(FIELDS + report("$D", "junit-jupiter", "<testsuite><testcase classname='fx.ATest' name='t'><failure/></testcase></testsuite>")
                            + "; echo 'bb-test: run 1/1 java exit 255; x'; exit 255", "1", "fx.ATest");

        for (var run : List.of(failure, mixed)) {
            assertThat(run.rc()).as(run.log()).isEqualTo("1");
            assertThat(run.fileRc()).isEqualTo("1");
            assertThat(run.log()).doesNotContain("refused");
        }
        assertThat(launch.rc()).as(launch.log()).isEqualTo("255");
        assertThat(launch.log()).doesNotContain("refused");
    }

    // ---- (c) BB_QUEUE=0: bb-build-direct writes .bb/rc from its own detached job, so a counted selector is refused at the call ----

    private BbFixtures.Outcome direct(String... args) throws Exception {
        BbFixtures.executable(tools.resolve("bb-build-direct"), "#!/bin/sh\necho direct-ran \"$@\"\n");
        var command = new ArrayList<>(List.of("bash", tools.resolve("bb-build").toString()));
        command.addAll(List.of(args));
        return BbFixtures.run(command, Map.of("BB_QUEUE", "0", "BB_OWNER", ME), null, null);
    }

    @Test
    void queueoff_anySelector_noneIncluded_isRefusedWith19_andNothingRuns() throws Exception {
        for (var args : List.of(List.of(co.toString(), "-Dtest=FooTest"), List.of(co.toString(), "-Dtest=none", "-Dit.test=none"),
                                List.of(co.toString(), "-Dtest=none"), List.of(co.toString(), "-Dtest=none", "-Dit.test=!X"),
                                List.of("--since", "abc", co.toString(), "-Dit.test=BbFooIT"),
                                List.of(co.toString(), "-Dit.test=none"), List.of(co.toString(), "-Dtest=!X"))) {
            var out = direct(args.toArray(String[]::new));

            assertThat(out.rc()).as(args + out.stdout() + out.stderr()).isEqualTo(19);
            assertThat(out.stderr()).contains("refusing (rc 19): BB_QUEUE=0 cannot count a -Dtest/-Dit.test selection");
            assertThat(out.stdout()).doesNotContain("direct-ran");
        }
    }

    @Test
    void queueoff_noSelector_runsBbBuildDirectUnchanged() throws Exception {
        for (var args : List.of(List.of(co.toString(), "-B", "install"), List.of(co.toString(), "-DskipTests"))) {
            var out = direct(args.toArray(String[]::new));

            assertThat(out.rc()).as(args + out.stderr()).isZero();
            assertThat(out.stdout()).startsWith("direct-ran " + String.join(" ", args));
        }
    }
}
