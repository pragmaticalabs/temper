package org.pragmatica.temper.acceptance.stack;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.Controller;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.git.GitCli;
import org.pragmatica.temper.controller.git.GitHubPulls;
import org.pragmatica.temper.controller.host.LinkTiming;
import org.pragmatica.temper.controller.http.BearerToken;
import org.pragmatica.temper.controller.http.Credentials;
import org.pragmatica.temper.controller.t3.LocalTicketTransport;
import org.pragmatica.temper.domain.model.InstanceId;
import org.pragmatica.temper.domain.model.MemoryMb;
import org.pragmatica.temper.domain.model.OwnerTag;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.store.api.Store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// CI-T3 WP-E (T3.1.m and T3.2): the real chain on one machine. A `claude`-named bash (a copy of
/// `/bin/bash`) runs `ops/mac/temper-mac session` and the real `ops/bb/bb-build` (the only stub is mvn, never reached: the
/// ticket stays `waiting` under `BB_MAX_REACTORS=0`); the real controller (Postgres) reads the real `ops/bb/bb-tickets`
/// through [LocalTicketTransport]; the real `temper-mac post` with the real `ps` posts to it with the MAC token; the real
/// CLI (`temper tickets`, its shaded jar) prints the line. Everything runs under `TZ=Pacific/Chatham` (UTC+12:45/13:45).
///
/// The ticket's `session_start` and the post's `start` come from one function in temper-mac, so a common error in it keeps
/// the CLI line ALIVE; [#session_isAliveWhileTheStandInRuns] therefore also compares the ticket's start with the JVM's own
/// reading of the stand-in's start (an independent clock), which is what turns the dropped-`-u` mutant red.
///
/// Probes (C10): liveness always DEAD -> [#session_isAliveWhileTheStandInRuns] red; the UTC conversion of the start dropped
/// (`-u` and `TZ=UTC`) -> the same test red; the liveness read from `mac_session` (every (pid, start) ever seen) ->
/// [#killedSession_isDeadWithTheLastReceiptTime] red.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class SessionChainIT {
    private static final String TOKEN = "chain-token";
    private static final String MAC_TOKEN = "chain-mac-token";
    private static final String TZ = "Pacific/Chatham";
    private static final String HOST = "bigboy";
    private static final Duration SHORT = Duration.ofSeconds(60);
    private static final Path OPS = Path.of(System.getProperty("temper.ops.dir")).toAbsolutePath().normalize();
    private static final Pattern QUEUED = Pattern.compile("queued ticket=(\\S+)");
    private static final Pattern MAC_LAST_OK = Pattern.compile("\"mac\"\\s*:\\s*\\{\\s*\"lastOkAt\"\\s*:\\s*\"([^\"]+)\"");
    private static final String KEY_LINE = "127.0.0.1 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAISessionChainITSessionChainITSessionCh\n";

    private Path dir;
    private Path cliHome;
    private Path queue;
    private FixtureRepo fixture;
    private Store store;
    private String database;
    private ControllerConfig config;
    private Controller.Running running;
    private Process standIn;
    private String ticket;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createDirectories(Path.of(System.getProperty("temper.acceptance.target", "target"),
                                              "acceptance",
                                              "chain-" + System.nanoTime())
                                          .toAbsolutePath());
        cliHome = Files.createDirectories(dir.resolve("cli-home"));
        queue = Files.createDirectories(dir.resolve("queue"));
        token(cliHome.resolve(".config/temper/token"), TOKEN);
        token(dir.resolve("mac-token"), MAC_TOKEN);
        fixture = FixtureRepo.create(dir, List.of());
        var opened = Pg.freshStore(AcceptanceStack.firstJobId(System.currentTimeMillis()));
        store = opened.store();
        database = opened.database();
        config = config();
        running = start().await(TimeSpan.timeSpan(60).seconds()).expect("controller start");
    }

    @AfterEach
    void tearDown() {
        if (standIn != null) {
            standIn.destroyForcibly();
        }
        waiters(ticket).forEach(handle -> {
            handle.destroyForcibly();
            handle.onExit().orTimeout(10, TimeUnit.SECONDS).exceptionally(_ -> null).join();
        });
        if (running != null) {
            running.stop().await(TimeSpan.timeSpan(60).seconds());
        }
        store.close();
    }

    /// T3.1.m: the CLI prints SESSION-ALIVE for the ticket the stand-in's bb-build queued, and the ticket's start is the
    /// stand-in's own start (the JVM's reading, not temper-mac's).
    @Test
    void session_isAliveWhileTheStandInRuns() throws Exception {
        queueTicketFromStandIn();
        post();
        awaitCollected();

        var line = ticketLine();

        assertThat(line).as(line).startsWith("  waiting " + ticket + " | SESSION-ALIVE | ");
        assertThat(sessionStart()).as("ticket session_start against ProcessHandle start")
                                  .isBetween(javaStart() - 2, javaStart() + 2);
    }

    /// T3.2: after `kill -9` of the stand-in the next post makes the row `DEAD(last seen <t>)`, t = the previous post's
    /// receipt time as the controller recorded it (`mac.lastOkAt` of the tickets route, same second truncation; the CLI's
    /// `last-post` is the Mac's clock before curl and can straddle a second boundary).
    @Test
    void killedSession_isDeadWithTheLastReceiptTime() throws Exception {
        queueTicketFromStandIn();
        post();
        awaitCollected();
        assertThat(ticketLine()).contains(" | SESSION-ALIVE | ");
        var receipt = controllerReceipt();

        standIn.destroyForcibly();
        assertThat(standIn.waitFor(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(1500);
        post();

        assertThat(ticketLine()).startsWith("  waiting " + ticket + " | DEAD(last seen " + receipt + ") | ");
    }

    // ---- the chain ---------------------------------------------------------------------------------------------------

    /// The stand-in: `temper-mac session` as its direct child, then the real `bb-build` with that session in its
    /// environment; it then stays alive until killed.
    private void queueTicketFromStandIn() throws Exception {
        var claude = dir.resolve("bin/claude");
        Files.createDirectories(claude.getParent());
        Files.copy(Path.of("/bin/bash"), claude, StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(claude, PosixFilePermissions.fromString("rwxr-xr-x"));
        var mvn = executable(dir.resolve("stub/mvn"), "#!/bin/sh\necho stubbed \"$@\"\n");
        var checkout = checkout(Files.createDirectories(dir.resolve("work")).resolve("t3e-co"));
        var session = dir.resolve("session.txt");
        var built = dir.resolve("built.txt");
        var script = String.join("\n",
                                 "\"" + OPS.resolve("mac/temper-mac") + "\" session > \"" + session + "\" || exit 9",
                                 "read -r line < \"" + session + "\"",
                                 "export $line",
                                 "\"" + OPS.resolve("bb/bb-build") + "\" \"" + checkout + "\" -B test > \"" + built + "\" 2>&1",
                                 "while :; do sleep 1; done");
        var env = new java.util.LinkedHashMap<String, String>();
        env.put("PATH", "/usr/bin:/bin");
        env.put("HOME", Files.createDirectories(dir.resolve("home")).toString());
        env.put("USER", System.getProperty("user.name"));
        env.put("TZ", TZ);
        env.put("BB_OWNER", "t3e");
        env.put("BB_TEST_QUEUE_ROOT", queue.toString());
        env.put("BB_TEST_PRIORITIES", dir.resolve("none-priorities").toString());
        env.put("BB_TEST_PORT_MODULES", dir.resolve("none-modules").toString());
        env.put("BB_TEST_REACTOR_PATTERN", "[n]o-such-maven-reactor");
        env.put("BB_TEST_MVN", mvn.toString());
        env.put("BB_PORT_GROUP", "0");
        env.put("BB_MAX_REACTORS", "0");
        var builder = HermeticProcess.builder(claude.toString(), "-c", script);
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.directory(dir.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        standIn = builder.start();
        until(() -> read(built).contains("queued ticket="), SHORT, "bb-build queued a ticket: " + read(built) + read(session));
        var found = QUEUED.matcher(read(built));
        assertThat(found.find()).as(read(built)).isTrue();
        ticket = found.group(1);
    }

    /// `temper-mac post` as launchd runs it: the real `ps`, the MAC token, the controller's URL.
    private void post() throws Exception {
        var builder = HermeticProcess.builder("/bin/bash", OPS.resolve("mac/temper-mac").toString(), "post");
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin",
                                            "HOME", dir.resolve("home").toString(),
                                            "TZ", TZ,
                                            "TEMPER_MAC_URL", url(),
                                            "TEMPER_MAC_TOKEN", dir.resolve("mac-token").toString(),
                                            "TEMPER_MAC_STATE", dir.resolve("mac-state").toString(),
                                            "TEMPER_MAC_LOG", dir.resolve("mac.log").toString()));
        builder.redirectErrorStream(true);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("temper-mac post: " + output + read(dir.resolve("mac.log"))).isZero();
    }

    /// The controller's collection round (30 s) has listed the ticket.
    private void awaitCollected() throws Exception {
        until(() -> {
            try {
                var reply = HttpClient.newHttpClient()
                                      .send(HttpRequest.newBuilder(URI.create(url() + "/api/v1/fleet/tickets?host=" + HOST))
                                                       .header("Authorization", "Bearer " + TOKEN)
                                                       .build(),
                                            HttpResponse.BodyHandlers.ofString());
                return reply.statusCode() == 200 && reply.body().contains(ticket);
            } catch (Exception e) {
                return false;
            }
        }, Duration.ofSeconds(120), "the controller collected the ticket " + ticket);
    }

    private String controllerReceipt() throws Exception {
        var reply = HttpClient.newHttpClient()
                              .send(HttpRequest.newBuilder(URI.create(url() + "/api/v1/fleet/tickets?host=" + HOST))
                                               .header("Authorization", "Bearer " + TOKEN)
                                               .build(),
                                    HttpResponse.BodyHandlers.ofString());
        var found = MAC_LAST_OK.matcher(reply.body());

        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(200);
        assertThat(found.find()).as(reply.body()).isTrue();
        return found.group(1);
    }

    private String ticketLine() throws Exception {
        return cliLines("tickets", "--host", HOST).stream()
                                                  .filter(line -> line.contains(" " + ticket + " "))
                                                  .findFirst()
                                                  .orElseThrow(() -> new AssertionError("no row for " + ticket));
    }

    private List<String> cliLines(String... args) throws Exception {
        try (var cli = CliProcess.launch(cliHome, url(), args)) {
            var exit = cli.exit(SHORT);
            assertThat(exit).as("exit of " + List.of(args) + " " + cli.out() + cli.err()).isZero();
            return cli.out();
        }
    }

    private long sessionStart() throws Exception {
        return Files.readAllLines(queue.resolve("waiting").resolve(ticket)).stream()
                    .filter(line -> line.startsWith("session_start="))
                    .map(line -> Long.parseLong(line.substring("session_start=".length())))
                    .reduce((first, last) -> last)
                    .orElseThrow(() -> new AssertionError("no session_start in the ticket"));
    }

    private long javaStart() {
        return standIn.info().startInstant().orElseThrow().getEpochSecond();
    }

    // ---- the controller ------------------------------------------------------------------------------------------------

    private Promise<Controller.Running> start() {
        var git = GitCli.gitCli(ProcessRunner.processRunner(), config.repo().origin(), config.repo().mirror());
        return Controller.controller(config,
                                     store,
                                     Credentials.credentials(BearerToken.bearerToken(TOKEN).expect("chain setup"),
                                                             none(),
                                                             none(),
                                                             none(),
                                                             some(BearerToken.bearerToken(MAC_TOKEN).expect("chain setup"))),
                                     new Banner(),
                                     Controller.Git.git(git, git, git, GitHubPulls.gitHubPulls(config.repo().origin())),
                                     LinkTiming.DEFAULT,
                                     TimeSpan.timeSpan(5).seconds(),
                                     Instant::now,
                                     host -> some(LocalTicketTransport.localTicketTransport(OPS.resolve("bb/bb-tickets"), _ -> queue, Map.of())))
                         .start();
    }

    private ControllerConfig config() throws Exception {
        var instance = InstanceId.instanceId("acceptance").expect("chain setup");
        var server = new ControllerConfig.Server("127.0.0.1",
                                                 AcceptanceStack.freePort(),
                                                 cliHome.resolve(".config/temper/token"),
                                                 none(),
                                                 instance,
                                                 none(),
                                                 none(),
                                                 some(dir.resolve("mac-token")));
        var fleet = ControllerConfig.Fleet.fleet(none(),
                                                 Files.createDirectories(dir.resolve("state")),
                                                 List.of(ControllerConfig.BbHost.bbHost(HOST, some(ControllerConfig.BbLink.bbLink("u", "127.0.0.1")))),
                                                 none(),
                                                 none());
        return ControllerConfig.controllerConfig(AcceptanceStack.runnerVersion(),
                                                 server,
                                                 ControllerConfig.Database.database("unused", 1, database, "temper", Path.of("/unused")),
                                                 ControllerConfig.Repo.repo(fixture.origin().toString(), dir.resolve("controller-mirror.git")),
                                                 ControllerConfig.Scheduling.scheduling(Duration.ofMillis(500),
                                                                                        Duration.ofSeconds(1200),
                                                                                        Duration.ofSeconds(120),
                                                                                        Duration.ofSeconds(600)),
                                                 ControllerConfig.Units.units(MemoryMb.memoryMb(3072).expect("chain setup"),
                                                                              MemoryMb.memoryMb(4096).expect("chain setup"),
                                                                              1024,
                                                                              1L << 30,
                                                                              none(),
                                                                              none(),
                                                                              ControllerConfig.Units.RERUN_INSTALL_DEFAULT),
                                                 ControllerConfig.PortGroup.portGroup(List.of(), OwnerTag.ownerTag("temper").expect("chain setup"), 1),
                                                 ControllerConfig.Worker.worker(Path.of(System.getProperty("temper.worker.jar"))),
                                                 List.of(),
                                                 ControllerConfig.Evidence.evidence(Files.createDirectories(dir.resolve("evidence")), 256L << 20, 1L << 30, 0),
                                                 ControllerConfig.Retention.retention(Duration.ofHours(6)),
                                                 ControllerConfig.PgDump.pgDump(LocalTime.now().plusHours(12), "no-such-pg-container"),
                                                 fleet);
    }

    private String url() {
        return "http://127.0.0.1:" + config.server().port();
    }

    /// The self-check's `ssh-keyscan` answered with a key line; everything else runs for real.
    private static final class Banner implements ProcessRunner {
        private final ProcessRunner real = ProcessRunner.processRunner();

        @Override
        public Promise<Completed> run(Command command) {
            return command.argv().getFirst().equals("ssh-keyscan")
                   ? Promise.success(Completed.completed("ssh-keyscan", 0, KEY_LINE.getBytes(StandardCharsets.US_ASCII), new byte[0], false))
                   : real.run(command);
        }

        @Override
        public Result<Spawned> spawn(Command command) {
            return real.spawn(command);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    /// Only this ticket's own waiter (its argv holds `bb-build __run <id>`).
    private static List<ProcessHandle> waiters(String id) {
        return id == null
               ? List.of()
               : ProcessHandle.allProcesses()
                              .filter(handle -> handle.info().commandLine().map(line -> line.contains("bb-build __run " + id)).orElse(false))
                              .toList();
    }

    private static Path checkout(Path co) throws Exception {
        Files.createDirectories(co);
        Files.writeString(co.resolve(".gitignore"), ".bb/\n");
        for (var step : List.of(List.of("init", "-q"), List.of("add", "."), List.of("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "c"))) {
            var git = new ArrayList<>(List.of("git", "-C", co.toString()));
            git.addAll(step);
            assertThat(HermeticProcess.builder(git).inheritIO().start().waitFor()).isZero();
        }
        return co;
    }

    private static Path executable(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private static void token(Path file, String token) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, token + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    private static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static void until(BooleanSupplier condition, Duration bound, String what) throws InterruptedException {
        var deadline = Instant.now().plus(bound);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("not within " + bound + ": " + what);
            }
            Thread.sleep(500);
        }
    }
}
