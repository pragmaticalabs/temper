package org.pragmatica.temper.acceptance.stack;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.pragmatica.kiln.core.checkout.Ancestry;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.service.Kiln;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.kiln.service.mirror.MirrorGit;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.temper.TemperHttp;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.vo.Url;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.github.GitHubPoller;
import org.pragmatica.kiln.service.github.JdkGitHubHttp;
import org.pragmatica.kiln.service.http.LoopbackServer;
import org.pragmatica.kiln.service.http.Router;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPostgres;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;

/// The real kiln-service for the merge gate: kiln's own store in an empty database of the
/// acceptance Postgres, its own `GitHubPoller` (with the change feed) and `PrFresh` over one [JdkGitHubHttp] pointed
/// at a [GitHubStub], and its own routes (`/api/v1/changes`, `/api/v1/pr-fresh`) on a free loopback port. The poller is
/// ticked by the test (one cycle, so kiln's `base_tip` is what a poll recorded); no other loop runs.
final class GateKiln implements AutoCloseable {
    static final String REPO = "o/r";
    private static final BranchName MAIN = BranchName.branchName("main").expect("kiln setup");

    private final KilnStore store;
    private final GitHubPoller poller;
    private final LoopbackServer server;
    private boolean serving = true;

    private GateKiln(KilnStore store, GitHubPoller poller, LoopbackServer server) {
        this.store = store;
        this.poller = poller;
        this.server = server;
    }

    static GateKiln start(Path highWater, GitHubStub github) {
        var db = Pg.emptyDatabase();
        var store = KilnPostgres.connect(DbConfig.dbConfig(db.host(), db.port(), db.database(), db.user(), db.password(), 4).expect("kiln setup"),
                                         REPO,
                                         highWater)
                                .await(Pg.WAIT)
                                .expect("kiln setup");
        // Everything after connect is guarded: a throw before the GateKiln exists would otherwise leak the store's pool,
        // since tearDown only sees a null kiln.
        return closingOnFailure(store::close, () -> {
            var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), github.api(), some("stub-token"), Duration.ofSeconds(10));
            // Kiln's own assembly (Kiln.assemble): the poller with its change feed, the ingest, and (through routes() and
            // fresh()) the API routes and PrFresh, over the stub-pointed GitHub port. Only the ports the gate never reaches
            // (temper, the mirror, host transports) are inert.
            var kiln = Kiln.assemble(config(), store, Kiln.Ports.ports(http, new InertTemper(), new InertMirror(), host -> {
                                         throw new IllegalStateException("no hosts are configured");
                                     }, Instant::now))
                           .expect("kiln setup");
            var routes = Stream.concat(kiln.routes().routes().stream(), Stream.of(kiln.fresh().route())).toList();
            // Mirrors the private Kiln.served(ApiRoutes) (Kiln.java, "The API's routes and the merge gate's fresh read"),
            // which Kiln.start does not expose; the guard below fails if the routes the gate needs are not in the set.
            var served = routes.stream().map(route -> route.method() + " " + route.path()).toList();
            for (var needed : List.of("GET /api/v1/changes", "GET /api/v1/pr-fresh")) {
                if (!served.contains(needed)) {
                    throw new IllegalStateException("kiln no longer registers " + needed + "; registered: " + served);
                }
            }
            var server = LoopbackServer.start(0, Router.router(routes)).expect("kiln setup");
            return new GateKiln(store, kiln.github(), server);
        });
    }

    /// Runs `build`; should it throw, `close` (the store's pool) still runs, and a failed close is attached to the
    /// throw as a suppressed exception rather than replacing it.
    static <T> T closingOnFailure(Supplier<Promise<Unit>> close, Supplier<T> build) {
        try {
            return build.get();
        } catch (RuntimeException e) {
            close.get().await(Pg.WAIT).onFailure(cause -> e.addSuppressed(new IllegalStateException("store close failed: " + cause.message())));
            throw e;
        }
    }

    private static KilnConfig config() {
        return KilnConfig.kilnConfig(KilnConfig.Server.server(0),
                                     KilnConfig.Database.database("unused", 0, "unused", "unused", Path.of("unused"), Path.of("unused")),
                                     KilnConfig.GitHub.gitHub(REPO, List.of(MAIN), Option.none(), GitHubPoller.DEADLINE),
                                     KilnConfig.Mirror.mirror("unused", Path.of("unused")),
                                     KilnConfig.Temper.temper(Url.url("http://127.0.0.1:1").expect("kiln setup"), Path.of("unused"), Duration.ofSeconds(10)),
                                     KilnConfig.Scan.scan(Duration.ofMinutes(5), Duration.ofHours(1), LocalTime.NOON, Duration.ZERO, Duration.ofDays(1), Duration.ofSeconds(60), Duration.ofSeconds(10)),
                                     KilnConfig.Mac.mac(List.of()),
                                     List.of());
    }

    private static final class InertTemper implements TemperHttp {
        @Override
        public Promise<String> digestText() {
            return Causes.cause("gate test: temper is not read").promise();
        }

        @Override

        public Promise<org.pragmatica.kiln.core.temper.HostsRead> fleetAlarms() {

            return Causes.cause("gate test: temper is not read").promise();

        }


        @Override
        public Promise<GateRead> gate(Sha head) {
            return Causes.cause("gate test: temper is not read").promise();
        }
    }

    private static final class InertMirror implements MirrorGit {
        @Override
        public Promise<Unit> fetch() {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<BehindFacts> behind(Sha head, BranchName base) {
            return Promise.success(new BehindFacts(Option.none(), Option.none()));
        }

        @Override
        public Promise<Map<Sha, Boolean>> reachable(Set<Sha> shas) {
            return Promise.success(Map.of());
        }

        @Override
        public Promise<Ancestry> ancestry(Sha head, List<BranchName> bases) {
            return Promise.success(new Ancestry(false, false, Set.of(), Set.of()));
        }
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    /// One GitHub poll now: records the base tip (and the sources) as the poller does.
    void poll() {
        poller.tick().await(Pg.WAIT).expect("kiln poll");
    }

    /// Kiln stops answering: its listener is closed (the controller's next read is refused).
    synchronized void stop() {
        if (serving) {
            serving = false;
            server.stop().await(Pg.WAIT);
        }
    }

    @Override
    public synchronized void close() {
        try {
            stop();
        } finally {
            store.close().await(Pg.WAIT);
        }
    }
}
