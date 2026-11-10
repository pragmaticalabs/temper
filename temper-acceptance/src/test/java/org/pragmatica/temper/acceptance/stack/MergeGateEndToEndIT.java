package org.pragmatica.temper.acceptance.stack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/// The merge gate end to end. The real CLI (its shaded jar, in this JVM, [InProcessCli]) with an
/// injected `file://` git base and a `gh` spy, the real controller (its routes, GO table, fresh token, over Postgres),
/// the real kiln-service ([GateKiln]: its poller and `pr-fresh` over a [GitHubStub]). Nothing between the stub's JSON and
/// the CLI's output lines is a double, except `gh` (`GhMerge`, spied) and git's origin (a local bare repository).
///
/// Rows: C10 E.go (all-green ACCEPT, base current, workflow untouched: GO, then `temper merge` calls `gh` once with
/// `--match-head-commit <sha>`; the GO is consumed and its outcome recorded) and G7.b (kiln stopped: `NO
/// source-unavailable kiln unreachable`); the negative controls (a FIX verdict, a moved head) give NO and `gh` is never called.
@Tag("docker")
@ExtendWith(DockerAvailable.class)
class MergeGateEndToEndIT {
    private static final String TOKEN = "gate-token";
    private static final String VERDICT_TOKEN = "gate-verdict-token";
    private static final String KEY_LINE = "127.0.0.1 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMergeGateEndToEndITMergeGateEndToEnd\n";
    private static final String REPO = GateKiln.REPO;
    private static final String ROOT = "/repos/" + REPO + "/";
    private static final long PR = 7;
    private static final Pattern GO_LINE = Pattern.compile("^go ([0-9a-f-]{36}) expires=\\S+$");

