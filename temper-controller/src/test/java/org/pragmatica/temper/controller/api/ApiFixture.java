package org.pragmatica.temper.controller.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.QueryParams;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.cancel.CancelJob;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.digest.Digest;
import org.pragmatica.temper.controller.events.EventFeed;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.controller.host.HostLink;
import org.pragmatica.temper.controller.host.HostLinks;
import org.pragmatica.temper.controller.host.LinkEnv;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.ApiRequest;
import org.pragmatica.temper.controller.http.ApiResponse;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.http.Responses;
import org.pragmatica.temper.controller.http.Route;
import org.pragmatica.temper.controller.http.Router;
import org.pragmatica.temper.controller.signal.Wake;
import org.pragmatica.temper.controller.submit.GitError;
import org.pragmatica.temper.controller.submit.GitRemote;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.controller.submit.RerunJob;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.SourceResolver;
import org.pragmatica.temper.controller.submit.SubmitJob;
import org.pragmatica.temper.controller.submit.TreeReader;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.mvn.ReactorModules;
import org.pragmatica.temper.domain.plan.PlanDefaults;
import org.pragmatica.temper.domain.refusal.ClassSource;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.refusal.TreeFacts;
import org.pragmatica.temper.domain.task.TaskError;
import org.pragmatica.temper.protocol.digest.Digests;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.pragmatica.lang.Unit.unit;


/// The router with every route over in-memory fakes and a scripted git, behind the full token and a read token (D3).
/// The digest route is the real [DigestRoutes] over a digest that always fails: its answer is not under test here,
/// only that the route is in the list and reachable. The gate route is the real [GateRoutes] over the fake jobs'
/// `gateRows`, the diff route the real [DiffRoutes] over the fake results' `sides` and the jobs' `gateRows`, the known
/// routes the real [KnownRoutes] over the fake registry, and the rerun route the real [RerunRoutes] over the fake jobs
/// and results and the same submit.
final class ApiFixture {
    static final String TOKEN = "s3cret-token";
    static final String READ_TOKEN = "r3ad-only-token";
    static final String SHA = "0cf563192a" + "0".repeat(30);
    /// The one lease token this fixture's lease check accepts (m3-design §6.4).
    static final String LEASE_TOKEN = "good-lease-token";

    final FakeStores.Jobs jobs = new FakeStores.Jobs();
    final FakeStores.Units units = new FakeStores.Units();
    final FakeStores.Refusals refusals = new FakeStores.Refusals();
    final FakeStores.Known known = new FakeStores.Known();
    final FakeStores.Results results = new FakeStores.Results();
    final FakeStores.Events events = new FakeStores.Events();
    final FakeStores.Maintenance maintenance = new FakeStores.Maintenance();
    final Map<String, String> refs = new ConcurrentHashMap<>(Map.of("main", SHA));
    final Set<String> containedShas = ConcurrentHashMap.newKeySet();
    final Map<String, List<ClassSource>> tree = new ConcurrentHashMap<>();
    /// Files of the tree at any SHA, by path (the rerun bound's shard weights).
    final Map<String, String> files = new ConcurrentHashMap<>();
    /// Each file read, as `<sha>:<path>`.
    final List<String> fileReads = new CopyOnWriteArrayList<>();
    volatile boolean filesDown = false;
    final List<List<UnitRef>> cancelled = new CopyOnWriteArrayList<>();
    final Wake wake = Wake.wake();
    volatile boolean gitDown = false;
    /// Every git or tree read a submit made: ls-remote, a mirror read, a tree read.
    final AtomicInteger gitCalls = new AtomicInteger();
    volatile boolean treeDown = false;
    /// The reactor the tree reader reports (none: unknown, the conservative M0 judgement).
    volatile Option<ReactorModules> reactor = Option.none();
    /// Every route the controller serves, in the order the router matches them ([ApiRoutes], [DigestRoutes], [GateRoutes],
    /// [SubscriberRoutes], [DiffRoutes], [KnownRoutes], then [RerunRoutes]).
    final List<Route> routes;
    final Responses responses;
    final Router router;

    ApiFixture(List<String> portGroupModules) {
        this(portGroupModules, Instant::now, List.of());
    }

