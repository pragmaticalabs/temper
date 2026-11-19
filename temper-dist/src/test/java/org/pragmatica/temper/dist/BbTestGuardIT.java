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

    @Test
    void versionGuard_aCheckoutOnAnotherJunitPlatformVersion_isRefusedWithRc15_namingBoth() throws Exception {
        var run = bbTestWithRootPom(ROOT_POM.replace("5.13.4", "5.12.2"), tmp.resolve("no-launcher.jar"));

        assertThat(run.rc()).as(run.stderr()).isEqualTo(15);
        assertThat(run.stderr()).contains("1.12.2", "1.13.4");
    }

    @Test
    void versionGuard_anUndeterminableVersion_isRefusedWithRc15_failClosed() throws Exception {
        var run = bbTestWithRootPom(ROOT_POM.replace("5.13.4", "${some.var}"), tmp.resolve("no-launcher.jar"));

        assertThat(run.rc()).as(run.stderr()).isEqualTo(15);
        assertThat(run.stderr()).contains("cannot be determined");
    }

    @Test
    void versionGuard_theMatchingVersion_passesTheCheck_andReachesTheLauncherCheck() throws Exception {
        var run = bbTestWithRootPom(ROOT_POM, tmp.resolve("no-launcher.jar"));

        assertThat(run.rc()).as(run.stderr()).isEqualTo(14);
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
}
