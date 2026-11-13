package org.pragmatica.temper.controller.it;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.temper.controller.t3.TicketTransport;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.temper.controller.Controller;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fake.FakeSsh;
import org.pragmatica.temper.controller.fake.FakeWorker;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.submit.GitError;
import org.pragmatica.temper.controller.submit.GitRemote;
import org.pragmatica.temper.controller.submit.Mirror;
import org.pragmatica.temper.controller.submit.PullNumber;
import org.pragmatica.temper.controller.submit.TreeReader;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.domain.model.Ref;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.refusal.Refusal;
import org.pragmatica.temper.domain.refusal.RefusalCode;
import org.pragmatica.temper.domain.refusal.TreeFacts;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.Store;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// A running controller on a fresh Postgres database, fake git, and one host `bb` behind a fake worker that runs
/// every unit at once: `UnitStarted`, then `UnitExited` (rc 0) and one report chunk with one passing testcase.
final class ControllerHarness implements AutoCloseable {
    static final String TOKEN = "it-token";
    /// The worker token (ruling R-M3-15): READ routes and `claim`.
    static final String WORKER_TOKEN = "it-worker-token";
    /// A judged build version (D-6), so the runs cross the split between it and the runner version `0.1.0`.
    static final String VERSION = "0.1.0+0123456789ab";
    static final String SHA_A = "a".repeat(40);
    static final String SHA_B = "b".repeat(40);
    static final HostName HOST = HostName.hostName("bb").unwrap();

    static final LinkTiming TIMING = LinkTiming.linkTiming(timeSpan(10).seconds(),
                                                           Duration.ofSeconds(120),
                                                           timeSpan(1).seconds(),
                                                           timeSpan(5).seconds(),
                                                           timeSpan(5).seconds(),
                                                           timeSpan(5).seconds(),
                                                           timeSpan(5).seconds(),
                                                           BackoffStrategy.exponential()
                                                                          .initialDelay(timeSpan(50).millis())
                                                                          .maxDelay(timeSpan(200).millis())
                                                                          .factor(2.0)
                                                                          .withoutJitter(),
                                                           Duration.ofSeconds(60),
                                                           Collections.nCopies(4, timeSpan(1).millis()),
                                                           timeSpan(5).seconds(),
                                                           LinkTiming.DEFAULT.unrecordedAfter());

    final Map<String, String> refs = new ConcurrentHashMap<>(Map.of("main", SHA_A));
    /// Shas the fake mirror treats as ancestors of a branch's tip (a lane checkpoint's `pushedSha`), by branch.
    final Map<String, Set<String>> onBranch = new ConcurrentHashMap<>();
    /// While set, the fake mirror's `branchContains` fails as a broken mirror or unreachable origin does (a [GitError]).
    volatile boolean mirrorDown;
    final Store store;
    final FakeSsh ssh;
    final Controller.Running running;
    final FakeWorker worker;
    final HttpClient http = HttpClient.newHttpClient();
    final String base;
    final Path evidenceRoot;
    final boolean ownsStore;

    private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();

    final TimeSpan pollWait;

    ControllerHarness(boolean withHost, TimeSpan pollWait) throws Exception {
        this(Pg.freshStore(),
             true,
             withHost,
             pollWait,
             UnaryOperator.identity(),
             Instant::now,
             none(),
             VERSION,
             UnaryOperator.identity(),
             List.of(),
             Duration.ofSeconds(600),
             TIMING);
    }

    /// A second controller, of build `version` and without a host, on `store` (another harness's, which keeps it).
    ControllerHarness(Store store, String version, TimeSpan pollWait) throws Exception {
        this(store,
             false,
             false,
             pollWait,
             UnaryOperator.identity(),
             Instant::now,
             none(),
             version,
             UnaryOperator.identity(),
             List.of(),
             Duration.ofSeconds(600),
             TIMING);
    }

