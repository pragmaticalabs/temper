package org.pragmatica.kiln.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.service.api.ApiRoutes;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.conflict.ConflictBoard;
import org.pragmatica.kiln.service.conflict.ConflictWatch;
import org.pragmatica.kiln.service.conflict.EventsLog;
import org.pragmatica.kiln.service.conflict.GitConflicts;
import org.pragmatica.kiln.service.derive.DeriveJob;
import org.pragmatica.kiln.service.github.BranchHeads;
import org.pragmatica.kiln.service.github.GitHubFeed;
import org.pragmatica.kiln.service.github.GitHubHttp;
import org.pragmatica.kiln.service.github.PrFresh;
import org.pragmatica.kiln.service.github.GitHubPoller;
import org.pragmatica.kiln.service.github.GitHubReads;
import org.pragmatica.kiln.service.github.RateBudget;
import org.pragmatica.kiln.service.http.LoopbackServer;
import org.pragmatica.kiln.service.http.Route;
import org.pragmatica.kiln.service.http.Router;
import org.pragmatica.kiln.service.loop.Daily;
import org.pragmatica.kiln.service.loop.Periodic;
import org.pragmatica.kiln.service.mirror.MirrorGit;
import org.pragmatica.kiln.service.mirror.MirrorStep;
import org.pragmatica.kiln.service.pipeline.PipelineReader;
import org.pragmatica.kiln.service.pipeline.PipelineScope;
import org.pragmatica.kiln.service.scan.HostScanner;
import org.pragmatica.kiln.service.scan.MacIngest;
import org.pragmatica.kiln.service.scan.ScanHost;
import org.pragmatica.kiln.service.scan.ScanTransport;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.temper.TemperHttp;
import org.pragmatica.kiln.service.temper.HostsReader;
import org.pragmatica.kiln.service.temper.TemperReader;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.pragmatica.lang.Option.some;


