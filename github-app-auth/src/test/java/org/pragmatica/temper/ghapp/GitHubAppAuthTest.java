package org.pragmatica.temper.ghapp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;


/// Controls A.1-A.6 against a stub GitHub and a fixed clock. The mutation each row must turn red under
/// is in its test's comment.
class GitHubAppAuthTest {
    private static final Instant T0 = Instant.parse("2026-11-05T10:00:00Z");
    private static final JsonMapper JSON = JsonMapper.jsonMapper().build();
    private static final Map<String, String> PERMISSIONS = Map.of("pull_requests", "read", "checks", "read");
    private static final List<String> REPOSITORIES = List.of("pragmatica", "temper");

    @TempDir
    Path dir;

    private MutableClock clock;
    private StubGitHub stub;
    private KeyPair pair;
    private Path keyFile;

    @BeforeEach
    void setUp() throws IOException {
        clock = new MutableClock(T0);
        pair = TestKeys.generate();
        keyFile = TestKeys.write(dir.resolve("app-key.pem"), TestKeys.pkcs8Pem(pair), "rw-------");
        stub = new StubGitHub(healthy());
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    /// Every mint succeeds with a token that lives one hour from the clock's now.
    private IntFunction<StubGitHub.Reply> healthy() {
        return n -> StubGitHub.Reply.created("ghs_stub-" + n,
                                             clock.instant().plus(Duration.ofHours(1)).toString());
    }

    private GitHubAppAuth auth() {
        return config(keyFile).flatMap(c -> GitHubAppAuth.githubAppAuth(c,
                                                                        GitHubAppAuth.httpClient(),
                                                                        clock))
                     .fold(cause -> {
                               throw new AssertionError(cause.message());
                           },
                           auth -> auth);
    }

    private Result<AppAuthConfig> config(Path key) {
        return AppAuthConfig.appAuthConfig("kiln-app",
                                           "Iv23test",
                                           12345678L,
                                           key,
                                           REPOSITORIES,
                                           PERMISSIONS,
                                           stub.base());
    }

    private static String token(GitHubAppAuth auth) {
        return auth.token()
                   .await()
                   .fold(cause -> "FAILED " + cause.message(),
                         SecretToken::value);
    }

    /// Waits until the background refresh, if any, has settled; the caller then sees its outcome.
    private static void quiesce(GitHubAppAuth auth) {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

        while (((MintingAuth) auth).mintInFlight() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static JsonNode tree(String json) {
        return JSON.readTree(json).fold(cause -> null, node -> node);
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> null);
    }

    private static Cause failure(GitHubAppAuth auth) {
        return auth.token()
                   .await()
                   .fold(cause -> cause, token -> null);
    }

    // A.1. Mutations: iat = now; exp = now+3600; HS256.
    @Test
    void a1_jwt_claimsAndSignature() throws Exception {
        assertThat(token(auth())).isEqualTo("ghs_stub-1");
        var jwt = stub.seen().getFirst().jwt();
        var payload = tree(StubGitHub.part(jwt, 1));

        assertThat(tree(StubGitHub.part(jwt, 0)).path("alg").stringValue()).isEqualTo("RS256");
        assertThat(payload.path("iat").longValue()).isEqualTo(T0.getEpochSecond() - 60);
        assertThat(payload.path("exp").longValue()).isEqualTo(T0.getEpochSecond() + 540);
        assertThat(payload.path("iss").stringValue()).isEqualTo("Iv23test");
        assertThat(StubGitHub.verifies(jwt, pair.getPublic())).isTrue();
        // control: the verifier rejects a signature made by another key
        assertThat(StubGitHub.verifies(jwt,
                                       TestKeys.generate().getPublic())).isFalse();
    }

    // A.2. Mutation: drop `permissions` from the body.
    @Test
    void a2_mintRequest_carriesConfiguredRepositoriesAndPermissions() {
        token(auth());
        var seen = stub.seen().getFirst();
        var expected = tree("{\"repositories\":[\"pragmatica\",\"temper\"],"
                           + "\"permissions\":{\"pull_requests\":\"read\",\"checks\":\"read\"}}");

        assertThat(seen.path()).isEqualTo("/app/installations/12345678/access_tokens");
        assertThat(tree(seen.body())).isEqualTo(expected);
        assertThat(seen.authorization()).startsWith("Bearer ");
    }

    // A.3, part 1. Mutation: refresh at expiry (margin 0).
    @Test
    void a3_refreshesOnce_whenLessThanTenMinutesRemain() {
        var auth = auth();

        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        clock.advance(Duration.ofMinutes(50));  // 10 min left: not yet "less than 10"
        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        assertThat(stub.seen()).hasSize(1);
        clock.advance(Duration.ofMinutes(1));  // 9 min left: served at once, the refresh runs behind it
        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        quiesce(auth);
        assertThat(token(auth)).isEqualTo("ghs_stub-2");
        assertThat(token(auth)).isEqualTo("ghs_stub-2");
        assertThat(stub.seen()).hasSize(2);
    }

    // A.3, part 1, under contention. A mint that settles before its completion is attached runs that completion inside
    // the calling `token()`, under the lock the caller already holds. Mutation: in MintingAuth.servedWhileRefreshing,
    // start the mint before reading the held token; the refreshing caller then gets the new token. The window is a
    // scheduling race (the exchange answers at once, the caller is descheduled), so many scenarios run side by side.
    @Test
    void a3_refreshingCaller_getsTheTokenHeldWhenItAsked_underContention() throws Exception {
        var scenarios = 600;
        var pool = Executors.newFixedThreadPool(32);
        List<Future<String>> results = new ArrayList<>();

        try {
            for (var i = 0; i < scenarios; i++) {
                results.add(pool.submit(this::refreshingCallerToken));
            }

            for (var result : results) {
                assertThat(result.get()).isEqualTo("ghs_stub-1");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /// The token the caller gets with 9 minutes left, from an auth over a client that answers at once.
    private String refreshingCallerToken() {
        var local = new MutableClock(T0);
        var client = new ImmediateHttpClient(n -> "{\"token\":\"ghs_stub-" + n
                                                 + "\",\"expires_at\":\"" + local.instant()
                                                                                 .plus(Duration.ofHours(1))
                                                 + "\","
                                                 + "\"permissions\":{\"pull_requests\":\"read\"}}");
        var auth = config(keyFile).flatMap(c -> GitHubAppAuth.githubAppAuth(c, client, local))
                         .fold(cause -> {
                                   throw new AssertionError(cause.message());
                               },
                               a -> a);

        token(auth);
        local.advance(Duration.ofMinutes(51));  // 9 min left
        return token(auth);
    }

    // A.3, part 2. Mutation: no single-flight (every caller starts its own mint).
    @Test
    void a3_concurrentRequests_shareOneMint() throws Exception {
        var auth = auth();

        token(auth);
        clock.advance(Duration.ofMinutes(61));  // expired: every caller waits for the one mint
        stub.script(n -> StubGitHub.Reply.created("ghs_stub-" + n,
                                                  clock.instant().plus(Duration.ofHours(1)).toString())
                                         .after(Duration.ofMillis(500)));
        var callers = 8;
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(callers);
        List<Future<String>> results = new ArrayList<>();

        try {
            for (var i = 0; i < callers; i++) {
                Callable<String> call = () -> {
                    gate.await();

                    return token(auth);
                };

                results.add(pool.submit(call));
            }

            gate.countDown();
            for (var result : results) {
                assertThat(result.get()).isEqualTo("ghs_stub-2");
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(stub.seen()).hasSize(2);
    }

    // A.4, part 1. Mutation: fall back to unauthenticated (serve an empty token instead of failing).
    @Test
    void a4_failedMint_keepsServingOldToken_thenCredentialUnavailable_onTheBackoffSchedule() {
        var auth = auth();

        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        stub.script(n -> StubGitHub.Reply.status(500, "boom"));
        clock.advance(Duration.ofMinutes(51));  // 9 min left, mints now fail
        List<Long> attempts = new ArrayList<>();
        var seenBefore = stub.seen().size();
        var served = new ArrayList<String>();
        var unavailable = 0;
        var start = clock.instant();

        for (var second = 0; second < 25 * 60; second++) {
            var before = stub.seen().size();
            var result = auth.token().await();

            quiesce(auth);
            if (stub.seen().size() > before) {
                attempts.add(Duration.between(start, clock.instant()).toSeconds());
            }

            var cause = causeOf(result);

            if (cause != null) {
                unavailable++;
                assertThat(cause).isInstanceOf(AppAuthError.CredentialUnavailable.class);
            } else {
                served.add(result.fold(c -> "", SecretToken::value));
            }

            clock.advance(Duration.ofSeconds(1));
        }
        // attempts at 0, then 30 s, 1, 2, 4, 5, 5 min after each failure
        assertThat(attempts).containsExactly(0L, 30L, 90L, 210L, 450L, 750L, 1050L, 1350L);
        assertThat(seenBefore).isEqualTo(1);
        // the old token served until its expiry (9 min = 540 s), never a new one, then nothing
        assertThat(served).hasSize(540).containsOnly("ghs_stub-1");
        assertThat(unavailable).isEqualTo(25 * 60 - 540);
    }

    // A.4, part 2. After expiry with a failed mint, no request is sent while the backoff holds, and the first
    // recovery clears it.
    @Test
    void a4_recovery_clearsFailureAndBackoff() {
        var auth = auth();

        token(auth);
        stub.script(n -> StubGitHub.Reply.status(500, "boom"));
        clock.advance(Duration.ofMinutes(61));  // expired
        assertThat(failure(auth)).isInstanceOf(AppAuthError.CredentialUnavailable.class);
        var sent = stub.seen().size();

        clock.advance(Duration.ofSeconds(10));
        assertThat(failure(auth)).isInstanceOf(AppAuthError.CredentialUnavailable.class);
        assertThat(stub.seen()).hasSize(sent);
        stub.script(healthy());
        clock.advance(Duration.ofSeconds(25));
        assertThat(token(auth)).startsWith("ghs_stub-");
        assertThat(stub.seen()).hasSize(sent + 1);
    }

    // A.5. Mutation: warn instead of refusing.
    @Test
    void a5_groupOrOtherReadableKey_refusesStart_exit78() throws IOException {
        for (var mode : List.of("rw-r--r--", "rw-r-----", "rw----r--", "rwxrwxrwx", "rw--w----")) {
            TestKeys.write(keyFile, TestKeys.pkcs8Pem(pair), mode);
            var started = config(keyFile).flatMap(c -> GitHubAppAuth.githubAppAuth(c, GitHubAppAuth.httpClient(), clock));
            var cause = causeOf(started);

            assertThat(cause).as(mode).isInstanceOf(AppAuthError.KeyFileTooOpen.class);
            assertThat(((AppAuthError.KeyFileTooOpen) cause).exitCode()).isEqualTo(78);
        }

        assertThat(stub.seen()).isEmpty();
        // control: 0600 and 0400 start
        TestKeys.write(keyFile, TestKeys.pkcs8Pem(pair), "r--------");
        assertThat(token(auth())).isEqualTo("ghs_stub-1");
    }

    @Test
    void a5_keyOpenedAfterStart_failsTheMint_andSendsNothing() throws IOException {
        var auth = auth();

        TestKeys.write(keyFile, TestKeys.pkcs8Pem(pair), "rw-r--r--");
        assertThat(failure(auth)).isInstanceOf(AppAuthError.CredentialUnavailable.class);
        assertThat(stub.seen()).isEmpty();
    }

    // A.6. Mutation: accept one PEM form only.
    @Test
    void a6_pkcs1AndPkcs8Keys_bothMint() throws Exception {
        for (var pem : List.<java.util.function.Function<KeyPair, String>> of(TestKeys::pkcs1Pem, TestKeys::pkcs8Pem)) {
            var fresh = TestKeys.generate();

            TestKeys.write(keyFile, pem.apply(fresh), "rw-------");
            var before = stub.seen().size();

            assertThat(token(auth())).startsWith("ghs_stub-");
            assertThat(StubGitHub.verifies(stub.seen().get(before).jwt(),
                                           fresh.getPublic())).isTrue();
        }

        assertThat(TestKeys.pkcs1Pem(pair)).contains("BEGIN RSA PRIVATE KEY");
        assertThat(TestKeys.pkcs8Pem(pair)).contains("BEGIN PRIVATE KEY");
    }

    @Test
    void notAnRsaKey_refusesStart() throws IOException {
        for (var junk : List.of("hello\n",
                                "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n",
                                "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nAAAA\n-----END RSA PRIVATE KEY-----\n",
                                "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n")) {
            TestKeys.write(keyFile, junk, "rw-------");
            var started = config(keyFile).flatMap(c -> GitHubAppAuth.githubAppAuth(c, GitHubAppAuth.httpClient(), clock));

            assertThat(causeOf(started)).as(junk).isInstanceOf(AppAuthError.KeyMalformed.class);
        }
    }

    @Test
    void rotatedKey_isUsedAtTheNextMint_withoutRestart() throws Exception {
        var auth = auth();
        var rotated = TestKeys.generate();

        token(auth);
        TestKeys.write(keyFile, TestKeys.pkcs1Pem(rotated), "rw-------");
        clock.advance(Duration.ofMinutes(55));
        token(auth);
        quiesce(auth);
        assertThat(StubGitHub.verifies(stub.seen().get(0).jwt(),
                                       pair.getPublic())).isTrue();
        assertThat(StubGitHub.verifies(stub.seen().get(1).jwt(),
                                       rotated.getPublic())).isTrue();
        assertThat(StubGitHub.verifies(stub.seen().get(1).jwt(),
                                       pair.getPublic())).isFalse();
    }

    @Test
    void config_refusesWhatWouldMintAnUnnarrowedToken() {
        var bad = List.of(AppAuthConfig.appAuthConfig("kiln-app",
                                                      "Iv23test",
                                                      1L,
                                                      keyFile,
                                                      List.of(),
                                                      PERMISSIONS,
                                                      stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app",
                                                      "Iv23test",
                                                      1L,
                                                      keyFile,
                                                      REPOSITORIES,
                                                      Map.of(),
                                                      stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app",
                                                      "bad\"id",
                                                      1L,
                                                      keyFile,
                                                      REPOSITORIES,
                                                      PERMISSIONS,
                                                      stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app",
                                                      "Iv23test",
                                                      0L,
                                                      keyFile,
                                                      REPOSITORIES,
                                                      PERMISSIONS,
                                                      stub.base()));

        for (var result : bad) {
            assertThat(causeOf(result)).isInstanceOf(AppAuthError.ConfigInvalid.class);
        }
    }

    /// The read-only allow-list refuses `administration` at any level (LIVE.10: `read` does not show `bypass_actors`;
    /// `write` is refused by ruling), a name outside the §3.1 set, and any level above `read`. Mutation: add
    /// `administration` to `Settings.PERMISSION_NAMES`.
    @Test
    void config_refusesAdministration_otherNames_andWrite() {
        var admin = AppAuthConfig.appAuthConfig("kiln-app",
                                                "Iv23test",
                                                1L,
                                                keyFile,
                                                REPOSITORIES,
                                                Map.of("administration", "read"),
                                                stub.base());
        var other = AppAuthConfig.appAuthConfig("kiln-app",
                                                "Iv23test",
                                                1L,
                                                keyFile,
                                                REPOSITORIES,
                                                Map.of("secrets", "read"),
                                                stub.base());
        var write = AppAuthConfig.appAuthConfig("kiln-app",
                                                "Iv23test",
                                                1L,
                                                keyFile,
                                                REPOSITORIES,
                                                Map.of("administration", "write"),
                                                stub.base());

        assertThat(causeOf(admin)).isInstanceOf(AppAuthError.ConfigInvalid.class);
        assertThat(causeOf(other)).isInstanceOf(AppAuthError.ConfigInvalid.class);
        assertThat(causeOf(write)).isInstanceOf(AppAuthError.ConfigInvalid.class);
    }

    /// Pins the EXACT permission-name set through the public path: each of the eight names is accepted at `read`, and
    /// every other name (the risky ones listed, `administration`, `secrets`, `workflows` among them) is refused. Mutation:
    /// add any name from `refused` (e.g. `workflows`) to `Settings.PERMISSION_NAMES`.
    @Test
    void config_acceptsExactlyTheEightPermissionNames() {
        var accepted = List.of("pull_requests", "checks", "statuses", "actions", "contents", "issues", "metadata", "members");
        var refused = List.of("administration", "secrets", "workflows", "packages", "pages", "deployments", "environments",
                              "repository_hooks", "security_events", "single_file", "organization_administration");

        for (var name : accepted) {
            assertThat(causeOf(configWith(name))).as(name).isNull();
        }

        for (var name : refused) {
            assertThat(causeOf(configWith(name))).as(name).isInstanceOf(AppAuthError.ConfigInvalid.class);
        }
    }

    private Result<AppAuthConfig> configWith(String permission) {
        return AppAuthConfig.appAuthConfig("kiln-app",
                                           "Iv23test",
                                           1L,
                                           keyFile,
                                           REPOSITORIES,
                                           Map.of(permission, "read"),
                                           stub.base());
    }

    private static List<String> capturingLogs(Runnable action) {
        return capturingLogs(_ -> action.run());
    }

    private static List<String> capturingLogs(java.util.function.Consumer<List<String>> action) {
        var records = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger = Logger.getLogger("org.pragmatica.temper.ghapp");
        var handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                var params = record.getParameters() == null
                             ? List.of()
                             : List.of(record.getParameters());

                records.add(record.getMessage() + " " + params);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };

        logger.addHandler(handler);
        try {
            action.accept(records);
        } finally {
            logger.removeHandler(handler);
        }

        return records;
    }

    /// Nothing logs a token, a JWT or an Authorization header; the mint outcome and a clock-skew 401 are logged.
    @Test
    void logs_identityAndExpiry_neverTokenOrJwt() {
        var records = capturingLogs(seen -> {
            var auth = auth();

            token(auth);
            stub.script(n -> StubGitHub.Reply.status(401, "'Issued at' claim ('iat') must be an Integer"));
            clock.advance(Duration.ofMinutes(55));
            token(auth);
            quiesce(auth);
            // the outcome is recorded (and `inflight` cleared) before it is logged: wait for the last record
            var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();

            while (seen.stream().noneMatch(r -> r.contains("clock-skew")) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        });
        var all = String.join("\n", records);
        var jwt = stub.seen().getFirst().jwt();

        assertThat(all).contains("mint ok")
                  .contains("mint failed")
                  .contains("clock-skew")
                  .contains("kiln-app")
                  .contains("12345678");
        assertThat(all).doesNotContain("ghs_")
                  .doesNotContain(jwt)
                  .doesNotContain("Bearer")
                  .doesNotContain("Authorization")
                  .doesNotContain("-----BEGIN");
    }

    // M2. Mutation: store the whole Causes.fromThrowable text as the reason (stack trace, server bytes).
    @Test
    void m2_serverEchoingTheJwtInAStatusLine_reachesNeitherTheLogNorTheMessage() throws Exception {
        try (var raw = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            var thread = new Thread(() -> echoAuthorizationInStatusLine(raw), "echo-server");

            thread.setDaemon(true);
            thread.start();
            var dead = URI.create("http://127.0.0.1:" + raw.getLocalPort());
            var auth = config(keyFile).map(c -> new Settings(c.identity(),
                                                             c.clientId(),
                                                             c.installationId(),
                                                             c.keyFile(),
                                                             c.repositories(),
                                                             c.permissions(),
                                                             dead))
                             .flatMap(c -> GitHubAppAuth.githubAppAuth(c,
                                                                       GitHubAppAuth.httpClient(),
                                                                       clock))
                             .fold(c -> null, a -> a);
            var message = new ArrayList<String>();
            var logs = capturingLogs(() -> message.add(failure(auth).message()));
            var jwt = echoed.get();

            assertThat(jwt).as("the stub saw a JWT").startsWith("eyJ");
            assertThat(message.getFirst()).contains("credential unavailable")
                      .doesNotContain(jwt)
                      .doesNotContain("eyJ")
                      .doesNotContain("\tat ")
                      .hasSizeLessThan(400);
            assertThat(String.join("\n", logs)).contains("mint failed")
                      .doesNotContain(jwt)
                      .doesNotContain("eyJ")
                      .doesNotContain("\tat ");
        }
    }

    private final java.util.concurrent.atomic.AtomicReference<String> echoed = new java.util.concurrent.atomic.AtomicReference<>("");

    private void echoAuthorizationInStatusLine(ServerSocket server) {
        while (!server.isClosed()) {
            try (var socket = server.accept()) {
                var in = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),
                                                                                  StandardCharsets.ISO_8859_1));
                String line;
                var jwt = "";

                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("authorization: bearer ")) {
                        jwt = line.substring("authorization: bearer ".length());
                    }
                }

                echoed.set(jwt);
                socket.getOutputStream()
                      .write(("HTTP/1.1 " + jwt + " " + jwt + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            } catch (IOException e) {
                return;
            }
        }
    }

    // M2, body route. Mutation: keep GitHub's `message` unmasked.
    @Test
    void m2_serverEchoingTheJwtInAnErrorBody_isMaskedAndCapped() {
        stub.script(n -> StubGitHub.Reply.status(401,
                                                 "bad " + stub.seen()
                                                              .getLast()
                                                              .jwt()
                                                + " and ghs_abcdef0123 "
                                                + "x".repeat(2000)));
        var auth = auth();
        var message = new ArrayList<String>();
        var logs = capturingLogs(() -> message.add(failure(auth).message()));
        var jwt = stub.seen().getFirst().jwt();

        assertThat(message.getFirst()).doesNotContain(jwt)
                  .doesNotContain("eyJ")
                  .doesNotContain("ghs_")
                  .hasSizeLessThan(400);
        assertThat(String.join("\n", logs)).doesNotContain(jwt).doesNotContain("ghs_");
    }

    @Test
    void unreachableGitHub_isAMintFailure_notAHang() throws Exception {
        var dead = URI.create("http://127.0.0.1:1");
        var auth = config(keyFile).map(c -> new Settings(c.identity(),
                                                         c.clientId(),
                                                         c.installationId(),
                                                         c.keyFile(),
                                                         c.repositories(),
                                                         c.permissions(),
                                                         dead))
                         .flatMap(c -> GitHubAppAuth.githubAppAuth(c,
                                                                   GitHubAppAuth.httpClient(),
                                                                   clock))
                         .fold(c -> null, a -> a);

        assertThat(failure(auth)).isInstanceOf(AppAuthError.CredentialUnavailable.class)
                  .extracting(Cause::message)
                  .asString()
                  .contains("mint unreachable");
        assertThat(Files.exists(keyFile)).isTrue();
    }

    // LOW 1. Mutation: the factory dereferences its arguments unchecked (NullPointerException, not a failure).
    @Test
    void config_nullArguments_areFailures_notExceptions() {
        var ok = Map.of("pull_requests", "read");
        var repos = List.of("temper");
        var withNullRepository = new ArrayList<String>(List.of("temper"));
        var withNullPermission = new HashMap<String, String>();
        var withNullLevel = new HashMap<String, String>();

        withNullRepository.add(null);
        withNullPermission.put(null, "read");
        withNullLevel.put("pull_requests", null);
        var bad = List.of(AppAuthConfig.appAuthConfig(null, "Iv23test", 1L, keyFile, repos, ok, stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app", null, 1L, keyFile, repos, ok, stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app", "Iv23test", 1L, null, repos, ok, stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app", "Iv23test", 1L, keyFile, null, ok, stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app", "Iv23test", 1L, keyFile, repos, null, stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app", "Iv23test", 1L, keyFile, repos, ok, null),
                          AppAuthConfig.appAuthConfig("kiln-app",
                                                      "Iv23test",
                                                      1L,
                                                      keyFile,
                                                      withNullRepository,
                                                      ok,
                                                      stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app",
                                                      "Iv23test",
                                                      1L,
                                                      keyFile,
                                                      repos,
                                                      withNullPermission,
                                                      stub.base()),
                          AppAuthConfig.appAuthConfig("kiln-app",
                                                      "Iv23test",
                                                      1L,
                                                      keyFile,
                                                      repos,
                                                      withNullLevel,
                                                      stub.base()));

        for (var result : bad) {
            assertThat(causeOf(result)).isInstanceOf(AppAuthError.ConfigInvalid.class);
        }
        // control: the same arguments, none missing, are a config
        List<String> built = AppAuthConfig.appAuthConfig("kiln-app",
                                                         "Iv23.test9",
                                                         1L,
                                                         keyFile,
                                                         repos,
                                                         ok,
                                                         stub.base())
                                          .fold(cause -> List.of(),
                                                AppAuthConfig::repositories);

        assertThat(built).isEqualTo(repos);
    }

    // LOW 2 (round 2). Mutation: the message after an expired short-lived token says "no token minted yet".
    @Test
    void expiredShortToken_insideItsBackoff_saysWhatHappened() {
        stub.script(n -> StubGitHub.Reply.created("ghs_stub-" + n,
                                                  clock.instant().plus(Duration.ofSeconds(10)).toString()));
        var auth = auth();

        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        clock.advance(Duration.ofSeconds(15));  // expired at T0+10s, backoff holds until T0+30s
        var cause = failure(auth);

        assertThat(cause).isInstanceOf(AppAuthError.CredentialUnavailable.class);
        assertThat(cause.message()).contains("token expired at " + T0.plusSeconds(10))
                  .contains("next mint after " + T0.plusSeconds(30))
                  .doesNotContain("no token minted yet")
                  .doesNotContain("ghs_stub");
        assertThat(stub.seen()).hasSize(1);
        // control: before any mint there is nothing to say but that
        stub.script(n -> StubGitHub.Reply.status(500, "boom"));
        var fresh = auth();

        assertThat(failure(fresh).message()).doesNotContain("expired");
    }

    // LOW 2. Mutation: a caller holding a valid token waits for the mint.
    @Test
    void validToken_isServedAtOnce_whileTheRefreshHangs() throws Exception {
        var auth = auth();

        token(auth);
        stub.script(n -> StubGitHub.Reply.created("ghs_stub-" + n,
                                                  clock.instant().plus(Duration.ofHours(1)).toString())
                                         .after(Duration.ofSeconds(3)));
        clock.advance(Duration.ofMinutes(55));
        var started = System.nanoTime();

        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(token(auth)).isEqualTo("ghs_stub-1");  // still the one mint in flight
        var deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();

        while (!token(auth).equals("ghs_stub-2") && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertThat(token(auth)).isEqualTo("ghs_stub-2");
        assertThat(stub.seen()).hasSize(2);
        // an expired token still waits for the mint
        clock.advance(Duration.ofMinutes(61));
        started = System.nanoTime();
        assertThat(token(auth)).isEqualTo("ghs_stub-3");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThan(Duration.ofSeconds(2));
    }

    // Flake 42206450488: a mint records its token and clears "in flight" in two steps; a caller whose token expired in
    // between joined the finished mint and was told its token had expired. The first "mint ok" log record (written
    // after the token is held) parks the mint thread, which holds that window open.
    // Mutation: clear `inflight` after the outcome again (outside minted/failed's critical section) -> red.
    @Test
    void expiredToken_callerDoesNotJoinAMintThatHasAlreadyRecordedItsToken() throws Exception {
        var auth = auth();

        token(auth);
        clock.advance(Duration.ofMinutes(55));
        var parked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var logger = Logger.getLogger("org.pragmatica.temper.ghapp");
        var handler = new Handler() {
            private boolean first = true;

            @Override
            public void publish(LogRecord record) {
                if (first && record.getMessage().startsWith("mint ok")) {
                    first = false;
                    parked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);

        try (var pool = Executors.newSingleThreadExecutor()) {
            assertThat(token(auth)).isEqualTo("ghs_stub-1");  // starts mint 2 behind this caller
            assertThat(parked.await(15, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            clock.advance(Duration.ofMinutes(61));  // stub-2 (held, recorded) is now expired
            var caller = pool.submit(() -> token(auth));

            Thread.sleep(500);
            release.countDown();
            assertThat(caller.get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("ghs_stub-3");
        } finally {
            release.countDown();
            logger.removeHandler(handler);
        }
    }

    // LOW 3. Mutation: no backoff after a token that is already inside the refresh margin.
    @Test
    void shortLivedToken_isUsedUntilExpiry_andMintedOncePerBackoff() {
        stub.script(n -> StubGitHub.Reply.created("ghs_stub-" + n,
                                                  clock.instant().plus(Duration.ofMinutes(5)).toString()));
        var auth = auth();

        for (var call = 0; call < 20; call++) {
            assertThat(token(auth)).isEqualTo("ghs_stub-1");
        }

        assertThat(stub.seen()).hasSize(1);
        clock.advance(Duration.ofSeconds(29));
        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        assertThat(stub.seen()).hasSize(1);
        clock.advance(Duration.ofSeconds(2));
        token(auth);  // the backoff has passed: one more mint, behind this caller
        quiesce(auth);
        assertThat(stub.seen()).hasSize(2);
        for (var call = 0; call < 20; call++) {
            token(auth);
        }

        quiesce(auth);
        assertThat(stub.seen()).hasSize(2);
    }

    // LOW 4. Mutation: no symlink check; no directory check.
    @Test
    void symlinkedKey_isRefused_exit78() throws IOException {
        var link = Files.createSymbolicLink(dir.resolve("link.pem"), keyFile);
        var cause = causeOf(config(link).flatMap(c -> GitHubAppAuth.githubAppAuth(c, GitHubAppAuth.httpClient(), clock)));

        assertThat(cause).isInstanceOf(AppAuthError.KeyFileUnsafe.class);
        assertThat(cause.message()).contains("symbolic link");
        assertThat(((AppAuthError.KeyFileUnsafe) cause).exitCode()).isEqualTo(78);
    }

    @Test
    void keyInAWritableDirectory_isRefused_exit78() throws IOException {
        var keys = Files.createDirectory(dir.resolve("keys"));
        var key = TestKeys.write(keys.resolve("k.pem"), TestKeys.pkcs8Pem(pair), "rw-------");

        for (var mode : List.of("rwxrwx---", "rwx-w----", "rwx----w-", "rwxrwxrwx")) {
            Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString(mode));
            var cause = causeOf(config(key).flatMap(c -> GitHubAppAuth.githubAppAuth(c,
                                                                                     GitHubAppAuth.httpClient(),
                                                                                     clock)));

            assertThat(cause).as(mode).isInstanceOf(AppAuthError.KeyFileUnsafe.class);
            assertThat(((AppAuthError.KeyFileUnsafe) cause).exitCode()).isEqualTo(78);
        }
        // control: a readable but not writable directory is fine
        Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThat(causeOf(config(key).flatMap(c -> GitHubAppAuth.githubAppAuth(c, GitHubAppAuth.httpClient(), clock)))).isNull();
    }

    // LOW 5. Mutation: the promise carries the bare String.
    @Test
    void printingThePromiseOrTheResult_neverShowsTheToken() {
        var auth = auth();
        var promise = auth.token();
        var result = promise.await();

        assertThat(token(auth)).isEqualTo("ghs_stub-1");
        assertThat(String.valueOf(promise)).doesNotContain("ghs_stub").contains("ghs_***");
        assertThat(String.valueOf(result)).doesNotContain("ghs_stub").contains("ghs_***");
        assertThat(String.valueOf(auth.token())).doesNotContain("ghs_stub");
        assertThat(new SecretToken("ghs_stub-1").toString()).isEqualTo("ghs_***");
    }

    // Info. Mutation: accept any PKCS#8 key.
    @Test
    void ecKey_isRefused_theJwtIsRs256() throws Exception {
        var generator = java.security.KeyPairGenerator.getInstance("EC");

        generator.initialize(256);
        TestKeys.write(keyFile,
                       TestKeys.pkcs8Pem(generator.generateKeyPair()),
                       "rw-------");
        var cause = causeOf(config(keyFile).flatMap(c -> GitHubAppAuth.githubAppAuth(c,
                                                                                     GitHubAppAuth.httpClient(),
                                                                                     clock)));

        assertThat(cause).isInstanceOf(AppAuthError.KeyMalformed.class);
    }
}
