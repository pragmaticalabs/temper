package org.pragmatica.temper.controller.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.t3.TicketRoutes;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.MacProcess;
import org.pragmatica.temper.store.api.NewMacPost;
import org.pragmatica.temper.store.api.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// `GET fleet/tickets` end to end over Postgres (T3.4, T3.5, T3.6, T3.7, T3.tun): the
/// reply's shape, every host exactly once in configuration order, the host statuses, and the liveness values the
/// stored Mac post, sessions and `mac` source make of each ticket.
@Tag("pg")
class TicketRoutesIT {
    private static final String READ = "read-token";
    private static final String FULL = "full-token";
    private static final String WORKER = "worker-token";
    private static final String VERDICT = "verdict-token";
    private static final String MAC = "mac-token";
    private static final long START = 1793667998L;
    private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");
    private static final Instant RETIRE = T0.plus(java.time.Duration.ofHours(12));

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private Store store;
    private Fleet fleet;
    private Router router;

    @TempDir
    Path state;

    @BeforeEach
    void setUp() {
        store = Pg.freshStore();
        fleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state),
                            List.of(),
                            store.fleet(),
                            ProcessRunner.processRunner(),
                            none(),
                            now::get);
        fleet.settle().await(Pg.WAIT).unwrap();
        var responses = Responses.responses(TemperJson.mapper());

        router = Router.router(Credentials.credentials(token(FULL),
                                                       some(token(READ)),
                                                       some(token(WORKER)),
                                                       some(token(VERDICT)),
                                                       some(token(MAC))),
                               TicketRoutes.ticketRoutes(responses,
                                                         store.fleet(),
                                                         store.mac(),
                                                         store.bb(),
                                                         List.of(TicketRoutes.Expected.expected("h1", "hostlink"),
                                                                 TicketRoutes.Expected.expected("h2",
                                                                                                "hostlink",
                                                                                                some(RETIRE)),
                                                                 TicketRoutes.Expected.expected("scan1", "bbscan"),
                                                                 TicketRoutes.Expected.expected("oldbox", "none")),
                                                         now::get,
                                                         () -> true)
                                           .routes(),
                               responses);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static BearerToken token(String raw) {
        return BearerToken.bearerToken(raw).unwrap();
    }

    private static Stream<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------
    private static BbTicket ticket(String id, String dir, String... kv) {
        var fields = new LinkedHashMap<String, String>();

        for (var i = 0; i < kv.length; i += 2) {
            fields.put(kv[i], kv[i + 1]);
        }

        return BbTicket.bbTicket(1,
                                 id,
                                 dir,
                                 dir.equals("done")
                                 ? none()
                                 : some("alive"),
                                 fields,
                                 List.of());
    }

    private static String session(long pid, long start) {
        return "session_pid=" + pid + ",session_start=" + start + ",session_label=CTO";
    }

    private static BbTicket withSession(String id, String dir, long pid, long start, String... more) {
        return ticket(id,
                      dir,
                      Stream.concat(Arrays.stream(session(pid, start).split(",")).flatMap(kv -> Arrays.stream(kv.split("=",
                                                                                                                       2))),
                                    Arrays.stream(more))
                            .toArray(String[]::new));
    }

    private void collect(String host, BbTicket... tickets) {
        collectWithQueue(host, "present", tickets);
    }

    private void collectWithQueue(String host, String queue, BbTicket... tickets) {
        store.bb()
             .collected(host,
                        now.get(),
                        BbStore.Listing.listing(queue,
                                                List.of(tickets),
                                                now.get(),
                                                0),
                        (_, _) -> BbStore.RoundWrite.NOTHING)
             .await(Pg.WAIT)
             .unwrap();
    }

    private void post(MacProcess... processes) {
        store.mac()
             .post(NewMacPost.newMacPost(now.get(),
                                         now.get().minusSeconds(2),
                                         List.of(processes)))
             .await(Pg.WAIT)
             .unwrap();
    }

    private static MacProcess process(int pid, long start, String label) {
        return MacProcess.macProcess(pid, start, label);
    }

    private ApiResponse get(String pathAndQuery) {
        return get(pathAndQuery, READ);
    }

    private ApiResponse get(String pathAndQuery, String bearer) {
        var parts = pathAndQuery.split("\\?", 2);
        var query = parts.length > 1
                    ? QueryParams.queryParams(Arrays.stream(parts[1].split("&"))
                                                    .map(pair -> pair.split("=", 2))
                                                    .collect(Collectors.toMap(pair -> pair[0],
                                                                              pair -> List.of(pair[1]))))
                    : QueryParams.empty();

        return router.handle(ApiRequest.apiRequest(new HttpRequest() {
            @Override
            public String requestId() {
                                                       return "test";
                                                   }

            @Override
            public HttpMethod method() {
                                                       return HttpMethod.GET;
                                                   }

            @Override
            public String path() {
                                                       return parts[0];
                                                   }

            @Override
            public Headers headers() {
                                                       return Headers.fromSingleValueMap(Map.of("Authorization",
                                                                                                "Bearer " + bearer));
                                                   }

            @Override
            public QueryParams queryParams() {
                                                       return query;
                                                   }

            @Override
            public byte[] body() {
                                                       return new byte[0];
                                                   }
        }))
                     .await(Pg.WAIT)
                     .unwrap();
    }

    private static String text(ApiResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private JsonNode reply(String query) {
        var response = get("/api/v1/fleet/tickets" + query);

        assertThat(response.status().code()).as(text(response)).isEqualTo(200);

        return TemperJson.mapper()
                         .readTree(text(response))
                         .unwrap();
    }

    private static JsonNode host(JsonNode reply, String name) {
        return elements(reply.path("hosts")).filter(node -> node.path("host")
                                                                .stringValue()
                                                                .equals(name))
                       .findFirst()
                       .orElseThrow();
    }

    private static JsonNode row(JsonNode host, String id) {
        return elements(host.path("tickets")).filter(node -> node.path("id")
                                                                 .stringValue()
                                                                 .equals(id))
                       .findFirst()
                       .orElseThrow();
    }

    // ---- T3.tun, query ------------------------------------------------------------------------------------------
    /// T3.tun: a host that is not expected is 404 `NO_HOST`; a malformed `done` is 400.
    @Test
    void t3tun_anUnknownHost_is404NoHost_andABadDoneIs400() {
        var unknown = get("/api/v1/fleet/tickets?host=nope");

        assertThat(unknown.status().code()).isEqualTo(404);
        assertThat(text(unknown)).isEqualTo("{\"error\":\"NO_HOST\"}");
        assertThat(get("/api/v1/fleet/tickets?done=abc").status().code()).isEqualTo(400);
        assertThat(get("/api/v1/fleet/tickets?done=201").status().code()).isEqualTo(400);
        assertThat(get("/api/v1/fleet/tickets?done=-1").status().code()).isEqualTo(400);
        assertThat(get("/api/v1/fleet/tickets?host=h1&done=200").status().code()).isEqualTo(200);
    }

    /// The tickets route is a READ route: the MAC and VERDICT tokens are refused with 403. The WORKER token reads (Access.WORKER
    /// permits READ routes), as do READ and FULL.
    @Test
    void theRoute_refusesTheMacAndVerdictTokens_403_andAdmitsTheReadingOnes() {
        for (var bearer : List.of(MAC, VERDICT)) {
            assertThat(get("/api/v1/fleet/tickets", bearer).status().code()).as(bearer).isEqualTo(403);
        }

        for (var bearer : List.of(READ, FULL, WORKER)) {
            assertThat(get("/api/v1/fleet/tickets", bearer).status().code()).as(bearer).isEqualTo(200);
        }
    }

    /// `created` of 20 digits is outside the contract's 19-digit range: no createdAt, and the route still answers.
    @Test
    void aTwentyDigitCreated_hasNoCreatedAt() {
        collect("h1", ticket("big", "running", "created", "10000000000000000000", "pr", "0042"));
        var row = row(host(reply(""), "h1"), "big");

        assertThat(row.path("createdAt").isNull()).isTrue();
        assertThat(row.path("pr").isNull()).as("pr has no leading zeros").isTrue();
    }

    @Test
    void queuePresentVsAbsent_isStoredWithTheSnapshot_andReachesTheRouteJson() {
        post();
        collect("h1", ticket("r1", "running"));
        collectWithQueue("h2", "absent");
        var reply = reply("");

        assertThat(host(reply, "h1").path("queue").stringValue()).isEqualTo("present");
        assertThat(host(reply, "h2").path("queue").stringValue()).isEqualTo("absent");
        assertThat(host(reply, "h2").path("status").stringValue()).isEqualTo("ok");
        assertThat(host(reply, "scan1").path("queue").isNull()).as("no snapshot, no queue").isTrue();
    }

    // ---- hosts --------------------------------------------------------------------------------------------------
    @Test
    void everyExpectedHostAppearsOnce_inOrder_withItsStatus_andNoStaleTickets() {
        post();
        collect("h1", ticket("r1", "running", "owner", "o"));
        collect("h2", ticket("old", "running"));
        now.set(T0.plusSeconds(10));
        store.bb().failed("h2", now.get(), "ssh exit 255: Connection refused").await(Pg.WAIT).unwrap();
        now.set(T0.plusSeconds(20));
        store.bb().failed("h2", now.get(), "ssh exit 255: Connection refused").await(Pg.WAIT).unwrap();
        collect("h1", ticket("r1", "running", "owner", "o"));
        var reply = reply("");

        assertThat(elements(reply.path("hosts")).map(node -> node.path("host")
                                                                 .stringValue())).containsExactly("h1",
                                                                                                  "h2",
                                                                                                  "scan1",
                                                                                                  "oldbox");
        assertThat(reply.path("v").intValue()).isEqualTo(1);
        assertThat(reply.path("asOf").stringValue()).isEqualTo("2026-11-04T12:00:20Z");
        var h1 = host(reply, "h1");

        assertThat(h1.path("via").stringValue()).isEqualTo("hostlink");
        assertThat(h1.path("status").stringValue()).isEqualTo("ok");
        assertThat(h1.path("takenAt").stringValue()).isEqualTo("2026-11-04T12:00:20Z");
        assertThat(h1.path("queue").stringValue()).isEqualTo("present");
        assertThat(h1.path("tickets").size()).isEqualTo(1);
        var h2 = host(reply, "h2");

        assertThat(h2.path("status").stringValue()).isEqualTo("UNREACHABLE");
        assertThat(h2.path("since").stringValue()).as("the FIRST failure").isEqualTo("2026-11-04T12:00:10Z");
        assertThat(h2.path("error").stringValue()).isEqualTo("ssh exit 255: Connection refused");
        assertThat(h2.path("tickets").size()).as("never stale tickets").isZero();
        assertThat(h2.path("takenAt").isNull()).isTrue();
        var scan = host(reply, "scan1");

        assertThat(scan.path("status").stringValue()).as("never yet collected").isEqualTo("UNREACHABLE");
        assertThat(scan.path("since").isNull()).isTrue();
        assertThat(scan.path("via").stringValue()).isEqualTo("bbscan");
        var old = host(reply, "oldbox");

        assertThat(old.path("status").stringValue()).isEqualTo("NOT-COLLECTED");
        assertThat(old.path("via").stringValue()).isEqualTo("none");
        assertThat(reply("?host=h2").path("hosts").size()).isEqualTo(1);
    }

    @Test
    void aSuccessOlderThan90Seconds_isUnreachable_sinceItsLastOk() {
        collect("h1", ticket("r1", "running"));
        now.set(T0.plusSeconds(90));
        assertThat(host(reply(""), "h1").path("status").stringValue()).as("90 s is still ok").isEqualTo("ok");
        now.set(T0.plusSeconds(91));
        var h1 = host(reply(""), "h1");

        assertThat(h1.path("status").stringValue()).isEqualTo("UNREACHABLE");
        assertThat(h1.path("since").stringValue()).isEqualTo("2026-11-04T12:00:00Z");
        assertThat(h1.path("error").isNull()).isTrue();
        assertThat(h1.path("tickets").size()).isZero();
    }

    @Test
    void theTickets_runningThenWaiting_inListingOrder_andTheDone_newestFirst_atMostDone() {
        var done = IntStream.range(0, 12)
                            .mapToObj(i -> ticket("d" + i,
                                                  "done",
                                                  "state",
                                                  "finished",
                                                  "rc",
                                                  "0",
                                                  "ended",
                                                  Long.toString(1793700000L - i)))
                            .toArray(BbTicket[]::new);
        var all = Stream.concat(Stream.of(ticket("r1", "running"),
                                          ticket("r2", "running"),
                                          ticket("w1", "waiting")),
                                Arrays.stream(done))
                        .toArray(BbTicket[]::new);

        collect("h1", all);
        var h1 = host(reply(""), "h1");

        assertThat(elements(h1.path("tickets")).map(node -> node.path("id")
                                                                .stringValue())).containsExactly("r1", "r2", "w1");
        assertThat(elements(h1.path("done")).map(node -> node.path("id")
                                                             .stringValue())).as("default 10")
                  .containsExactly("d0", "d1", "d2", "d3", "d4", "d5", "d6", "d7", "d8", "d9");
        assertThat(elements(host(reply("?done=3"), "h1").path("done")).map(node -> node.path("id")
                                                                                       .stringValue())).containsExactly("d0",
                                                                                                                        "d1",
                                                                                                                        "d2");
        assertThat(host(reply("?done=0"), "h1").path("done").size()).isZero();
        assertThat(host(reply("?done=200"), "h1").path("done").size()).isEqualTo(12);
        var first = host(reply(""), "h1").path("done").get(0);

        assertThat(first.path("state").stringValue()).isEqualTo("finished");
        assertThat(first.path("rc").stringValue()).isEqualTo("0");
        assertThat(first.path("endedAt").stringValue()).isEqualTo(Instant.ofEpochSecond(1793700000L).toString());
    }

    @Test
    void theTicketFields_aMappedAsContracted() {
        collect("h1",
                BbTicket.bbTicket(1,
                                  "r1",
                                  "running",
                                  some("gone"),
                                  Map.of("owner",
                                         "o",
                                         "agent",
                                         "ag",
                                         "lane",
                                         "l",
                                         "pr",
                                         "42",
                                         "co",
                                         "/w/co",
                                         "output",
                                         "/w/co/.bb/build.log",
                                         "session_label",
                                         "CTO",
                                         "created",
                                         "1793700000500000000",
                                         "args",
                                         "-B clean "),
                                  List.of()));
        var r1 = row(host(reply(""), "h1"), "r1");

        assertThat(r1.path("dir").stringValue()).isEqualTo("running");
        assertThat(r1.path("owner").stringValue()).isEqualTo("o");
        assertThat(r1.path("process").stringValue()).as("waiter gone").isEqualTo("process-orphan");
        assertThat(r1.path("lane").stringValue()).isEqualTo("l");
        assertThat(r1.path("pr").longValue()).isEqualTo(42L);
        assertThat(r1.path("co").stringValue()).isEqualTo("/w/co");
        assertThat(r1.path("output").stringValue()).isEqualTo("/w/co/.bb/build.log");
        assertThat(r1.path("sessionLabel").stringValue()).isEqualTo("CTO");
        assertThat(r1.path("createdAt").stringValue()).isEqualTo(Instant.ofEpochSecond(1793700000L).toString());
        assertThat(r1.path("args").stringValue()).isEqualTo("-B clean ");
        now.set(T0.plusSeconds(1));
        collect("h1", ticket("r2", "running"));
        var r2 = row(host(reply(""), "h1"), "r2");

        assertThat(r2.path("process").stringValue()).isEqualTo("waiter-alive");
        assertThat(r2.path("owner").isNull()).isTrue();
        assertThat(r2.path("pr").isNull()).isTrue();
    }

    // ---- liveness -----------------------------------------------------------------------------------------------
    /// T3.3, T3.5, T3.6, T3.7 through the route: SESSION-ALIVE with the agent beside it, a reused pid DEAD never seen, a
    /// DEAD naming the previous post's receipt, DETACHED by label, a legacy ticket UNKNOWN.
    @Test
    void liveness_theFiveValues_throughTheRoute() {
        post(process(12, START, "gone"), process(10, START, "CTO"), process(20, START + 1, "lead1"));
        now.set(T0.plusSeconds(30));
        post(process(10, START, "CTO"), process(20, START + 1, "lead1"));
        collect("h1",
                withSession("alive", "running", 10, START, "agent", "t3wpb"),
                withSession("reused", "running", 10, START + 7),
                withSession("dead", "waiting", 12, START),
                ticket("det-alive", "running", "detached_reader", "lead1"),
                ticket("det-dead", "running", "detached_reader", "lead9"),
                ticket("legacy", "running", "owner", "o", "co", "/w"),
                withSession("done1", "done", 10, START));
        var h1 = host(reply(""), "h1");

        assertThat(row(h1, "alive").path("liveness").stringValue()).isEqualTo("SESSION-ALIVE");
        assertThat(row(h1, "alive").path("agent").stringValue()).as("T3.6: the agent is carried beside the value")
                  .isEqualTo("t3wpb");
        assertThat(row(h1, "reused").path("liveness").stringValue()).isEqualTo("DEAD(last seen never)");
        assertThat(row(h1, "dead").path("liveness").stringValue()).isEqualTo("DEAD(last seen 2026-11-04T12:00:00Z)");
        assertThat(row(h1, "det-alive").path("liveness").stringValue()).isEqualTo("DETACHED(reader=lead1 ALIVE)");
        assertThat(row(h1, "det-dead").path("liveness").stringValue()).isEqualTo("DETACHED(reader=lead9 DEAD)");
        assertThat(row(h1, "legacy").path("liveness").stringValue()).isEqualTo("UNKNOWN(no session field)");
        assertThat(row(h1, "legacy").path("agent").isNull()).isTrue();
        assertThat(reply("").path("mac").path("sessions").intValue()).isEqualTo(2);
        assertThat(reply("").path("mac").path("stale").booleanValue()).isFalse();
        assertThat(reply("").path("mac").path("lastOkAt").stringValue()).isEqualTo("2026-11-04T12:00:30Z");
        assertThat(reply("").path("mac").path("postedAt").stringValue()).isEqualTo("2026-11-04T12:00:28Z");
        assertThat(h1.path("done").size()).isEqualTo(1);
    }

    /// T3.4: the `mac` source last ok 181 s ago and the session not in the post: STALE-SOURCE; at 179 s: DEAD.
    @Test
    void t34_staleAfter180Seconds_neverDead() {
        post(process(99, START, "other"));
        var expectation = new TreeMap<>(Map.of(179L,
                                               "DEAD(last seen never)",
                                               181L,
                                               "STALE-SOURCE(mac job silent since 2026-11-04T12:00:00Z)"));

        expectation.forEach((age, value) -> {
            now.set(T0.plusSeconds(age));
            collect("h1", withSession("t", "running", 10, START), ticket("legacy", "running"));
            var reply = reply("");

            assertThat(row(host(reply, "h1"),
                           "t").path("liveness").stringValue()).as("age " + age)
                      .isEqualTo(value);
            assertThat(reply.path("mac").path("stale").booleanValue()).isEqualTo(age > 180);
            assertThat(row(host(reply, "h1"), "legacy").path("liveness").stringValue()).isEqualTo("UNKNOWN(no session field)");
        });
    }

    @Test
    void aMacJobThatNeverPosted_isStaleSourceNever_andTheMacBlockSaysSo() {
        collect("h1", withSession("t", "running", 10, START));
        var reply = reply("");

        assertThat(row(host(reply, "h1"), "t").path("liveness").stringValue()).isEqualTo("STALE-SOURCE(mac job silent since never)");
        assertThat(reply.path("mac").path("lastOkAt").isNull()).isTrue();
        assertThat(reply.path("mac").path("postedAt").isNull()).isTrue();
        assertThat(reply.path("mac").path("stale").booleanValue()).isTrue();
        assertThat(reply.path("mac").path("sessions").intValue()).isZero();
    }

    // ---- S1.17, fleet/alarms ------------------------------------------------------------------------------------
    private static BbAlarm mark(String host, String kind, String subject, String threshold) {
        return BbAlarm.bbAlarm(host, kind, subject, threshold, T0);
    }

    private void collectFiring(Instant hostAt, List<BbAlarm> fire, BbTicket... tickets) {
        store.bb()
             .collected("h1",
                        now.get(),
                        BbStore.Listing.listing("present",
                                                List.of(tickets),
                                                hostAt,
                                                3),
                        (_, _) -> BbStore.RoundWrite.roundWrite(List.of(),
                                                                fire,
                                                                List.of()))
             .await(Pg.WAIT)
             .unwrap();
    }

    private JsonNode alarms() {
        var response = get("/api/v1/fleet/alarms");

        assertThat(response.status().code()).as(text(response)).isEqualTo(200);

        return TemperJson.mapper()
                         .readTree(text(response))
                         .unwrap();
    }

    /// S1.17: `fleet/alarms` with a READ token lists only the CURRENT alarms: a fired `queue-wait` ticket that has
    /// started running is not listed, a waiting one is, once, at its highest threshold, aged on the snapshot's `hostAt`;
    /// a `host-retiring` mark is listed while `now < retireAt` (at its tightest threshold) and not past it. Mutation:
    /// list every `bb_alarm` row → the running ticket and the past `host-retiring` appear, red.
    @Test
    void s1_17_fleetAlarms_listsOnlyCurrentAlarms() {
        var hostAt = Instant.ofEpochSecond(START + 100_000);
        var created = Long.toString((hostAt.getEpochSecond() - 200 * 60) * 1_000_000_000L);

        collectFiring(hostAt,
                      List.of(mark("h1", "queue-wait", "w1", "60"),
                              mark("h1", "queue-wait", "w2", "60"),
                              mark("h1", "queue-wait", "w2", "180"),
                              mark("h1", "clock-skew", "-", "30")),
                      ticket("w1", "waiting", "created", created),
                      ticket("w2", "waiting", "created", created));
        now.set(now.get().plusSeconds(30));
        collectFiring(hostAt.plusSeconds(30),
                      List.of(),
                      ticket("w1",
                             "running",
                             "created",
                             created,
                             "started",
                             Long.toString(hostAt.getEpochSecond())),
                      ticket("w2", "waiting", "created", created));
        store.bb()
             .retiring(now.get(),
                       _ -> BbStore.RoundWrite.roundWrite(List.of(),
                                                          List.of(mark("h2",
                                                                       "host-retiring",
                                                                       RETIRE.toString(),
                                                                       "3"),
                                                                  mark("h2",
                                                                       "host-retiring",
                                                                       RETIRE.toString(),
                                                                       "1")),
                                                          List.of()))
             .await(Pg.WAIT)
             .unwrap();
        var reply = alarms();

        assertThat(reply.path("v").intValue()).isEqualTo(1);
        assertThat(elements(reply.path("hosts")).map(node -> node.path("host")
                                                                 .stringValue())).containsExactly("h1",
                                                                                                  "h2",
                                                                                                  "scan1",
                                                                                                  "oldbox");
        var h1 = host(reply, "h1");

        assertThat(h1.path("status").stringValue()).isEqualTo("ok");
        assertThat(h1.path("skewS").intValue()).isEqualTo(3);
        assertThat(h1.path("hostAt").stringValue()).isEqualTo(hostAt.plusSeconds(30).toString());
        assertThat(elements(h1.path("alarms")).map(node -> node.path("kind")
                                                               .stringValue()
                                                          + " " + node.path("ticket")
                                                                      .asString("-")
                                                          + " " + node.path("limitMin")
                                                                      .asString("-")
                                                          + " " + node.path("ageMin")
                                                                      .asString("-"))).containsExactly("clock-skew - - -",
                                                                                                       "queue-wait w2 180 200");
        var h2 = host(reply, "h2");

        assertThat(h2.path("status").stringValue()).isEqualTo("UNREACHABLE");
        assertThat(h2.path("retireAt").stringValue()).isEqualTo(RETIRE.toString());
        assertThat(elements(h2.path("alarms")).map(node -> node.path("kind")
                                                               .stringValue()
                                                          + " " + node.path("days")
                                                                      .intValue())).containsExactly("host-retiring 1");
        assertThat(host(reply, "oldbox").path("status").stringValue()).isEqualTo("NOT-COLLECTED");
        now.set(RETIRE);
        assertThat(elements(host(alarms(), "h2").path("alarms"))).as("at retireAt: not listed").isEmpty();
        assertThat(get("/api/v1/fleet/alarms", "nope").status().code()).isEqualTo(401);
    }

    /// #107: a host at or past its `retireAt` is no longer collected; `fleet/tickets` and `fleet/alarms` both answer
    /// `RETIRED` for it (never `UNREACHABLE`, which the CLI turns into exit 4 for good), one second earlier it is still
    /// judged by its collection. Mutation: judge a retired host by its collection row → `UNREACHABLE`, red.
    @Test
    void retiredHost_answersRetired_notUnreachable_inBothViews() {
        now.set(RETIRE.minusSeconds(1));
        assertThat(host(reply("?host=h2"), "h2").path("status").stringValue()).as("before retireAt").isEqualTo("UNREACHABLE");
        assertThat(host(alarms(), "h2").path("status").stringValue()).as("before retireAt").isEqualTo("UNREACHABLE");

        now.set(RETIRE);
        var tickets = host(reply("?host=h2"), "h2");

        assertThat(tickets.path("status").stringValue()).isEqualTo("RETIRED");
        assertThat(tickets.path("tickets").size()).isZero();
        assertThat(host(alarms(), "h2").path("status").stringValue()).isEqualTo("RETIRED");
    }
}
