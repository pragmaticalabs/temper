package org.pragmatica.kiln.service;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.github.GhAnswer;
import org.pragmatica.kiln.service.github.PrFresh;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.mirror.MirrorGit;
import org.pragmatica.kiln.service.scan.ScanTransport;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.temper.TemperHttp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/// [Kiln#start] and [Kiln.Running#stop] without I/O beyond the loopback socket (WP9): a started kiln serves on its port
/// and runs every loop and daily run; a stopped one stops them all and refuses connections. A GitHub tick that ran a
/// cycle is followed by a mirror step, and one that was not due is not (§3.1); the budget the sources view shows is the
/// one GitHub's last answer stated (open item 2); retention prunes the changes recorded over 30 days ago.
class KilnStartTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(20).seconds();
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Instant RESET = NOW.plusSeconds(600);

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger fetches = new AtomicInteger();

    /// Every store part answers an empty value of its promised type, and records the call.
    private KilnStore quiet() {
        return (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                  new Class<?>[]{KilnStore.class},
                                                  (_, method, _) -> method.getName()
                                                                          .equals("close")
                                                                    ? Promise.unitPromise()
                                                                    : Proxy.newProxyInstance(KilnStore.class.getClassLoader(),
                                                                                             new Class<?>[]{method.getReturnType()},
                                                                                             (_, part, args) -> {
                                                                                                 calls.add(part.getName() + (args == null
                                                                                                                             ? List.of()
                                                                                                                             : List.of(args)));

                                                                                                 return part.getName()
                                                                                                            .equals("watermark")
                                                                                                        ? Instant.EPOCH
                                                                                                        : Promise.success(empty(((ParameterizedType) part.getGenericReturnType()).getActualTypeArguments() [0]));
                                                                                             }));
    }

    private static Object empty(java.lang.reflect.Type type) {
        var raw = type instanceof ParameterizedType parameterized
                  ? parameterized.getRawType()
                  : type;

        return raw == List.class
               ? List.of()
               : raw == Option.class
                 ? none()
                 : raw == Map.class
                   ? Map.of()
                   : Unit.unit();
    }

    /// GitHub answering 500 with its rate headers, temper and every host failing, the mirror fetching (counted).
    static Kiln.Ports failingPorts() {
        return ports(new AtomicInteger(), Instant::now);
    }

    private static Kiln.Ports ports(AtomicInteger fetches, Supplier<Instant> clock) {
        return Kiln.Ports.ports((_, _) -> Promise.success(GhAnswer.ghAnswer(500,
                                                                            none(),
                                                                            "{}",
                                                                            some(4321),
                                                                            some(5000),
                                                                            some(RESET),
                                                                            none(),
                                                                            none())),
                                new TemperHttp() {
            @Override
            public Promise<String> digestText() {
                                    return Causes.cause("temper down").promise();
                                }

            @Override
            public Promise<org.pragmatica.kiln.core.temper.HostsRead> fleetAlarms() {
                                    return Causes.cause("temper down").promise();
                                }

            @Override
            public Promise<GateRead> gate(Sha head) {
                                    return Causes.cause("temper down").promise();
                                }
        },
                                new MirrorGit() {
            @Override
            public Promise<Unit> fetch() {
                                    fetches.incrementAndGet();

                                    return Promise.unitPromise();
                                }

            @Override
            public Promise<BehindFacts> behind(Sha head, BranchName base) {
                                    return Causes.cause("no mirror").promise();
                                }

            @Override
            public Promise<Map<Sha, Boolean>> reachable(Set<Sha> shas) {
                                    return Causes.cause("no mirror").promise();
                                }

            @Override
            public Promise<Ancestry> ancestry(Sha head, List<BranchName> bases) {
                                    return Causes.cause("no mirror").promise();
                                }
        },
                                _ -> new ScanTransport() {
            @Override
            public Promise<List<String>> scan(List<String> roots, List<CheckoutPath> full) {
                                    return Causes.cause("ssh exit 255").promise();
                                }

            @Override
            public Promise<List<String>> reach(Set<Sha> shas) {
                                    return Causes.cause("ssh exit 255").promise();
                                }

            @Override
            public Promise<List<String>> size(String root, CheckoutPath path) {
                                    return Causes.cause("ssh exit 255").promise();
                                }
        },
                                clock);
    }

    private Kiln kiln(Supplier<Instant> clock) {
        var config = KilnWiringTest.config(List.of(KilnWiringTest.host(HostName.hostName("bigboy").unwrap())));
        var onPortZero = KilnConfig.kilnConfig(KilnConfig.Server.server(0),
                                               config.db(),
                                               config.github(),
                                               config.mirror(),
                                               config.temper(),
                                               config.scan(),
                                               config.mac(),
                                               config.hosts());

        return Kiln.assemble(onPortZero, quiet(), ports(fetches, clock)).unwrap();
    }

    @Test
    void stopThenStartAgain_servesAndRunsTheLoops() throws Exception {
        var kiln = kiln(Instant::now);
        var first = kiln.start().await(WAIT).unwrap();
        var firstPort = first.port();

        first.stop().await(WAIT).unwrap();
        var second = kiln.start().await(WAIT).unwrap();

        try {
            assertThat(second.loops()).hasSize(8).allMatch(loop -> loop.running());
            assertThat(second.daily()).allMatch(daily -> daily.scheduled());
            assertThat(HttpClient.newHttpClient()
                                 .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + second.port() + "/nope")).build(),
                                       HttpResponse.BodyHandlers.ofString())
                                 .statusCode()).isEqualTo(404);
        } finally {
            second.stop().await(WAIT);
        }
    }

    @Test
    void start_servesAndRunsEveryLoop_stop_stopsThemAll() throws Exception {
        var running = kiln(Instant::now).start().await(WAIT).unwrap();
        var port = running.port();

        assertThat(port).isPositive();
        assertThat(HttpClient.newHttpClient()
                             .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/nope")).build(),
                                   HttpResponse.BodyHandlers.ofString())
                             .statusCode()).isEqualTo(404);
        assertThat(running.loops()).hasSize(8).allMatch(loop -> loop.running());
        assertThat(running.daily()).hasSize(1).allMatch(daily -> daily.scheduled());
        assertThat(running.routes().expected()).hasSize(6);
        running.stop().await(WAIT).unwrap();
        assertThat(running.loops()).noneMatch(loop -> loop.running());
        assertThat(running.daily()).noneMatch(daily -> daily.scheduled());
        assertThatThrownBy(() -> new Socket().connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                                                      2_000)).isInstanceOf(ConnectException.class);
    }

    @Test
    void aGitHubCycle_isFollowedByAMirrorStep_aTickNotDueIsNot_andTheBudgetIsGitHubs() {
        var kiln = kiln(() -> NOW);

        assertThat(kiln.budget().isEmpty()).isTrue();
        kiln.gitHubThenMirror().await(WAIT).unwrap();
        assertThat(fetches).hasValue(1);
        kiln.gitHubThenMirror().await(WAIT).unwrap();
        assertThat(fetches).hasValue(1);
        var budget = kiln.budget().unwrap();

        assertThat(budget.value().remaining()).isEqualTo(4321);
        assertThat(budget.value().hourlyCap()).isEqualTo(1572);
        assertThat(budget.value().reserve()).isEqualTo(1500);
        assertThat(budget.value().kilnSpent()).isPositive();
        assertThat(budget.value().reset()).isEqualTo(RESET);
        assertThat(budget.observedAt()).isEqualTo(NOW);
    }

    /// The served router mounts `GET /api/v1/pr-fresh`: a refused `sha` is the route's 400, never
    /// the router's 404 for an unknown path.
    @Test
    void start_servesPrFresh_onTheRouter() throws Exception {
        var running = kiln(Instant::now).start().await(WAIT).unwrap();

        try {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + running.port() + PrFresh.PATH
                                                                                            + "?repo=o/r&pr=2&sha=abc")).build(),
                                                           HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("sha");
        } finally {
            running.stop().await(WAIT);
        }
    }

    @Test
    void retention_prunesWhatWasRecorded30DaysAgo() {
        kiln(() -> NOW).pruned().await(WAIT).unwrap();
        assertThat(calls).contains("prune[" + NOW.minus(Duration.ofDays(30)) + "]");
    }
}
