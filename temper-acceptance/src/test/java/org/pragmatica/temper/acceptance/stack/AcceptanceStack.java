package org.pragmatica.temper.acceptance.stack;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.acceptance.trust.ControllerApi;
import org.pragmatica.temper.acceptance.trust.Judge;
import org.pragmatica.temper.acceptance.trust.Processes;
import org.pragmatica.temper.acceptance.trust.TemperCli;
import org.pragmatica.temper.acceptance.trust.TemperSide;
import org.pragmatica.temper.controller.Controller;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.git.GitHubPulls;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.protocol.api.HostView;
import org.pragmatica.temper.protocol.api.JobView;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.HostName;
import org.pragmatica.temper.store.api.Store;

import static org.pragmatica.lang.Option.none;

/// The real temper, assembled for acceptance: the controller (real routes, real `GitCli` against a local origin of
/// the fixture project, a fresh Postgres database), one host (two for [#twoHosts]), and the CLI's shaded jar as the client the T1/T2
/// procedure uses. Two transports to the worker:
///
/// - **docker tier** ([#docker]): [LocalSsh] runs the real worker as a separate JVM on this machine, frames over
///   pipes, units in the runner image on the local docker daemon. No bb-hold: `[portGroup] modules` is empty.
/// - **host tier** ([#host]): the controller's real ssh to the host's `worker-launch`, bb-hold for the `hold` module.
/// - **two docker-tier hosts** ([#twoHosts], m4-design §12.3): two [LocalSsh] workers, each with its own HOME, behind
///   [LocalHosts], and a short `hostLost`, so a test can blackhole one host ([#blackhole]) and watch it go LOST and
///   recover. Both share the local docker daemon, so a unit's container name is one name for both hosts.
///
/// The version is derived from the shipped runner Dockerfile and launcher (as temper-worker's docker ITs do), so the
/// runner image is rebuilt exactly when either changes; the worker reports the same version.
public final class AcceptanceStack implements AutoCloseable {
    public static final String TOKEN = "acceptance-token";
    public static final HostName HOST = HostName.hostName("local").expect("acceptance setup");
    public static final HostName HOST_A = HostName.hostName("a").expect("acceptance setup");
    public static final HostName HOST_B = HostName.hostName("b").expect("acceptance setup");
    public static final TimeSpan WAIT = TimeSpan.timeSpan(60).seconds();

    /// A4: every acceptance store's job ids start at or above this offset (7e11). Unit containers and networks are
    /// named `temper-<job>-<unit>` with no instance in the name, and a worker adopts or removes a container by that
    /// name, so on a shared host THIS offset is what keeps acceptance units apart from the live controller's (whose ids
    /// count from 1 and are in the thousands); the `temper.instance` label only keeps reconcile from treating the other
    /// controller's running containers as its own. The live controller must still be stopped for a host-tier run: both
    /// share the `temper/runner:<version>` image tag and the port group.
    public static final long ACCEPTANCE_JOB_ID_OFFSET = 700_000_000_000L;

    /// The first job id of a fresh store varies within this span above the offset, so runs differ from each other.
    static final long ACCEPTANCE_JOB_ID_SPAN = 100_000_000_000L;
    /// `[scheduler] hostLostSeconds` of the one-host stacks: the production default (m4-design §2.1).
    static final Duration HOST_LOST = Duration.ofSeconds(600);
    private static volatile AcceptanceStack shared;

    public final Path dir;
    public final FixtureRepo fixture;
    public final Path workerHome;
    public final String version;
    public final ProcessRunner ssh;
    public final ControllerApi api;
    public final TemperCli cli;
    public final Judge judge;
    private final Store store;
    private final String database;
    /// The docker tier's hosts by name; empty on the host tier.
    private final Map<HostName, LocalSsh> locals;
    private final ControllerConfig config;
    private final Path cliHome;
    /// Docker tier only: the workspace seed is this run's own and is refreshed from each job; the host tier never
    /// writes the host's `~/.temper/seed-m2`.
    private final boolean ownSeed;
    private final Map<String, Long> gates = new ConcurrentHashMap<>();
    private volatile Controller.Running running;

