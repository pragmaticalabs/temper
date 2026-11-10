package org.pragmatica.temper.acceptance.stack;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.source.Source;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.api.ApiRoutes;
import org.pragmatica.kiln.service.github.Credential;
import org.pragmatica.kiln.service.github.GitHubBudget;
import org.pragmatica.kiln.service.github.GitHubPoller;
import org.pragmatica.kiln.service.github.GitHubWatch;
import org.pragmatica.kiln.service.http.LoopbackServer;
import org.pragmatica.kiln.service.http.Router;
import org.pragmatica.kiln.service.mirror.MirrorStep;
import org.pragmatica.kiln.service.scan.MacIngest;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPostgres;
import org.pragmatica.kiln.service.temper.TemperReader;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The real kiln-service's change feed for [FleetEventsIT] (lead ruling F4): kiln's own store
/// (`KilnPostgres.connect`: its migrations, its epoch and its high-water file) in an empty database of the acceptance
/// Postgres, and kiln's own routes ([ApiRoutes], [LoopbackServer]) on a free loopback port. No loop runs (no GitHub,
/// mirror, temper or host reads): the test writes what kiln's GitHub poller would, through the same store calls
/// (`prs().upsert`, `sources().record`), so every byte of `/api/v1/changes` the controller reads is kiln's.
final class RealKiln implements AutoCloseable {
    static final String REPO = "o/r";

    private static final Periods PERIODS = Periods.periods(GitHubPoller.PERIOD,
                                                           MirrorStep.PERIOD,
                                                           TemperReader.PERIOD,
                                                           Duration.ofMinutes(5));

    /// No GitHub sample and no drift read yet: what kiln serves before its first poll.
    private static final GitHubBudget NO_BUDGET = new GitHubBudget(none(),
                                                                   Credential.Mode.TOKEN,
                                                                   new GitHubWatch.Drift(none(),
                                                                                         List.of(),
                                                                                         none(),
                                                                                         none(),
                                                                                         none(),
                                                                                         0));

    private static final BranchName MAIN = BranchName.branchName("main").expect("kiln setup");
    private static final BranchName HEAD_REF = BranchName.branchName("feature").expect("kiln setup");
    private static final Sha HEAD = Sha.sha("a".repeat(40)).expect("kiln setup");

    private final KilnStore store;
    private final LoopbackServer server;
    private final String database;

    private RealKiln(KilnStore store, LoopbackServer server, String database) {
        this.store = store;
        this.server = server;
        this.database = database;
    }

    static RealKiln start(Path highWater) {
        var db = Pg.emptyDatabase();
        var store = KilnPostgres.connect(DbConfig.dbConfig(db.host(),
                                                           db.port(),
                                                           db.database(),
                                                           db.user(),
                                                           db.password(),
                                                           4)
                                                 .expect("kiln setup"),
                                         REPO,
                                         highWater)
                                .await(Pg.WAIT)
                                .expect("kiln setup");
        var ingest = MacIngest.macIngest(store.checkouts(),
                                         store.sources(),
                                         List.of(),
                                         Instant::now,
                                         () -> {});
        var routes = ApiRoutes.apiRoutes(store,
                                         ingest,
                                         List.of(MAIN),
                                         List.of(),
                                         Option::none,
                                         List::of,
                                         () -> PERIODS,
                                         PERIODS,
                                         Instant::now,
                                         () -> NO_BUDGET);
        var server = LoopbackServer.start(0,
                                          Router.router(routes.routes())).expect("kiln setup");

        return new RealKiln(store, server, db.database());
    }

    URI url() {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    /// kiln's own database, for `Pg.psql`.
    String database() {
        return database;
    }

    /// The GitHub source's state as the poller records a good cycle: `lastOkAt` now.
    void githubOk() {
        store.sources()
             .record(Source.GITHUB,
                     new SourceState.Ok(Instant.now()))
             .await(Pg.WAIT)
             .expect("kiln github source");
    }

    /// One PR upserted as the poller does after a read; a state change records kiln's `PR_STATE` row.
    void pr(long number, PrState state) {
        var now = Instant.now();
        var pr = new PullRequest(PrNumber.prNumber(number).expect("kiln pr"),
                                 "pr " + number,
                                 state,
                                 false,
                                 MAIN,
                                 HEAD_REF,
                                 some(REPO),
                                 HEAD,
                                 state == PrState.MERGED
                                 ? some(now)
                                 : none(),
                                 state == PrState.OPEN
                                 ? none()
                                 : some(now),
                                 now,
                                 List.of(),
                                 List.of(),
                                 List.of());

        store.prs().upsert(List.of(pr), now).await(Pg.WAIT).expect("kiln upsert");
    }

    @Override
    public void close() {
        server.stop().await(Pg.WAIT);
        store.close().await(Pg.WAIT);
    }
}
