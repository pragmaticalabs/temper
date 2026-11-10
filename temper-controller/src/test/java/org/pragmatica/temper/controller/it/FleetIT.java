package org.pragmatica.temper.controller.it;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.fleet.HighWater;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.fleet.KilnClient;
import org.pragmatica.temper.controller.fleet.KilnReply;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.LinkEnv;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.FleetStore;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The fleet stream end to end over Postgres: the routes and their scopes, the subscriber's
/// token binding (R2-f), the epoch check of the stateless wait, staleness by age of a frozen kiln source (F13), the
/// automatic temper epoch (R3-4), and the host-link transition events.
@Tag("pg")
class FleetIT {
    private static final String FULL = "full-token";
    private static final String READ = "read-token";
    private static final HostName BIGBOY = new HostName("bigboy");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-03T12:00:00Z"));
    private Store store;
    private String database;
    private Fleet fleet;
    private Router router;
    @TempDir
    Path state;

    @BeforeEach
    void setUp() {
        var named = Pg.freshNamed();
        store = named.store();
        database = named.database();
        fleet = fleet();
        fleet.settle().await(Pg.WAIT).unwrap();
        router = router(some(BearerToken.bearerToken(READ).unwrap()));
    }

    /// The routes as served after the controller's start (the start gate itself is [#gated_beforeStart_everyFleetRouteIs503NotReady_andAfterItTheVerdictServes]).
    private Router router(Option<BearerToken> read) {
        return Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), read),
                             fleet.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), read.map(BearerToken::id), () -> true),
                             Responses.responses(TemperJson.mapper()));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void create_isWrite_201_then409() {
        assertThat(call(READ, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\"}").status().code()).isEqualTo(403);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"head\"}").status().code()).isEqualTo(201);
        var again = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");

        assertThat(again.status().code()).isEqualTo(409);
        assertThat(text(again)).contains("SUBSCRIBER_EXISTS");
    }

    /// Lead ruling F2 (no two-statement window): a rotation that lands right before the store's ack, after every read
    /// the route made, still refuses the old epoch's ack with 409 and the cursor stays. The seam rotates the epoch
    /// inside the `ack` call, before the real one runs. Probe: check the epoch on a head read before a plain update → 204,
    /// the cursor moves → red.
    @Test
    void ack_whenTheEpochRotatesJustBeforeTheWrite_is409_andTheCursorStays() {
        var real = store.fleet();
        var rotating = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                           new Class<?>[] {FleetStore.class},
                                                           (_, method, args) -> method.getName().equals("ack")
                                                                                ? real.renewEpoch().flatMap(_ -> invoked(real, method, args))
                                                                                : method.invoke(real, args));
        var raced = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("rotating")), List.of(), rotating, ProcessRunner.processRunner(), none(), now::get);
        raced.settle().await(Pg.WAIT).unwrap();
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               raced.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none(), () -> true),
                               Responses.responses(TemperJson.mapper()));
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");

        var acked = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(1));

        assertThat(acked.status().code()).as(text(acked)).isEqualTo(409);
        assertThat(text(acked)).contains("EPOCH_MISMATCH");
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).as("the cursor stays").isZero();
    }

    /// Verifier F6 (the epoch first, whatever the seq): an ack of another epoch whose seq is ALSO above the head
    /// (an ack held across a restore) is 409 `EPOCH_MISMATCH`, never 400 `ACK_AHEAD`, which would end `--follow` with 64.
    /// Probe: check the head before the epoch → 400 → red.
    @Test
    void ack_inAnotherEpochAndAboveTheHead_is409NotAckAhead_andTheCursorStays() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");

        var held = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack",
                        "{\"epoch\":\"" + UUID.randomUUID() + "\",\"seq\":50}");

        assertThat(held.status().code()).as(text(held)).isEqualTo(409);
        assertThat(text(held)).contains("EPOCH_MISMATCH");
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).as("the cursor stays").isZero();
    }

    /// Verifier F7: an events reply's rows are read in one epoch; when the epoch rotates between that read and the
    /// heartbeat's, the heartbeat would label old-log rows with the new epoch and their ack (in the new epoch) would be
    /// accepted. The seam rotates right after the rows are read: the reply is the heartbeat alone (new epoch), the
    /// reader polls again. Probe: ignore the epoch read before the rows → the row is served under the new epoch → red.
    @Test
    void events_whenTheEpochRotatesBetweenTheRowsAndTheHeartbeat_answersTheHeartbeatAlone() {
        var real = store.fleet();
        var rotating = new AtomicBoolean();
        var rotatingAfterRead = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                                    new Class<?>[] {FleetStore.class},
                                                                    (_, method, args) -> method.getName().equals("pending") && rotating.get()
                                                                                         ? invoked(real, method, args).flatMap(rows -> real.renewEpoch().map(_ -> rows))
                                                                                         : method.invoke(real, args));
        var raced = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("rotating-read")), List.of(), rotatingAfterRead, ProcessRunner.processRunner(), none(), now::get);
        raced.settle().await(Pg.WAIT).unwrap();
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               raced.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none(), () -> true),
                               Responses.responses(TemperJson.mapper()));
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");
        var before = real.head().await(Pg.WAIT).unwrap().epoch();
        rotating.set(true);

        var lines = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1", "")).lines().toList();

        var after = real.head().await(Pg.WAIT).unwrap().epoch();
        assertThat(after).as("the seam rotated").isNotEqualTo(before);
        assertThat(lines).as("the heartbeat alone").singleElement()
                         .satisfies(line -> assertThat(line).contains("\"kind\":\"heartbeat\"").contains("\"epoch\":\"" + after + "\""));
    }

    /// The statement moved nothing although the epoch matches and the seq is AT the head: the subscriber was gone by
    /// the write (the seam acks another name), so the answer is 404 `NO_SUBSCRIBER`, never 400 `ACK_AHEAD` (PIT: the
    /// boundary of the head check in `FleetRoutes.refusal`).
    @Test
    void ack_whenTheSubscriberIsGoneAtTheWrite_is404_notAckAhead() {
        var real = store.fleet();
        var vanishing = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                            new Class<?>[] {FleetStore.class},
                                                            (_, method, args) -> method.getName().equals("ack")
                                                                                 ? real.ack("gone", (String) args[1], (Long) args[2])
                                                                                 : method.invoke(real, args));
        var raced = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("vanishing")), List.of(), vanishing, ProcessRunner.processRunner(), none(), now::get);
        raced.settle().await(Pg.WAIT).unwrap();
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               raced.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none(), () -> true),
                               Responses.responses(TemperJson.mapper()));
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");

        var acked = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(1));

        assertThat(acked.status().code()).as(text(acked)).isEqualTo(404);
        assertThat(text(acked)).contains("NO_SUBSCRIBER");
    }

    @SuppressWarnings("unchecked")
    private static Promise<Object> invoked(FleetStore real, Method method, Object[] args) {
        try {
            return (Promise<Object>) method.invoke(real, args);
        } catch (ReflectiveOperationException e) {
            return Causes.fromThrowable(e).promise();
        }
    }

    /// R2-f: the subscriber is bound to the token that created it; another token (here the read token) is refused.
    @Test
    void ack_withAnotherToken_is403_andWithItsOwn_movesTheCursor() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");

        var foreign = call(READ, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(1));
        assertThat(foreign.status().code()).isEqualTo(403);
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).isZero();
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1", "").status().code()).isEqualTo(403);

        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(1)).status().code()).isEqualTo(204);
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).isEqualTo(1);
    }

    /// Lead ruling F2: an ack names the epoch its line was read in; one read in another epoch (a restore between the read
    /// and the ack) is 409 `EPOCH_MISMATCH` and the cursor stays, even for a seq the current epoch has; an ack without an
    /// epoch is 400. Probe: compare seqs only → 204, the cursor moves → red.
    @Test
    void ack_readInAnotherEpoch_is409_andTheCursorStays_andOneWithoutAnEpochIs400() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");
        append("host-answering", "cloudbb-3");

        var foreign = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack",
                           "{\"epoch\":\"" + UUID.randomUUID() + "\",\"seq\":1}");
        var bare = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", "{\"seq\":1}");

        assertThat(foreign.status().code()).isEqualTo(409);
        assertThat(text(foreign)).contains("EPOCH_MISMATCH");
        assertThat(bare.status().code()).isEqualTo(400);
        assertThat(text(bare)).contains("epoch required");
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).as("the cursor stays").isZero();
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(2)).status().code()).isEqualTo(204);
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).isEqualTo(2);
    }

    @Test
    void events_deliversAfterTheCursor_thenTheHeartbeatAsTheLastLine() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");
        append("host-unreachable", "bigboy");

        var reply = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=1", ""));
        var lines = reply.lines().toList();

        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("\"seq\":1").contains("\"kind\":\"host-answering\"");
        assertThat(lines.getLast()).startsWith("{\"v\":1,\"kind\":\"heartbeat\"").contains("\"seqHead\":2").contains("\"hostlink:bigboy\"");
        assertThat(text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&format=text", "")).lines().toList())
            .hasSize(3)
            .last()
            .satisfies(line -> assertThat(line).startsWith("{\"v\":1,\"kind\":\"heartbeat\""));
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=nobody", "").status().code()).isEqualTo(404);
        assertThat(text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=nobody", ""))).contains("NO_SUBSCRIBER");
    }

    @Test
    void after_otherEpoch_is409WithTheCurrentEpoch_andAMatchIsFound() {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        append("host-answering", "cloudbb-3");
        append("host-unreachable", "bigboy");

        var changed = call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=00000000-0000-0000-0000-000000000000:0", "");
        assertThat(changed.status().code()).isEqualTo(409);
        assertThat(text(changed)).contains("EPOCH_CHANGED").contains(epoch);
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":99", "").status().code())
            .as("R3-4: a seq above the head in the same epoch").isEqualTo(409);

        var found = text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&until=host-unreachable,host-answering&match=host=bigboy", ""));
        assertThat(found.lines().toList()).hasSize(2).first().satisfies(line -> assertThat(line).contains("\"seq\":2"));
        var none = text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":2&until=host-unreachable", ""));
        assertThat(none.lines().toList()).singleElement().satisfies(line -> assertThat(line).contains("\"kind\":\"heartbeat\""));
    }

    /// F13: kiln answers (the pull succeeds every time) but its github poller froze: `kiln:github` goes stale by age,
    /// announced once, while `kiln` itself stays fresh.
    @Test
    void frozenGithubLastOkAt_isAnnouncedAsSourceStale_whileThePullStaysFresh() {
        var frozen = now.get().minusSeconds(600);
        KilnClient kiln = (_, _) -> Promise.success(new KilnReply.Changes("E1",
                                                                          1,
                                                                          0,
                                                                          Map.of("github", new KilnReply.SourceInfo(some(frozen), 60)),
                                                                          List.of()));

        fleet.pull(kiln).await(Pg.WAIT).unwrap();
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();
        fleet.pull(kiln).await(Pg.WAIT).unwrap();
        fleet.staleness(now.get()).await(Pg.WAIT).unwrap();

        var stale = rows().stream().filter(row -> row.kind().equals("source-stale")).map(FleetEventRow::subject).toList();
        assertThat(stale).contains("kiln:github").doesNotContain("kiln").containsOnlyOnce("kiln:github");
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""))).contains("\"kiln:github\":{\"lastOkAt\":\"" + frozen + "\",\"periodS\":60,\"stale\":true}");
    }

    /// R3-4: a dump restored from earlier in the SAME epoch: the high-water file is ahead of the database, so the
    /// controller takes a new epoch by itself and announces it. Simulated by a file whose seq is above the head.
    @Test
    void settle_fileAheadOfTheDatabase_takesANewEpoch_andAnnouncesIt() throws Exception {
        var before = store.fleet().head().await(Pg.WAIT).unwrap();
        append("host-answering", "bigboy");
        Files.writeString(state.resolve("fleet-hwm"), before.epoch() + " 50\n", StandardCharsets.US_ASCII);

        var epoch = fleet().settle().await(Pg.WAIT).unwrap();

        assertThat(epoch).isNotEqualTo(before.epoch());
        assertThat(rows()).last().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("epoch-changed");
            assertThat(row.epoch()).isEqualTo(epoch);
            assertThat(row.payload()).contains("restore");
        });
        assertThat(Files.readString(state.resolve("fleet-hwm")).trim()).startsWith(epoch + " ");
        assertThat(fleet().settle().await(Pg.WAIT).unwrap()).as("a second start changes nothing").isEqualTo(epoch);
    }

    /// Lead ruling 11-03: a WRITE caller may bind the subscriber to the configured read token; the reader then
    /// polls and acks it, and the creator, holding another token, is refused. `caller` is the default.
    @Test
    void create_bindToRead_isPolledAndAckedByTheReadTokenOnly() {
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"r1\",\"start\":\"seq:0\",\"bindTo\":\"read\"}").status().code()).isEqualTo(201);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"c1\",\"start\":\"seq:0\",\"bindTo\":\"caller\"}").status().code()).isEqualTo(201);
        append("host-answering", "bigboy");

        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events?subscriber=r1", ""))).contains("host-answering");
        assertThat(call(READ, HttpMethod.POST, "/api/v1/fleet/subscribers/r1/ack", ackBody(1)).status().code()).isEqualTo(204);
        assertThat(store.fleet().subscriber("r1").await(Pg.WAIT).unwrap().unwrap().cursor()).isEqualTo(1);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=r1", "").status().code()).isEqualTo(403);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/r1/ack", ackBody(1)).status().code()).isEqualTo(403);
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events?subscriber=c1", "").status().code()).as("caller: bound to the full token").isEqualTo(403);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=c1", "").status().code()).isEqualTo(200);
        assertThat(call(READ, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"r2\",\"bindTo\":\"read\"}").status().code())
            .as("creating stays WRITE").isEqualTo(403);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"r3\",\"bindTo\":\"other\"}").status().code()).isEqualTo(400);

        router = router(none());
        var unconfigured = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"r4\",\"bindTo\":\"read\"}");
        assertThat(unconfigured.status().code()).isEqualTo(400);
        assertThat(text(unconfigured)).contains("no read token");
        assertThat(store.fleet().subscriber("r4").await(Pg.WAIT).unwrap()).isEqualTo(none());
    }

    /// Restore (a): a real `pg_restore --clean` of an earlier dump of the SAME epoch under the running
    /// controller, no restart. The next insert's seq is below the mark read before it (a seq used twice), so the epoch
    /// rotates before the mark could be rewritten lower.
    @Test
    void restoreUnderARunningController_theNextInsertRotatesTheEpoch() throws Exception {
        var before = restoredUnderTheController();

        append("host-unreachable", "bigboy");

        var after = store.fleet().head().await(Pg.WAIT).unwrap();
        assertThat(after.epoch()).isNotEqualTo(before);
        assertThat(rows()).last().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("epoch-changed");
            assertThat(row.epoch()).isEqualTo(after.epoch());
            assertThat(row.payload()).contains("\"reason\": \"restore\"").contains("\"detail\": \"running\"").contains(before);
        });
        assertThat(Files.readString(state.resolve("fleet-hwm"))).isEqualTo(after.epoch() + " " + after.seq() + "\n");
        assertThat(fleet().settle().await(Pg.WAIT).unwrap()).as("a restart after it changes nothing").isEqualTo(after.epoch());
    }

    /// Restore (a): the same restore with no insert after it: the minute's check finds it.
    @Test
    void restoreUnderARunningController_theMinuteCheckRotatesTheEpoch() throws Exception {
        var before = restoredUnderTheController();
        var links = HostLinks.hostLinks(List.of(), store.jobs());
        now.set(now.get().plusSeconds(30));

        fleet.minute(links).await(Pg.WAIT).unwrap();

        var after = store.fleet().head().await(Pg.WAIT).unwrap();
        assertThat(after.epoch()).isNotEqualTo(before);
        assertThat(source(Fleet.HIGHWATER).lastOkAt()).as("the rotating check wrote the file").isEqualTo(some(now.get()));
        assertThat(rows()).anySatisfy(row -> {
            assertThat(row.kind()).isEqualTo("epoch-changed");
            assertThat(row.payload()).contains("\"detail\": \"running\"");
        });
        now.set(now.get().plusSeconds(60));
        fleet.minute(links).await(Pg.WAIT).unwrap();
        assertThat(store.fleet().head().await(Pg.WAIT).unwrap().epoch()).as("rotated once").isEqualTo(after.epoch());
        assertThat(source(Fleet.HIGHWATER).lastOkAt()).as("the file written after the rotation").isEqualTo(some(now.get()));
    }

    /// A mark behind the database (a reply that overtook a later one, or a crash between commit and rename) is no
    /// restore: the check moves the mark up to the database and keeps the epoch.
    @Test
    void highWaterCheck_markBehindTheDatabase_keepsTheEpoch_andCatchesUp() throws Exception {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        assertThat(fleet.writer()
                        .append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}")))
                        .await(Pg.WAIT)
                        .unwrap()).as("the writer answers the seq inserted").isEqualTo(some(1L));
        append("host-unreachable", "bigboy");
        fleet.writer().highWater().force(new HighWater.Mark(epoch, 1));

        fleet.highWater().await(Pg.WAIT).unwrap();

        assertThat(store.fleet().head().await(Pg.WAIT).unwrap().epoch()).isEqualTo(epoch);
        assertThat(Files.readString(state.resolve("fleet-hwm"))).isEqualTo(epoch + " 2\n");
        assertThat(rows()).noneMatch(row -> row.kind().equals("epoch-changed"));
    }

    /// Restore (b): no file on a database whose stream was written is an unknown history: a new epoch. On a
    /// fresh database (this test's start) no file is the first start, and nothing is announced.
    @Test
    void settle_noFileButAWrittenStream_takesANewEpoch() throws Exception {
        assertThat(rows()).as("first start on a fresh database").isEmpty();
        var before = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        append("host-answering", "bigboy");
        Files.delete(state.resolve("fleet-hwm"));

        var epoch = fleet().settle().await(Pg.WAIT).unwrap();

        assertThat(epoch).isNotEqualTo(before);
        assertThat(rows()).last().satisfies(row -> assertThat(row.payload()).contains("\"detail\": \"no-file\""));
    }

    /// Restore (c): a high-water file that cannot be written never refuses start: the epoch rotates, the
    /// controller runs, and `selfcheck:highwater` never records a success, so it is announced stale. A writable file
    /// is that source's success.
    @Test
    void settle_unwritableHighWater_rotatesAndContinues_andTheSourceGoesStale() throws Exception {
        assertThat(source(Fleet.HIGHWATER).lastOkAt()).as("the writable start").isEqualTo(some(now.get()));
        var before = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        var blocked = state.resolve("blocked");
        Files.writeString(blocked, "a file where the state directory should be");
        var unwritable = fleet(blocked.resolve("state"));

        var epoch = unwritable.settle().await(Pg.WAIT).unwrap();

        assertThat(epoch).isNotEqualTo(before);
        assertThat(rows()).last().satisfies(row -> assertThat(row.payload()).contains("\"detail\": \"unwritable\""));
        now.set(now.get().plusSeconds(600));
        unwritable.highWater().await(Pg.WAIT).unwrap();
        unwritable.staleness(now.get()).await(Pg.WAIT).unwrap();
        assertThat(source(Fleet.HIGHWATER).lastOkAt()).as("no write since the first start").isEqualTo(some(now.get().minusSeconds(600)));
        assertThat(rows()).anySatisfy(row -> {
            assertThat(row.kind()).isEqualTo("source-stale");
            assertThat(row.subject()).isEqualTo(Fleet.HIGHWATER);
        });
        assertThat(store.fleet().head().await(Pg.WAIT).unwrap().epoch()).as("running on: no rotation per minute").isEqualTo(epoch);

        unwritable.writer().highWater().force(new HighWater.Mark(epoch, 99));
        unwritable.highWater().await(Pg.WAIT).unwrap();
        assertThat(store.fleet().head().await(Pg.WAIT).unwrap().epoch()).as("the mark in memory still detects").isNotEqualTo(epoch);
        assertThat(source(Fleet.HIGHWATER).lastOkAt()).as("a rotation whose file write failed is no success")
                                                     .isEqualTo(some(now.get().minusSeconds(600)));
    }

    /// C4.2 (FIX-6): no optimistic initial ok, and no window serving one that is not a verdict: until the controller's
    /// start has completed (the first self-check included), every fleet route is 503 NOT_READY; after it, the heartbeat
    /// carries the check's verdict (here the loopback probe meets a closed port), and the result is announced.
    @Test
    void gated_beforeStart_everyFleetRouteIs503NotReady_andAfterItTheVerdictServes() throws Exception {
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               fleet.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none()),
                               Responses.responses(TemperJson.mapper()));
        for (var path : List.of("/api/v1/fleet/heartbeat", "/api/v1/fleet/heartbeat/history", "/api/v1/fleet/events?subscriber=d1",
                                "/api/v1/fleet/events/after?after=x:0&until=k")) {
            var early = call(READ, HttpMethod.GET, path, "");
            assertThat(early.status().code()).as(path).isEqualTo(503);
            assertThat(text(early)).as(path).contains("NOT_READY").doesNotContain("selfCheck");
        }
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\"}").status().code()).isEqualTo(503);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/reset", "{}").status().code()).isEqualTo(503);
        assertThat(call(READ, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/ack", ackBody(0)).status().code()).isEqualTo(503);
        int closed;
        try (var socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }

        fleet.start(closed).await(Pg.WAIT).unwrap();

        var heartbeat = call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", "");
        assertThat(heartbeat.status().code()).isEqualTo(200);
        assertThat(text(heartbeat)).contains("\"loopback\"").doesNotContain("\"ok\":false,\"failed\":[]");
        assertThat(rows()).anySatisfy(row -> assertThat(row.kind()).isEqualTo("self-check-failed"));
    }

    /// R2-b (verifier round 2): before the first self-check completes, no heartbeat says ok=false with nothing failed
    /// (ok is false ONLY for ssh-banner or loopback: a waiter seeing ok=false exits 6).
    @Test
    void r2b_beforeTheFirstSelfCheck_heartbeatIsNotAFailure() {
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               fleet.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none()),
                               Responses.responses(TemperJson.mapper()));
        var heartbeat = text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""));

        assertThat(heartbeat).as(heartbeat).doesNotContain("\"selfCheck\":{\"ok\":false,\"failed\":[]");
    }

    /// R2-a (verifier round 2, FIX-5): a restore under the running controller that lost exactly ONE row (the last),
    /// then one insert: that insert gets the SAME seq as the mark (a seq used twice). Neither `seq < mark` nor the
    /// minute's `mark > head` sees it; `seq <= mark` (the mark read before the insert) does, and the epoch rotates.
    @Test
    void r2a_restoreLosingOneRow_thenOneInsert_rotatesTheEpoch() throws Exception {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        append("host-answering", "bigboy");
        append("host-unreachable", "bigboy");
        var dump = "/tmp/" + database + "-r2a.dump";
        docker("pg_dump", "-U", "temper", "-Fc", "-d", database, "-f", dump);
        append("host-answering", "bigboy");
        assertThat(fleet.writer().highWater().mark()).isEqualTo(new HighWater.Mark(epoch, 3));
        docker("pg_restore", "-U", "temper", "--clean", "--if-exists", "-d", database, dump);
        assertThat(store.fleet().head().await(Pg.WAIT).unwrap().reached()).isEqualTo(2);

        append("host-unreachable", "bigboy");
        var links = HostLinks.hostLinks(List.of(), store.jobs());
        now.set(now.get().plusSeconds(30));
        fleet.minute(links).await(Pg.WAIT).unwrap();

        var after = store.fleet().head().await(Pg.WAIT).unwrap();
        assertThat(after.epoch()).as("seq 3 now names a different event").isNotEqualTo(epoch);
        assertThat(rows()).anySatisfy(row -> assertThat(row.payload()).contains("\"detail\": \"running\"").contains(epoch));
    }

    /// NOTE-7: a dump taken while an insert was in flight records the sequence above its rows (the insert had its seq,
    /// not yet committed). That insert commits after the dump and the mark reaches it; a restore of the dump then
    /// leaves the head below the mark but the sequence at it. The minute's check compares the mark with the head
    /// alone, so it rotates; compared with the sequence too, the restore would pass unseen.
    @Test
    void restoreOfADumpTakenWithAnInsertInFlight_theMinuteCheckRotatesTheEpoch() throws Exception {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        var links = HostLinks.hostLinks(List.of(), store.jobs());
        append("host-answering", "bigboy");
        append("host-unreachable", "bigboy");
        psql("SELECT nextval('fleet_events_seq_seq')");
        var dump = "/tmp/" + database + "-inflight.dump";
        docker("pg_dump", "-U", "temper", "-Fc", "-d", database, "-f", dump);
        psql("INSERT INTO fleet_events (seq, epoch, observed_at, source, kind, subject, payload) "
             + "SELECT 3, id, now(), 'hostlink', 'host-answering', 'bigboy', '{}'::jsonb FROM temper_epoch");
        fleet.highWater().await(Pg.WAIT).unwrap();
        assertThat(fleet.writer().highWater().mark()).as("the in-flight insert reached the mark").isEqualTo(new HighWater.Mark(epoch, 3));

        docker("pg_restore", "-U", "temper", "--clean", "--if-exists", "-d", database, dump);
        var restored = store.fleet().head().await(Pg.WAIT).unwrap();
        assertThat(restored.seq()).as("the dump's rows").isEqualTo(2);
        assertThat(restored.sequence()).as("the dump's sequence, the in-flight seq").isEqualTo(3);
        now.set(now.get().plusSeconds(60));
        fleet.minute(links).await(Pg.WAIT).unwrap();

        assertThat(store.fleet().head().await(Pg.WAIT).unwrap().epoch()).isNotEqualTo(epoch);
        assertThat(rows()).anySatisfy(row -> assertThat(row.payload()).contains("\"detail\": \"running\"").contains(epoch));
    }

    /// NOTE-8: an insert's restore check is never skipped because another check is running. A minute check is held
    /// inside its database read; an insert that reuses a seq (the mark stands above the database, as a restore leaves
    /// it) waits for that check, then rotates the epoch, and only then answers.
    @Test
    void aReusedSeqWhileACheckIsRunning_waitsForIt_thenRotates() throws Exception {
        var real = store.fleet();
        var release = Promise.<Unit> promise();
        var hold = new AtomicBoolean();
        var held = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                       new Class<?>[] {FleetStore.class},
                                                       (_, method, args) -> method.getName().equals("head") && hold.getAndSet(false)
                                                                            ? release.flatMap(_ -> real.head())
                                                                            : method.invoke(real, args));
        var raced = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("race")), List.of(), held, ProcessRunner.processRunner(), none(), now::get);
        raced.settle().await(Pg.WAIT).unwrap();
        var epoch = real.head().await(Pg.WAIT).unwrap().epoch();
        raced.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}"))).await(Pg.WAIT).unwrap();
        hold.set(true);
        var check = raced.highWater();
        raced.writer().highWater().force(new HighWater.Mark(epoch, 10));

        var insert = raced.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-unreachable", "bigboy", "{}")));

        Thread.sleep(500);
        assertThat(insert.isResolved()).as("the insert waits for the running check").isFalse();
        release.succeed(Unit.unit());
        check.await(Pg.WAIT).unwrap();
        assertThat(insert.await(Pg.WAIT).unwrap()).isEqualTo(some(2L));
        assertThat(real.head().await(Pg.WAIT).unwrap().epoch()).isNotEqualTo(epoch);
        assertThat(rows()).last().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("epoch-changed");
            assertThat(row.payload()).contains("\"detail\": \"running\"");
        });
    }

    /// FER of the checks: a database that does not answer the head read degrades them, never an insert. The minute's
    /// check is answered as not written (no `selfcheck:highwater` success); an insert that reused a seq still answers
    /// its seq while its rotation fails (logged), and the epoch stays.
    @Test
    void aFailingHeadRead_theChecksDegrade_andTheInsertStands() {
        var real = store.fleet();
        var failing = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                          new Class<?>[] {FleetStore.class},
                                                          (_, method, args) -> method.getName().equals("head")
                                                                               ? Causes.cause("injected").promise()
                                                                               : method.invoke(real, args));
        var degraded = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("degraded")), List.of(), failing, ProcessRunner.processRunner(), none(), now::get);
        var epoch = real.head().await(Pg.WAIT).unwrap().epoch();
        var started = now.get();
        now.set(started.plusSeconds(600));

        degraded.highWater().await(Pg.WAIT).unwrap();

        assertThat(source(Fleet.HIGHWATER).lastOkAt()).as("a failed check is no success").isEqualTo(some(started));
        degraded.writer().highWater().force(new HighWater.Mark(epoch, 10));
        assertThat(degraded.writer()
                           .append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}")))
                           .await(Pg.WAIT)
                           .unwrap()).isEqualTo(some(1L));
        assertThat(real.head().await(Pg.WAIT).unwrap().epoch()).isEqualTo(epoch);
    }

    /// The first self-check at start is also the one the minute runs again (the check kept by start).
    @Test
    void start_thenTheMinute_runsTheSelfCheckAgain() throws Exception {
        var processes = ProcessRunner.processRunner();
        var keyscans = new AtomicInteger();
        var counting = (ProcessRunner) Proxy.newProxyInstance(ProcessRunner.class.getClassLoader(),
                                                              new Class<?>[] {ProcessRunner.class},
                                                              (_, method, args) -> {
                                                                  if (method.getName().equals("run")) {
                                                                      keyscans.incrementAndGet();
                                                                  }
                                                                  return method.invoke(processes, args);
                                                              });
        var checked = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(), store.fleet(), counting, none(), now::get);
        checked.settle().await(Pg.WAIT).unwrap();
        int closed;
        try (var socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }

        checked.start(closed).await(Pg.WAIT).unwrap();
        checked.minute(HostLinks.hostLinks(List.of(), store.jobs())).await(Pg.WAIT).unwrap();

        assertThat(keyscans.get()).as("ssh-keyscan at start and at the minute").isEqualTo(2);
    }

    /// C4.2 ruling (round 3): a reset to a start above the head is refused like the create: 400 START_AHEAD, the cursor
    /// unchanged; at the head it moves.
    @Test
    void reset_startAboveTheHead_is400StartAhead_andTheCursorStays() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");

        var ahead = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/reset", "{\"start\":\"seq:2\"}");

        assertThat(ahead.status().code()).isEqualTo(400);
        assertThat(text(ahead)).contains("START_AHEAD");
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).isZero();
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/reset", "{\"start\":\"seq:1\"}").status().code()).isEqualTo(200);
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap().unwrap().cursor()).isEqualTo(1);
    }

    /// Round 3 ruling: a rotation that fails after the insert committed keeps its proof as a pending rotation. The
    /// reused seq here EQUALS the mark, so after the insert the head equals the mark and the mark-vs-head check alone
    /// would never rotate: only the pending proof does. While pending, `selfcheck:highwater` reads stale; the next
    /// tick retries the rotation, and the source clears.
    @Test
    void aFailedRotation_staysPending_theSourceReadsStale_andTheNextTickRotates() {
        var real = store.fleet();
        var failOnce = new AtomicBoolean();
        var flaky = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                        new Class<?>[] {FleetStore.class},
                                                        (_, method, args) -> method.getName().equals("renewEpoch") && failOnce.getAndSet(false)
                                                                             ? Causes.cause("injected").promise()
                                                                             : method.invoke(real, args));
        var pendingFleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("pending")), List.of(), flaky, ProcessRunner.processRunner(), none(), now::get);
        pendingFleet.settle().await(Pg.WAIT).unwrap();
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               pendingFleet.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none(), () -> true),
                               Responses.responses(TemperJson.mapper()));
        var epoch = real.head().await(Pg.WAIT).unwrap().epoch();
        pendingFleet.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}"))).await(Pg.WAIT).unwrap();
        pendingFleet.writer().highWater().force(new HighWater.Mark(epoch, 2));
        failOnce.set(true);

        assertThat(pendingFleet.writer()
                               .append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-unreachable", "bigboy", "{}")))
                               .await(Pg.WAIT)
                               .unwrap()).as("the insert stands").isEqualTo(some(2L));
        assertThat(real.head().await(Pg.WAIT).unwrap().epoch()).as("the rotation failed").isEqualTo(epoch);
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""))).as("pending: stale")
                                                                              .containsPattern("\"selfcheck:highwater\":\\{[^}]*\"stale\":true");

        now.set(now.get().plusSeconds(30));
        pendingFleet.minute(HostLinks.hostLinks(List.of(), store.jobs())).await(Pg.WAIT).unwrap();

        assertThat(real.head().await(Pg.WAIT).unwrap().epoch()).as("the next tick rotated").isNotEqualTo(epoch);
        assertThat(rows()).anySatisfy(row -> assertThat(row.payload()).contains("\"detail\": \"running\"").contains(epoch));
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""))).as("cleared")
                                                                              .containsPattern("\"selfcheck:highwater\":\\{[^}]*\"stale\":false");
    }

    /// n1 (verifier): one insert whose seqs span the mark (first <= mark < last) reused the mark's seq: the FIRST seq
    /// decides, so the epoch rotates (the last alone would pass, and the mark-vs-head check sees nothing after it).
    @Test
    void anInsertSpanningTheMark_rotatesTheEpoch() {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        append("host-answering", "bigboy");
        fleet.writer().highWater().force(new HighWater.Mark(epoch, 2));

        assertThat(fleet.writer()
                        .append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-unreachable", "bigboy", "{}"),
                                        NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}")))
                        .await(Pg.WAIT)
                        .unwrap()).as("seqs 2 and 3").isEqualTo(some(3L));

        assertThat(store.fleet().head().await(Pg.WAIT).unwrap().epoch()).isNotEqualTo(epoch);
        assertThat(rows()).last().satisfies(row -> assertThat(row.payload()).contains("\"detail\": \"running\"").contains(epoch));
    }

    /// n3 (verifier): two rotations queued for the same evidence (two inserts reusing seqs while a check runs): the
    /// first rotates, the second finds the epoch changed and is skipped. Exactly one epoch-changed.
    @Test
    void twoRotationsQueuedForTheSameEvidence_rotateOnce() throws Exception {
        var real = store.fleet();
        var release = Promise.<Unit> promise();
        var hold = new AtomicBoolean();
        var held = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                       new Class<?>[] {FleetStore.class},
                                                       (_, method, args) -> method.getName().equals("head") && hold.getAndSet(false)
                                                                            ? release.flatMap(_ -> real.head())
                                                                            : method.invoke(real, args));
        var raced = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("twice")), List.of(), held, ProcessRunner.processRunner(), none(), now::get);
        raced.settle().await(Pg.WAIT).unwrap();
        var epoch = real.head().await(Pg.WAIT).unwrap().epoch();
        raced.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}"))).await(Pg.WAIT).unwrap();
        hold.set(true);
        var check = raced.highWater();
        raced.writer().highWater().force(new HighWater.Mark(epoch, 10));

        var first = raced.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-unreachable", "bigboy", "{}")));
        var second = raced.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}")));
        Thread.sleep(500);
        release.succeed(Unit.unit());
        check.await(Pg.WAIT).unwrap();
        first.await(Pg.WAIT).unwrap();
        second.await(Pg.WAIT).unwrap();

        assertThat(real.head().await(Pg.WAIT).unwrap().epoch()).isNotEqualTo(epoch);
        assertThat(rows()).filteredOn(row -> row.kind().equals("epoch-changed")).as("one rotation").hasSize(1);
    }

    /// n4 (verifier): `started` is set only once the first self-check has completed: while it is in flight (here its
    /// ssh-keyscan is held), every fleet route is 503 NOT_READY; released, they serve.
    @Test
    void startInFlight_theFleetRoutesAre503_untilTheFirstCheckCompletes() throws Exception {
        var processes = ProcessRunner.processRunner();
        var release = Promise.<Unit> promise();
        var blocked = (ProcessRunner) Proxy.newProxyInstance(ProcessRunner.class.getClassLoader(),
                                                             new Class<?>[] {ProcessRunner.class},
                                                             (_, method, args) -> method.getName().equals("run")
                                                                                  ? release.flatMap(_ -> processes.run((Command) args[0]))
                                                                                  : method.invoke(processes, args));
        var starting = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(BIGBOY), store.fleet(), blocked, none(), now::get);
        starting.settle().await(Pg.WAIT).unwrap();
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               starting.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none()),
                               Responses.responses(TemperJson.mapper()));
        int closed;
        try (var socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }

        var started = starting.start(closed);
        Thread.sleep(300);

        assertThat(started.isResolved()).as("the first check is held").isFalse();
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", "").status().code()).isEqualTo(503);
        release.succeed(Unit.unit());
        started.await(Pg.WAIT).unwrap();
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", "").status().code()).isEqualTo(200);
    }

    /// PIT (linksUp): a host whose link stays UP is refreshed every minute, so past 3 x period it is not stale.
    @Test
    void aHostUpPastThreePeriods_isNotStale() {
        for (int minute = 1; minute <= 4; minute++) {
            now.set(now.get().plusSeconds(60));
            fleet.minute(() -> List.of(BIGBOY)).await(Pg.WAIT).unwrap();
        }

        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""))).containsPattern("\"hostlink:bigboy\":\\{[^}]*\"stale\":false");
        assertThat(rows()).noneMatch(row -> row.kind().equals("source-stale") && row.subject().equals("hostlink:bigboy"));
    }

    /// PIT (start$1): a database failing during the first self-check (its announcement and its success write) does
    /// not stop the start: it completes, the routes serve, and `selfcheck` reads stale (degraded: no success recorded).
    @Test
    void startWithTheDatabaseFailingDuringTheFirstCheck_completes_andSelfcheckReadsStale() throws Exception {
        var real = store.fleet();
        var failing = new AtomicBoolean();
        var flaky = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                        new Class<?>[] {FleetStore.class},
                                                        (_, method, args) -> failing.get() && (method.getName().equals("append") || method.getName().equals("seen"))
                                                                             ? Causes.cause("injected").promise()
                                                                             : method.invoke(real, args));
        var degraded = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(BIGBOY), flaky, ProcessRunner.processRunner(), none(), now::get);
        degraded.settle().await(Pg.WAIT).unwrap();
        router = Router.router(Credentials.credentials(BearerToken.bearerToken(FULL).unwrap(), some(BearerToken.bearerToken(READ).unwrap())),
                               degraded.routes(Responses.responses(TemperJson.mapper()), TemperJson.mapper(), none()),
                               Responses.responses(TemperJson.mapper()));
        int closed;
        try (var socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        failing.set(true);

        assertThat(degraded.start(closed).await(Pg.WAIT).unwrap()).as("the start completes").isEqualTo(Unit.unit());

        var heartbeat = call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", "");
        assertThat(heartbeat.status().code()).isEqualTo(200);
        assertThat(text(heartbeat)).containsPattern("\"selfcheck\":\\{[^}]*\"stale\":true");
    }

    /// d2 (verifier): a pending rotation is retried BEFORE the next insert, not only by the minute: with a pending
    /// proof and no minute in between, an append rotates the epoch first, and the appended row carries the new epoch.
    @Test
    void aPendingRotation_isRetriedBeforeTheNextAppend_andTheRowCarriesTheNewEpoch() {
        var real = store.fleet();
        var failOnce = new AtomicBoolean();
        var flaky = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                        new Class<?>[] {FleetStore.class},
                                                        (_, method, args) -> method.getName().equals("renewEpoch") && failOnce.getAndSet(false)
                                                                             ? Causes.cause("injected").promise()
                                                                             : method.invoke(real, args));
        var pendingFleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state.resolve("retry")), List.of(), flaky, ProcessRunner.processRunner(), none(), now::get);
        pendingFleet.settle().await(Pg.WAIT).unwrap();
        var epoch = real.head().await(Pg.WAIT).unwrap().epoch();
        pendingFleet.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "bigboy", "{}"))).await(Pg.WAIT).unwrap();
        pendingFleet.writer().highWater().force(new HighWater.Mark(epoch, 2));
        failOnce.set(true);
        pendingFleet.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-unreachable", "bigboy", "{}"))).await(Pg.WAIT).unwrap();
        assertThat(pendingFleet.writer().rotationPending()).as("the rotation failed: pending").isTrue();
        assertThat(real.head().await(Pg.WAIT).unwrap().epoch()).isEqualTo(epoch);

        pendingFleet.writer().append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "after", "{}"))).await(Pg.WAIT).unwrap();

        var rotated = real.head().await(Pg.WAIT).unwrap().epoch();
        assertThat(rotated).as("rotated before the append").isNotEqualTo(epoch);
        assertThat(pendingFleet.writer().rotationPending()).isFalse();
        assertThat(rows()).last().satisfies(row -> {
            assertThat(row.subject()).isEqualTo("after");
            assertThat(row.epoch()).as("the appended row carries the new epoch").isEqualTo(rotated);
        });
    }

    /// PIT (upHosts): a configured host whose link was never started is not UP, so `minute(links)` records no
    /// `hostlink:<h>` success for it: a dead host never reads fresh.
    @Test
    void minute_aLinkNeverStarted_recordsNoHostlinkSuccess() {
        var config = ControllerConfig.Host.host(BIGBOY, "127.0.0.1", "builder", Path.of("/k"), Path.of("/kh"), 5, 20480, false);
        var env = LinkEnv.linkEnv(null, TemperJson.mapper(), store.units(), store.jobs(), store.hosts(), Wake.wake(), "0.1.0", "git@example:repo.git", 1024,
                                  none(), none(), Duration.ofSeconds(30), Duration.ofSeconds(600), null, LinkTiming.DEFAULT,
                                  OwnerTag.ownerTag("temper").unwrap(), InstanceId.DEFAULT, null);
        var links = HostLinks.hostLinks(List.of(HostLink.hostLink(config, env)), store.jobs());
        now.set(now.get().plusSeconds(60));

        fleet.minute(links).await(Pg.WAIT).unwrap();

        assertThat(source("hostlink:bigboy").lastOkAt()).as("never UP: no success").isEqualTo(none());
    }

    /// C4.2 ruling: a subscriber start above the head would skip the seqs written up to it: 400 START_AHEAD, nothing
    /// created; at the head it is created.
    @Test
    void create_startAboveTheHead_is400StartAhead_andAtTheHeadIsCreated() {
        append("host-answering", "bigboy");

        var ahead = call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:2\"}");

        assertThat(ahead.status().code()).isEqualTo(400);
        assertThat(text(ahead)).contains("START_AHEAD");
        assertThat(store.fleet().subscriber("d1").await(Pg.WAIT).unwrap()).isEqualTo(none());
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:1\"}").status().code()).isEqualTo(201);
    }

    /// NOTE-3: a failed minute step (here the staleness read) never skips the later ones: the heartbeat tick is
    /// recorded.
    @Test
    void minute_aFailedStalenessStep_stillRecordsTheTick() {
        var real = store.fleet();
        var failing = (FleetStore) Proxy.newProxyInstance(FleetStore.class.getClassLoader(),
                                                          new Class<?>[] {FleetStore.class},
                                                          (_, method, args) -> method.getName().equals("sources")
                                                                               ? Causes.cause("injected").promise()
                                                                               : method.invoke(real, args));
        var minuteFleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(), failing, ProcessRunner.processRunner(), none(), now::get);

        minuteFleet.minute(HostLinks.hostLinks(List.of(), store.jobs())).await(Pg.WAIT).unwrap();

        assertThat(real.ticks(now.get().minusSeconds(60)).await(Pg.WAIT).unwrap()).isNotEmpty();
    }

    /// With kiln configured, its source row exists from the start, never seen (so it reads stale, not absent).
    @Test
    void settle_withKiln_expectsTheKilnSource() {
        KilnClient kiln = (_, _) -> Promise.success(new KilnReply.Changes("E1", 0, 0, Map.of(), List.of()));
        Fleet.fleet(ControllerConfig.Fleet.fleet(none(), state), List.of(), store.fleet(), ProcessRunner.processRunner(), some(kiln), now::get)
             .settle()
             .await(Pg.WAIT)
             .unwrap();

        assertThat(source("kiln").lastOkAt()).isEqualTo(none());
    }

    /// Every parameter branch of the routes: bounds at both ends, numbers, positions, pairs, flags.
    @Test
    void routes_parameterBranches() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();

        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=500&wait=0", "").status().code()).isEqualTo(200);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=1&wait=55", "").status().code()).isEqualTo(200);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=501", "").status().code()).isEqualTo(400);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&wait=-1", "").status().code()).isEqualTo(400);
        assertThat(text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=abc", ""))).contains("Invalid max: abc");
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=:1", "").status().code()).as("an empty epoch").isEqualTo(400);
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&match==bigboy", "").status().code())
            .as("an empty key").isEqualTo(400);
        for (var flag : List.of("1", "true")) {
            assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&includeCatchUp=" + flag, "")).lines().toList())
                .hasSize(2);
        }
        for (var flag : List.of("0", "false")) {
            assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&includeCatchUp=" + flag, "")).lines().toList())
                .hasSize(2);
        }
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&match=nokey=", "")).lines().toList())
            .as("a key absent from the payload matches nothing, not the empty value").hasSize(1);
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&match=subject=bigboy", "")).lines().toList())
            .hasSize(2);
    }

    /// Catch-up rows are skipped unless asked; live rows always match.
    @Test
    void after_catchUpRows_onlyWhenAsked_liveRowsAlways() {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        fleet.writer()
             .append(List.of(NewFleetEvent.kilnRow(now.get(), "K", 1, "pr-state", "pr:1", "{}", true),
                             NewFleetEvent.kilnRow(now.get(), "K", 2, "pr-state", "pr:2", "{}", false)))
             .await(Pg.WAIT)
             .unwrap();

        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0", ""))).contains("pr:2").doesNotContain("pr:1");
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&includeCatchUp=1", ""))).contains("pr:1");
    }

    /// The scan pages through `MAX` rows at a time: a match on the row after a full page is still found.
    @Test
    void after_matchAfterAFullPage_isFound() {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        var filler = IntStream.range(0, 500)
                                               .mapToObj(i -> NewFleetEvent.newFleetEvent(now.get(), "hostlink", "host-answering", "h" + i, "{}"))
                                               .toList();
        fleet.writer().append(filler).await(Pg.WAIT).unwrap();
        append("host-unreachable", "bigboy");

        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&until=host-unreachable", "")))
            .contains("\"seq\":501");
    }

    /// A wait answers at once when something is there (both routes), and a stateless wait with nothing there wakes
    /// on a later match.
    @Test
    void waits_answerAtOnceWhenSomethingIsThere_andWakeOnALaterMatch() throws Exception {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");

        var started = System.nanoTime();
        assertThat(text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&wait=20", ""))).contains("host-answering");
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&wait=20", ""))).contains("host-answering");
        assertThat(System.nanoTime() - started).as("no wait when something is there").isLessThan(10_000_000_000L);
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":1&wait=1", "")).lines().toList())
            .as("nothing there, the wait ends: the heartbeat alone").hasSize(1);

        var unmatched = router.handle(ApiRequest.apiRequest(request(HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":1&until=host-unreachable&wait=3", "",
                                                                    Map.of("Authorization", "Bearer " + READ))));
        var waitStarted = System.nanoTime();
        Thread.sleep(300);
        append("host-answering", "cloudbb-3");
        assertThat(text(unmatched.await(Pg.WAIT).unwrap()).lines().toList()).as("C4.2: a wake without a match waits on").hasSize(1);
        assertThat(System.nanoTime() - waitStarted).as("until the wait expires").isGreaterThan(2_500_000_000L);

        var reply = router.handle(ApiRequest.apiRequest(request(HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":2&until=host-unreachable&wait=20", "",
                                                                Map.of("Authorization", "Bearer " + READ))));
        Thread.sleep(300);
        append("host-unreachable", "bigboy");
        assertThat(text(reply.await(Pg.WAIT).unwrap())).contains("host-unreachable");
    }

    /// A subscriber below the retention floor gets one `gap` line, carrying the current epoch.
    @Test
    void events_belowTheFloor_isAGapLineInTheCurrentEpoch() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");
        append("host-unreachable", "bigboy");
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        store.fleet().prune(Instant.now().plusSeconds(86_400)).await(Pg.WAIT).unwrap();

        var lines = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1", "")).lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.getFirst()).contains("\"kind\":\"gap\"").contains("\"seq\":2").contains(epoch);
    }

    @Test
    void linkSignal_upAndDown_areHostEvents_andUpIsHostlinkSuccess() {
        fleet.signal().up().apply(BIGBOY);
        awaitRows(1);
        fleet.signal().down().apply(BIGBOY);
        awaitRows(2);

        assertThat(rows()).extracting(FleetEventRow::kind).containsExactly("host-answering", "host-unreachable");
        assertThat(store.fleet().source("hostlink:bigboy").await(Pg.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(some(now.get()));
    }

    /// L0 transitions: a failed check is announced once, a recovery once, and only a passed check is `selfcheck`'s
    /// success.
    @Test
    void checked_announcesTransitionsOnly_andAPassIsSelfcheckSuccess() {
        var advisory = HeartbeatLine.Advisory.advisory(some(1.0), 4, none());
        var failed = HeartbeatLine.SelfCheck.selfCheck(false, List.of("ssh-banner"), advisory);
        var passed = HeartbeatLine.SelfCheck.selfCheck(true, List.of(), advisory);

        fleet.checked(failed, now.get()).await(Pg.WAIT).unwrap();
        fleet.checked(failed, now.get()).await(Pg.WAIT).unwrap();
        assertThat(store.fleet().source("selfcheck").await(Pg.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(none());
        fleet.checked(passed, now.get()).await(Pg.WAIT).unwrap();
        fleet.checked(passed, now.get()).await(Pg.WAIT).unwrap();
        fleet.checked(passed, now.get()).await(Pg.WAIT).unwrap();

        assertThat(rows()).extracting(FleetEventRow::kind).containsExactly("self-check-failed", "self-check-ok");
        var payload = TemperJson.mapper().readTree(rows().getFirst().payload()).unwrap();

        assertThat(payload.path("reason").stringValue()).isEqualTo("ssh-banner");
        assertThat(payload.path("cores").intValue()).isEqualTo(4);
        assertThat(store.fleet().source("selfcheck").await(Pg.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(some(now.get()));
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""))).contains("\"selfCheck\":{\"ok\":true");
    }

    @Test
    void pull_rowsReachTheStream_andKilnLagIsHeadMinusCursor() {
        var change = new KilnReply.Change(4, "PR_STATE", some("o/r"), some(7L), "o/r#7", none(), none(), now.get(), none(), TemperJson.mapper().readTree("{}").unwrap());
        KilnClient kiln = (_, _) -> Promise.success(new KilnReply.Changes("E1", 1, 10, Map.of(), List.of(change)));

        fleet.pull(kiln).await(Pg.WAIT).unwrap();

        assertThat(rows()).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo("pr-state");
            assertThat(row.catchUp()).isTrue();
        });
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat", ""))).endsWith("\"kilnLag\":6}");
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&until=pr-state", "")).lines().toList()).hasSize(1);
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=" + epoch + ":0&until=pr-state&includeCatchUp=1&match=pr=7", "")).lines().toList())
            .as("catch-up rows only when asked").hasSize(2);
    }

    @Test
    void reset_history_andParameterBounds() {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"seq:0\"}");
        append("host-answering", "bigboy");

        assertThat(text(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/reset", "{\"start\":\"head\"}"))).contains("\"cursor\":1");
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/nobody/reset", "{}").status().code()).isEqualTo(404);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/reset", "{\"start\":\"tail\"}").status().code()).isEqualTo(400);
        assertThat(call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers/d1/reset", "{\"start\":\"seq:-1\"}").status().code()).isEqualTo(400);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&max=0", "").status().code()).isEqualTo(400);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&wait=56", "").status().code()).isEqualTo(400);
        assertThat(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&format=xml", "").status().code()).isEqualTo(400);
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=nonsense", "").status().code()).isEqualTo(400);
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=E:1&includeCatchUp=2", "").status().code()).isEqualTo(400);
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/events/after?after=E:1&match=novalue", "").status().code()).isEqualTo(400);

        store.fleet().tick(now.get(), now.get().minusSeconds(60)).await(Pg.WAIT).unwrap();
        assertThat(text(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat/history?since=2026-11-03T00:00:00Z", "")))
            .isEqualTo("{\"ticks\":[{\"at\":\"2026-11-03T12:00:00Z\"}]}");
        assertThat(call(READ, HttpMethod.GET, "/api/v1/fleet/heartbeat/history?since=yesterday", "").status().code()).isEqualTo(400);
    }

    /// The long-poll waits for the wake an insert gives, then answers what it brought.
    @Test
    void events_longPoll_wakesOnAnInsert() throws Exception {
        call(FULL, HttpMethod.POST, "/api/v1/fleet/subscribers", "{\"name\":\"d1\",\"start\":\"head\"}");
        var reply = router.handle(ApiRequest.apiRequest(request(HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&wait=20", "",
                                                                Map.of("Authorization", "Bearer " + FULL))));
        Thread.sleep(300);
        var started = System.nanoTime();
        append("host-answering", "bigboy");
        var lines = text(reply.await(Pg.WAIT).unwrap()).lines().toList();

        assertThat(lines).hasSize(2).first().satisfies(line -> assertThat(line).contains("host-answering"));
        assertThat(System.nanoTime() - started).as("woken, not timed out").isLessThan(10_000_000_000L);
        var empty = text(call(FULL, HttpMethod.GET, "/api/v1/fleet/events?subscriber=d1&wait=1", "")).lines().toList();
        assertThat(empty).as("unacked: the same event again").hasSize(2);
    }

    private Fleet fleet() {
        return fleet(state);
    }

    private Fleet fleet(Path stateDir) {
        return Fleet.fleet(ControllerConfig.Fleet.fleet(none(), stateDir), List.of(BIGBOY), store.fleet(), ProcessRunner.processRunner(), none(), now::get);
    }

    private FleetSourceRow source(String name) {
        return store.fleet().source(name).await(Pg.WAIT).unwrap().unwrap();
    }

    /// Two events, a real `pg_dump` (epoch E, seq 2), three more events (the mark at 5), then a real
    /// `pg_restore --clean` of the dump while this test's controller (its store's pool, its fleet) keeps running.
    /// Answers E.
    private String restoredUnderTheController() throws Exception {
        var epoch = store.fleet().head().await(Pg.WAIT).unwrap().epoch();
        append("host-answering", "bigboy");
        append("host-unreachable", "bigboy");
        var dump = "/tmp/" + database + ".dump";
        docker("pg_dump", "-U", "temper", "-Fc", "-d", database, "-f", dump);
        append("host-answering", "bigboy");
        append("host-unreachable", "bigboy");
        append("host-answering", "bigboy");
        assertThat(fleet.writer().highWater().mark()).isEqualTo(new HighWater.Mark(epoch, 5));

        docker("pg_restore", "-U", "temper", "--clean", "--if-exists", "-d", database, dump);

        var restored = store.fleet().head().await(Pg.WAIT).unwrap();
        assertThat(restored.epoch()).isEqualTo(epoch);
        assertThat(restored.reached()).as("the dump's head").isEqualTo(2);
        return epoch;
    }

    private void psql(String sql) throws Exception {
        docker("psql", "-U", "temper", "-d", database, "-v", "ON_ERROR_STOP=1", "-c", sql);
    }

    private static void docker(String... command) throws Exception {
        var argv = new ArrayList<>(List.of("docker", "exec", Pg.containerName()));
        argv.addAll(List.of(command));
        var process = HermeticProcess.builder(argv).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes());

        assertThat(process.waitFor()).as(output).isZero();
    }

    private void append(String kind, String host) {
        fleet.writer()
             .append(List.of(NewFleetEvent.newFleetEvent(now.get(), "hostlink", kind, host, "{\"host\":\"" + host + "\"}")))
             .await(Pg.WAIT)
             .unwrap();
    }

    private List<FleetEventRow> rows() {
        return switch (store.fleet().pending(0, 100).await(Pg.WAIT).unwrap()) {
            case FleetPending.Events events -> events.rows();
            case FleetPending.Gap gap -> throw new AssertionError("gap " + gap);
        };
    }

    private void awaitRows(int count) {
        var deadline = System.nanoTime() + 10_000_000_000L;
        while (rows().size() < count && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private ApiResponse call(String token, HttpMethod method, String pathAndQuery, String body) {
        return router.handle(ApiRequest.apiRequest(request(method, pathAndQuery, body, Map.of("Authorization", "Bearer " + token))))
                     .await(Pg.WAIT)
                     .unwrap();
    }

    private static String text(ApiResponse response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static HttpRequest request(HttpMethod method, String pathAndQuery, String body, Map<String, String> headers) {
        var parts = pathAndQuery.split("\\?", 2);
        var query = parts.length > 1
                    ? QueryParams.queryParams(Arrays.stream(parts[1].split("&"))
                                                    .map(pair -> pair.split("=", 2))
                                                    .collect(Collectors.toMap(pair -> pair[0], pair -> List.of(pair.length > 1 ? pair[1] : ""))))
                    : QueryParams.empty();
        return new HttpRequest() {
            @Override
            public String requestId() {
                return "test";
            }

            @Override
            public HttpMethod method() {
                return method;
            }

            @Override
            public String path() {
                return parts[0];
            }

            @Override
            public Headers headers() {
                return Headers.fromSingleValueMap(headers);
            }

            @Override
            public QueryParams queryParams() {
                return query;
            }

            @Override
            public byte[] body() {
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
    }

    /// An ack body in the current epoch (lead ruling F2: an ack names the epoch its line was read in).
    private String ackBody(long seq) {
        return "{\"epoch\":\"" + store.fleet().head().await(Pg.WAIT).unwrap().epoch() + "\",\"seq\":" + seq + "}";
    }
}
