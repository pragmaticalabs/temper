package org.pragmatica.temper.acceptance.stack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.Controller;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.git.GitHubPulls;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.Store;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;

/// CI-T1 WP5: the real `temper events` CLI (its shaded jar, as a process) against the real controller (its routes,
/// its fleet stream, its kiln pull, its epoch) over a real Postgres. WP4 proved the CLI against
/// a scripted controller and WP3 the routes by direct calls; this is the two of them on the wire.
///
/// The controller has no hosts (no worker, no docker-tier image): the fleet needs none. Its `[fleet] kilnUrl` is a
/// [FakeKiln] serving the wire, and its process runner answers the self-check's `ssh-keyscan` with a key line
/// (gated in the NOT_READY case), so the verdict does not depend on the build host's sshd. Everything between that
/// wire and the CLI's exit code is production code; [#realKiln_heartbeatNamesItsSources_aPrStateReachesTheCli_andAResetIsAnnounced]
/// serves that wire from the real kiln-service ([RealKiln], lead ruling F4).
///
/// Host links (lead ruling F4): one case configures a host whose link never comes UP (an unresolvable address), so its
/// `hostlink:<h>` source, seeded never-seen, is stale; no case takes a link that WAS up down (this harness runs no
/// worker). Stated gap: the in-process controller predates build identity, so the CLI's version-skew check (exit 13)
/// is not exercised here (unit-tested against the scripted controller only).
///
/// Real time: the CLI's heartbeat bound (110 s) and the long-poll (55 s) are constants of the CLI with no clock seam a
/// process can be given, so [#until_controllerStops_exits3OnTheSilenceBound] runs ~2 min and
/// [#until_kilnStopsAnsweringDuringTheWait_exits4] up to ~1 min.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class FleetEventsIT {
    private static final String TOKEN = "fleet-token";
    private static final String READ_TOKEN = "fleet-read-token";
    private static final Duration SHORT = Duration.ofSeconds(60);
    private static final Pattern TEXT_HEAD = Pattern.compile("^([0-9a-f]{8}):([0-9]+) (\\S+) (\\S+) (\\S+) catchUp=([01])( .*)?$");
    private static final String KEY_LINE = "127.0.0.1 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFleetEventsITFleetEventsITFleetEvents\n";

    private Path dir;
    private Path cliHome;
    private Path readHome;
    private Path tokenFile;
    private FixtureRepo fixture;
    private FakeKiln kiln;
    private Store store;
    private String database;
    private ControllerConfig config;
    private Banner banner;
    private Controller.Running running;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createDirectories(Path.of(System.getProperty("temper.acceptance.target", "target"),
                                              "acceptance",
                                              "fleet-" + System.nanoTime())
                                          .toAbsolutePath());
        cliHome = Files.createDirectories(dir.resolve("cli-home"));
        readHome = Files.createDirectories(dir.resolve("read-home"));
        tokenFile = tokenFile(cliHome, TOKEN);
        tokenFile(readHome, READ_TOKEN);
        fixture = FixtureRepo.create(dir, List.of());
        kiln = FakeKiln.start();
        var opened = Pg.freshStore(AcceptanceStack.firstJobId(System.currentTimeMillis()));
        store = opened.store();
        database = opened.database();
        config = config(kiln.url());
        banner = new Banner();
    }

    @AfterEach
    void tearDown() {
        banner.open();
        stopController();
        kiln.close();
        store.close();
    }

    // ---- --follow over the subscriber's cursor ------------------------------------------------------------

    /// `--create`, then `--follow` (NDJSON) prints what the kiln pull wrote, in seq order, acking each; a second
    /// `--follow` (text form) starts after the last acked seq: together they are exactly the stream's rows after the
    /// subscriber's start, no duplicate, no gap.
    @Test
    void follow_printsTheStreamInSeqOrder_andASecondRunResumesAfterTheLastAck() throws Exception {
        startController();
        awaitFirstPull();
        var start = heartbeat().path("seqHead").asLong();
        assertThat(oneShot("events", "--create", "f1", "--start", "head")).as("--create").isZero();
        assertThat(cursor("f1")).isEqualTo(start);
        kiln.change("PR_STATE", 1, "o/r#1");
        kiln.change("HEAD_MOVED", 2, "o/r#2");
        kiln.change("CHECK_CELL", 3, "o/r#3");
        var firstRows = awaitRows(start, 3, "kiln");

        var ndjson = CliProcess.launch(cliHome, url(), "events", "--subscriber", "f1", "--follow");
        try (ndjson) {
            ndjson.awaitOut(lines -> lines.size() >= firstRows.size(), SHORT, firstRows.size() + " event lines");
            awaitAcked("f1", ndjson, FleetEventsIT::ndjsonSeq);
        }
        var first = ndjson.out().stream().map(FleetEventsIT::json).toList();
        assertThat(cursor("f1")).as("killed after its last ack").isEqualTo(seqOf(first.getLast()));

        assertThat(first).allSatisfy(FleetEventsIT::isEventLineInContractOrder);
        var firstSeqs = first.stream().map(FleetEventsIT::seqOf).toList();
        assertThat(firstSeqs).as("in seq order, exactly the rows after the start").isEqualTo(rowsBetween(start, firstSeqs.getLast()));
        assertThat(first.stream().filter(line -> line.path("source").stringValue().equals("kiln")).map(line -> line.path("kind").stringValue()))
            .containsExactly("pr-state", "pr-head-moved", "check-cell");

        kiln.change("PR_STATE", 4, "fix the build\tnow");
        kiln.change("PR_STATE", 5, "b64:Zm9v");
        var last = awaitRows(start, 5, "kiln").getLast();

        var text = CliProcess.launch(cliHome, url(), "events", "--subscriber", "f1", "--follow", "--format", "text");
        try (text) {
            text.awaitOut(lines -> lines.stream().anyMatch(line -> textSeq(line) == last), SHORT, "the text line of seq " + last);
            awaitAcked("f1", text, FleetEventsIT::textSeq);
        }
        var second = text.out();
        assertThat(cursor("f1")).as("killed after its last ack").isEqualTo(textSeq(second.getLast()));

        var epoch8 = heartbeat().path("epoch").stringValue().substring(0, 8);
        assertThat(second).allSatisfy(line -> assertThat(line).matches(TEXT_HEAD).startsWith(epoch8 + ":"));
        var secondSeqs = second.stream().map(FleetEventsIT::textSeq).toList();
        assertThat(secondSeqs).as("resumed after the last ack: no duplicate, no gap").isEqualTo(rowsBetween(firstSeqs.getLast(), last));
        assertThat(second).filteredOn(line -> line.contains(" pr-state "))
                          .hasSize(2)
                          .first()
                          .satisfies(line -> assertThat(subjectOf(line)).as("a subject with whitespace is b64")
                                                                     .isEqualTo("fix the build\tnow"));
        assertThat(second).filteredOn(line -> line.contains(" pr-state "))
                          .last()
                          .satisfies(line -> assertThat(subjectOf(line)).as("a raw subject starting with b64: is encoded (lead ruling F3)")
                                                                     .isEqualTo("b64:Zm9v"));
    }

    /// The follow outlives a controller restart: refused connections and the start's 503s are retried, and what is
    /// written after the restart is printed, after the cursor, with nothing printed twice.
    @Test
    void follow_survivesAControllerRestart_andPrintsWhatComesAfter() throws Exception {
        startController();
        awaitFirstPull();
        var start = heartbeat().path("seqHead").asLong();
        assertThat(oneShot("events", "--create", "f2")).as("--create, start head by default").isZero();

        var follow = CliProcess.launch(cliHome, url(), "events", "--subscriber", "f2", "--follow");
        try (follow) {
            kiln.change("PR_STATE", 1, "o/r#1");
            follow.awaitOut(lines -> lines.stream().anyMatch(line -> line.contains("\"o/r#1\"")), SHORT, "the line of o/r#1");
            stopController();
            Thread.sleep(2_000);
            startController();
            awaitFirstPull();
            kiln.change("PR_STATE", 2, "o/r#2");
            follow.awaitOut(lines -> lines.stream().anyMatch(line -> line.contains("\"o/r#2\"")), SHORT, "the line of o/r#2");
            awaitAcked("f2", follow, FleetEventsIT::ndjsonSeq);
        }

        var seqs = follow.out().stream().map(FleetEventsIT::ndjsonSeq).toList();
        assertThat(seqs).as("in seq order, once each, no gap").isEqualTo(rowsBetween(start, seqs.getLast()));
    }

    /// Lead ruling F2, the verifier's lost-ack case on the real stack: in epoch A eight lines are printed and acked, then
    /// a ninth is printed while its ack is held back ([AckGate]); kiln stops answering, the controller stops, its
    /// database is restored from a dump taken before both (`pg_restore --clean`: the cursor and the sequence go back),
    /// and it restarts, rotating to epoch B (`file-ahead`), whose `epoch-changed` and start rows reuse the first seqs. The held ack names
    /// epoch A AND a seq above B's head (verifier F6): the epoch is checked first, so 409, not 400 `ACK_AHEAD` (which
    /// would end the follow with 64); the cursor stays, and the follow reads again from it, so every row of epoch B is
    /// printed. Before the ruling the epoch-blind ack moved the cursor over B's first rows.
    @Test
    void follow_anAckHeldAcrossARestore_isRefusedForItsEpoch_andTheNewEpochIsPrintedWhole() throws Exception {
        startController();
        awaitFirstPull();
        var start = heartbeat().path("seqHead").asLong();
        assertThat(oneShot("events", "--create", "f3", "--start", "head")).isZero();
        var dump = "/tmp/" + database + "-f2.dump";
        Pg.dump(database, dump);
        var oldEpoch = heartbeat().path("epoch").stringValue();

        try (var gate = AckGate.start(URI.create(url()));
             var follow = CliProcess.launch(cliHome, gate.url(), "events", "--subscriber", "f3", "--follow")) {
            for (var pr = 1; pr <= 8; pr++) {
                kiln.change("PR_STATE", pr, "o/r#" + pr);
            }
            follow.awaitOut(lines -> lines.stream().anyMatch(line -> line.contains("\"o/r#8\"")), SHORT, "the line of o/r#8");
            awaitAcked("f3", follow, FleetEventsIT::ndjsonSeq);
            gate.refuseAcks(true);
            kiln.change("PR_STATE", 9, "o/r#9");
            follow.awaitOut(lines -> lines.stream().anyMatch(line -> line.contains("\"o/r#9\"")), SHORT, "the line of o/r#9");
            awaitTrue(() -> gate.refused() > 0, "its ack held back");
            var held = ndjsonSeq(follow.out().getLast());
            kiln.stopAnswering();
            stopController();
            Pg.restore(database, dump);
            assertThat(cursor("f3")).as("restored").isEqualTo(start);
            startController();
            var newEpoch = heartbeat().path("epoch").stringValue();
            assertThat(newEpoch).isNotEqualTo(oldEpoch);
            var last = rowsBetween(start, Long.MAX_VALUE).getLast();
            assertThat(held).as("the held ack is above the new epoch's head (verifier F6)").isGreaterThan(heartbeat().path("seqHead").asLong());
            gate.refuseAcks(false);

            follow.awaitOut(lines -> lines.stream().anyMatch(line -> line.contains("\"" + newEpoch + "\"") && ndjsonSeq(line) == last),
                            Duration.ofSeconds(90),
                            "the new epoch's last row, seq " + last);
            awaitAcked("f3", follow, FleetEventsIT::ndjsonSeq);

            var printedInB = follow.out().stream().map(FleetEventsIT::json).filter(line -> line.path("epoch").stringValue().equals(newEpoch)).toList();
            assertThat(printedInB.stream().map(FleetEventsIT::seqOf).toList()).as("every row of the new epoch after the restored cursor, once, in order")
                                                                              .isEqualTo(rowsBetween(start, last));
            assertThat(printedInB.getFirst().path("kind").stringValue()).isEqualTo("epoch-changed");
            assertThat(follow.err()).anyMatch(line -> line.contains("Ack refused: the controller's epoch is no longer " + oldEpoch));
        }
    }

    /// Binding: a subscriber bound to the creating token refuses the read token (403, exit 77), creating is WRITE
    /// (77 for the read token), and `--bind-to read` makes a subscriber the read token polls and acks.
    @Test
    void follow_withAnotherTokensSubscriber_exits77_andBindToReadLetsTheReaderFollow() throws Exception {
        startController();
        awaitFirstPull();
        assertThat(oneShot("events", "--create", "c1")).isZero();
        assertThat(oneShot("events", "--create", "r1", "--bind-to", "read")).isZero();

        try (var refused = CliProcess.launch(readHome, url(), "events", "--subscriber", "c1", "--follow");
             var create = CliProcess.launch(readHome, url(), "events", "--create", "r2")) {
            assertThat(refused.exit(SHORT)).as("stderr: %s", refused.err()).isEqualTo(77);
            assertThat(create.exit(SHORT)).as("stderr: %s", create.err()).isEqualTo(77);
        }
        var id = kiln.change("PR_STATE", 3, "o/r#3");
        var reader = CliProcess.launch(readHome, url(), "events", "--subscriber", "r1", "--follow");
        try (reader) {
            reader.awaitOut(lines -> lines.stream().anyMatch(line -> line.contains("\"o/r#3\"")), SHORT, "the line of o/r#3 (kiln id " + id + ")");
            awaitAcked("r1", reader, FleetEventsIT::ndjsonSeq);
        }
    }

    /// Lead ruling 11-03 (i): kiln's own `kiln:highwater` source reaches the heartbeat under that name,
    /// fresh, beside the prefixed `kiln:github`; never as `kiln:kiln:highwater`.
    @Test
    void heartbeat_listsKilnHighwaterAsKilnNamesIt() throws Exception {
        startController();
        awaitFirstPull();
        var sources = heartbeat().path("sources");

        assertThat(sources.path("kiln:highwater").path("stale").isBoolean()).as("sources: %s", sources).isTrue();
        assertThat(sources.path("kiln:highwater").path("stale").asBoolean()).isFalse();
        assertThat(sources.path("kiln:github").path("stale").asBoolean()).isFalse();
        assertThat(sources.has("kiln:kiln:highwater")).isFalse();
    }

    // ---- exit 0: --until with a live heartbeat ------------------------------------------------------------------

    @Test
    void until_withALiveHeartbeat_exits0OnTheMatchingLineOnly() throws Exception {
        startController();
        awaitFirstPull();
        var position = position();

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-head-moved", "--match", "pr=9",
                                           "--after", position, "--timeout", "3m")) {
            kiln.change("HEAD_MOVED", 8, "o/r#8");
            kiln.change("PR_STATE", 9, "o/r#9");
            kiln.change("HEAD_MOVED", 9, "o/r#9");

            assertThat(until.exit(SHORT)).as("stderr: %s", until.err()).isZero();
            assertThat(until.out()).singleElement().satisfies(line -> {
                var event = json(line);
                isEventLineInContractOrder(event);
                assertThat(event.path("kind").stringValue()).isEqualTo("pr-head-moved");
                assertThat(event.path("payload").path("pr").asLong()).isEqualTo(9);
                assertThat(event.path("catchUp").asBoolean()).isFalse();
            });
        }
    }

    // ---- exit 3: no heartbeat for 110 s --------------------------------------------------------------------------

    /// The controller stops 3 s into the wait; the CLI retries the refused connection until 110 s have passed since its
    /// last heartbeat (by receipt time, which is at or after its launch and at or before the stop), then exits 3.
    @Test
    void until_controllerStops_exits3OnTheSilenceBound() throws Exception {
        startController();
        awaitFirstPull();

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--after", position(),
                                           "--timeout", "10m")) {
            Thread.sleep(3_000);
            assertThat(until.isAlive()).as("waiting: %s", until.err()).isTrue();
            var stopped = Instant.now();
            stopController();

            var rc = until.exit(Duration.ofSeconds(150));
            var ended = Instant.now();

            assertThat(rc).as("stderr: %s", until.err()).isEqualTo(3);
            assertThat(until.err()).anyMatch(line -> line.contains("No heartbeat from the controller for 110 s"));
            assertThat(Duration.between(until.launched(), ended)).as("not before 110 s of silence").isGreaterThanOrEqualTo(Duration.ofSeconds(110));
            assertThat(Duration.between(stopped, ended)).as("within the bound of the last heartbeat").isLessThanOrEqualTo(Duration.ofSeconds(120));
        }
    }

    // ---- exit 7: the epoch rotates under a waiter ----------------------------------------------------------

    /// A restore-style high-water mark ahead of the database (`file-ahead`) found at the controller's restart
    /// rotates the epoch; the waiter, which kept retrying through the restart, gets 409 `EPOCH_CHANGED` and exits 7.
    @Test
    void until_theEpochRotatesAcrossARestart_exits7() throws Exception {
        startController();
        awaitFirstPull();
        var before = heartbeat();
        var oldEpoch = before.path("epoch").stringValue();

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--after", position(),
                                           "--timeout", "5m")) {
            Thread.sleep(2_000);
            stopController();
            Files.writeString(config.fleet().stateDir().resolve("fleet-hwm"),
                              oldEpoch + " " + (before.path("seqHead").asLong() + 50) + "\n",
                              StandardCharsets.US_ASCII);
            startController();
            var newEpoch = heartbeat().path("epoch").stringValue();

            assertThat(newEpoch).isNotEqualTo(oldEpoch);
            assertThat(until.exit(SHORT)).as("stderr: %s", until.err()).isEqualTo(7);
            assertThat(until.err()).anyMatch(line -> line.contains(oldEpoch) && line.contains(newEpoch));
            assertThat(Pg.psql(database, "SELECT payload->>'detail' FROM fleet_events WHERE kind = 'epoch-changed'"))
                .isEqualTo("file-ahead");
        }
    }

    // ---- exit 4: a stale source the wait depends on --------------------------------------------------------

    /// kiln's `github` source last succeeded 10 min ago (period 60 s): `kiln:github` is stale by age, and a wait for a
    /// GitHub kind ends at its first heartbeat with 4, while a wait for a controller kind does not depend on it.
    @Test
    void until_kilnGithubStale_exits4_andAControllerKindStillWaits() throws Exception {
        kiln.githubLastOkAt(Instant.now().minus(Duration.ofMinutes(10)));
        startController();
        awaitFirstPull();
        assertThat(heartbeat().path("sources").path("kiln:github").path("stale").asBoolean()).isTrue();

        try (var github = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--timeout", "3m");
             var controller = CliProcess.launch(cliHome, url(), "events", "--until", "epoch-changed", "--timeout", "5s")) {
            assertThat(github.exit(SHORT)).as("stderr: %s", github.err()).isEqualTo(4);
            assertThat(github.err()).anyMatch(line -> line.contains("Source kiln:github, which this wait depends on, is stale"));
            assertThat(controller.exit(SHORT)).as("a controller kind ends on its timeout: %s", controller.err()).isEqualTo(5);
        }
    }

    /// Lead ruling 11-03 (a): the stream's own integrity source `selfcheck:highwater` gates EVERY `--until` wait,
    /// controller kinds included. A high-water file that cannot be written at start rotates the epoch and leaves the
    /// source without a good write (stale); a wait for a GitHub kind and one for a controller kind both exit 4 naming
    /// it, while `--follow` does not exit and prints the controller's `source-stale` line for it.
    @Test
    void until_highWaterUnwritable_exits4ForEveryKind_andFollowShowsTheSourceStaleLine() throws Exception {
        var state = config.fleet().stateDir();
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            startController();
            awaitFirstPull();
            assertThat(heartbeat().path("sources").path("selfcheck:highwater").path("stale").asBoolean())
                .as("the file could not be written (not running as root?)").isTrue();
            assertThat(oneShot("events", "--create", "h1", "--start", "seq:0")).isZero();

            var follow = CliProcess.launch(cliHome, url(), "events", "--subscriber", "h1", "--follow");
            try (follow;
                 var github = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--timeout", "30s");
                 var controller = CliProcess.launch(cliHome, url(), "events", "--until", "epoch-changed", "--timeout", "30s")) {
                assertThat(github.exit(SHORT)).as("stderr: %s", github.err()).isEqualTo(4);
                assertThat(github.err()).anyMatch(line -> line.contains("Source selfcheck:highwater, which this wait depends on, is stale"));
                assertThat(controller.exit(SHORT)).as("stderr: %s", controller.err()).isEqualTo(4);
                assertThat(controller.err()).anyMatch(line -> line.contains("Source selfcheck:highwater, which this wait depends on, is stale"));
                follow.awaitOut(lines -> lines.stream().anyMatch(line -> line.contains("\"source-stale\"") && line.contains("\"selfcheck:highwater\"")),
                                Duration.ofSeconds(100),
                                "the source-stale line of selfcheck:highwater");
                assertThat(follow.isAlive()).as("--follow does not exit on a stale source: %s", follow.err()).isTrue();
            }
        } finally {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /// Lead ruling F1 (4 beats 0): with `selfcheck:highwater` stale (the file unwritable from the start), a wait from
    /// `--after` whose first answer is a MATCH still exits 4: the heartbeat that ends the reply is judged before the
    /// match, and the match is not printed. Before the ruling the match ended the wait with 0 (verifier probe F1).
    @Test
    void until_aMatchWhileSelfcheckHighwaterIsStale_exits4_andPrintsNothing() throws Exception {
        var state = config.fleet().stateDir();
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            startController();
            awaitFirstPull();
            assertThat(heartbeat().path("sources").path("selfcheck:highwater").path("stale").asBoolean())
                .as("the file could not be written (not running as root?)").isTrue();
            var position = position();

            try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--after", position,
                                               "--timeout", "3m")) {
                var id = kiln.change("PR_STATE", 3, "o/r#3");
                awaitRows(Long.parseLong(position.substring(position.lastIndexOf(':') + 1)), 1, "kiln");

                assertThat(until.exit(SHORT)).as("stderr: %s (kiln id %s)", until.err(), id).isEqualTo(4);
                assertThat(until.err()).anyMatch(line -> line.contains("Source selfcheck:highwater, which this wait depends on, is stale"));
                assertThat(until.out()).as("the match is not printed").isEmpty();
            }
        } finally {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    /// Lead ruling 11-03 point 2: a kiln kind also depends on kiln's own feed integrity, `kiln:highwater`; frozen
    /// 10 min ago (period 60 s), the wait exits 4 naming it.
    @Test
    void until_kilnHighwaterStale_exits4() throws Exception {
        kiln.highWaterLastOkAt(Instant.now().minus(Duration.ofMinutes(10)));
        startController();
        awaitFirstPull();
        assertThat(heartbeat().path("sources").path("kiln:highwater").path("stale").asBoolean()).isTrue();

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--timeout", "30s")) {
            assertThat(until.exit(SHORT)).as("stderr: %s", until.err()).isEqualTo(4);
            assertThat(until.err()).anyMatch(line -> line.contains("Source kiln:highwater, which this wait depends on, is stale"));
        }
    }

    /// kiln stops answering during a wait: the pull's own source `kiln` (period 5 s) goes stale 15 s later, and the
    /// waiter exits 4 on the first heartbeat after that (its long-poll returns at most 55 s after it started).
    @Test
    void until_kilnStopsAnsweringDuringTheWait_exits4() throws Exception {
        startController();
        awaitFirstPull();

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--timeout", "5m")) {
            Thread.sleep(3_000);
            assertThat(until.isAlive()).as("waiting: %s", until.err()).isTrue();
            kiln.stopAnswering();

            assertThat(until.exit(Duration.ofSeconds(90))).as("stderr: %s", until.err()).isEqualTo(4);
            assertThat(until.err()).anyMatch(line -> line.contains("Source kiln, which this wait depends on, is stale"));
            assertThat(Duration.between(until.launched(), Instant.now())).isLessThanOrEqualTo(Duration.ofSeconds(85));
        }
    }

    // ---- lead ruling F4: the real kiln-service on the wire ----------------------------------------------------------

    /// Lead ruling F4: the controller pulls from the REAL kiln-service ([RealKiln]: kiln's store, migrations, epoch,
    /// high-water file and routes) instead of the [FakeKiln], which M2 showed can agree with the controller where kiln
    /// does not. The heartbeat names kiln's sources as the controller derives them (`kiln`, `kiln:github`,
    /// `kiln:highwater`, all fresh); a `PR_STATE` kiln records on a PR upsert reaches a waiting CLI as its `pr-state`
    /// line (exit 0); and kiln's epoch rotated under it (its `kiln_epoch` row deleted, recreated on the next read) is
    /// announced by the controller as a `source-reset` row.
    @Test
    void realKiln_heartbeatNamesItsSources_aPrStateReachesTheCli_andAResetIsAnnounced() throws Exception {
        try (var real = RealKiln.start(dir.resolve("kiln-change-hwm"))) {
            real.githubOk();
            real.pr(7, PrState.OPEN);
            config = config(real.url());
            startController();
            awaitKilnPulled();
            var sources = heartbeat().path("sources");
            assertThat(List.of("kiln", "kiln:github", "kiln:highwater")).as("sources: %s", sources)
                .allSatisfy(name -> assertThat(sources.path(name).path("stale").asBoolean(true)).as("%s present and fresh", name).isFalse());

            try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--match", "pr=7",
                                               "--after", position(), "--timeout", "3m")) {
                real.pr(7, PrState.CLOSED);

                assertThat(until.exit(SHORT)).as("stderr: %s", until.err()).isZero();
                assertThat(until.out()).singleElement().satisfies(line -> {
                    var event = json(line);
                    isEventLineInContractOrder(event);
                    assertThat(event.path("kind").stringValue()).isEqualTo("pr-state");
                    assertThat(event.path("source").stringValue()).isEqualTo("kiln");
                    assertThat(event.path("payload").path("pr").asLong()).isEqualTo(7);
                    assertThat(event.path("catchUp").asBoolean()).isFalse();
                });
            }

            Pg.psql(real.database(), "DELETE FROM kiln_epoch");
            awaitTrue(() -> !Pg.psql(database, "SELECT count(*) FROM fleet_events WHERE kind = 'source-reset'").equals("0"),
                      "the controller's source-reset row after kiln's epoch rotated");
        }
    }

    /// Lead ruling F4, the host half: a configured host whose link never came UP has a stale `hostlink:<h>` (seeded
    /// never-seen); a wait for a host kind depends on it and exits 4 naming it, while a wait for a kiln kind does not.
    @Test
    void until_aHostKind_whoseHostLinkNeverCameUp_exits4() throws Exception {
        var host = ControllerConfig.Host.host(HostName.hostName("ghost").expect("fleet setup"), "fleet-it.invalid", "acceptance",
                                              dir.resolve("no-key"), dir.resolve("no-known-hosts"), 1, 4096, true);
        config = config(kiln.url(), List.of(host));
        startController();
        awaitFirstPull();
        assertThat(heartbeat().path("sources").path("hostlink:ghost").path("stale").asBoolean(false)).as("hostlink:ghost stale").isTrue();

        try (var hostWait = CliProcess.launch(cliHome, url(), "events", "--until", "host-answering", "--match", "subject=ghost",
                                              "--timeout", "1m");
             var kilnWait = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--timeout", "5s")) {
            assertThat(hostWait.exit(SHORT)).as("stderr: %s", hostWait.err()).isEqualTo(4);
            assertThat(hostWait.err()).anyMatch(line -> line.contains("Source hostlink:ghost, which this wait depends on, is stale"));
            assertThat(kilnWait.exit(SHORT)).as("a kiln kind ends on its timeout: %s", kilnWait.err()).isEqualTo(5);
        }
    }

    // ---- exit 6: the controller's self-check failed --------------------------------------------------------

    /// `ssh-keyscan` gives no key line: the heartbeat says `ok:false, failed:[ssh-banner]` and a wait ends with 6.
    @Test
    void until_selfCheckFailed_exits6() throws Exception {
        banner.fail();
        startController();
        assertThat(heartbeat().path("selfCheck").path("failed").toString()).contains("ssh-banner");

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--timeout", "1m")) {
            assertThat(until.exit(SHORT)).as("stderr: %s", until.err()).isEqualTo(6);
            assertThat(until.err()).anyMatch(line -> line.contains("Controller self-check failed: ssh-banner"));
        }
    }

    // ---- lead ruling O2: the deadline does not hide a known diagnosis --------------------------------------------------

    /// `--timeout` under 55 s: the long-poll asks for 1 s less than the time left, so its heartbeat-only answer, which
    /// shows `kiln` stale (kiln stopped 3 s in, stale 15 s later), arrives before the deadline and the wait exits 4,
    /// not 5. Before the ruling the client's bound was cut to the deadline and expired just before that answer, which
    /// was dropped as "Controller unreachable".
    @Test
    void until_staleSourceFoundAtTheDeadline_exits4_not5() throws Exception {
        startController();
        awaitFirstPull();

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--timeout", "30s")) {
            Thread.sleep(3_000);
            assertThat(until.isAlive()).as("waiting: %s", until.err()).isTrue();
            kiln.stopAnswering();

            assertThat(until.exit(SHORT)).as("stderr: %s", until.err()).isEqualTo(4);
            assertThat(until.err()).anyMatch(line -> line.contains("Source kiln, which this wait depends on, is stale"))
                                   .noneMatch(line -> line.contains("unreachable"));
            assertThat(Duration.between(until.launched(), Instant.now())).as("by the deadline").isLessThan(Duration.ofSeconds(35));
        }
    }

    // ---- NOT_READY ---------------------------------------------------------------------------------------------

    /// While the first self-check runs, every fleet route answers 503 `NOT_READY`; the CLI treats it as transient
    /// and retries; once the start completes it finds the match.
    @Test
    void until_duringNotReady_retriesThe503_thenMatches() throws Exception {
        banner.close();
        var started = start();
        awaitStatus(503);
        assertThat(get("/api/v1/fleet/heartbeat").body()).contains("NOT_READY");
        var epoch = Pg.psql(database, "SELECT id FROM temper_epoch");

        try (var until = CliProcess.launch(cliHome, url(), "events", "--until", "pr-state", "--match", "pr=5",
                                           "--after", epoch + ":0", "--timeout", "3m")) {
            until.awaitErr(lines -> lines.stream().anyMatch(line -> line.contains("503")), SHORT, "a retried 503");
            assertThat(until.isAlive()).isTrue();
            banner.open();
            running = started.await(TimeSpan.timeSpan(60).seconds()).expect("controller start");
            awaitFirstPull();
            kiln.change("PR_STATE", 5, "o/r#5");

            assertThat(until.exit(SHORT)).as("stderr: %s", until.err()).isZero();
            assertThat(until.out()).singleElement().satisfies(line -> assertThat(json(line).path("payload").path("pr").asLong()).isEqualTo(5));
        }
    }

    // ---- the controller ----------------------------------------------------------------------------------------------

    private void startController() {
        running = start().await(TimeSpan.timeSpan(60).seconds()).expect("controller start");
    }

    private Promise<Controller.Running> start() {
        var git = GitCli.gitCli(ProcessRunner.processRunner(), config.repo().origin(), config.repo().mirror());
        return Controller.controller(config,
                                     store,
                                     Credentials.credentials(BearerToken.bearerToken(TOKEN).expect("fleet setup"),
                                                             some(BearerToken.bearerToken(READ_TOKEN).expect("fleet setup"))),
                                     banner,
                                     Controller.Git.git(git, git, git, GitHubPulls.gitHubPulls(config.repo().origin())),
                                     LinkTiming.DEFAULT,
                                     TimeSpan.timeSpan(5).seconds(),
                                     Instant::now)
                         .start();
    }

    private void stopController() {
        if (running != null) {
            running.stop().await(TimeSpan.timeSpan(60).seconds());
            running = null;
        }
    }

    /// The controller's config, its `[fleet] kilnUrl` = `kilnUrl` (the [FakeKiln], or a [RealKiln]), no hosts.
    private ControllerConfig config(URI kilnUrl) throws Exception {
        return config(kilnUrl, List.of());
    }

    private ControllerConfig config(URI kilnUrl, List<ControllerConfig.Host> hosts) throws Exception {
        return ControllerConfig.controllerConfig(AcceptanceStack.runnerVersion(),
                                                 ControllerConfig.Server.server("127.0.0.1", AcceptanceStack.freePort(), tokenFile, none(),
                                                                                InstanceId.instanceId("acceptance").expect("fleet setup")),
                                                 ControllerConfig.Database.database("unused", 1, database, "temper", Path.of("/unused")),
                                                 ControllerConfig.Repo.repo(fixture.origin().toString(), dir.resolve("controller-mirror.git")),
                                                 ControllerConfig.Scheduling.scheduling(Duration.ofMillis(500),
                                                                                        Duration.ofSeconds(1200),
                                                                                        Duration.ofSeconds(120),
                                                                                        Duration.ofSeconds(600)),
                                                 ControllerConfig.Units.units(MemoryMb.memoryMb(3072).expect("fleet setup"),
                                                                              MemoryMb.memoryMb(4096).expect("fleet setup"),
                                                                              1024,
                                                                              1L << 30,
                                                                              none(),
                                                                              none(),
                                                                              ControllerConfig.Units.RERUN_INSTALL_DEFAULT),
                                                 ControllerConfig.PortGroup.portGroup(List.of(), OwnerTag.ownerTag("temper").expect("fleet setup"), 1),
                                                 ControllerConfig.Worker.worker(Path.of(System.getProperty("temper.worker.jar"))),
                                                 hosts,
                                                 ControllerConfig.Evidence.evidence(Files.createDirectories(dir.resolve("evidence")), 256L << 20, 1L << 30, 0),
                                                 ControllerConfig.Retention.retention(Duration.ofHours(6)),
                                                 ControllerConfig.PgDump.pgDump(LocalTime.now().plusHours(12), "no-such-pg-container"),
                                                 ControllerConfig.Fleet.fleet(some(kilnUrl), Files.createDirectories(dir.resolve("state"))));
    }

    private static Path tokenFile(Path home, String token) throws Exception {
        var file = Files.createDirectories(home.resolve(".config/temper")).resolve("token");
        Files.writeString(file, token + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    private String url() {
        return "http://127.0.0.1:" + config.server().port();
    }

    /// The self-check's `ssh-keyscan` answered with a key line once open (it starts open), or with none after [#fail];
    /// everything else runs for real. Closed, it holds the controller's start inside its first self-check, where the
    /// fleet routes are 503.
    private static final class Banner implements ProcessRunner {
        private final ProcessRunner real = ProcessRunner.processRunner();
        private volatile Promise<Unit> gate = Promise.success(unit());
        private volatile String keys = KEY_LINE;

        void fail() {
            keys = "";
        }

        void close() {
            gate = Promise.promise();
        }

        void open() {
            gate.succeed(unit());
        }

        @Override
        public Promise<Completed> run(Command command) {
            return command.argv().getFirst().equals("ssh-keyscan")
                   ? gate.map(_ -> Completed.completed("ssh-keyscan", keys.isEmpty() ? 1 : 0, keys.getBytes(StandardCharsets.US_ASCII), new byte[0], false))
                   : real.run(command);
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            return real.spawn(command);
        }
    }

    // ---- reads ---------------------------------------------------------------------------------------------------------

    private int oneShot(String... args) throws Exception {
        try (var cli = CliProcess.launch(cliHome, url(), args)) {
            return cli.exit(SHORT);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient()
                         .send(HttpRequest.newBuilder(URI.create(url() + path)).header("Authorization", "Bearer " + TOKEN).build(),
                               HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode heartbeat() throws Exception {
        var reply = get("/api/v1/fleet/heartbeat");
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        return json(reply.body());
    }

    /// `<epoch>:<seqHead>` now: a wait from here sees only what is written after.
    private String position() throws Exception {
        var beat = heartbeat();
        return beat.path("epoch").stringValue() + ":" + beat.path("seqHead").asLong();
    }

    private void awaitStatus(int status) throws Exception {
        awaitTrue(() -> {
            try {
                return get("/api/v1/fleet/heartbeat").statusCode() == status;
            } catch (Exception e) {
                return false;
            }
        }, "fleet heartbeat " + status);
    }

    /// The controller's kiln pull has succeeded once: rows written after this are live, not catch-up.
    private void awaitFirstPull() throws Exception {
        awaitTrue(() -> {
            try {
                return kiln.reads() > 0 && heartbeat().path("sources").path("kiln").path("lastOkAt").isString();
            } catch (Exception e) {
                return false;
            }
        }, "the first kiln pull");
    }

    /// The controller's kiln pull has succeeded once, whichever kiln it reads.
    private void awaitKilnPulled() throws Exception {
        awaitTrue(() -> {
            try {
                return heartbeat().path("sources").path("kiln").path("lastOkAt").isString();
            } catch (Exception e) {
                return false;
            }
        }, "the first kiln pull");
    }

    /// The stream's seqs after `after`, once at least `count` of them come from `source`.
    private List<Long> awaitRows(long after, int count, String source) throws Exception {
        awaitTrue(() -> Long.parseLong(Pg.psql(database, "SELECT count(*) FROM fleet_events WHERE seq > " + after + " AND source = '" + source + "'")) >= count,
                  count + " " + source + " rows");
        return rowsBetween(after, Long.MAX_VALUE);
    }

    /// The stream's seqs in `(after, upTo]`, ascending.
    private List<Long> rowsBetween(long after, long upTo) {
        var text = Pg.psql(database, "SELECT seq FROM fleet_events WHERE seq > " + after + " AND seq <= " + upTo + " ORDER BY seq");
        return text.isBlank() ? List.of() : text.lines().map(Long::parseLong).toList();
    }

    private long cursor(String subscriber) {
        return Long.parseLong(Pg.psql(database, "SELECT cursor FROM fleet_subscribers WHERE name = '" + subscriber + "'"));
    }

    /// The subscriber's cursor is at the seq of the last line `follow` printed (so killing it now redelivers nothing).
    private void awaitAcked(String subscriber, CliProcess follow, ToLongFunction<String> seq) throws Exception {
        awaitTrue(() -> cursor(subscriber) == seq.applyAsLong(follow.out().getLast()), "the last printed line acked");
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws Exception {
        var deadline = Instant.now().plus(SHORT);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("never: " + what);
            }
            Thread.sleep(200);
        }
    }

    private static JsonNode json(String line) {
        return TemperJson.mapper().readTree(line).expect("a JSON line: " + line);
    }

    private static long ndjsonSeq(String line) {
        return seqOf(json(line));
    }

    private static long seqOf(JsonNode event) {
        return event.path("seq").asLong();
    }

    /// The event line's fields, in the order written.
    private static void isEventLineInContractOrder(JsonNode event) {
        assertThat(event.properties().stream().map(Map.Entry::getKey).toList())
            .containsExactly("v", "epoch", "seq", "at", "observedAt", "kind", "subject", "catchUp", "source", "payload");
    }

    private static long textSeq(String line) {
        var matcher = TEXT_HEAD.matcher(line);
        return matcher.matches() ? Long.parseLong(matcher.group(2)) : -1;
    }

    private static String subjectOf(String line) {
        var matcher = TEXT_HEAD.matcher(line);
        assertThat(matcher.matches()).as(line).isTrue();
        var subject = matcher.group(5);
        return subject.startsWith("b64:")
               ? new String(Base64.getUrlDecoder().decode(subject.substring(4)), StandardCharsets.UTF_8)
               : subject;
    }
}