    /// With a host, over `git` instead of the fake git (a real [org.pragmatica.temper.controller.git.GitCli]).
    ControllerHarness(Controller.Git git, TimeSpan pollWait) throws Exception {
        this(Pg.freshStore(),
             true,
             true,
             pollWait,
             UnaryOperator.identity(),
             Instant::now,
             Option.some(git),
             VERSION,
             UnaryOperator.identity(),
             List.of(),
             Duration.ofSeconds(600),
             TIMING);
    }

    /// With a host, reading time from `clock`, and `[fleet] laneStaleMin` as `laneStaleMin` (lane registry `stale`).
    ControllerHarness(TimeSpan pollWait, Supplier<Instant> clock, int laneStaleMin) throws Exception {
        this(Pg.freshStore(),
             true,
             true,
             pollWait,
             UnaryOperator.identity(),
             clock,
             none(),
             VERSION,
             UnaryOperator.identity(),
             List.of(),
             Duration.ofSeconds(600),
             TIMING,
             _ -> none(),
             laneStaleMin);
    }

    /// With a host; the controller works on `seen` of the fresh store ([#store] stays the store itself) and reads its
    /// time from `clock`.
    ControllerHarness(TimeSpan pollWait, UnaryOperator<Store> seen, Supplier<Instant> clock) throws Exception {
        this(Pg.freshStore(),
             true,
             true,
             pollWait,
             seen,
             clock,
             none(),
             VERSION,
             UnaryOperator.identity(),
             List.of(),
             Duration.ofSeconds(600),
             TIMING);
    }

    /// With a host configured as `host` makes of the default one (m4-design §10: `retireAt`), reading time from `clock`.
    ControllerHarness(TimeSpan pollWait, Supplier<Instant> clock, UnaryOperator<ControllerConfig.Host> host) throws Exception {
        this(Pg.freshStore(),
             true,
             true,
             pollWait,
             UnaryOperator.identity(),
             clock,
             none(),
             VERSION,
             host,
             List.of(),
             Duration.ofSeconds(600),
             TIMING);
    }

    /// With a host configured as `host` makes of the default one and the `more` hosts, reading time from `clock`
    /// (#107: a retiring host beside one that is not). Each other host's worker is [FakeSsh#nextWorker(String)].
    ControllerHarness(TimeSpan pollWait,
                      Supplier<Instant> clock,
                      UnaryOperator<ControllerConfig.Host> host,
                      List<ControllerConfig.Host> more) throws Exception {
        this(Pg.freshStore(),
             true,
             true,
             pollWait,
             UnaryOperator.identity(),
             clock,
             none(),
             VERSION,
             host,
             more,
             Duration.ofSeconds(600),
             TIMING);
    }

    /// As the `more` constructor, reading time from `clock` and the tickets of each host over `transports` (#107 e2e).
    ControllerHarness(TimeSpan pollWait,
                      Supplier<Instant> clock,
                      UnaryOperator<ControllerConfig.Host> host,
                      Fn1<Option<TicketTransport>, String> transports) throws Exception {
        this(Pg.freshStore(),
             true,
             true,
             pollWait,
             UnaryOperator.identity(),
             clock,
             none(),
             VERSION,
             host,
             List.of(),
             Duration.ofSeconds(600),
             TIMING,
             transports);
    }

    /// With the default host and the `more` hosts, whose links are LOST after `hostLost` down (m4-design §2.1). The
    /// default host's worker said `Hello` ([#worker]); each other host's worker is [FakeSsh#nextWorker(String)].
    /// Over [#TIMING] with `stableUp` replaced, so a host's down clock clears within the test.
    ControllerHarness(TimeSpan pollWait, List<ControllerConfig.Host> more, Duration hostLost, Duration stableUp) throws Exception {
        this(Pg.freshStore(),
             true,
             true,
             pollWait,
             UnaryOperator.identity(),
             Instant::now,
             none(),
             VERSION,
             UnaryOperator.identity(),
             more,
             hostLost,
             LinkTiming.linkTiming(TIMING.hello(),
                                   TIMING.silence(),
                                   TIMING.livenessCheck(),
                                   TIMING.request(),
                                   TIMING.prepare(),
                                   TIMING.start(),
                                   TIMING.deploy(),
                                   TIMING.backoff(),
                                   stableUp,
                                   TIMING.startRetry(),
                                   TIMING.startAttemptTimeout(),
                                   TIMING.unrecordedAfter()));
    }

