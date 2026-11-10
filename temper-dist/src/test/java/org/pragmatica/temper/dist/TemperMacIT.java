package org.pragmatica.temper.dist;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// `ops/mac/temper-mac` (M.argv, M.walk, M.label, M.ucomm, T3.1.tz) against a JDK HTTP
/// stub controller reached by the real `curl` (`post`), a `TEMPER_MAC_PS` fixture (the parsing) and a copy of `/bin/bash`
/// named `claude` as the ancestor (`session`, real `ps`). Runs on Linux (GNU `date`, procps `ps`) here; the script also
/// takes the BSD path on the Mac.
///
/// Probes: the `-u` of the start conversion dropped, under `TZ=Pacific/Chatham` → [#session_underChatham_startIsWithinASecondOfTheJvmsReading]
/// red; the token on a `-H` header → [#post_tokenIsNeverOnAnArgv] red; match `ucomm` or a substring →
/// [#post_onlyAClaudeBasename_isASession] red; refuse when there is no ancestor → [#session_noClaudeAncestor_exits3_withEmptyStdout] red.
class TemperMacIT {
    private static final String TOKEN = "mac-token-s3cr3t-123";
    /// The measured fixture: `Tue Nov  3 01:06:38 2026` UTC is 1793667998.
    private static final String T38 = "Tue Nov  3 01:06:38 2026";
    private static final String FIXTURE_PS = """
        f=$PS_FIXTURE
        echo "TZ=${TZ-} LC_ALL=${LC_ALL-} $*" >> "$f.calls"
        [ -z "${PS_FAIL:-}" ] || exit 1
        pid=
        for a in "$@"; do pid=$a; done
        case "$*" in
          *-A*) awk -F'|' '{ printf "%5s %s %s\\n", $1, $3, $4 }' "$f" ;;
          *ppid=,comm=*)
            if awk -F'|' -v p="$pid" '$1==p { found=1 } END { exit !found }' "$f"; then
              awk -F'|' -v p="$pid" '$1==p { print $2, $4 }' "$f"
            else
              echo "${PS_UNKNOWN:-1 bash}"
            fi ;;
          *args=*) awk -F'|' -v p="$pid" '$1==p { print $5 }' "$f" ;;
          *lstart=*) awk -F'|' -v p="$pid" '$1==p { print $3 }' "$f" ;;
        esac
        """;

    record Seen(String method, String path, String authorization, String contentType, String body) {}

    @TempDir
    Path dir;

    private Path home;
    private Path bin;
    private Path fixture;
    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "{\"receivedAt\":\"2026-11-04T12:00:00Z\",\"sessions\":0}";

    @BeforeEach
    void stubs() throws IOException {
        home = Files.createDirectories(dir.resolve("home"));
        bin = Files.createDirectories(dir.resolve("bin"));
        fixture = dir.resolve("procs");
        Files.writeString(Files.createDirectories(home.resolve(".config/temper")).resolve("mac-token"), TOKEN + "\n");
        MacScripts.executable(bin.resolve("curl"), "echo \"$*\" >> \"$STUBS/curl.args\"\nexec /usr/bin/curl \"$@\"\n");
        MacScripts.executable(bin.resolve("ps-fixture"), FIXTURE_PS);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/fleet/mac", this::answer);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(HttpExchange exchange) throws IOException {
        var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(exchange.getRequestMethod(),
                          exchange.getRequestURI().getPath(),
                          exchange.getRequestHeaders().getFirst("Authorization"),
                          exchange.getRequestHeaders().getFirst("Content-Type"),
                          body));
        var bytes = reply.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void procs(String... lines) throws IOException {
        Files.writeString(fixture, String.join("\n", lines) + "\n");
    }

    private Map<String, String> env(String... more) {
        var env = new java.util.LinkedHashMap<>(MacScripts.env(Map.of("HOME", home.toString(), "PATH", bin + ":/usr/bin:/bin", "STUBS", dir.toString(),
                                                                       "TEMPER_MAC_URL", "http://127.0.0.1:" + server.getAddress().getPort())));
        for (var i = 0; i < more.length; i += 2) {
            env.put(more[i], more[i + 1]);
        }
        return env;
    }

    private Map<String, String> fixtureEnv(String... more) {
        var env = env(more);
        env.put("TEMPER_MAC_PS", bin.resolve("ps-fixture").toString());
        env.put("PS_FIXTURE", fixture.toString());
        return env;
    }