/// kiln assembled (kiln-f1-design §1.2, based on temper's `Controller.java:80-218`): every part is passed in, built by
/// [#assemble] from the config, the store and the [Ports]. [#start] serves the routes on the loopback address, then
/// starts the loops (§3.1):
///
/// | Loop | Period |
/// |---|---|
/// | GitHub poller (paces itself, Q-7), then the mirror step after each cycle that ran | [GitHubPoller#PERIOD] |
/// | mirror step | [MirrorStep#PERIOD] |
/// | temper reader | [TemperReader#PERIOD] |
/// | hosts reader (fleet alarms) | [HostsReader#PERIOD] |
/// | pipeline baseline (#102), through the GitHub poller's own rate budget | [PipelineScope#PERIOD] |
/// | per host: hot pass, size | `[scan] hotSeconds`, `sizeGapSeconds` |
/// | per host: full pass | daily at `[scan] fullAt` |
/// | retention: changes recorded over 30 days ago | daily |
///
/// The store's preconditions, honoured here (WP9 open item 11): one writer of check cells per PR (only the one
/// [GitHubPoller], single-flight, writes cells); one pass per host at a time (one [ScanHost] per host, its serial shared by
/// that host's scanner and the derivation's `reach`); the derivation triggered after each pass, each mirror fetch that
/// succeeded and each PR upsert ([UpsertTrigger]).
public record Kiln(KilnConfig config,
                   KilnStore store,
                   GitHubPoller github,
                   TemperReader temper,
                   HostsReader fleet,
                   PipelineReader pipeline,
                   MirrorStep mirror,
                   List<HostScanner> scanners,
                   MacIngest ingest,
                   DeriveJob derive,
                   Option<ConflictWatch> conflicts,
                   Supplier<Instant> clock) {
    /// Changes recorded longer ago than this are pruned (spec §7).
    public static final Duration RETENTION = Duration.ofDays(30);
    /// The pipeline baseline's first cycle waits for the poller's first, which sizes the shared budget.
    public static final Duration PIPELINE_DELAY = Duration.ofMinutes(2);

    public Kiln {
        scanners = List.copyOf(scanners);
    }

    /// The I/O kiln's loops run over: GitHub, temper, the mirror, each host's transport, and the clock.
    /// `heads` lists each repository's branches for the change feed (CI interface T1 WP2); [BranchHeads#NONE] without
    /// one (only `main` and the configured bases are then tracked).
    public record Ports(GitHubHttp github,
                        TemperHttp temper,
                        MirrorGit mirror,
                        Fn1<ScanTransport, KilnConfig.Host> transports,
                        Supplier<Instant> clock,
                        BranchHeads heads) {
        public static Ports ports(GitHubHttp github,
                                  TemperHttp temper,
                                  MirrorGit mirror,
                                  Fn1<ScanTransport, KilnConfig.Host> transports,
                                  Supplier<Instant> clock) {
            return ports(github, temper, mirror, transports, clock, BranchHeads.NONE);
        }

        public static Ports ports(GitHubHttp github,
                                  TemperHttp temper,
                                  MirrorGit mirror,
                                  Fn1<ScanTransport, KilnConfig.Host> transports,
                                  Supplier<Instant> clock,
                                  BranchHeads heads) {
            return new Ports(github, temper, mirror, transports, clock, heads);
        }
    }

    /// A started kiln: its server and its loops.
    public record Running(LoopbackServer server, ApiRoutes routes, List<Periodic> loops, List<Daily> daily) {
        public Running {
            loops = List.copyOf(loops);
            daily = List.copyOf(daily);
        }

        public int port() {
            return server.port();
        }

        /// Stops the loops (a run in flight ends on its own), then the server. The store stays open: its owner closes it.
        public Promise<Unit> stop() {
            loops.forEach(Periodic::stop);
            daily.forEach(Daily::stop);

            return server.stop();
        }
    }

    /// Builds every part from the config: one [ScanHost] per configured host, shared by its [HostScanner] and the
    /// [DeriveJob]; the deadlines and timeouts from the config (WP9 open item 7); the Mac's roots from `[mac] roots`
    /// (open item 8).
    public static Result<Kiln> assemble(KilnConfig config, KilnStore store, Ports ports) {
        var clock = ports.clock();
        var hosts = config.hosts()
                          .stream()
                          .map(host -> ScanHost.scanHost(host.name(),
                                                         host.roots(),
                                                         ports.transports().apply(host)))
                          .toList();
        var derive = DeriveJob.deriveJob(store.checkouts(),
                                         store.prs(),
                                         ports.mirror(),
                                         hosts,
                                         config.github().repo(),
                                         config.github().bases(),
                                         config.scan().staleAfter(),
                                         config.scan().stepDeadline(),
                                         DeriveJob.DEADLINE,
                                         clock);
        var scanners = hosts.stream()
                            .map(host -> HostScanner.hostScanner(host,
                                                                 store.checkouts(),
                                                                 store.sources(),
                                                                 clock,
                                                                 config.scan().hotWindow(),
                                                                 config.scan().stepDeadline(),
                                                                 HostScanner.FULL_DEADLINE,
                                                                 derive::trigger))
                            .toList();
        var ingest = MacIngest.macIngest(store.checkouts(),
                                         store.sources(),
                                         config.mac().roots(),
                                         clock,
                                         derive::trigger);
        var conflicts = config.conflicts()
                              .map(settings -> ConflictWatch.conflictWatch(GitConflicts.gitConflicts(ProcessRunner.processRunner(),
                                                                                                     config.mirror()
                                                                                                           .path(),
                                                                                                     settings.scratch()),
                                                                           store.prs()::all,
                                                                           settings.branch(),
                                                                           EventsLog.file(settings.eventsLog()),
                                                                           clock,
                                                                           ConflictWatch.DEADLINE));
        var mirror = MirrorStep.mirrorStep(ports.mirror(),
                                           store.prs(),
                                           store.sources(),
                                           clock,
                                           at -> fetched(derive, conflicts, at),
                                           MirrorStep.DEADLINE);
        var temper = TemperReader.temperReader(ports.temper(),
                                               store,
                                               clock,
                                               config.temper().cycleDeadline());
        var fleet = HostsReader.hostsReader(ports.temper(),
                                            store.sources(),
                                            clock,
                                            config.temper().cycleDeadline());
        var budget = RateBudget.rateBudget(config.github().hourlyCap(),
                                           config.github().reserve());
        var reads = GitHubReads.gitHubReads(ports.github(), store.etags(), budget, clock);
        var pipeline = PipelineReader.pipelineReader(ports.github(),
                                                     store.etags(),
                                                     budget,
                                                     store.pipeline(),
                                                     PipelineScope.pipelineScope(config.github().repo()),
                                                     clock);

        return GitHubPoller.gitHubPoller(reads,
                                         UpsertTrigger.upsertTrigger(store, derive::prsUpserted),
                                         config.github().repo(),
                                         config.github().bases(),
                                         clock,
                                         config.github().cycleDeadline(),
                                         some(GitHubFeed.gitHubFeed(reads,
                                                                    store.feed(),
                                                                    ports.heads(),
                                                                    config.github().repos())))
                           .map(github -> new Kiln(config,
                                                   store,
                                                   github,
                                                   temper,
                                                   fleet,
                                                   pipeline,
                                                   mirror,
                                                   scanners,
                                                   ingest,
                                                   derive,
                                                   conflicts,
                                                   clock));
    }

    /// After a mirror fetch that succeeded: the derivation's trigger, then the conflict watch (temper #100), which
    /// starts its step and returns at once.
    private static void fetched(DeriveJob derive, Option<ConflictWatch> conflicts, Instant at) {
        derive.mirrorFetched(at);
        conflicts.onPresent(watch -> watch.fetched(at));
    }

    /// Serves the routes on `[server] port` of the loopback address (and of `[server] bind`, if set), then starts the loops. A port that cannot be bound
    /// fails the start (fatal, `Main`).
    public Promise<Running> start() {
        var routes = routes();

        return LoopbackServer.start(config.server().port(),
                                    config.server().bind(),
                                    config.server().hostNames(),
                                    Router.router(served(routes)))
                             .async()
                             .map(server -> started(server, routes));
    }

    private Running started(LoopbackServer server, ApiRoutes routes) {
        var loops = loops();
        var daily = daily();

        loops.forEach(Periodic::start);
        daily.forEach(Daily::start);

        return new Running(server, routes, loops, daily);
    }

    /// The API's routes and the merge gate's fresh read.
    private List<Route> served(ApiRoutes routes) {
        return Stream.concat(routes.routes().stream(),
                             Stream.of(fresh().route()))
                     .toList();
    }

    /// `GET /api/v1/pr-fresh` over the poller's own GitHub port and rate budget; its one store access is the `base_tip`
    /// read.
    public PrFresh fresh() {
        return PrFresh.prFresh(github.reads().http(),
                               github.reads().budget(),
                               store.feed()::baseTip,
                               config.github().repos(),
                               clock);
    }

    public ApiRoutes routes() {
        return ApiRoutes.apiRoutes(store,
                                   ingest,
                                   config.github().bases(),
                                   hostNames(),
                                   this::budget,
                                   github::skippedNotes,
                                   this::periods,
                                   configuredPeriods(),
                                   clock,
                                   github::budget,
                                   fleet::lastReply,
                                   pipeline::view,
                                   this::conflictBoard);
    }

    /// The conflict watch's board; none while the watch is off (no `[conflicts]` from the loader).
    Option<ConflictBoard> conflictBoard() {
        return conflicts.map(ConflictWatch::board);
    }

    private List<HostName> hostNames() {
        return config.hosts()
                     .stream()
                     .map(KilnConfig.Host::name)
                     .toList();
    }

    /// The rate budget as GitHub last stated it, observed when it was stated (WP9 open item 2).
    Option<Observed<org.pragmatica.kiln.service.web.RateBudget>> budget() {
        var rate = github.rate();
        var budget = github.reads().budget();

        return Option.all(rate.remaining(),
                          rate.reset(),
                          rate.at())
                     .map((remaining, reset, at) -> Observed.observed(new org.pragmatica.kiln.service.web.RateBudget(remaining,
                                                                                                                     reset,
                                                                                                                     budget.spent(clock.get()),
                                                                                                                     budget.hourlyCap(),
                                                                                                                     budget.reserve()),
                                                                      at));
    }

    /// The periods the pages judge staleness by: GitHub's as it is paced now, the hot pass's, and the Mac's
    /// [Periods#MAC_PUSH] (WP9 open item 3).
    Periods periods() {
        return Periods.periods(github.period(),
                               MirrorStep.PERIOD,
                               TemperReader.PERIOD,
                               config.scan().hot());
    }

    /// The loops' fixed periods, GitHub's never stretched: what the change feed reports as `periodS`
    /// (lead ruling), so a reader judging staleness by age sees rate-limited data as stale.
    Periods configuredPeriods() {
        return Periods.periods(GitHubPoller.PERIOD,
                               MirrorStep.PERIOD,
                               TemperReader.PERIOD,
                               config.scan().hot());
    }

    List<Periodic> loops() {
        var scan = config.scan();

        return Stream.of(Stream.of(Periodic.periodic("github",
                                                     Duration.ZERO,
                                                     GitHubPoller.PERIOD,
                                                     this::gitHubThenMirror),
                                   Periodic.periodic("mirror", MirrorStep.PERIOD, MirrorStep.PERIOD, mirror::step),
                                   Periodic.periodic("temper", Duration.ZERO, TemperReader.PERIOD, temper::tick),
                                   Periodic.periodic("hosts", Duration.ZERO, HostsReader.PERIOD, fleet::tick),
                                   Periodic.periodic("pipeline", PIPELINE_DELAY, PipelineScope.PERIOD, pipeline::tick),
                                   Periodic.periodic("retention",
                                                     Duration.ZERO,
                                                     Duration.ofDays(1),
                                                     this::pruned)),
                         scanners.stream()
                                 .flatMap(scanner -> Stream.of(Periodic.periodic("hot:" + scanner.host().name().value(),
                                                                                 Duration.ZERO,
                                                                                 scan.hot(),
                                                                                 scanner::hot),
                                                               Periodic.periodic("size:" + scanner.host().name().value(),
                                                                                 scan.sizeGap(),
                                                                                 scan.sizeGap(),
                                                                                 scanner::size))))
                     .flatMap(loops -> loops)
                     .toList();
    }

    List<Daily> daily() {
        return scanners.stream()
                       .map(scanner -> Daily.daily("full:" + scanner.host().name().value(),
                                                   config.scan().fullAt(),
                                                   ZoneId.systemDefault(),
                                                   clock,
                                                   scanner::full))
                       .toList();
    }

    /// A GitHub tick, then the mirror step when a cycle ran (§3.1: the mirror step runs right after each GitHub cycle).
    Promise<Unit> gitHubThenMirror() {
        return github.tick()
                     .flatMap(ran -> ran.isPresent()
                                     ? mirror.step()
                                             .mapToUnit()
                                     : Promise.unitPromise());
    }

    Promise<Unit> pruned() {
        return store.changes()
                    .prune(clock.get().minus(RETENTION));
    }
}
