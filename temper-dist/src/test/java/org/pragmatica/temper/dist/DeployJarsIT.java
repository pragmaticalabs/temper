package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/deploy-jars.sh` (the deploy runbook) on this build's dist jars: the runnable `-all` jars land in
/// `~/.temper/dist/` under the names the service and `[worker] jar` read; a thin module jar (no `Main-Class`, F5) or
/// the two jars swapped is refused before anything is copied (M1 WP7 verification A6).
///
/// The launcher (M1 pre-live verification F1, the verifier's `deploy-dryrun.sh` as a test): the home starts with
/// bigboy's live M0 launcher (`7ef7836`, a test resource), which answers `evidence` with 64; after the deploy the
/// pinned `ops/worker-launch` answers it, the old one is kept, and a launcher other than the pinned one is refused.
///
/// The judge (m2-design §1.7, ruling R-M2-10): both jars must name the same clean 40-hex commit. This build's own jars
/// name whatever `temper.judge` it was built with (`unjudged` for a plain build), so every deploy here uses copies of
/// them whose version resources were rewritten to a fixture judge, as `ops/build-dist.sh` would have written it.
/// Probes: drop the `-dirty` case → [#deploy_refusesAJudgeThatIsNotOneCleanCommit_andCopiesNothing]'s dirty row is still
/// refused by the 40-hex check, with the wrong reason (red on the message); compare one jar only →
/// [#deploy_refusesTwoDifferentJudges_andCopiesNothing] copies the pair (red).
class DeployJarsIT {
    private static final String VERSION = System.getProperty("project.version");
    private static final Path DIST = Path.of(System.getProperty("dist.dir"));
    private static final Path OPS = Path.of("..", "ops").toAbsolutePath().normalize();
    private static final Path SCRIPT = OPS.resolve("deploy-jars.sh");
    private static final Path CONTROLLER = DIST.resolve("temper-controller-" + VERSION + "-all.jar");
    private static final Path WORKER = DIST.resolve("temper-worker-" + VERSION + "-all.jar");
    /// The pinned launcher: `ops/worker-launch` at `3f9c081` (the deploy runbook; deployed only with the maintainer's approval).
    private static final String PINNED_LAUNCHER = "c0fb07828176f219e7883c3d88f64f4210c097e89ab03b0003efcc952ad4976c";
    private static final String M0_LAUNCHER = "545e6a08361013efe990fef6a1d5db321ad8c706844e8887d26f668b7a913f9e";
    private static final String CONTROLLER_VERSION = "org/pragmatica/temper/controller/controller-version.properties";
    private static final String WORKER_VERSION = "org/pragmatica/temper/worker/worker-version.properties";
    private static final String JUDGE = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    private static final String OTHER_JUDGE = "5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f80";

    @TempDir
    static Path fixtures;
    /// This build's jars, judged [#JUDGE].
    private static Path judgedController;
    private static Path judgedWorker;

    @BeforeAll
    static void judgedJars() throws IOException {
        judgedController = judged(CONTROLLER, CONTROLLER_VERSION, "judge=" + JUDGE, fixtures.resolve("controller.jar"));
        judgedWorker = judged(WORKER, WORKER_VERSION, "judge=" + JUDGE, fixtures.resolve("worker.jar"));
    }

    record Outcome(int rc, String output) {}

    @Test
    void deploy_placesBothRunnableJars_underTheirServiceNames(@TempDir Path home) throws Exception {
        var outcome = deploy(SCRIPT, home, judgedController, judgedWorker);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(Files.mismatch(home.resolve(".temper/dist/temper-controller.jar"), judgedController)).isEqualTo(-1L);
        assertThat(Files.mismatch(home.resolve(".temper/dist/temper-worker.jar"), judgedWorker)).isEqualTo(-1L);
        assertThat(outcome.output()).containsPattern("temper-controller.jar sha256 [0-9a-f]{64} from ")
                                    .containsPattern("temper-worker.jar sha256 [0-9a-f]{64} from ")
                                    .contains("judge " + JUDGE + " (both jars)");
        assertThat(home.resolve(".temper/dist/temper-worker.jar.partial")).doesNotExist();
    }

    /// bigboy as it is: the M0 launcher refuses `evidence` (64). The deploy installs the pinned launcher, which
    /// answers it (0, a tarball of the unit), keeps the M0 one as `.bak-<sha256>`, and prints both sha256s.
    @Test
    void deploy_replacesTheM0Launcher_withThePinnedOne_thatAnswersEvidence(@TempDir Path home) throws Exception {
        var launcher = home.resolve(".temper/bin/worker-launch");
        Files.createDirectories(launcher.getParent());
        Files.copy(Path.of(getClass().getResource("/worker-launch-7ef7836").toURI()), launcher);
        Files.writeString(Files.createDirectories(home.resolve(".temper/jobs/42/units/gate")).resolve("log.tail"), "x\n");
        assertThat(sha256(launcher)).isEqualTo(M0_LAUNCHER);
        assertThat(sha256(OPS.resolve("worker-launch"))).as("ops/worker-launch is the pinned launcher").isEqualTo(PINNED_LAUNCHER);
        var before = evidence(home);
        assertThat(before.rc()).as(before.output()).isEqualTo(64);

        var outcome = deploy(SCRIPT, home, judgedController, judgedWorker);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(sha256(launcher)).isEqualTo(PINNED_LAUNCHER);
        assertThat(sha256(launcher.resolveSibling("worker-launch.bak-" + M0_LAUNCHER))).isEqualTo(M0_LAUNCHER);
        assertThat(outcome.output()).contains("worker-launch was sha256 " + M0_LAUNCHER)
                                    .contains("worker-launch sha256 " + PINNED_LAUNCHER + " (3f9c081)");
        assertThat(evidence(home).rc()).as("the pinned launcher answers evidence").isZero();
    }

    /// N2: the jars the deploy replaces are kept as `<target>.bak-<sha256>`, the rollback's old jars; a re-run of the
    /// same deploy keeps nothing more (the targets already are the new jars).
    @Test
    void deploy_keepsTheReplacedJars_asBackups_once(@TempDir Path home) throws Exception {
        var dist = Files.createDirectories(home.resolve(".temper/dist"));
        var oldController = Files.writeString(dist.resolve("temper-controller.jar"), "the M0 controller jar\n");
        var oldWorker = Files.writeString(dist.resolve("temper-worker.jar"), "the M0 worker jar\n");
        var controllerSha = sha256(oldController);
        var workerSha = sha256(oldWorker);

        var outcome = deploy(SCRIPT, home, judgedController, judgedWorker);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(dist.resolve("temper-controller.jar.bak-" + controllerSha)).hasContent("the M0 controller jar");
        assertThat(dist.resolve("temper-worker.jar.bak-" + workerSha)).hasContent("the M0 worker jar");
        assertThat(outcome.output()).contains("temper-controller.jar was sha256 " + controllerSha)
                                    .contains("temper-worker.jar was sha256 " + workerSha);
        assertThat(Files.mismatch(dist.resolve("temper-controller.jar"), judgedController)).isEqualTo(-1L);

        var again = deploy(SCRIPT, home, judgedController, judgedWorker);

        assertThat(again.rc()).as(again.output()).isZero();
        assertThat(again.output()).doesNotContain("jar was sha256");
        try (var listed = Files.list(dist)) {
            assertThat(listed.filter(path -> path.getFileName().toString().contains(".bak-"))).hasSize(2);
        }
    }

    /// N6: a `~/.temper` or `~/.temper/jobs` that is a symbolic link makes the pinned launcher refuse every `evidence`
    /// (64), so the deploy refuses it before copying anything.
    @Test
    void deploy_refusesALinkedTemperOrJobs_andCopiesNothing(@TempDir Path dir) throws Exception {
        var elsewhere = Files.createDirectories(dir.resolve("elsewhere"));
        var linkedTemper = Files.createDirectories(dir.resolve("a"));
        Files.createSymbolicLink(linkedTemper.resolve(".temper"), elsewhere);
        var linkedJobs = Files.createDirectories(dir.resolve("b/.temper")).getParent();
        Files.createSymbolicLink(linkedJobs.resolve(".temper/jobs"), elsewhere);

        for (var home : List.of(linkedTemper, linkedJobs)) {
            var outcome = deploy(SCRIPT, home, judgedController, judgedWorker);

            assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
            assertThat(outcome.output()).contains("is a symbolic link").contains("nothing copied");
            assertThat(home.resolve(".temper/dist")).doesNotExist();
            assertThat(home.resolve(".temper/bin")).doesNotExist();
        }
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed).as("the link target is untouched").isEmpty();
        }
    }

    /// A launcher other than the pinned one (an unapproved change to ops/worker-launch) is refused, and nothing is
    /// copied, not even the valid jars.
    @Test
    void deploy_refusesALauncherThatIsNotThePinnedOne_andCopiesNothing(@TempDir Path dir) throws Exception {
        var ops = Files.createDirectories(dir.resolve("ops"));
        var script = Files.copy(SCRIPT, ops.resolve("deploy-jars.sh"));
        Files.writeString(ops.resolve("worker-launch"), Files.readString(OPS.resolve("worker-launch")) + "# changed\n");
        var home = Files.createDirectories(dir.resolve("home"));
        var outcome = deploy(script, home, judgedController, judgedWorker);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("not the pinned " + PINNED_LAUNCHER);
        assertThat(home.resolve(".temper")).doesNotExist();
    }

    /// The deploy mistake the F5 rename makes likely: the old (now thin) name. Refused, and the controller jar,
    /// although valid, is not copied either.
    @Test
    void deploy_refusesAThinJar_andCopiesNothing(@TempDir Path home) throws Exception {
        var thin = thinJar(home.resolve("temper-worker-" + VERSION + ".jar"));
        var outcome = deploy(SCRIPT, home, judgedController, thin);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("Main-Class 'none'");
        assertThat(home.resolve(".temper")).doesNotExist();
    }

    @Test
    void deploy_refusesTheJarsSwapped_andCopiesNothing(@TempDir Path home) throws Exception {
        var outcome = deploy(SCRIPT, home, judgedWorker, judgedController);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("expected org.pragmatica.temper.controller.Main");
        assertThat(home.resolve(".temper")).doesNotExist();
    }

    /// m2-design §1.7: a missing judge line, `unjudged` (a plain `mvn install`, this very build in CI), a `-dirty` build,
    /// a short or upper-case commit, in either jar: refused with the reason, nothing copied.
    @ParameterizedTest(name = "{0} judge ''{1}'' is refused: {2}")
    @CsvSource(delimiter = '|',
               value = {"controller | <none> | has no judge= line",
                        "worker     | <none> | has no judge= line",
                        "controller | unjudged | is unjudged",
                        "worker     | unjudged | is unjudged",
                        "controller | 1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d-dirty | built from a dirty tree",
                        "worker     | 1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d-dirty | built from a dirty tree",
                        "controller | 1a2b3c4 | not a 40-hex commit",
                        "controller | 1A2B3C4D5E6F708192A3B4C5D6E7F8091A2B3C4D | not a 40-hex commit",
                        "worker     | pre-judge | not a 40-hex commit",
                        "controller | ${temper.judge} | not a 40-hex commit"})
    void deploy_refusesAJudgeThatIsNotOneCleanCommit_andCopiesNothing(String jar, String judge, String reason,
                                                                     @TempDir Path home) throws Exception {
        var line = judge.equals("<none>")
                   ? ""
                   : "judge=" + judge;
        var controller = jar.equals("controller")
                         ? judged(CONTROLLER, CONTROLLER_VERSION, line, home.resolve("c.jar"))
                         : judgedController;
        var worker = jar.equals("worker")
                     ? judged(WORKER, WORKER_VERSION, line, home.resolve("w.jar"))
                     : judgedWorker;
        var outcome = deploy(SCRIPT, home, controller, worker);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains(reason).contains("nothing copied");
        assertThat(home.resolve(".temper")).doesNotExist();
    }

    /// Two builds' jars (each clean on its own): refused, nothing copied. The worker that runs is the jar deployed here,
    /// so equal judges in both jars are what lets the handshake carry no judge (§1.7).
    @Test
    void deploy_refusesTwoDifferentJudges_andCopiesNothing(@TempDir Path home) throws Exception {
        var otherWorker = judged(WORKER, WORKER_VERSION, "judge=" + OTHER_JUDGE, home.resolve("w.jar"));
        var outcome = deploy(SCRIPT, home, judgedController, otherWorker);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(3);
        assertThat(outcome.output()).contains("the controller jar has judge " + JUDGE + ", the worker jar " + OTHER_JUDGE)
                                    .contains("nothing copied");
        assertThat(home.resolve(".temper")).doesNotExist();
    }

    /// The judge line is read as the controller reads it (`ControllerVersion`): surrounding blanks are not part of it.
    @Test
    void deploy_readsAJudgeLineWithBlanks_asTheControllerDoes(@TempDir Path home) throws Exception {
        var controller = judged(CONTROLLER, CONTROLLER_VERSION, "  judge= " + JUDGE + " \r", home.resolve("c.jar"));
        var outcome = deploy(SCRIPT, home, controller, judgedWorker);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(outcome.output()).contains("judge " + JUDGE + " (both jars)");
    }

    /// A copy of `jar` at `target` whose version resource is `version=<this build's version>` and then `judgeLine`
    /// (none when empty), as a build with that judge would have filtered it.
    private static Path judged(Path jar, String resource, String judgeLine, Path target) throws IOException {
        Files.copy(jar, target);
        try (var zip = FileSystems.newFileSystem(target)) {
            Files.writeString(zip.getPath(resource),
                              "version=" + VERSION + "\n" + (judgeLine.isEmpty()
                                                             ? ""
                                                             : judgeLine + "\n"),
                              StandardCharsets.UTF_8);
        }
        return target;
    }

    private static Path thinJar(Path path) throws IOException {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (OutputStream out = Files.newOutputStream(path); var jar = new JarOutputStream(out, manifest)) {
            jar.flush();
        }
        return path;
    }

    private static Outcome deploy(Path script, Path home, Path controller, Path worker) throws IOException, InterruptedException {
        return ran(HermeticProcess.builder(List.of("bash", script.toString(), controller.toString(), worker.toString())), home);
    }

    /// The installed launcher asked `evidence 42 gate small` as the live key's forced command asks it.
    private static Outcome evidence(Path home) throws IOException, InterruptedException {
        var builder = HermeticProcess.builder(List.of("bash", home.resolve(".temper/bin/worker-launch").toString()));
        builder.environment().remove("TEMPER_WORKER_HOME");
        builder.environment().put("SSH_ORIGINAL_COMMAND", "evidence 42 gate small");
        return ran(builder, home);
    }

    private static Outcome ran(ProcessBuilder builder, Path home) throws IOException, InterruptedException {
        builder.redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);

        return new Outcome(process.waitFor(), output);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
