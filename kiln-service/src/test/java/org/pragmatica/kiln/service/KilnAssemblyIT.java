package org.pragmatica.kiln.service;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Sha;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.config.KilnConfig;
import org.pragmatica.kiln.service.github.JdkGitHubHttp;
import org.pragmatica.kiln.service.mirror.GitMirror;
import org.pragmatica.kiln.service.scan.LocalScanTransport;
import org.pragmatica.kiln.service.scan.ScanFakes;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.pg.KilnPgFixture;
import org.pragmatica.kiln.service.temper.TemperHttp;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.vo.Url;
import org.pragmatica.temper.protocol.exec.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The assembly end to end (WP9): [Kiln] started on a random loopback port against kiln-pg (docker), a GitHub fake and a
/// temper fake on the loopback address, a real [GitMirror] over a local bare origin, and the real `kiln-scan` through
/// [LocalScanTransport] for one host. The loops run on their own; the test waits for every source to be recorded, then
/// hits every route of kiln-f1-design §4.
///
/// Pins: every route answers (§4); the drill-down links of the dashboard resolve (`status=` enum names, open item 5);
/// the sources view carries the poller's rate budget and its skipped PR (open item 2) and every host's pass (open item
/// 1); the summary's `asOf` is the store's committed watermark and a `since` before the oldest kept change gets its
/// `gap` line first (open item 10); a source never recorded shows as such in the API (open item 12); the ingest answers
/// per §4 and deletes only under `[mac] roots` (open item 8); a pass and a mirror fetch trigger the derivation (open
/// item 11).
@Tag("pg")
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KilnAssemblyIT {
    private static final String REPO = "o/r";
    private static final String ROOT = "/repos/" + REPO + "/";
    private static final String PULLS = ROOT + "pulls?state=all&sort=updated&direction=desc&per_page=100";
    private static final Duration WAIT = Duration.ofSeconds(90);
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(60).seconds();
    private static final Pattern DRILL = Pattern.compile("href=\"(/checkouts\\?[^\"]*)\"");
    private static final Map<String, String> GIT_ENV = Map.of("GIT_CONFIG_NOSYSTEM", "1",
                                                              "GIT_CONFIG_GLOBAL", "/dev/null",
                                                              "GIT_AUTHOR_NAME", "kiln",
                                                              "GIT_AUTHOR_EMAIL", "kiln@example.org",
                                                              "GIT_COMMITTER_NAME", "kiln",
                                                              "GIT_COMMITTER_EMAIL", "kiln@example.org");

    @TempDir
    Path dir;

    private final HttpClient client = HttpClient.newHttpClient();
    private final List<AutoCloseable> closing = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (var resource : closing.reversed()) {
            resource.close();
        }
    }

    // ---- the scenario ------------------------------------------------------------------------------------------------
    @Test
    void everyRoute_answers_overTheLiveLoops() throws Exception {
        var origin = origin();
        var head = git(origin, "rev-parse", "refs/pull/1/head");
        var github = fakeGitHub(head, 200);
        var home = scannedHome();
        var store = store();
        var running = started(store, github, origin, home);
        var base = "http://127.0.0.1:" + running.port();

        awaitTrue(() -> recorded(base, "github", "mirror", "temper", "host:bigboy"));
        // item 11: the hot passes and the mirror fetch triggered the derivation; the second hot pass gave full facts
        awaitTrue(() -> rows(get(base + "/api/v1/checkouts")).stream().anyMatch(row -> row.get("derivedAt") != null && row.get("factsAt") != null));

        // item 11, a pass triggers the derivation by itself: a checkout made now is derived (with full facts) within
        // 15 s, while the next mirror fetch, the only other trigger here, is a minute after the first
        var later = home.toRealPath() + "/work/co/b";
        checkout(home.resolve("work/co/b"));
        awaitTrue(() -> rows(get(base + "/api/v1/checkouts?host=bigboy")).stream()
                                                                         .anyMatch(row -> later.equals(row.get("path")) && row.get("derivedAt") != null
                                                                                          && row.get("factsAt") != null),
                  Duration.ofSeconds(15));


        // GET /: the dashboard, with the poller's budget and skipped PR (item 2) and the host's passes (item 1)
        var dashboard = get(base + "/");
        assertThat(dashboard.statusCode()).isEqualTo(200);
        assertThat(dashboard.body()).contains("rate budget 4990 left",
                                              "skipped #2: ",
                                              "hot pass: COMPLETE, 2 of 2 rows",
                                              "push: none recorded");

        // item 5: every drill-down link the dashboard renders resolves, by the enum's name
        var links = DRILL.matcher(dashboard.body())
                         .results()
                         .map(found -> found.group(1).replace("&amp;", "&"))
                         .toList();
        assertThat(links).isNotEmpty()
                         .anyMatch(link -> link.contains("status=REMOVABLE"));
        for (var link : links) {
            assertThat(get(base + link).statusCode()).as(link).isEqualTo(200);
        }
        assertThat(get(base + "/checkouts?status=IN_USE").statusCode()).isEqualTo(200);
        assertThat(get(base + "/checkouts?status=IN-USE").statusCode()).isEqualTo(400);
        assertThat(get(base + "/checkouts").statusCode()).isEqualTo(400);
        assertThat(get(base + "/checkouts?status=ACTIVE&page=0").statusCode()).isEqualTo(400);
        assertThat(get(base + "/non-git?host=bigboy&root=" + home + "/work/co/*").statusCode()).isEqualTo(200);
        assertThat(get(base + "/non-git?host=bigboy").statusCode()).isEqualTo(400);

        // JSON routes; a source never recorded (the Mac) reads NONE, never absent (item 12)
        var prs = json(get(base + "/api/v1/prs"));
        assertThat(list(prs, "prs")).anySatisfy(pr -> assertThat(pr).containsEntry("number", 1).containsEntry("head", head));
        assertThat(state(prs, "mac")).isEqualTo("NONE");
        assertThat(state(prs, "github")).isEqualTo("INCOMPLETE");
        assertThat(rows(get(base + "/api/v1/checkouts?host=bigboy"))).anySatisfy(row -> assertThat(row).containsEntry("host", "bigboy")
                                                                                                 .containsEntry("path", home.toRealPath() + "/work/co/a"));
        var sources = json(get(base + "/api/v1/sources"));
        assertThat(map(sources, "rate")).containsEntry("remaining", 4990);
        assertThat(map(sources, "rate")).containsEntry("hourlyCap", 1572).containsEntry("reserve", 1500).containsKey("kilnSpent");
        assertThat(list(sources, "skipped")).singleElement().satisfies(skipped -> assertThat(skipped).containsEntry("number", 2));
        assertThat(list(sources, "passes")).anySatisfy(pass -> assertThat(pass).containsEntry("host", "bigboy")
                                                                             .containsEntry("kind", "HOT")
                                                                             .containsEntry("outcome", "COMPLETE")
                                                                             .containsEntry("rows", 2)
                                                                             .containsEntry("expected", 2));

        // item 10: asOf is the committed watermark, read before the changes; item 12: the Mac's missing state is a line
        var before = store.changes().watermark();
        var summary = get(base + "/api/v1/summary?format=text");
        var after = store.changes().watermark();
        var cursor = Instant.parse(summary.body().lines().reduce((_, last) -> last).orElseThrow().replaceFirst("^(next|more): since=", ""));
        assertThat(cursor).isBetween(before, after);
        assertThat(summary.body()).contains("source mac: no state recorded, last success never");
        assertThat(await(store.changes().oldest()).isPresent()).isTrue();
        var gapped = get(base + "/api/v1/summary?format=text&since=2000-01-01T00:00:00Z");
        assertThat(gapped.body().lines().findFirst().orElseThrow()).startsWith("gap: since=2000-01-01T00:00:00Z");
        assertThat(json(get(base + "/api/v1/summary"))).containsKeys("lines", "asOf");
        assertThat(get(base + "/api/v1/summary?since=yesterday").statusCode()).isEqualTo(400);

        // POST /api/v1/ingest/mac: §4's answer; a complete push deletes only under [mac] roots (item 8)
        assertThat(post(base + "/api/v1/ingest/mac", push(true, "/m/root/a", "/m/root/b", "/m/elsewhere/c")).body())
            .isEqualTo("{\"outcome\":\"COMPLETE\",\"rows\":3}");
        assertThat(post(base + "/api/v1/ingest/mac", push(true, "/m/root/a")).body()).isEqualTo("{\"outcome\":\"COMPLETE\",\"rows\":1}");
        assertThat(macPaths(store)).containsExactlyInAnyOrder("/m/root/a", "/m/elsewhere/c");
        assertThat(post(base + "/api/v1/ingest/mac", push(false, "/m/root/z")).body()).isEqualTo("{\"outcome\":\"INCOMPLETE\",\"rows\":1}");
        assertThat(state(json(get(base + "/api/v1/sources")), "mac")).isEqualTo("INCOMPLETE");

        // the server's own refusals
        assertThat(post(base + "/api/v1/ingest/mac", "x".repeat(8 * 1024 * 1024 + 1)).statusCode()).isEqualTo(413);
        assertThat(get(base + "/nope").statusCode()).isEqualTo(404);
        assertThat(post(base + "/", "").statusCode()).isEqualTo(405);
    }

    /// Item 12: GitHub failing on a fresh store shows GITHUB-DOWN on the page and in the API, never an empty list that
    /// reads as "no open PR".
    @Test
    void aFailingGitHub_showsAsSuch_onEveryAnswer() throws Exception {
        var origin = origin();
        var github = fakeGitHub(git(origin, "rev-parse", "refs/pull/1/head"), 502);
        var store = store();
        var running = started(store, github, origin, scannedHome());
        var base = "http://127.0.0.1:" + running.port();

        awaitTrue(() -> recorded(base, "github"));

        var prs = json(get(base + "/api/v1/prs"));
        assertThat(list(prs, "prs")).isEmpty();
        assertThat(state(prs, "github")).isEqualTo("GITHUB_DOWN");
        assertThat(get(base + "/").body()).contains("GITHUB-DOWN");
        assertThat(get(base + "/api/v1/summary?format=text").body()).contains("source github: GITHUB-DOWN(");
    }

    // ---- assembly ----------------------------------------------------------------------------------------------------
    private KilnStore store() {
        var store = KilnPgFixture.freshStore();

        closing.add(() -> store.close().await(AWAIT));
        return store;
    }

    private Kiln.Running started(KilnStore store, URI github, Path origin, Path home) {
        var host = KilnConfig.Host.host(HostName.hostName("bigboy").unwrap(),
                                        "127.0.0.1",
                                        "builder",
                                        dir.resolve("key"),
                                        dir.resolve("known_hosts"),
                                        List.of(home + "/work/co/*"));
        var config = KilnConfig.kilnConfig(KilnConfig.Server.server(0),
                                           KilnConfig.Database.database("127.0.0.1", 5432, "kiln", "kiln", dir.resolve("pw"), Path.of("/hwm")),
                                           KilnConfig.GitHub.gitHub(REPO, List.of(BranchName.branchName("main").unwrap()), none(), Duration.ofMinutes(1)),
                                           KilnConfig.Mirror.mirror(origin.toString(), dir.resolve("mirror.git")),
                                           KilnConfig.Temper.temper(Url.url("http://127.0.0.1:9").unwrap(), dir.resolve("t"), Duration.ofMinutes(1)),
                                           KilnConfig.Scan.scan(Duration.ofSeconds(2),
                                                                Duration.ofHours(48),
                                                                LocalTime.of(4, 0),
                                                                Duration.ofHours(1),
                                                                Duration.ofHours(48),
                                                                Duration.ofSeconds(120),
                                                                Duration.ofSeconds(50)),
                                           KilnConfig.Mac.mac(List.of("/m/root/*")),
                                           List.of(host));
        var ports = Kiln.Ports.ports(JdkGitHubHttp.jdkGitHubHttp(JdkGitHubHttp.httpClient(), github, some("t0ken"), Duration.ofSeconds(10)),
                                     new FakeTemper(),
                                     GitMirror.gitMirror(ProcessRunner.processRunner(), origin.toString(), dir.resolve("mirror.git")).unwrap(),
                                     _ -> LocalScanTransport.localScanTransport(Path.of("..", "ops", "kiln", "kiln-scan").toAbsolutePath().normalize(),
                                                                                home,
                                                                                GIT_ENV,
                                                                                TimeSpan.timeSpan(50).seconds()),
                                     Instant::now);
        var running = await(Kiln.assemble(config, store, ports).async().flatMap(Kiln::start));

        closing.add(() -> running.stop().await(AWAIT));
        return running;
    }

    /// A temper that answers every gate PASS and a one-line digest.
    record FakeTemper() implements TemperHttp {
        @Override
        public Promise<String> digestText() {
            return Promise.success("digest: nothing queued");
        }

        @Override

        public Promise<org.pragmatica.kiln.core.temper.HostsRead> fleetAlarms() {

            return Promise.success(new org.pragmatica.kiln.core.temper.HostsRead.Answered(List.of()));

        }


        @Override
        public Promise<GateRead> gate(Sha head) {
            return Promise.success(new GateRead.Answered(head.value(), "PASS", none(), none()));
        }
    }

    // ---- GitHub fake -------------------------------------------------------------------------------------------------
    /// Every read of one clean cycle: PR #1 open on `main` at `head`, PR #2 skipped (its head ref is no branch name),
    /// `main` requiring `ci`, which succeeded. `pullsStatus` other than 200 fails the pulls read.
    private URI fakeGitHub(String head, int pullsStatus) throws IOException {
        var answers = new ConcurrentHashMap<String, String>();
        var t0 = "\"2026-10-30T10:00:00Z\"";

        answers.put(PULLS, "[" + pr(1, "feature-1", head, t0) + "," + pr(2, "-bad", "b".repeat(40), t0) + "]");
        answers.put(ROOT + "pulls/1", "{\"number\":1,\"mergeable\":true,\"mergeable_state\":\"clean\"}");
        answers.put(ROOT + "commits/" + head, "{\"sha\":\"x\",\"commit\":{\"committer\":{\"date\":" + t0 + "}}}");
        answers.put(ROOT + "commits/" + head + "/check-runs?filter=all&per_page=100",
                    "{\"total_count\":1,\"check_runs\":[{\"id\":10,\"name\":\"ci\",\"app\":{\"id\":15368},\"status\":\"completed\","
                    + "\"conclusion\":\"success\",\"started_at\":" + t0 + "}]}");
        answers.put(ROOT + "commits/" + head + "/statuses?per_page=100", "[]");
        answers.put(ROOT + "branches/main", "{\"name\":\"main\",\"protection\":{\"enabled\":true,\"required_status_checks\":"
                                            + "{\"enforcement_level\":\"non_admins\",\"checks\":[{\"context\":\"ci\",\"app_id\":15368}]}}}");
        answers.put(ROOT + "rules/branches/main", "[]");
        answers.put(ROOT + "actions/runs?branch=main&per_page=20", "{\"total_count\":0,\"workflow_runs\":[]}");
        answers.put(ROOT + "actions/runs?event=schedule&per_page=20", "{\"total_count\":0,\"workflow_runs\":[]}");
        answers.put(ROOT + "issues?state=open&creator=app/github-actions", "[]");

        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> answer(exchange, answers, pullsStatus));
        server.start();
        closing.add(() -> server.stop(0));
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void answer(HttpExchange exchange, Map<String, String> answers, int pullsStatus) throws IOException {
        try (exchange) {
            var key = exchange.getRequestURI().toString();
            var feed = feedEmpty(key);
            var body = answers.getOrDefault(key, feed.orElse("{\"message\":\"Not Found\"}"));
            var status = key.equals(PULLS) ? pullsStatus : answers.containsKey(key) || feed.isPresent() ? 200 : 404;
            var bytes = (status == 200 ? body : "{\"message\":\"bad gateway\"}").getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-RateLimit-Remaining", "4990");
            exchange.getResponseHeaders().add("X-RateLimit-Limit", "5000");
            exchange.getResponseHeaders().add("X-RateLimit-Reset", Long.toString(Instant.now().plusSeconds(1800).getEpochSecond()));
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    /// The change feed's reads (CI interface T1 WP2) of a quiet repository: no runs, no issues.
    private static Optional<String> feedEmpty(String key) {
        if (key.startsWith(ROOT + "actions/runs?")) {
            return Optional.of("{\"total_count\":0,\"workflow_runs\":[]}");
        }
        return key.startsWith(ROOT + "issues?state=all") || key.startsWith(ROOT + "pulls?state=open")
               ? Optional.of("[]")
               : Optional.empty();
    }

    private static String pr(long number, String headRef, String head, String at) {
        return "{\"number\":" + number + ",\"title\":\"pr " + number + "\",\"state\":\"open\",\"draft\":false,\"base\":{\"ref\":\"main\"},"
               + "\"head\":{\"ref\":\"" + headRef + "\",\"sha\":\"" + head + "\",\"repo\":{\"full_name\":\"" + REPO + "\"}},"
               + "\"merged_at\":null,\"closed_at\":null,\"updated_at\":" + at + ",\"labels\":[],\"body\":null,\"user\":{\"login\":\"x\"}}";
    }

    // ---- git and scan fixtures ---------------------------------------------------------------------------------------
    /// A bare origin with `main` and `refs/pull/1/head` one commit ahead of it.
    private Path origin() throws IOException {
        var work = Files.createDirectories(dir.resolve("origin-work"));
        var bare = dir.resolve("origin.git");

        git(work, "init", "-q", "-b", "main");
        Files.writeString(work.resolve("f"), "1");
        git(work, "add", "f");
        git(work, "commit", "-q", "-m", "base");
        git(work, "checkout", "-q", "-b", "feature-1");
        Files.writeString(work.resolve("f"), "2");
        git(work, "commit", "-q", "-am", "feature");
        git(dir, "init", "-q", "--bare", bare.toString());
        git(work, "push", "-q", bare.toString(), "main", "feature-1", "feature-1:refs/pull/1/head");
        return bare;
    }

    /// A home with one checkout under `~/work/co`.
    private Path scannedHome() throws IOException {
        var home = Files.createDirectories(dir.resolve("home-" + System.nanoTime()));

        checkout(home.resolve("work/co/a"));
        return home;
    }

    private static void checkout(Path path) throws IOException {
        var checkout = Files.createDirectories(path);

        git(checkout, "init", "-q", "-b", "feature-1");
        Files.writeString(checkout.resolve("f"), "x");
        git(checkout, "add", "f");
        git(checkout, "commit", "-q", "-m", "local");
    }

    private static String git(Path in, String... args) {
        try {
            var command = new ArrayList<>(List.of("git"));
            command.addAll(List.of(args));
            var builder = HermeticProcess.builder(command).directory(in.toFile()).redirectErrorStream(true);
            builder.environment().putAll(GIT_ENV);
            var process = builder.start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();

            assertThat(process.waitFor()).as("git %s: %s", String.join(" ", args), out).isZero();
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String push(boolean complete, String... paths) {
        var lines = ScanFakes.listing(some("/Users/kiln"), paths);

        return String.join("\n", complete ? lines : lines.subList(0, lines.size() - 1)) + "\n";
    }

    private static List<String> macPaths(KilnStore store) {
        return await(store.checkouts().rows(HostName.MAC)).stream().map(row -> row.path().value()).toList();
    }

    // ---- HTTP --------------------------------------------------------------------------------------------------------
    private boolean recorded(String base, String... sources) {
        var answer = json(get(base + "/api/v1/sources"));

        return List.of(sources).stream().noneMatch(source -> "NONE".equals(state(answer, source)));
    }

    private static Map<String, Object> json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readString(response.body(), new TypeToken<Map<String, Object>>() {}).unwrap();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> json, String key) {
        return (List<Map<String, Object>>) json.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> json, String key) {
        return (Map<String, Object>) json.get(key);
    }

    private static List<Map<String, Object>> rows(HttpResponse<String> response) {
        return list(json(response), "rows");
    }

    private static Object state(Map<String, Object> json, String source) {
        return list(json, "sources").stream()
                                    .filter(entry -> source.equals(entry.get("source")))
                                    .findFirst()
                                    .orElseThrow()
                                    .get("state");
    }

    private HttpResponse<String> get(String uri) {
        return send(HttpRequest.newBuilder(URI.create(uri)).GET().build());
    }

    private HttpResponse<String> post(String uri, String body) {
        return send(HttpRequest.newBuilder(URI.create(uri)).header("X-Kiln-Ingest", "1").POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        awaitTrue(condition, WAIT);
    }

    private static void awaitTrue(BooleanSupplier condition, Duration within) throws InterruptedException {
        var deadline = Instant.now().plus(within);

        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("condition within %s", within).isBefore(deadline);
            Thread.sleep(250);
        }
    }

    private static <T> T await(Promise<T> promise) {
        return promise.await(AWAIT).unwrap();
    }
}
