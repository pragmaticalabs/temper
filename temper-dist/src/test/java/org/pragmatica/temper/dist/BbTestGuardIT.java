package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
                                       + "[ -z \"$MVN_ARGS\" ] || printf '%s\\n' \"$@\" > \"$MVN_ARGS\"\n"
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

    /// NO HIDDEN READS (bto23): the launch runs with the checkout as its cwd, so an empty or relative PATH entry would resolve a command
    /// (`timeout` itself) to checkout content, unbounded. Refused at the top (rc 2), before anything is read. Probe: drop the PATH check ->
    /// red (the run gets past it to the stale-jar check, rc 12).
    @ParameterizedTest
    @ValueSource(strings = {"leading-dot", "relative", "trailing-empty"})
    void bbTest_withAnEmptyOrRelativePathEntry_isRefusedWithRc2_beforeAnyRead(String form) throws Exception {
        var path = System.getenv("PATH");
        var bad = switch (form) {
            case "leading-dot" -> ".:" + path;
            case "relative" -> path + ":rel/bin";
            default -> path + ":";
        };
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-test").toString(),
                                         co.toString(),
                                         "app",
                                         "SomeTest"),
                                 Map.of("PATH", bad),
                                 null,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isEqualTo(2);
        assertThat(run.stderr()).contains("PATH has an empty or relative entry", "refused (rc 2)").doesNotContain("stale");
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
        var made = BbFixtures.run(List.of("bash", "-c", "touch lib/$'\\xff'.txt"), Map.of("LC_ALL", "C"), co, null);

        // only EILSEQ (errno 84, a filesystem that refuses a non-UTF-8 name: ZFS utf8only=on) skips; any other touch failure fails (vbt5 L1)
        assumeTrue(made.rc() == 0 || !made.stderr().contains("Invalid or incomplete multibyte or wide character"),
                   "this filesystem refuses a non-UTF-8 file name (EILSEQ): " + made.stderr());
        assertThat(made.rc()).as("touch of the non-UTF-8 name: " + made.stderr()).isZero();
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

    // ---- vbt4 L2: a launch-time guard refusal is told apart from java's own exit status on the ticket ----
    @Test
    void launchTime_aGuardRefusal_writesVerdictSourceGuard_onTheTicket() throws Exception {
        var run = launchDirect(ROOT_POM);

        assertThat(run.rc()).as("the launcher jar is absent: a launch-time guard, rc 14 " + run.stderr()).isEqualTo(14);
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

    // ---- RESOLVED JUNIT CHECK (CTO ruling on vbt5/vbt6, temper#72): after the java run, the junit jars on the run's own classpath are read
    // by their MANIFEST; any version other than the launcher's, or nothing readable found, is rc 15 and replaces the verdict. Here java is a
    // stand-in that writes one passing testcase and exits 0, so every refusal below is a refusal of a run whose tests passed.

    private Path junitJar(String file, String title, String version, String entry) throws Exception {
        var path = Files.createDirectories(tmp.resolve("jars")).resolve(file);
        var manifest = new java.util.jar.Manifest();
        var attrs = manifest.getMainAttributes();

        attrs.put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        if (title != null) {
            attrs.put(java.util.jar.Attributes.Name.IMPLEMENTATION_TITLE, title);
        }
        if (version != null) {
            attrs.put(java.util.jar.Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(path), manifest)) {
            out.putNextEntry(new java.util.jar.JarEntry(entry));
            out.write(1);
            out.closeEntry();
        }
        return path;
    }

    private String matching() throws Exception {
        return junitJar("junit-platform-commons-1.13.4.jar", "junit-platform-commons", "1.13.4", "org/junit/platform/commons/X.class") + ":"
               + junitJar("junit-jupiter-api-5.13.4.jar", "junit-jupiter-api", "5.13.4", "org/junit/jupiter/api/X.class");
    }

    /// __launch of ticket T1 with a stand-in launcher (its sha passed as the pin) and a stand-in java, the app module recorded with
    /// `appExtra` as the tail of its classpath.
    private BbFixtures.Outcome resolvedRun(String appExtra) throws Exception {
        var record = record(appExtra);

        assertThat(record.rc()).as(record.stdout() + record.stderr()).isZero();
        var running = Files.createDirectories(tmp.resolve("q").resolve("running"));

        Files.writeString(running.resolve("T1"), "id=T1\nco=" + co + "\n");
        var launcher = Files.writeString(tmp.resolve("launcher.jar"), "stand-in launcher");
        var sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(launcher)));
        var fakeJava = BbFixtures.executable(tmp.resolve("bin").resolve("java"),
                                         "#!/usr/bin/env bash\n"
                                        + "while [ $# -gt 0 ]; do [ \"$1\" = --reports-dir ] && D=$2; shift; done\n"
                                        + "printf '<testsuite name=\"s\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase classname=\"a.ATest\" name=\"t\"/></testsuite>\\n' > \"$D/TEST-junit-jupiter.xml\"\n"
                                        + "echo STAND-IN-JAVA-RAN; exit 0\n");

        return BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-test").toString(), "__launch", "app", "1", "a.ATest"),
                              Map.of("BB_TEST_QUEUE_ROOT", tmp.resolve("q").toString(),
                                     "BB_TEST_LAUNCHER_JAR", launcher.toString(),
                                     "BB_TEST_LAUNCHER_SHA", sha,
                                     "BB_TEST_JAVA", fakeJava.toString()),
                              co,
                              null);
    }

    private String ticket() throws Exception {
        return Files.readString(tmp.resolve("q").resolve("running").resolve("T1"));
    }

    private void assertRefused(BbFixtures.Outcome run, String reason) throws Exception {
        assertThat(run.rc()).as("refused, replacing java's exit 0: " + run.stdout() + run.stderr()).isEqualTo(15);
        assertThat(run.stdout()).contains("bb-test: REFUSED (rc 15)", reason)
                                .as("the run's console is not printed as a result").doesNotContain("STAND-IN-JAVA-RAN", "java exit 0");
        assertThat(ticket()).contains("\nrefused=junit version: ", reason, "\nverdict_source=guard\n").doesNotContain("verdict_source=java");
        assertThat(Files.readString(co.resolve(".bb/refused"))).contains(reason);
        assertThat(co.resolve("app/target/bb-test-reports/T1")).as("no report dir that a count could read as this run's").doesNotExist();
        assertThat(co.resolve("app/target/bb-test-reports/T1.refused/console.txt")).exists();
    }

    @Test
    void resolved_theLaunchersJunitOnTheClasspath_passes_andJavasExitIsTheVerdict() throws Exception {
        var run = resolvedRun(matching() + ":" + tmp.resolve("no-such-classes-dir"));

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("STAND-IN-JAVA-RAN",
                                          "bb-test: resolved junit on the test classpath: junit-jupiter-api 5.13.4, junit-platform-commons 1.13.4");
        assertThat(ticket()).contains("verdict_source=java").doesNotContain("refused=");
        assertThat(co.resolve(".bb/refused")).doesNotExist();
    }

    @Test
    void resolved_anotherJupiterVersion_isRefusedWithRc15_namingIt() throws Exception {
        var older = junitJar("junit-jupiter-api-5.11.4.jar", "junit-jupiter-api", "5.11.4", "org/junit/jupiter/api/X.class");

        assertRefused(resolvedRun(older + ":" + matching()), "resolved junit-jupiter-api 5.11.4 (launcher wants 5.13.4)");
    }

    @Test
    void resolved_anotherPlatformVersion_isRefusedWithRc15_namingIt() throws Exception {
        var newer = junitJar("junit-platform-engine-1.14.1.jar", "junit-platform-engine", "1.14.1", "org/junit/platform/engine/X.class");

        assertRefused(resolvedRun(matching() + ":" + newer), "junit-platform-engine 1.14.1 (launcher wants 1.13.4)");
    }

    @Test
    void failClosed_noJunitJarOnTheClasspath_isRefused() throws Exception {
        var plain = junitJar("other.jar", "other", "1.0", "x/Y.class");

        assertRefused(resolvedRun(plain.toString()), "no junit-platform, jupiter or vintage jar on the test classpath");
    }

    @Test
    void failClosed_aClasspathJarThatDoesNotExist_isRefused() throws Exception {
        assertRefused(resolvedRun(matching() + ":/seed/x.jar"), "the classpath jar /seed/x.jar does not exist");
    }

    @Test
    void failClosed_aJarThatCannotBeRead_isRefused() throws Exception {
        var broken = Files.writeString(Files.createDirectories(tmp.resolve("jars")).resolve("broken.jar"), "not a zip");

        assertRefused(resolvedRun(matching() + ":" + broken), "cannot be read as a jar (BadZipFile)");
    }

    @Test
    void failClosed_aJunitJarWithoutAManifestVersion_isRefused() throws Exception {
        var bare = junitJar("junit-jupiter-api.jar", "junit-jupiter-api", null, "org/junit/jupiter/api/X.class");

        assertRefused(resolvedRun(matching() + ":" + bare), "has no junit name or no version in its manifest");
    }

    @Test
    void failClosed_aClasspathDirectoryHoldingJunitClasses_isRefused() throws Exception {
        var classes = Files.createDirectories(tmp.resolve("shaded").resolve("org/junit/jupiter/api"));

        Files.writeString(classes.resolve("X.class"), "x");
        assertRefused(resolvedRun(matching() + ":" + tmp.resolve("shaded")), "holds junit classes, which no manifest versions");
    }

    // ---- vbt7 F2: fail closed, never hang. A FIFO or device opened by java (or by the digest) blocks forever, so it is refused first ----

    private Path fifo(String name) throws Exception {
        var path = Files.createDirectories(tmp.resolve("special")).resolve(name);

        assertThat(BbFixtures.run(List.of("mkfifo", path.toString()), Map.of(), null, null).rc()).isZero();

        return path;
    }

    /// Probe: drop bb-test's pre-run entry check -> red: the .jar FIFOs reach the stand-in java and are refused later under another reason
    /// (resolved check: "does not exist"), the bare FIFO and /dev/zero reach java and pass (rc 0). The real-java hang is BbTestLaunchIT's row.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fifo.jar", "fifo", "/dev/zero", "link-to-fifo.jar"})
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void preRun_aClasspathEntryThatIsAFifoOrADevice_isRefusedWithRc15_beforeJavaRuns(String entry) throws Exception {
        Path path;

        if (entry.startsWith("/")) {
            path = Path.of(entry);
        } else if (entry.startsWith("link-")) {
            path = Files.createSymbolicLink(tmp.resolve(entry), fifo("target-fifo"));
        } else {
            path = fifo(entry);
        }
        var started = System.nanoTime();
        var run = resolvedRun(matching() + ":" + path);
        var seconds = (System.nanoTime() - started) / 1_000_000_000L;

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(15);
        assertThat(seconds).as("refused within seconds, never a hang").isLessThan(20);
        assertThat(run.stdout()).contains("bb-test: REFUSED (rc 15): unreadable classpath entry: " + path + " is a ",
                                          "not a regular file or directory; java was not run")
                                .doesNotContain("STAND-IN-JAVA-RAN");
        assertThat(ticket()).contains("\nrefused=unreadable classpath entry: " + path, "\nverdict_source=guard\n").doesNotContain("verdict_source=java");
        assertThat(Files.readString(co.resolve(".bb/refused"))).contains("unreadable classpath entry: " + path);
        assertThat(co.resolve("app/target/bb-test-reports/T1")).as("java never ran: no report dir").doesNotExist();
    }

    /// A FIFO inside a recorded classes dir: the stale-jar guard digests every file there, and opening a FIFO blocks. It is refused (rc 12).
    /// Probe: drop sha_file's regular-file check -> check blocks on the FIFO, cut by the timeout, red.
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void digest_aFifoInsideAClassesDir_isRefusedWithRc12_neverAHang() throws Exception {
        recorded();
        assertThat(BbFixtures.run(List.of("mkfifo", co.resolve("lib/target/classes/B.class").toString()), Map.of(), null, null).rc()).isZero();
        var run = check();

        assertThat(run.rc()).as(run.stderr()).isEqualTo(12);
        assertThat(run.stderr()).contains("is not a regular file");
    }

    /// vbt7 F3: maven takes `-l<file>` attached as well as `-l <file>`; either would make the record's offline maven write over the build's
    /// own log. Both forms (and --log-file=) are dropped; -llr (maven's --legacy-local-repository) is not a -l and is kept.
    /// Probe: drop the attached -l clause -> -lattached.log reaches maven, red.
    @Test
    void record_dropsEveryFormOfTheLogFileOption_andKeepsLlr() throws Exception {
        var args = tmp.resolve("mvn-args");
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-cp-record").toString(), "record", co.toString(),
                                         "-pl", "app", "-lattached.log", "-l", "separate.log", "--log-file=long.log", "--log-file", "long2.log", "-llr"),
                                 Map.of("BB_TEST_MVN", fakeMvn.toString(), "MVN_ARGS", args.toString()),
                                 null,
                                 null);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        var seen = Files.readAllLines(args);

        assertThat(seen).contains("-pl", "app", "-llr")
                        .noneMatch(a -> a.contains("log"))
                        .doesNotContain("-l", "--log-file");
    }

    /// vbt9 READ INVARIANT (CTO ruling): every command in ops/bb/bb-test that opens checkout-controlled content runs through bounded().
    /// Static, on the script's own source (the authority): BbTestReadAudit applies the header's definition and prints every command it
    /// classified. The roots are pinned here, so narrowing the definition in the header turns this red too.
    /// Controls in-row (the instrument can fail): a bare `cat "$CO/.bb/x"` added, the classpath record read unwrapped, bounded() made to
    /// run its command without the timeout, and the roots line removed, each red. Branch probes: the same added read and unwrap, committed.
    /// OWN-TEMP (bounded) (CTO ruling via bto23): RD and OUT have no exemption; every read of one is the CAP_PY read under bounded(). Controls:
    /// the console read made a bare `cat "$OUT"`, or a bounded read without the byte cap, bounded() opening its <out> outside the timeout,
    /// a `: > "$RD"` added, a `cd "$CO"` added, and a relative-path read or redirect added (the launch's cwd is the checkout), each red.
    /// NO HIDDEN READS (bto24): a bare relative word (`cat x`) cannot be told from text, so the launch leaves the checkout cwd (`cd /`);
    /// controls: that `cd /` removed, and any other cd/pushd/popd added (`cd -` returns to the checkout), each red.
    @Test
    void everyCheckoutRead_isBounded() throws Exception {
        var source = Files.readString(BbFixtures.OPS_BB.resolve("bb-test"));
        var audit = BbTestReadAudit.audit(source);

        audit.calls().forEach(c -> System.out.println("bb-test read audit: " + c));
        System.out.println("bb-test read audit: checkout variables " + audit.checkoutVars());
        assertThat(audit.roots()).as("the header's checkout-roots line").containsExactly("CO", "MOD");
        assertThat(audit.problems()).as("commands opening checkout content outside bounded()").isEmpty();
        assertThat(audit.checkoutVars()).contains("D", "BASE", "CP", "RD", "OUT");
        assertThat(audit.ownTemp()).as("own-temp: assigned only from mktemp or empty").containsExactlyInAnyOrder("RD", "OUT", "PG");
        assertThat(audit.calls().stream().filter(c -> c.verdict().startsWith("BOUNDED (capped own-temp read")).map(BbTestReadAudit.Call::text))
                  .as("every own-temp read is capped: the stale-jar output, the record, the entry check, the count, the junit read, the console, the port group (bto25 G6)")
                  .hasSize(7)
                  .filteredOn(t -> t.endsWith("\"$OUT\"")).hasSize(1);
        assertThat(audit.calls().stream().filter(c -> c.verdict().equals("BOUNDED")).map(BbTestReadAudit.Call::text))
                  .as("the bounded reads the invariant names")
                  .anyMatch(t -> t.contains("\"$HERE/bb-cp-record\" check") && t.contains(" - "))
                  .anyMatch(t -> t.contains("\"$HERE/bb-cp-record\" check") && t.contains("\"$RD\""))
                  .anyMatch(t -> t.contains(" cat \"$CO/.bb/cp/"))
                  .anyMatch(t -> t.contains("$ENTRY_PY"))
                  .anyMatch(t -> t.contains("\"$JAVA\""))
                  .anyMatch(t -> t.contains("bb-testcount"))
                  .anyMatch(t -> t.contains("$JUNIT_PY"))
                  .anyMatch(t -> t.contains("bb-portgroup.py"))
                  .anyMatch(t -> t.contains(".bb/refused"));

        var launcher = "  guard launcher_ok\n";
        var unwrapFrom = "bounded \"$READ_T\" \"$CO\" \"$RD\" cat \"$CO/.bb/cp/${MOD//\\//__}.cp\"";
        var body = "then (cd \"$d\" && exec timeout -k 10 \"$s\" \"$@\")";

        assertThat(source).contains(launcher, unwrapFrom, body, "\n# checkout-roots: CO MOD\n");
        assertThat(BbTestReadAudit.audit(source.replace(launcher, launcher + "  cat \"$CO/.bb/x\"\n")).problems())
                  .as("control: a bare read added").isNotEmpty().anyMatch(p -> p.contains("VIOLATION") && p.contains("cat \"$CO/.bb/x\""));
        assertThat(BbTestReadAudit.audit(source.replace(unwrapFrom, "cat \"$CO/.bb/cp/${MOD//\\//__}.cp\" > \"$RD\"")).problems())
                  .as("control: the record read unwrapped").isNotEmpty().anyMatch(p -> p.contains("VIOLATION") && p.contains("cat \"$CO/.bb/cp/"));
        assertThat(BbTestReadAudit.audit(source.replace(body, "then (cd \"$d\" && exec \"$@\")")).problems())
                  .as("control: bounded() without its timeout").isNotEmpty().anyMatch(p -> p.contains("bounded() runs its command"));
        assertThat(BbTestReadAudit.audit(source.replace("\n# checkout-roots: CO MOD\n", "\n")).problems())
                  .as("control: no definition line").isNotEmpty().anyMatch(p -> p.contains("checkout-roots"));

        var console = "bounded \"$READ_T\" \"$CO\" - python3 -I -c \"$CAP_PY\" \"$READ_CAP\" \"$OUT\"";
        var leave = "  CO=$(pwd); cd /; ";

        assertThat(source).contains(leave);
        assertThat(BbTestReadAudit.audit(source.replace(leave, "  CO=$(pwd); ")).problems())
                  .as("control: the launch left in the checkout cwd (a bare `cat x` would read checkout content)").isNotEmpty()
                  .anyMatch(p -> p.contains("does not leave the checkout cwd"));
        var outBranch = "(cd \"$d\" && exec timeout -k 10 \"$s\" sh -c 'o=$1; shift; exec \"$@\" >> \"$o\" 2>&1' sh \"$o\" \"$@\")";

        assertThat(source).contains(console, outBranch);
        assertThat(BbTestReadAudit.audit(source.replace(console, "cat \"$OUT\"")).problems())
                  .as("control: the console read unbounded").isNotEmpty().anyMatch(p -> p.contains("VIOLATION") && p.contains("cat \"$OUT\""));
        assertThat(BbTestReadAudit.audit(source.replace(console, "bounded \"$READ_T\" \"$CO\" - cat \"$OUT\"")).problems())
                  .as("control: the console read bounded in time only").isNotEmpty().anyMatch(p -> p.contains("without the byte cap"));
        assertThat(BbTestReadAudit.audit(source.replace(outBranch, "(cd \"$d\" && exec timeout -k 10 \"$s\" \"$@\") >> \"$o\" 2>&1")).problems())
                  .as("control: bounded() opening <out> outside the timeout").isNotEmpty().anyMatch(p -> p.contains("outside the timeout"));
        for (var added : List.of(": > \"$RD\"", "cd \"$CO\"", "pushd \"$CO/.bb\"", "cat .bb/cp/x.cp", "echo x > .bb/y", "sed -n 1p ./x",
                                 "cd -", "popd", "cd ..", "builtin cd \"$MOD\"", "command cd")) {
            assertThat(BbTestReadAudit.audit(source.replace(launcher, launcher + "  " + added + "\n")).problems())
                      .as("control: " + added + " added").isNotEmpty().anyMatch(p -> p.contains("VIOLATION") && p.contains(added));
        }
    }
}
