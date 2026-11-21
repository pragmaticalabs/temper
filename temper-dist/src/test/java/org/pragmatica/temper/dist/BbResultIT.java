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
import java.util.concurrent.TimeUnit;
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
/// [#fixtureReportsUnderSrcOrNestedTargets_areNeverCounted]; drop the `.bb/ticket` comparison -> [#aLaterBuildInTheCheckout_givesUnavailable_neverAnotherBuildsCounts];
/// accept a bare `[ERROR]` as the first error -> [#aCompileFailure_hasNoReports_andTheFirstErrorComesFromTheLog]; drop the 300-char cut ->
/// [#aLineOverThreeHundredChars_isCut]; exit 0 for a running ticket -> [#waitingAndRunning_exit3_unknown4_malformedId2]; keep only the first failing test in
/// --json -> [#json_carriesEveryFailingTest_andTheFullDetail]; read the whole log at once -> [#aHugeLog_isReadInBoundedChunks].
/// Second round (verifier vbres1): quadratic `[ERROR]` line (the old lazy regex) -> [#aLogLineOfTwoHundredThousandBlanks_isScannedInLinearTime];
/// drop the testsuites-root branch -> [#aTestsuitesRoot_countsEveryTestsuiteInIt]; os.walk followlinks=True ->
/// [#aSymlinkLoopAndASymlinkOutOfTheCheckout_areNotFollowed]; drop `.m2-local` from PRUNE -> [#aReportUnderM2Local_isNotCounted]; drop sorted() ->
/// [#theFirstFailingTest_isTheOneFromTheFirstReportPathInSortedOrder]; print raw control bytes -> [#controlBytesInALogAndAMessage_areReplacedInTextAndJson];
/// drop the regular-file test -> [#aFifoNamedLikeAReport_isSkippedNotRead].
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
    private String lastId;

    private void done(String id, String state, String rc) throws IOException {
        lastId = id;
        Files.writeString(queue.resolve("done").resolve(id),
                          "id=" + id + "\nowner=t\nco=" + co + "\ncreated=" + ((STARTED - 100) * 1_000_000_000L)
                         + "\ngroup=0\nargs=-B test \nstarted=" + STARTED + "\nwaited=3\nstate=" + state + "\nrc=" + rc
                         + "\nended=" + (STARTED + 60) + "\n");
    }

    private void bbFiles(long start, String log) throws IOException {
        var bb = Files.createDirectories(co.resolve(".bb"));

        Files.writeString(bb.resolve("start"), start + "\n");
        Files.writeString(bb.resolve("ticket"), lastId + "\n");   // #80: the run that owns .bb is the last done() ticket
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
        Files.writeString(co.resolve(".bb/ticket"), "9-9\n");
        report("a/target/surefire-reports/TEST-a.xml", suite(42, 3, 0, 0, failed("a.A", "x", "failure", "later")), STARTED + 5010);
        var run = result("1-17");

        assertThat(lines(run)).containsExactly("ticket=1-17 state=finished rc=1 wall=160s",
                                               "tests: unavailable (a later build in this checkout replaced the reports)",
                                               "first error: unavailable: a later build in this checkout replaced the log");
        assertThat(run.stdout()).doesNotContain("42").doesNotContain("later build's");
    }

    @Test
    void aCheckoutWithoutBbTicket_isUnavailableToo() throws Exception {
        done("1-18", "finished", "0");
        report("a/target/surefire-reports/TEST-a.xml", suite(5, 0, 0, 0, ""));

        assertThat(lines(result("1-18"))).contains("tests: unavailable (the checkout's .bb/ticket is gone)");
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

        // a 600 MB log (sparse, no newline at all) under a 400 MB address-space limit: reading it whole cannot fit (300 MB under 700 MB let the unbounded read pass)
        Files.delete(log);
        try (var raf = new RandomAccessFile(log.toFile(), "rw")) {
            raf.setLength(600L * 1024 * 1024);
        }

        var limited = BbFixtures.run(List.of("bash",
                                             "-c",
                                             "ulimit -v 400000; exec python3 \"$0\" 1-22",
                                             BB_RESULT.toString()),
                                     Map.of("PATH", System.getenv("PATH"), "HOME", dir.toString(), "BB_TEST_QUEUE_ROOT", queue.toString()),
                                     null,
                                     null);

        assertThat(limited.rc()).as(limited.stderr()).isZero();
        assertThat(lines(limited).get(2)).startsWith("first error: no [ERROR] line in the first 64 MB or last 16 MB");
    }

    /// Like result(), but the script must end within `seconds` (a hang is a failure, not a stuck build).
    private BbFixtures.Outcome resultWithin(int seconds, String... args) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        var command = new ArrayList<>(List.of("python3", BB_RESULT.toString()));

        command.addAll(List.of(args));
        var builder = BbFixtures.process(command, env);

        builder.redirectErrorStream(false);
        var out = Files.createTempFile(dir, "out", ".txt");
        var err = Files.createTempFile(dir, "err", ".txt");

        builder.redirectOutput(out.toFile());
        builder.redirectError(err.toFile());
        var process = builder.start();

        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("bb-result did not finish within " + seconds + " s");
        }

        return new BbFixtures.Outcome(process.exitValue(), Files.readString(out), Files.readString(err));
    }

    @Test
    void aLogLineOfTwoHundredThousandBlanks_isScannedInLinearTime() throws Exception {
        done("4-1", "finished", "1");
        bbFiles(STARTED, "[ERROR] a" + " ".repeat(200_000) + "b\n");
        var t0 = System.nanoTime();
        var run = resultWithin(20, "4-1");
        var seconds = (System.nanoTime() - t0) / 1_000_000_000L;

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(lines(run).get(2)).startsWith("first error: [ERROR] a   ").endsWith("...");
        assertThat(seconds).as("seconds for one 200 kB line").isLessThan(5);
    }

    @Test
    void aTestsuitesRoot_countsEveryTestsuiteInIt() throws Exception {
        done("4-2", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-multi.xml",
               "<?xml version=\"1.0\"?>\n<testsuites>\n<testsuite name=\"x\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"0\">"
              + failed("a.X", "bad", "failure", "m") + "</testsuite>\n<testsuite name=\"y\" tests=\"4\" failures=\"0\" errors=\"1\" skipped=\"2\"/>\n</testsuites>\n");
        var run = result("4-2");

        assertThat(lines(run)).contains("tests: 7 run, 1 failures, 1 errors, 2 skipped (1 report files)");
    }

    @Test
    void aSymlinkLoopAndASymlinkOutOfTheCheckout_areNotFollowed() throws Exception {
        done("4-3", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-real.xml", suite(1, 0, 0, 0, ""));
        Files.createSymbolicLink(co.resolve("a/loop"), co.resolve("a"));
        Files.createSymbolicLink(co.resolve("loopUp"), co);
        var outside = dir.resolve("outside");

        Files.createDirectories(outside.resolve("target/surefire-reports"));
        var foreign = outside.resolve("target/surefire-reports/TEST-out.xml");

        Files.writeString(foreign, suite(50, 0, 0, 0, ""));
        Files.setLastModifiedTime(foreign, FileTime.fromMillis((STARTED + 30) * 1000));
        Files.createSymbolicLink(co.resolve("ext"), outside);
        var run = resultWithin(60, "4-3");

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(lines(run)).contains("tests: 1 run, 0 failures, 0 errors, 0 skipped (1 report files)");
    }

    @Test
    void aReportUnderM2Local_isNotCounted() throws Exception {
        done("4-4", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-real.xml", suite(1, 0, 0, 0, ""));
        report(".m2-local/org/x/y/target/surefire-reports/TEST-m2.xml", suite(40, 0, 0, 0, ""));
        var run = result("4-4");

        assertThat(lines(run)).contains("tests: 1 run, 0 failures, 0 errors, 0 skipped (1 report files)");
    }

    @Test
    void theFirstFailingTest_isTheOneFromTheFirstReportPathInSortedOrder() throws Exception {
        done("4-5", "finished", "1");
        bbFiles(STARTED, "");
        // created in an order that is neither sorted nor reverse-sorted, so no directory listing order (creation, reverse creation, hash) is the sorted one
        var created = List.of("m5", "m2", "m8", "m1", "m7", "m3", "m6", "m4");

        for (var m : created) {
            report(m + "/target/surefire-reports/TEST-" + m + ".xml", suite(1, 1, 0, 0, failed(m + ".T", "t" + m, "failure", "msg " + m)));
        }

        var text = result("4-5");
        var json = result("4-5", "--json").stdout();

        assertThat(lines(text).get(3)).isEqualTo("first failing: m1.T#tm1: msg m1");

        var positions = new ArrayList<Integer>();

        for (var i = 1; i <= 8; i++) {
            positions.add(json.indexOf("\"name\": \"tm" + i + "\""));
        }

        assertThat(positions).as("failing tests in --json, sorted by report path").isSorted().doesNotContain(-1);
    }

    @Test
    void controlBytesInALogAndAMessage_areReplacedInTextAndJson() throws Exception {
        done("4-6", "finished", "1");
        bbFiles(STARTED, "");
        Files.write(co.resolve(".bb/build.log"), new byte[]{'[', 'E', 'R', 'R', 'O', 'R', ']', ' ', 'b', 'a', 'd', 0x1b, '[', '3', '1', 'm', 'r', 0, (byte) 0xff, ' ', 'e', 'n', 'd', '\n'});
        var logRun = result("4-6");
        var logJson = result("4-6", "--json");

        assertThat(lines(logRun)).contains("first error: [ERROR] bad?[31mr?? end");
        assertThat(logJson.stdout()).contains("bad?[31mr?? end").doesNotContain("\\u001b").doesNotContain("\\u0000");

        done("4-7", "finished", "1");
        Files.writeString(co.resolve(".bb/ticket"), "4-7\n");
        report("a/target/surefire-reports/TEST-a.xml", suite(1, 1, 0, 0, failed("a.A", "t", "failure", "m\u007f\u009bz")));
        var msgRun = result("4-7");
        var msgJson = result("4-7", "--json");

        assertThat(lines(msgRun).get(3)).isEqualTo("first failing: a.A#t: m??z");
        assertThat(msgJson.stdout()).contains("\"message\": \"m??z\"");

        for (var out : List.of(logRun.stdout(), logJson.stdout(), msgRun.stdout(), msgJson.stdout())) {
            assertThat(out.chars().filter(c -> (c < 0x20 && c != '\n' && c != '\t') || c == 0x7f || (c >= 0x80 && c < 0xa0) || c == 0xfffd)).isEmpty();
        }
    }

    @Test
    void aFifoNamedLikeAReport_isSkippedNotRead() throws Exception {
        done("4-8", "finished", "0");
        bbFiles(STARTED, "");
        report("a/target/surefire-reports/TEST-good.xml", suite(2, 0, 0, 0, ""));
        var fifo = co.resolve("a/target/surefire-reports/TEST-x.xml");

        assertThat(BbFixtures.process("mkfifo", fifo.toString()).start().waitFor()).isZero();
        // touch, not Files.setLastModifiedTime: the JDK opens the file first, and opening a FIFO with no peer blocks forever
        assertThat(BbFixtures.process("touch", "-d", "@" + (STARTED + 30), fifo.toString()).start().waitFor()).isZero();
        var run = resultWithin(20, "4-8");

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(lines(run)).contains("tests: 2 run, 0 failures, 0 errors, 0 skipped (1 report files)");
    }

    @Test
    void anOrphanedTicket_withoutStarted_saysThereWasNoBuild() throws Exception {
        Files.writeString(queue.resolve("done").resolve("3-1"), "id=3-1\nco=" + co + "\ncreated=" + (STARTED * 1_000_000_000L) + "\nstate=orphaned\nended=" + (STARTED + 9) + "\n");
        var run = result("3-1");

        assertThat(run.rc()).isZero();
        assertThat(lines(run)).containsExactly("ticket=3-1 state=orphaned rc=- wall=9s",
                                               "tests: none (the ticket has no started build or no checkout)");
    }

    private static final String WHY = "junit version: resolved junit-jupiter-api 5.11.4 (launcher wants 5.13.4); the launcher is junit-platform 1.13.4";

    /// A bb-test ticket refused by the resolved junit check (temper#72 ruling 3) whose tests PASSED: a passing report in the checkout and
    /// an error line in the log, so any count, report, failing test or error line printed would be a test-green read of a refused ticket.
    private void refusedButGreen(String id, String rc) throws IOException {
        Files.writeString(queue.resolve("done").resolve(id),
                          "id=" + id + "\nowner=t\nco=" + co + "\ncreated=" + ((STARTED - 100) * 1_000_000_000L) + "\ngroup=0\nargs=__launch app\nstarted="
                         + STARTED + "\nrefused=" + WHY + "\nverdict_source=guard\nstate=finished\nrc=" + rc + "\nended=" + (STARTED + 60) + "\n");
        bbFiles(STARTED, "[ERROR] some error line\n");
        report("app/target/surefire-reports/TEST-a.ATest.xml", suite(4, 0, 0, 0, ok("a.ATest", "t")));
    }

    @Test
    void aRefusedTicket_whoseTestsPassed_printsRefused_neverACountOrAGreenRead() throws Exception {
        refusedButGreen("1-60", "15");
        var run = result("1-60");

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(lines(run)).containsExactly("ticket=1-60 state=finished rc=15 verdict=REFUSED wall=160s",
                                               "refused: " + WHY,
                                               "tests: refused (not a verdict: " + WHY + ")");
    }

    @Test
    void aRefusedTicket_withRcZero_isStillRefused_theRefusalReplacesTheVerdict() throws Exception {
        refusedButGreen("1-61", "0");

        assertThat(lines(result("1-61"))).containsExactly("ticket=1-61 state=finished rc=0 verdict=REFUSED wall=160s",
                                                          "refused: " + WHY,
                                                          "tests: refused (not a verdict: " + WHY + ")");
    }

    @Test
    void aRefusedTicket_json_saysVerdictRefused_withTheReason_andNoCountsReportsOrFailing() throws Exception {
        refusedButGreen("1-62", "15");
        var json = result("1-62", "--json").stdout();

        assertThat(json).contains("\"verdict\": \"refused\"", "\"refused\": \"" + WHY + "\"", "\"status\": \"refused\"",
                                  "\"reason\": \"not a verdict: " + WHY + "\"", "\"reports\": []", "\"reportDirs\": []", "\"failing\": []",
                                  "\"firstError\": null", "\"rc\": 15")
                        .doesNotContain("\"counted\"", "\"tests\": 4", "TEST-a.ATest.xml");
    }

    @Test
    void anUnrefusedTicket_json_saysTheRcIsTheVerdict() throws Exception {
        done("1-63", "finished", "0");
        bbFiles(STARTED, "");

        assertThat(result("1-63", "--json").stdout()).contains("\"verdict\": \"rc\"", "\"refused\": null");
    }
}
