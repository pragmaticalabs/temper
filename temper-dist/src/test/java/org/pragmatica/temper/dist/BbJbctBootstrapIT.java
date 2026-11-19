package org.pragmatica.temper.dist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// bb-build's jbct plugin bootstrap (feat/bb-jbct-bootstrap): real `bb-build __run` waiters with a recording stub maven, over fixture git
/// checkouts holding a root pom, a `.github/workflows/ci.yml` and a `maven.config`. Every ticket runs to its end in `done/`.
///
/// Probes (throwaway local branches, never pushed): drop the present check in bb-jbct-bootstrap (always install) ->
/// [#present_doesNotInstallAgain] red; read ci.yml from the working tree instead of `git show HEAD:` ->
/// [#aWorkingTreeCopyOfCiYml_doesNotWin_theListOfHeadIs_used] red; run the bootstrap without taking its rc (`|| true`-style: the main run
/// follows a failed bootstrap) -> [#aFailedBootstrap_isTheTicketsRc_andTheMainRunNeverStarts] red; use the root project.version as <ver> instead
/// of the plugin's effective version -> [#thePluginPinnedApartFromTheProjectVersion_isTheJarTheGuardChecks] red.
/// Require every ci.yml install list to agree (not only those holding the plugin) -> [#realRc6Shape_pickedListIsThePluginsOne_versionIsTheRc6PomsRc5] red.
class BbJbctBootstrapIT {
    private static final String LIST = "jbct/jbct-maven-plugin,jbct/slice-processor,aether/pg-tools/pg-codegen";
    private static final String VER = "1.2.3-rc1";
    private static final String BOOTSTRAP_LINE = "bootstrap: jbct plugin " + VER + " installed into .m2-local";

    @TempDir
    Path dir;

    private Path queue;
    private Path tools;
    private int seq;

    record Run(int rc, String log, List<String> mvn, List<String> ticket) {
        List<String> installs() {
            return mvn.stream()
                      .filter(l -> l.startsWith("install -B -DskipTests -pl "))
                      .toList();
        }

        List<String> mains() {
            return mvn.stream()
                      .filter(l -> !l.startsWith("install -B -DskipTests -pl "))
                      .toList();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "needs flock, pgrep, GNU tools, git and python3");
        queue = dir.resolve("q");
        for (var d : List.of("waiting", "running", "done")) {
            Files.createDirectories(queue.resolve(d));
        }

        tools = Files.createDirectories(dir.resolve("tools"));
        for (var script : List.of("bb-build", "bb-jbct-bootstrap")) {
            BbFixtures.executable(tools.resolve(script),
                                  Files.readString(BbFixtures.OPS_BB.resolve(script)));
        }

        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        Files.writeString(dir.resolve("loadavg"), "0.00 0.00 0.00 1/1 1\n");
        BbFixtures.executable(dir.resolve("stubmvn"),
                              "#!/bin/sh\necho \"$*\" >> \"$FAKE_MVN_REC\"\n"
                             + "case \"$*\" in install\\ -B\\ -DskipTests\\ -pl*) [ -z \"$FAKE_INSTALL_RC\" ] || exit \"$FAKE_INSTALL_RC\";; esac\n"
                             + "echo \"stub mvn $*\"\n");
    }

    private static String ci(String... lines) {
        return "jobs:\n  b:\n    steps:\n" + java.util.Arrays.stream(lines)
                                                             .map(l -> "      - name: n\n        run: " + l + "\n")
                                                             .collect(Collectors.joining());
    }

    private static final String BOOTSTRAP_CMD = "mvn install -B -DskipTests -pl " + LIST + " -am";
    private static final String GROUP_CMD = "mvn install -B -DskipTests -pl \"${{ steps.plan.outputs.modules }}\" -am";
    private static final String ROOT_CMD = "mvn install -B -DskipTests";

    private static String plugin(String version) {
        return "<plugin><groupId>org.pragmatica-lite</groupId><artifactId>jbct-maven-plugin</artifactId>" + (version == null
                                                                                                             ? ""
                                                                                                             : "<version>" + version
                                                                                                              + "</version>")
             + "</plugin>";
    }

    private static String pomWith(String own, String props, String build) {
        return "<project><parent><groupId>g</groupId><artifactId>p</artifactId><version>9.9.9</version></parent>"
             + "<artifactId>root</artifactId>" + (own == null
                                                  ? ""
                                                  : "<version>" + own + "</version>")
             + "<properties>" + props
             + "</properties><build>" + build
             + "</build>"
             + "<dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>7.7.7</version></dependency></dependencies></project>\n";
    }

