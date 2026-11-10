package org.pragmatica.kiln.service.github;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.kiln.service.Main;
import org.pragmatica.kiln.service.TestAppKey;
import org.pragmatica.kiln.service.config.ConfigError;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.config.KilnConfigLoader;
import org.pragmatica.kiln.service.store.Cached;
import org.pragmatica.kiln.service.store.DbConfig;
import org.pragmatica.kiln.service.store.EtagStore;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.assertj.core.api.Assertions.assertThat;


/// Controls K.2 and K.3 and the mint failure, against a [GitHubFake] that answers both the mint and
/// the API: with the App configured every GitHub request kiln makes carries the installation token, a token that cannot
/// be had refuses the request locally, and `hourlyCap` still binds whatever limit the App's replies report. The mutation
/// each test must turn red under is in its comment.
class AppCredentialTest {
    private static final String MINT = "/app/installations/77/access_tokens";
    private static final String TOKEN = "ghs_APPTOKEN0123456789";
    private static final Path HOME = Path.of("/home/builder");

    /// A clock the test moves.
    private static final class Moving extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        void advance(Duration by) {
            now.updateAndGet(last -> last.plus(by));
        }
    }

    @TempDir
    Path dir;

    private GitHubFake fake;
    private final Moving clock = new Moving();

    @BeforeEach
    void start() {
        fake = GitHubFake.start();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private void minting(String token, Duration lasts) {
        fake.answer(MINT,
                    201,
                    "{\"token\":\"" + token
                   + "\",\"expires_at\":\"" + clock.instant()
                                                   .plus(lasts)
                                                   .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                   + "\",\"permissions\":{\"pull_requests\":\"read\"}}");
    }

    private KilnConfig config() throws Exception {
        return config("");
    }

    private KilnConfig config(String more) throws Exception {
        var key = TestAppKey.write(dir.resolve("kiln/app-key.pem"), "rw-------");
        var toml = """
            [db]
            host = "127.0.0.1"
            port = 1
            name = "kiln"
            user = "kiln"
            passwordFile = "/home/builder/pg"
            [github]
            repo = "o/r"
            bases = ["main"]
            [github.app]
            clientId = "Iv23test"
            installationId = 77
            keyFile = "%s"
            [mirror]
            origin = "https://github.com/o/r.git"
            [temper]
            url = "http://127.0.0.1:7070"
            readTokenFile = "/home/builder/rt"
            """.formatted(key) + more;

        return TomlParser.parse(toml)
                         .flatMap(doc -> KilnConfigLoader.parse(doc, HOME))
                         .unwrap();
    }

    private Credential app(KilnConfig config) {
        return Credential.credential(config.github(),
                                     fake.api(),
                                     clock)
                         .unwrap();
    }

    private List<GitHubFake.Request> apiRequests() {
        return fake.requests()
                   .stream()
                   .filter(request -> !request.path()
                                              .startsWith("/app/"))
                   .toList();
    }

    private List<GitHubFake.Request> mints() {
        return fake.requests()
                   .stream()
                   .filter(request -> request.path()
                                             .startsWith("/app/"))
                   .toList();
    }

    private static final String MORE_REPOS = """
        [[repos]]
        repo = "o/s"
        bases = ["main"]
        [[repos]]
        repo = "o/t"
        bases = ["main"]
        """;

    /// F5: the mint body narrows the token to exactly the eight read permissions and exactly the
    /// configured repositories by name, in file order. Mutations: drop one permission from `Credential.APP_PERMISSIONS`
    /// (`issues`); replace `.distinct()` in `Credential.repositoryNames` with `.limit(1)`.
    @Test
    void theMint_narrowsToTheContractPermissions_andEveryConfiguredRepository() throws Exception {
        var config = config(MORE_REPOS);

        minting(TOKEN, Duration.ofHours(1));
        assertThat(app(config).bearer().await().unwrap()).isEqualTo(Option.some(TOKEN));
        var body = JsonMapper.defaultJsonMapper().readTree(mints().getFirst().body()).unwrap();
        var expected = Map.of("pull_requests",
                              "read",
                              "checks",
                              "read",
                              "statuses",
                              "read",
                              "actions",
                              "read",
                              "contents",
                              "read",
                              "issues",
                              "read",
                              "metadata",
                              "read",
                              "members",
                              "read");
        var permissions = body.get("permissions");
        var repositories = body.get("repositories").valueStream().map(name -> name.asString()).toList();

        assertThat(permissions.size()).isEqualTo(expected.size());
        expected.forEach((key, level) -> assertThat(permissions.get(key).asString()).as(key)
                                                   .isEqualTo(level));
        assertThat(repositories).containsExactly("r", "s", "t");
    }

    /// The mint names repositories without an owner, so a `[[repos]]` entry of another owner would fail every mint with
    /// 422 and take down all reads: the configuration is refused at the start (exit 78). Mutation: remove the owner
    /// check in `Credential.appCredential`.
    @Test
    void anAppWithARepositoryOfAnotherOwner_isRefused_exit78() throws Exception {
        var config = config("[[repos]]\nrepo = \"other/s\"\nbases = [\"main\"]\n");
        var refused = Credential.credential(config.github(), fake.api(), clock).fold(cause -> cause, value -> null);

        assertThat(refused).isSameAs(ConfigError.Credential.APP_REPOS_OTHER_OWNER);
        assertThat(Main.exitCode(refused)).isEqualTo(78);
        assertThat(mints()).isEmpty();
    }

    /// A credential outage burns none of the local hourlyCap: a request the credential refuses is never counted.
    /// Mutation: spend before asking the credential (the old order in `GitHubReads`).
    @Test
    void aMintOutage_ofManyRequests_spendsNothingOfTheBudget() throws Exception {
        var config = config();
        var budget = RateBudget.rateBudget(3, 10);
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(),
                                               fake.api(),
                                               app(config),
                                               Duration.ofSeconds(10));
        var reads = GitHubReads.gitHubReads(http, etags(new ConcurrentHashMap<>()), budget, clock::instant);

        fake.answer(MINT, 500, "{\"message\":\"down\"}");
        for (var i = 0; i < 10; i++) {
            var failure = reads.parsedOnce("/repos/o/r/" + i, Result::success)
                               .await()
                               .fold(cause -> cause, value -> null);
            var conditional = reads.body("/repos/o/r/" + i).await().fold(cause -> cause, value -> null);

            assertThat(failure).isInstanceOf(GitHubReadError.CredentialUnavailable.class);
            assertThat(conditional).isInstanceOf(GitHubReadError.CredentialUnavailable.class);
        }

        assertThat(budget.spent(clock.instant())).as("the budget's own spend").isZero();
        assertThat(apiRequests()).isEmpty();
    }

    /// K.2: every request of the production ports carries the App token; the stub sees no unauthenticated request, and
    /// the mint is the only request that carries a JWT. Mutation: leave one path on the old client (the ports built with
    /// `none()` for the token).
    @Test
    void k2_everyRequestThroughTheProductionPorts_carriesTheAppToken() throws Exception {
        var config = config();
        var settings = new Main.Settings(config,
                                         app(config),
                                         "temper-read",
                                         DbConfig.dbConfig("127.0.0.1",
                                                           1,
                                                           "kiln",
                                                           "kiln",
                                                           "pw",
                                                           DbConfig.DEFAULT_MAX_CONNECTIONS).unwrap());
        var github = Main.ports(settings, fake.api()).unwrap().github();
        var paths = List.of("/repos/o/r/pulls?state=open",
                            "/repos/o/r/pulls/1",
                            "/repos/o/r/pulls/1/files",
                            "/repos/o/r/commits/abc",
                            "/repos/o/r/commits/abc/check-runs",
                            "/repos/o/r/commits/abc/statuses",
                            "/repos/o/r/branches/main",
                            "/repos/o/r/compare/a...b",
                            "/repos/o/r/actions/runs?branch=main",
                            "/repos/o/r/issues?state=all");

        minting(TOKEN, Duration.ofHours(1));
        fake.answerPrefix("/repos/o/r/", "{}");
        for (var path : paths) {
            assertThat(github.get(path, none()).await().unwrap().status()).as(path).isEqualTo(200);
        }

        github.get("/repos/o/r/pulls/1", Option.some("\"e\"")).await().unwrap();
        assertThat(apiRequests()).hasSize(paths.size() + 1)
                  .extracting(GitHubFake.Request::authorization)
                  .containsOnly("Bearer " + TOKEN);
        assertThat(apiRequests()).as("unauthenticated requests the stub saw")
                  .filteredOn(request -> request.authorization() == null)
                  .isEmpty();
        assertThat(mints()).as("one mint serves the lot").hasSize(1);
        assertThat(mints().getFirst().authorization()).startsWith("Bearer ").doesNotContain(TOKEN);
    }

    /// A mint that fails after the held token expired is `CredentialUnavailable`, and the request is not sent at all,
    /// unauthenticated or otherwise. Mutation: recover the failure to an absent token in `Credential.App.bearer`.
    @Test
    void aMintFailure_afterExpiry_refusesLocally_neverSendsUnauthenticated() throws Exception {
        var config = config();
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(),
                                               fake.api(),
                                               app(config),
                                               Duration.ofSeconds(10));

        minting(TOKEN, Duration.ofHours(1));
        fake.answerPrefix("/repos/o/r/", "{}");
        assertThat(http.get("/repos/o/r/pulls/1", none()).await().unwrap().status()).isEqualTo(200);
        fake.answer(MINT, 404, "{\"message\":\"Not Found\"}");
        clock.advance(Duration.ofMinutes(55));
        assertThat(http.get("/repos/o/r/pulls/1",
                            none()).await().isSuccess()).as("the held token serves until it expires")
                  .isTrue();
        clock.advance(Duration.ofMinutes(10));
        var before = apiRequests().size();
        var failure = http.get("/repos/o/r/pulls/1", none()).await().fold(cause -> cause, answer -> null);

        assertThat(failure).isInstanceOf(GitHubReadError.CredentialUnavailable.class);
        assertThat(failure.message()).doesNotContain(TOKEN);
        assertThat(apiRequests()).as("nothing was sent").hasSize(before);
    }

    /// K.3: `hourlyCap` refuses at the cap while the App's replies report a limit of 5,000 with 4,900 left. Mutation: read
    /// the cap from the reply's limit (in `RateBudget.spend`).
    @Test
    void k3_hourlyCap_stillRefusesAtTheCap_whateverLimitTheAppReports() throws Exception {
        var config = config();
        var stored = new ConcurrentHashMap<String, Cached>();
        var budget = RateBudget.rateBudget(3, 10);
        var http = JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(),
                                               fake.api(),
                                               app(config),
                                               Duration.ofSeconds(10));
        var reads = GitHubReads.gitHubReads(http, etags(stored), budget, clock::instant);

        minting(TOKEN, Duration.ofHours(1));
        fake.answerPrefix("/repos/o/r/", "{}");
        fake.rate(4900,
                  5000,
                  clock.instant().plusSeconds(1800),
                  false);
        for (var path : List.of("/repos/o/r/a", "/repos/o/r/b", "/repos/o/r/c")) {
            assertThat(reads.parsedOnce(path, Result::success).await().isSuccess()).as(path).isTrue();
        }

        var fourth = reads.parsedOnce("/repos/o/r/d", Result::success).await().fold(cause -> cause, value -> null);

        assertThat(budget.rate().get().limit()).as("the App's limit was reported").isEqualTo(Option.some(5000));
        assertThat(fourth).isInstanceOf(GitHubReadError.BudgetSpent.class);
        assertThat(apiRequests()).as("the fourth was never sent")
                  .extracting(GitHubFake.Request::path)
                  .containsExactly("/repos/o/r/a", "/repos/o/r/b", "/repos/o/r/c");
    }

    private static EtagStore etags(Map<String, Cached> stored) {
        return new EtagStore() {
            @Override
            public Promise<Option<Cached>> cached(String url) {
                return Promise.success(Option.option(stored.get(url)));
            }

            @Override
            public Promise<Unit> store(String url, String etag, String body, Instant at) {
                stored.put(url, new Cached(etag, body, at));

                return Promise.unitPromise();
            }
        };
    }
}
