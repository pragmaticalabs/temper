package org.pragmatica.temper.dist;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// `bb-result <ticket-id> [--json]`: the one-screen summary of a finished queue ticket. Real script (python3) against a fake queue root
/// (`BB_TEST_QUEUE_ROOT`) and a fake checkout holding `.bb/start`, `.bb/build.log` and report XML with chosen mtimes.
///
/// Probes (each: break bb-result, the named test goes red, restore):
/// drop the `mtime >= started` filter -> [#aReportOlderThanStarted_isNotCounted]; drop `src` from PRUNE and the target-entry rule ->
/// [#fixtureReportsUnderSrcOrNestedTargets_areNeverCounted]; drop the `.bb/start` comparison -> [#aLaterBuildInTheCheckout_givesUnavailable_neverAnotherBuildsCounts];
/// accept a bare `[ERROR]` as the first error -> [#aCompileFailure_hasNoReports_andTheFirstErrorComesFromTheLog]; drop the 300-char cut ->
/// [#aLineOverThreeHundredChars_isCut]; exit 0 for a running ticket -> [#waitingAndRunning_exit3_unknown4_malformedId2]; keep only the first failing test in
/// --json -> [#json_carriesEveryFailingTest_andTheFullDetail]; read the whole log at once -> [#aHugeLog_isReadInBoundedChunks].
/// bb-build --help is covered by BbBuildFieldsIT.
class BbResultIT {
    private static final Path BB_RESULT = BbFixtures.OPS_BB.resolve("bb-result");
    private static final long STARTED = 1_800_000_100L;

    @TempDir
    Path dir;

