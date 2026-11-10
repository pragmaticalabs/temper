package org.pragmatica.temper.cli;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.temper.cli.FakeController.Reply;
import static org.pragmatica.temper.cli.FleetFixtures.EPOCH;
import static org.pragmatica.temper.cli.FleetFixtures.OTHER_EPOCH;
import static org.pragmatica.temper.cli.FleetFixtures.event;
import static org.pragmatica.temper.cli.FleetFixtures.heartbeat;
import static org.pragmatica.temper.cli.FleetFixtures.reply;
import static org.pragmatica.temper.cli.FleetFixtures.staleSource;

/// `temper events` end to end: the real JDK HttpClient against the fake controller's scripted fleet
/// routes (WP3 serves them; the wire is the stream's published one), with fake time, so the 110 s heartbeat clock and
/// `--timeout` run without real waiting.
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class EventsAgainstControllerTest {
    private static final String AFTER = "GET /fleet/events/after";
    private static final String AFTER_42 = EPOCH + ":42";
    private static final String FAILURE = "{\"conclusion\":\"failure\",\"attempt\":1}";
    private static final String SUCCESS = "{\"conclusion\":\"success\",\"attempt\":1}";

    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private List<String> targets(String prefix) {
        return controller.received()
                         .stream()
                         .map(FakeController.Received::target)
                         .filter(target -> target.startsWith("/api/v1" + prefix))
                         .toList();
    }

    private Duration paused() {
        return cli.time.pauses()
                       .stream()
                       .reduce(Duration.ZERO, Duration::plus);
    }

    @Nested
    class Until {
        /// Positive control 1 (§10 T1.6): the matching line is printed as received, exit 0.
        @Test
        void until_printsTheMatchingLine_andExitsZero() {
            var match = event(43, "check-concluded", "acme/app#7", false, FAILURE);

            controller.on(AFTER, reply(match, heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly(match);
        }

        /// Positive control 2: a stream of events none of which matches (another kind; the kind with a failing
        /// `--match`) never ends the wait; `--timeout` does, exit 5, nothing printed. Probe: exit 0 on any event → red.
        @Test
        void until_timesOut_withOnlyNonMatchingEvents() {
            controller.on(AFTER,
                          reply(event(43, "pr-state", "acme/app#7", false, "{}"),
                                event(44, "check-concluded", "acme/app#7", false, SUCCESS),
                                heartbeat(44)));

            var run = cli.run("events",
                              "--until",
                              "check-concluded",
                              "--match",
                              "conclusion=failure",
                              "--after",
                              AFTER_42,
                              "--timeout",
                              "30s");

            assertThat(run.exit()).as(run.err()).isEqualTo(5);
            assertThat(run.out()).isEmpty();
            assertThat(run.err()).contains("No matching event within PT30S");
            assertThat(paused()).isEqualTo(Duration.ofSeconds(30));
            assertThat(cli.time.pauses()).allMatch(pause -> pause.compareTo(Duration.ofSeconds(1)) <= 0);
        }

        /// Positive control 3: replies that carry lines but no heartbeat line never restart the heartbeat clock: 110 s
        /// of fake time later the wait exits 3, long before its 10 min timeout. Probe: restart the clock on any reply
        /// line instead of a heartbeat → the wait runs to its timeout, exit 5 → red.
        @Test
        void until_exitsThree_after110sWithoutAHeartbeatLine() {
            controller.on(AFTER, reply(event(43, "pr-state", "acme/app#7", false, "{}")));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "10m");

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(run.err()).contains("No heartbeat from the controller for 110 s");
            assertThat(paused()).isEqualTo(Duration.ofSeconds(110));
        }

        /// A controller that is down (connect refused) is retried, backoff 1 s doubling to 30 s, until the heartbeat
        /// clock runs out (design §10 T1.5: "controller stopped → exit 3 in ≤110 s").
        @Test
        void until_exitsThree_whenTheControllerIsDown() throws IOException {
            int port;
            try (var socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            cli.env("TEMPER_URL", "http://127.0.0.1:" + port);

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(1),
                                                          Duration.ofSeconds(2),
                                                          Duration.ofSeconds(4),
                                                          Duration.ofSeconds(8),
                                                          Duration.ofSeconds(16),
                                                          Duration.ofSeconds(30),
                                                          Duration.ofSeconds(30),
                                                          Duration.ofSeconds(19));
        }

        /// Positive control 4: `kiln:github` stale while waiting on a GitHub kind → exit 4, naming it.
        @Test
        void until_exitsFour_whenGithubIsStale_forAGithubKind() {
            controller.on(AFTER, reply(heartbeat(EPOCH, 42, staleSource("kiln:github"), true)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(4);
            assertThat(run.err()).contains("Source kiln:github, which this wait depends on, is stale");
        }

        /// A stale `kiln:github` does not end a wait on a host kind: that wait depends on its hostlink only.
        @Test
        void until_ignoresAStaleGithub_forAHostKind() {
            controller.on(AFTER, reply(heartbeat(EPOCH, 42, staleSource("kiln:github"), true)));

            var run = cli.run("events",
                              "--until",
                              "host-unreachable",
                              "--match",
                              "subject=cloudbb-3",
                              "--after",
                              AFTER_42,
                              "--timeout",
                              "5s");

            assertThat(run.exit()).as(run.err()).isEqualTo(5);
        }

        @Test
        void until_exitsFour_whenTheNamedHostLinkIsStale() {
            controller.on(AFTER, reply(heartbeat(EPOCH, 42, staleSource("hostlink:cloudbb-3"), true)));

            var run = cli.run("events", "--until", "host-unreachable", "--match", "subject=cloudbb-3", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(4);
            assertThat(run.err()).contains("hostlink:cloudbb-3");
        }

        /// Without a host in `--match`, a host kind depends on every hostlink the heartbeat lists.
        @Test
        void until_exitsFour_whenAnyHostLinkIsStale_withoutAHostMatch() {
            controller.on(AFTER, reply(heartbeat(EPOCH, 42, staleSource("hostlink:bigboy"), true)));

            var run = cli.run("events", "--until", "host-answering", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(4);
            assertThat(run.err()).contains("hostlink:bigboy");
        }

        /// Fail closed: a source the wait depends on that the heartbeat does not list counts as stale.
        @Test
        void until_exitsFour_whenADependedOnSourceIsAbsent() {
            controller.on(AFTER, reply(heartbeat(EPOCH, 42, Map.of("kiln", false, "kiln:highwater", false, "selfcheck:highwater", false), true)));

            var run = cli.run("events", "--until", "pr-head-moved", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(4);
            assertThat(run.err()).contains("kiln:github");
        }

        /// Positive control 5 (R2-6.3): a catch-up row of the awaited kind does not end the wait by default, even when
        /// the controller sends it. Probe: drop the catch-up check → exit 0 → red.
        @Test
        void until_ignoresCatchUpRows_byDefault() {
            controller.on(AFTER, reply(event(43, "check-concluded", "acme/app#7", true, FAILURE), heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "5s");

            assertThat(run.exit()).as(run.err()).isEqualTo(5);
            assertThat(run.out()).isEmpty();
            assertThat(targets("/fleet/events/after")).allMatch(target -> target.contains("includeCatchUp=0"));
        }

        @Test
        void until_takesCatchUpRows_withIncludeCatchUp() {
            var replayed = event(43, "check-concluded", "acme/app#7", true, FAILURE);

            controller.on(AFTER, reply(replayed, heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--include-catch-up");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly(replayed);
            assertThat(targets("/fleet/events/after").getFirst()).contains("includeCatchUp=1");
        }

        /// Positive control 6 (R2-6.2): `--after` names an epoch the controller no longer has (409 EPOCH_CHANGED) → 7.
        @Test
        void until_exitsSeven_onEpochChanged() {
            controller.on(AFTER, Reply.json(409, "{\"error\":\"EPOCH_CHANGED\",\"epoch\":\"" + OTHER_EPOCH + "\"}"));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(7);
            assertThat(run.err()).contains(EPOCH)
                                 .contains(OTHER_EPOCH);
        }

        /// The same when only the reply's lines name the other epoch: the CLI compares it itself.
        @Test
        void until_exitsSeven_whenTheHeartbeatNamesAnotherEpoch() {
            controller.on(AFTER, reply(heartbeat(OTHER_EPOCH, 3, FleetFixtures.FRESH, true)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(7);
        }

        @Test
        void until_exitsSeven_whenAMatchingEventNamesAnotherEpoch() {
            controller.on(AFTER,
                          reply(event(OTHER_EPOCH, 43, "check-concluded", "acme/app#7", false, FAILURE), heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(7);
            assertThat(run.out()).isEmpty();
        }

        @Test
        void until_exitsSix_whenTheSelfCheckFailed() {
            controller.on(AFTER, reply(heartbeat(EPOCH, 42, FleetFixtures.FRESH, false, "ssh-banner")));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(6);
            assertThat(run.err()).contains("Controller self-check failed: ssh-banner");
        }

        /// Lead ruling F1 (4 beats 0): the reply's heartbeat is judged before its events, so a match that arrives with
        /// a stale dependency ends the wait with 4 and is not printed. Probe: match before verdict → exit 0 → red.
        @Test
        void until_aMatchWithAStaleDependency_exitsFour_andPrintsNothing() {
            controller.on(AFTER,
                          reply(event(43, "check-concluded", "acme/app#7", false, FAILURE),
                                heartbeat(EPOCH, 43, staleSource("selfcheck:highwater"), true)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(4);
            assertThat(run.err()).contains("Source selfcheck:highwater, which this wait depends on, is stale");
            assertThat(run.out()).isEmpty();
        }

        /// Lead ruling F1: a match that arrives with a failed self-check ends the wait with 6, not printed.
        @Test
        void until_aMatchWithAFailedSelfCheck_exitsSix_andPrintsNothing() {
            controller.on(AFTER,
                          reply(event(43, "check-concluded", "acme/app#7", false, FAILURE),
                                heartbeat(EPOCH, 43, FleetFixtures.FRESH, false, "loopback")));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(6);
            assertThat(run.err()).contains("Controller self-check failed: loopback");
            assertThat(run.out()).isEmpty();
        }

        /// Lead ruling F1: a match in a reply without its heartbeat cannot be judged, so the reply is retried from the
        /// same position (the match is asked for again, not skipped), and the next reply, heartbeat included, ends it.
        @Test
        void until_aMatchWithoutItsHeartbeat_isAskedForAgain() {
            var match = event(43, "check-concluded", "acme/app#7", false, FAILURE);

            controller.on(AFTER, reply(match), reply(match, heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly(match);
            assertThat(targets("/fleet/events/after")).hasSize(2)
                                                       .allMatch(target -> target.contains("after=" + encoded(AFTER_42)));
        }

        /// Without `--after` the wait starts at the head the heartbeat names.
        @Test
        void until_withoutAfter_startsAtTheHeartbeatsHead() {
            var match = event(42, "pr-head-moved", "acme/app#7", false, "{}");

            controller.on("GET /fleet/heartbeat", reply(heartbeat(41)))
                      .on(AFTER, reply(match, heartbeat(42)));

            var run = cli.run("events", "--until", "pr-head-moved");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly(match);
            assertThat(targets("/fleet/events/after").getFirst()).contains("after=" + encoded(EPOCH + ":41"));
        }

        /// The query carries every option (`--match` repeated joins comma-separated), the long-poll's 55 s, and the token.
        @Test
        void until_queryCarriesKindsMatchCatchUpAndWait() {
            controller.on(AFTER, reply(event(43, "check-concluded", "acme/app#7", false, "{\"conclusion\":\"failure\",\"event\":\"push\"}"),
                                       heartbeat(43)));

            var run = cli.run("events",
                              "--until",
                              "check-concluded,pr-state",
                              "--match",
                              "conclusion=failure",
                              "--match",
                              "event=push",
                              "--after",
                              AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(controller.received()
                                 .getFirst()
                                 .target()).isEqualTo("/api/v1/fleet/events/after?after=" + encoded(AFTER_42)
                                                      + "&includeCatchUp=0&match=" + encoded("conclusion=failure,event=push")
                                                      + "&until=" + encoded("check-concluded,pr-state") + "&wait=55");
            assertThat(controller.received()
                                 .getFirst()
                                 .authorization()).isEqualTo("Bearer " + CliHarness.TOKEN);
        }

        /// A `--timeout` shorter than the long-poll holds it 1 s less than the time left (lead ruling O2), so the
        /// heartbeat-only answer at the wait's end arrives before the deadline instead of racing the client's bound.
        @Test
        void until_cutsTheLongPoll_toOneSecondBeforeTheTimeout() {
            controller.on(AFTER, reply(event(43, "check-concluded", "acme/app#7", false, FAILURE), heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "20s");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(targets("/fleet/events/after").getFirst()).endsWith("&wait=19");
        }

        /// Lead ruling O2: the long-poll's answer is awaited until 1 s past `--timeout`. The controller answers its 1 s
        /// hold late, 0.5 s after the deadline, with `kiln` stale: the answer is still read and the wait exits 4, not 5.
        /// Probe: cut the bound to the time left of `--timeout` → the answer is dropped, 5 → red.
        @Test
        void until_aLateHeartbeatAtTheDeadline_isStillJudged_exits4() {
            controller.answerAfter(Duration.ofMillis(3_500))
                      .on(AFTER, reply(heartbeat(EPOCH, 42, staleSource("kiln"), true)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "3s");

            assertThat(run.exit()).as(run.err()).isEqualTo(4);
            assertThat(run.err()).contains("kiln").doesNotContain("unreachable");
        }

        /// The position moves past the events received, so the next request does not see them again.
        @Test
        void until_asksAfterTheLastEventReceived() {
            var match = event(44, "check-concluded", "acme/app#7", false, FAILURE);

            controller.on(AFTER,
                          reply(event(43, "pr-state", "acme/app#7", false, "{}"), heartbeat(43)),
                          reply(match, heartbeat(44)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly(match);
            assertThat(targets("/fleet/events/after").get(1)).contains("after=" + encoded(EPOCH + ":43"));
        }

        /// A 5xx is retried after 1 s; the match then ends the wait.
        @Test
        void until_retriesAServerError() {
            var match = event(43, "check-concluded", "acme/app#7", false, FAILURE);

            controller.on(AFTER, Reply.json(503, "{\"error\":\"busy\"}"), reply(match, heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(1));
            assertThat(run.err()).contains("Unexpected HTTP 503").contains("retrying in 1s");
        }

        /// A failure no retry changes ends the wait after one request, with its own code (`FleetApi.isFinal`); a 5xx
        /// (500 is the first) is retried.
        @ParameterizedTest
        @CsvSource({"400,64", "404,64", "418,69", "499,69", "403,77"})
        void until_endsAtOnce_onAFinalStatus(int status, int exit) {
            controller.on(AFTER, Reply.json(status, "{\"error\":\"no\"}"));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(exit);
            assertThat(controller.received()).hasSize(1);
            assertThat(cli.time.pauses()).isEmpty();
        }

        @Test
        void until_retriesA500() {
            var match = event(43, "check-concluded", "acme/app#7", false, FAILURE);

            controller.on(AFTER, Reply.json(500, "{\"error\":\"db\"}"), reply(match, heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(controller.received()).hasSize(2);
        }

        /// A controller of another build (D-6) ends the wait at once: 13.
        @Test
        void until_exits13_onVersionSkew() {
            controller.temperVersion(Optional.of("0.1.0-SNAPSHOT+222222222222"))
                      .on(AFTER, reply(heartbeat(42)));

            var run = cli.version("0.1.0-SNAPSHOT+111111111111")
                         .run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(13);
            assertThat(controller.received()).hasSize(1);
        }

        /// The 409 ends the wait after one request.
        @Test
        void until_epochChanged_endsAtOnce() {
            controller.on(AFTER, Reply.json(409, "{\"error\":\"EPOCH_CHANGED\"}"));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).isEqualTo(7);
            assertThat(controller.received()).hasSize(1);
            assertThat(cli.time.pauses()).isEmpty();
        }

        /// A reply whose line does not decode is retried, not ended on.
        @Test
        void until_retriesAnUndecodableReply() {
            var match = event(43, "check-concluded", "acme/app#7", false, FAILURE);

            controller.on(AFTER, reply("<html>proxy</html>"), reply(match, heartbeat(43)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.err()).contains("Unreadable controller answer from /fleet/events/after");
            assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(1));
        }

        /// Without `--after`, a failed heartbeat read is retried before the wait starts.
        @Test
        void until_withoutAfter_retriesTheHeartbeatRead() {
            var match = event(42, "pr-head-moved", "acme/app#7", false, "{}");

            controller.on("GET /fleet/heartbeat", Reply.json(503, "{\"error\":\"busy\"}"), reply(heartbeat(41)))
                      .on(AFTER, reply(match, heartbeat(42)));

            var run = cli.run("events", "--until", "pr-head-moved");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(1));
        }

        /// v2 (verifier F2): heartbeat-only replies restart the clock on every receipt: a 5 min wait ends by its timeout
        /// (5), never by silence (3). Probe: `Waiting.beaten` keeps the old beat → exit 3 at 110 s → red.
        @Test
        void v2_until_heartbeatsButNoEvents_exitsFiveAtTimeout_notThree() {
            controller.on(AFTER, reply(heartbeat(42)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "5m");

            assertThat(run.exit()).as(run.err()).isEqualTo(5);
            assertThat(paused()).isEqualTo(Duration.ofMinutes(5));
        }

        /// v3: 5xx for the whole wait never restarts the clock: exit 3 at 110 s.
        @Test
        void v3_until_serverErrorsThroughout_exitThreeAt110s() {
            controller.on(AFTER, Reply.json(502, "{\"error\":\"gateway\"}"));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "10m");

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(paused()).isEqualTo(Duration.ofSeconds(110));
        }

        /// v4: a heartbeat line cut in half is no heartbeat: exit 3 at 110 s.
        @Test
        void v4_until_halfAHeartbeatLine_isNeverABeat() {
            var beat = heartbeat(42);

            controller.on(AFTER, reply(beat.substring(0, beat.length() / 2)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "10m");

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(paused()).isEqualTo(Duration.ofSeconds(110));
        }

        /// v5: every pair must hold, given repeated and comma-separated, on subject and payload alike.
        @Test
        void v5_until_matchesOnlyWhenEveryPairHolds() {
            var almost = event(43, "check-concluded", "acme/app#7", false, "{\"conclusion\":\"failure\",\"attempt\":2}");
            var other = event(44, "check-concluded", "acme/app#8", false, FAILURE);
            var match = event(45, "check-concluded", "acme/app#7", false, FAILURE);

            controller.on(AFTER, reply(almost, other, match, heartbeat(45)));

            var run = cli.run("events",
                              "--until",
                              "check-concluded",
                              "--match",
                              "subject=acme/app#7,conclusion=failure",
                              "--match",
                              "attempt=1",
                              "--after",
                              AFTER_42);

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly(match);
        }

        /// v6: a source that goes stale after fresh beats ends the wait on the stale beat, not before.
        @Test
        void v6_until_staleAfterFresh_exitsFourOnTheStaleBeat() {
            controller.on(AFTER,
                          reply(heartbeat(42)),
                          reply(heartbeat(42)),
                          reply(heartbeat(EPOCH, 42, staleSource("kiln"), true)));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).as(run.err()).isEqualTo(4);
            assertThat(targets("/fleet/events/after")).hasSize(3);
        }

        /// The bounds run on the monotonic clock: a wall clock that steps an hour back on every read changes nothing.
        /// Probe: time `events` on `context.clock()` → the deadline never comes → red (test timeout).
        @Test
        void until_bounds_ignoreWallClockSteps() {
            var wall = new AtomicReference<>(Instant.parse("2026-11-03T10:00:00Z"));

            controller.on(AFTER, reply(heartbeat(42)));

            var run = cli.runAt(() -> wall.getAndUpdate(at -> at.minusSeconds(3600)),
                                "events",
                                "--until",
                                "check-concluded",
                                "--after",
                                AFTER_42,
                                "--timeout",
                                "3m");

            assertThat(run.exit()).as(run.err()).isEqualTo(5);
            assertThat(paused()).isEqualTo(Duration.ofMinutes(3));
        }

        /// A body that stalls after the headers is cut by the call's own bound, not held: exit 5 by `--timeout` + 1 s
        /// (lead ruling O2: the bound is `min(hold + slack, time left + 1 s)`). The deadline is on the wall (clock and
        /// monotonic counter real, pauses fake): on fake time, which moves only on pauses, the stalled calls would never
        /// bring the deadline nearer and a second bound would be measured. Probe: drop the whole-exchange bound in
        /// `HttpTransport` → the call hangs → red (test timeout).
        @Test
        void until_stalledBody_isCutByTheBound() throws Exception {
            try (var stalled = StalledServer.start()) {
                cli.env("TEMPER_URL", stalled.url());
                var started = System.nanoTime();

                var run = cli.runOnTheWall("events", "--until", "check-concluded", "--after", AFTER_42, "--timeout", "3s");

                assertThat(run.exit()).as(run.err()).isEqualTo(5);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("--timeout 3 s + 1 s grace + 0.75 s margin")
                                                                          .isLessThan(Duration.ofMillis(4_750));
            }
        }

        @Test
        void until_exits77_onABadToken() {
            controller.on(AFTER, Reply.json(401, "{\"error\":\"unauthorized\"}"));

            var run = cli.run("events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).isEqualTo(77);
            assertThat(controller.received()).hasSize(1);
        }

        /// A closed stdout loses the line: exit 5, as every command's.
        @Test
        void until_exitsFive_whenStdoutIsClosed() {
            controller.on(AFTER, reply(event(43, "check-concluded", "acme/app#7", false, FAILURE), heartbeat(43)));

            var run = cli.run(CapturedOutput.killedAfter(1), "events", "--until", "check-concluded", "--after", AFTER_42);

            assertThat(run.exit()).isEqualTo(5);
        }
    }

    /// An ack body as the CLI sends it (lead ruling F2).
    private static String ack(String epoch, long seq) {
        return "{\"epoch\":\"" + epoch + "\",\"seq\":" + seq + "}";
    }

    @Nested
    class Follow {
        private static final String EVENTS = "GET /fleet/events";
        private static final String ACK = "POST /fleet/subscribers/cto/ack";
        private static final Reply STOP = Reply.json(401, "{\"error\":\"unauthorized\"}");

        private long acks() {
            return controller.received()
                             .stream()
                             .filter(received -> received.target()
                                                         .endsWith("/ack"))
                             .count();
        }

        /// Positive control 7: each line is printed and flushed before its ack is sent: at each flush, the acks the
        /// controller has received are exactly those of the lines before it. Probe: ack before print → the first
        /// flush sees one ack already → red.
        @Test
        void follow_printsEachLine_thenAcksIt() {
            var first = event(5, "check-concluded", "acme/app#7", false, FAILURE);
            var second = event(6, "pr-state", "acme/app#7", false, "{}");
            var journal = new CopyOnWriteArrayList<String>();

            controller.on(EVENTS, reply(first, second, heartbeat(6)), STOP)
                      .on(ACK, new Reply(204, "", "application/json"));

            var run = cli.run(CapturedOutput.journaled(Integer.MAX_VALUE, line -> journal.add(acks() + " " + line)),
                              "events",
                              "--subscriber",
                              "cto",
                              "--follow");

            assertThat(run.exit()).isEqualTo(77);
            assertThat(run.out()).containsExactly(first, second);
            assertThat(journal).containsExactly("0 " + first, "1 " + second);
            assertThat(controller.received()
                                 .stream()
                                 .filter(received -> received.target()
                                                             .endsWith("/ack"))
                                 .map(FakeController.Received::body)).containsExactly(ack(EPOCH, 5), ack(EPOCH, 6));
            assertThat(targets("/fleet/events").getFirst()).isEqualTo("/api/v1/fleet/events?subscriber=cto&max=500&wait=55&format=ndjson");
        }

        /// A line that cannot be flushed is never acked: exit 5 and no ack request.
        @Test
        void follow_neverAcks_aLineThatWasNotPrinted() {
            controller.on(EVENTS, reply(event(5, "pr-state", "acme/app#7", false, "{}"), heartbeat(5)))
                      .on(ACK, new Reply(204, "", "application/json"));

            var run = cli.run(CapturedOutput.killedAfter(1), "events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).isEqualTo(5);
            assertThat(run.err()).contains("stdout is closed");
            assertThat(acks()).isZero();
        }

        /// A failed ack is retried; the line is not printed again (at-least-once, one copy per process).
        @Test
        void follow_retriesAFailedAck_withoutPrintingTheLineAgain() {
            var line = event(5, "pr-state", "acme/app#7", false, "{}");

            controller.on(EVENTS, reply(line, heartbeat(5)), STOP)
                      .on(ACK, Reply.json(500, "{\"error\":\"db\"}"), new Reply(204, "", "application/json"));

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).isEqualTo(77);
            assertThat(run.out()).containsExactly(line);
            assertThat(acks()).isEqualTo(2);
            assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(1));
        }

        /// v1 (verifier F1): after a restore the new epoch's seqs start below the old; its lines, `epoch-changed`
        /// first, are printed, not taken for repeats. Probe: dedup by seq alone → the second line is acked unprinted → red.
        @Test
        void v1_follow_printsTheNewEpochsLines_afterARestore() {
            var old = event(50, "pr-state", "acme/app#7", false, "{}");
            var changed = event(OTHER_EPOCH, 10, "epoch-changed", "temper", false, "{\"reason\":\"restore\"}");
            var next = event(OTHER_EPOCH, 11, "pr-state", "acme/app#7", false, "{}");

            controller.on(EVENTS,
                          reply(old, heartbeat(50)),
                          reply(changed, next, heartbeat(OTHER_EPOCH, 11, FleetFixtures.FRESH, true)),
                          STOP)
                      .on(ACK, new Reply(204, "", "application/json"));

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).as(run.err()).isEqualTo(77);
            assertThat(run.out()).containsExactly(old, changed, next);
        }

        /// Lead ruling F2: an ack names the epoch its line was read in (the reply's heartbeat's, also for a line of an
        /// older epoch the batch carries). Refused with 409 (a restore between print and ack), the rest of the old
        /// batch is dropped and the next poll reads again from the cursor: the new epoch's lines, `epoch-changed`
        /// first, are printed, none is skipped, and an old line printed before (49) is acked, not printed again.
        /// Probe: treat the 409 as any failure → the follow stops → red.
        @Test
        void f2_follow_anAckRefusedForItsEpoch_dropsTheBatch_andReadsTheNewEpoch() {
            var old = event(50, "pr-state", "acme/app#7", false, "{}");
            var dropped = event(51, "pr-state", "acme/app#8", false, "{}");
            var survivor = event(49, "pr-state", "acme/app#6", false, "{}");
            var changed = event(OTHER_EPOCH, 50, "epoch-changed", "temper", false, "{\"reason\":\"restore\"}");

            controller.on(EVENTS,
                          reply(old, dropped, heartbeat(51)),
                          reply(survivor, changed, heartbeat(OTHER_EPOCH, 50, FleetFixtures.FRESH, true)),
                          STOP)
                      .on(ACK, Reply.json(409, "{\"error\":\"EPOCH_MISMATCH\"}"), new Reply(204, "", "application/json"));

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).as(run.err()).isEqualTo(77);
            assertThat(run.out()).containsExactly(old, changed);
            assertThat(run.err()).contains("Ack refused: the controller's epoch is no longer " + EPOCH);
            assertThat(controller.received()
                                 .stream()
                                 .filter(received -> received.target()
                                                             .endsWith("/ack"))
                                 .map(FakeController.Received::body)).containsExactly(ack(EPOCH, 50), ack(OTHER_EPOCH, 49), ack(OTHER_EPOCH, 50));
            assertThat(cli.time.pauses()).as("no backoff: an epoch boundary is not a failure").isEmpty();
        }

        /// Lead ruling F2: a reply whose events come without their heartbeat names no epoch to ack them in; it is not
        /// delivered but retried, so its lines are printed from the next, complete reply.
        @Test
        void f2_follow_eventsWithoutTheirHeartbeat_areNotDelivered_butReadAgain() {
            var line = event(5, "pr-state", "acme/app#7", false, "{}");

            controller.on(EVENTS, reply(line), reply(line, heartbeat(5)), STOP)
                      .on(ACK, new Reply(204, "", "application/json"));

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).as(run.err()).isEqualTo(77);
            assertThat(run.out()).containsExactly(line);
            assertThat(acks()).isEqualTo(1);
            assertThat(cli.time.pauses()).containsExactly(Duration.ofSeconds(1));
        }

        /// A long batch whose acks each need a retry spans more than 110 s; every answered ack arms the clock again,
        /// so the follow delivers it all. Probe: no re-arm on ack → exit 3 mid-batch → red.
        @Test
        void follow_longBatch_withSlowAcks_isNotSilence() {
            var lines = new String[121];
            var acks = new Reply[240];

            for (var index = 0; index < 120; index++) {
                lines[index] = event(100 + index, "pr-state", "acme/app#7", false, "{}");
                acks[2 * index] = Reply.json(500, "{\"error\":\"db\"}");
                acks[2 * index + 1] = new Reply(204, "", "application/json");
            }
            lines[120] = heartbeat(219);
            controller.on(EVENTS, reply(lines), STOP)
                      .on(ACK, acks);

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).as(run.err()).isEqualTo(77);
            assertThat(run.out()).hasSize(120);
            assertThat(paused()).isEqualTo(Duration.ofSeconds(120));
        }

        /// C4.2: an ack above the head is 400 `ACK_AHEAD`: a defect, not a transient; the follow stops with 64 after
        /// one ack, and it only ever acks the seq of a line it received.
        @Test
        void follow_ackAhead_exits64_withoutARetry() {
            var line = event(5, "pr-state", "acme/app#7", false, "{}");

            controller.on(EVENTS, reply(line, heartbeat(5)))
                      .on(ACK, Reply.json(400, "{\"error\":\"ACK_AHEAD\"}"));

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).as(run.err()).isEqualTo(64);
            assertThat(run.err()).contains("ACK_AHEAD");
            assertThat(acks()).isEqualTo(1);
            assertThat(controller.received()
                                 .stream()
                                 .filter(received -> received.target()
                                                             .endsWith("/ack"))
                                 .map(FakeController.Received::body)).containsExactly(ack(EPOCH, 5));
            assertThat(cli.time.pauses()).isEmpty();
        }

        @Test
        void follow_exitsThree_after110sWithoutAHeartbeat() {
            controller.on(EVENTS, reply());

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).as(run.err()).isEqualTo(3);
            assertThat(paused()).isEqualTo(Duration.ofSeconds(110));
        }

        /// Heartbeat-only replies keep the follow alive: a 1 s pause each, no exit 3.
        @Test
        void follow_heartbeatOnlyReplies_keepItAlive() {
            var heartbeats = new Reply[200];

            Arrays.fill(heartbeats, reply(heartbeat(4)));
            heartbeats[199] = STOP;
            controller.on(EVENTS, heartbeats);

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).as(run.err()).isEqualTo(77);
            assertThat(paused()).isEqualTo(Duration.ofSeconds(199));
        }

        @Test
        void follow_exits64_forAnUnknownSubscriber() {
            controller.on(EVENTS, Reply.json(404, "{\"error\":\"NO_SUBSCRIBER\"}"));

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).isEqualTo(64);
            assertThat(run.err()).contains("NO_SUBSCRIBER");
        }

        @Test
        void follow_exits77_forAnotherTokensSubscriber() {
            controller.on(EVENTS, Reply.json(403, "{\"error\":\"forbidden\"}"));

            var run = cli.run("events", "--subscriber", "cto", "--follow");

            assertThat(run.exit()).isEqualTo(77);
        }

        /// `--format text`: the text lines are printed as received; the seq to ack comes from their head.
        @Test
        void follow_textFormat_printsTextLines_andAcksTheirSeq() {
            var line = "0f1e2d3c:5 2026-11-03T10:00:00Z check-concluded acme/app#7 catchUp=0 conclusion=failure";

            controller.on(EVENTS, reply(line, heartbeat(5)), STOP)
                      .on(ACK, new Reply(204, "", "application/json"));

            var run = cli.run("events", "--subscriber", "cto", "--follow", "--format", "text");

            assertThat(run.exit()).as(run.err()).isEqualTo(77);
            assertThat(run.out()).containsExactly(line);
            assertThat(targets("/fleet/events").getFirst()).endsWith("&format=text");
            assertThat(controller.received()
                                 .stream()
                                 .filter(received -> received.target()
                                                             .endsWith("/ack"))
                                 .map(FakeController.Received::body)).containsExactly(ack(EPOCH, 5));
        }
    }

    @Nested
    class Create {
        private static final String SUBSCRIBERS = "POST /fleet/subscribers";

        @Test
        void create_postsNameAndStart() {
            controller.on(SUBSCRIBERS, Reply.json(201, "{\"name\":\"cto\"}"));

            var run = cli.run("events", "--create", "cto", "--start", "seq:41");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly("events subscriber cto created start=seq:41 bind-to=caller");
            assertThat(controller.received()
                                 .getFirst()
                                 .body()).isEqualTo("{\"name\":\"cto\",\"start\":\"seq:41\",\"bindTo\":\"caller\"}");
        }

        @Test
        void create_startsAtTheHead_byDefault() {
            controller.on(SUBSCRIBERS, Reply.json(201, "{}"));

            var run = cli.run("events", "--create", "cto");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(controller.received()
                                 .getFirst()
                                 .body()).isEqualTo("{\"name\":\"cto\",\"start\":\"head\",\"bindTo\":\"caller\"}");
        }

        /// `--bind-to read` binds the subscriber to the controller's read token.
        @Test
        void create_bindsToTheReadToken() {
            controller.on(SUBSCRIBERS, Reply.json(201, "{}"));

            var run = cli.run("events", "--create", "dispatcher", "--bind-to", "read");

            assertThat(run.exit()).as(run.err()).isZero();
            assertThat(run.out()).containsExactly("events subscriber dispatcher created start=head bind-to=read");
            assertThat(controller.received()
                                 .getFirst()
                                 .body()).isEqualTo("{\"name\":\"dispatcher\",\"start\":\"head\",\"bindTo\":\"read\"}");
        }

        /// A controller without a read token refuses `bindTo:read` with 400: exit 64.
        @Test
        void create_bindToRead_withoutAReadToken_exits64() {
            controller.on(SUBSCRIBERS, Reply.json(400, "{\"error\":\"no read token configured\"}"));

            var run = cli.run("events", "--create", "dispatcher", "--bind-to", "read");

            assertThat(run.exit()).isEqualTo(64);
            assertThat(run.err()).contains("no read token configured");
        }

        @Test
        void create_exitsTwo_whenTheNameExists() {
            controller.on(SUBSCRIBERS, Reply.json(409, "{\"error\":\"subscriber exists\"}"));

            var run = cli.run("events", "--create", "cto");

            assertThat(run.exit()).isEqualTo(2);
            assertThat(run.err()).contains("subscriber exists");
        }

        @Test
        void create_exits77_forTheReadToken() {
            controller.on(SUBSCRIBERS, Reply.json(403, "{\"error\":\"forbidden\"}"));

            assertThat(cli.run("events", "--create", "cto")
                          .exit()).isEqualTo(77);
        }
    }

    /// Usage errors exit 64 without a request.
    @ParameterizedTest
    @ValueSource(strings = {"events",
                            "events --until Check_Concluded",
                            "events --until check-concluded,",
                            "events --until check-concluded --after 42",
                            "events --until check-concluded --after 0f1e2d3c:42",
                            "events --until check-concluded --match conclusion",
                            "events --until check-concluded --timeout 0s",
                            "events --until check-concluded --subscriber cto",
                            "events --subscriber cto",
                            "events --follow",
                            "events --subscriber cto --follow --format xml",
                            "events --create cto --start tail",
                            "events --create cto --follow",
                            "events --create cto --bind-to anyone",
                            "events --until check-concluded --bind-to read"})
    void usage_exits64_withoutARequest(String line) {
        var run = cli.run(line.split(" "));

        assertThat(run.exit()).as(run.err()).isEqualTo(64);
        assertThat(controller.received()).isEmpty();
    }
}
