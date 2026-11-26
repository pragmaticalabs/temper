package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;


/// #132: a ticket's evidence must not claim more test executions than it has distinct per-run reports for. Maven writes every run of
/// `surefire:test` to the same target/surefire-reports files, so N repeated goals in one ticket are one report set: bb-build refuses
/// the call at submit (rc 2, nothing queued, the message names `bb-test --repeat N`, which keeps one report directory per run).
/// The unit is the test-running goal: surefire `test` (the `test` phase and every later phase run it) and failsafe `integration-test`
/// (the `integration-test` phase and every later one). A goal list that runs a unit more than once is refused, whatever the form of
/// the second naming: phase, `prefix:goal`, `groupId:artifactId[:version]:goal`, `goal@execution`.
///
/// Probes (mutate ops/bb/bb-build `repeated_test_goal`, the named rows turn red): count only the phases, not the goals ->
/// [#surefireGoalTwice_isRefused]; drop the failsafe unit -> [#verifyTwice_isRefused]; ignore `@execution` -> [#differentExecutionIds_areRefused];
/// do not skip the value of `-pl` -> [#aModuleNamedTest_isNotAGoal]; refuse every goal (no count) -> the `_queues` rows.
class BbRepeatedGoalIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");
    private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();
    private Path co;
    private Path queue;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var tools = dir.resolve("tools");

        BbFixtures.executable(tools.resolve("bb-build"), Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        for (var f : List.of("bb-queue-lib.sh", "bb-portgroup.py", "bb-modules.py")) {
            Files.copy(BbFixtures.OPS_BB.resolve(f), tools.resolve(f));
        }

        BbFixtures.executable(tools.resolve("bb-cp-record"), "#!/bin/sh\nexit 0\n");
        BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\n");
        BbFixtures.executable(dir.resolve("fakebin/docker"), "#!/bin/sh\nexit 0\n");
        queue = dir.resolve("q");
        co = Files.createDirectories(dir.resolve("work").resolve("ag-co"));
        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }
    }

    @AfterEach
    void killWaiters() throws Exception {
        for (var id : tickets) {
            BbFixtures.kill(BbFixtures.waiters(id));
        }
    }

    private BbFixtures.Outcome submit(Map<String, String> extra, String... args) throws Exception {
        var env = new HashMap<String, String>();

        env.put("PATH", dir.resolve("fakebin") + ":" + System.getenv("PATH"));
        env.put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "ag");
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", dir.resolve("stub/mvn").toString());
        env.put("BB_PORT_GROUP", "0");
        env.putAll(extra);
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(), co.toString()));

        command.addAll(List.of(args));
        var run = BbFixtures.run(command, env, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        if (matcher.find()) {
            tickets.add(matcher.group(1));
        }

        return run;
    }

    private long ticketsOnDisk() throws Exception {
        try (Stream<Path> all = Stream.of("waiting", "running", "done")
                                      .map(queue::resolve)
                                      .filter(Files::isDirectory)
                                      .flatMap(d -> {
                                          try {
                                              return Files.list(d);
                                          } catch (java.io.IOException e) {
                                              throw new IllegalStateException(e);
                                          }
                                      })) {
            return all.count();
        }
    }

    private void refused(String... args) throws Exception {
        var run = submit(Map.of(), args);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(2);
        assertThat(run.stderr()).contains("bb-test --repeat N").contains("more than once");
        assertThat(run.stdout()).doesNotContain("queued ticket=");
        assertThat(ticketsOnDisk()).as("nothing queued").isZero();
        assertThat(co.resolve(".bb/queued")).doesNotExist();
    }

    private void queues(String... args) throws Exception {
        var run = submit(Map.of(), args);

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("queued ticket=");
    }

    // ---- the breaking inputs, each refused --------------------------------------------------------------------------
    @Test
    void surefireGoalTwice_isRefused() throws Exception {
        refused("surefire:test", "surefire:test");
    }

    @Test
    void aPhaseAndTheGoalItRuns_areRefused() throws Exception {
        refused("test", "surefire:test");
        refused("surefire:test", "test");
    }

    @Test
    void theFullCoordinateTwice_isRefused() throws Exception {
        refused(SUREFIRE + ":3.5.4:test", SUREFIRE + ":3.5.4:test");
        refused(SUREFIRE + ":test", SUREFIRE + ":test");
    }

    @Test
    void thePrefixAndTheFullCoordinate_areTheSameGoal() throws Exception {
        refused("surefire:test", SUREFIRE + ":3.5.4:test");
        refused(SUREFIRE + ":test", "surefire:test");
    }

    @Test
    void differentExecutionIds_areRefused() throws Exception {
        refused("surefire:test@a", "surefire:test@b");
        refused("test", "surefire:test@extra");
    }

    @Test
    void verifyTwice_isRefused() throws Exception {
        refused("verify", "verify");
    }

    @Test
    void failsafeGoalTwiceAndMixedWithItsPhases_areRefused() throws Exception {
        refused("failsafe:integration-test", "failsafe:integration-test");
        refused("integration-test", "failsafe:integration-test");
        refused("verify", "failsafe:integration-test@it2");
        refused("org.apache.maven.plugins:maven-failsafe-plugin:3.5.4:integration-test", "failsafe:integration-test");
    }

    @Test
    void aLaterPhaseRunsTheEarlierGoalsToo_soTestThenInstall_isRefused() throws Exception {
        refused("test", "install");
        refused("package", "verify");
        refused("pre-integration-test", "test");
        refused("prepare-package", "test");
        refused("post-integration-test", "test");
        refused("deploy", "test");
        refused("verify", "install");
    }

    @Test
    void optionsAndTheirValuesAreAroundTheGoals_notBetweenThemAndTheCount() throws Exception {
        refused("-B", "-pl", "core", "-am", "-Dtest=Foo", "surefire:test", "-T", "2", "surefire:test");
        refused("-DskipITs", "-D", "x=y", "test", "-q", "surefire:test");
    }

    // ---- what must still queue ----------------------------------------------------------------------------------------
    @Test
    void cleanInstall_queues() throws Exception {
        queues("clean", "install");
    }

    @Test
    void testCompileThenTheGoal_queues() throws Exception {
        queues("test-compile", "surefire:test");
    }

    @Test
    void aSingleVerify_queues() throws Exception {
        queues("verify");
    }

    @Test
    void installSkippingTests_queues() throws Exception {
        queues("install", "-DskipTests");
    }

    @Test
    void theNoArgDefault_queues() throws Exception {
        queues();
    }

    @Test
    void oneSurefireAndOneFailsafeGoal_queue() throws Exception {
        queues("surefire:test", "failsafe:integration-test", "failsafe:verify");
    }

    @Test
    void theFailsafeVerifyGoalTwice_runsNoTests_andQueues() throws Exception {
        queues("failsafe:verify", "failsafe:verify");
    }

    @Test
    void aModuleNamedTest_isNotAGoal() throws Exception {
        queues("-pl", "test", "test");
        queues("-rf", ":verify", "-pl", "verify", "package");
    }

    @Test
    void aThirdPartyPluginWithATestGoal_isNotSurefire() throws Exception {
        queues("jbct:check", "other:test", "other:test", "g:surefire:test", "g:surefire:test");
    }

    @Test
    void aBbTestLaunch_isNotAGoalList() throws Exception {
        var run = submit(Map.of("BB_LAUNCH", "/bin/true", "BB_DEDUP", "0"), "__launch", "core", "3", "test", "test");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isZero();
        assertThat(run.stdout()).contains("queued ticket=");
    }

    @Test
    void underTheLegacyDirectPath_theRepeatIsRefusedToo() throws Exception {
        var run = submit(Map.of("BB_QUEUE", "0"), "surefire:test", "surefire:test");

        assertThat(run.rc()).as(run.stdout() + run.stderr()).isEqualTo(2);
        assertThat(run.stderr()).contains("bb-test --repeat N");
    }
}
