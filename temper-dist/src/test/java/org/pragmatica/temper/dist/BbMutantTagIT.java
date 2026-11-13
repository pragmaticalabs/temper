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


/// Issue #46: a build submitted by bb-mutate is marked in the queue, so a sweep does not read a mutation run (its mutants are EXPECTED to go
/// red, the same two-test command every ~35 s) as an unattended retry loop. `BB_VIA` at enqueue is recorded in the ticket as `via=<value>`
/// (additive, like `requireDocker`); `bb-queue` shows `[mutant <id>]` in the RUNNING and FINISHED rows of a `via=bb-mutate:<spec>:<id>`
/// ticket; `bb-tickets` carries the field as any other. A ticket without the field is untouched. (bb-mutate passing BB_VIA: [BbMutateIT].)
///
/// Probes (each red, reverted): drop the `via` line from bb-build -> [#via_isRecordedInTheTicket_andShownByBbTickets];
/// record it unvalidated -> [#aMalformedVia_isDroppedWithANotice_andTheBuildStillRuns]; print the tag in RUNNING only -> [#theQueueListing_tagsMutantRows_inRunningAndFinished];
/// tag every ticket -> [#theQueueListing_leavesOtherRowsUntouched].
class BbMutantTagIT {
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");
    private static final String VIA = "bb-mutate:spec:m7";

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
        Files.copy(BbFixtures.OPS_BB.resolve("bb-queue-lib.sh"),
                   tools.resolve("bb-queue-lib.sh"),
                   StandardCopyOption.REPLACE_EXISTING);
        Files.copy(BbFixtures.OPS_BB.resolve("bb-portgroup.py"),
                   tools.resolve("bb-portgroup.py"),
                   StandardCopyOption.REPLACE_EXISTING);
        BbFixtures.executable(tools.resolve("bb-netns-run"), "#!/bin/sh\necho \"NETNS $*\"\n");
        var mvn = BbFixtures.executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho \"HOST $*\"\n");

        BbFixtures.executable(dir.resolve("fakebin/docker"), "#!/bin/sh\nexit 0\n");
        co = dir.resolve("work").resolve("mt-co");
        initRepo(co);
        env = new HashMap<>();
        env.put("PATH",
                dir.resolve("fakebin") + ":" + System.getenv("PATH"));
        env.put("HOME",
                Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("BB_OWNER", "mt");
        env.put("BB_TEST_QUEUE_ROOT",
                dir.resolve("q").toString());
        env.put("BB_TEST_PRIORITIES",
                dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES",
                dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_DEDUP", "0");
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

    private record Built(String ticket, String done, BbFixtures.Outcome outcome) {}

    /// enqueues one build with `via` (null: BB_VIA not set), waits for the stub maven to finish and the ticket to reach done/
    private Built build(String via) throws Exception {
        var withEnv = new HashMap<>(env);

        if (via != null) {
            withEnv.put("BB_VIA", via);
        }

        var run = BbFixtures.run(List.of(dir.resolve("tools/bb-build").toString(),
                                         co.toString(),
                                         "-B",
                                         "verify"),
                                 withEnv,
                                 null,
                                 null);
        var matcher = QUEUED.matcher(run.stdout());

        assertThat(matcher.find()).as(run.stdout() + run.stderr()).isTrue();
        tickets.add(matcher.group(1));
        var done = dir.resolve("q/done").resolve(matcher.group(1));

        for (var i = 0; i < 300 && !Files.exists(done); i++) {
            Thread.sleep(100);
        }

        assertThat(done).as("the ticket reached done/").exists();

        var rc = co.resolve(".bb/rc");

        for (var i = 0; i < 300 && !Files.exists(rc); i++) {
            Thread.sleep(100);
        }

        assertThat(rc).as("the build wrote .bb/rc").exists();

        return new Built(matcher.group(1), Files.readString(done), run);
    }

    private static List<String> keys(String ticket) {
        return ticket.lines()
                     .filter(l -> l.contains("="))
                     .map(l -> l.substring(0,
                                           l.indexOf('=')))
                     .toList();
    }

    private BbFixtures.Outcome bbTickets() throws Exception {
        return BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-tickets").toString()),
                              env,
                              null,
                              null);
    }

    @Test
    void via_isRecordedInTheTicket_andShownByBbTickets() throws Exception {
        var built = build(VIA);

        assertThat(built.done().lines().toList()).contains("via=" + VIA);
        var listing = bbTickets();

        assertThat(listing.rc()).as(listing.stderr()).isZero();
        assertThat(listing.stdout().lines().filter(l -> l.contains("\"ticket\":\"" + built.ticket() + "\"")).toList()).singleElement()
                  .satisfies(line -> assertThat(line).contains("\"via\":\"" + VIA + "\"")
                                               .contains("\"dropped\":[]"));
    }

    /// Additive: no BB_VIA, no field, and the other fields are exactly the ones a BB_VIA build has (same keys, same order).
    @Test
    void withoutVia_theTicketHasNoViaField_andOtherwiseTheSameKeys() throws Exception {
        var plain = build(null);
        var marked = build(VIA);

        assertThat(plain.done()).doesNotContain("via");
        assertThat(keys(plain.done())).doesNotContain("via");
        var markedKeys = new ArrayList<>(keys(marked.done()));

        assertThat(markedKeys.remove("via")).isTrue();
        assertThat(markedKeys).isEqualTo(keys(plain.done()));
        var line = bbTickets().stdout()
                            .lines()
                            .filter(l -> l.contains("\"ticket\":\"" + plain.ticket() + "\""))
                            .findFirst()
                            .orElseThrow();

        assertThat(line).doesNotContain("\"via\"");
    }

    @Test
    void aMalformedVia_isDroppedWithANotice_andTheBuildStillRuns() throws Exception {
        for (var bad : List.of("has space", "semi;colon", "dollar$x", "x".repeat(65), "new\nline")) {
            var built = build(bad);

            assertThat(built.outcome().rc()).as("value '" + bad + "': " + built.outcome().stderr()).isZero();
            assertThat(built.outcome().stderr()).contains("bb-build: ignoring BB_VIA");
            assertThat(keys(built.done())).as("value '" + bad + "' dropped").doesNotContain("via");
            assertThat(Files.readString(co.resolve(".bb/build.log"))).contains("HOST -B verify");
        }
    }

    private Path queueTickets(String sub, String id, String via, String args) throws Exception {
        var body = "id=" + id
                 + "\nowner=mu\nco=/w/co/" + id
                 + "\nsha=abc\nstarted=" + (System.currentTimeMillis() / 1000 - 90)
                 + "\nended=" + (System.currentTimeMillis() / 1000 - 30)
                 + "\nargs=" + args
                 + "\n" + (via == null
                           ? ""
                           : "via=" + via + "\n")
                 + "state=" + ("done".equals(sub)
                               ? "finished"
                               : "running")
                 + "\nrc=1\n";

        return Files.writeString(Files.createDirectories(dir.resolve("q").resolve(sub)).resolve(id),
                                 body);
    }

    private List<String> listing() throws Exception {
        var q = new HashMap<>(env);

        q.put("BB_TEST_PG_SLOTS_FILE",
              Files.writeString(dir.resolve("slots"), "3\n").toString());
        q.put("BB_TEST_LOADAVG",
              Files.writeString(dir.resolve("loadavg"), "0.10 0.10 0.10 1/100 1\n").toString());
        q.put("BB_TEST_TICKET_LOG",
              dir.resolve("log.tsv").toString());
        var run = BbFixtures.run(List.of(BbFixtures.OPS_BB.resolve("bb-queue").toString(),
                                         "10"),
                                 q,
                                 dir,
                                 null);

        assertThat(run.rc()).as(run.stderr()).isZero();

        return run.stdout()
                  .lines()
                  .toList();
    }

    private static String row(List<String> lines, String id) {
        return lines.stream()
                    .filter(l -> l.contains(id))
                    .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void theQueueListing_tagsMutantRows_inRunningAndFinished() throws Exception {
        var running = "run-" + BbFixtures.RUN;
        var finished = "fin-" + BbFixtures.RUN;

        queueTickets("running", running, "bb-mutate:spec:mut.A", "-B verify");
        queueTickets("done", finished, "bb-mutate:spec:mut.B", "-B verify");
        var lines = listing();

        assertThat(row(lines, running)).endsWith("-B verify [waiter gone] [mutant mut.A]");
        assertThat(row(lines, finished)).endsWith("-B verify [mutant mut.B]");
    }

    @Test
    void theQueueListing_leavesOtherRowsUntouched() throws Exception {
        var plainRunning = "plr-" + BbFixtures.RUN;
        var plainDone = "pld-" + BbFixtures.RUN;
        var foreign = "frn-" + BbFixtures.RUN;
        var empty = "emp-" + BbFixtures.RUN;

        queueTickets("running", plainRunning, null, "-B verify");
        queueTickets("done", plainDone, null, "-B verify");
        queueTickets("done", foreign, "someone-else:spec:x", "-B verify");
        queueTickets("done", empty, "bb-mutate:spec:", "-B verify");
        var lines = listing();

        assertThat(row(lines, plainRunning)).endsWith("-B verify [waiter gone]").doesNotContain("mutant");
        assertThat(row(lines, plainDone)).endsWith("-B verify").doesNotContain("mutant");
        assertThat(row(lines, foreign)).endsWith("-B verify");
        assertThat(row(lines, empty)).endsWith("-B verify");
    }
}