    /// With a link per entry of `hosts` (never started, so it stays as built) and [ApiRoutes] reading time from `clock`.
    ApiFixture(Supplier<Instant> clock, List<ControllerConfig.Host> hosts) {
        this(List.of(), clock, hosts);
    }

    private ApiFixture(List<String> portGroupModules, Supplier<Instant> clock, List<ControllerConfig.Host> hosts) {
        var mapper = TemperJson.mapper();

        responses = Responses.responses(mapper);
        GitRemote remote = ref -> gitCalls.incrementAndGet() < 0
                                  ? Promise.<Sha> promise()
                                  : gitDown
                                    ? GitError.Failed.FACTORY.apply("ls-remote",
                                                                    Causes.cause("network down"))
                                                             .promise()
                                    : refs.containsKey(ref.value())
                                      ? Sha.sha(refs.get(ref.value())).async()
                                      : Refusal.refusal(RefusalCode.SOURCE, "ref " + ref + " not found").promise();
        var mirror = new Mirror() {
            @Override
            public Promise<Unit> fetch() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Boolean> containedInBranch(Sha sha) {
                gitCalls.incrementAndGet();

                return Promise.success(containedShas.contains(sha.value()));
            }

            @Override
            public Promise<List<PullNumber>> pullHeadsContaining(Sha sha) {
                return Promise.success(List.of());
            }

            @Override
            public Promise<Option<Sha>> branchTip(Ref branch) {
                return Promise.success(Option.option(refs.get(branch.value())).flatMap(sha -> Sha.sha(sha).option()));
            }

            @Override
            public Promise<Boolean> branchContains(Ref branch, Sha sha) {
                return Promise.success(sha.value().equals(refs.get(branch.value())));
            }
        };
        var treeReader = new TreeReader() {
            @Override
            public Promise<TreeFacts> facts(Sha sha, Set<String> names) {
                gitCalls.incrementAndGet();

                return treeDown
                       ? GitError.Failed.FACTORY.apply("show",
                                                       Causes.cause("mirror broken"))
                                                .promise()
                       : Promise.success(TreeFacts.treeFacts(Map.copyOf(tree), reactor));
            }

            @Override
            public Promise<Option<byte[]>> file(Sha sha, String path) {
                fileReads.add(sha.value() + ":" + path);

                return filesDown
                       ? GitError.Failed.FACTORY.apply("show",
                                                       Causes.cause("mirror broken"))
                                                .promise()
                       : FakeStores.later(5,
                                          Promise.success(Option.option(files.get(path)).map(text -> text.getBytes(StandardCharsets.UTF_8))));
            }
        };
        var links = HostLinks.hostLinks(hosts.stream().map(host -> HostLink.hostLink(host, linkEnv(mapper))).toList(),
                                        jobs);
        var submit = SubmitJob.submitJob(SubmitJob.Steps.steps(SourceResolver.sourceResolver(remote,
                                                                                             mirror,
                                                                                             pull -> Causes.cause("no PR heads in this fixture").promise()),
                                                               refusals,
                                                               treeReader,
                                                               jobs,
                                                               PlanDefaults.planDefaults(MemoryMb.memoryMb(6144).unwrap(),
                                                                                         MemoryMb.memoryMb(16384).unwrap(),
                                                                                         portGroupModules),
                                                               (identity, recipe, options) -> Digests.dedupKey(identity,
                                                                                                               recipe,
                                                                                                               options,
                                                                                                               "0.1.0"),
                                                               refsToKill -> record(refsToKill),
                                                               wake,
                                                               lease -> lease.tokenHash()
                                                                             .equals(Digests.sha256Hex(LEASE_TOKEN).unwrap())
                                                                        ? Promise.unitPromise()
                                                                        : TaskError.General.LEASE_LOST.promise()));
        var feed = EventFeed.eventFeed(events,
                                       jobs,
                                       wake,
                                       TimeSpan.timeSpan(200).millis());
        var routes = ApiRoutes.apiRoutes(responses,
                                         mapper,
                                         submit,
                                         CancelJob.cancelJob(jobs, refsToKill -> record(refsToKill), wake),
                                         TailLog.tailLog(jobs, links),
                                         jobs,
                                         results,
                                         units,
                                         refusals,
                                         maintenance,
                                         links,
                                         feed,
                                         clock,
                                         LinkTiming.DEFAULT.startBound());
        Digest digest = request -> Causes.cause("no digest in this fixture").promise();

        results.gates = jobs;
        var gate = GateRoutes.gateRoutes(responses, jobs, links, Instant::now, LinkTiming.DEFAULT.startBound());

        this.routes = Stream.of(routes.routes(),
                                DigestRoutes.digestRoutes(responses,
                                                          digest,
                                                          links,
                                                          LinkTiming.DEFAULT.startBound())
                                            .routes(),
                                gate.routes(),
                                SubscriberRoutes.subscriberRoutes(responses, mapper, feed, jobs).routes(),
                                DiffRoutes.diffRoutes(responses,
                                                      results,
                                                      links,
                                                      Instant::now,
                                                      LinkTiming.DEFAULT.startBound())
                                          .routes(),
                                KnownRoutes.knownRoutes(responses, mapper, known).routes(),
                                RerunRoutes.rerunRoutes(routes,
                                                        mapper,
                                                        RerunJob.rerunJob(RerunJob.Steps.steps(jobs,
                                                                                               results,
                                                                                               submit,
                                                                                               treeReader,
                                                                                               ControllerConfig.Units.RERUN_INSTALL_DEFAULT)))
                                           .routes())
                            .flatMap(List::stream)
                            .toList();
        router = router(Option.some(READ_TOKEN));
    }

