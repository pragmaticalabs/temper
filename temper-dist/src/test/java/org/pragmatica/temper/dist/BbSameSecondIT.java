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


/// Issue #80: `ops/bb/bb-build` dedup identifies the checkout's current result by the run's TICKET (`.bb/ticket`), not by its start second.
/// A `date` stub first on PATH answers `+%s` with one fixed epoch, so every launch in the test shares a start second on demand; every other
/// `date` call (ticket ids, `-Is`, `-d`) is passed through to the real one.
///
/// Dedup decision for a checkout without `.bb/ticket` (a run launched before this change): NOT current, so no dedup. That is the safe direction:
/// the cost is one redundant build, never a stale answer.
///
/// Probes (each red, reverted): compare `.bb/start` to the ticket's `started=` again -> [#aSecondBuildInTheSameSecond_doesNotMakeTheFirstOneCurrent];
/// stop writing `.bb/ticket` -> [#aRepeatWithNothingBetween_isStillDeduped].
class BbSameSecondIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();
    private Path co;
    private Map<String, String> env;

    @BeforeEach
    void linuxOnly() throws Exception {
        assumeTrue(BbFixtures.linux(), "bb-build needs GNU tools, flock and pgrep");
        var tools = dir.resolve("tools");

        BbFixtures.executable(tools.resolve("bb-build"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(BbFixtures.OPS_BB.resolve("bb-portgroup.py"), tools.resolve("bb-portgroup.py"), StandardCopyOption.REPLACE_EXISTING);
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\n");

        BbFixtures.executable(dir.resolve("fakebin/date"),
                              "#!/bin/sh\nif [ \"$*\" = \"+%s\" ]; then echo 1700000000; else exec /bin/date \"$@\"; fi\n");
        co = dir.resolve("work").resolve("ss-co");
        initRepo(co);
        env = new HashMap<>();
        env.put("PATH", dir.resolve("fakebin") + ":" + System.getenv("PATH"));
        env.put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "ss");
        env.put("BB_TEST_QUEUE_ROOT", dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
    }

    @AfterEach
    void killWaiters() throws Exception {
        for (var id : tickets) {
            BbFixtures.kill(BbFixtures.waiters(id));
        }
    }

    private static void initRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        Files.writeString(repo.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", repo.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }
    }

    private record Build(String ticket, BbFixtures.Outcome outcome) {}

    /// Enqueue and, when it was queued, wait for the build and its ticket to finish.
    private Build build(String... args) throws Exception {
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(), co.toString()));

        command.addAll(List.of(args));
        var run = BbFixtures.run(command, env, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        if (!matcher.find()) {
            return new Build(null, run);
        }

        var id = matcher.group(1);

        tickets.add(id);
        var rc = co.resolve(".bb/rc");

        for (var i = 0; i < 300 && !Files.exists(rc); i++) {
            Thread.sleep(100);
        }

        assertThat(rc).as("the build finished").exists();
        var done = dir.resolve("q/done").resolve(id);

        for (var i = 0; i < 100 && !Files.exists(done); i++) {
            Thread.sleep(100);
        }

        assertThat(done).as("the ticket reached done/").exists();

        return new Build(id, run);
    }

    @Test
    void aRepeatWithNothingBetween_isStillDeduped() throws Exception {
        var first = build("-B", "verify");

        assertThat(first.ticket()).isNotNull();
        var again = build("-B", "verify");

        assertThat(again.outcome().rc()).as(again.outcome().stdout() + again.outcome().stderr()).isEqualTo(8);
        assertThat(again.outcome().stderr()).contains("identical build already finished");
    }

    @Test
    void aSecondBuildInTheSameSecond_doesNotMakeTheFirstOneCurrent() throws Exception {
        var first = build("-B", "verify");
        var between = build("-B", "test");

        assertThat(first.ticket()).isNotNull();
        assertThat(between.ticket()).isNotNull();
        assertThat(Files.readString(co.resolve(".bb/start")).strip()).as("the premise: both runs share one second").isEqualTo("1700000000");
        var again = build("-B", "verify");

        assertThat(again.outcome().rc()).as("deduped onto a result .bb no longer holds: " + again.outcome().stderr()).isZero();
        assertThat(again.ticket()).isNotNull().isNotEqualTo(first.ticket());
        assertThat(Files.readString(co.resolve(".bb/ticket")).strip()).isEqualTo(again.ticket());
    }
}
