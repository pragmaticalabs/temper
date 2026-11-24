package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// `ops/bb/bb-build`'s argument guards (the #1553 host-killing test, the failsafe filter, the cap-1 module warning) match the maven args
/// with `grep -q`. #102: fed through `printf | grep -q`, grep exits at its first match, printf dies of SIGPIPE on an arg list larger than
/// the pipe buffer, and under pipefail that status turns a MATCH into "no match": the guard silently does not fire. Each guard is run with
/// the match first and 300 KB of further args after it, and once with no padding (the control: the guard itself works).
/// The padding must be many LINES, not many words: bb-build joins the args with `$*`, so separate words alone make one line, and grep
/// reads a single long line to its end (match, no SIGPIPE: the old form passes). Each padding word therefore carries 100 newlines.
///
/// #112 UNINSTALLED SIBLING (rc 11): `-pl` without -am/-amd is refused at submit, nothing queued, when a reactor module the selected ones depend on has
/// no pom in the checkout's `.m2-local`. Reactor: root -> a, b (depends on a), c (depends on b), d (no deps); version 1, group g.x.
/// Phase and scope: only a lifecycle phase at or after `compile` is checked; before `test-compile` the `test` scope is left out; a pom-packaged selected module
/// resolves nothing; past the selected module only compile/runtime edges are followed; a dependency matches a reactor module by (groupId, artifactId).
/// Probes (each mutation of ops/bb/bb-modules.py `installed_check` turns the named rows red):
///  - drop `-am`/`--also-make` from the tuple -> [#theUninstalledSiblingGuard_queuesWithAm]
///  - drop `-amd`/`--also-make-dependents` from the tuple -> [#theUninstalledSiblingGuard_queuesWithAmd]
///  - drop the `isfile` test (every needed module counts as missing) -> [#theUninstalledSiblingGuard_queuesWhenTheSiblingIsInstalled]
///  - match a dependency by artifactId alone (not (groupId, artifactId)) -> [#theUninstalledSiblingGuard_doesNotCountAnExternalArtifactWhoseArtifactIdNamesAReactorModule]
///  - drop the lifecycle-phase gate (any `-pl` run is checked) -> [#theUninstalledSiblingGuard_neverRefusesAPluginGoalAlone]
///  - drop the `test`-scope cut before test-compile -> [#theUninstalledSiblingGuard_queuesACompileWhenOnlyATestScopeSiblingIsMissing]
///  - count the dependencies of a pom-packaged selected module like a jar module's -> [#theUninstalledSiblingGuard_queuesAPomPackagedModule]
///  - drop the transitive step (direct dependencies only) -> [#theUninstalledSiblingGuard_followsTheDependenciesOfADependencyTransitively]
///  - follow every scope past the selected module -> [#theUninstalledSiblingGuard_doesNotFollowATestScopeEdgeOfADependency]
///  - drop the `.strip()` of groupId, artifactId and version -> [#theUninstalledSiblingGuard_stripsWhitespaceAroundCoordinates],
///    [#theUninstalledSiblingGuard_findsAnInstalledSiblingWhoseCoordinatesArePadded]
///  - drop `- selected` (the selected modules count as needed) -> [#theUninstalledSiblingGuard_queuesAModuleWithNoReactorDependencies],
///    [#theUninstalledSiblingGuard_neverAsksForTheSelectedModulesThemselves]
///
/// Probes: put `printf '%s' "$A" |` back in front of any one grep of a guard -> that guard's `_withALongArgList` row is red (its control stays green).
/// Drop `[^!]` from the named-test guard -> [#theHostKillingTestGuard_letsAnExcludedRunThrough] red.
class BbBuildArgGuardIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");

    private static final String SPRT = "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamPublishReshuffleTest.java";

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();
    private Path co;
    private Map<String, String> env;
    private boolean reactor;
    private List<String> installed = List.of();
    private final Map<String, String> poms = new HashMap<>();
    private boolean pomModule;

    @BeforeEach
    void linuxOnly() {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
    }

    @AfterEach
    void killWaiters() throws Exception {
        for (var id : tickets) {
            BbFixtures.kill(BbFixtures.waiters(id));
        }
    }

    private void setUp(Map<String, String> extra) throws Exception {
        var tools = dir.resolve("tools");

        BbFixtures.executable(tools.resolve("bb-build"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"),
                   tools.resolve("bb-queue-lib.sh"),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(BbFixtures.OPS_BB.resolve("bb-portgroup.py"),
                   tools.resolve("bb-portgroup.py"),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(BbFixtures.OPS_BB.resolve("bb-modules.py"),
                   tools.resolve("bb-modules.py"),
                   StandardCopyOption.REPLACE_EXISTING);
        BbFixtures.executable(tools.resolve("bb-cp-record"), "#!/bin/sh\nexit 0\n");
        BbFixtures.executable(tools.resolve("bb-netns-run"), "#!/bin/sh\necho \"NETNS $*\"\n");
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\n");

        BbFixtures.executable(dir.resolve("fakebin/docker"), "#!/bin/sh\nexit 0\n");
        co = dir.resolve("work").resolve("ag-co");
        Files.createDirectories(co);
        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        Files.createDirectories(co.resolve(SPRT).getParent());
        Files.writeString(co.resolve(SPRT), "class StreamPublishReshuffleTest {}\n");
        if (reactor) {
            writeReactor();
        }

        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }

        for (var module : installed) {
            var pom = co.resolve(".m2-local/g/x/" + module + "/1/" + module + "-1.pom");

            Files.createDirectories(pom.getParent());
            Files.writeString(pom, "<project/>\n");
        }

        env = new HashMap<>();
        env.put("PATH",
                dir.resolve("fakebin") + ":" + System.getenv("PATH"));
        env.put("HOME",
                Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "ag");
        env.put("BB_TEST_QUEUE_ROOT",
                dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
        env.putAll(extra);
    }

    private static final String NS = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>";

    private static String pom(String artifact, String... deps) {
        var dependencies = new StringBuilder();

        for (var dep : deps) {
            dependencies.append("<dependency><groupId>g.x</groupId><artifactId>")
                        .append(dep)
                        .append("</artifactId></dependency>");
        }

        return NS
             + "<parent><groupId>g.x</groupId><artifactId>root</artifactId><version>1</version></parent><artifactId>" + artifact
             + "</artifactId>" + (deps.length == 0
                                  ? ""
                                  : "<dependencies>" + dependencies + "</dependencies>")
             + "</project>\n";
    }

    private static String dep(String group, String artifact, String scope) {
        return "<dependency><groupId>" + group
             + "</groupId><artifactId>" + artifact
             + "</artifactId>" + (scope.isEmpty()
                                  ? ""
                                  : "<scope>" + scope + "</scope>")
             + "</dependency>";
    }

    /// a module pom of the fixture reactor with raw `<dependency>` elements; `extra` is raw text put before `<artifactId>` (a padded `<version>`)
    private static String pomWith(String artifact, String packaging, String extra, String... deps) {
        return NS
             + "<parent><groupId>g.x</groupId><artifactId>root</artifactId><version>1</version></parent>" + extra
             + "<artifactId>" + artifact
             + "</artifactId>" + (packaging.isEmpty()
                                  ? ""
                                  : "<packaging>" + packaging + "</packaging>")
             + "<dependencies>" + String.join("", deps)
             + "</dependencies></project>\n";
    }

    private void writeReactor() throws Exception {
        Files.writeString(co.resolve("pom.xml"),
                          NS
                         + "<groupId>g.x</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules>"
                         + "<module>a</module><module>b</module><module>c</module><module>d</module>" + (pomModule
                                                                                                         ? "<module>p</module>"
                                                                                                         : "")
                         + "</modules></project>\n");
        var modules = Map.of("a", new String[0], "b", new String[]{"a"}, "c", new String[]{"b"}, "d", new String[0]);

        for (var module : modules.entrySet()) {
            Files.createDirectories(co.resolve(module.getKey()));
            Files.writeString(co.resolve(module.getKey()).resolve("pom.xml"),
                              pom(module.getKey(), module.getValue()));
        }

        if (pomModule) {
            Files.createDirectories(co.resolve("p"));
            Files.writeString(co.resolve("p/pom.xml"), pomWith("p", "pom", "", dep("g.x", "a", "")));
        }

        for (var override : poms.entrySet()) {
            Files.writeString(co.resolve(override.getKey()),
                              override.getValue());
        }
    }

    private static final int LINES_PER_WORD = 100;
    private static final int WORD_BYTES = LINES_PER_WORD * 31;

    /// the match words first, then `pad` bytes of further args: separate words, each of 100 newline-ended lines (31 bytes a line), so the
    /// arg string has thousands of lines and grep is satisfied in its first block while the producer still has most of it to write.
    private BbFixtures.Outcome run(Map<String, String> extra, int pad, String... head) throws Exception {
        setUp(extra);
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(),
                                              co.toString()));

        command.addAll(List.of(head));
        for (var i = 0; i < pad / WORD_BYTES; i++) {
            command.add("-Dpad" + i + "=" + ("x".repeat(30) + "\n").repeat(LINES_PER_WORD));
        }

        var run = BbFixtures.run(command, env, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        if (matcher.find()) {
            tickets.add(matcher.group(1));
        }

        return run;
    }

    private static final int LONG = 300_000;
    private static final String FAILSAFE = "forge-tests runs through failsafe";
    private static final String HEAVY = "Heavy/e2e forge selection";
    private static final String NAMED = "names StreamPublishReshuffleTest";
    private static final String CAP1 = "WARNING: BB_MAX_REACTORS=1 is for root builds only";

    @Test
    void theFailsafeFilterGuard_refusesTheRun() throws Exception {
        var run = run(Map.of(), 0, "-B", "-pl", "aether/forge/forge-tests", "-Dtest=Foo", "install");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(10);
        assertThat(run.stderr()).contains(FAILSAFE);
    }

    @Test
    void theFailsafeFilterGuard_refusesTheRun_withALongArgList() throws Exception {
        var run = run(Map.of(), LONG, "-B", "-pl", "aether/forge/forge-tests", "-Dtest=Foo", "install");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(10);
        assertThat(run.stderr()).contains(FAILSAFE);
    }

    @Test
    void theHostKillingTestGuard_refusesANamedRun() throws Exception {
        var run = run(Map.of(), 0, "-B", "verify", "-Dit.test=StreamPublishReshuffleTest");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(6);
        assertThat(run.stderr()).contains(NAMED);
    }

    @Test
    void theHostKillingTestGuard_refusesANamedRun_withALongArgList() throws Exception {
        var run = run(Map.of(), LONG, "-B", "verify", "-Dit.test=StreamPublishReshuffleTest");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(6);
        assertThat(run.stderr()).contains(NAMED);
    }

    @Test
    void theHostKillingTestGuard_refusesAHeavySelection() throws Exception {
        var run = run(Map.of(), 0, "-B", "verify", "-Pwith-e2e");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(6);
        assertThat(run.stderr()).contains(HEAVY);
    }

    @Test
    void theHostKillingTestGuard_refusesAHeavySelection_withALongArgList() throws Exception {
        var run = run(Map.of(), LONG, "-B", "verify", "-Pwith-e2e");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(6);
        assertThat(run.stderr()).contains(HEAVY);
    }

    /// the `[^!]` of the named-test guard: an exclusion (`!StreamPublishReshuffleTest`) is not a naming. Dropping `[^!]` refuses it (rc 6).
    @Test
    void theHostKillingTestGuard_letsAnExcludedRunThrough() throws Exception {
        var run = run(Map.of(), 0, "-B", "verify", "-Pwith-e2e", "-Dit.test=!StreamPublishReshuffleTest");

        assertThat(run.stderr()).doesNotContain(NAMED, HEAVY);
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isNotEqualTo(6);
        assertThat(run.stdout()).contains("queued ticket=");
    }

    @Test
    void theCapOneGuard_warnsOnAModuleBuild() throws Exception {
        var run = run(Map.of("BB_MAX_REACTORS", "1"), 0, "-B", "-pl", "some-module", "install");

        assertThat(run.stderr()).contains(CAP1);
    }

    @Test
    void theCapOneGuard_warnsOnAModuleBuild_withALongArgList() throws Exception {
        var run = run(Map.of("BB_MAX_REACTORS", "1"), LONG, "-B", "-pl", "some-module", "install");

        assertThat(run.stderr()).contains(CAP1);
    }

    @Test
    void theGuards_leaveAPlainLongBuildAlone() throws Exception {
        var run = run(Map.of(), LONG, "-B", "-pl", "some-module", "install");

        assertThat(run.stderr()).doesNotContain(FAILSAFE, HEAVY, NAMED, CAP1);
        assertThat(run.stdout()).contains("queued ticket=");
    }

    private static final String SIBLING = "add -am, or install them first";

    private void assertRefused(BbFixtures.Outcome run, String... missing) throws Exception {
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(11);
        assertThat(run.stderr()).contains(SIBLING).contains(missing);
        assertThat(run.stdout()).doesNotContain("queued ticket=");
        assertThat(co.resolve(".bb/queued")).doesNotExist();
        try (var waiting = Files.list(Files.createDirectories(dir.resolve("q/waiting")))) {
            assertThat(waiting.toList()).as("nothing queued").isEmpty();
        }
    }

    private void assertQueued(BbFixtures.Outcome run) {
        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stderr()).doesNotContain(SIBLING);
        assertThat(run.stdout()).contains("queued ticket=");
    }

    @Test
    void theUninstalledSiblingGuard_refusesAPlWithoutAm_namingTheMissingModule_andQueuesNothing() throws Exception {
        reactor = true;
        assertRefused(run(Map.of(), 0, "-B", "-pl", "b", "test"), "a=g.x:a:1");
    }

    @Test
    void theUninstalledSiblingGuard_refusesALongArgList() throws Exception {
        reactor = true;
        assertRefused(run(Map.of(), LONG, "-B", "-pl", "b", "test"), "a=g.x:a:1");
    }

    @Test
    void theUninstalledSiblingGuard_namesEveryMissingModule_notTheInstalledOnes() throws Exception {
        reactor = true;
        installed = List.of("a");
        var run = run(Map.of(), 0, "-B", "-pl", "c", "test");

        assertRefused(run, "b=g.x:b:1");
        assertThat(run.stderr()).doesNotContain("a=g.x:a:1");
    }

    /// the first spelling sets the tree up, the others run bb-build again in it (a refusal leaves nothing behind)
    @Test
    void theUninstalledSiblingGuard_readsEverySpellingOfPl() throws Exception {
        reactor = true;
        assertRefused(run(Map.of(), 0, "-B", "-pl=b", "test"), "a=g.x:a:1");
        for (var spelling : List.of(List.of("-pl", ":b"),
                                    List.of("-pl", "g.x:b"),
                                    List.of("--projects", "b"),
                                    List.of("--projects=b"),
                                    List.of("-plb"),
                                    List.of("-pl", "?b"),
                                    List.of("-pl", "b,d"),
                                    List.of("-pl", "!a"))) {
            var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(),
                                                  co.toString(),
                                                  "-B"));

            command.addAll(spelling);
            command.add("test");
            assertRefused(BbFixtures.run(command, env, null, null), "a=g.x:a:1");
        }
    }

    @Test
    void theUninstalledSiblingGuard_queuesWithAm() throws Exception {
        reactor = true;
        assertQueued(run(Map.of(), 0, "-B", "-pl", "b", "-am", "test"));
    }

    @Test
    void theUninstalledSiblingGuard_queuesWithAlsoMake() throws Exception {
        reactor = true;
        assertQueued(run(Map.of(), 0, "-B", "--also-make", "-pl", "b", "test"));
    }

    @Test
    void theUninstalledSiblingGuard_queuesWithAmd() throws Exception {
        reactor = true;
        assertQueued(run(Map.of(), 0, "-B", "-pl", "b", "-amd", "test"));
    }

    @Test
    void theUninstalledSiblingGuard_queuesWhenTheSiblingIsInstalled() throws Exception {
        reactor = true;
        installed = List.of("a");
        assertQueued(run(Map.of(), 0, "-B", "-pl", "b", "test"));
    }

    @Test
    void theUninstalledSiblingGuard_queuesAModuleWithNoReactorDependencies() throws Exception {
        reactor = true;
        assertQueued(run(Map.of(), 0, "-B", "-pl", "a", "test"));
    }

    /// `-pl a,b`: b's sibling a is itself selected, so it is built in the reactor and needs no jar
    @Test
    void theUninstalledSiblingGuard_neverAsksForTheSelectedModulesThemselves() throws Exception {
        reactor = true;
        assertQueued(run(Map.of(), 0, "-B", "-pl", "a,b", "test"));
    }

    /// runs bb-build again in the tree the first `run` set up (a refusal leaves nothing behind)
    private BbFixtures.Outcome again(String... args) throws Exception {
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(),
                                              co.toString(),
                                              "-B"));

        command.addAll(List.of(args));
        var run = BbFixtures.run(command, env, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        if (matcher.find()) {
            tickets.add(matcher.group(1));
        }

        return run;
    }

    /// a dependency is a reactor module only when groupId AND artifactId match: external g2:a is not the reactor's g.x:a
    @Test
    void theUninstalledSiblingGuard_doesNotCountAnExternalArtifactWhoseArtifactIdNamesAReactorModule() throws Exception {
        reactor = true;
        poms.put("b/pom.xml", pomWith("b", "", "", dep("g2", "a", "")));
        assertQueued(run(Map.of(), 0, "-B", "-pl", "b", "test"));
    }

    /// a groupId written as a property of the pom is substituted before it is matched
    @Test
    void theUninstalledSiblingGuard_substitutesAPropertyGroupId() throws Exception {
        reactor = true;
        poms.put("b/pom.xml", pomWith("b", "", "", dep("${project.groupId}", "a", "")));
        assertRefused(run(Map.of(), 0, "-B", "-pl", "b", "test"), "a=g.x:a:1");
    }

    /// a groupId still holding `${` while the artifactId names a reactor module cannot be placed: not refused, a note
    @Test
    void theUninstalledSiblingGuard_doesNotRefuseAGroupIdItCannotSubstitute() throws Exception {
        reactor = true;
        poms.put("b/pom.xml", pomWith("b", "", "", dep("${no.such.group}", "a", "")));
        var run = run(Map.of(), 0, "-B", "-pl", "b", "test");

        assertQueued(run);
        assertThat(run.stderr()).contains("-pl check skipped: cannot resolve a dependency coordinate of b");
    }

    /// before test-compile only the non-test scopes are resolved: a missing test-scope sibling does not stop `compile`
    @Test
    void theUninstalledSiblingGuard_queuesACompileWhenOnlyATestScopeSiblingIsMissing() throws Exception {
        reactor = true;
        poms.put("b/pom.xml", pomWith("b", "", "", dep("g.x", "a", "test")));
        assertQueued(run(Map.of(), 0, "-B", "-pl", "b", "compile"));
    }

    /// from test-compile on every scope is resolved
    @Test
    void theUninstalledSiblingGuard_refusesATestRunWhenATestScopeSiblingIsMissing() throws Exception {
        reactor = true;
        poms.put("b/pom.xml", pomWith("b", "", "", dep("g.x", "a", "test")));
        assertRefused(run(Map.of(), 0, "-B", "-pl", "b", "test"), "a=g.x:a:1");
        assertRefused(again("-pl", "b", "test-compile"), "a=g.x:a:1");
    }

    /// a plugin goal with no lifecycle phase resolves nothing this check looks for: never refused, whatever it is (`-P test` is a profile, not a phase)
    @Test
    void theUninstalledSiblingGuard_neverRefusesAPluginGoalAlone() throws Exception {
        reactor = true;
        assertQueued(run(Map.of(), 0, "-B", "-pl", "b", "jbct:check"));
        assertQueued(again("-pl", "b", "jbct:format"));
        assertQueued(again("-pl", "b", "clean"));
        assertQueued(again("-pl", "b", "-P", "test", "jbct:check"));
        assertRefused(again("-pl", "b", "-P", "test", "verify"), "a=g.x:a:1");
    }

    /// a pom-packaged selected module resolves nothing of its own: its dependency on the missing `a` and its own missing artifact do not refuse
    @Test
    void theUninstalledSiblingGuard_queuesAPomPackagedModule() throws Exception {
        reactor = true;
        pomModule = true;
        assertQueued(run(Map.of(), 0, "-B", "-pl", "p", "test"));
    }

    /// `-pl c`: c needs b, b needs a: both must be installed, a is named though c does not depend on it directly
    @Test
    void theUninstalledSiblingGuard_followsTheDependenciesOfADependencyTransitively() throws Exception {
        reactor = true;
        installed = List.of("b");
        var run = run(Map.of(), 0, "-B", "-pl", "c", "test");

        assertRefused(run, "a=g.x:a:1");
        assertThat(run.stderr()).doesNotContain("b=g.x:b:1");
    }

    /// past the selected module only compile and runtime edges are followed: b's test-scope `a` is not resolved for c
    @Test
    void theUninstalledSiblingGuard_doesNotFollowATestScopeEdgeOfADependency() throws Exception {
        reactor = true;
        installed = List.of("b");
        poms.put("b/pom.xml", pomWith("b", "", "", dep("g.x", "a", "test")));
        assertQueued(run(Map.of(), 0, "-B", "-pl", "c", "test"));
    }

    /// maven trims groupId, artifactId and version: padded coordinates still match, and the message holds the trimmed ones
    @Test
    void theUninstalledSiblingGuard_stripsWhitespaceAroundCoordinates() throws Exception {
        reactor = true;
        poms.put("a/pom.xml", pomWith(" a ", "", "<groupId> g.x </groupId><version> 1 </version>"));
        poms.put("b/pom.xml", pomWith("b", "", "", dep(" g.x ", " a ", "")));
        assertRefused(run(Map.of(), 0, "-B", "-pl", "b", "test"), "a=g.x:a:1");
    }

    @Test
    void theUninstalledSiblingGuard_findsAnInstalledSiblingWhoseCoordinatesArePadded() throws Exception {
        reactor = true;
        installed = List.of("a");
        poms.put("a/pom.xml", pomWith(" a ", "", "<groupId> g.x </groupId><version> 1 </version>"));
        poms.put("b/pom.xml", pomWith("b", "", "", dep(" g.x ", " a ", "")));
        assertQueued(run(Map.of(), 0, "-B", "-pl", "b", "test"));
    }

    @Test
    void theUninstalledSiblingGuard_leavesAWholeReactorBuildAlone() throws Exception {
        reactor = true;
        assertQueued(run(Map.of(), 0, "-B", "test"));
    }

    /// a form it cannot resolve is not refused: a note on stderr, the run queued
    @Test
    void theUninstalledSiblingGuard_doesNotRefuseASelectorItCannotResolve() throws Exception {
        reactor = true;
        var run = run(Map.of(), 0, "-B", "-pl", "nope", "test");

        assertQueued(run);
        assertThat(run.stderr()).contains("-pl check skipped: cannot resolve -pl selector nope");
    }

    /// --since builds its own `-pl` from the closure of the changed modules: unchanged by the guard, with nothing installed
    @Test
    void theUninstalledSiblingGuard_leavesSinceModeAlone() throws Exception {
        reactor = true;
        setUp(Map.of());
        Files.writeString(co.resolve("c/new.txt"), "x\n");
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(),
                                              "--since",
                                              "HEAD",
                                              co.toString(),
                                              "test"));
        var run = BbFixtures.run(command, env, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        if (matcher.find()) {
            tickets.add(matcher.group(1));
        }

        assertQueued(run);
        assertThat(run.stdout()).contains("-pl a,b,c");
    }
}