    /// As the full constructor with the config's own ticket transports (ssh).
    private ControllerHarness(Store store,
                              boolean ownsStore,
                              boolean withHost,
                              TimeSpan pollWait,
                              UnaryOperator<Store> seen,
                              Supplier<Instant> clock,
                              Option<Controller.Git> git,
                              String version,
                              UnaryOperator<ControllerConfig.Host> host,
                              List<ControllerConfig.Host> more,
                              Duration hostLost,
                              LinkTiming timing) throws Exception {
        this(store, ownsStore, withHost, pollWait, seen, clock, git, version, host, more, hostLost, timing, _ -> none());
    }

    private ControllerHarness(Store store,
                              boolean ownsStore,
                              boolean withHost,
                              TimeSpan pollWait,
                              UnaryOperator<Store> seen,
                              Supplier<Instant> clock,
                              Option<Controller.Git> git,
                              String version,
                              UnaryOperator<ControllerConfig.Host> host,
                              List<ControllerConfig.Host> more,
                              Duration hostLost,
                              LinkTiming timing,
                              Fn1<Option<TicketTransport>, String> transports) throws Exception {
        this(store,
             ownsStore,
             withHost,
             pollWait,
             seen,
             clock,
             git,
             version,
             host,
             more,
             hostLost,
             timing,
             transports,
             ControllerConfig.Fleet.DEFAULT_LANE_STALE_MIN);
    }

