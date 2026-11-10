package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.temper.dist.DeploySandbox.Outcome;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/deploy/lib.sh` through the toy unit (rows P1-P21 and P36-P42).
/// Each test names its row; the mutation that must turn it red is the one in the positive-control table, listed
/// beside the assertion that catches it. A refusal is pinned by its stderr message as well as by rc 3, because a
/// mutated check would otherwise be masked by the plan digest or another check refusing for a different reason.
class DeployLibIT {
    private static final String COMMIT = "1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d";
    private static final String OLD_CONTENT = "toy build zero\n";
    private static final String NEW_CONTENT = "toy build one\n";
    private static final Pattern RECORD = Pattern.compile(
        "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z event=(install|rollback) unit=[a-z-]+ commit=[0-9a-f]{40} sha256=([0-9a-f]{64}) "
        + "outcome=(installed|unchanged|health-failed|partial|rolled-back|rollback-failed|kept-forward-only) rollback=([0-9a-f]{64}|absent) "
        + "schema=(V[0-9]+|V[0-9]+->V[0-9]+|n/a|unknown) dir=([^ ]+)$");
    private static final String BANNER_FIRST = "=".repeat(20) + " ROLLBACK FAILED " + "=".repeat(20);
    private static final String BANNER_LAST = "=".repeat(57);

    @TempDir
    Path dir;
    private DeploySandbox sb;
    private String oldSha;
    private String newSha;

    @BeforeEach
    void rig() throws Exception {
        sb = new DeploySandbox(dir);
        oldSha = sb.live(OLD_CONTENT);
        newSha = sb.stage(COMMIT, NEW_CONTENT);
    }

    @AfterEach
    void close() {
        sb.close();
    }

    private Path script() {
        return sb.stageDir(COMMIT).resolve("deploy-toy.sh");
    }

    private Outcome install() throws Exception {
        return sb.install(COMMIT, oldSha, newSha);
    }

    private static String flipLast(String hex) {
        return hex.substring(0, hex.length() - 1) + (hex.endsWith("0") ? "1" : "0");
    }

    private int count(String needle) throws Exception {
        return (int) sb.shimLog().lines().filter(l -> l.equals(needle)).count();
    }

    private void assertUntouched(Map<String, String> before) throws Exception {
        assertThat(sb.snapshot()).isEqualTo(before);
        assertThat(sb.deploysLog()).isEmpty();
        assertThat(sb.shimLog()).doesNotContain("systemctl --user restart").doesNotContain("systemctl --user start").doesNotContain("systemctl --user stop");
    }

    private Outcome refusedInstall(String current, String fresh, String plan) throws Exception {
        return sb.run(script(), sb.root, "install", "--commit", COMMIT, "--expect-current", current, "--expect-new", fresh, "--expect-plan", plan);
    }

    // ------------------------------------------------------------------------------------ refusals (P1-P4)

    /// P1: compare only the first 12 characters of the set id.
    @Test
    void p1_expectCurrentOneDigitOff_refuses_andWritesNothing() throws Exception {
        var before = sb.snapshot();
        var out = refusedInstall(flipLast(oldSha), newSha, "0".repeat(64));
        assertThat(out.rc()).as(out.err()).isEqualTo(3);
        assertThat(out.err()).contains("--expect-current says " + flipLast(oldSha));
        assertUntouched(before);
    }

    /// P2: drop the `--expect-new` comparison.
    @Test
    void p2_expectNewNotTheStagedSet_refuses_andWritesNothing() throws Exception {
        var before = sb.snapshot();
        var out = refusedInstall(oldSha, flipLast(newSha), "0".repeat(64));
        assertThat(out.rc()).as(out.err()).isEqualTo(3);
        assertThat(out.err()).contains("--expect-new says " + flipLast(newSha));
        assertUntouched(before);
    }

    /// P3: skip the MANIFEST re-hash.
    @Test
    void p3_aStagedByteFlippedAfterStage_refuses_andWritesNothing() throws Exception {
        Files.writeString(sb.stageDir(COMMIT).resolve("files/toy.bin"), NEW_CONTENT + "x");
        var before = sb.snapshot();
        var out = refusedInstall(oldSha, newSha, "0".repeat(64));
        assertThat(out.rc()).as(out.err()).isEqualTo(3);
        assertThat(out.err()).contains("do not hash to their MANIFEST");
        assertUntouched(before);
    }

    /// P4: drop step 1 (the staged-copy check). The copy is complete, so only that check refuses it.
    @Test
    void p4_installFromOutsideTheStageDir_refuses() throws Exception {
        var elsewhere = sb.home.resolve("elsewhere");
        try (Stream<Path> files = Files.walk(sb.stageDir(COMMIT))) {
            for (var file : files.toList()) {
                var target = elsewhere.resolve(sb.stageDir(COMMIT).relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                    Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(file));
                }
            }
        }
        var dry = sb.run(elsewhere.resolve("deploy-toy.sh"), sb.root, "install", "--commit", COMMIT, "--expect-current", oldSha, "--expect-new", newSha, "--dry-run");
        assertThat(dry.rc()).as(dry.err()).isEqualTo(3);
        var out = sb.run(elsewhere.resolve("deploy-toy.sh"), sb.root, "install", "--commit", COMMIT, "--expect-current", oldSha, "--expect-new", newSha,
                         "--expect-plan", "0".repeat(64));
        assertThat(out.rc()).isEqualTo(3);
        assertThat(out.err()).contains("install runs the staged copy");
        assertThat(sb.liveFile()).hasContent(OLD_CONTENT.strip());
    }

    // --------------------------------------------------------------------------- health, rollback (P5-P8, P13, P16)

    /// P5: delete the restore loop of step 13.
    @Test
    void p5_healthFailsForNew_rollsBackAndRechecks() throws Exception {
        sb.behave(newSha, "500");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(DeploySandbox.sha256(Files.readAllBytes(sb.liveFile()))).isEqualTo(oldSha);
        assertThat(sb.liveFile().resolveSibling("toy.bin.bak-" + oldSha)).exists();
        assertThat(count("systemctl --user restart toy")).isEqualTo(2);
        assertThat(sb.get("/health")).isEqualTo(200);
        assertThat(out.err()).doesNotContain("ROLLBACK FAILED");
    }

    /// P6: map the re-check failure to 5.
    @Test
    void p6_rollbackRecheckFails_exits6_andRecordsRollbackFailed() throws Exception {
        sb.behave(newSha, "500");
        sb.behave(oldSha, "500");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(6);
        assertThat(sb.deploysLog().get(sb.deploysLog().size() - 1)).contains("event=rollback ").contains("outcome=rollback-failed");
    }

    /// P7: drop the step 12 branch.
    @Test
    void p7_schemaAdvanced_keepsTheNewSet_exits7() throws Exception {
        Files.writeString(sb.home.resolve("shim/migrates"), "");
        sb.behave(newSha, "500");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(7);
        assertThat(DeploySandbox.sha256(Files.readAllBytes(sb.liveFile()))).isEqualTo(newSha);
        assertThat(count("systemctl --user restart toy")).isEqualTo(1);
        assertThat(out.err()).contains("the schema stays at V5").contains("MigrationNotInBuild").contains("pre-install dump");
        assertThat(sb.deploysLog().get(1)).contains("event=rollback ").contains("outcome=kept-forward-only").contains("schema=V4->V5");
    }

    /// P8: treat `unknown` as advanced.
    @Test
    void p8_schemaReadFails_stillRollsBack_recordsUnknown() throws Exception {
        Files.writeString(sb.home.resolve("shim/schema-fails"), "");
        sb.behave(newSha, "500");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(sb.deploysLog().get(1)).contains("outcome=rolled-back").contains("schema=unknown");
    }

    /// P13: write a single `installed` line, or skip the rollback line.
    @Test
    void p13_rollback_writesExactlyTwoRecordLines() throws Exception {
        sb.behave(newSha, "500");
        var out = install();
        assertThat(out.rc()).isEqualTo(5);
        var lines = sb.deploysLog();
        assertThat(lines).hasSize(2).allSatisfy(l -> assertThat(l).matches(RECORD));
        assertThat(lines.get(0)).contains("event=install ").contains("outcome=health-failed").contains("rollback=" + oldSha).contains("sha256=" + newSha);
        assertThat(lines.get(1)).contains("event=rollback ").contains("outcome=rolled-back").contains("rollback=" + oldSha);
    }

    /// P16: drop `dl_settled`.
    @Test
    void p16_aCrashInTheSettleWindow_failsHealth_andRollsBack() throws Exception {
        Files.writeString(sb.home.resolve("shim/toy.crash"), "3\n");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(5);
        assertThat(out.out()).contains("settle");
        assertThat(DeploySandbox.sha256(Files.readAllBytes(sb.liveFile()))).isEqualTo(oldSha);
    }

    // ------------------------------------------------------------------------------- dry-run and stage (P9-P11, P19-P20)

    /// P9: make the executor ignore `--dry-run`.
    @Test
    void p9_installDryRun_changesNothing() throws Exception {
        var before = sb.snapshot();
        var out = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(out.rc()).as(out.err()).isZero();
        assertThat(out.out()).startsWith("plan-header v1 unit=toy ").contains("\nplan-sha256 ");
        assertThat(sb.snapshot()).isEqualTo(before);
        assertThat(sb.home.resolve(".deploy/lock")).doesNotExist();
        assertThat(sb.shimLog().lines().filter(l -> l.startsWith("systemctl"))).allMatch(l -> l.matches("systemctl --user (show|is-active) .*"));
    }

    /// P10: run step 3 before the dry-run exit.
    @Test
    void p10_stageDryRun_runsNoBuildAndWritesNothing() throws Exception {
        var repo = sb.repo();
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main(), "--dry-run");
        assertThat(out.rc()).as(out.err()).isZero();
        assertThat(out.out()).contains("stage-plan commit " + repo.main()).contains("live " + oldSha);
        assertThat(sb.shimLog()).doesNotContain("bb-temper");
        assertThat(sb.git(repo.work(), "branch", "--list", "deploy-stage/*")).isEmpty();
        assertThat(sb.stageDir(repo.main())).doesNotExist();
        assertThat(sb.home.resolve(".deploy/stage/toy").toFile().list()).containsExactly(COMMIT);
    }

    private void writeHosts(String text) throws Exception {
        Files.writeString(sb.home.resolve(".config/bb-temper/hosts"), text);
    }

    /// A hosts file saved with CRLF line ends gives the same owner install line as the LF one: no \r in the key path.
    @Test
    void stage_withACrlfHostConfig_printsTheSameInstallLineAsTheLfOne() throws Exception {
        writeHosts(TestHosts.TEXT.replace("\n", "\r\n"));
        var repo = sb.repo();
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(out.out()).contains("install ! ssh -i ~/" + TestHosts.KEY + " -o BatchMode=yes " + TestHosts.BIGBOY + " '~/.deploy/stage/toy/")
                             .doesNotContain("\r");
    }

    /// Two `key` lines are malformed even when they name the same path: refused (rc 3) naming the second line, before any build.
    @Test
    void stage_withARepeatedKeyLine_refusesNamingTheLine() throws Exception {
        writeHosts("key ~/" + TestHosts.KEY + "\nkey ~/" + TestHosts.KEY + "\nhost bigboy " + TestHosts.BIGBOY + " - - auto\n");
        var repo = sb.repo();
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("line 2: key is listed twice");
        assertThat(sb.shimLog()).doesNotContain("bb-temper");
    }

    /// P11: drop the `--into` root check.
    @Test
    void p11_stageLocal_writesOnlyUnderTheStageRoot() throws Exception {
        var kilnDist = Files.createDirectories(sb.home.resolve(".kiln/dist"));
        Files.writeString(kilnDist.resolve("kiln-service.jar"), "live kiln\n");
        var before = outsideStageRoot(sb.snapshot());
        sb.stage("2222222222222222222222222222222222222222", "toy build two\n");
        assertThat(outsideStageRoot(sb.snapshot())).isEqualTo(before);
        assertThat(sb.shimLog()).doesNotContain("systemctl");
        var out = sb.run(script(), sb.root, "stage-local", "--commit", COMMIT, "--src", sb.root.resolve("src-" + COMMIT).toString(), "--into", kilnDist.toString());
        assertThat(out.rc()).as(out.err()).isEqualTo(3);
        assertThat(out.err()).contains("is not under");
        assertThat(outsideStageRoot(sb.snapshot())).isEqualTo(before);
    }

    private static Map<String, String> outsideStageRoot(Map<String, String> snapshot) {
        return snapshot.entrySet().stream().filter(e -> !e.getKey().startsWith(".deploy/stage")).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /// P19: drop the `merge-base` check.
    @Test
    void p19_stageOfACommitNotOnOriginMain_refuses() throws Exception {
        var repo = sb.repo();
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.side());
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("is not on origin/main");
        assertThat(sb.shimLog()).doesNotContain("bb-temper");
    }

    /// P20: ignore wait's rc.
    @Test
    void p20_bbTemperWaitFails_exits4_andKeepsTheCheckout() throws Exception {
        var repo = sb.repo();
        Files.writeString(sb.home.resolve("shim/bb-wait-rc"), "1\n");
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(4);
        assertThat(sb.shimLog()).contains("bb-temper wait t-deploy-toy-").doesNotContain("bb-temper rm");
        assertThat(sb.stageDir(repo.main())).doesNotExist();
    }

    /// Not a table row: the whole of stage, end to end on the toy (checkout, build, collect, move, report, owner's line).
    @Test
    void stage_fullFlow_leavesAStagedDirAndPrintsTheOwnersLine() throws Exception {
        var repo = sb.repo();
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        var staged = DeploySandbox.sha256("toy build one\n");
        var stage = sb.stageDir(repo.main());
        assertThat(stage.resolve("files/toy.bin")).hasContent("toy build one");
        assertThat(stage.resolve("STAGE")).content().contains("set=" + staged).contains("commit=" + repo.main()).contains("src=t-deploy-toy-");
        assertThat(stage.resolve("SCRIPTS")).exists();
        assertThat(out.out()).contains("commit " + repo.main()).contains("judge none").contains("set " + staged).contains("live " + oldSha)
                             .containsPattern("plan-sha256 [0-9a-f]{64}")
                             .containsPattern("install ! ssh -i ~/" + TestHosts.KEY + " -o BatchMode=yes " + TestHosts.BIGBOY + " '~/.deploy/stage/toy/" + repo.main()
                                              + "/deploy-toy.sh install --commit " + repo.main() + " --expect-current " + oldSha + " --expect-new " + staged
                                              + " --expect-plan [0-9a-f]{64}'");
        assertThat(sb.shimLog()).contains("bb-temper rm t-deploy-toy-");
        assertThat(sb.git(repo.work(), "branch", "--list", "deploy-stage/*")).isEmpty();
        assertThat(sb.home.resolve(".toy/dist/toy.bin")).hasContent(OLD_CONTENT.strip());
    }

    /// bb-temper picks a build host itself when BB_HOST is unset; the lib must pin every call to DL_BUILD_HOST.
    @Test
    void stage_pinsEveryBbTemperCallToTheBuildHost() throws Exception {
        var repo = sb.repo();
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        var calls = Files.readAllLines(sb.home.resolve("shim-bbhost.log"));
        assertThat(calls).extracting(l -> l.split(" ")[0]).containsExactly("checkout", "build", "wait", "report", "rm");
        assertThat(calls).extracting(l -> l.split(" ")[1]).containsOnly(TestHosts.BIGBOY);
    }

    // ------------------------------------------------------------------------- unit-file drift (D3.4a, D4.1, F-D)

    private static final String DRIFT_LINE = "drift %s/.config/systemd/user/toy.service live=cafe repo=repo-unit-sha";

    private String driftLine() {
        return DRIFT_LINE.formatted(sb.home);
    }

    /// F-D, `live`: the unit's drift follows the live MANIFEST; no drift hook output without the drift. Mutation: remove the
    /// `unit_drift` call of dl_live.
    @Test
    void drift_live_reportsTheUnitsDrift_afterTheLiveManifest() throws Exception {
        sb.env.put("DL_TOY_DRIFT", "cafe");
        var out = sb.run(script(), sb.root, "live");
        assertThat(out.rc()).as(out.err()).isZero();
        assertThat(out.out()).contains(driftLine());
        assertThat(out.out().indexOf(driftLine())).isGreaterThan(out.out().indexOf(oldSha + "  toy.bin"));
        sb.env.remove("DL_TOY_DRIFT");
        assertThat(sb.run(script(), sb.root, "live").out()).doesNotContain("drift ");
    }

    /// F-D, `install --dry-run`: the drift comes before the plan, is never a refusal, and the plan digest is the same with and
    /// without it. Mutation: remove the `unit_drift` call of dl_install.
    @Test
    void drift_installDryRun_reportsBeforeThePlan_neverRefuses_digestUnchanged() throws Exception {
        var plain = sb.dryRun(COMMIT, oldSha, newSha);
        sb.env.put("DL_TOY_DRIFT", "cafe");
        var drifted = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(drifted.rc()).as(drifted.err()).isZero();
        assertThat(drifted.out()).contains(driftLine());
        assertThat(drifted.out().indexOf(driftLine())).isLessThan(drifted.out().indexOf("plan-header"));
        assertThat(DeploySandbox.planSha(drifted)).isEqualTo(DeploySandbox.planSha(plain));
        assertThat(plain.out()).doesNotContain("drift ");
    }

    /// F-D, the real `install`: the drift is printed before `do-header`, in B/install.log too, and the install still succeeds.
    /// Mutation: print the drift only in the dry-run.
    @Test
    void drift_install_reportsBeforeDoHeader_andStillInstalls() throws Exception {
        sb.env.put("DL_TOY_DRIFT", "cafe");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(out.out()).contains(driftLine());
        assertThat(out.out().indexOf(driftLine())).isLessThan(out.out().indexOf("do-header"));
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).contains("outcome=installed");
    }

    /// F-D, `stage`: the printed report carries the drift (the live fetch-back and the staged dry-run). Mutation: no drift anywhere
    /// in stage's output.
    @Test
    void drift_stage_reportsTheUnitsDrift() throws Exception {
        sb.env.put("DL_TOY_DRIFT", "cafe");
        var repo = sb.repo();
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(out.out()).contains(driftLine());
        assertThat(out.out().indexOf(driftLine())).isGreaterThan(out.out().indexOf("live " + oldSha));
    }

    /// Stage prints the drift once (the live fetch-back), not again from the dry-run plan text, and the owner line's plan digest
    /// is the one of the plan without drift. Mutations: stop filtering `^drift ` from the printed plan (two lines);
    /// hash the filtered text (digest differs).
    @Test
    void drift_stage_printsOneDriftLine_andTheDigestIsTheDriftFreeOne() throws Exception {
        var repo = sb.repo();
        var plain = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(plain.rc()).as(plain.out() + plain.err()).isZero();
        sb.env.put("DL_TOY_DRIFT", "cafe");
        var out = sb.run(repo.work().resolve("ops/deploy/deploy-toy.sh"), repo.work(), "stage", "--commit", repo.main());
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(out.out().lines().filter(l -> l.startsWith("drift ")).toList()).containsExactly(driftLine());
        assertThat(out.out()).contains("--expect-plan " + DeploySandbox.planSha(plain));
    }

    // ------------------------------------------------------------------------------------------ record (P12-P15)

    /// P12: omit the `rollback=` field.
    @Test
    void p12_success_writesOneRecordLine() throws Exception {
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(sb.deploysLog()).hasSize(1);
        var line = sb.deploysLog().get(0);
        assertThat(line).matches(RECORD).contains("event=install ").contains("outcome=installed").contains("rollback=" + oldSha).contains("sha256=" + newSha).contains("schema=V4");
        assertThat(DeploySandbox.sha256(Files.readAllBytes(sb.liveFile()))).isEqualTo(newSha);
        assertThat(sb.liveFile().resolveSibling("toy.bin.bak-" + oldSha)).hasContent(OLD_CONTENT.strip());
        assertThat(out.out()).contains("do-header v1 unit=toy");
    }

    /// P14: always restart.
    @Test
    void p14_liveEqualsStaged_isUnchanged_noRestart() throws Exception {
        sb.live(NEW_CONTENT);
        var out = sb.install(COMMIT, newSha, newSha);
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(sb.deploysLog()).hasSize(1);
        assertThat(sb.deploysLog().get(0)).matches(RECORD).contains("outcome=unchanged").contains("dir=none");
        assertThat(sb.shimLog()).doesNotContain("systemctl --user restart").doesNotContain("systemctl --user stop");
    }

    /// P15: ignore a `mkdir` failure.
    @Test
    void p15_aHeldLock_refuses_andIsLeftAsFound() throws Exception {
        var dry = sb.dryRun(COMMIT, oldSha, newSha);
        var lock = Files.createDirectories(sb.home.resolve(".deploy/lock"));
        Files.writeString(lock.resolve("pid"), "99999\n");
        var before = sb.snapshot();
        var out = sb.installWithPlan(COMMIT, oldSha, newSha, DeploySandbox.planSha(dry));
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(3);
        assertThat(out.err()).contains("another install holds ~/.deploy/lock").contains("99999");
        assertThat(sb.snapshot()).isEqualTo(before);
        assertThat(sb.liveFile()).hasContent(OLD_CONTENT.strip());
    }

    // ----------------------------------------------------------------------------------- swap failure, token (P17-P18)

    /// P17: turn a failed `mv` into success. The directory is read-only, but its `.bak-<old>` and `.partial` already
    /// exist, so only the rename (which writes the directory) fails.
    @Test
    void p17_aFailedRename_isPartial_exit8() throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "permissions do not bind root");
        var dist = sb.liveFile().getParent();
        Files.writeString(dist.resolve("toy.bin.bak-" + oldSha), OLD_CONTENT);
        Files.writeString(dist.resolve("toy.bin.partial"), "stale\n");
        var dry = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(dry.out()).contains("backup backup-kept");
        Files.setPosixFilePermissions(dist, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            var out = sb.installWithPlan(COMMIT, oldSha, newSha, DeploySandbox.planSha(dry));
            assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(8);
            assertThat(out.out()).containsPattern("FAILED 0[0-9]{2} rename");
            assertThat(out.err()).contains("partial:");
            assertThat(sb.deploysLog()).hasSize(1);
            assertThat(sb.deploysLog().get(0)).matches(RECORD).contains("outcome=partial");
        } finally {
            Files.setPosixFilePermissions(dist, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /// P18: pass the token with `-H "Authorization: ..."`.
    @Test
    void p18_theTokenNeverAppearsOnCurlsArgv() throws Exception {
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        assertThat(out.out()).contains("/hosts 200 ok");
        assertThat(sb.shimLog()).contains("curl -s -m 10").contains("-H @").doesNotContain(DeploySandbox.TOKEN).doesNotContain("Authorization");
    }

    // ----------------------------------------------------------------------------------------------- lint (P21)

    /// P21: add `declare -A` to lib.sh.
    @Test
    void p21_libAndToyUseNoBash4Constructs() throws Exception {
        var banned = List.of(Pattern.compile("declare\\s+-[a-zA-Z]*A"), Pattern.compile("\\bmapfile\\b"), Pattern.compile("\\breadarray\\b"),
                             Pattern.compile("\\$\\{[^}]*(,,|\\^\\^)"), Pattern.compile("local\\s+-n\\b"), Pattern.compile("\\bcoproc\\b"),
                             Pattern.compile("\\|&"), Pattern.compile("&>>"), Pattern.compile("\\[\\[\\s+-v"));
        for (var file : List.of(DeploySandbox.OPS_DEPLOY.resolve("lib.sh"), DeploySandbox.RESOURCES.resolve("deploy-toy.sh"))) {
            var code = Files.readAllLines(file).stream().filter(l -> !l.stripLeading().startsWith("#")).toList();
            for (var pattern : banned) {
                assertThat(code).as(file + " vs " + pattern).noneMatch(l -> pattern.matcher(l).find());
            }
            var syntax = sb.ran(List.of("bash", "-n", file.toString()), sb.root);
            assertThat(syntax.rc()).as(syntax.err()).isZero();
        }
    }

    // ------------------------------------------------------------------------------------ rollback failure (P36-P38)

    /// P36: print the banner to stdout, or exit 5.
    @Test
    void p36_rollbackFailure_exits6_withTheExactBanner_onStderr() throws Exception {
        sb.behave(newSha, "500");
        sb.behave(oldSha, "500");
        var out = install();
        assertThat(out.rc()).isEqualTo(6);
        var err = out.err().lines().toList();
        assertThat(err).contains(BANNER_FIRST);
        assertThat(err.get(err.size() - 1)).isEqualTo(BANNER_LAST);
        assertThat(out.out()).doesNotContain("ROLLBACK FAILED");
        assertThat(err).anyMatch(l -> l.startsWith("failed at: probe"));
        var lines = sb.deploysLog();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("event=install ").contains("outcome=health-failed");
        assertThat(lines.get(1)).contains("event=rollback ").contains("outcome=rollback-failed");
    }

    /// P37: treat a failed `dl_place` in the rollback as success.
    @Test
    void p37_aRestoreWhoseBackupIsGone_exits6_namingTheRestore() throws Exception {
        sb.behave(newSha, "500+rmbak");
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(6);
        assertThat(out.err()).contains("failed at: restore " + sb.liveFile());
        assertThat(String.join("\n", sb.deploysLog())).doesNotContain("rolled-back").contains("rollback-failed");
        assertThat(out.err().lines().toList()).contains(BANNER_FIRST);
    }

    /// P38: run `unit_health new` in the rollback.
    @Test
    void p38_theRollbackRecheckExpectsTheOldSetsValues() throws Exception {
        sb.behave(newSha, "500");
        sb.behave(oldSha, "fixed:" + newSha.substring(0, 12));
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isEqualTo(6);
        assertThat(out.out()).contains("FAIL header Toy-Version");
    }

    // ----------------------------------------------------------------------------------------- the plan (P39-P42)

    /// P39: add a timestamp to the `plan-header`. `@HOME@` stands for the sandbox HOME and `@SHA@` for the digest of
    /// the lines above it, which the test computes itself.
    @Test
    void p39_goldenPlan_byteForByte_andStable() throws Exception {
        var first = sb.dryRun(COMMIT, oldSha, newSha);
        var second = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(first.rc()).as(first.err()).isZero();
        assertThat(second.out()).isEqualTo(first.out());
        var golden = Files.readString(DeploySandbox.RESOURCES.resolve("plan-toy.golden")).replace("@HOME@", sb.home.toString());
        var body = golden.substring(0, golden.indexOf("plan-sha256 "));
        var expected = body + "plan-sha256 " + DeploySandbox.sha256(body) + "\n";
        assertThat(first.out()).isEqualTo(expected);
    }

    /// P40: drop the `rollback` phase from the printed plan.
    @Test
    void p40_theToyPlanHoldsEveryActionKind() throws Exception {
        var plan = sb.dryRun(COMMIT, oldSha, newSha).out();
        for (var kind : List.of("check staged-copy ", "check manifest ", "check expect-new ", "check expect-current ", "check lock free", "check schema ",
                                "check alarm-timer ", "prepare mkdir ", "prepare write ", "prepare stop ", "prepare dump ", "prepare verify-dump ",
                                "backup backup ", "swap copy ", "swap chmod ", "swap rename ", "swap verify ", "restart restart ", "health timeout ",
                                "health probe ", "health settle ", "health reprobe ", "after start ", "record record ", "on-unhealthy record ",
                                "on-unhealthy forward-only-if ", "rollback restore ", "rollback verify ", "rollback restart ", "rollback probe ",
                                "rollback settle ", "rollback reprobe ", "rollback start ", "rollback record ", "rollback else-record ")) {
            assertThat(plan).as(kind).containsPattern("(?m)^plan [0-9]{3} " + Pattern.quote(kind));
        }
        sb.liveFile().toFile().delete();
        var fresh = sb.dryRun(COMMIT, "absent", newSha).out();
        assertThat(fresh).containsPattern("(?m)^plan [0-9]{3} rollback set-aside .*\\.failed-" + newSha).doesNotContain(" backup backup ");
        sb.live(NEW_CONTENT);
        assertThat(sb.dryRun(COMMIT, newSha, newSha).out()).containsPattern("(?m)^plan [0-9]{3} record record .* outcome=unchanged ").doesNotContain(" prepare ");
        sb.live(OLD_CONTENT);
        Files.writeString(sb.liveFile().resolveSibling("toy.bin.bak-" + oldSha), OLD_CONTENT);
        assertThat(sb.dryRun(COMMIT, oldSha, newSha).out()).contains(" backup backup-kept ");
    }

    /// P41: skip the digest comparison.
    @Test
    void p41_aPlanDigestThatDiffers_refuses_andWritesNothing() throws Exception {
        var plan = DeploySandbox.planSha(sb.dryRun(COMMIT, oldSha, newSha));
        var before = sb.snapshot();
        var wrong = sb.installWithPlan(COMMIT, oldSha, newSha, flipLast(plan));
        assertThat(wrong.rc()).as(wrong.err()).isEqualTo(3);
        assertThat(wrong.err()).contains("--expect-plan says " + flipLast(plan)).contains("plan-sha256 " + plan);
        assertUntouched(before);
        var state = Files.createDirectories(sb.home.resolve("shim/state"));
        Files.writeString(state.resolve("toy-timer.timer.active"), "active\n");
        var stale = sb.installWithPlan(COMMIT, oldSha, newSha, plan);
        assertThat(stale.rc()).as(stale.err()).isEqualTo(3);
        assertThat(stale.err()).contains("the plan digest is");
        assertUntouched(before);
        assertThat(sb.liveFile()).hasContent(OLD_CONTENT.strip());
    }

    /// P42: let the swap run an action the plan lacks (an extra `chmod`).
    @Test
    void p42_theExecutedDoLinesAreThePlanLines() throws Exception {
        var plan = sb.dryRun(COMMIT, oldSha, newSha).out().lines().toList();
        var out = install();
        assertThat(out.rc()).as(out.out() + out.err()).isZero();
        var expected = new ArrayList<String>();
        for (var line : plan) {
            if (line.startsWith("plan-sha256 ") || line.startsWith("plan 0") && line.contains(" on-unhealthy ")) {
                break;
            }
            expected.add(line.replaceFirst("^plan-header ", "do-header ").replaceFirst("^plan ", "do "));
        }
        var executed = out.out().lines().filter(l -> l.startsWith("do ") || l.startsWith("do-header ")).toList();
        assertThat(executed).isEqualTo(expected);
        assertThat(out.out().lines().filter(l -> l.startsWith("done "))).hasSize(expected.size() - 1);
    }

    @Test
    void usage_expectPlanWithDryRun_isRefusedWithRc2() throws Exception {
        var out = sb.run(script(), sb.root, "install", "--commit", COMMIT, "--expect-current", oldSha, "--expect-new", newSha, "--dry-run", "--expect-plan", "0".repeat(64));
        assertThat(out.rc()).isEqualTo(2);
        var missing = sb.run(script(), sb.root, "install", "--commit", COMMIT, "--expect-current", oldSha, "--expect-new", newSha);
        assertThat(missing.rc()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ plan hooks' rc (P43, D3.4a lead ruling)

    private void planHookFails_refuses(String hook, String message) throws Exception {
        sb.env.put("DL_TOY_FAIL", hook);
        var before = sb.snapshot();
        var dry = sb.dryRun(COMMIT, oldSha, newSha);
        assertThat(dry.rc()).as(dry.out() + dry.err()).isEqualTo(3);
        assertThat(dry.err()).contains(message);
        assertThat(dry.out()).doesNotContain("plan-sha256");
        assertThat(sb.snapshot()).isEqualTo(before);
        assertThat(sb.shimLog()).doesNotContain("systemctl --user restart").doesNotContain("systemctl --user stop");
    }

    /// P43: ignore the rc of `unit_plan_prepare` in the plan build.
    @Test
    void planHookRc_planPrepareFails_refuses() throws Exception {
        planHookFails_refuses("prepare", "unit_plan_prepare failed");
    }

    /// P43: ignore the rc of `unit_plan_restart new`.
    @Test
    void planHookRc_planRestartNewFails_refuses() throws Exception {
        planHookFails_refuses("restart:new", "unit_plan_restart new failed");
    }

    /// P43: ignore the rc of `unit_plan_restart old` (the rollback's restart).
    @Test
    void planHookRc_planRestartOldFails_refuses() throws Exception {
        planHookFails_refuses("restart:old", "unit_plan_restart old failed");
    }

    /// P43: ignore the rc of `unit_plan_after new`.
    @Test
    void planHookRc_planAfterNewFails_refuses() throws Exception {
        planHookFails_refuses("after:new", "unit_plan_after new failed");
    }

    /// P43: ignore the rc of `unit_plan_after old` (the rollback's after).
    @Test
    void planHookRc_planAfterOldFails_refuses() throws Exception {
        planHookFails_refuses("after:old", "unit_plan_after old failed");
    }

    /// P43: ignore the rc of `unit_probes`.
    @Test
    void planHookRc_planProbesFails_refuses() throws Exception {
        planHookFails_refuses("probes:new", "unit_probes new failed");
    }
}
