package org.pragmatica.temper.controller;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.api.ApiRoutes;
import org.pragmatica.temper.controller.api.DiffRoutes;
import org.pragmatica.temper.controller.api.DigestRoutes;
import org.pragmatica.temper.controller.api.GateRoutes;
import org.pragmatica.temper.controller.api.KnownRoutes;
import org.pragmatica.temper.controller.api.RerunRoutes;
import org.pragmatica.temper.controller.api.SubscriberRoutes;
import org.pragmatica.temper.controller.api.TaskRoutes;
import org.pragmatica.temper.controller.api.TailLog;
import org.pragmatica.temper.controller.cancel.CancelJob;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.digest.Digest;
import org.pragmatica.temper.controller.events.EventFeed;
import org.pragmatica.temper.controller.evidence.EvidenceLayout;
import org.pragmatica.temper.controller.evidence.ResultFiles;
import org.pragmatica.temper.controller.evidence.SshEvidence;
import org.pragmatica.temper.controller.evidence.UnitEvidence;
import org.pragmatica.temper.controller.flow.Collect;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.LinkEnv;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.LeaseToken;
import org.pragmatica.temper.controller.http.HttpGlue;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.http.VersionGate;
import org.pragmatica.temper.controller.maintenance.DumpSchedule;
import org.pragmatica.temper.controller.maintenance.PgDump;
import org.pragmatica.temper.controller.retention.RetentionGc;
import org.pragmatica.temper.controller.scheduler.Scheduler;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.controller.submit.GitRemote;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.controller.submit.RerunJob;
import org.pragmatica.temper.controller.submit.PullOrigins;
import org.pragmatica.temper.controller.submit.SourceResolver;
import org.pragmatica.temper.controller.submit.SubmitJob;
import org.pragmatica.temper.controller.submit.TreeReader;
import org.pragmatica.temper.controller.t2.FreshToken;
import org.pragmatica.temper.controller.t2.GoRoutes;
import org.pragmatica.temper.controller.t2.KilnFresh;
import org.pragmatica.temper.controller.t2.KilnPull;
import org.pragmatica.temper.controller.t2.T2Detector;
import org.pragmatica.temper.controller.t2.T2Routes;
import org.pragmatica.temper.controller.review.CarryProof;
import org.pragmatica.temper.controller.review.ReviewGitHub;
import org.pragmatica.temper.controller.review.ReviewRoutes;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.git.GitHubRepo;
import org.pragmatica.temper.controller.t3.MacRoutes;
import org.pragmatica.temper.controller.t3.RetireWarn;
import org.pragmatica.temper.controller.t3.TicketAlarms;
import org.pragmatica.temper.controller.t3.TicketCollector;
import org.pragmatica.temper.controller.t3.TicketEvents;
import org.pragmatica.temper.controller.t3.TicketRoutes;
import org.pragmatica.temper.controller.t3.TicketTransport;
import org.pragmatica.temper.controller.t3.TicketTransports;
import org.pragmatica.temper.controller.task.ClaimTask;
import org.pragmatica.temper.controller.task.CloseTask;
import org.pragmatica.temper.controller.task.CreateTask;
import org.pragmatica.temper.controller.task.PullMerges;
import org.pragmatica.temper.controller.task.TaskSweeper;
import org.pragmatica.temper.domain.plan.PlanDefaults;
import org.pragmatica.temper.domain.retention.RetentionPolicy;
import org.pragmatica.temper.domain.task.ProtectedRefs;
import org.pragmatica.temper.protocol.digest.Digests;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.HostConfig;
import org.pragmatica.temper.store.api.Store;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// The assembled controller (m0-design §6, m1-design §3, §4.2): HTTP API, submit, scheduler, host links, events, the
/// evidence store, the retention GC with the `result.json` sweep, the nightly `pg_dump`, and the fleet event stream. Every dependency is
/// passed in, so tests run it with a test store, fake git and a fake `ProcessRunner`.
public record Controller(ControllerConfig config,
                         Store store,
                         Credentials credentials,
                         ProcessRunner runner,
                         Git git,
                         LinkTiming timing,
                         TimeSpan pollWait,
                         Supplier<Instant> clock,
                         Fn1<Option<TicketTransport>, String> ticketTransports) {
    /// Request bodies are capped at 1 MiB (m0-design §6.2).
    public static final int MAX_CONTENT_LENGTH = 1024 * 1024;
    public static final TimeSpan POLL_WAIT = TimeSpan.timeSpan(30).seconds();
    /// One scheduler tick (store reads, grant transaction) never takes longer than this.
    public static final TimeSpan TICK_BOUND = TimeSpan.timeSpan(60).seconds();

    /// The git adapters submit uses, the judge of pull-request heads (R-M2-7), and GitHub's merged flag for closing a
    /// task (m3-design §10.5).
    public record Git(GitRemote remote,
                      Mirror mirror,
                      TreeReader tree,
                      PullOrigins pulls,
                      PullMerges merges,
                      Option<ReviewGitHub> reviews) {
        /// Without a PR-state reader: closing a task as merged fails (fail closed), as when GitHub cannot be read.
        public static Git git(GitRemote remote, Mirror mirror, TreeReader tree, PullOrigins pulls) {
            return new Git(remote,
                           mirror,
                           tree,
                           pulls,
                           _ -> Causes.cause("no pull-request state reader").promise(),
                           none());
        }

        public static Git git(GitRemote remote, Mirror mirror, TreeReader tree, PullOrigins pulls, PullMerges merges) {
            return new Git(remote, mirror, tree, pulls, merges, none());
        }

        /// With the reviewer's GitHub (`[github.reviewer]`): `POST fleet/reviews` answers
        /// 503 `REVIEWER_CREDENTIAL` without it.
        public Git withReviews(Option<ReviewGitHub> reviews) {
            return new Git(remote, mirror, tree, pulls, merges, reviews);
        }
    }

    /// The periodic maintenance: the retention GC (with the `result.json` sweep), the daily dump, and the task sweeper
    /// (lease expiry, resumes and the dispatcher's T1, m3-design §6.2).
    public record Maintenance(RetentionGc retention, DumpSchedule dumps, TaskSweeper tasks) {
        public static Maintenance maintenance(RetentionGc retention, DumpSchedule dumps, TaskSweeper tasks) {
            return new Maintenance(retention, dumps, tasks);
        }

        Unit stop() {
            retention.stop();
            tasks.stop();

            return dumps.stop();
        }
    }

    /// A started controller. `routes` are the routes its HTTP API serves (the census of the D-6 version gate enumerates
    /// them).
    public record Running(HttpServer server,
                          List<Route> routes,
                          HostLinks links,
                          Scheduler scheduler,
                          Wake wake,
                          Maintenance maintenance,
                          Fleet fleet,
                          T2Detector detector,
                          TicketCollector collector,
                          RetireWarn retire) {
        public Running {
            routes = List.copyOf(routes);
        }

        public static Running running(HttpServer server,
                                      List<Route> routes,
                                      HostLinks links,
                                      Scheduler scheduler,
                                      Wake wake,
                                      Maintenance maintenance,
                                      Fleet fleet,
                                      T2Detector detector,
                                      TicketCollector collector,
                                      RetireWarn retire) {
            return new Running(server, routes, links, scheduler, wake, maintenance, fleet, detector, collector, retire);
        }

        public int port() {
            return server.port();
        }

        /// Stops the retire tick and the ticket collector (waiting for a read in flight to be recorded), the maintenance, the links (workers shut down; their containers keep running) and the HTTP server.
        public Promise<Unit> stop() {
            retire.stop();

            return collector.stop()
                            .flatMap(_ -> stopRest());
        }

        private Promise<Unit> stopRest() {
            detector.stop();
            fleet.stop();
            maintenance.stop();
            scheduler.stop();
            links.stopAll();

            return server.stop();
        }
    }

    public static Controller controller(ControllerConfig config,
                                        Store store,
                                        Credentials credentials,
                                        ProcessRunner runner,
                                        Git git,
                                        LinkTiming timing,
                                        TimeSpan pollWait,
                                        Supplier<Instant> clock) {
        return new Controller(config, store, credentials, runner, git, timing, pollWait, clock, _ -> none());
    }

    /// With `ticketTransports` answering, per bb host, the transport to read its tickets with (tests and the acceptance
    /// chain pass a [org.pragmatica.temper.controller.t3.LocalTicketTransport]); none → the ssh transport of the config.
    public static Controller controller(ControllerConfig config,
                                        Store store,
                                        Credentials credentials,
                                        ProcessRunner runner,
                                        Git git,
                                        LinkTiming timing,
                                        TimeSpan pollWait,
                                        Supplier<Instant> clock,
                                        Fn1<Option<TicketTransport>, String> ticketTransports) {
        return new Controller(config, store, credentials, runner, git, timing, pollWait, clock, ticketTransports);
    }

    /// Upserts the configured hosts, starts the links and the scheduler, then the HTTP server, then the maintenance.
    public Promise<Running> start() {
        var wake = Wake.wake();
        var mapper = TemperJson.mapper();
        var layout = EvidenceLayout.evidenceLayout(config.evidence().root());
        var resultFiles = ResultFiles.resultFiles(layout, store.results(), clock);
        var fleet = Fleet.fleet(config.fleet(),
                                config.hosts().stream().map(ControllerConfig.Host::name).toList(),
                                store.fleet(),
                                runner,
                                mapper,
                                clock);
        var links = HostLinks.hostLinks(config.hosts()
                                              .stream()
                                              .map(host -> HostLink.hostLink(host,
                                                                             linkEnv(mapper, wake, resultFiles).withSignal(fleet.signal())
                                                                                    .withClock(clock)))
                                              .toList(),
                                        store.jobs());
        var scheduler = Scheduler.scheduler(links,
                                            store.units(),
                                            config.scheduler().lease(),
                                            config.scheduler().starvation(),
                                            config.scheduler().retireWindow(),
                                            config.units().diskFloorBytes(),
                                            config.portGroup().maxHolding(),
                                            clock,
                                            TICK_BOUND);
        var maintenance = maintenance(layout, resultFiles, links);
        var router = router(mapper, wake, links, scheduler, fleet);
        var detector = T2Detector.t2Detector(store.t2(), fleet.writer(), clock);
        var collector = TicketCollector.ticketCollector(store.bb(),
                                                        store.fleet(),
                                                        fleet.writer(),
                                                        ticketTargets(),
                                                        mapper,
                                                        clock,
                                                        TicketAlarms.round(TicketEvents.ticketEvents(),
                                                                           TicketAlarms.Limits.limits(config.fleet()
                                                                                                            .queueWaitAlarmMin(),
                                                                                                      config.fleet()
                                                                                                            .portGroupHeldAlarmMin())),
                                                        TicketCollector.READ_BOUND);
        var retire = RetireWarn.retireWarn(store.bb(),
                                           fleet.writer(),
                                           config.hosts()
                                                 .stream()
                                                 .map(host -> RetireWarn.Retire.retire(host.name().value(),
                                                                                       host.retireAt()))
                                                 .toList(),
                                           clock);

        return upsertHosts().flatMap(_ -> fleet.settle())
                          .flatMap(_ -> collector.settle())
                          .flatMap(_ -> serve(router))
                          .flatMap(server -> fleet.start(server.port())
                                                  .map(_ -> server))
                          .map(server -> started(server,
                                                 router.routes(),
                                                 links,
                                                 scheduler,
                                                 wake,
                                                 maintenance,
                                                 fleet,
                                                 detector,
                                                 collector,
                                                 retire));
    }

    /// Every collected bb host with its transport: each `[[hosts]]` host over its own key (and not
    /// after its `retireAt`, #103), each `bbHosts` entry with a link over the scan key; the entries without a link are
    /// not collected.
    private List<TicketCollector.Target> ticketTargets() {
        return Stream.concat(config.hosts()
                                   .stream()
                                   .map(host -> TicketCollector.Target.target(host.name().value(),
                                                                              ticketTransports.apply(host.name().value())
                                                                                              .or(() -> TicketTransports.hostLink(runner,
                                                                                                                                  host)),
                                                                              host.retireAt())),
                             config.fleet()
                                   .bbHosts()
                                   .stream()
                                   .flatMap(bb -> bb.link()
                                                    .map(link -> TicketCollector.Target.target(bb.name(),
                                                                                               ticketTransports.apply(bb.name())
                                                                                                               .or(() -> TicketTransports.scanKey(runner,
                                                                                                                                                  link,
                                                                                                                                                  config.fleet()))))
                                                    .stream()))
                     .toList();
    }

    /// Every expected bb host, in configuration order: the `[[hosts]]`, then `bbHosts`.
    private List<TicketRoutes.Expected> expectedHosts() {
        return Stream.concat(config.hosts()
                                   .stream()
                                   .map(host -> TicketRoutes.Expected.expected(host.name().value(),
                                                                               "hostlink",
                                                                               host.retireAt())),
                             config.fleet()
                                   .bbHosts()
                                   .stream()
                                   .map(bb -> TicketRoutes.Expected.expected(bb.name(),
                                                                             bb.link().isPresent()
                                                                             ? "bbscan"
                                                                             : "none")))
                     .toList();
    }

    private Running started(HttpServer server,
                            List<Route> routes,
                            HostLinks links,
                            Scheduler scheduler,
                            Wake wake,
                            Maintenance maintenance,
                            Fleet fleet,
                            T2Detector detector,
                            TicketCollector collector,
                            RetireWarn retire) {
        links.startAll();
        fleet.schedule(links);
        detector.schedule();
        collector.schedule();
        retire.schedule();
        scheduler.start(TimeSpan.fromDuration(config.scheduler().interval()),
                        wake);
        maintenance.retention().start(TimeSpan.fromDuration(config.retention().interval()));
        maintenance.dumps().start();
        maintenance.tasks().start(TaskSweeper.INTERVAL);

        return Running.running(server, routes, links, scheduler, wake, maintenance, fleet, detector, collector, retire);
    }

    private Maintenance maintenance(EvidenceLayout layout, ResultFiles resultFiles, HostLinks links) {
        return Maintenance.maintenance(RetentionGc.retentionGc(store.retention(),
                                                               store.evidence(),
                                                               links,
                                                               resultFiles,
                                                               layout,
                                                               RetentionPolicy.DEFAULT,
                                                               clock),
                                       DumpSchedule.dumpSchedule(PgDump.pgDump(runner,
                                                                               layout,
                                                                               config.pgDump().container(),
                                                                               config.db().user(),
                                                                               config.db().name(),
                                                                               store.maintenance(),
                                                                               clock),
                                                                 store.maintenance(),
                                                                 config.pgDump().at(),
                                                                 ZoneId.systemDefault(),
                                                                 clock),
                                       TaskSweeper.taskSweeper(store.tasks(), clock));
    }

    /// `Collect.all`: a failed start is only logged (`Main`), and the log names every host that could not be written.
    private Promise<Unit> upsertHosts() {
        return Collect.all(config.hosts().stream().map(this::upsertHost).toList()).mapToUnit();
    }

    /// `hostKey` is not configured in M0: ssh pins it through `knownHosts`. `shared_cpu` is what the grant's candidate
    /// filter and its per-attempt snapshot read (m4-design §9.1, §9.4).
    private Promise<Unit> upsertHost(ControllerConfig.Host host) {
        return HostConfig.hostConfig(host.name(),
                                     host.address(),
                                     none(),
                                     host.cap(),
                                     host.memoryBudgetMb(),
                                     host.portIsolated(),
                                     host.sharedCpu(),
                                     host.retireAt())
                         .async()
                         .flatMap(store.hosts()::upsert);
    }

    private Promise<HttpServer> serve(Router router) {
        return HttpServer.httpServer(HttpServerConfig.httpServerConfig("temper",
                                                                       config.server().port())
                                                     .withMaxContentLength(MAX_CONTENT_LENGTH),
                                     HttpGlue.httpGlue(router,
                                                       VersionGate.versionGate(config.version(), router.routes())));
    }

    private Router router(JsonMapper mapper, Wake wake, HostLinks links, Scheduler scheduler, Fleet fleet) {
        var responses = Responses.responses(mapper);
        var feed = EventFeed.eventFeed(store.events(), store.jobs(), wake, pollWait);
        var submit = SubmitJob.submitJob(submitSteps(wake, links));
        var routes = ApiRoutes.apiRoutes(responses,
                                         mapper,
                                         submit,
                                         CancelJob.cancelJob(store.jobs(), links, wake),
                                         TailLog.tailLog(store.jobs(), links),
                                         store.jobs(),
                                         store.results(),
                                         store.units(),
                                         store.refusals(),
                                         store.maintenance(),
                                         links,
                                         feed,
                                         clock,
                                         timing.startBound());
        var digest = DigestRoutes.digestRoutes(responses,
                                               Digest.digest(store.digest(),
                                                             DigestRoutes.links(links),
                                                             scheduler::explain,
                                                             clock),
                                               links,
                                               timing.startBound());
        var gate = GateRoutes.gateRoutes(responses, store.jobs(), links, clock, timing.startBound());
        var subscribers = SubscriberRoutes.subscriberRoutes(responses, mapper, feed, store.jobs());
        var diff = DiffRoutes.diffRoutes(responses, store.results(), links, clock, timing.startBound());
        var known = KnownRoutes.knownRoutes(responses, mapper, store.known());
        var tasks = taskRoutes(responses, mapper);
        var rerun = RerunRoutes.rerunRoutes(routes,
                                            mapper,
                                            RerunJob.rerunJob(RerunJob.Steps.steps(store.jobs(),
                                                                                   store.results(),
                                                                                   submit,
                                                                                   git.tree(),
                                                                                   config.units()
                                                                                         .rerunInstallAllowance())));

        return Router.router(credentials,
                             Stream.of(routes.routes(),
                                       digest.routes(),
                                       gate.routes(),
                                       subscribers.routes(),
                                       diff.routes(),
                                       known.routes(),
                                       rerun.routes(),
                                       tasks.routes(),
                                       fleet.routes(responses,
                                                    mapper,
                                                    credentials.read().map(BearerToken::id)),
                                       t2Routes(responses, mapper, fleet).routes(),
                                       MacRoutes.macRoutes(responses,
                                                           mapper,
                                                           store.mac(),
                                                           clock,
                                                           fleet.ready())
                                                .routes(),
                                       TicketRoutes.ticketRoutes(responses,
                                                                 store.fleet(),
                                                                 store.mac(),
                                                                 store.bb(),
                                                                 expectedHosts(),
                                                                 clock,
                                                                 fleet.ready())
                                                   .routes(),
                                       goRoutes(responses, mapper, fleet).routes(),
                                       reviewRoutes(responses, mapper, fleet).routes())
                                   .flatMap(List::stream)
                                   .toList(),
                             responses);
    }

    /// The T2 routes, gated as the fleet routes are; a queue `add` without a base asks the
    /// configured kiln.
    private T2Routes t2Routes(Responses responses, JsonMapper mapper, Fleet fleet) {
        return T2Routes.t2Routes(responses,
                                 mapper,
                                 store.t2(),
                                 fleet.writer(),
                                 config.fleet().kilnUrl().map(url -> KilnPull.http(url, mapper, KilnPull.TIMEOUT)),
                                 clock,
                                 fleet.ready());
    }

    /// The author-set and review routes, gated as the fleet routes are. The carry
    /// proof runs on the configured repository's mirror only; any other repository has none (503).
    private ReviewRoutes reviewRoutes(Responses responses, JsonMapper mapper, Fleet fleet) {
        return ReviewRoutes.reviewRoutes(responses,
                                         mapper,
                                         store.reviews(),
                                         store.t2(),
                                         fleet.writer(),
                                         git.reviews(),
                                         this::carryProof,
                                         config.version(),
                                         clock,
                                         fleet.ready());
    }

    private Option<CarryProof> carryProof(String repo) {
        var configured = GitHubRepo.gitHubRepo(config.repo().origin()).fold(_ -> false,
                                                                            name -> name.fullName()
                                                                                        .equals(repo));

        return configured && git.mirror() instanceof GitCli cli
               ? some(CarryProof.carryProof(runner, cli.mirror(), cli::fetch))
               : none();
    }

    /// The GO routes, gated as the fleet routes are: `pr-fresh` asks the configured kiln,
    /// and its tokens are signed with a key made here, once per start (a restart voids them).
    private GoRoutes goRoutes(Responses responses, JsonMapper mapper, Fleet fleet) {
        return GoRoutes.goRoutes(responses,
                                 mapper,
                                 store.t2(),
                                 fleet.writer(),
                                 config.fleet().kilnUrl().map(url -> KilnFresh.http(url, mapper, KilnPull.TIMEOUT)),
                                 FreshToken.freshToken(new SecureRandom()),
                                 clock,
                                 fleet.ready());
    }

    /// The task API (m3-design §11.1): lease tokens from the platform's strong random source; a claimed task's clone
    /// comes from this controller's origin.
    private TaskRoutes taskRoutes(Responses responses, JsonMapper mapper) {
        var random = new SecureRandom();

        return TaskRoutes.taskRoutes(responses,
                                     mapper,
                                     store.tasks(),
                                     CreateTask.createTask(store.tasks(), git.mirror(), ProtectedRefs.DEFAULT, clock),
                                     ClaimTask.claimTask(ClaimTask.Steps.steps(store.tasks(),
                                                                               git.mirror(),
                                                                               () -> LeaseToken.mint(random),
                                                                               config.repo().origin(),
                                                                               clock)),
                                     CloseTask.closeTask(store.tasks(), git.merges(), clock),
                                     clock);
    }

    private SubmitJob.Steps submitSteps(Wake wake, HostLinks links) {
        return SubmitJob.Steps.steps(SourceResolver.sourceResolver(git.remote(), git.mirror(), git.pulls()),
                                     store.refusals(),
                                     git.tree(),
                                     store.jobs(),
                                     PlanDefaults.planDefaults(config.units().memory(),
                                                               config.units().gateMemory(),
                                                               config.portGroup().modules()),
                                     (identity, recipe, options) -> Digests.dedupKey(identity,
                                                                                     recipe,
                                                                                     options,
                                                                                     config.dedupRunner()),
                                     links,
                                     wake,
                                     store.tasks()::holds);
    }

    private LinkEnv linkEnv(JsonMapper mapper, Wake wake, ResultFiles resultFiles) {
        return LinkEnv.linkEnv(runner,
                               mapper,
                               store.units(),
                               store.jobs(),
                               store.hosts(),
                               wake,
                               config.version(),
                               config.repo().origin(),
                               config.units().cpuShares(),
                               config.units().mavenOpts(),
                               config.units().tcHubPrefix(),
                               config.scheduler().lease(),
                               config.scheduler().hostLost(),
                               config.worker().jar(),
                               timing,
                               config.portGroup().owner(),
                               config.server().instance(),
                               UnitEvidence.unitEvidence(SshEvidence.sshEvidence(runner, config.evidence()),
                                                         store.evidence(),
                                                         resultFiles,
                                                         config.evidence().root(),
                                                         config.evidence().minFreeBytes()));
    }
}
