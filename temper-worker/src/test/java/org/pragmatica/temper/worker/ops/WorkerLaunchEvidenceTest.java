package org.pragmatica.temper.worker.ops;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `worker-launch evidence <job> <unit> small|full` (m1-design §3.2, maintainer ruling M1-2): read-only `tar -czf -` of
/// one unit directory on stdout. A path escape and a symbolic link anywhere from `~/.temper` down are refused (64),
/// a missing directory is 66, the size is capped (65), tar never follows a link, and no environment is printed.
/// Runs the script with the host's bash and tools (BSD on macOS, GNU on Linux); `WorkerLaunchLinuxDockerIT` runs it
/// on Linux too.
class WorkerLaunchEvidenceTest {
    static final Path SCRIPT = Path.of("..", "ops", "worker-launch")
                                   .toAbsolutePath()
                                   .normalize();
    static final String SECRET = "s3cr3t-env-value";
    static final String TARGET = "TARGET-OUTSIDE-THE-UNIT";

    @TempDir
    Path home;

    private Path unit;

    record Outcome(int rc, byte[] stdout, String stderr) {}

    @BeforeEach
    void setUp() throws IOException {
        unit = unitTree(home, home.resolve("outside.txt"));
    }

    /// `~/.temper/jobs/7/units/main` with the small set, the full log, and a symlink to a file outside.
    static Path unitTree(Path home, Path outsideFile) throws IOException {
        var unit = Files.createDirectories(home.resolve(".temper/jobs/7/units/main"));

        Files.createDirectories(unit.resolve(".temper"));
        Files.writeString(unit.resolve(".temper/marker"), "1760356800000000000");
        Files.createDirectories(unit.resolve("reports/core/target/surefire-reports"));
        Files.writeString(unit.resolve("reports/core/target/surefire-reports/TEST-a.xml"), "<testsuite/>");
        Files.createDirectories(unit.resolve("threaddumps"));
        Files.writeString(unit.resolve("threaddumps/1.txt"), "Full thread dump");
        Files.createDirectories(unit.resolve("manifest"));
        Files.writeString(unit.resolve("manifest/manifest.json"), "{}");
        Files.writeString(unit.resolve("log.tail"), "tail");
        Files.writeString(unit.resolve("log"), "the whole log");
        Files.writeString(outsideFile, TARGET);
        Files.createSymbolicLink(unit.resolve(".temper/link"), outsideFile);
        return unit;
    }

    static Outcome launch(Path home, String command) throws IOException, InterruptedException {
        var builder = HermeticProcess.builder("bash", SCRIPT.toString());

        builder.environment()
               .clear();
        builder.environment()
               .put("HOME", home.toString());
        builder.environment()
               .put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
        builder.environment()
               .put("TEMPER_TEST_SECRET", SECRET);
        builder.environment()
               .put("SSH_ORIGINAL_COMMAND", command);

        var process = builder.start();

        process.getOutputStream()
               .close();

        var stdout = process.getInputStream()
                            .readAllBytes();
        var stderr = new String(process.getErrorStream()
                                       .readAllBytes(), StandardCharsets.UTF_8);

        return new Outcome(process.waitFor(), stdout, stderr);
    }

    /// The archive's member names, from `tar -tzf -`.
    static List<String> members(byte[] tarball) throws IOException, InterruptedException {
        var process = HermeticProcess.builder("tar", "-tzf", "-").start();

        try (var in = process.getOutputStream()) {
            in.write(tarball);
        }

        var names = new String(process.getInputStream()
                                      .readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).isZero();
        return names.lines()
                    .map(name -> name.endsWith("/")
                                 ? name.substring(0, name.length() - 1)
                                 : name)
                    .toList();
    }

