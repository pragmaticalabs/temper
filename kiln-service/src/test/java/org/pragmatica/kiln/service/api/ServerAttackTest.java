package org.pragmatica.kiln.service.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.service.http.LoopbackServer;
import org.pragmatica.kiln.service.http.Router;
import org.pragmatica.kiln.service.scan.MacIngest;
import org.pragmatica.kiln.service.scan.ScanFakes;
import org.pragmatica.kiln.service.store.ChangeStore;
import org.pragmatica.kiln.service.store.KilnStore;
import org.pragmatica.kiln.service.store.Page;
import org.pragmatica.kiln.service.store.ViewStore;
import org.pragmatica.kiln.service.store.WorkEvent;
import org.pragmatica.kiln.service.store.WorkStore;
import org.pragmatica.kiln.service.web.Periods;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// The server under attack over raw sockets (adopted from the WP9 adversarial verification, then hardened per its
/// rulings): CSRF and DNS rebinding are refused, the body caps hold for both framings, a body that is not UTF-8 or never
/// arrives gets a 4xx, a refusal never echoes the query, and every answer is `nosniff`.
class ServerAttackTest {
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final Instant WATERMARK = NOW.minusSeconds(3);

    private final ScanFakes.RecordingStore checkouts = new ScanFakes.RecordingStore();
    private final ScanFakes.RecordingSources sources = new ScanFakes.RecordingSources();
    private final List<String> pages = new CopyOnWriteArrayList<>();
    private LoopbackServer server;

