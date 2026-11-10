package org.pragmatica.temper.acceptance.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.temper.acceptance.stack.AcceptanceStack;
import org.pragmatica.temper.acceptance.stack.DockerAvailable;
import org.pragmatica.temper.acceptance.stack.Shell;
import org.pragmatica.temper.acceptance.trust.T1Harness;
import org.pragmatica.temper.acceptance.trust.TrustReport;

import static org.assertj.core.api.Assertions.assertThat;

/// R-14 through the REAL T1 path (preflight, snapshot, Gate via the CLI, the Judge reading the snapshot's
/// `.bb/build.log`, the report), docker tier; adopted from the M1 pre-live verifier's `VerifierT1CrashIT` (F2). Five
/// SHAs:
/// - main, e1 (an empty commit on main): comparable and green;
/// - red (the fixture's `red` overlay, one failing test): comparable, passed, and the red SHA T1 needs (F7);
/// - e3: temper green, but bb's `.bb/build.log` carries a fork-crash line → NOT COMPARABLE through bb's side only;
/// - crash: temper's Gate crashes the fork, bb's log is clean → NOT COMPARABLE through temper's side only.
///
/// The criterion holds (3 comparable, all passed, one red), but two crash explanations are unwritten: the report is
/// INCOMPLETE, never PASS, and the harness exits [T1Harness#EXIT_INCOMPLETE] (F3). A second re-run never writes over
/// the first re-run's report, whose explanation a reviewer wrote (F3).
///
/// Mutation probe: the Judge passes `none()` for bb's crash evidence → e3 becomes ✔ and this goes red.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class T1CrashIT {
    private static final String DATE = "2026-10-15";
    private static final String CRASH_LINE = "[ERROR] ExecutionException The forked VM terminated without properly saying goodbye. VM crash or System.exit called?";
    private static final String PROBE = "fx.includes.CrashedForkProbeTest";
    private static final String EXPLANATION = "the reviewer's explanation of the crash";
    private AcceptanceStack stack;
    private Path out;
    private Path bbReport;
    private final List<String> targets = new ArrayList<>();
    private String main, e1, red, e3, crash;

    @BeforeAll
    void checkouts() throws Exception {
        stack = AcceptanceStack.docker();
        var mainJob = stack.gate("main");
        var redJob = stack.gate("red");
        var crashJob = stack.gate("crash");
        main = stack.fixture.sha("main");
        red = stack.fixture.sha("red");
        crash = stack.fixture.sha("crash");
        var dir = Files.createDirectories(stack.dir.resolve("t1crash"));
        var work = dir.resolve("work");
        Shell.ok("git", "clone", "-q", stack.fixture.origin().toString(), work.toString());
        Shell.ok("git", "-C", work.toString(), "checkout", "-q", main);
        e1 = emptyCommit(work, "e1");
        e3 = emptyCommit(work, "e3");
        out = dir.resolve("out");
        bbReport = dir.resolve("bb-report");
        Files.copy(Path.of(getClass().getResource("/bb-report/bb-report.archived").toURI()), bbReport, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(bbReport, PosixFilePermissions.fromString("rwx------"));
        var mainWs = stack.workspace(mainJob);
        var mainLog = Files.readString(stack.unitDir(mainJob, "gate").resolve("log"));
        assertThat(mainLog).doesNotContain("The forked VM terminated");
        var redLog = Files.readString(stack.unitDir(redJob, "gate").resolve("log"));
        assertThat(redLog).doesNotContain("The forked VM terminated");
        var crashWs = stack.workspace(crashJob);
        add(main, checkout(dir.resolve("co-main"), main, mainWs, mainLog, "0"));
        add(e1, checkout(dir.resolve("co-e1"), e1, mainWs, mainLog, "0"));
        add(red, checkout(dir.resolve("co-red"), red, stack.workspace(redJob), redLog, "1"));
        add(e3, checkout(dir.resolve("co-e3"), e3, mainWs, mainLog + CRASH_LINE + "\n[INFO] BUILD FAILURE\n", "1"));
        add(crash, checkout(dir.resolve("co-crash"), crash, hasXml(crashWs) ? crashWs : mainWs, mainLog, "0"));
    }

    private void add(String sha, Path co) {
        targets.addAll(List.of("--sha", sha, "--bb-co", co.toString()));
    }

    private String emptyCommit(Path work, String branch) throws Exception {
        Shell.ok("git", "-C", work.toString(), "-c", "user.name=v", "-c", "user.email=v@v", "commit", "-q", "--allow-empty", "-m", branch);
        Shell.ok("git", "-C", work.toString(), "push", "-q", "origin", "HEAD:refs/heads/" + branch);
        return Shell.ok("git", "-C", work.toString(), "rev-parse", "HEAD").strip();
    }

    private static boolean hasXml(Path ws) throws Exception {
        try (Stream<Path> walk = Files.walk(ws)) {
            return walk.filter(file -> !file.startsWith(ws.resolve(".m2-local")))
                       .anyMatch(file -> file.getFileName().toString().startsWith("TEST-") && file.toString().endsWith(".xml"));
        }
    }

    /// A bb checkout of `sha` whose last bb-build left `.bb/` (with `rc`) and the XML of `workspace`.
    private Path checkout(Path co, String sha, Path workspace, String buildLog, String rc) throws Exception {
        Shell.ok("git", "clone", "-q", stack.fixture.origin().toString(), co.toString());
        Shell.ok("git", "-C", co.toString(), "checkout", "-q", sha);
        var bb = Files.createDirectories(co.resolve(".bb"));
        Files.writeString(bb.resolve("rc"), rc + "\n");
        Files.writeString(bb.resolve("args"), "clean install");
        Files.writeString(bb.resolve("start"), "1760000000\n");
        Files.writeString(bb.resolve("end"), "1760000060\n");
        Files.writeString(bb.resolve("build.log"), buildLog);
        Files.writeString(bb.resolve("run.marker"), "");
        Files.setLastModifiedTime(bb.resolve("run.marker"), FileTime.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        try (Stream<Path> walk = Files.walk(workspace)) {
            for (var xml : walk.filter(file -> !file.startsWith(workspace.resolve(".m2-local")))
                               .filter(file -> file.getFileName().toString().startsWith("TEST-") && file.toString().endsWith(".xml"))
                               .toList()) {
                var target = co.resolve(workspace.relativize(xml).toString());
                Files.createDirectories(target.getParent());
                Files.copy(xml, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return co;
    }

    @Test
    @Order(1)
    void live_crashOnEitherSide_isNotComparable_andLeftOutOfTheCount_andTheReportIsIncomplete() throws Exception {
        var argv = new ArrayList<>(args());
        argv.addAll(targets);
        var exit = T1Harness.run(argv, Map.of("HOME", stack.cliHome().toString()));
        var report = Files.readString(out.resolve("T1-" + DATE + ".md"));

        assertReport(report);
        assertThat(exit).as(report).isEqualTo(T1Harness.EXIT_INCOMPLETE);
    }

    @Test
    @Order(2)
    void rerun_fromTheSnapshots_judgesTheSame() throws Exception {
        var exit = T1Harness.run(args("--rerun"), Map.of("HOME", stack.cliHome().toString()));
        var report = Files.readString(out.resolve("T1-" + DATE + "-rerun.md"));

        assertReport(report);
        assertThat(exit).as(report).isEqualTo(T1Harness.EXIT_INCOMPLETE);
    }

    /// F3: the explanation a reviewer wrote into the first re-run's report survives a second re-run, which writes
    /// alongside.
    @Test
    @Order(3)
    void rerun_again_writesAlongside_andNeverOverAWrittenExplanation() throws Exception {
        var first = out.resolve("T1-" + DATE + "-rerun.md");
        var explained = Files.readString(first).replaceFirst(Pattern.quote(TrustReport.UNWRITTEN), EXPLANATION);
        Files.writeString(first, explained);

        var exit = T1Harness.run(args("--rerun"), Map.of("HOME", stack.cliHome().toString()));

        assertThat(Files.readString(first)).isEqualTo(explained).contains(EXPLANATION);
        assertReport(Files.readString(out.resolve("T1-" + DATE + "-rerun.2.md")));
        assertThat(exit).isEqualTo(T1Harness.EXIT_INCOMPLETE);
    }

    private void assertReport(String report) {
        assertThat(report).as(report)
                          .contains("## " + main + " ✔", "## " + e1 + " ✔", "## " + red + " ✔")
                          .contains("## " + e3 + " " + TrustReport.NOT_COMPARABLE)
                          .contains("## " + crash + " " + TrustReport.NOT_COMPARABLE)
                          .contains("(5 SHAs, 3 passed, 2 not comparable")
                          .contains("**Overall: INCOMPLETE**")
                          .doesNotContain("Overall: PASS")
                          .doesNotContain("T1 needs a comparable red SHA")
                          .contains("- **2 crash explanation(s) unwritten:**")
                          .contains("| failed classes | 1 | 1 | ✔ |")
                          .contains("a fork crash in bb's build log;")
                          .contains("a fork crash in temper's log tail;")
                          .contains("fork crash in bb's build log:", CRASH_LINE)
                          .contains("fork crash in temper's log tail:")
                          .contains("- missing: " + PROBE)
                          .contains("each missing class (" + PROBE + ")");
    }

    private List<String> args(String... more) {
        var java = Path.of(System.getProperty("java.home"), "bin", "java");
        return Stream.concat(Stream.of("--controller", stack.url(),
                                       "--token-file", stack.cliHome().resolve(".config/temper/token").toString(),
                                       "--temper", "env HOME=" + stack.cliHome() + " " + java + " -jar " + System.getProperty("temper.cli.jar"),
                                       "--bb-report", bbReport.toString(),
                                       "--out", out.toString(),
                                       "--date", DATE,
                                       "--planted", "fx.includes.RedProbeTest",
                                       "--queue-hours", "1",
                                       "--run-hours", "1"),
                             Stream.of(more))
                     .toList();
    }
}
