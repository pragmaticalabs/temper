package org.pragmatica.temper.acceptance.kiln;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.source.SourceState;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.config.KilnSecrets;
import org.pragmatica.kiln.service.github.GitHubPoller;
import org.pragmatica.kiln.service.github.GitHubReads;
import org.pragmatica.kiln.service.github.JdkGitHubHttp;
import org.pragmatica.kiln.service.github.RateBudget;
import org.pragmatica.kiln.service.store.CheckStore;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPostgres;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.some;

/// K-15 (kiln-f1-design §7.2), a live smoke on the build host: kiln's real GitHub poller, with the real token read by
/// path (`-Dkiln.tokenFile`, default `~/.config/kiln/github-token`; never printed), into a real kiln-pg (postgres:16 in
/// docker), one cycle over `pragmaticalabs/pragmatica` with the configured bases `release-1.0.0-rc5` and `main`.
///
/// - Cycle 1 reads `Ok`, and the open PR count kiln holds equals the oracle's, the two read at most 60 s apart (an oracle
///   reading 0 aborts the smoke as inconclusive, never a pass): `gh pr list --state open` where `gh`
///   is installed and answers, else GitHub's search count (`/search/issues?q=repo:…+is:pr+is:open`, `total_count`,
///   Q-15). The oracle used is printed. A PR opened or closed between the two reads would differ, so one mismatch is
///   re-read once.
/// - The rc5 base's required list as kiln resolved it reads `REQUIRED(9, non_admins)`.
///
/// Runs only with `-Pacceptance -Dkiln.live` (profile `kiln-live`); without `kiln.live` it skips itself.
@Tag("host")
@Tag("kiln-live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KilnLiveSmokeIT {
    private static final String REPO = "pragmaticalabs/pragmatica";
    private static final BranchName RC5 = BranchName.branchName("release-1.0.0-rc5").unwrap();
    private static final BranchName MAIN = BranchName.branchName("main").unwrap();
    private static final TimeSpan CYCLE = TimeSpan.timeSpan(15).minutes();
    private static final Pattern TOTAL = Pattern.compile("\"total_count\"\\s*:\\s*(\\d+)");

    private GenericContainer<?> postgres;
    private KilnStore store;
    private final Map<BranchName, RequiredChecks> required = new ConcurrentHashMap<>();
    @TempDir
    Path highWater;

    record Oracle(String name, int open, Instant at) {}

    @BeforeAll
    void start() {
        Assumptions.assumeTrue(!System.getProperty("kiln.live", "").isBlank(), "kiln.live is not set");
        postgres = new GenericContainer<>("postgres:16").withEnv("POSTGRES_USER", "kiln")
                                                        .withEnv("POSTGRES_PASSWORD", "kiln-live")
                                                        .withEnv("POSTGRES_DB", "kiln")
                                                        .withExposedPorts(5432)
                                                        .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2));
        postgres.start();
        var db = DbConfig.dbConfig(postgres.getHost(), postgres.getMappedPort(5432), "kiln", "kiln", "kiln-live", 10).unwrap();
        store = KilnPostgres.connect(db, REPO, highWater.resolve("change-hwm")).await(TimeSpan.timeSpan(60).seconds()).unwrap();
    }

    @AfterAll
    void stop() {
        if (store != null) {
            store.close().await(TimeSpan.timeSpan(30).seconds());
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void openPrCount_matchesGitHub_andRc5RequiresNine() throws Exception {
        var token = token();
        var poller = poller(token);

        var first = cycle(poller);
        var kilnAt = Instant.now();
        var kiln = openCount();
        var oracle = oracle(token);

        System.out.printf("K-15 cycle 1: state %s, kiln open %d, oracle %s open %d, read %s (kiln ended %s)%n",
                          first, kiln, oracle.name(), oracle.open(), oracle.at(), Instant.now());
        if (kiln != oracle.open()) {
            var again = cycle(poller);
            kilnAt = Instant.now();
            kiln = openCount();
            oracle = oracle(token);
            System.out.printf("K-15 cycle 2 (re-read after a mismatch): state %s, kiln open %d, oracle %s open %d%n",
                              again, kiln, oracle.name(), oracle.open());
        }

        Assumptions.assumeTrue(oracle.open() > 0, "the oracle reads 0 open PRs: inconclusive, never a pass");
        assertThat(first).as("cycle 1").isInstanceOf(SourceState.Ok.class);
        assertThat(kiln).as("open PRs: kiln vs %s", oracle.name()).isEqualTo(oracle.open());
        assertThat(Duration.between(kilnAt, oracle.at()).abs()).as("kiln's read and the oracle's, apart").isLessThanOrEqualTo(Duration.ofSeconds(60));
        assertThat(required.get(RC5)).as("rc5's required list").isInstanceOfSatisfying(RequiredChecks.Required.class, list -> {
            assertThat(list.checks()).hasSize(9);
            assertThat(list.enforcement()).isEqualTo("non_admins");
        });
        System.out.printf("K-15 rc5: %s; main: %s%n", required.get(RC5), required.get(MAIN));
    }

    /// The token by path, as kiln reads it ([KilnSecrets]); a missing file fails the smoke (it needs the real token).
    private static Option<String> token() {
        var file = Option.option(System.getProperty("kiln.tokenFile"))
                         .filter(path -> !path.isBlank())
                         .map(Path::of)
                         .or(Path.of(System.getProperty("user.home"), ".config/kiln/github-token"));
        var token = KilnSecrets.gitHubToken(KilnConfig.GitHub.gitHub(REPO, List.of(RC5, MAIN), some(file), Duration.ofMinutes(10))).unwrap();

        assertThat(token.isPresent()).as("a GitHub token at %s", file).isTrue();
        return token;
    }

    private GitHubPoller poller(Option<String> token) {
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), JdkGitHubHttp.API, token, JdkGitHubHttp.TIMEOUT);
        var reads = GitHubReads.gitHubReads(http, store.etags(), RateBudget.rateBudget(1_000_000, 10), Instant::now);

        return GitHubPoller.gitHubPoller(reads, recordingRequired(store), REPO, List.of(RC5, MAIN), Instant::now, Duration.ofMinutes(14)).unwrap();
    }

    /// One cycle, ticking until it is due (a second cycle waits out the poller's period).
    private SourceState cycle(GitHubPoller poller) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofMinutes(3));

        while (true) {
            var ran = poller.tick().await(CYCLE).unwrap();

            if (ran.isPresent()) {
                return ran.unwrap();
            }
            assertThat(Instant.now()).as("a due cycle").isBefore(deadline);
            Thread.sleep(5_000);
        }
    }

    private int openCount() {
        return store.views().openPrs().await(TimeSpan.timeSpan(60).seconds()).unwrap().size();
    }

    /// `gh` where it is installed and answers (given the same token in its environment, `GH_TOKEN`: bigboy's `gh` has no
    /// login of its own); else the search count with the same token.
    private static Oracle oracle(Option<String> token) throws Exception {
        if (run("command -v gh", Option.none()).isPresent()) {
            var gh = run("gh pr list --repo " + REPO + " --state open --limit 1000 --json number --jq length", token);

            if (gh.isPresent()) {
                return new Oracle("gh pr list", Integer.parseInt(gh.unwrap().strip()), Instant.now());
            }
            System.out.println("K-15: gh is installed but did not answer; using the search count");
        }
        return search(token);
    }

    private static Oracle search(Option<String> token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("https://api.github.com/search/issues?q=repo:" + REPO + "+is:pr+is:open&per_page=1"))
                                 .header("Accept", "application/vnd.github+json")
                                 .header("X-GitHub-Api-Version", "2022-11-28");
        token.onPresent(value -> request.header("Authorization", "Bearer " + value));
        var response = HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
        var total = TOTAL.matcher(response.body());

        assertThat(response.statusCode()).as("search status").isEqualTo(200);
        assertThat(total.find()).as("search total_count").isTrue();
        return new Oracle("GET /search/issues total_count", Integer.parseInt(total.group(1)), Instant.now());
    }

    /// The command's stdout when it exits 0; the token, if given, only in the child's environment.
    private static Option<String> run(String command, Option<String> token) throws Exception {
        var builder = HermeticProcess.builder("bash", "-c", command).redirectError(ProcessBuilder.Redirect.DISCARD);

        token.onPresent(value -> builder.environment().put("GH_TOKEN", value));
        var process = builder.start();
        var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        return process.waitFor() == 0 && !out.isBlank()
               ? some(out)
               : Option.none();
    }

    /// The store, with every required list the poller writes recorded per base: what kiln resolved, as it stored it.
    private KilnStore recordingRequired(KilnStore delegate) {
        var checks = delegate.checks();
        var recording = (CheckStore) Proxy.newProxyInstance(CheckStore.class.getClassLoader(), new Class<?>[] {CheckStore.class}, (_, method, args) -> {
            if (method.getName().equals("required")) {
                required.put((BranchName) args[0], (RequiredChecks) args[1]);
            }
            return invoke(method, checks, args);
        });

        return (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(), new Class<?>[] {KilnStore.class},
                                                  (_, method, args) -> method.getName().equals("checks") ? recording : invoke(method, delegate, args));
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