    private Path queue;
    private Path co;
    private final Map<String, String> noEnv = Map.of();

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb tools run on the Linux build hosts");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }
        co = Files.createDirectories(dir.resolve("co"));
    }

    private BbFixtures.Outcome result(String... args) throws Exception {
        return result(noEnv, args);
    }

    private BbFixtures.Outcome result(Map<String, String> extra, String... args) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.putAll(extra);
        var command = new ArrayList<>(List.of("python3", BB_RESULT.toString()));

        command.addAll(List.of(args));

        return BbFixtures.run(command, env, null, null);
    }

    /// done/<id>: finished, created 100 s before started, ended 60 s after.
    private void done(String id, String state, String rc) throws IOException {
        Files.writeString(queue.resolve("done").resolve(id),
                          "id=" + id + "\nowner=t\nco=" + co + "\ncreated=" + ((STARTED - 100) * 1_000_000_000L)
                         + "\ngroup=0\nargs=-B test \nstarted=" + STARTED + "\nwaited=3\nstate=" + state + "\nrc=" + rc
                         + "\nended=" + (STARTED + 60) + "\n");
    }

    private void bbFiles(long start, String log) throws IOException {
        var bb = Files.createDirectories(co.resolve(".bb"));

        Files.writeString(bb.resolve("start"), start + "\n");
        Files.writeString(bb.resolve("build.log"), log);
    }

    private static String suite(int tests, int failures, int errors, int skipped, String cases) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuite name=\"s\" tests=\"" + tests + "\" failures=\"" + failures
               + "\" errors=\"" + errors + "\" skipped=\"" + skipped + "\">\n" + cases + "</testsuite>\n";
    }

    private static String ok(String cls, String name) {
        return "<testcase classname=\"" + cls + "\" name=\"" + name + "\" time=\"0.1\"/>\n";
    }

    private static String failed(String cls, String name, String kind, String message) {
        return "<testcase classname=\"" + cls + "\" name=\"" + name + "\" time=\"0.1\"><" + kind + " message=\"" + message + "\" type=\"T\">stack\nline</"
               + kind + "></testcase>\n";
    }

    private Path report(String rel, String xml, long mtime) throws IOException {
        var file = co.resolve(rel);

        Files.createDirectories(file.getParent());
        Files.writeString(file, xml);
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime * 1000));

        return file;
    }

    private Path report(String rel, String xml) throws IOException {
        return report(rel, xml, STARTED + 30);
    }

    private static List<String> lines(BbFixtures.Outcome o) {
        return o.stdout().lines().toList();
    }

    private List<String> tree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return walk.sorted()
                       .map(p -> {
                                try {
                                    return p + " " + (Files.isRegularFile(p) ? Files.size(p) : -1) + " " + Files.getLastModifiedTime(p).toMillis();
                                } catch (IOException e) {
                                    throw new IllegalStateException(e);
                                }
                            })
                       .collect(Collectors.toList());
        }
    }

    @Test
    void aPass_printsCountsAcrossModules_headerLikeBbWait_andWritesNothing() throws Exception {
        done("1-10", "finished", "0");
        bbFiles(STARTED, "[INFO] BUILD SUCCESS\n");
        report("a/target/surefire-reports/TEST-a.ATest.xml", suite(5, 0, 0, 1, ok("a.ATest", "t")));
        report("b/target/failsafe-reports/TEST-b.BIT.xml", suite(3, 0, 0, 0, ok("b.BIT", "t")));
        var before = tree(dir);
        var run = result("1-10");

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(lines(run)).containsExactly("ticket=1-10 state=finished rc=0 wall=160s",
                                               "tests: 8 run, 0 failures, 0 errors, 1 skipped (2 report files)",
                                               "reports: " + co + "/… (2 report dirs)");
        assertThat(tree(dir)).as("read-only: no file or directory written or touched").isEqualTo(before);
    }

    @Test
    void oneModule_namesItsReportDirectory() throws Exception {
        done("1-11", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-a.ATest.xml", suite(2, 0, 0, 0, ""));
        var run = result("1-11");

        assertThat(lines(run)).containsExactly("ticket=1-11 state=finished rc=0 wall=160s",
                                               "tests: 2 run, 0 failures, 0 errors, 0 skipped (1 report files)",
                                               "reports: " + co + "/a/target/surefire-reports/");
    }

    @Test
    void aFailure_namesTheFirstFailingTest_withTheFirstLineOfItsMessage() throws Exception {
        done("1-12", "finished", "1");
        bbFiles(STARTED, "[ERROR] Tests run: 3, Failures: 1\n");
        report("a/target/surefire-reports/TEST-a.ATest.xml",
               suite(3, 1, 0, 0, ok("a.ATest", "good") + failed("a.ATest", "bad", "failure", "expected 1&#10;but was 2") + ok("a.ATest", "fine")));
        var run = result("1-12");

        assertThat(run.rc()).isZero();
        assertThat(lines(run)).hasSize(4);
        assertThat(lines(run).get(0)).isEqualTo("ticket=1-12 state=finished rc=1 wall=160s");
        assertThat(lines(run).get(1)).isEqualTo("tests: 3 run, 1 failures, 0 errors, 0 skipped (1 report files)");
        assertThat(lines(run).get(3)).isEqualTo("first failing: a.ATest#bad: expected 1");
        assertThat(run.stdout()).as("a failing test suppresses the log's first error").doesNotContain("first error");
    }

    @Test
    void anError_isNamedLikeAFailure() throws Exception {
        done("1-13", "finished", "1");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-a.ATest.xml", suite(1, 0, 1, 0, failed("a.ATest", "boom", "error", "java.lang.IllegalStateException: boom")));
        var run = result("1-13");

        assertThat(lines(run)).contains("tests: 1 run, 0 failures, 1 errors, 0 skipped (1 report files)",
                                        "first failing: a.ATest#boom: java.lang.IllegalStateException: boom");
    }

    @Test
    void aCompileFailure_hasNoReports_andTheFirstErrorComesFromTheLog() throws Exception {
        done("1-14", "finished", "1");
        bbFiles(STARTED,
                "[INFO] Compiling 3 source files\n[ERROR]\n[ERROR]   \n\n[ERROR] /co/a/Foo.java:[3,5] cannot find symbol\n[ERROR] second\n");
        var run = result("1-14");

        assertThat(lines(run)).containsExactly("ticket=1-14 state=finished rc=1 wall=160s",
                                               "tests: none (no report written by this build)",
                                               "first error: [ERROR] /co/a/Foo.java:[3,5] cannot find symbol");
    }

    @Test
    void fixtureReportsUnderSrcOrNestedTargets_areNeverCounted() throws Exception {
        done("1-15", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-real.xml", suite(1, 0, 0, 0, ""));
        report("a/src/test/resources/s1-reports/TEST-fix.xml", suite(99, 9, 0, 0, ""));
        report("a/src/test/resources/fx/target/surefire-reports/TEST-fix2.xml", suite(98, 8, 0, 0, ""));
        report("a/target/test-classes/fx/target/surefire-reports/TEST-copy.xml", suite(97, 7, 0, 0, ""));
        report("a/target/classes/TEST-loose.xml", suite(96, 6, 0, 0, ""));
        var run = result("1-15");

        assertThat(lines(run)).contains("tests: 1 run, 0 failures, 0 errors, 0 skipped (1 report files)");
    }

    @Test
    void aReportOlderThanStarted_isNotCounted() throws Exception {
        done("1-16", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-old.xml", suite(7, 0, 0, 0, ""), STARTED - 1);
        report("a/target/surefire-reports/TEST-new.xml", suite(2, 0, 0, 0, ""), STARTED);

        assertThat(lines(result("1-16"))).contains("tests: 2 run, 0 failures, 0 errors, 0 skipped (1 report files)");

        Files.setLastModifiedTime(co.resolve("a/target/surefire-reports/TEST-new.xml"), FileTime.fromMillis((STARTED - 5) * 1000));

        assertThat(lines(result("1-16"))).contains("tests: none (no report written by this build)");
    }

    @Test
    void aLaterBuildInTheCheckout_givesUnavailable_neverAnotherBuildsCounts() throws Exception {
        done("1-17", "finished", "1");
        bbFiles(STARTED + 5000, "[ERROR] the later build's failure\n");
        report("a/target/surefire-reports/TEST-a.xml", suite(42, 3, 0, 0, failed("a.A", "x", "failure", "later")), STARTED + 5010);
        var run = result("1-17");

        assertThat(lines(run)).containsExactly("ticket=1-17 state=finished rc=1 wall=160s",
                                               "tests: unavailable (a later build in this checkout replaced the reports)",
                                               "first error: unavailable: a later build in this checkout replaced the log");
        assertThat(run.stdout()).doesNotContain("42").doesNotContain("later build's");
    }

    @Test
    void aCheckoutWithoutBbStart_isUnavailableToo() throws Exception {
        done("1-18", "finished", "0");
        report("a/target/surefire-reports/TEST-a.xml", suite(5, 0, 0, 0, ""));

        assertThat(lines(result("1-18"))).contains("tests: unavailable (the checkout's .bb/start is gone)");
    }

    @Test
    void anUnparseableReport_isCountedAsBad_andNeverStopsTheSummary() throws Exception {
        done("1-19", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-good.xml", suite(4, 0, 0, 0, ""));
        report("a/target/surefire-reports/TEST-cut.xml", "<testsuite tests=\"9\" failures=\"0\" err");
        var run = result("1-19");

        assertThat(run.rc()).isZero();
        assertThat(lines(run)).contains("tests: 4 run, 0 failures, 0 errors, 0 skipped (2 report files, 1 bad report files)");
    }

    @Test
    void waitingAndRunning_exit3_unknown4_malformedId2() throws Exception {
        Files.writeString(queue.resolve("waiting").resolve("2-1"), "id=2-1\nco=" + co + "\n");
        Files.writeString(queue.resolve("running").resolve("2-2"), "id=2-2\nco=" + co + "\nstarted=" + STARTED + "\n");
        var waiting = result("2-1");
        var running = result("2-2");

        assertThat(waiting.rc()).isEqualTo(3);
        assertThat(waiting.stdout().strip()).isEqualTo("ticket=2-1 state=waiting: not finished; use bb-await --ticket 2-1");
        assertThat(running.rc()).isEqualTo(3);
        assertThat(running.stdout().strip()).isEqualTo("ticket=2-2 state=running: not finished; use bb-await --ticket 2-2");
        assertThat(result("2-2", "--json").rc()).isEqualTo(3);
        assertThat(result("9-9").rc()).isEqualTo(4);
        assertThat(result("9-9").stdout()).isEmpty();

        for (var bad : List.of(List.of("abc"), List.of("1-2-3"), List.of("12"), List.of("1-2", "3-4"), List.of("--json"), List.<String>of(), List.of("1-2", "--bogus"))) {
            var run = result(bad.toArray(new String[0]));

            assertThat(run.rc()).as(bad.toString()).isEqualTo(2);
            assertThat(run.stdout()).isEmpty();
            assertThat(run.stderr()).contains("usage: bb-result");
        }
    }

    @Test
    void json_carriesEveryFailingTest_andTheFullDetail() throws Exception {
        done("1-20", "finished", "1");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-a.xml",
               suite(3, 2, 0, 0, failed("a.A", "one", "failure", "m1") + failed("a.A", "two", "failure", "m2")));
        report("b/target/failsafe-reports/TEST-b.xml", suite(2, 0, 1, 0, failed("b.B", "three", "error", "m3")));
        var run = result("1-20", "--json");
        var json = run.stdout();

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(json.lines()).as("one JSON object").hasSize(1);
        assertThat(json).contains("\"ticket\": \"1-20\"", "\"rc\": 1", "\"wall\": 160", "\"status\": \"counted\"", "\"tests\": 5", "\"failures\": 2", "\"errors\": 1",
                                  "\"reportFiles\": 2", "\"badReportFiles\": 0", "\"name\": \"one\"", "\"name\": \"two\"", "\"name\": \"three\"",
                                  "\"message\": \"m1\"", "\"message\": \"m2\"", "\"message\": \"m3\"", "\"kind\": \"error\"");
        assertThat(json.split("\"kind\":", -1)).as("three failing tests").hasSize(4);
        assertThat(json).contains(co.resolve("a/target/surefire-reports/TEST-a.xml").toString(), co.resolve("b/target/failsafe-reports/TEST-b.xml").toString());
    }

    @Test
    void aLineOverThreeHundredChars_isCut() throws Exception {
        done("1-21", "finished", "1");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-a.xml", suite(1, 1, 0, 0, failed("a.A", "long", "failure", "x".repeat(500))));
        var line = lines(result("1-21")).get(3);

        assertThat(line).hasSize(300).startsWith("first failing: a.A#long: xxx").endsWith("...");
    }

    @Test
    void aHugeLog_isReadInBoundedChunks() throws Exception {
        done("1-22", "finished", "1");
        bbFiles(STARTED, "");
        var log = co.resolve(".bb/build.log");

        try (var out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            var line = "[WARNING] a repeated line of build output that a stuck build writes forever and ever\n";

            for (var i = 0; i < 50_000_000 / line.length(); i++) {
                out.write(line);
            }

            out.write("[ERROR] the real failure\n");
        }

        var t0 = System.nanoTime();
        var run = result("1-22");
        var seconds = (System.nanoTime() - t0) / 1_000_000_000L;

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(lines(run).get(2)).isEqualTo("first error: [ERROR] the real failure");
        assertThat(run.stdout().length()).isLessThan(1024);
        assertThat(seconds).as("seconds for a 50 MB log").isLessThan(20);

        // a 300 MB log (sparse, no newline at all) under a 700 MB address-space limit: reading it whole cannot fit
        try (var raf = new RandomAccessFile(log.toFile(), "rw")) {
            raf.setLength(300L * 1024 * 1024);
        }

        var limited = BbFixtures.run(List.of("bash",
                                             "-c",
                                             "ulimit -v 700000; exec python3 \"$0\" 1-22",
                                             BB_RESULT.toString()),
                                     Map.of("PATH", System.getenv("PATH"), "HOME", dir.toString(), "BB_TEST_QUEUE_ROOT", queue.toString()),
                                     null,
                                     null);

        assertThat(limited.rc()).as(limited.stderr()).isZero();
        assertThat(lines(limited).get(2)).startsWith("first error: no [ERROR] line in the first 64 MB or last 16 MB");
    }

    @Test
    void anOrphanedTicket_withoutStarted_saysThereWasNoBuild() throws Exception {
        Files.writeString(queue.resolve("done").resolve("3-1"), "id=3-1\nco=" + co + "\ncreated=" + (STARTED * 1_000_000_000L) + "\nstate=orphaned\nended=" + (STARTED + 9) + "\n");
        var run = result("3-1");

        assertThat(run.rc()).isZero();
        assertThat(lines(run)).containsExactly("ticket=3-1 state=orphaned rc=- wall=9s",
                                               "tests: none (the ticket has no started build or no checkout)");
    }
}