    private ControllerHarness(Store store,
                              boolean ownsStore,
                              boolean withHost,
                              TimeSpan pollWait,
                              UnaryOperator<Store> seen,
                              Supplier<Instant> clock,
                              Option<Controller.Git> git,
                              String version,
                              UnaryOperator<ControllerConfig.Host> host,
                              List<ControllerConfig.Host> more,
                              Duration hostLost,
                              LinkTiming timing,
                              Fn1<Option<TicketTransport>, String> transports,
                              int laneStaleMin) throws Exception {
        this.store = store;
        this.ownsStore = ownsStore;
        this.pollWait = pollWait;
        ssh = new FakeSsh(this::respond);
        var port = freePort();
        var jar = Files.createTempFile("worker", ".jar");

        evidenceRoot = Files.createTempDirectory("temper-evidence");
        var config = ControllerConfig.controllerConfig(version,
                                                       ControllerConfig.Server.server("0.0.0.0",
                                                                                      port,
                                                                                      Path.of("/unused"),
                                                                                      Option.none(),
                                                                                      InstanceId.DEFAULT),
                                                       ControllerConfig.Database.database("x",
                                                                                          1,
                                                                                          "x",
                                                                                          "x",
                                                                                          Path.of("/unused")),
                                                       ControllerConfig.Repo.repo("git@example:repo.git",
                                                                                  Path.of("/unused")),
                                                       ControllerConfig.Scheduling.scheduling(Duration.ofMillis(200),
                                                                                              Duration.ofSeconds(1200),
                                                                                              Duration.ofSeconds(30),
                                                                                              hostLost),
                                                       ControllerConfig.Units.units(MemoryMb.memoryMb(6144).unwrap(),
                                                                                    MemoryMb.GATE_DEFAULT,
                                                                                    1024,
                                                                                    1L<< 30,
                                                                                    none(),
                                                                                    none(),
                                                                                    ControllerConfig.Units.RERUN_INSTALL_DEFAULT),
                                                       ControllerConfig.PortGroup.portGroup(List.of(),
                                                                                            OwnerTag.ownerTag("temper").unwrap(),
                                                                                            1),
                                                       ControllerConfig.Worker.worker(jar),
                                                       withHost
                                                       ? java.util.stream.Stream.concat(java.util.stream.Stream.of(host.apply(ControllerConfig.Host.host(HOST,
                                                                                                                                                         "localhost",
                                                                                                                                                         "builder",
                                                                                                                                                         Path.of("/k"),
                                                                                                                                                         Path.of("/kh"),
                                                                                                                                                         5,
                                                                                                                                                         20480,
                                                                                                                                                         false))),
                                                                                        more.stream())
                                                                                .toList()
                                                       : List.of(),
                                                       ControllerConfig.Evidence.evidence(evidenceRoot,
                                                                                          1L << 20,
                                                                                          1L << 20,
                                                                                          0),
                                                       ControllerConfig.Retention.retention(Duration.ofHours(1)),
                                                       ControllerConfig.PgDump.pgDump(LocalTime.of(3, 30), "temper-pg"),
                                                       ControllerConfig.Fleet.fleet(none(),
                                                                                    evidenceRoot.resolveSibling("temper-state"),
                                                                                    List.of(),
                                                                                    none(),
                                                                                    none(),
                                                                                    ControllerConfig.Fleet.DEFAULT_QUEUE_WAIT_ALARM_MIN,
                                                                                    ControllerConfig.Fleet.DEFAULT_PORTGROUP_STALL_ALARM_MIN,
                                                                                    ControllerConfig.Fleet.DEFAULT_PORTGROUP_HELD_AGE_ALARM_MIN,
                                                                                    laneStaleMin));
        GitRemote remote = ref -> refs.containsKey(ref.value())
                                  ? Sha.sha(refs.get(ref.value())).async()
                                  : Refusal.refusal(RefusalCode.SOURCE, "no such ref").promise();
        var mirror = new Mirror() {
            @Override
            public Promise<Unit> fetch() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Boolean> containedInBranch(Sha sha) {
                return Promise.success(refs.containsValue(sha.value()));
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
                return mirrorDown
                       ? GitError.Failed.FACTORY.apply("fetch",
                                                       Causes.cause("origin unreachable"))
                                                .promise()
                       : Promise.success(sha.value()
                                            .equals(refs.get(branch.value())) || onBranch.getOrDefault(branch.value(),
                                                                                                       Set.of())
                                                                                         .contains(sha.value()));
            }
        };
        var tree = new TreeReader() {
            @Override
            public Promise<TreeFacts> facts(Sha sha, Set<String> names) {
                return Promise.success(TreeFacts.EMPTY);
            }

            /// No tree files: the rerun bound falls back from history to its default, never to weights.
            @Override
            public Promise<Option<byte[]>> file(Sha sha, String path) {
                return Promise.success(Option.none());
            }
        };

        running = Controller.controller(config,
                                        seen.apply(store),
                                        Credentials.credentials(BearerToken.bearerToken(TOKEN).unwrap(),
                                                                Option.none(),
                                                                Option.some(BearerToken.bearerToken(WORKER_TOKEN).unwrap())),
                                        ssh,
                                        git.or(() -> Controller.Git.git(remote,
                                                                        mirror,
                                                                        tree,
                                                                        pull -> Causes.cause("no PR heads in the fake git").promise())),
                                        timing,
                                        pollWait,
                                        clock,
                                        transports)
                            .start()
                            .await(Pg.WAIT)
                            .unwrap();
        base = "http://127.0.0.1:" + port + "/api/v1";
        worker = withHost
                 ? hello(ssh.nextWorker("localhost"))
                 : null;
    }

    private static FakeWorker hello(FakeWorker worker) {
        worker.send(new Message.Hello(Handshake.PROTOCOL_VERSION, VERSION, "bb", 1000, 1000, 999, 32, facts(), List.of()));

        return worker;
    }

    static HostFactsDto facts() {
        return new HostFactsDto(0.5,
                                0.5,
                                32,
                                0,
                                0,
                                none(),
                                none(),
                                1_000_000,
                                100L << 30,
                                100L << 30,
                                Instant.now(),
                                List.of());
    }

