package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// The stale-jar guard of bb-test (design §3, controls 1-4) and the testcount of its reports (control 7). A checkout with two reactor modules
/// (lib, app) is recorded by `bb-cp-record record` with a stand-in maven that writes each module's classpath; no real maven runs.
class BbTestGuardIT {
    private static final Path REPORTS = Path.of("src", "test", "resources", "bb", "launcher-reports").toAbsolutePath();

    private static final String ROOT_POM = "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><properties><junit.version>5.13.4</junit.version></properties></project>";

    @TempDir
    Path tmp;

    Path co;
    Path fakeMvn;

    @BeforeEach
    void checkout() throws Exception {
        assumeTrue(BbFixtures.linux(), "the bb scripts need GNU tools");
        co = tmp.resolve("co");
        Files.createDirectories(co);
        Files.writeString(co.resolve("pom.xml"), ROOT_POM);
        for (var m : List.of("lib", "app")) {
            Files.createDirectories(co.resolve(m).resolve("src"));
            Files.writeString(co.resolve(m).resolve("pom.xml"),
                              "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>" + m
                             + "</artifactId><version>1</version></project>");
            Files.writeString(co.resolve(m).resolve("src").resolve("A.java"),
                              "class A {}");
        }

        Files.writeString(co.resolve(".gitignore"), "target/\n.bb/\n");
        fakeMvn = BbFixtures.executable(tmp.resolve("bin").resolve("mvn"),
                                        "#!/usr/bin/env bash\n"
                                       + "for m in lib app; do mkdir -p $m/target/classes $m/target/test-classes; echo \"class-$m\" > $m/target/classes/A.class; done\n"
                                       + "echo '/seed/junit.jar' > lib/target/bb-cp.txt\n"
                                       + "echo \"$PWD/lib/target/classes:${APP_EXTRA:-/seed/x.jar}\" > app/target/bb-cp.txt\n");
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "c1");
    }

    private void git(String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git", "-C", co.toString()));

        cmd.addAll(List.of(args));
        assertThat(BbFixtures.run(cmd, Map.of(), null, null).rc()).isZero();
    }

    private BbFixtures.Outcome record(String appExtra) throws Exception {
        var env = new HashMap<String, String>();

        env.put("BB_TEST_MVN", fakeMvn.toString());
        env.put("APP_EXTRA", appExtra);

        return BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-cp-record").toString(),
                                      "record",
                                      co.toString(),
                                      "-pl",
                                      "app",
                                      "-am"),
                              env,
                              null,
                              null);
    }

    private BbFixtures.Outcome check() throws Exception {
        return BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-cp-record").toString(),
                                      "check",
                                      co.toString(),
                                      "app"),
                              Map.of(),
                              null,
                              null);
    }

    private void recorded() throws Exception {
        var run = record("/seed/x.jar");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
    }

    @Test
    void anUntouchedRecordedTree_passesTheGuard() throws Exception {
        recorded();
        var run = check();

        assertThat(run.rc()).as(run.stderr()).isZero();
    }

    @Test
    void control1_aSourceEditInAModuleAfterTheBuild_isRefusedUnderB_namingTheModule() throws Exception {
        recorded();
        Files.writeString(co.resolve("lib").resolve("src").resolve("A.java"),
                          "class A { int x; }");
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("module lib", "guard (b)");
    }

    @Test
    void control2_aNewCommit_isRefusedUnderA() throws Exception {
        recorded();
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "c2");
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (a)");
    }

    @Test
    void control3_aClassesDirReplacedAfterTheBuild_isRefusedUnderC() throws Exception {
        recorded();
        Files.writeString(co.resolve("lib").resolve("target").resolve("classes").resolve("A.class"),
                          "built-at-HEAD~1");
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (c)", "lib/target/classes");
    }

    @Test
    void control4_aClasspathPointedAtAnM2LocalSnapshotJarOfAReactorModule_isRefusedUnderD() throws Exception {
        var run = record(co + "/.m2-local/g/lib/1-SNAPSHOT/lib-1-SNAPSHOT.jar");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        var checked = check();

        assertThat(checked.rc()).isEqualTo(12);
        assertThat(checked.stderr()).contains("guard (d)");
    }

    @Test
    void aTreeNeverRecorded_isRefused() throws Exception {
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
    }

    @Test
    void bbTest_refusesBbStaleOk_withRc12_whateverItsValue() throws Exception {
        recorded();
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-test").toString(),
                                         co.toString(),
                                         "app",
                                         "SomeTest"),
                                 Map.of("BB_STALE_OK", "0"),
                                 null,
                                 null);

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("BB_STALE_OK is refused");
    }

    @Test
    void bbTest_withTheLauncherJarAbsent_failsLoudlyNamingPathAndSha() throws Exception {
        recorded();
        var missing = tmp.resolve("no-launcher.jar");
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-test").toString(),
                                         co.toString(),
                                         "app",
                                         "SomeTest"),
                                 Map.of("BB_TEST_LAUNCHER_JAR", missing.toString()),
                                 null,
                                 null);

        assertThat(run.rc()).isEqualTo(14);
        assertThat(run.stderr()).contains(missing.toString(),
                                          "3fdfc37e29744a9a67dd5365e81467e26fbde0b7aa204e6f8bbe79eeaa7ae892");
    }

    private BbFixtures.Outcome bbTestWithRootPom(String pom, Path launcher) throws Exception {
        Files.writeString(co.resolve("pom.xml"), pom);
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "pom");
        recorded();

        return BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-test").toString(),
                                      co.toString(),
                                      "app",
                                      "SomeTest"),
                              Map.of("BB_TEST_LAUNCHER_JAR", launcher.toString()),
                              null,
                              null);
    }

    private static final String POM_HEAD = "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging>";

    private BbFixtures.Outcome versionOf(String pomBody) throws Exception {
        return bbTestWithRootPom(POM_HEAD + pomBody + "</project>", tmp.resolve("no-launcher.jar"));
    }

    private void refused15(BbFixtures.Outcome run) {
        assertThat(run.rc()).as(run.stderr()).isEqualTo(15);
    }

    @Test
    void versionGuard_aCheckoutOnAnotherJunitPlatformVersion_isRefusedWithRc15_namingBoth() throws Exception {
        var run = bbTestWithRootPom(ROOT_POM.replace("5.13.4", "5.12.2"), tmp.resolve("no-launcher.jar"));

        refused15(run);
        assertThat(run.stderr()).contains("1.12.2", "1.13.4");
    }

    @Test
    void versionGuard_anUndeterminableVersion_isRefusedWithRc15_failClosed() throws Exception {
        var run = bbTestWithRootPom(ROOT_POM.replace("5.13.4", "${some.var}"), tmp.resolve("no-launcher.jar"));

        refused15(run);
        assertThat(run.stderr()).contains("cannot be determined");
    }

    @Test
    void versionGuard_theMatchingVersion_passesTheCheck_andReachesTheLauncherCheck() throws Exception {
        var run = bbTestWithRootPom(ROOT_POM, tmp.resolve("no-launcher.jar"));

        assertThat(run.rc()).as(run.stderr()).isEqualTo(14);
    }

    @Test
    void versionGuard_aCommentedOutPlatformProperty_isNotRead() throws Exception {
        var run = versionOf("<properties><!-- <junit-platform.version>1.13.4</junit-platform.version> --><junit.version>5.12.2</junit.version></properties>");

        refused15(run);
        assertThat(run.stderr()).contains("1.12.2");
    }

    @Test
    void versionGuard_twoDefinitions_areRefused_evenWhenTheFirstMatches() throws Exception {
        refused15(versionOf("<properties><junit-platform.version>1.13.4</junit-platform.version><junit-platform.version>1.12.0</junit-platform.version></properties>"));
    }

    @Test
    void versionGuard_aPropertyOutsideProjectProperties_isNotRead() throws Exception {
        var run = versionOf("<dependencyManagement><junit-platform.version>1.13.4</junit-platform.version></dependencyManagement>");

        refused15(run);
        assertThat(run.stderr()).contains("cannot be determined");
    }

    @Test
    void versionGuard_aPropertyReference_isRefused() throws Exception {
        refused15(versionOf("<properties><junit-platform.version>${junit.platform}</junit-platform.version></properties>"));
    }

    @Test
    void versionGuard_aSixDotXJunitVersion_isRefused() throws Exception {
        refused15(versionOf("<properties><junit.version>6.0.0</junit.version></properties>"));
    }

    @Test
    void versionGuard_aSnapshotVersion_isRefused() throws Exception {
        refused15(versionOf("<properties><junit-platform.version>1.13.4-SNAPSHOT</junit-platform.version></properties>"));
    }

    @Test
    void versionGuard_aCrlfPomWithTheMatchingVersion_isAccepted() throws Exception {
        var pom = POM_HEAD
                + "\r\n<properties>\r\n<junit.version>\r\n  5.13.4 \r\n</junit.version>\r\n</properties>\r\n</project>\r\n";
        var run = bbTestWithRootPom(pom, tmp.resolve("no-launcher.jar"));

        assertThat(run.rc()).as(run.stderr()).isEqualTo(14);
    }

    private BbFixtures.Outcome launchDirect(String pom, String... extra) throws Exception {
        // what the queue's waiter runs for a bb-test ticket, with the enqueue-time check out of the way: __launch in the checkout,
        // with one running ticket naming it
        Files.writeString(co.resolve("pom.xml"), pom);
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "--allow-empty", "-m", "pom");
        recorded();
        var running = Files.createDirectories(tmp.resolve("q").resolve("running"));

        Files.writeString(running.resolve("T1"), "id=T1\nco=" + co + "\n");
        var cmd = new java.util.ArrayList<>(List.of(BbFixtures.OPS_BB.resolve("bb-test").toString(),
                                                    "__launch",
                                                    "app",
                                                    "1",
                                                    "SomeTest"));

        cmd.addAll(List.of(extra));

        return BbFixtures.run(cmd,
                              Map.of("BB_TEST_QUEUE_ROOT",
                                     tmp.resolve("q").toString(),
                                     "BB_TEST_LAUNCHER_JAR",
                                     tmp.resolve("no-launcher.jar").toString()),
                              co,
                              null);
    }

    @Test
    void launchTime_aCheckoutOnAnotherJunitPlatformVersion_isRefusedWithRc15() throws Exception {
        var run = launchDirect(ROOT_POM.replace("5.13.4", "5.12.2"));

        assertThat(run.rc()).as(run.stderr()).isEqualTo(15);
        assertThat(run.stderr()).contains("1.12.2", "1.13.4");
    }

    @Test
    void launchTime_theMatchingVersion_reachesTheLauncherCheck() throws Exception {
        assertThat(launchDirect(ROOT_POM).rc()).isEqualTo(14);
    }

    @Test
    void control3c_aClasspathEntryTheManifestNeverRecorded_isRefusedUnderC() throws Exception {
        recorded();
        var manifest = co.resolve(".bb").resolve("cp").resolve("manifest");
        var kept = Files.readAllLines(manifest)
                        .stream()
                        .filter(l -> !l.startsWith("entry app lib/target/classes "))
                        .toList();

        assertThat(kept).hasSize(Files.readAllLines(manifest).size() - 1);
        Files.write(manifest, kept);
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (c)", "lib/target/classes was never recorded");
    }

    @Test
    void record_leavesNoTargetBbCpTxt_andKeepsTheClasspathUnderDotBb() throws Exception {
        recorded();
        assertThat(co.resolve("lib/target/bb-cp.txt")).doesNotExist();
        assertThat(co.resolve("app/target/bb-cp.txt")).doesNotExist();
        assertThat(co.resolve(".bb/cp/app.cp")).exists();
        assertThat(co.resolve(".bb/cp/lib.cp")).exists();
    }

    @Test
    void digest_rootPomEditedAfterTheBuild_isRefusedUnderB_namingTheReactorRoot() throws Exception {
        recorded();
        Files.writeString(co.resolve("pom.xml"), ROOT_POM.replace("<version>1</version>", "<version>2</version>"));
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (b)", "reactor root");
    }

    @Test
    void digest_aFileUnderDotMvnAddedAfterTheBuild_isRefusedUnderB_namingTheReactorRoot() throws Exception {
        recorded();
        Files.createDirectories(co.resolve(".mvn"));
        Files.writeString(co.resolve(".mvn").resolve("jvm.config"),
                          "-Dx=1");
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("reactor root");
    }

    @Test
    void digest_aParentPomNotOnTheClasspathEditedAfterTheBuild_isRefusedUnderB_namingTheReactorRoot() throws Exception {
        Files.createDirectories(co.resolve("parent"));
        Files.writeString(co.resolve("parent").resolve("pom.xml"),
                          "<project><modelVersion>4.0.0</modelVersion></project>");
        git("add", "-A");
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "parent");
        recorded();
        Files.writeString(co.resolve("parent").resolve("pom.xml"),
                          "<project><modelVersion>4.0.0</modelVersion><version>9</version></project>");
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("reactor root");
    }

    @Test
    void digest_anExecBitFlippedAfterTheBuild_isRefusedUnderB() throws Exception {
        recorded();
        var src = co.resolve("lib").resolve("src").resolve("A.java");

        Files.setPosixFilePermissions(src, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("module lib", "guard (b)");
    }

    private static String sha256(String text) throws Exception {
        return java.util.HexFormat.of()
                                  .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                                                                        .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void digest_oneFileNamedLikeTwoDigestLines_isNotTheSameTreeAsTwoFiles() throws Exception {
        // under unencoded "path<TAB>mode<TAB>sha<LF>" lines, {lib/x: A, lib/y: B} and the single file "lib/x<TAB>-<TAB>sha(A)<LF>lib/y" (a directory named "x<TAB>-<TAB>sha(A)<LF>lib" holding y): B hash alike
        var lib = co.resolve("lib");

        Files.writeString(lib.resolve("x"), "A");
        Files.writeString(lib.resolve("y"), "B");
        recorded();
        Files.delete(lib.resolve("x"));
        Files.delete(lib.resolve("y"));
        var weird = Files.createDirectories(lib.resolve("x\t-\t" + sha256("A") + "\nlib"));

        Files.writeString(weird.resolve("y"), "B");
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (b)");
    }

    @Test
    void digest_aNonUtf8FileName_isRefusedWithRc12_andAMessage_neverATraceback() throws Exception {
        recorded();
        var made = BbFixtures.run(List.of("bash", "-c", "touch lib/$'\\xff'.txt"), Map.of(), co, null);

        assumeTrue(made.rc() == 0, "this filesystem refuses a non-UTF-8 file name (e.g. ZFS utf8only=on): " + made.stderr());
        var run = check();

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("not valid UTF-8").doesNotContain("Traceback");
    }

    @Test
    void bbTest_onAStaleTree_isRefusedBeforeAnythingIsQueued() throws Exception {
        recorded();
        Files.writeString(co.resolve("app").resolve("src").resolve("A.java"),
                          "class A { int y; }");
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-test").toString(),
                                         co.toString(),
                                         "app",
                                         "SomeTest"),
                                 Map.of(),
                                 null,
                                 null);

        assertThat(run.rc()).isEqualTo(12);
        assertThat(run.stderr()).contains("module app", "guard (b)");
    }

    private BbFixtures.Outcome count(Path dir) throws Exception {
        return BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-testcount").toString(),
                                      dir.toString()),
                              Map.of(),
                              null,
                              null);
    }

    @Test
    void control7_aLauncherReportDirWithKnownOutcomes_isCountedExactly() throws Exception {
        var run = count(REPORTS);

        assertThat(run.rc()).as(run.stderr()).isZero();
        assertThat(run.stdout().trim()).isEqualTo("testcases=6 passed=3 failures=1 errors=1 skipped=1 files=3 duplicates=0");
    }

    @Test
    void control7_anEmptyReportDir_isNeverZeroFailures() throws Exception {
        var empty = Files.createDirectories(tmp.resolve("empty"));

        assertThat(count(empty).rc()).isEqualTo(4);
    }

    @Test
    void control7_aDirHoldingADuplicateTestcase_isRefused() throws Exception {
        var dup = Files.createDirectories(tmp.resolve("dup"));

        Files.copy(REPORTS.resolve("TEST-junit-jupiter.xml"), dup.resolve("TEST-junit-jupiter.xml"));
        Files.copy(REPORTS.resolve("TEST-junit-jupiter.xml"), dup.resolve("TEST-again.xml"));
        assertThat(count(dup).rc()).isEqualTo(3);
    }

    // ---- vbt4 M1: every override of the junit version properties is refused (rc 15), not only the root pom's /project/properties ----
    private static final String ROOT_PROPS = "<properties><junit.version>5.13.4</junit.version></properties>";

    @Test
    void versionGuard_aRootPomProfileSettingThePlatformProperty_isRefusedWithRc15() throws Exception {
        var run = versionOf(ROOT_PROPS
                           + "<profiles><profile><id>p</id><properties><junit-platform.version>1.13.4</junit-platform.version></properties></profile></profiles>");

        refused15(run);
        assertThat(run.stderr()).contains("a <profile> in the root pom sets <junit-platform.version>");
    }

    @Test
    void versionGuard_twoPropertiesBlocks_areRefusedWithRc15() throws Exception {
        var run = versionOf(ROOT_PROPS + "<properties><other>x</other></properties>");

        refused15(run);
        assertThat(run.stderr()).contains("the root pom has 2 <properties> blocks");
    }

    @Test
    void versionGuard_aModulePomOnThePathSettingTheProperty_isRefusedWithRc15() throws Exception {
        Files.writeString(co.resolve("app").resolve("pom.xml"),
                          "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>app</artifactId><version>1</version>"
                         + "<properties><junit.version>5.12.2</junit.version></properties></project>");
        var run = bbTestWithRootPom(ROOT_POM, tmp.resolve("no-launcher.jar"));

        refused15(run);
        assertThat(run.stderr()).contains("app/pom.xml sets <junit.version>");
    }

    @Test
    void versionGuard_aMavenConfigDefineOfTheProperty_isRefusedWithRc15() throws Exception {
        Files.createDirectories(co.resolve(".mvn"));
        Files.writeString(co.resolve(".mvn").resolve("maven.config"),
                          "-B\n-D junit-platform.version=1.12.2\n");
        var run = bbTestWithRootPom(ROOT_POM, tmp.resolve("no-launcher.jar"));

        refused15(run);
        assertThat(run.stderr()).contains(".mvn/maven.config sets -Djunit-platform.version");
    }

    @Test
    void launchTime_aDefineOfTheVersionInTheLaunchArgs_isRefusedWithRc15() throws Exception {
        var run = launchDirect(ROOT_POM, "-Djunit.version=5.12.2");

        assertThat(run.rc()).as(run.stderr()).isEqualTo(15);
        assertThat(run.stderr()).contains("the launch args set -Djunit.version");
    }

    // ---- vbt4 L2: a launch-time guard refusal is told apart from java's own exit status on the ticket ----
    @Test
    void launchTime_aGuardRefusal_writesVerdictSourceGuard_onTheTicket() throws Exception {
        var run = launchDirect(ROOT_POM.replace("5.13.4", "5.12.2"));

        assertThat(run.rc()).as(run.stderr()).isEqualTo(15);
        assertThat(Files.readString(tmp.resolve("q").resolve("running").resolve("T1"))).contains("\nverdict_source=guard\n")
                  .doesNotContain("verdict_source=java");
    }

    // ---- vbt4 L3: content behind a symlink is under no digest, so a symlink leaving its module is refused (rc 12) ----
    @Test
    void digest_aFileSymlinkInClassesLeavingTheModule_isRefusedWithRc12() throws Exception {
        recorded();
        var outside = Files.writeString(tmp.resolve("outside.class"), "x");

        Files.createSymbolicLink(co.resolve("lib/target/classes/B.class"), outside);
        var run = check();

        assertThat(run.rc()).as(run.stderr()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (c)", "resolves outside");
    }

    @Test
    void record_aFileSymlinkInClassesLeavingTheModule_failsTheRecord_soTheCheckRefusesWithRc12() throws Exception {
        // without the refusal the record hashes the link string, and the outside file can change under an unchanged digest
        var outside = Files.writeString(tmp.resolve("outside.class"), "x");

        Files.createDirectories(co.resolve("lib/target/classes"));
        Files.createSymbolicLink(co.resolve("lib/target/classes/B.class"), outside);
        var rec = record("/seed/x.jar");

        assertThat(rec.rc()).as(rec.stdout() + rec.stderr()).isEqualTo(1);
        assertThat(rec.stderr()).contains("FAILED, record removed", "resolves outside");
        assertThat(check().rc()).as("no record: refused").isEqualTo(12);
    }

    @Test
    void digest_aDirectorySymlinkInsideAClassesDir_isRefusedWithRc12() throws Exception {
        recorded();
        Files.createSymbolicLink(co.resolve("lib/target/classes/pkg"), co.resolve("lib/src"));
        var run = check();

        assertThat(run.rc()).as(run.stderr()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (c)", "directory symlink");
    }

    @Test
    void digest_aTrackedSymlinkInAModuleLeavingIt_isRefusedWithRc12() throws Exception {
        recorded();
        Files.createSymbolicLink(co.resolve("lib/src/B.java"), co.resolve("app/src/A.java"));
        var run = check();

        assertThat(run.rc()).as(run.stderr()).isEqualTo(12);
        assertThat(run.stderr()).contains("guard (b)", "resolves outside");
    }

    // ---- vbt4 L4: record names its maven's process group in .bb/cp-record.pgid while maven runs, and removes it after ----
    @Test
    void record_writesItsMavensPgidAndStartTime_whileMavenRuns_andRemovesTheFile() throws Exception {
        var seen = tmp.resolve("seen-pgid");
        var waitingMvn = BbFixtures.executable(tmp.resolve("bin").resolve("mvn-pgid"),
                                               "#!/usr/bin/env bash\n"
                                              + "for _ in $(seq 100); do [ -s .bb/cp-record.pgid ] && break; sleep 0.1; done\n"
                                              + "echo \"$$ $(sed 's/^.*) //' /proc/$$/stat | cut -d' ' -f20) $(cat .bb/cp-record.pgid)\" > " + seen
                                              + "\n"
                                              + "exec " + fakeMvn
                                              + " \"$@\"\n");
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-cp-record").toString(),
                                         "record",
                                         co.toString(),
                                         "-pl",
                                         "app",
                                         "-am"),
                                 Map.of("BB_TEST_MVN", waitingMvn.toString(), "APP_EXTRA", "/seed/x.jar"),
                                 null,
                                 null);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        var w = Files.readString(seen).strip().split(" ");

        assertThat(w).as("maven's own pid and start time, then the file's <pgid> <start>").hasSize(4);
        assertThat(w[2]).as("pgid = maven's pid (its own session)").isEqualTo(w[0]);
        assertThat(w[3]).as("the start time of that process").isEqualTo(w[1]);
        assertThat(co.resolve(".bb/cp-record.pgid")).as("removed once maven exited").doesNotExist();
    }
}