    /// The plugin is `${project.version}` of the root, as in a pragmatica checkout.
    private static String pom(String own) {
        return pomWith(own, "", "<plugins>" + plugin("${project.version}") + "</plugins>");
    }

    private void git(Path co, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("git", "-C", co.toString(), "-c", "user.name=t", "-c", "user.email=t@t"));

        cmd.addAll(List.of(args));
        assertThat(BbFixtures.run(cmd, Map.of(), null, null).rc()).as(String.join(" ", cmd)).isZero();
    }

    private Path checkout(String ciYml, String pomXml, boolean withJar) throws Exception {
        var co = Files.createDirectories(dir.resolve("work").resolve("jb1-" + (++seq)));

        Files.writeString(co.resolve(".gitignore"), ".bb/\n.m2-local/\n");
        Files.createDirectories(co.resolve(".mvn"));
        Files.writeString(co.resolve(".mvn/maven.config"), "-Dmaven.repo.local=.m2-local\n");
        Files.writeString(co.resolve("pom.xml"), pomXml);
        Files.createDirectories(co.resolve(".github/workflows"));
        Files.writeString(co.resolve(".github/workflows/ci.yml"), ciYml);
        git(co, "init", "-q");
        git(co, "add", ".");
        git(co, "commit", "-qm", "c");
        Files.createDirectories(co.resolve(".bb"));
        if (withJar) {
            var jar = co.resolve(".m2-local/org/pragmatica-lite/jbct-maven-plugin/" + VER
                                + "/jbct-maven-plugin-" + VER
                                + ".jar");

            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "jar");
        }

        return co;
    }

    private Path checkout(boolean withJar) throws Exception {
        return checkout(ci(BOOTSTRAP_CMD, GROUP_CMD, ROOT_CMD), pom(VER), withJar);
    }

    private Run run(Path co, String... mvnArgs) throws Exception {
        return run(co, Map.of(), tools.resolve("bb-build"), mvnArgs);
    }

    /// Enqueues nothing: writes the ticket the way bb-build would, then runs the waiter itself to the end.
    private Run run(Path co, Map<String, String> more, Path script, String... mvnArgs) throws Exception {
        var id = (100 + seq++) + "-" + BbFixtures.RUN;
        var rec = dir.resolve("rec-" + id);

        Files.writeString(queue.resolve("waiting").resolve(id),
                          "id=" + id
                         + "\nowner=jb1\nco=" + co
                         + "\nsha=abc\nkey=\ncap=5\nlimit=0\ncreated=" + (System.currentTimeMillis() * 1_000_000L)
                         + "\ngroup=0\nargs=-B x \n");
        var env = new HashMap<String, String>();

        env.put("PATH", System.getenv("PATH"));
        env.put("HOME", dir.toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "jb1");
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_LOADAVG",
                dir.resolve("loadavg").toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_MAX_REACTORS", "5");
        env.put("BB_TEST_MVN",
                dir.resolve("stubmvn").toString());
        env.put("FAKE_MVN_REC", rec.toString());
        env.putAll(more);
        var cmd = new ArrayList<>(List.of("bash", script.toString(), "__run", id));

        cmd.addAll(List.of(mvnArgs));
        var out = BbFixtures.run(cmd, env, null, null);
        var done = queue.resolve("done").resolve(id);

        assertThat(done).as(out.stdout() + out.stderr()).exists();
        var ticket = Files.readAllLines(done);
        var rc = ticket.stream()
                       .filter(l -> l.startsWith("rc="))
                       .reduce((a, b) -> b)
                       .map(l -> Integer.parseInt(l.substring(3)))
                       .orElseThrow();

        return new Run(rc,
                       Files.readString(co.resolve(".bb/build.log")),
                       Files.exists(rec)
                       ? Files.readAllLines(rec)
                       : List.of(),
                       ticket);
    }

    private static void assertBootstrapRanFirst(Run r) {
        assertThat(r.installs()).as("the bootstrap install")
                  .containsExactly("install -B -DskipTests -pl " + LIST + " -am");
        assertThat(r.mvn().get(0)).as("the bootstrap comes before the main run")
                  .startsWith("install -B -DskipTests -pl ");
        assertThat(r.log()).contains(BOOTSTRAP_LINE);
        assertThat(r.ticket()).contains("jbct_bootstrap=" + VER);
    }

    @Test
    void missing_bootstrapsOnceWithTheExactCiList_printsTheLine_thenTheMainRun() throws Exception {
        var r = run(checkout(false), "-B", "jbct:check");

        assertThat(r.rc()).as(r.log()).isZero();
        assertBootstrapRanFirst(r);
        assertThat(r.mains()).containsExactly("-B jbct:check -Daether.requireDocker=true");
        assertThat(r.log().indexOf(BOOTSTRAP_LINE)).as("the line precedes the main run's output")
                  .isLessThan(r.log().indexOf("stub mvn -B jbct:check"));
    }

    @Test
    void aFullyQualifiedPluginGoal_triggersToo() throws Exception {
        var r = run(checkout(false), "-B", "org.pragmatica-lite:jbct-maven-plugin:check", "-pl", "!jbct");

        assertThat(r.rc()).isZero();
        assertBootstrapRanFirst(r);
    }

    @Test
    void present_doesNotInstallAgain() throws Exception {
        var r = run(checkout(true), "-B", "jbct:check");

        assertThat(r.rc()).isZero();
        assertThat(r.installs()).isEmpty();
        assertThat(r.log()).contains("bootstrap: jbct plugin " + VER + " present").doesNotContain("installed into");
        assertThat(r.ticket()).noneMatch(l -> l.startsWith("jbct_bootstrap="));
        assertThat(r.mains()).hasSize(1);
    }

    @Test
    void aNonJbctGoal_noBootstrap_andTheLogHoldsOnlyMaven() throws Exception {
        var r = run(checkout(false), "-B", "clean", "install");

        assertThat(r.rc()).isZero();
        assertThat(r.installs()).isEmpty();
        assertThat(r.log()).doesNotContain("bootstrap:");
        assertThat(r.log()).isEqualTo("stub mvn -B clean install -Daether.requireDocker=true\n");
        assertThat(r.ticket()).noneMatch(l -> l.startsWith("jbct_bootstrap="));
    }

    @Test
    void theJbctProfile_triggers_inEveryForm() throws Exception {
        for (var args : List.of(List.of("-B", "-Pjbct", "verify"),
                                List.of("-B", "-Pacceptance,jbct", "verify"),
                                List.of("-B", "-P", "jbct", "verify"),
                                List.of("-B", "--activate-profiles=a,jbct", "verify"))) {
            var r = run(checkout(false), args.toArray(String[]::new));

            assertThat(r.rc()).as(args + r.log()).isZero();
            assertBootstrapRanFirst(r);
        }
    }

    @Test
    void aDeactivatedOrOtherProfile_doesNotTrigger() throws Exception {
        for (var args : List.of(List.of("-B", "-P!jbct", "verify"),
                                List.of("-B", "-Pjbct-extra", "verify"),
                                List.of("-B", "-P", "-jbct", "verify"))) {
            var r = run(checkout(false), args.toArray(String[]::new));

            assertThat(r.installs()).as(args.toString()).isEmpty();
            assertThat(r.log()).doesNotContain("bootstrap:");
        }
    }

    @Test
    void ciYmlChangedAtHead_theNewListIsUsed() throws Exception {
        var co = checkout(false);

        Files.writeString(co.resolve(".github/workflows/ci.yml"),
                          ci("mvn install -B -DskipTests -pl other/a,jbct/jbct-maven-plugin -am"));
        git(co, "commit", "-qam", "new list");
        var r = run(co, "-B", "jbct:check");

        assertThat(r.installs()).containsExactly("install -B -DskipTests -pl other/a,jbct/jbct-maven-plugin -am");
    }

    @Test
    void aWorkingTreeCopyOfCiYml_doesNotWin_theListOfHeadIs_used() throws Exception {
        var co = checkout(false);

        Files.writeString(co.resolve(".github/workflows/ci.yml"), ci("mvn install -B -DskipTests -pl edited/x -am"));
        var r = run(co, "-B", "jbct:check");

        assertThat(r.rc()).isZero();
        assertThat(r.installs()).containsExactly("install -B -DskipTests -pl " + LIST + " -am");
    }

    private static final String EMBER_CMD = "mvn install -B -DskipTests -pl aether/ember -am";
    private static final String CONSENSUS_CMD = "mvn install -B -DskipTests -pl integrations/consensus -am";

    @Test
    void disagreeingPluginLists_areRefused_nothingRuns() throws Exception {
        var r = run(checkout(ci(BOOTSTRAP_CMD, "mvn install -B -DskipTests -pl jbct/jbct-maven-plugin,jbct/other -am"),
                             pom(VER),
                             false),
                    "-B",
                    "jbct:check");

        assertThat(r.rc()).isEqualTo(84);
        assertThat(r.log()).contains("bootstrap: REFUSED").contains("different -pl lists").contains("(rc=84)");
        assertThat(r.mvn()).isEmpty();
    }

    @Test
    void distractorInstallLines_areIgnored_thePluginListIsPicked() throws Exception {
        var r = run(checkout(ci(EMBER_CMD, BOOTSTRAP_CMD, CONSENSUS_CMD, BOOTSTRAP_CMD, GROUP_CMD, ROOT_CMD),
                             pom(VER),
                             false),
                    "-B",
                    "jbct:check");

        assertThat(r.rc()).isZero();
        assertThat(r.installs()).containsExactly("install -B -DskipTests -pl " + LIST + " -am");
    }

    @Test
    void aDistractorLineAlone_isRefused83_aSubstringOfThePluginNameDoesNotCount() throws Exception {
        var r = run(checkout(ci(EMBER_CMD,
                                "mvn install -B -DskipTests -pl x/jbct/jbct-maven-plugin-y,jbct/jbct-maven-plugins -am"),
                             pom(VER),
                             false),
                    "-B",
                    "jbct:check");

        assertThat(r.rc()).isEqualTo(83);
        assertThat(r.log()).contains("(rc=83)");
        assertThat(r.mvn()).isEmpty();
    }

    @Test
    void realRc6Shape_pickedListIsThePluginsOne_versionIsTheRc6PomsRc5() throws Exception {
        var ciYml = resource("/bb/jbct-bootstrap-rc6-ci.yml");
        var pomXml = resource("/bb/jbct-bootstrap-rc6-pom.xml");
        var r = run(checkout(ciYml, pomXml, false), "-B", "jbct:check");

        assertThat(r.rc()).isZero();
        assertThat(r.log()).contains("bootstrap: jbct plugin 1.0.0-rc5 installed into .m2-local");
        assertThat(r.installs()).containsExactly("install -B -DskipTests -pl " + LIST + " -am");
    }

    @Test
    void realMainShape_pickedListIsThePluginsOne_versionIsMainsRc4() throws Exception {
        var r = run(checkout(resource("/bb/jbct-bootstrap-main-ci.yml"),
                             resource("/bb/jbct-bootstrap-main-pom.xml"),
                             false),
                    "-B",
                    "jbct:check");

        assertThat(r.rc()).isZero();
        assertThat(r.log()).contains("bootstrap: jbct plugin 1.0.0-rc4 installed into .m2-local");
        assertThat(r.installs()).containsExactly("install -B -DskipTests -pl " + LIST + " -am");
    }

    private static String resource(String name) throws IOException {
        try (var in = BbJbctBootstrapIT.class.getResourceAsStream(name)) {
            assertThat(in).as(name).isNotNull();

            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    void noBootstrapLine_isRefused_aVariableListDoesNotCount() throws Exception {
        var r = run(checkout(ci(GROUP_CMD, ROOT_CMD), pom(VER), false), "-B", "jbct:check");

        assertThat(r.rc()).isEqualTo(83);
        assertThat(r.log()).contains("bootstrap: REFUSED").contains("(rc=83)");
        assertThat(r.mvn()).isEmpty();
    }

    @Test
    void anUnresolvableVersion_isRefused_theParentsAndADependencysAreNotTheProjects() throws Exception {
        var r = run(checkout(ci(BOOTSTRAP_CMD), pom(null), false), "-B", "jbct:check");

        assertThat(r.rc()).isEqualTo(87);
        assertThat(r.log()).contains("bootstrap: REFUSED").contains("unresolved").contains("(rc=87)");
        assertThat(r.mvn()).isEmpty();
    }

    @Test
    void anUndefinedPropertyOrADeepChain_isRefused() throws Exception {
        for (var build : List.of(pomWith("5.0.0", "", "<plugins>" + plugin("${nope}") + "</plugins>"),
                                 pomWith("5.0.0",
                                         "<a>${b}</a><b>${c}</b><c>" + VER + "</c>",
                                         "<plugins>" + plugin("${a}") + "</plugins>"))) {
            var r = run(checkout(ci(BOOTSTRAP_CMD), build, false), "-B", "jbct:check");

            assertThat(r.rc()).as(r.log()).isEqualTo(87);
            assertThat(r.mvn()).isEmpty();
        }
    }

    @Test
    void noPluginEntry_isRefused_theRootVersionAloneIsNotTheAnswer() throws Exception {
        var r = run(checkout(ci(BOOTSTRAP_CMD), pomWith(VER, "", ""), false), "-B", "jbct:check");

        assertThat(r.rc()).isEqualTo(82);
        assertThat(r.log()).contains("bootstrap: REFUSED").contains("(rc=82)");
        assertThat(r.mvn()).isEmpty();
    }

    @Test
    void conflictingEntries_areRefused_agreeingOnesAreNot() throws Exception {
        var conflict = pomWith("5.0.0",
                               "",
                               "<pluginManagement><plugins>" + plugin(VER)
                              + "</plugins></pluginManagement>"
                              + "<plugins>" + plugin("4.4.4")
                              + "</plugins>");
        var r = run(checkout(ci(BOOTSTRAP_CMD), conflict, false), "-B", "jbct:check");

        assertThat(r.rc()).isEqualTo(86);
        assertThat(r.log()).contains("(rc=86)");
        assertThat(r.mvn()).isEmpty();
        var agree = pomWith(VER,
                            "<pv>" + VER + "</pv>",
                            "<pluginManagement><plugins>" + plugin("${pv}")
                           + "</plugins></pluginManagement>"
                           + "<plugins>" + plugin("${project.version}")
                           + "</plugins>");
        var ok = run(checkout(ci(BOOTSTRAP_CMD), agree, false), "-B", "jbct:check");

        assertThat(ok.rc()).as(ok.log()).isZero();
        assertThat(ok.log()).contains(BOOTSTRAP_LINE);
    }

    @Test
    void thePluginPinnedApartFromTheProjectVersion_isTheJarTheGuardChecks() throws Exception {
        var pinned = pomWith("5.0.0", "", "<plugins>" + plugin(VER) + "</plugins>");
        var present = run(checkout(ci(BOOTSTRAP_CMD), pinned, true), "-B", "jbct:check");

        assertThat(present.rc()).isZero();
        assertThat(present.log()).contains("bootstrap: jbct plugin " + VER + " present");
        assertThat(present.mvn()).as("only the main run").hasSize(1);
        var missing = run(checkout(ci(BOOTSTRAP_CMD), pinned, false), "-B", "jbct:check");

        assertThat(missing.rc()).isZero();
        assertThat(missing.log()).contains(BOOTSTRAP_LINE);
        assertThat(missing.installs()).hasSize(1);
    }

    @Test
    void aPropertyVersion_isResolved_oneNestingLevelIncluded() throws Exception {
        var direct = pomWith("5.0.0", "<pv>" + VER + "</pv>", "<plugins>" + plugin("${pv}") + "</plugins>");
        var nested = pomWith("5.0.0",
                             "<pv>${pw}</pv><pw>" + VER + "</pw>",
                             "<pluginManagement><plugins>" + plugin("${pv}") + "</plugins></pluginManagement>");

        for (var pom : List.of(direct, nested)) {
            var r = run(checkout(ci(BOOTSTRAP_CMD), pom, false), "-B", "jbct:check");

            assertThat(r.rc()).as(r.log()).isZero();
            assertThat(r.log()).contains(BOOTSTRAP_LINE);
        }
    }

    @Test
    void aWorkingTreeCopyOfThePom_doesNotWin_theOneAtHeadIs_read() throws Exception {
        var co = checkout(false);

        Files.writeString(co.resolve("pom.xml"), pomWith("5.0.0", "", "<plugins>" + plugin("8.8.8") + "</plugins>"));
        var r = run(co, "-B", "jbct:check");

        assertThat(r.rc()).as(r.log()).isZero();
        assertThat(r.log()).contains(BOOTSTRAP_LINE);
    }

    @Test
    void aMavenConfigWithoutRepoLocal_isRefused_theInstallWouldWriteTheSharedRepo() throws Exception {
        var co = checkout(false);

        Files.writeString(co.resolve(".mvn/maven.config"), "-B\n");
        var r = run(co, "-B", "jbct:check");

        assertThat(r.rc()).isEqualTo(85);
        assertThat(r.mvn()).isEmpty();
    }

    @Test
    void aFailedBootstrap_isTheTicketsRc_andTheMainRunNeverStarts() throws Exception {
        var r = run(checkout(false), Map.of("FAKE_INSTALL_RC", "7"), tools.resolve("bb-build"), "-B", "jbct:check");

        assertThat(r.rc()).isEqualTo(7);
        assertThat(r.log()).contains("bootstrap: jbct plugin " + VER + " FAILED rc=7").doesNotContain("installed into");
        assertThat(r.mains()).isEmpty();
        assertThat(r.installs()).hasSize(1);
        assertThat(r.ticket()).noneMatch(l -> l.startsWith("jbct_bootstrap="));
    }

    /// Pre-#64 behaviour for a ticket that is not a jbct run, on a host where the helper was never installed (the state of every host
    /// until its operator runs install-bb-fields.sh): the same mvn argv, a build.log holding only maven's output, no bootstrap ticket line.
    /// Mutation: call the helper unconditionally in bb-build -> "No such file or directory" in the log, rc != 0, red.
    @Test
    void plainVerify_withNoHelperInstalled_isUnaffected_sameArgvSameLogNoBootstrapLine() throws Exception {
        Files.delete(tools.resolve("bb-jbct-bootstrap"));
        var r = run(checkout(false), "-B", "verify");

        assertThat(r.rc()).as(r.log()).isZero();
        assertThat(r.mvn()).containsExactly("-B verify -Daether.requireDocker=true");
        assertThat(r.log()).isEqualTo("stub mvn -B verify -Daether.requireDocker=true\n");
        assertThat(r.ticket()).noneMatch(l -> l.contains("bootstrap"));
    }

    /// A ticket that is not a jbct run never execs the helper even when it is installed.
    /// Mutation: call the helper unconditionally -> the marker exists, red.
    @Test
    void plainVerify_neverExecsAnInstalledHelper() throws Exception {
        var marker = dir.resolve("helper-ran");

        BbFixtures.executable(tools.resolve("bb-jbct-bootstrap"), "#!/bin/sh\ntouch " + marker + "\n");
        var r = run(checkout(false), "-B", "verify");

        assertThat(r.rc()).isZero();
        assertThat(marker).doesNotExist();
        assertThat(r.mains()).containsExactly("-B verify -Daether.requireDocker=true");
    }

    /// A jbct run on a host with no helper: refusal rc 88 (documented in bb-build's header), the named line, the rc on the ticket, no maven at all.
    /// Mutation: drop the presence check on the jbct path -> bash's own "No such file" rc 127, no refusal line, red.
    @Test
    void jbctGoal_withNoHelper_isRefusedRc88_noMaven() throws Exception {
        Files.delete(tools.resolve("bb-jbct-bootstrap"));
        var r = run(checkout(false), "-B", "jbct:check");

        assertThat(r.rc()).as(r.log()).isEqualTo(88);
        assertThat(r.log()).contains("bootstrap: refused: helper ").contains(" missing");
        assertThat(r.ticket()).contains("rc=88");
        assertThat(r.mvn()).isEmpty();
    }

    /// Same refusal for a helper that is present but not executable, and for the profile form.
    @Test
    void jbctProfile_withANonExecutableHelper_isRefusedRc88_noMaven() throws Exception {
        assertThat(tools.resolve("bb-jbct-bootstrap").toFile().setExecutable(false)).isTrue();
        var r = run(checkout(false), "-B", "-Pjbct", "verify");

        assertThat(r.rc()).as(r.log()).isEqualTo(88);
        assertThat(r.log()).contains("bootstrap: refused: helper ").contains(" missing or not executable");
        assertThat(r.mvn()).isEmpty();
    }

    /// Negative control: with the hook gone the same run reaches maven with no bootstrap, and the positive checks above are what notice.
    @Test
    void negativeControl_hookRemoved_theMainRunHasNoBootstrap_andTheCheckGoesRed() throws Exception {
        var stripped = Files.readAllLines(tools.resolve("bb-build"))
                            .stream()
                            .filter(l -> !l.contains("bb-jbct-bootstrap\" \"$CO\""))
                            .collect(Collectors.joining("\n", "", "\n"));
        // its own directory and the name bb-build: the queue finds a waiter by the text "bb-build __run <id>"
        var bare = Files.createDirectories(dir.resolve("nohook"));

        BbFixtures.executable(bare.resolve("bb-build"), stripped);
        Files.copy(tools.resolve("bb-queue-lib.sh"), bare.resolve("bb-queue-lib.sh"));
        var r = run(checkout(false), Map.of(), bare.resolve("bb-build"), "-B", "jbct:check");

        assertThat(r.installs()).isEmpty();
        assertThat(r.mains()).hasSize(1);
        assertThatThrownBy(() -> assertBootstrapRanFirst(r)).isInstanceOf(AssertionError.class);
    }
}