    static String gunzipped(byte[] tarball) throws IOException {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(tarball))) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private Outcome launch(String command) throws IOException, InterruptedException {
        return launch(home, command);
    }

    @Test
    void small_streamsTheFixedSet_asGzip_withoutFollowingALink() throws Exception {
        var outcome = launch("evidence 7 main small");

        assertThat(outcome.rc()).as(outcome.stderr())
                                .isZero();
        assertThat(Arrays.copyOf(outcome.stdout(), 2)).containsExactly(0x1f, 0x8b);
        assertThat(members(outcome.stdout())).contains(".temper/marker",
                                                       ".temper/link",
                                                       "reports/core/target/surefire-reports/TEST-a.xml",
                                                       "threaddumps/1.txt",
                                                       "manifest/manifest.json",
                                                       "log.tail")
                                             .doesNotContain("log");
        assertThat(gunzipped(outcome.stdout())).doesNotContain(TARGET)
                                               .doesNotContain(SECRET);
        assertThat(outcome.stderr()).doesNotContain(SECRET);
    }

    @Test
    void full_streamsTheWholeDirectory() throws Exception {
        var outcome = launch("evidence 7 main full");

        assertThat(outcome.rc()).as(outcome.stderr())
                                .isZero();
        assertThat(members(outcome.stdout())).contains("./log", "./log.tail", "./.temper/marker");
        assertThat(gunzipped(outcome.stdout())).doesNotContain(TARGET);
    }

    @Test
    void missingMembers_areSkipped() throws Exception {
        Files.delete(unit.resolve("threaddumps/1.txt"));
        Files.delete(unit.resolve("threaddumps"));
        Files.delete(unit.resolve("log.tail"));

        var outcome = launch("evidence 7 main small");

        assertThat(outcome.rc()).as(outcome.stderr())
                                .isZero();
        assertThat(members(outcome.stdout())).contains(".temper/marker")
                                             .doesNotContain("log.tail", "threaddumps");
    }

    @Test
    void malformedRequests_areRefused() throws Exception {
        for (var command : List.of("evidence",
                                   "evidence 7 main",
                                   "evidence 7 main small extra",
                                   "evidence ../ main small",
                                   "evidence 7 ../main small",
                                   "evidence 7 .. small",
                                   "evidence 0 main small",
                                   "evidence 07 main small",
                                   "evidence 7 Main small",
                                   "evidence 7 main medium",
                                   "evidence 7 main$(id) small",
                                   "evidence  7 main small",
                                   "evidence 12345678901234567890 main small")) {
            var outcome = launch(command);

            assertThat(outcome.rc()).as(command)
                                    .isEqualTo(64);
            assertThat(outcome.stdout()).as(command)
                                        .isEmpty();
        }
    }

    @Test
    void aMissingUnitDirectory_is66() throws Exception {
        assertThat(launch("evidence 7 other small").rc()).isEqualTo(66);
        assertThat(launch("evidence 8 main small").rc()).isEqualTo(66);
    }

    /// The path escape: a unit directory that is a symlink to a directory outside `~/.temper/jobs`.
    @Test
    void aSymlinkedUnitDirectory_isRefused() throws Exception {
        var elsewhere = Files.createDirectories(home.resolve("elsewhere"));

        Files.writeString(elsewhere.resolve("stolen"), TARGET);
        Files.createSymbolicLink(home.resolve(".temper/jobs/7/units/escape"), elsewhere);

        var outcome = launch("evidence 7 escape full");

        assertThat(outcome.rc()).isEqualTo(64);
        assertThat(outcome.stdout()).isEmpty();
    }

    /// A symlink higher up (the job directory) is refused even when it points inside `~/.temper/jobs`.
    @Test
    void aSymlinkedJobDirectory_isRefused_evenPointingInsideJobs() throws Exception {
        Files.createSymbolicLink(home.resolve(".temper/jobs/9"), home.resolve(".temper/jobs/7"));

        assertThat(launch("evidence 9 main small").rc()).isEqualTo(64);
    }

    @Test
    void aSymlinkedUnitsDirectory_isRefused() throws Exception {
        var job = Files.createDirectories(home.resolve(".temper/jobs/10"));

        Files.createSymbolicLink(job.resolve("units"), home.resolve(".temper/jobs/7/units"));

        assertThat(launch("evidence 10 main small").rc()).isEqualTo(64);
    }

    /// Over the cap (apparent size, so a sparse file counts in full): 65, and nothing on stdout.
    @Test
    void evidenceOverTheCap_is65_andSendsNothing() throws Exception {
        try (var file = new RandomAccessFile(unit.resolve("reports/huge.xml")
                                                 .toFile(), "rw")) {
            file.setLength(256L * 1024 * 1024 + 1);
        }

        var small = launch("evidence 7 main small");

        assertThat(small.rc()).as(small.stderr())
                              .isEqualTo(65);
        assertThat(small.stdout()).isEmpty();

        Files.delete(unit.resolve("reports/huge.xml"));
        try (var file = new RandomAccessFile(unit.resolve("huge.bin")
                                                 .toFile(), "rw")) {
            file.setLength(4L * 1024 * 1024 * 1024 + 1);
        }

        assertThat(launch("evidence 7 main small").rc()).as("huge.bin is outside the small set")
                                                         .isZero();
        assertThat(launch("evidence 7 main full").rc()).isEqualTo(65);
    }

    /// The size counts regular files, never what a link points at; `full` has its own, larger cap.
    @Test
    void theCap_countsNoLinkTarget_andFullHasTheLargerCap() throws Exception {
        sparse(home.resolve("big.bin"), 256L * 1024 * 1024 + 1);
        Files.createSymbolicLink(unit.resolve("reports/big-link"), home.resolve("big.bin"));

        var small = launch("evidence 7 main small");

        assertThat(small.rc()).as(small.stderr())
                              .isZero();

        Files.delete(unit.resolve("reports/big-link"));
        sparse(unit.resolve("big.bin"), 256L * 1024 * 1024 + 1);

        var full = launch("evidence 7 main full");

        assertThat(full.rc()).as(full.stderr())
                             .isZero();
    }

    private static void sparse(Path file, long length) throws IOException {
        try (var handle = new RandomAccessFile(file.toFile(), "rw")) {
            handle.setLength(length);
        }
    }
}
