package org.pragmatica.temper.dist;

import org.pragmatica.temper.testsupport.HermeticProcess;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// `ops/mac/temper-deadman` (design §3 L2) against stubs: `ssh-keyscan` and `osascript` first on
/// `PATH` (each logs its argv; the banner stub prints a key line only when told to), the controller a JDK HTTP server
/// on loopback reached by the real `curl`, and the clock `TEMPER_DEADMAN_NOW`. Runs on Linux (GNU date) here; the
/// script also takes the BSD path on the Mac.
///
/// Probes: drop the hour throttle → [#failure_notifiesAtMostOncePerHour_perCondition] red; drop the recovery notice →
/// [#recovery_notifiesOnce] red; compare the sleep gap with `>= 0` → [#sleepGap_isReported_fromTheHeartbeatLog]'s
/// no-gap half red.
class MacDeadmanIT {
    private static final Path SCRIPT = Path.of("..", "ops", "mac", "temper-deadman").toAbsolutePath().normalize();
    private static final Path PLIST = Path.of("..", "ops", "mac", "com.pragmatica.temper-deadman.plist").toAbsolutePath().normalize();
    private static final long T = Instant.parse("2026-11-03T09:00:00Z").getEpochSecond();
    private static final String TOKEN = "read-token-123";
    private static final String OK_BEAT = "{\"v\":1,\"kind\":\"heartbeat\",\"epoch\":\"e\",\"asOf\":\"2026-11-03T09:00:00.250Z\","
                                          + "\"seqHead\":3,\"sources\":{},\"selfCheck\":{\"ok\":true,\"failed\":[],"
                                          + "\"advisory\":{\"load1\":1.0,\"cores\":8,\"memSomeAvg60\":0.1}},\"kilnLag\":0}";
    private static final String BAD_BEAT = OK_BEAT.replace("\"ok\":true,\"failed\":[]", "\"ok\":false,\"failed\":[\"ssh-banner\"]");

    record Outcome(int rc, String output) {}

    @TempDir
    Path dir;

    private Path home;
    private Path bin;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int heartbeatStatus = 200;
    private volatile String heartbeatBody = OK_BEAT;
    private volatile long heartbeatDelayMs = 0;
    private volatile String history = "";