    private MacScripts.Result mac(Map<String, String> env, String... args) throws Exception {
        var command = new java.util.ArrayList<String>(List.of("bash", MacScripts.MAC.toString()));
        command.addAll(List.of(args));
        return MacScripts.run(command, env, dir);
    }

    private String log() throws IOException {
        var file = home.resolve("Library/Logs/temper-mac.log");
        return Files.exists(file) ? Files.readString(file) : "";
    }

    private static String processesOf(String body) {
        var matcher = java.util.regex.Pattern.compile("^\\{\"v\":1,\"postedAt\":\"\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ\",\"processes\":\\[(.*)]}$").matcher(body);
        assertThat(matcher.matches()).as(body).isTrue();
        return matcher.group(1);
    }

    /// pid|ppid|lstart|comm|args
    private static String proc(int pid, int ppid, String lstart, String comm, String args) {
        return pid + "|" + ppid + "|" + lstart + "|" + comm + "|" + args;
    }

    private void mixedTable() throws IOException {
        procs(proc(300, 1, T38, "/Applications/Claude/claude", "claude --name CTO"),
              proc(100, 1, "Tue Nov  3 01:06:40 2026", "/x/Application Support/Claude/claude-code/2.1.316/claude", "claude --name a b"),
              proc(101, 1, "Tue Nov  3 01:06:41 2026", "/x/2.1.316", "2.1.316 --name zzz"),
              proc(102, 1, "Tue Nov  3 01:06:42 2026", "/bin/Claude", "Claude"),
              proc(103, 1, "Tue Nov  3 01:06:43 2026", "/opt/claude", "claude --name=Eq --dangerously-skip-permissions"),
              proc(104, 1, "Tue Nov  3 01:06:44 2026", "/usr/bin/claude-helper", "claude-helper"),
              proc(105, 1, "Tue Nov  3 01:06:45 2026", "claude", "claude"),
              proc(106, 1, "Tue Nov  3 01:06:46 2026", "/bin/bash", "bash -c claude"));
    }

    // ---- post --------------------------------------------------------------------------------------------------
    /// M.ucomm, M.label: only a comm whose basename is exactly `claude` is a session (not `ucomm` `2.1.316`, not
    /// `Claude`, not `claude-helper`); `--name maintainer` and `--name=Eq` give their value, `--name a b` gives `a` and no name `pid:<pid>`;
    /// sorted by pid, with the UTC start of each lstart, under three time zones.
    @Test
    void post_onlyAClaudeBasename_isASession() throws Exception {
        mixedTable();

        for (var tz : List.of("UTC", "Pacific/Chatham", "America/Los_Angeles")) {
            seen.clear();
            var run = mac(fixtureEnv("TZ", tz), "post");

            assertThat(run.rc()).as(tz + " " + run.err()).isZero();
            assertThat(seen).as(tz).singleElement().satisfies(request -> {
                assertThat(request.method()).isEqualTo("POST");
                assertThat(request.path()).isEqualTo("/api/v1/fleet/mac");
                assertThat(request.contentType()).isEqualTo("application/json");
                assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
                assertThat(processesOf(request.body()))
                    .isEqualTo("{\"pid\":100,\"start\":1793668000,\"label\":\"a\"},{\"pid\":103,\"start\":1793668003,\"label\":\"Eq\"},"
                               + "{\"pid\":105,\"start\":1793668005,\"label\":\"pid:105\"},{\"pid\":300,\"start\":1793667998,\"label\":\"CTO\"}");
            });
        }
    }

    /// H5: every `ps` call runs with `TZ=UTC` and `LC_ALL=C`, so `lstart` is a UTC reading in a fixed format.
    @Test
    void post_readsPsInUtcAndTheCLocale() throws Exception {
        mixedTable();

        mac(fixtureEnv("TZ", "Pacific/Chatham"), "post");

        assertThat(Files.readAllLines(Path.of(fixture + ".calls"))).isNotEmpty().allSatisfy(call -> assertThat(call).startsWith("TZ=UTC LC_ALL=C "));
    }

