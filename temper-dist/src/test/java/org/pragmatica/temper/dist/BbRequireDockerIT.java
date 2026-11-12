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


/// `ops/bb/bb-build` run for real through the queue: whichever way maven is launched (the host, or `bb-netns-run` for an iso=1 ticket),
/// the argv ends with `-Daether.requireDocker=true`, after the caller's args, so a Docker IT class fails instead of not running
/// when Docker is absent (pragmatica #2591). The stub maven and a fake `bb-netns-run` just print their argv into the build log.
///
/// `BB_REQUIRE_DOCKER=0` at enqueue (#31) omits the flag from both launches and is recorded in the ticket as `requireDocker=0`, so a
/// report cannot present a no-flag run as a guarded one: bb-tickets carries the field, bb-queue marks the row, dedup keeps the two apart.
///
/// Probes (each red, reverted): drop the property from the host launch -> [#theHostLaunch_endsWithRequireDocker_afterTheCallersArgs];
/// drop it from the netns launch -> [#theNetnsLaunch_endsWithRequireDocker_afterTheCallersArgs]; ignore the opt-out ->
/// [#optOut_omitsTheFlag_onTheHostLaunch], [#optOut_omitsTheFlag_onTheNetnsLaunch]; drop the ticket field ->
/// [#optOut_isRecordedInTheTicket_andShownByBbTickets], [#optOut_marksTheQueueListing]; drop requireDocker from the dedup key ->
/// [#optOut_isNotDedupedAgainstAFinishedGuardedBuild].
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

    private record Run(String log, String ticket, BbFixtures.Outcome outcome) {}

    private Path co;
    private Map<String, String> env;

    private void initRepo(Path repo) throws Exception {
        Files.createDirectories(repo);
        if (Files.exists(repo.resolve(".git"))) {
            return;
        }

        Files.writeString(repo.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"),
                                List.of("add", "."),
                                List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", repo.toString()));

            git.addAll(step);
            assertThat(BbFixtures.run(git, Map.of(), null, null).rc()).isZero();
        }
    }

    private void setUp(String group, Map<String, String> extra) throws Exception {
        var tools = dir.resolve("tools");

        BbFixtures.executable(tools.resolve("bb-build"),
                              Files.readString(BbFixtures.OPS_BB.resolve("bb-build")));
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"),
                   tools.resolve("bb-queue-lib.sh"),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(BbFixtures.OPS_BB.resolve("bb-portgroup.py"),
                   tools.resolve("bb-portgroup.py"),
                   StandardCopyOption.REPLACE_EXISTING);
        BbFixtures.executable(tools.resolve("bb-netns-run"), "#!/bin/sh\necho \"NETNS $*\"\n");
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\n");

        BbFixtures.executable(dir.resolve("fakebin/docker"), "#!/bin/sh\nexit 0\n");
        co = dir.resolve("work").resolve("rd-co");
        initRepo(co);
        env = new HashMap<>();
        env.put("PATH",
                dir.resolve("fakebin") + ":" + System.getenv("PATH"));
        env.put("HOME",
                Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "rd");
        env.put("BB_TEST_QUEUE_ROOT",
                dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", group);
        env.putAll(extra);
    }

    private BbFixtures.Outcome enqueue(Path checkout, Map<String, String> withEnv, String... args) throws Exception {
        var command = new ArrayList<>(List.of(dir.resolve("tools/bb-build").toString(),
                                              checkout.toString()));

        command.addAll(List.of(args));
        var run = BbFixtures.run(command, withEnv, null, null);
        var matcher = QUEUED.matcher(run.stdout());

        if (matcher.find()) {
            tickets.add(matcher.group(1));
        }

        return run;
    }

    private Run build(String group, Map<String, String> extra, String... args) throws Exception {
        setUp(group, extra);
        var run = enqueue(co, env, args);
        var matcher = QUEUED.matcher(run.stdout());

        assertThat(matcher.find()).as(run.stdout() + run.stderr()).isTrue();
        var rc = co.resolve(".bb/rc");

        for (var i = 0; i < 300 && !Files.exists(rc); i++) {
            Thread.sleep(100);
        }

        assertThat(rc).as("the build finished").exists();
        var done = dir.resolve("q/done").resolve(matcher.group(1));

        for (var i = 0; i < 100 && !Files.exists(done); i++) {
            Thread.sleep(100);
        }

        assertThat(done).as("the ticket reached done/").exists();

        return new Run(Files.readString(co.resolve(".bb/build.log")).strip(),
                       matcher.group(1),
                       run);
    }

    private String doneTicket(Run run) throws Exception {
        return Files.readString(dir.resolve("q/done").resolve(run.ticket()));
    }

    private static final Map<String, String> OPT_OUT = Map.of("BB_REQUIRE_DOCKER", "0");

    @Test
    void theHostLaunch_endsWithRequireDocker_afterTheCallersArgs() throws Exception {
        assertThat(build("0", Map.of(), "-B", "verify", "-Dfoo=1").log()).isEqualTo("HOST -B verify -Dfoo=1 -Daether.requireDocker=true");
    }

    @Test
    void theNetnsLaunch_endsWithRequireDocker_afterTheCallersArgs() throws Exception {
        var log = build("1", Map.of(), "-B", "verify", "-Dfoo=1").log();

        assertThat(log).startsWith("NETNS ").endsWith(" -B verify -Dfoo=1 -Daether.requireDocker=true");
    }

    @Test
    void explicitOne_isTheDefault_andLeavesTheTicketWithoutTheField() throws Exception {
        var run = build("0", Map.of("BB_REQUIRE_DOCKER", "1"), "-B", "verify");

        assertThat(run.log()).isEqualTo("HOST -B verify -Daether.requireDocker=true");
        assertThat(doneTicket(run)).doesNotContain("requireDocker");
    }

    @Test
    void optOut_omitsTheFlag_onTheHostLaunch() throws Exception {
        assertThat(build("0", OPT_OUT, "-B", "verify", "-Dfoo=1").log()).isEqualTo("HOST -B verify -Dfoo=1");
    }

    @Test
    void optOut_omitsTheFlag_onTheNetnsLaunch() throws Exception {
        var log = build("1", OPT_OUT, "-B", "verify", "-Dfoo=1").log();

        assertThat(log).startsWith("NETNS ").endsWith(" -B verify -Dfoo=1").doesNotContain("requireDocker");
    }

    @Test
    void optOut_isRecordedInTheTicket_andShownByBbTickets() throws Exception {
        var run = build("0", OPT_OUT, "-B", "verify");

        assertThat(doneTicket(run).lines().toList()).contains("requireDocker=0");
        var tickets = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-tickets").toString()),
                                     env,
                                     null,
                                     null);

        assertThat(tickets.rc()).as(tickets.stderr()).isZero();
        assertThat(tickets.stdout().lines().filter(l -> l.contains("\"ticket\":\"" + run.ticket() + "\"")).toList()).singleElement()
                  .satisfies(line -> assertThat(line).contains("\"requireDocker\":\"0\"")
                                               .contains("\"dropped\":[]"));
    }

    @Test
    void optOut_marksTheQueueListing() throws Exception {
        setUp("0", Map.of("BB_REQUIRE_DOCKER", "0", "BB_MAX_REACTORS", "0"));
        var guarded = enqueue(co, env, "-B", "optedout");
        var other = dir.resolve("work").resolve("rd-co2");
        var plainEnv = new HashMap<>(env);

        assertThat(guarded.rc()).as(guarded.stderr()).isZero();
        initRepo(other);
        plainEnv.remove("BB_REQUIRE_DOCKER");
        var second = enqueue(other, plainEnv, "-B", "plain");

        assertThat(second.rc()).as(second.stdout() + second.stderr()).isZero();
        var listing = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-queue").toString(),
                                             "0"),
                                     env,
                                     dir,
                                     null);
        var rows = listing.stdout().lines().filter(l -> l.contains(" rd-co")).toList();

        assertThat(rows).hasSize(2);
        assertThat(rows.stream().filter(l -> l.contains("[no-requireDocker]")).toList()).singleElement()
                  .satisfies(l -> assertThat(l).contains("-B optedout"));
        assertThat(rows.stream().filter(l -> l.contains("-B plain")).toList()).singleElement()
                  .satisfies(l -> assertThat(l).doesNotContain("requireDocker"));
    }

    @Test
    void optOut_isNotDedupedAgainstAFinishedGuardedBuild() throws Exception {
        var guarded = build("0", Map.of(), "-B", "verify");

        assertThat(guarded.log()).endsWith("-Daether.requireDocker=true");
        var optOut = build("0", OPT_OUT, "-B", "verify");

        assertThat(optOut.outcome().rc()).as(optOut.outcome().stdout() + optOut.outcome().stderr()).isZero();
        assertThat(optOut.log()).isEqualTo("HOST -B verify");
    }

    @Test
    void aBadValue_isRefusedWithExitTwo_andNothingIsQueued() throws Exception {
        for (var bad : List.of("2", "true", "01", "0 ", "-1", "yes")) {
            setUp("0", Map.of("BB_REQUIRE_DOCKER", bad));
            var run = enqueue(co, env, "-B", "verify");

            assertThat(run.rc()).as("value '" + bad + "': " + run.stdout() + run.stderr()).isEqualTo(2);
            assertThat(run.stderr()).contains("BB_REQUIRE_DOCKER must be 0 or 1");
            try (var files = Files.walk(dir.resolve("q"))) {
                assertThat(files.filter(Files::isRegularFile)
                                .filter(f -> !f.getFileName()
                                               .toString()
                                               .startsWith("."))
                                .toList()).as("nothing queued for '" + bad + "'")
                          .isEmpty();
            }

            assertThat(co.resolve(".bb")).doesNotExist();
        }
    }
}
