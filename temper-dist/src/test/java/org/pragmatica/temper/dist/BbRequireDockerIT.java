package org.pragmatica.temper.dist;

import java.nio.file.Files;
import java.nio.file.Path;
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

/// `ops/bb/bb-build` run for real through the queue: whichever way maven is launched (the host, or `bb-netns-run` for an iso=1 ticket),
/// the argv ends with `-Daether.requireDocker=true`, after the caller's args, so a Docker IT class fails instead of not running
/// when Docker is absent (pragmatica #2591). The stub maven and a fake `bb-netns-run` just print their argv into the build log.
///
/// Probes (each red, reverted): drop the property from the host launch -> [#theHostLaunch_endsWithRequireDocker_afterTheCallersArgs];
/// drop it from the netns launch -> [#theNetnsLaunch_endsWithRequireDocker_afterTheCallersArgs].
class BbRequireDockerIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");

    @TempDir
    Path dir;

    private final List<String> tickets = new ArrayList<>();

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

    private String build(String group, String... args) throws Exception {
        var tools = dir.resolve("tools");

        BbFixtures.executable(tools.resolve("bb-build"), Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"), tools.resolve("bb-queue-lib.sh"));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-portgroup.py"), tools.resolve("bb-portgroup.py"));
        BbFixtures.executable(tools.resolve("bb-netns-run"), "#!/bin/sh\necho \"NETNS $*\"\n");
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\n");
        BbFixtures.executable(dir.resolve("fakebin/docker"), "#!/bin/sh\nexit 0\n");
        var co = Files.createDirectories(dir.resolve("work").resolve("rd-co"));

        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"), List.of("add", "."), List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }
        var env = new HashMap<String, String>();

        env.put("PATH", dir.resolve("fakebin") + ":" + System.getenv("PATH"));
        env.put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "rd");
        env.put("BB_TEST_QUEUE_ROOT", dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", group);
        var command = new ArrayList<>(List.of(tools.resolve("bb-build").toString(), co.toString()));

        command.addAll(List.of(args));
        var run = BbFixtures.run(command, env, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        assertThat(matcher.find()).as(run.stdout() + run.stderr()).isTrue();
        tickets.add(matcher.group(1));
        var rc = co.resolve(".bb/rc");

        for (var i = 0; i < 300 && !Files.exists(rc); i++) {
            Thread.sleep(100);
        }
        assertThat(rc).as("the build finished").exists();
        return Files.readString(co.resolve(".bb/build.log")).strip();
    }

    @Test
    void theHostLaunch_endsWithRequireDocker_afterTheCallersArgs() throws Exception {
        assertThat(build("0", "-B", "verify", "-Dfoo=1")).isEqualTo("HOST -B verify -Dfoo=1 -Daether.requireDocker=true");
    }

    @Test
    void theNetnsLaunch_endsWithRequireDocker_afterTheCallersArgs() throws Exception {
        var log = build("1", "-B", "verify", "-Dfoo=1");

        assertThat(log).startsWith("NETNS ").endsWith(" -B verify -Dfoo=1 -Daether.requireDocker=true");
    }
}
