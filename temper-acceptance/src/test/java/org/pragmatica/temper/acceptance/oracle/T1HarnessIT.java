package org.pragmatica.temper.acceptance.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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

import static org.assertj.core.api.Assertions.assertThat;

/// The T1 harness itself (m1-design §8.1, §9.3 "before trusting it"), docker tier, end to end against the real
/// controller and worker: a bb checkout is made from the fixture at `main` with the report XML of temper's own Gate
/// of `main` and a `.bb/` as bb-build writes it; the harness then runs steps 1-5 with the archived bigboy bb-report
/// (preflight with git and find, the snapshot, bb-report on it, the Gate through the CLI, the controller's record,
/// rows, manifest and evidence `log.tail`, the comparison, the report file).
///
/// **Instrument validation (§9.3):** the snapshot is then doctored (one `<testcase>` removed from one bb XML, one
/// failure flipped in another) and step 4 is re-run from it: the report must say ✘ on exactly the count and the
/// failed-set checks, ✔ on EMPTY and the manifest.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class T1HarnessIT {
    private static final String DATE = "2026-10-14";
    private AcceptanceStack stack;
    private String sha;
    private Path out;
    private Path bbReport;
    private Path checkout;

    @BeforeAll
    void checkout() throws Exception {
        stack = AcceptanceStack.docker();
        var job = stack.gate("main");
        sha = stack.fixture.sha("main");
        var dir = Files.createDirectories(stack.dir.resolve("t1"));
        out = dir.resolve("out");
        bbReport = dir.resolve("bb-report");
        Files.copy(Path.of(getClass().getResource("/bb-report/bb-report.archived").toURI()), bbReport, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(bbReport, PosixFilePermissions.fromString("rwx------"));
        checkout = bbCheckout(dir.resolve("co"), stack.workspace(job), stack.unitDir(job, "gate").resolve("log"));
    }

    /// A bb checkout of `sha` whose last bb-build left `.bb/` and the XML of temper's own build of the same SHA.
    private Path bbCheckout(Path co, Path workspace, Path log) throws Exception {
        Shell.ok("git", "clone", "-q", stack.fixture.origin().toString(), co.toString());
        Shell.ok("git", "-C", co.toString(), "checkout", "-q", sha);
        var bb = Files.createDirectories(co.resolve(".bb"));
        Files.writeString(bb.resolve("rc"), "0\n");
        Files.writeString(bb.resolve("args"), "clean install");
        Files.writeString(bb.resolve("start"), "1760000000\n");
        Files.writeString(bb.resolve("end"), "1760000060\n");
        Files.copy(log, bb.resolve("build.log"));
        Files.writeString(bb.resolve("run.marker"), "");
        Files.setLastModifiedTime(bb.resolve("run.marker"), FileTime.from(Instant.now().minus(1, ChronoUnit.DAYS)));
        try (Stream<Path> walk = Files.walk(workspace)) {
            for (var xml : walk.filter(file -> !file.startsWith(workspace.resolve(".m2-local")))
                               .filter(file -> file.getFileName().toString().startsWith("TEST-") && file.toString().endsWith(".xml"))
                               .toList()) {
                var target = co.resolve(workspace.relativize(xml).toString());
                Files.createDirectories(target.getParent());
                Files.copy(xml, target);
            }
        }
        return co;
    }

    @Test
    @Order(1)
    void live_judgesTheShaPassed() throws Exception {
        var exit = T1Harness.run(args("--sha", sha, "--bb-co", checkout.toString()), Map.of("HOME", stack.cliHome().toString()));
        var report = Files.readString(out.resolve("T1-" + DATE + ".md"));

        assertThat(report).as(report).contains("## " + sha + " ✔", "- preflight ✔");
        assertThat(exit).as("one SHA: T1 needs >= 3, so the overall verdict is FAIL").isEqualTo(T1Harness.EXIT_FAIL);
        assertThat(report).contains("**Overall: FAIL** (1 SHAs, 1 passed");
        assertThat(Files.readString(out.resolve("T1-" + DATE + "/" + sha.substring(0, 7) + "/bb-report.exit")).strip()).isEqualTo("0");
    }

    @Test
    @Order(2)
    void rerun_onADoctoredSnapshot_failsExactlyTheDoctoredChecks() throws Exception {
        var bb = out.resolve("T1-" + DATE + "/" + sha.substring(0, 7) + "/bb");
        var beta = find(bb, "TEST-fx.includes.BetaTest.xml");
        var fast = find(bb, "TEST-fx.groups.FastTest.xml");
        Files.writeString(beta, Files.readString(beta).replaceFirst("(?s)<testcase .*?(/>|</testcase>)", ""));
        Files.writeString(fast, Files.readString(fast).replaceFirst("(<testcase [^>]*?)/>", "$1><failure message=\"doctored\">x</failure></testcase>"));

        var exit = T1Harness.run(args("--rerun"), Map.of("HOME", stack.cliHome().toString()));
        var report = Files.readString(out.resolve("T1-" + DATE + "-rerun.md"));

        assertThat(exit).isEqualTo(T1Harness.EXIT_FAIL);
        assertThat(report).as(report).contains("## " + sha + " ✘");
        assertThat(line(report, "| `<testcase>` count |")).endsWith("✘ |");
        assertThat(line(report, "| failed classes |")).endsWith("✘ |");
        assertThat(line(report, "| EMPTY |")).endsWith("✔ |");
        assertThat(line(report, "| manifest |")).endsWith("✔ |");
        assertThat(report).contains("- failed only in bb: fx.groups.FastTest", "only temper: fx.includes.BetaTest#beta passed");
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

    private static Path find(Path root, String name) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(file -> file.getFileName().toString().equals(name)).findFirst().orElseThrow();
        }
    }

    private static String line(String report, String prefix) {
        return report.lines().filter(line -> line.startsWith(prefix)).findFirst().orElseThrow(() -> new AssertionError("no line " + prefix));
    }
}