    private AcceptanceStack(Path dir, FixtureRepo fixture, Path workerHome, String version, ProcessRunner ssh, Store store,
                            String database, Map<HostName, LocalSsh> locals, ControllerConfig config, Path cliHome,
                            boolean ownSeed) throws Exception {
        this.dir = dir;
        this.fixture = fixture;
        this.workerHome = workerHome;
        this.version = version;
        this.ssh = ssh;
        this.store = store;
        this.database = database;
        this.locals = Map.copyOf(locals);
        this.config = config;
        this.cliHome = cliHome;
        this.ownSeed = ownSeed;
        var processes = Processes.processes(ProcessRunner.processRunner());
        this.api = ControllerApi.controllerApi(URI.create(url()), TOKEN);
        this.cli = TemperCli.temperCli(processes,
                                       List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                               "-jar",
                                               System.getProperty("temper.cli.jar")),
                                       Map.of("HOME", cliHome.toString(),
                                              "TEMPER_URL", url(),
                                              "TEMPER_OWNER", "acceptance"));
        this.judge = Judge.judge(processes, api, Path.of("bb-report"));
        start();
    }

    /// The controller's root URL (`TEMPER_URL`).
    public String url() {
        return "http://127.0.0.1:" + config.server().port();
    }

    /// The CLI's HOME (its token file is `.config/temper/token` there).
    public Path cliHome() {
        return cliHome;
    }

    /// The JVM-wide docker-tier stack over every overlay (built once; closed when the JVM exits).
    public static synchronized AcceptanceStack docker() throws Exception {
        if (shared == null) {
            shared = docker("docker", List.of("junit3", "junit4", "crash", "badpom", "red", "rerun", "rerun-wide", "rerun-it", "rerun-crash", "rerun-nocompile"));
            Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(shared::close));
        }
        return shared;
    }

    static AcceptanceStack docker(String name, List<String> overlays) throws Exception {
        var dir = runDir(name);
        var version = runnerVersion();
        var workerHome = Files.createDirectories(dir.resolve("home"));
        seedFromCache(workerHome);
        var ssh = new LocalSsh(workerHome, version, dockerGid(), workerLaunch());
        var host = ControllerConfig.Host.host(HOST, "localhost", "acceptance", Path.of("/unused"), Path.of("/unused"), 2, 20480, true);
        return assembled(dir, overlays, version, workerHome, ssh, Map.of(HOST, ssh), List.of(host), List.of(), 1, true, HOST_LOST);
    }

    /// Two docker-tier hosts, [#HOST_A] and [#HOST_B] (ssh addresses `host-a`, `host-b`), each a [LocalWorker] with its
    /// own HOME, and `[scheduler] hostLostSeconds` = `hostLost` (m4-design §12.3). The workspace seed is read from the
    /// cache but never written back: a job may run on either host.
    public static AcceptanceStack twoHosts(String name, List<String> overlays, Duration hostLost) throws Exception {
        var dir = runDir(name);
        var version = runnerVersion();
        var dockerGid = dockerGid();
        var locals = new LinkedHashMap<HostName, LocalSsh>();
        var byAddress = new LinkedHashMap<String, LocalSsh>();
        var hosts = new ArrayList<ControllerConfig.Host>();
        for (var host : List.of(HOST_A, HOST_B)) {
            var home = Files.createDirectories(dir.resolve("home-" + host.value()));
            seedFromCache(home);
            var ssh = new LocalSsh(host.value(), home, version, dockerGid, workerLaunch());
            locals.put(host, ssh);
            byAddress.put("host-" + host.value(), ssh);
            hosts.add(ControllerConfig.Host.host(host, "host-" + host.value(), "acceptance", Path.of("/unused"), Path.of("/unused"), 2, 20480, true));
        }
        return assembled(dir, overlays, version, locals.get(HOST_A).workerHome(), new LocalHosts(byAddress), locals, hosts, List.of(), 1,
                         false, hostLost);
    }

    private static int dockerGid() throws Exception {
        return Integer.parseInt(Shell.ok("docker", "run", "--rm", "--quiet", "-v", "/var/run/docker.sock:/s", "alpine:3.20", "stat", "-c", "%g", "/s"));
    }

    private static Path workerLaunch() {
        return Path.of(System.getProperty("temper.ops.dir"), "worker-launch");
    }

    /// The host-tier stack: real ssh to `host`, bb-hold for the fixture's `hold` module, `maxHolding` 2 (so a second
    /// temper hold unit can wait in bb-hold behind the first, A11 case 3).
    public static AcceptanceStack host(String name, List<String> overlays, HostSettings settings) throws Exception {
        var dir = runDir(name);
        settings.installWorker(Path.of(System.getProperty("temper.worker.jar")));
        var host = ControllerConfig.Host.host(HOST, settings.address(), settings.user(), settings.key(), settings.knownHosts(), 2, 40960, true);
        return assembled(dir, overlays, settings.version(), settings.workerHome(), new RecordingRunner(ProcessRunner.processRunner()),
                         Map.of(), List.of(host), List.of("hold"), 2, false, HOST_LOST);
    }

    /// The first job id of a fresh acceptance store: in `[OFFSET, OFFSET + SPAN)` whatever the clock reads (A4).
    static long firstJobId(long nowMillis) {
        return ACCEPTANCE_JOB_ID_OFFSET + Math.floorMod(nowMillis, ACCEPTANCE_JOB_ID_SPAN);
    }

    private static AcceptanceStack assembled(Path dir, List<String> overlays, String version, Path workerHome, ProcessRunner ssh,
                                             Map<HostName, LocalSsh> locals, List<ControllerConfig.Host> hosts,
                                             List<String> portGroupModules, int maxHolding, boolean ownSeed,
                                             Duration hostLost) throws Exception {
        var fixture = FixtureRepo.create(dir, overlays);
        var opened = Pg.freshStore(firstJobId(System.currentTimeMillis()));
        var cliHome = Files.createDirectories(dir.resolve("cli-home"));
        var tokenFile = Files.createDirectories(cliHome.resolve(".config/temper")).resolve("token");
        Files.writeString(tokenFile, TOKEN + "\n");
        Files.setPosixFilePermissions(tokenFile, PosixFilePermissions.fromString("rw-------"));
        var config = ControllerConfig.controllerConfig(version,
                                                       ControllerConfig.Server.server("127.0.0.1", freePort(), tokenFile, Option.none(), InstanceId.instanceId("acceptance").expect("acceptance setup")),
                                                       ControllerConfig.Database.database("unused", 1, opened.database(), "temper", Path.of("/unused")),
                                                       ControllerConfig.Repo.repo(fixture.origin().toString(), dir.resolve("controller-mirror.git")),
                                                       ControllerConfig.Scheduling.scheduling(Duration.ofMillis(500),
                                                                                              Duration.ofSeconds(1200),
                                                                                              Duration.ofSeconds(120),
                                                                                              hostLost),
                                                       ControllerConfig.Units.units(MemoryMb.memoryMb(3072).expect("acceptance setup"),
                                                                                    MemoryMb.memoryMb(4096).expect("acceptance setup"),
                                                                                    1024,
                                                                                    1L << 30,
                                                                                    none(),
                                                                                    none(),
                                                                                    ControllerConfig.Units.RERUN_INSTALL_DEFAULT),
                                                       ControllerConfig.PortGroup.portGroup(portGroupModules, OwnerTag.ownerTag("temper").expect("acceptance setup"), maxHolding),
                                                       ControllerConfig.Worker.worker(Path.of(System.getProperty("temper.worker.jar"))),
                                                       hosts,
                                                       ControllerConfig.Evidence.evidence(Files.createDirectories(dir.resolve("evidence")),
                                                                                          256L << 20,
                                                                                          1L << 30,
                                                                                          0),
                                                       ControllerConfig.Retention.retention(Duration.ofHours(6)),
                                                       ControllerConfig.PgDump.pgDump(LocalTime.now().plusHours(12), "no-such-pg-container"));
        return new AcceptanceStack(dir, fixture, workerHome, version, ssh, opened.store(), opened.database(), locals, config, cliHome,
                                   ownSeed);
    }

    private void start() throws Exception {
        var git = GitCli.gitCli(ProcessRunner.processRunner(), config.repo().origin(), config.repo().mirror());
        running = Controller.controller(config,
                                        store,
                                        Credentials.credentials(BearerToken.bearerToken(TOKEN).expect("acceptance setup"), Option.none()),
                                        ssh,
                                        Controller.Git.git(git, git, git, GitHubPulls.gitHubPulls(config.repo().origin())),
                                        LinkTiming.DEFAULT,
                                        TimeSpan.timeSpan(5).seconds(),
                                        Instant::now)
                            .start()
                            .await(WAIT)
                            .expect("acceptance setup");
        awaitHostUp(Duration.ofMinutes(3));
    }

    /// Stops this controller (host links end: the worker shuts down, unit containers keep running) and starts a new
    /// one on the same database, as `systemctl restart` would.
    public void restartController() throws Exception {
        stopController();
        start();
    }

    /// Stops the controller only (A11's lost-start case: no worker is attached while bb-hold grants).
    public void stopController() {
        running.stop().await(WAIT);
    }

    public void startController() throws Exception {
        start();
    }

    /// `temper cancel <job>` through the CLI.
    public void cancel(long job) {
        var completed = cli.processes()
                           .run(cliArgv("cancel", String.valueOf(job)), cli.env(), TimeSpan.timeSpan(2).minutes())
                           .await()
                           .expect("acceptance setup");
        if (completed.rc() != 0) {
            throw new AssertionError("temper cancel " + job + " exited " + completed.rc() + ": " + completed.stderrText());
        }
    }

    /// Every configured host UP.
    public void awaitHostUp(Duration bound) throws Exception {
        var deadline = Instant.now().plus(bound);
        var last = "";
        while (Instant.now().isBefore(deadline)) {
            var hosts = hosts();
            last = hosts.toString();
            if (hosts.size() == config.hosts().size() && hosts.stream().allMatch(host -> host.state().equals("UP"))) {
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("host link never UP: " + last);
    }

    /// `GET /hosts`; empty when the controller does not answer 200.
    public List<HostView> hosts() throws Exception {
        var reply = HttpClient.newHttpClient()
                              .send(HttpRequest.newBuilder(URI.create(api.api() + "/hosts"))
                                               .header("Authorization", "Bearer " + TOKEN)
                                               .build(),
                                    HttpResponse.BodyHandlers.ofString());
        return reply.statusCode() == 200
               ? List.of(TemperJson.mapper().readString(reply.body(), HostView[].class).expect("acceptance setup"))
               : List.of();
    }

    /// `temper submit gate --sha <branch's sha> --fresh --prio 5`, then `temper wait`; the job id. Memoized per
    /// branch, so the docker-tier classes share one Gate of `main`.
    public long gate(String branch) {
        return gates.computeIfAbsent(branch, this::freshGate);
    }

    /// A Gate that is not shared: submitted and waited for.
    public long freshGate(String branch) {
        var job = submitGate(branch);
        awaited(job, Duration.ofMinutes(30));
        return job;
    }

    public long submitGate(String branch) {
        return cli.submitGate(fixture.sha(branch), 5).await().expect("acceptance setup");
    }

    /// `temper submit module-test --pl <pl> --sha <branch's sha> --fresh` through the CLI, then `temper wait`.
    public long moduleTest(String branch, String pl) throws Exception {
        var job = submitModuleTest(branch, pl);
        awaited(job, Duration.ofMinutes(30));
        return job;
    }

    /// `temper submit module-test --pl <pl> --sha <branch's sha> --fresh` through the CLI, not waited for.
    public long submitModuleTest(String branch, String pl) {
        var submitted = cli.processes()
                           .run(cliArgv("submit", "module-test", "--pl", pl, "--sha", fixture.sha(branch), "--fresh"),
                                cli.env(),
                                TimeSpan.timeSpan(2).minutes())
                           .await()
                           .expect("acceptance setup");
        return TemperCli.jobOf(submitted.stdoutText()).expect("acceptance setup");
    }

    /// `temper <args>` through the CLI's shaded jar: its exit code and output (m2-design §5.6 `RerunIT`).
    public Completed temper(String... args) {
        return cli.processes()
                  .run(cliArgv(args), cli.env(), TimeSpan.timeSpan(2).minutes())
                  .await()
                  .expect("acceptance setup");
    }

    /// The controller's store, for reads the API does not offer (the stored record's bytes, `finished_at`).
    public Store store() {
        return store;
    }

    private List<String> cliArgv(String... args) {
        var argv = new ArrayList<>(cli.command());
        argv.addAll(List.of(args));
        return argv;
    }

    /// `temper wait <job>`: the verdict line; the seed is refreshed from the job's workspace afterwards.
    public String awaited(long job, Duration bound) {
        var line = cli.waitFor(job, TimeSpan.fromDuration(bound)).await().expect("acceptance setup");
        refreshSeed(job);
        return line;
    }

    public JobView job(long job) {
        return api.job(job).await().expect("acceptance setup");
    }

    /// temper's record, rows, manifest and log tail (the evidence's `log.tail` when it was pulled).
    public TemperSide side(long job) {
        return judge.temperSide(job).await().expect("acceptance setup");
    }

    public Path workspace(long job) {
        return workerHome.resolve(".temper/jobs/" + job + "/ws");
    }

    public Path unitDir(long job, String unit) {
        return workerHome.resolve(".temper/jobs/" + job + "/units/" + unit);
    }

    /// A docker-tier host's worker HOME.
    public Path workerHome(HostName host) {
        return local(host).workerHome();
    }

    public Path unitDir(HostName host, long job, String unit) {
        return workerHome(host).resolve(".temper/jobs/" + job + "/units/" + unit);
    }

    /// The docker-tier host stops answering ([LocalSsh#blackhole]).
    public void blackhole(HostName host) {
        local(host).blackhole();
    }

    public void restore(HostName host) {
        local(host).restore();
    }

    private LocalSsh local(HostName host) {
        var ssh = locals.get(host);
        if (ssh == null) {
            throw new IllegalArgumentException("no docker-tier host " + host);
        }
        return ssh;
    }

    /// One SQL statement on the controller's database (`psql -tA` output), for rows the API neither writes nor shows.
    public String sql(String statement) {
        return Pg.psql(database, statement);
    }

    public Path evidenceRoot() {
        return config.evidence().root();
    }

    @Override
    public void close() {
        try {
            if (running != null) {
                running.stop().await(WAIT);
            }
            saveSeedCache();
        } catch (Exception ignored) {
            // best effort at JVM exit
        }
        store.close();
    }

    // ---- seed: the Maven repository a workspace starts from ------------------------------------------------------

    private static Path seedCache() {
        return Path.of(System.getProperty("temper.acceptance.target", "target"), "acceptance-seed-m2").toAbsolutePath();
    }

    /// The worker's seed starts as the persistent cache (empty on the first run: the build then downloads online).
    private static void seedFromCache(Path workerHome) throws Exception {
        var seed = Files.createDirectories(workerHome.resolve(".temper/seed-m2"));
        if (Files.isDirectory(seedCache())) {
            Shell.ok("cp", "-R", seedCache() + "/.", seed.toString());
        }
    }

    /// What a job downloaded joins the seed for the next job (the fixture's own artifacts excepted).
    private void refreshSeed(long job) {
        var repo = workspace(job).resolve(".m2-local");
        if (!ownSeed || !Files.isDirectory(repo)) {
            return;
        }
        try {
            var seed = workerHome.resolve(".temper/seed-m2");
            Shell.ok("cp", "-R", repo + "/.", seed.toString());
            Shell.ok("rm", "-rf", seed.resolve("fx").toString());
        } catch (Exception e) {
            throw new AssertionError("seed refresh failed", e);
        }
    }

    private void saveSeedCache() throws Exception {
        var seed = workerHome.resolve(".temper/seed-m2");
        if (ownSeed && Files.isDirectory(seed)) {
            Files.createDirectories(seedCache());
            Shell.ok("cp", "-R", seed + "/.", seedCache().toString());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static Path runDir(String name) throws IOException {
        return Files.createDirectories(Path.of(System.getProperty("temper.acceptance.target", "target"),
                                               "acceptance",
                                               name + "-" + System.currentTimeMillis())
                                           .toAbsolutePath());
    }

    static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /// `it-<sha256 of the runner Dockerfile and launcher>[:12]`, as temper-worker's docker ITs derive it.
    static String runnerVersion() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var name : List.of("/runner/Dockerfile", "/runner/RunSteps.java")) {
            try (var in = AcceptanceStack.class.getResourceAsStream(name)) {
                digest.update(in.readAllBytes());
            }
        }
        return "it-" + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
    }

}