    @BeforeEach
    void stubs() throws IOException {
        home = Files.createDirectories(dir.resolve("home"));
        TestHosts.write(home);
        bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(Files.createDirectories(home.resolve(".config/temper")).resolve("read-token"), TOKEN + "\n");
        stub("ssh-keyscan", "echo \"$*\" >> \"$STUBS/ssh-keyscan.args\"\n[ -f \"$STUBS/banner\" ] && cat \"$STUBS/banner\"\nexit 0\n");
        stub("osascript", "echo \"$*\" >> \"$STUBS/notes\"\n");
        stub("curl", "echo \"$*\" >> \"$STUBS/curl.args\"\nexec /usr/bin/curl \"$@\"\n");
        banner(true);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/fleet/heartbeat", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        requests.add(exchange.getRequestURI() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
        var history = exchange.getRequestURI().getPath().endsWith("/history");
        var body = (history ? this.history : heartbeatBody).getBytes(StandardCharsets.UTF_8);
        var status = history ? 200 : heartbeatStatus;
        try {
            Thread.sleep(history ? 0 : heartbeatDelayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void stub(String name, String body) throws IOException {
        var file = Files.writeString(bin.resolve(name), "#!/bin/bash\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private void banner(boolean answers) throws IOException {
        var file = dir.resolve("banner");
        if (answers) {
            Files.writeString(file, "# 192.0.2.10:22 SSH-2.0-OpenSSH_9.6\n192.0.2.10 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI\n");
        } else {
            Files.deleteIfExists(file);
        }
    }

    private Outcome run(long now) throws IOException, InterruptedException {
        return run(now, true);
    }

    private Outcome run(long now, boolean withUrl) throws IOException, InterruptedException {
        var builder = HermeticProcess.builder(List.of("bash", SCRIPT.toString()));
        var env = builder.environment();
        env.put("HOME", home.toString());
        env.put("PATH", bin + ":/usr/bin:/bin");
        env.put("STUBS", dir.toString());
        env.put("TEMPER_DEADMAN_NOW", Long.toString(now));
        if (withUrl) {
            env.put("TEMPER_DEADMAN_URL", url());
        }
        builder.redirectErrorStream(true);
        var process = builder.start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Outcome(process.exitValue(), output);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private List<String> notes() throws IOException {
        var file = dir.resolve("notes");
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    private String log() throws IOException {
        var file = home.resolve("Library/Logs/temper-deadman.log");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    private Path stateFile() {
        return home.resolve("Library/Application Support/temper-deadman/state");
    }

    @Test
    void allGood_notifiesNothing_andRecordsTheRun() throws Exception {
        var outcome = run(T);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(notes()).isEmpty();
        assertThat(log()).isEmpty();
        assertThat(stateFile()).hasContent("asOf=2026-11-03T09:00:00.250Z\nrun=" + T);
        assertThat(Files.readString(dir.resolve("ssh-keyscan.args")).strip()).isEqualTo("-T 5 192.0.2.10");
        assertThat(requests).containsExactly("/api/v1/fleet/heartbeat Bearer " + TOKEN);
    }

    @Test
    void bannerFailure_notifies_andLogs() throws Exception {
        banner(false);

        var outcome = run(T);

        assertThat(outcome.rc()).isEqualTo(1);
        assertThat(notes()).singleElement().asString()
                           .contains("display notification \"bigboy banner: no ssh banner from 192.0.2.10 within 5 s\"");
        assertThat(log()).contains("2026-11-03T09:00:00Z FAIL banner: no ssh banner");
    }

    @Test
    void heartbeatFailure_notifies() throws Exception {
        heartbeatStatus = 503;

        var outcome = run(T);

        assertThat(outcome.rc()).isEqualTo(1);
        assertThat(notes()).singleElement().asString().contains("bigboy heartbeat: no heartbeat from " + url());
        assertThat(log()).contains("FAIL heartbeat");
    }

    /// The heartbeat must answer within 10 s: a controller that takes 12 s counts as no heartbeat.
    @Test
    void slowHeartbeat_countsAsNone() throws Exception {
        heartbeatDelayMs = 12_000;

        var outcome = run(T);

        assertThat(outcome.rc()).isEqualTo(1);
        assertThat(notes()).singleElement().asString().contains("bigboy heartbeat");
    }

    @Test
    void answerThatIsNotAHeartbeat_notifies() throws Exception {
        heartbeatBody = "<html>proxy error</html>";

        run(T);

        assertThat(notes()).singleElement().asString().contains("bigboy heartbeat: the answer is not a heartbeat line");
    }

    @Test
    void selfCheckFalse_notifies() throws Exception {
        heartbeatBody = BAD_BEAT;

        var outcome = run(T);

        assertThat(outcome.rc()).isEqualTo(1);
        assertThat(notes()).singleElement().asString().contains("bigboy selfcheck: selfCheck.ok=false failed=ssh-banner");
    }

    /// One notification per condition per hour; each condition throttled on its own; every failing run logged.
    @Test
    void failure_notifiesAtMostOncePerHour_perCondition() throws Exception {
        history = minutes("2026-11-03T09:", 1, 59);
        banner(false);
        run(T);
        run(T + 60);
        heartbeatBody = BAD_BEAT;
        run(T + 120);
        run(T + 3599);

        assertThat(notes()).hasSize(2);
        assertThat(notes().get(0)).contains("bigboy banner");
        assertThat(notes().get(1)).contains("bigboy selfcheck");
        assertThat(log().lines().filter(line -> line.contains("FAIL banner"))).hasSize(4);

        run(T + 3600);

        assertThat(notes()).hasSize(3);
        assertThat(notes().get(2)).contains("bigboy banner");
    }

    @Test
    void recovery_notifiesOnce() throws Exception {
        heartbeatStatus = 500;
        run(T);
        heartbeatStatus = 200;
        var recovered = run(T + 60);
        run(T + 120);

        assertThat(recovered.rc()).isZero();
        assertThat(notes()).hasSize(2);
        assertThat(notes().get(1)).contains("bigboy heartbeat recovered");
        assertThat(log()).contains("RECOVERED heartbeat");
    }

    /// After a sleep (previous run > 5 min ago), a gap over 15 min in the heartbeat log is reported once as
    /// `bigboy silent <from>..<to>`; a log without one reports nothing; a short pause reads no log at all.
    @Test
    void sleepGap_isReported_fromTheHeartbeatLog() throws Exception {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "asOf=2026-11-03T08:00:00Z\nrun=" + (T - 3600) + "\n");
        history = minutes("2026-11-03T08:", 1, 10).replace("]}", ",") + minutes("2026-11-03T08:", 31, 59).substring("{\"ticks\":[".length());

        var outcome = run(T);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(notes()).singleElement().asString().contains("bigboy silent 2026-11-03T08:10:00Z..2026-11-03T08:31:00Z");
        assertThat(log()).contains("SILENT bigboy silent 2026-11-03T08:10:00Z..2026-11-03T08:31:00Z");
        assertThat(requests).contains("/api/v1/fleet/heartbeat/history?since=2026-11-03T08:00:00Z Bearer " + TOKEN);

        Files.writeString(stateFile(), "asOf=2026-11-03T08:00:00Z\nrun=" + (T - 3600) + "\n");
        history = minutes("2026-11-03T08:", 1, 59);
        run(T);

        assertThat(notes()).hasSize(1);

        requests.clear();
        run(T + 240);

        assertThat(requests).noneMatch(request -> request.contains("/history"));
    }

    /// A heartbeat log with no tick since the last asOf is one gap up to now; the run still records itself.
    @Test
    void sleepGap_withNoTickAtAll_isReported_andTheRunRecorded() throws Exception {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "asOf=2026-11-03T08:00:00Z\nrun=" + (T - 3600) + "\n");

        var outcome = run(T);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(notes()).singleElement().asString().contains("bigboy silent 2026-11-03T08:00:00Z..2026-11-03T09:00:00Z");
        assertThat(stateFile()).hasContent("asOf=2026-11-03T09:00:00.250Z\nrun=" + T);
    }

    /// No URL anywhere: exit 2 with a log line (not a silent abort).
    @Test
    void missingUrl_exitsTwo_withALogLine() throws Exception {
        var outcome = run(T, false);

        assertThat(outcome.rc()).isEqualTo(2);
        assertThat(log()).contains("no controller URL");
        assertThat(notes()).singleElement().asString().contains("bigboy config: no controller URL");
    }

    /// Without TEMPER_DEADMAN_URL the URL is the CLI's own (`url = "…"` in ~/.config/temper/config).
    @Test
    void url_comesFromTheCliConfig() throws Exception {
        Files.writeString(home.resolve(".config/temper/config"), "# temper\nurl = \"" + url() + "\"\n");

        var outcome = run(T, false);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(requests).hasSize(1);
    }

    /// The probe host is bigboy's address from the bb host config (no address is baked into the script); with neither the
    /// config line nor TEMPER_DEADMAN_HOST it is a missing setting: notified, logged, exit 2, and nothing is probed.
    @Test
    void missingProbeHost_notifies_andExitsTwo() throws Exception {
        Files.delete(home.resolve(".config/bb-temper/hosts"));

        var outcome = run(T);

        assertThat(outcome.rc()).isEqualTo(2);
        assertThat(log()).contains("FAIL config: no probe host");
        assertThat(notes()).singleElement().asString().contains("bigboy config: no probe host");
        assertThat(dir.resolve("ssh-keyscan.args")).doesNotExist();
    }

    /// A missing setting is a failure like any other: notified (throttled), logged, exit 2.
    @Test
    void missingTokenFile_notifies_andExitsTwo() throws Exception {
        Files.delete(home.resolve(".config/temper/read-token"));

        var outcome = run(T);
        run(T + 60);

        assertThat(outcome.rc()).isEqualTo(2);
        assertThat(log()).contains("FAIL config: no token file");
        assertThat(notes()).singleElement().asString().contains("bigboy config: no token file");
        assertThat(requests).isEmpty();
    }

    /// A throttle file that does not hold a number is reset and logged; the run goes on and notifies.
    @Test
    void corruptThrottleFile_isReset_andTheRunGoesOn() throws Exception {
        var marker = home.resolve("Library/Application Support/temper-deadman/notified-banner");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "abc\n");
        banner(false);

        var outcome = run(T);

        assertThat(outcome.rc()).as(outcome.output()).isEqualTo(1);
        assertThat(log()).contains("held 'abc'; reset");
        assertThat(notes()).singleElement().asString().contains("bigboy banner");
        assertThat(requests).containsExactly("/api/v1/fleet/heartbeat Bearer " + TOKEN);
        assertThat(marker).hasContent(Long.toString(T));
    }

    /// A token holding `\` and `"` reaches the controller unchanged: both are escaped in curl's config syntax.
    @Test
    void token_withBackslashAndQuote_arrivesUnchanged() throws Exception {
        var token = "a\\b\"c";
        Files.writeString(home.resolve(".config/temper/read-token"), token + "\n");

        var outcome = run(T);

        assertThat(outcome.rc()).as(outcome.output()).isZero();
        assertThat(requests).containsExactly("/api/v1/fleet/heartbeat Bearer " + token);
    }

    /// The token reaches curl on stdin, never on its argv (where `ps` shows it); the request still carries it.
    @Test
    void token_neverOnCurlsArgv() throws Exception {
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), "asOf=2026-11-03T08:00:00Z\nrun=" + (T - 3600) + "\n");
        history = minutes("2026-11-03T08:", 1, 59);

        run(T);

        assertThat(Files.readString(dir.resolve("curl.args"))).doesNotContain(TOKEN)
                                                              .contains("/api/v1/fleet/heartbeat/history");
        assertThat(requests).allMatch(request -> request.endsWith("Bearer " + TOKEN)).hasSize(2);
    }

    /// The plist runs the installed script every 60 s and at load; the header carries the owner's install commands.
    @Test
    void plistAndHeader_carryTheInstall() throws Exception {
        var plist = Files.readString(PLIST);
        var header = Files.readString(SCRIPT);

        assertThat(plist).contains("<string>com.pragmatica.temper-deadman</string>")
                         .contains("<key>StartInterval</key>\n    <integer>60</integer>")
                         .contains("<key>RunAtLoad</key>\n    <true/>")
                         .contains("<string>__HOME__/.local/bin/temper-deadman</string>");
        assertThat(header).contains("! launchctl bootstrap gui/$UID ~/Library/LaunchAgents/com.pragmatica.temper-deadman.plist")
                          .contains("! launchctl bootout gui/$UID/com.pragmatica.temper-deadman")
                          .startsWith("#!/bin/bash")
                          .contains("set -euo pipefail");
    }

    /// One `{"at":…}` tick per minute from `prefix + first` to `prefix + last` (minutes, zero-padded), in the ruled
    /// shape: `{"ticks":[…]}`. Each tick also carries an `asOf` an hour off, which must be ignored.
    private static String minutes(String prefix, int first, int last) {
        var rows = new ArrayList<String>();
        for (var minute = first; minute <= last; minute++) {
            rows.add("{\"at\":\"" + prefix + String.format("%02d", minute) + ":00Z\",\"asOf\":\"2026-11-03T03:00:00Z\"}");
        }
        return "{\"ticks\":[" + String.join(",", rows) + "]}";
    }
}
