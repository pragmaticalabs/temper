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
///
/// Probes: put `printf '%s' "$A" |` back in front of any one grep of a guard -> that guard's `_withALongArgList` row is red (its control stays green).
class BbBuildArgGuardIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");

    private static final String SPRT = "aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamPublishReshuffleTest.java";

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();
    private Path co;
    private Map<String, String> env;

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
        BbFixtures.executable(tools.resolve("bb-cp-record"), "#!/bin/sh\nexit 0\n");
        BbFixtures.executable(tools.resolve("bb-netns-run"), "#!/bin/sh\necho \"NETNS $*\"\n");
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\n");

        BbFixtures.executable(dir.resolve("fakebin/docker"), "#!/bin/sh\nexit 0\n");
        co = dir.resolve("work").resolve("ag-co");
        Files.createDirectories(co);
        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        Files.createDirectories(co.resolve(SPRT).getParent());
        Files.writeString(co.resolve(SPRT), "class StreamPublishReshuffleTest {}\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
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

    /// the match words first, then `pad` bytes of further args (40 bytes each): the grep is satisfied in its first block.
    private BbFixtures.Outcome run(Map<String, String> extra, int pad, String... head) throws Exception {
        setUp(extra);
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(),
                                              co.toString()));

        command.addAll(List.of(head));
        for (var i = 0; i < pad / 40; i++) {
            command.add("-Dpad" + i + "=" + "x".repeat(30));
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
}