    /// M.argv: the token reaches curl on its stdin config, never on an argv (`ps` shows argv to every user).
    @Test
    void post_tokenIsNeverOnAnArgv() throws Exception {
        mixedTable();

        var run = mac(fixtureEnv(), "post");

        assertThat(run.rc()).as(run.err()).isZero();
        var argv = Files.readString(dir.resolve("curl.args"));
        assertThat(argv).doesNotContain(TOKEN).doesNotContain("Authorization").contains("--config -").contains("--data-binary @");
        assertThat(seen).singleElement().satisfies(request -> assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    @Test
    void post_isTheDefaultVerb() throws Exception {
        procs(proc(300, 1, T38, "/x/claude", "claude --name CTO"));

        var run = mac(fixtureEnv());

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(processesOf(seen.getFirst().body())).isEqualTo("{\"pid\":300,\"start\":1793667998,\"label\":\"CTO\"}");
    }

    /// No session running is a valid post: an empty list.
    @Test
    void post_withNoSession_sendsAnEmptyList() throws Exception {
        procs(proc(106, 1, T38, "/bin/bash", "bash"));

        var run = mac(fixtureEnv(), "post");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(processesOf(seen.getFirst().body())).isEmpty();
    }

    @Test
    void post_moreThan512_sendsTheFirst512_andLogsIt() throws Exception {
        procs(IntStream.rangeClosed(1000, 1512).mapToObj(pid -> proc(pid, 1, T38, "/x/claude", "claude")).toArray(String[]::new));

        var run = mac(fixtureEnv(), "post");

        assertThat(run.rc()).as(run.err()).isZero();
        var body = processesOf(seen.getFirst().body());
        assertThat(body.split("\\},\\{")).hasSize(512);
        assertThat(body).startsWith("{\"pid\":1000,").endsWith("{\"pid\":1511,\"start\":1793667998,\"label\":\"pid:1511\"}");
        assertThat(log()).contains("too many claude processes (513)");
    }

    /// The post's processes are sorted by pid and the cut keeps the lowest pids, whatever order `ps` lists them in.
    /// Mutation: drop `sort -n` → red.
    @Test
    void post_outOfPidOrder_isSortedByPid_andTheCutKeepsTheLowest() throws Exception {
        procs(IntStream.rangeClosed(1000, 1512).map(i -> 2512 - i).mapToObj(pid -> proc(pid, 1, T38, "/x/claude", "claude")).toArray(String[]::new));

        var run = mac(fixtureEnv(), "post");

        assertThat(run.rc()).as(run.err()).isZero();
        var body = processesOf(seen.getFirst().body());
        assertThat(body.split("\\},\\{")).hasSize(512);
        assertThat(body).startsWith("{\"pid\":1000,").endsWith("{\"pid\":1511,\"start\":1793667998,\"label\":\"pid:1511\"}");
    }

    /// A failure is logged once, with the code and the head of the reply; the next failing run adds nothing; the first
    /// success after it logs the recovery once; later successes add nothing. Exit 1 while failing, 0 when posted.
    @Test
    void post_logsOnlyChanges_failureOnce_thenRecoveryOnce() throws Exception {
        procs(proc(300, 1, T38, "/x/claude", "claude --name CTO"));
        status = 403;
        reply = "{\"error\":\"forbidden: not permitted for this token\"}";

        assertThat(mac(fixtureEnv(), "post").rc()).isEqualTo(1);
        assertThat(mac(fixtureEnv(), "post").rc()).isEqualTo(1);
        assertThat(log().lines().toList()).singleElement().asString()
                                          .matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ post failed: 403 \\{\"error\":\"forbidden: not permitted for this token\"}\\s*");

        status = 200;
        assertThat(mac(fixtureEnv(), "post").rc()).isZero();
        assertThat(mac(fixtureEnv(), "post").rc()).isZero();
        assertThat(log().lines().toList()).hasSize(2).last().asString().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ post recovered");
        assertThat(Files.readString(home.resolve("Library/Application Support/temper-mac/state"))).isEqualTo("failing=0\n");
    }

    @Test
    void post_whenTheControllerIsUnreachable_logsTheCurlExit_andExits1() throws Exception {
        procs(proc(300, 1, T38, "/x/claude", "claude --name CTO"));
        int closed;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }

        var run = mac(fixtureEnv("TEMPER_MAC_URL", "http://127.0.0.1:" + closed), "post");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(log()).contains(" post failed: curl exit 7");
    }

    /// `ps` itself failing must not post an empty list (every session would read DEAD).
    @Test
    void post_whenPsFails_postsNothing() throws Exception {
        procs(proc(300, 1, T38, "/x/claude", "claude --name CTO"));

        var run = mac(fixtureEnv("PS_FAIL", "1"), "post");

        assertThat(run.rc()).isEqualTo(1);
        assertThat(seen).isEmpty();
        assertThat(log()).contains("ps failed: nothing posted");
    }

    @Test
    void post_withoutUrlOrToken_exits2() throws Exception {
        procs(proc(300, 1, T38, "/x/claude", "claude"));
        var noUrl = fixtureEnv();
        noUrl.remove("TEMPER_MAC_URL");

        var withoutUrl = mac(noUrl, "post");
        Files.delete(home.resolve(".config/temper/mac-token"));
        var withoutToken = mac(fixtureEnv(), "post");

        assertThat(withoutUrl.rc()).isEqualTo(2);
        assertThat(withoutUrl.err()).contains("no controller URL");
        assertThat(withoutToken.rc()).isEqualTo(2);
        assertThat(withoutToken.err()).contains("no token file");
        assertThat(seen).isEmpty();
    }

    /// Real `ps`: a copy of bash named `claude` running under `--name` is posted with its pid, its label and a start
    /// within a second of the JVM's own reading of the process's start.
    @Test
    void post_withRealPs_listsTheClaudeStandIn() throws Exception {
        var claude = MacScripts.claude(dir.resolve("standin"));
        var standIn = MacScripts.background(List.of(claude.toString(), "-c", "sleep 60; true", "--name", "RealOne"), env(), dir);
        try {
            Thread.sleep(300);
            var started = standIn.info().startInstant().orElseThrow();

            var run = mac(env("TZ", "Pacific/Chatham"), "post");

            assertThat(run.rc()).as(run.err()).isZero();
            var entry = java.util.regex.Pattern.compile("\\{\"pid\":" + standIn.pid() + ",\"start\":(\\d+),\"label\":\"RealOne\"}").matcher(seen.getFirst().body());
            assertThat(entry.find()).as(seen.getFirst().body()).isTrue();
            assertThat(Long.parseLong(entry.group(1))).isCloseTo(started.getEpochSecond(), within(1L));
        } finally {
            standIn.descendants().forEach(ProcessHandle::destroyForcibly);
            standIn.destroyForcibly();
        }
    }

    @Test
    void unknownVerb_isUsage_exit2() throws Exception {
        var run = mac(env(), "frobnicate");

        assertThat(run.rc()).isEqualTo(2);
        assertThat(run.err()).contains("usage: temper-mac [post|session]");
    }

    // ---- session -----------------------------------------------------------------------------------------------
    /// T3.1.tz: run under `TZ=Pacific/Chatham` (a +13:45 zone: a local reading would be hours off), `session` prints
    /// the ancestor's pid, the start within a second of the JVM's own reading, and the `--name` label. The two script
    /// sides share one function, so only this independent clock catches a common error.
    @Test
    void session_underChatham_startIsWithinASecondOfTheJvmsReading() throws Exception {
        var claude = MacScripts.claude(dir.resolve("standin"));
        var walk = "bash " + MacScripts.MAC + " session; echo rc=$?";

        var run = MacScripts.run(List.of(claude.toString(), "-c", walk, "--name", "CTO"), env("TZ", "Pacific/Chatham"), dir);

        assertThat(run.out()).as(run.err()).matches("(?s)BB_SESSION_PID=\\d+ BB_SESSION_START=\\d+ BB_SESSION_LABEL=CTO\\nrc=0\\n");
        var line = run.out().lines().findFirst().orElseThrow();
        var start = Long.parseLong(line.replaceAll(".*BB_SESSION_START=(\\d+).*", "$1"));
        assertThat(start).isCloseTo(run.started().getEpochSecond(), within(1L));
    }

    @Test
    void session_walksFromTheCallerUp_toTheFirstClaude_andLabelsItByName() throws Exception {
        procs(proc(400, 300, "Tue Nov  3 01:07:00 2026", "/bin/zsh", "-zsh"),
              proc(300, 1, T38, "/x/Application Support/Claude/claude-code/2.1.316/claude", "claude --name CTO"));

        var run = mac(fixtureEnv("PS_UNKNOWN", "400 bash"), "session");

        assertThat(run.rc()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("BB_SESSION_PID=300 BB_SESSION_START=1793667998 BB_SESSION_LABEL=CTO\n");
    }

    /// M.label: the label is the single word after `--name` (a quoted `a b` is two words under `ps`, so `a`; a prompt
    /// argument after the name does not change it; no name is `pid:<pid>`); `BB_SESSION` wins when well-formed
    /// and is noted and ignored when not. Mutation: re-join the words after `--name` → red.
    @Test
    void session_label_isTheSingleWordAfterName_bbSessionWins() throws Exception {
        var unknown = new String[] {"PS_UNKNOWN", "300 bash"};
        procs(proc(300, 1, T38, "/x/claude", "claude --name CTO \"fix the bug\""));
        assertThat(mac(fixtureEnv(unknown), "session").out()).endsWith("BB_SESSION_LABEL=CTO\n");
        procs(proc(300, 1, T38, "/x/claude", "claude --name a b"));
        assertThat(mac(fixtureEnv(unknown), "session").out()).endsWith("BB_SESSION_LABEL=a\n");
        procs(proc(300, 1, T38, "/x/claude", "claude"));
        assertThat(mac(fixtureEnv(unknown), "session").out()).endsWith("BB_SESSION_LABEL=pid:300\n");
        procs(proc(300, 1, T38, "/x/claude", "claude --name a b"));
        assertThat(mac(fixtureEnv("PS_UNKNOWN", "300 bash", "BB_SESSION", "x"), "session").out()).endsWith("BB_SESSION_LABEL=x\n");
        var malformed = mac(fixtureEnv("PS_UNKNOWN", "300 bash", "BB_SESSION", "a b"), "session");
        assertThat(malformed.out()).endsWith("BB_SESSION_LABEL=a\n");
        assertThat(malformed.err()).contains("ignoring BB_SESSION");
    }

    /// M.walk: no `claude` ancestor is exit 3, an empty stdout and one stderr line (the caller then sends no session
    /// fields); the `ucomm` of a claude (`2.1.316`) is not a `claude`.
    @Test
    void session_noClaudeAncestor_exits3_withEmptyStdout() throws Exception {
        procs(proc(400, 300, "Tue Nov  3 01:07:00 2026", "/bin/zsh", "-zsh"),
              proc(300, 1, T38, "/x/2.1.316", "claude --name CTO"));

        var run = mac(fixtureEnv("PS_UNKNOWN", "400 bash"), "session");

        assertThat(run.rc()).isEqualTo(3);
        assertThat(run.out()).isEmpty();
        assertThat(run.err()).isEqualTo("temper-mac: no claude ancestor\n");
    }

    @Test
    void session_stopsAtPidZeroAndOne() throws Exception {
        procs(proc(400, 0, "Tue Nov  3 01:07:00 2026", "/bin/zsh", "-zsh"));

        assertThat(mac(fixtureEnv("PS_UNKNOWN", "400 bash"), "session").rc()).isEqualTo(3);
    }

    /// The walk is bounded: a parent cycle with no claude ends after 64 steps.
    @Test
    void session_aParentCycle_endsAfter64Steps() throws Exception {
        procs(proc(400, 401, "Tue Nov  3 01:07:00 2026", "/bin/zsh", "-zsh"),
              proc(401, 400, "Tue Nov  3 01:07:01 2026", "/bin/zsh", "-zsh"));

        var run = mac(fixtureEnv("PS_UNKNOWN", "400 bash"), "session");

        assertThat(run.rc()).isEqualTo(3);
        assertThat(Files.readAllLines(Path.of(fixture + ".calls")).stream().filter(call -> call.contains("ppid=,comm=")).count()).isEqualTo(64);
    }

    /// A claude found whose start cannot be read (it exited meanwhile) is no ancestor.
    @Test
    void session_aClaudeWithoutAStart_isNoAncestor() throws Exception {
        procs(proc(300, 1, "", "/x/claude", "claude --name CTO"));

        var run = mac(fixtureEnv("PS_UNKNOWN", "300 bash"), "session");

        assertThat(run.rc()).isEqualTo(3);
        assertThat(run.out()).isEmpty();
    }

    // ---- plist -------------------------------------------------------------------------------------------------
    @Test
    void plist_runsPostEvery60s_atLoad_withTheDeadmansShape() throws Exception {
        var plist = Files.readString(MacScripts.PLIST);

        assertThat(plist).contains("<string>com.pragmatica.temper-mac</string>")
                         .contains("<string>/bin/bash</string>\n        <string>__HOME__/.local/bin/temper-mac</string>\n        <string>post</string>")
                         .contains("<key>StartInterval</key>\n    <integer>60</integer>")
                         .contains("<key>RunAtLoad</key>\n    <true/>")
                         .contains("<key>ProcessType</key>\n    <string>Background</string>")
                         .contains("<string>__HOME__/Library/Logs/temper-mac.launchd.log</string>");
        assertThat(plist.split("temper-mac.launchd.log")).hasSize(3);
        assertThat(Files.readString(MacScripts.MAC)).contains("launchctl bootstrap gui/$UID").contains("install -m 0755 ops/mac/temper-mac ~/.local/bin/temper-mac")
                                                    .contains("launchctl print gui/$UID/com.pragmatica.temper-mac | grep -E 'state|last exit'")
                                                    .contains("sed \"s|__HOME__|$HOME|g\" ops/mac/com.pragmatica.temper-mac.plist");
    }
}