    /// The fake worker: prepare at once; start, then exit with one passing testcase. A unit whose args contain
    /// `held` only starts (it runs until cancelled). A unit with a manifest spec also delivers a manifest and reports
    /// against it: [#MANIFEST], which reports the optional `org.x.BarTest` besides `org.x.FooTest`.
    List<Message> respond(Message request) {
        return switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit start when start.manifest().isPresent() -> List.of(started(start),
                                                                                      exited(start.job(), start.unit()),
                                                                                      new Message.UnitManifest(start.job(),
                                                                                                               start.unit(),
                                                                                                               1,
                                                                                                               0,
                                                                                                               true,
                                                                                                               MANIFEST),
                                                                                      reportWithManifest(start));
            case Message.StartUnit start -> start.steps().stream().anyMatch(step -> step.contains("held"))
                                            ? List.of(started(start))
                                            : List.of(started(start),
                                                      exited(start.job(), start.unit()),
                                                      report(start.job(), start.unit()));
            case Message.Welcome _, Message.UnitReportAck _, Message.Shutdown _ -> List.of();
            default -> List.of(new Message.Ack(none()));
        };
    }

    private static Message started(Message.StartUnit start) {
        return new Message.UnitStarted(start.job(),
                                       start.unit(),
                                       1,
                                       "c" + start.job(),
                                       "sha256:img",
                                       1_700_000_000_000_000_000L,
                                       Instant.now(),
                                       facts(),
                                       none());
    }

    /// The Testcontainers reap every fake unit reports (m0-design §12.7).
    static final TcLeftoversDto LEFTOVERS = new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto("tc-left", "s-9")),
                                                               none());

    static Message exited(long job) {
        return exited(job, "main");
    }

    /// The unit `unit` (a Gate's is `gate`) exits 0.
    static Message exited(long job, String unit) {
        return new Message.UnitExited(job,
                                      unit,
                                      1,
                                      0,
                                      none(),
                                      Instant.now(),
                                      new UnitEndFactsDto(facts(), none(), Instant.now(), Map.of(), LEFTOVERS, none()),
                                      1,
                                      1,
                                      0,
                                      none(),
                                      none());
    }

    /// Required `org.x.FooTest`; optional `org.x.BarTest` (reports) and `org.x.SilentTest` (does not), ruling R-2.
    static final ManifestOutcomeDto MANIFEST = new ManifestOutcomeDto.Computed(List.of(new ManifestEntryDto("core",
                                                                                                            "org.x.FooTest",
                                                                                                            "surefire")),
                                                                               List.of(new ManifestEntryDto("core",
                                                                                                            "org.x.BarTest",
                                                                                                            "surefire"),
                                                                                       new ManifestEntryDto("core",
                                                                                                            "org.x.SilentTest",
                                                                                                            "surefire")),
                                                                               List.of(),
                                                                               List.of());

    private static Message reportWithManifest(Message.StartUnit start) {
        return new Message.UnitReport(start.job(),
                                      start.unit(),
                                      1,
                                      0,
                                      true,
                                      List.of(new TestcaseDto("org.x.FooTest",
                                                              "works",
                                                              "passed",
                                                              0.2,
                                                              start.unit(),
                                                              false),
                                              new TestcaseDto("org.x.BarTest",
                                                              "runs",
                                                              "passed",
                                                              0.1,
                                                              start.unit(),
                                                              false)));
    }

    static Message report(long job) {
        return report(job, "main");
    }

    static Message report(long job, String unit) {
        return new Message.UnitReport(job,
                                      unit,
                                      1,
                                      0,
                                      true,
                                      List.of(new TestcaseDto("org.x.FooTest", "works", "passed", 0.2, unit, false)));
    }

    /// `temperVersion` is the `Temper-Version` response header, empty when absent.
    record Reply(int status, String body, String temperVersion) {}

    Reply call(String method, String path, String body, boolean withToken) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                                 .timeout(Duration.ofSeconds(30))
                                 .method(method,
                                         body.isEmpty()
                                         ? HttpRequest.BodyPublishers.noBody()
                                         : HttpRequest.BodyPublishers.ofString(body));

        if (withToken) {
            builder.header("Authorization", "Bearer " + TOKEN);
        }

        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        return new Reply(response.statusCode(),
                         response.body(),
                         response.headers().firstValue("Temper-Version").orElse(""));
    }

    /// A full-token call with `headers` added (`Temper-Version`, D-6); `withToken` false sends no `Authorization`.
    Reply callWith(String method, String path, String body, boolean withToken, Map<String, String> headers) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(base + path))
                                 .timeout(Duration.ofSeconds(30))
                                 .method(method,
                                         body.isEmpty()
                                         ? HttpRequest.BodyPublishers.noBody()
                                         : HttpRequest.BodyPublishers.ofString(body));

        if (withToken) {
            builder.header("Authorization", "Bearer " + TOKEN);
        }

        headers.forEach(builder::header);
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        return new Reply(response.statusCode(),
                         response.body(),
                         response.headers().firstValue("Temper-Version").orElse(""));
    }

    Reply get(String path) throws IOException, InterruptedException {
        return call("GET", path, "", true);
    }

    /// A call with `authorization` as the whole `Authorization` value (`Bearer <worker token>`, `Lease <token>`).
    Reply callAs(String method, String path, String body, String authorization) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                                 .timeout(Duration.ofSeconds(30))
                                 .header("Authorization", authorization)
                                 .method(method,
                                         body.isEmpty()
                                         ? HttpRequest.BodyPublishers.noBody()
                                         : HttpRequest.BodyPublishers.ofString(body))
                                 .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());

        return new Reply(response.statusCode(),
                         response.body(),
                         response.headers().firstValue("Temper-Version").orElse(""));
    }

    Reply post(String path, String body) throws IOException, InterruptedException {
        return call("POST", path, body, true);
    }

    static String mvnJob(String source, String... args) {
        return "{\"submittedBy\":\"v2174\",\"source\":" + source
             + ",\"recipe\":{\"mvn\":{\"args\":[" + String.join(",",
                                                                java.util.Arrays.stream(args)
                                                                                .map(arg -> "\"" + arg + "\"")
                                                                                .toList())
             + "]}}}";
    }

    static long jobOf(Reply reply) {
        var matcher = java.util.regex.Pattern.compile("\"job\":(\\d+)").matcher(reply.body());

        if (!matcher.find()) {
            throw new AssertionError("no job id in " + reply);
        }

        return Long.parseLong(matcher.group(1));
    }

    String waitForState(long job, String state) throws Exception {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var last = "";

        while (System.nanoTime() < deadline) {
            last = get("/jobs/" + job).body();
            if (last.contains("\"state\":\"" + state + "\",\"verdict\"") || last.startsWith("{\"job\":" + job
                                                                                           + ",\"state\":\"" + state
                                                                                           + "\"")) {
                return last;
            }

            Thread.sleep(100);
        }

        throw new AssertionError("job " + job + " never reached " + state + "; last " + last);
    }

    static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void stopServer() {
        if (stopped.compareAndSet(false, true)) {
            running.stop().await(TimeSpan.timeSpan(10).seconds());
        }
    }

    /// Stops this controller (keeping the database) and starts a new one on it, without hosts.
    ControllerHarness restartOn(Store same) throws Exception {
        stopServer();

        return new ControllerHarness(same,
                                     false,
                                     false,
                                     pollWait,
                                     UnaryOperator.identity(),
                                     Instant::now,
                                     none(),
                                     VERSION,
                                     UnaryOperator.identity(),
                                     List.of(),
                                     Duration.ofSeconds(600),
                                     TIMING);
    }

    @Override
    public void close() {
        stopServer();
        if (ownsStore) {
            store.close();
        }
    }
}