    private final ViewStore views = (ViewStore) Proxy.newProxyInstance(ViewStore.class.getClassLoader(), new Class<?>[] {ViewStore.class}, (_, method, args) -> switch (method.getName()) {
        case "checkouts" -> {
            pages.add(String.valueOf(args[2]));
            yield Promise.success(new Page<>(List.of(), (int) args[2], 1));
        }
        case "nonGit", "openPrs", "counts", "passes", "listed" -> Promise.success(List.of());
        case "digest" -> Promise.success(Option.none());
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final ChangeStore changes = (ChangeStore) Proxy.newProxyInstance(ChangeStore.class.getClassLoader(), new Class<?>[] {ChangeStore.class}, (_, method, args) -> switch (method.getName()) {
        case "watermark" -> WATERMARK;
        case "since" -> Promise.success(List.of());
        case "oldest" -> Promise.success(Option.some(NOW.minusSeconds(86_400)));
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final List<WorkEvent> recorded = new CopyOnWriteArrayList<>();

    private final WorkStore work = (WorkStore) Proxy.newProxyInstance(WorkStore.class.getClassLoader(), new Class<?>[] {WorkStore.class}, (_, method, args) -> switch (method.getName()) {
        case "record" -> recorded.add((WorkEvent) args[0]) ? Promise.success(1L) : null;
        default -> throw new UnsupportedOperationException(method.getName());
    });

    private final KilnStore store = (KilnStore) Proxy.newProxyInstance(KilnStore.class.getClassLoader(), new Class<?>[] {KilnStore.class}, (_, method, _) -> switch (method.getName()) {
        case "views" -> views;
        case "sources" -> sources;
        case "changes" -> changes;
        case "checkouts" -> checkouts;
        case "work" -> work;
        default -> throw new UnsupportedOperationException(method.getName());
    });

    @BeforeEach
    void start() {
        var routes = ApiRoutes.apiRoutes(store,
                                         MacIngest.macIngest(checkouts, sources, List.of("~/src/pragmatica*"), () -> NOW, () -> {}),
                                         List.of(BranchName.branchName("main").unwrap()),
                                         List.of(HostName.hostName("bigboy").unwrap()),
                                         Option::none,
                                         List::of,
                                         () -> Periods.periods(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(120)),
                                         Periods.periods(Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(60), Duration.ofSeconds(120)),
                                         () -> NOW,
                                         () -> null);
        server = LoopbackServer.start(0, Router.router(routes.routes())).unwrap();
    }

    @AfterEach
    void stop() {
        server.stop().await(TimeSpan.timeSpan(10).seconds());
    }

    record Answer(int status, String head, String body) {}

    private Answer raw(byte[] request, int timeoutMs) throws IOException {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.port()), 2_000);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            var text = new String(readAll(socket.getInputStream()), StandardCharsets.UTF_8);
            var split = text.indexOf("\r\n\r\n");
            var head = split < 0 ? text : text.substring(0, split);
            var status = head.startsWith("HTTP/1.1 ") ? Integer.parseInt(head.substring(9, 12)) : -1;
            return new Answer(status, head, split < 0 ? "" : text.substring(split + 4));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        try {
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, n);
                if (complete(buffer.toByteArray())) {
                    break;
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            // the answer so far is all there is
        }
        return buffer.toByteArray();
    }

    /// A response with a Content-Length that has arrived in full.
    private static boolean complete(byte[] bytes) {
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        var split = text.indexOf("\r\n\r\n");
        if (split < 0) {
            return false;
        }
        var head = text.substring(0, split).toLowerCase();
        var at = head.indexOf("content-length: ");
        if (at < 0) {
            return false;
        }
        var length = Integer.parseInt(head.substring(at + 16).split("\r\n")[0].trim());
        return bytes.length >= split + 4 + length;
    }

    private Answer get(String target, String host) throws IOException {
        return raw(("GET " + target + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1), 10_000);
    }

    private String local() {
        return "127.0.0.1:" + server.port();
    }

    private static byte[] concat(byte[] head, byte[] body) {
        var all = Arrays.copyOf(head, head.length + body.length);
        System.arraycopy(body, 0, all, head.length, body.length);
        return all;
    }

    // ---- CSRF and DNS rebinding ---------------------------------------------------------------------------------------
    /// A browser page's `fetch(..., {method:"POST", mode:"no-cors", body})` is a simple request: text/plain, a foreign
    /// Origin, no preflight. A complete push with no rows and a forged home deletes every Mac row under the roots.
    @Test
    void csrf_aCrossOriginTextPlainPush_isRefused() throws IOException {
        var body = String.join("\n", ScanFakes.listing(Option.some("/Users/kiln"))).getBytes(StandardCharsets.UTF_8);
        var head = ("POST /api/v1/ingest/mac HTTP/1.1\r\nHost: " + local() + "\r\nOrigin: https://evil.example\r\n"
                    + "Content-Type: text/plain;charset=UTF-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);

        var withHeader = ("POST /api/v1/ingest/mac HTTP/1.1\r\nHost: " + local() + "\r\nOrigin: https://evil.example\r\nX-Kiln-Ingest: 1\r\n"
                          + "Content-Type: text/plain;charset=UTF-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var noHeader = ("POST /api/v1/ingest/mac HTTP/1.1\r\nHost: " + local() + "\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);

        assertThat(raw(concat(head, body), 10_000).status()).isEqualTo(403);
        assertThat(raw(concat(withHeader, body), 10_000).status()).as("an Origin, even with the header").isEqualTo(403);
        assertThat(raw(concat(noHeader, body), 10_000).status()).as("no X-Kiln-Ingest").isEqualTo(403);
        assertThat(checkouts.applied).as("rows untouched").isEmpty();
        assertThat(ingest(body).status()).as("kiln-ingest's own request").isEqualTo(200);
        assertThat(checkouts.applied).hasSize(1);
    }

    /// The same browser request against the work ledger: a forged event is refused whatever its headers but the two
    /// `kiln-ingest work` sends, and the store records nothing until that one.
    @Test
    void csrf_aCrossOriginWorkEvent_isRefused() throws IOException {
        var body = "{\"item\":\"T1-WP5\",\"phase\":\"merged\",\"round\":9}".getBytes(StandardCharsets.UTF_8);
        var browser = ("POST /api/v1/ingest/work HTTP/1.1\r\nHost: " + local() + "\r\nOrigin: https://evil.example\r\nX-Kiln-Ingest: 1\r\n"
                       + "Content-Type: text/plain;charset=UTF-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var noHeader = ("POST /api/v1/ingest/work HTTP/1.1\r\nHost: " + local() + "\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var own = ("POST /api/v1/ingest/work HTTP/1.1\r\nHost: " + local() + "\r\nX-Kiln-Ingest: 1\r\nContent-Length: " + body.length
                   + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);

        assertThat(raw(concat(browser, body), 10_000).status()).as("an Origin, even with the header").isEqualTo(403);
        assertThat(raw(concat(noHeader, body), 10_000).status()).as("no X-Kiln-Ingest").isEqualTo(403);
        assertThat(recorded).isEmpty();
        assertThat(raw(concat(own, body), 10_000).status()).as("kiln-ingest work's own request").isEqualTo(200);
        assertThat(recorded).hasSize(1);
    }

    /// A push as `kiln-ingest` sends it: `X-Kiln-Ingest: 1`, no `Origin`.
    private Answer ingest(byte[] body) throws IOException {
        return raw(concat(("POST /api/v1/ingest/mac HTTP/1.1\r\nHost: " + local() + "\r\nX-Kiln-Ingest: 1\r\nContent-Length: " + body.length
                           + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1), body), 30_000);
    }

    /// DNS rebinding: a page on evil.example rebinds its name to 127.0.0.1; the browser sends `Host: evil.example:PORT`
    /// and the page may read the answer (same origin to the browser).
    @Test
    void dnsRebinding_aForeignHostHeader_isRefused() throws IOException {
        var foreign = get("/api/v1/sources", "evil.example:" + server.port());
        var noHost = raw("GET /api/v1/sources HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), 10_000);

        assertThat(foreign.status()).isEqualTo(421);
        assertThat(foreign.body()).doesNotContain("source");
        assertThat(get("/api/v1/sources", "127.0.0.1.evil.example").status()).isEqualTo(421);
        assertThat(get("/api/v1/sources", "[::1]:" + server.port()).status()).isEqualTo(421);
        assertThat(noHost.status()).as("no Host").isEqualTo(421);
        // an ssh -L tunnel's own local port, and a browser's localhost
        assertThat(get("/api/v1/sources", "localhost:17171").status()).isEqualTo(200);
        assertThat(get("/api/v1/sources", "LOCALHOST").status()).isEqualTo(200);
        assertThat(get("/api/v1/sources", "127.0.0.1").status()).isEqualTo(200);
    }

    // ---- bodies --------------------------------------------------------------------------------------------------------
    /// 8 MiB of bytes that are not UTF-8: within the server's byte cap, but each byte decodes to U+FFFD (3 bytes), so
    /// MacIngest's own byte cap trips on the re-encoded string and the route answers 500, not a 4xx.
    @Test
    void invalidUtf8_withinTheByteCap_isAClientError_notA500() throws IOException {
        var body = new byte[LoopbackServer.MAX_BODY];
        Arrays.fill(body, (byte) 0xFF);
        var answer = ingest(body);

        assertThat(answer.status()).as("an 8 MiB non-UTF-8 body").isEqualTo(400);
        assertThat(ingest(new byte[] {'a', (byte) 0xC3}).status()).as("a truncated sequence").isEqualTo(400);
        assertThat(checkouts.applied).isEmpty();
    }

    private static byte[] chunked(int size) {
        var out = new ByteArrayOutputStream();
        var piece = 64 * 1024;
        var line = new byte[piece];
        Arrays.fill(line, (byte) '\n');
        var left = size;
        while (left > 0) {
            var n = Math.min(piece, left);
            out.writeBytes((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(line, 0, n);
            out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            left -= n;
        }
        out.writeBytes("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    @Test
    void chunked_exactly8MiB_reachesTheRoute_andOneMoreIs413() throws IOException {
        var head = ("POST /api/v1/ingest/mac HTTP/1.1\r\nHost: " + local() + "\r\nX-Kiln-Ingest: 1\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);

        var exact = raw(concat(head, chunked(LoopbackServer.MAX_BODY)), 30_000);
        var over = raw(concat(head, chunked(LoopbackServer.MAX_BODY + 1)), 30_000);

        assertThat(exact.status()).isEqualTo(200);
        assertThat(over.status()).isEqualTo(413);
    }

    @Test
    void contentLength_exactly8MiB_reachesTheRoute_andOneMoreIs413() throws IOException {
        var exact = new byte[LoopbackServer.MAX_BODY];
        Arrays.fill(exact, (byte) '\n');
        var over = Arrays.copyOf(exact, LoopbackServer.MAX_BODY + 1);
        over[LoopbackServer.MAX_BODY] = '\n';

        var a = ingest(exact);
        var b = ingest(over);

        assertThat(a.status()).isEqualTo(200);
        assertThat(b.status()).isEqualTo(413);
    }

    /// A client that announces a body and never sends it gets 408 once [LoopbackServer#READ] has passed.
    @Test
    void slowClient_announcedBodyNeverSent_is408_afterTheReadDeadline() throws IOException {
        var start = System.nanoTime();
        var answer = raw(("POST /api/v1/ingest/mac HTTP/1.1\r\nHost: " + local() + "\r\nX-Kiln-Ingest: 1\r\nContent-Length: 10\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1), 75_000);
        var waited = Duration.ofNanos(System.nanoTime() - start);

        assertThat(answer.status()).isEqualTo(408);
        assertThat(waited).isBetween(LoopbackServer.READ.minusSeconds(1), LoopbackServer.READ.plusSeconds(10));
        assertThat(checkouts.applied).isEmpty();
    }

    // ---- queries ---------------------------------------------------------------------------------------------------------
    @Test
    void queries_malformedOrHostile_areAnswered4xx_neverDropped() throws IOException {
        assertThat(get("/checkouts?status=%zz", local()).status()).as("%%zz").isBetween(400, 499);
        assertThat(get("/checkouts?status=%E0%A4", local()).status()).as("truncated UTF-8").isBetween(400, 499);
        assertThat(get("/checkouts?status=IN-USE", local()).status()).as("IN-USE").isEqualTo(400);
        assertThat(get("/checkouts?status=in_use", local()).status()).as("in_use").isEqualTo(400);
        assertThat(get("/checkouts?status=BOGUS", local()).status()).as("BOGUS").isEqualTo(400);
        assertThat(get("/checkouts", local()).status()).as("no status").isEqualTo(400);
        assertThat(get("/api/v1/checkouts?page=-1", local()).status()).as("page -1").isEqualTo(400);
        assertThat(get("/api/v1/checkouts?page=0", local()).status()).as("page 0").isEqualTo(400);
        assertThat(get("/api/v1/checkouts?page=99999999999", local()).status()).as("page overflow").isEqualTo(400);
        assertThat(get("/api/v1/checkouts?page=2147483647", local()).status()).as("page max int").isEqualTo(200);
        assertThat(get("/api/v1/checkouts?page=1&page=-5", local()).status()).as("repeat").isEqualTo(200);
        assertThat(get("/api/v1/checkouts?host=%3Cscript%3E", local()).status()).as("host script").isEqualTo(400);
        assertThat(get("/non-git?host=bigboy&root=%3Cscript%3Ealert(1)%3C/script%3E", local()).body()).doesNotContain("<script>");
        assertThat(pages).contains("2147483647", "1");
    }

    @Test
    void summary_edges() throws IOException {
        var future = get("/api/v1/summary?since=2099-01-01T00:00:00Z", local());
        var bad = get("/api/v1/summary?since=yesterday", local());
        var old = get("/api/v1/summary?since=2000-01-01T00:00:00Z&format=text", local());

        assertThat(future.status()).isEqualTo(200);
        assertThat(future.body()).contains(WATERMARK.toString());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(old.body().lines().findFirst().orElse("")).startsWith("gap");
    }

    @Test
    void headers_head_andNosniff() throws IOException {
        var head = raw(("HEAD / HTTP/1.1\r\nHost: " + local() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1), 5_000);
        var refused = get("/checkouts?status=%3Chtml%3E%3Cscript%3Ealert(1)%3C/script%3E", local());

        assertThat(head.status()).as("HEAD").isEqualTo(405);
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.body()).doesNotContain("<", "script", "html");
        for (var answer : List.of(head, refused, get("/api/v1/sources", local()), get("/nope", local()), get("/", "evil.example"))) {
            assertThat(answer.head().toLowerCase()).contains("x-content-type-options: nosniff");
        }
    }

    @Test
    void ipv6Loopback_andWildcard_areNotBound() {
        assertThat(connects("::1")).as("::1").isFalse();
        assertThat(server.address().getAddress().isLoopbackAddress()).isTrue();
    }

    private boolean connects(String address) {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(address), server.port()), 1_000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