    private Path dir;
    private Path cliHome;
    private Path verdictToken;
    private Path origin;
    private Path work;
    private String base0;
    private String head1;
    private GitHubStub github;
    private GateKiln kiln;
    private Store store;
    private String database;
    private ControllerConfig config;
    private Controller.Running running;
    private InProcessCli cli;
    private final InProcessCli.GhSpy gh = new InProcessCli.GhSpy();

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createDirectories(Path.of(System.getProperty("temper.acceptance.target", "target"), "acceptance", "gate-" + System.nanoTime()).toAbsolutePath());
        cliHome = Files.createDirectories(dir.resolve("cli-home"));
        var tokenFile = secret(cliHome.resolve(".config/temper/token"), TOKEN);
        verdictToken = secret(dir.resolve("verdict-token"), VERDICT_TOKEN);
        gitOrigin();
        github = GitHubStub.start();
        stubGitHub(head1, "src/A.java");
        kiln = GateKiln.start(dir.resolve("kiln-change-hwm"), github);
        kiln.poll();
        var opened = Pg.freshStore(AcceptanceStack.firstJobId(System.currentTimeMillis()));
        store = opened.store();
        database = opened.database();
        config = config(tokenFile);
        running = start().await(TimeSpan.timeSpan(60).seconds()).expect("controller start");
        awaitReady();
        Pg.psql(database, "INSERT INTO ci_control_paths (repo, glob, added_by) VALUES ('" + REPO + "', '.github/**', 'gate-it')");
        cli = new InProcessCli();
    }

    @AfterEach
    void tearDown() throws Exception {
        var failures = new java.util.ArrayList<Throwable>();
        stopping(failures, () -> { if (cli != null) { cli.close(); } });
        stopping(failures, () -> { if (running != null) { running.stop().await(TimeSpan.timeSpan(60).seconds()); } });
        stopping(failures, () -> { if (kiln != null) { kiln.close(); } });
        stopping(failures, () -> { if (github != null) { github.close(); } });
        stopping(failures, () -> { if (store != null) { store.close(); } });
        if (!failures.isEmpty()) {
            var first = failures.getFirst();
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw first instanceof Exception e ? e : new RuntimeException(first);
        }
    }

    /// Each stop runs even when an earlier one threw; the first failure is rethrown with the others suppressed.
    private static void stopping(java.util.List<Throwable> failures, ThrowingStop stop) {
        try {
            stop.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    @FunctionalInterface
    private interface ThrowingStop {
        void run() throws Exception;
    }

    /// E.go: GO, then `temper merge` calls `gh` once with the head; the GO is consumed and its outcome is `merged`.
    @Test
    void allGreenAccept_goThenMerge_callsGhOnceWithTheHead_andRecordsTheOutcome() throws Exception {
        accept(head1);

        var gate = gate(head1);

        assertThat(gate.exit()).as("%s %s", gate.out(), gate.err()).isZero();
        var go = goId(gate);
        var out = gate.out();
        var iso = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ";
        var isoFraction = iso.replace("Z", "(\\.\\d+)?Z");
        assertThat(out).as("GO block: six lines, no 'carried' line for a verdict given on this head").hasSize(6);
        assertThat(out.get(0)).isEqualTo("GO " + REPO + "#" + PR + "@" + head1);
        assertThat(out.get(1)).isEqualTo("base main tip=" + base0);
        assertThat(out.get(2)).matches("verdict ACCEPT by=gate-it at=" + isoFraction + " report=\\S*report-ACCEPT\\.md sha256=[0-9a-f]{12}");
        assertThat(out.get(3)).matches("go " + go + " expires=" + isoFraction);
        assertThat(out.get(4)).as("R3-1: the literal placeholder, never a path").isEqualTo("temper merge " + go + " --token-file <verdict-token>");
        assertThat(out.get(5)).matches("as-of " + iso);
        assertThat(gh.calls()).as("merge-gate never calls gh").isEmpty();
        assertThat(Pg.psql(database, "SELECT consumed_at IS NULL FROM go WHERE id = '" + go + "'")).as("a created GO is not yet consumed").isEqualTo("t");

        var merge = run("merge", go, "--token-file", verdictToken.toString());

        assertThat(merge.exit()).as("%s %s", merge.out(), merge.err()).isZero();
        assertThat(merge.out()).containsExactly("consumed go " + go + " " + REPO + "#" + PR + "@" + head1.substring(0, 12) + " base=main base_tip=" + base0.substring(0, 12),
                                                "merged " + REPO + "#" + PR + " go=" + go + " base_tip_before=" + base0);
        assertThat(gh.calls()).as("gh pr merge --match-head-commit <sha>, once").containsExactly(REPO + " " + PR + " " + head1);
        assertThat(Pg.psql(database, "SELECT outcome || ' ' || (consumed_at IS NOT NULL) FROM go WHERE id = '" + go + "'")).isEqualTo("merged true");
        assertThat(Pg.psql(database, "SELECT count(*) FROM merges WHERE go_id = '" + go + "'")).isEqualTo("1");

        var again = run("merge", go, "--token-file", verdictToken.toString());

        assertThat(again.exit()).as("a consumed GO is single use").isEqualTo(2);
        assertThat(again.out()).as("step 1: 409 prints 'refused <detail>' (kebab of the wire code)").containsExactly("refused go-used");
        assertThat(gh.calls()).as("the second merge reached no gh").hasSize(1);
    }

    /// G7.b: kiln stopped, the controller's read of pr-fresh is refused: `NO` with the exact reason line; no GO row, no gh.
    @Test
    void kilnStopped_isNoSourceUnavailableKiln_andNothingIsMerged() throws Exception {
        accept(head1);
        kiln.stop();

        var gate = gate(head1);

        assertThat(gate.exit()).as("%s %s", gate.out(), gate.err()).isEqualTo(1);
        assertThat(gate.out()).hasSize(3);
        assertThat(gate.out().subList(0, 2)).containsExactly("NO", "source-unavailable kiln unreachable");
        assertThat(gate.out().get(2)).startsWith("as-of ");
        assertThat(Pg.psql(database, "SELECT count(*) FROM go")).isEqualTo("0");
        assertThat(gh.calls()).isEmpty();
    }

    /// Negative control for E.go: the verdict is FIX, so the same stack that gave GO gives NO.
    @Test
    void fixVerdict_isNo_andNoGoExists() throws Exception {
        verdict(head1, "FIX");

        var gate = gate(head1);

        assertThat(gate.exit()).as("%s %s", gate.out(), gate.err()).isEqualTo(1);
        assertThat(gate.out()).contains("NO", "verdict-FIX").doesNotContain("GO " + REPO + "#" + PR + "@" + head1);
        assertThat(Pg.psql(database, "SELECT count(*) FROM go")).isEqualTo("0");
        assertThat(gh.calls()).isEmpty();
    }

    /// Negative control for E.go: the head moved on GitHub after the ACCEPT, so the gate for the accepted sha is NO.
    @Test
    void movedHead_isNo_andNoGoExists() throws Exception {
        accept(head1);
        var head2 = commit("feature", Map.of("src/A.java", "a2\n"), "feature moved");
        push(head2, "refs/heads/feature");
        push(head2, "refs/pull/" + PR + "/head");
        stubGitHub(head2, "src/A.java");

        var gate = gate(head1);

        assertThat(gate.exit()).as("%s %s", gate.out(), gate.err()).isEqualTo(1);
        assertThat(gate.out()).contains("NO", "head-moved refs/heads/feature " + head2).doesNotContain("GO " + REPO + "#" + PR + "@" + head1);
        assertThat(Pg.psql(database, "SELECT count(*) FROM go")).isEqualTo("0");
        assertThat(gh.calls()).isEmpty();
    }

    // ---- the CLI --------------------------------------------------------------------------------------------------------

    private InProcessCli.Run run(String... args) {
        return cli.run(cliHome, "http://127.0.0.1:" + config.server().port(), "file://" + origin.getParent().getParent() + "/", gh, args);
    }

    private InProcessCli.Run gate(String sha) {
        return run("merge-gate", REPO, Long.toString(PR), sha);
    }

    private void accept(String sha) throws Exception {
        verdict(sha, "ACCEPT");
    }

    /// `temper verdict` through the CLI: the report's last line names the verdict; the controller keeps its path and digest.
    private void verdict(String sha, String verdict) throws Exception {
        var report = Files.writeString(dir.resolve("report-" + verdict + ".md"),
                                       "# review\nlooks right\nVERDICT: " + verdict + " " + REPO + "#" + PR + "@" + sha + " base=main\n");
        var registered = run("verdict", REPO, Long.toString(PR), sha, verdict, "--base", "main", "--by", "gate-it", "--report", report.toString(),
                             "--token-file", verdictToken.toString());

        assertThat(registered.exit()).as("%s %s", registered.out(), registered.err()).isZero();
    }

    private static String goId(InProcessCli.Run gate) {
        return gate.out().stream().map(GO_LINE::matcher).filter(java.util.regex.Matcher::matches).findFirst().orElseThrow().group(1);
    }

    // ---- GitHub: a local bare origin and the stub -------------------------------------------------------------------------

    /// `<dir>/gh/o/r.git` (the CLI's `<base><repo>.git`): `main` at `base0`, `feature` and `refs/pull/7/head` at `head1`.
    private void gitOrigin() throws Exception {
        origin = Files.createDirectories(dir.resolve("gh").resolve(REPO + ".git"));
        work = Files.createDirectories(dir.resolve("work"));
        Shell.ok("git", "init", "-q", "--bare", origin.toString());
        Shell.ok("git", "-C", work.toString(), "init", "-q", "-b", "main");
        base0 = commit("main", Map.of("src/A.java", "a\n", ".github/workflows/ci.yml", "on: pull_request\n"), "base");
        head1 = commit("feature", Map.of("src/A.java", "a1\n"), "feature");
        push(base0, "refs/heads/main");
        push(head1, "refs/heads/feature");
        push(head1, "refs/pull/" + PR + "/head");
    }

    /// A commit writing `files` on `branch` (created from `main` when new); answers its sha.
    private String commit(String branch, Map<String, String> files, String message) throws Exception {
        var git = List.of("git", "-C", work.toString(), "-c", "user.name=gate", "-c", "user.email=gate@example.org");
        if (branch.equals("feature")) {
            git(git, Shell.run(60, "git", "-C", work.toString(), "rev-parse", "--verify", "-q", "feature").exit() == 0
                     ? List.of("checkout", "-q", "feature")
                     : List.of("checkout", "-q", "-b", "feature"));
        }
        for (var entry : files.entrySet()) {
            var file = work.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        git(git, List.of("add", "-A"));
        git(git, List.of("commit", "-q", "-m", message));
        return Shell.ok("git", "-C", work.toString(), "rev-parse", "HEAD");
    }

    private static void git(List<String> git, List<String> args) throws Exception {
        Shell.ok(java.util.stream.Stream.concat(git.stream(), args.stream()).toArray(String[]::new));
    }

    private void push(String sha, String ref) throws Exception {
        Shell.ok("git", "-C", work.toString(), "push", "-q", "--force", origin.toString(), sha + ":" + ref);
    }

    /// What GitHub says: PR 7 open at `head` (changed file `file`), `main` at `base0` requiring `ci` (app 15368), `ci`
    /// green in a pull_request workflow run, and quiet answers for everything the poller also reads.
    private void stubGitHub(String head, String file) throws Exception {
        var tree = Shell.ok("git", "-C", work.toString(), "rev-parse", base0 + "^{tree}");
        var now = Instant.now().toString();
        github.answerPrefix(ROOT + "pulls?", "[]");
        github.answerPrefix(ROOT + "actions/runs?", "{\"total_count\":0,\"workflow_runs\":[]}");
        github.answerPrefix(ROOT + "issues?", "[]");
        github.answer(ROOT + "pulls/" + PR,
                      "{\"number\":" + PR + ",\"state\":\"open\",\"merged_at\":null,\"merged_by\":null,\"changed_files\":1,\"base\":{\"ref\":\"main\"},"
                      + "\"head\":{\"sha\":\"" + head + "\",\"ref\":\"feature\",\"repo\":{\"full_name\":\"" + REPO + "\"}}}");
        var branch = "{\"name\":\"main\",\"commit\":{\"sha\":\"" + base0 + "\",\"commit\":{\"tree\":{\"sha\":\"" + tree + "\"}}},"
                     + "\"protection\":{\"enabled\":true,\"required_status_checks\":{\"enforcement_level\":\"non_admins\","
                     + "\"checks\":[{\"context\":\"ci\",\"app_id\":15368}]}}}";
        github.answer(ROOT + "branches/main", branch);
        github.answer(ROOT + "rules/branches/main", "[]");
        github.answer(ROOT + "commits/" + head + "/check-runs?filter=all&per_page=100",
                      "{\"total_count\":1,\"check_runs\":[{\"id\":20,\"name\":\"ci\",\"app\":{\"id\":15368},\"status\":\"completed\",\"conclusion\":\"success\","
                      + "\"started_at\":\"" + now + "\",\"check_suite\":{\"id\":7}}]}");
        github.answer(ROOT + "actions/runs?head_sha=" + head + "&per_page=100",
                      "{\"total_count\":1,\"workflow_runs\":[{\"id\":100,\"name\":\"CI\",\"status\":\"completed\",\"conclusion\":\"success\","
                      + "\"head_sha\":\"" + head + "\",\"html_url\":\"https://example.invalid/100\",\"created_at\":\"" + now + "\",\"event\":\"pull_request\","
                      + "\"head_branch\":\"feature\",\"run_attempt\":1,\"check_suite_id\":7,\"updated_at\":\"" + now + "\","
                      + "\"pull_requests\":[{\"number\":" + PR + "}]}]}");
        github.answer(ROOT + "commits/" + head + "/statuses?per_page=100", "[]");
        github.answer(ROOT + "pulls/" + PR + "/files?per_page=100", "[{\"filename\":\"" + file + "\"}]");
    }

    // ---- the controller -----------------------------------------------------------------------------------------------------

    private Promise<Controller.Running> start() {
        var git = GitCli.gitCli(ProcessRunner.processRunner(), config.repo().origin(), config.repo().mirror());
        return Controller.controller(config,
                                     store,
                                     Credentials.credentials(BearerToken.bearerToken(TOKEN).expect("gate setup"),
                                                             none(),
                                                             none(),
                                                             some(BearerToken.bearerToken(VERDICT_TOKEN).expect("gate setup")),
                                                             none()),
                                     new KeyScan(),
                                     Controller.Git.git(git, git, git, GitHubPulls.gitHubPulls(config.repo().origin())),
                                     LinkTiming.DEFAULT,
                                     TimeSpan.timeSpan(5).seconds(),
                                     Instant::now)
                         .start();
    }

    private ControllerConfig config(Path tokenFile) throws Exception {
        var fixture = FixtureRepo.create(dir, List.of());
        return ControllerConfig.controllerConfig(AcceptanceStack.runnerVersion(),
                                                 ControllerConfig.Server.server("127.0.0.1", AcceptanceStack.freePort(), tokenFile, none(),
                                                                                InstanceId.instanceId("acceptance").expect("gate setup")),
                                                 ControllerConfig.Database.database("unused", 1, database, "temper", Path.of("/unused")),
                                                 ControllerConfig.Repo.repo(fixture.origin().toString(), dir.resolve("controller-mirror.git")),
                                                 ControllerConfig.Scheduling.scheduling(Duration.ofMillis(500), Duration.ofSeconds(1200), Duration.ofSeconds(120),
                                                                                        Duration.ofSeconds(600)),
                                                 ControllerConfig.Units.units(MemoryMb.memoryMb(3072).expect("gate setup"), MemoryMb.memoryMb(4096).expect("gate setup"),
                                                                              1024, 1L << 30, none(), none(), ControllerConfig.Units.RERUN_INSTALL_DEFAULT),
                                                 ControllerConfig.PortGroup.portGroup(List.of(), OwnerTag.ownerTag("temper").expect("gate setup"), 1),
                                                 ControllerConfig.Worker.worker(Path.of(System.getProperty("temper.worker.jar"))),
                                                 List.of(),
                                                 ControllerConfig.Evidence.evidence(Files.createDirectories(dir.resolve("evidence")), 256L << 20, 1L << 30, 0),
                                                 ControllerConfig.Retention.retention(Duration.ofHours(6)),
                                                 ControllerConfig.PgDump.pgDump(LocalTime.now().plusHours(12), "no-such-pg-container"),
                                                 ControllerConfig.Fleet.fleet(some(kiln.url()), Files.createDirectories(dir.resolve("state"))));
    }

    /// The fleet routes answer 503 NOT_READY until the first self-check ends: wait for the heartbeat.
    private void awaitReady() throws Exception {
        var deadline = Instant.now().plusSeconds(90);
        var client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + config.server().port() + "/api/v1/fleet/heartbeat"))
                                               .header("Authorization", "Bearer " + TOKEN)
                                               .build();
        while (Instant.now().isBefore(deadline)) {
            if (client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                return;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("the controller's fleet never became ready");
    }

    private static Path secret(Path file, String token) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, token + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }

    /// The self-check's `ssh-keyscan` answered with a key line (as FleetEventsIT does); everything else runs for real.
    private static final class KeyScan implements ProcessRunner {
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
}
