package org.pragmatica.kiln.service.scan;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// The real `ops/kiln/kiln-ingest` (ruling 6) against a JDK `HttpServer` standing in for kiln on the loopback address:
/// only `kiln-ingest mac` or `kiln-ingest work` with no `SSH_ORIGINAL_COMMAND` runs; stdin goes verbatim to
/// `/api/v1/ingest/<verb>`, capped at 8 MiB for `mac` and 16 KiB for `work`; nothing of it is ever evaluated; the private
/// temporary file is removed; the exit code is curl's.
@Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class KilnIngestIT {
    private static final Path SCRIPT = Path.of("..", "ops", "kiln", "kiln-ingest").toAbsolutePath().normalize();
    private static final int MAX = 8 * 1024 * 1024;
    private static final int WORK_MAX = 16 * 1024;

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> ingestHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void kiln() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            ingestHeaders.add(exchange.getRequestHeaders().getFirst("X-Kiln-Ingest") + " origin=" + exchange.getRequestHeaders().getFirst("Origin"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var answer = (status.get() == 200 ? "{\"outcome\":\"COMPLETE\",\"rows\":1}" : "{\"error\":\"no\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        Files.createDirectories(dir.resolve("tmp"));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void mac_postsStdinVerbatim_printsKilnsAnswer_andRemovesItsFile() throws IOException {
        var body = "{\"kind\":\"stub\",\"path\":\"/Users/k/a\",\"activityAt\":1,\"commonDir\":\"/Users/k/a/.git\"}\n"
                   + "{\"end\":true,\"count\":1,\"nonGit\":0}\n";

        var ran = ingest(Map.of(), body, "mac");

        assertThat(ran.rc()).as(ran.stderr()).isZero();
        assertThat(ran.stdout()).isEqualTo("{\"outcome\":\"COMPLETE\",\"rows\":1}");
        assertThat(paths).containsExactly("POST /api/v1/ingest/mac");
        // WP9 verification (CSRF): the real curl sends the header kiln requires, and no Origin
        assertThat(ingestHeaders).containsExactly("1 origin=null");
        assertThat(bodies).containsExactly(body);
        assertThat(tmpFiles()).isEmpty();
    }

    @Test
    void anyOtherRequest_isRefused_andNothingIsSent() throws IOException {
        assertThat(ingest(Map.of("SSH_ORIGINAL_COMMAND", "mac"), "x", "mac").rc()).as("a client command").isEqualTo(64);
        assertThat(ingest(Map.of("SSH_ORIGINAL_COMMAND", "scan /"), "x", "mac").rc()).as("a client command").isEqualTo(64);
        assertThat(ingest(Map.of(), "x").rc()).as("no argument").isEqualTo(64);
        assertThat(ingest(Map.of(), "x", "scan").rc()).as("another verb").isEqualTo(64);
        assertThat(ingest(Map.of(), "x", "mac", "extra").rc()).as("two arguments").isEqualTo(64);
        assertThat(ingest(Map.of(), "x", "mac ").rc()).as("not exactly mac").isEqualTo(64);
        assertThat(ingest(Map.of(), "x", "work ").rc()).as("not exactly work").isEqualTo(64);
        assertThat(ingest(Map.of(), "x", "work", "mac").rc()).as("two verbs").isEqualTo(64);
        assertThat(ingest(Map.of("SSH_ORIGINAL_COMMAND", "work"), "x", "work").rc()).as("a client command on the work key").isEqualTo(64);
        assertThat(ingest(Map.of(), "x", "../work").rc()).as("a path, not a verb").isEqualTo(64);
        assertThat(ingest(Map.of("KILN_PORT", "7171; touch " + dir.resolve("pwned")), "x", "mac").rc()).as("a bad port").isEqualTo(64);
        assertThat(ingest(Map.of("KILN_PORT", "70000"), "x", "mac").rc()).as("a port past 65535").isEqualTo(64);
        assertThat(paths).isEmpty();
        assertThat(dir.resolve("pwned")).doesNotExist();
    }

    @Test
    void anEmptyClientCommand_isNoCommand() throws IOException {
        assertThat(ingest(Map.of("SSH_ORIGINAL_COMMAND", ""), "x", "mac").rc()).isZero();
        assertThat(bodies).containsExactly("x");
    }

    @Test
    void aPushOver8MiB_isRefused_andNothingIsSent() throws IOException {
        var ran = ingest(Map.of(), "x".repeat(MAX + 1), "mac");

        assertThat(ran.rc()).isEqualTo(65);
        assertThat(paths).isEmpty();
        assertThat(tmpFiles()).isEmpty();
    }

    @Test
    void aPushOfExactly8MiB_isSent() throws IOException {
        assertThat(ingest(Map.of(), "x".repeat(MAX), "mac").rc()).isZero();
        assertThat(bodies).singleElement().satisfies(body -> assertThat(body).hasSize(MAX));
    }

    /// The work key's verb reaches only the ledger route, verbatim, with kiln's header and no Origin.
    @Test
    void work_postsStdinVerbatim_toTheLedgerRoute() throws IOException {
        var body = "{\"item\":\"T1-WP5\",\"phase\":\"verify\",\"round\":2,\"note\":\"$(touch " + dir.resolve("pwned") + ")\"}";

        var ran = ingest(Map.of(), body, "work");

        assertThat(ran.rc()).as(ran.stderr()).isZero();
        assertThat(paths).containsExactly("POST /api/v1/ingest/work");
        assertThat(ingestHeaders).containsExactly("1 origin=null");
        assertThat(bodies).containsExactly(body);
        assertThat(dir.resolve("pwned")).doesNotExist();
        assertThat(tmpFiles()).isEmpty();
    }

    @Test
    void aWorkEventOver16KiB_isRefused_andNothingIsSent() throws IOException {
        var ran = ingest(Map.of(), "x".repeat(WORK_MAX + 1), "work");

        assertThat(ran.rc()).isEqualTo(65);
        assertThat(paths).isEmpty();
        assertThat(tmpFiles()).isEmpty();
        assertThat(ingest(Map.of(), "x".repeat(WORK_MAX), "work").rc()).isZero();
        assertThat(bodies).singleElement().satisfies(sent -> assertThat(sent).hasSize(WORK_MAX));
    }

    /// kiln's refusal of an event (a 400 naming the field) is printed and exits 22.
    @Test
    void aRefusedWorkEvent_isCurlsExit22_withKilnsAnswerPrinted() throws IOException {
        status.set(400);

        var ran = ingest(Map.of(), "{}", "work");

        assertThat(ran.rc()).isEqualTo(22);
        assertThat(ran.stdout()).contains("{\"error\":\"no\"}");
    }

    @Test
    void theInput_isNeverEvaluated() throws IOException {
        var body = "$(touch " + dir.resolve("pwned") + ")\n`touch " + dir.resolve("pwned") + "`\n; touch " + dir.resolve("pwned") + "\n";

        assertThat(ingest(Map.of(), body, "mac").rc()).isZero();
        assertThat(bodies).containsExactly(body);
        assertThat(dir.resolve("pwned")).doesNotExist();
    }

    @Test
    void kilnsHttpError_isCurlsExit22_withItsBodyPrinted() throws IOException {
        status.set(500);

        var ran = ingest(Map.of(), "x", "mac");

        assertThat(ran.rc()).isEqualTo(22);
        assertThat(ran.stdout()).contains("{\"error\":\"no\"}");
        assertThat(tmpFiles()).isEmpty();
    }

    @Test
    void kilnNotListening_isCurlsExit7() throws IOException {
        var port = server.getAddress().getPort();
        server.stop(0);

        assertThat(ingest(Map.of("KILN_PORT", String.valueOf(port)), "x", "mac").rc()).isEqualTo(7);
    }

    @Test
    void theTemporaryFile_isPrivate() throws IOException {
        var spy = ScanFixture.script(dir.resolve("bin/curl"), "for a in \"$@\"; do case $a in @*) ls -l \"${a#@}\";; esac; done\n");

        var ran = ScanFixture.run(dir, environment(Map.of("PATH", spy.getParent() + ":" + System.getenv("PATH"))), "x",
                                  List.of("bash", SCRIPT.toString(), "mac"));

        assertThat(ran.stdout()).startsWith("-rw-------");
        assertThat(tmpFiles()).isEmpty();
    }

    private ScanFixture.Ran ingest(Map<String, String> extra, String stdin, String... args) {
        var builder = HermeticProcess.builder(Stream.concat(Stream.of("bash", SCRIPT.toString()), Stream.of(args)).toList());
        builder.environment().clear();
        builder.environment().putAll(environment(extra));
        return ScanFixture.ran(builder, stdin);
    }

    private Map<String, String> environment(Map<String, String> extra) {
        var env = new HashMap<String, String>(Map.of("PATH", System.getenv("PATH"),
                                                               "HOME", dir.toString(),
                                                               "TMPDIR", dir.resolve("tmp").toString(),
                                                               "KILN_PORT", String.valueOf(server.getAddress().getPort())));
        env.putAll(extra);
        return env;
    }

    private List<Path> tmpFiles() throws IOException {
        try (var files = Files.list(dir.resolve("tmp"))) {
            return files.toList();
        }
    }
}