    /// A router over the same routes with the full token and `readToken`, if any.
    Router router(Option<String> readToken) {
        return Router.router(Credentials.credentials(BearerToken.bearerToken(TOKEN).unwrap(),
                                                     readToken.map(raw -> BearerToken.bearerToken(raw).unwrap())),
                             routes,
                             responses);
    }

    /// A link that is only read: no process runner, worker jar or evidence, since nothing here starts it.
    private LinkEnv linkEnv(JsonMapper mapper) {
        return LinkEnv.linkEnv(null,
                               mapper,
                               units,
                               jobs,
                               new FakeStores.Hosts(),
                               wake,
                               "0.1.0",
                               "git@example:repo.git",
                               1024,
                               Option.none(),
                               Option.none(),
                               Duration.ofSeconds(30),
                               Duration.ofSeconds(600),
                               null,
                               LinkTiming.DEFAULT,
                               OwnerTag.ownerTag("temper").unwrap(),
                               InstanceId.DEFAULT,
                               null);
    }

    int refusalsRecorded() {
        return jobs.refusals.size();
    }

    private Unit record(List<UnitRef> refsToKill) {
        cancelled.add(refsToKill);

        return unit();
    }

    ApiResponse call(HttpMethod method, String pathAndQuery, String body, Map<String, String> headers) {
        return call(router, method, pathAndQuery, body, headers);
    }

    static ApiResponse call(Router router,
                            HttpMethod method,
                            String pathAndQuery,
                            String body,
                            Map<String, String> headers) {
        return router.handle(ApiRequest.apiRequest(request(method, pathAndQuery, body, headers)))
                     .await()
                     .unwrap();
    }

    ApiResponse readAuthed(HttpMethod method, String pathAndQuery, String body) {
        return call(method, pathAndQuery, body, Map.of("Authorization", "Bearer " + READ_TOKEN));
    }

    ApiResponse authed(HttpMethod method, String pathAndQuery, String body) {
        return call(method, pathAndQuery, body, Map.of("Authorization", "Bearer " + TOKEN));
    }

    ApiResponse submit(String json) {
        return authed(HttpMethod.POST, "/api/v1/jobs", json);
    }

    static HttpRequest request(HttpMethod method, String pathAndQuery, String body, Map<String, String> headers) {
        var parts = pathAndQuery.split("\\?", 2);
        var query = parts.length > 1
                    ? QueryParams.queryParams(java.util.Arrays.stream(parts[1].split("&"))
                                                              .map(pair -> pair.split("=", 2))
                                                              .collect(java.util.stream.Collectors.toMap(pair -> pair[0],
                                                                                                         pair -> List.of(pair.length > 1
                                                                                                                         ? pair[1]
                                                                                                                         : ""))))
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
}
