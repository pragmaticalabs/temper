package org.pragmatica.temper.cli;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.temper.protocol.api.DigestText;
import org.pragmatica.temper.protocol.api.DigestView;
import org.pragmatica.temper.protocol.api.MaintenanceView;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;

/// `temper digest`: the cursor file is read, the digest printed, and only then the new cursor written (0600); a failed
/// fetch, a rejected cursor or a print that did not reach stdout leaves the file as it was; `--since` neither reads nor
/// writes it.
class DigestCommandTest {
    private static final JsonMapper JSON = TemperJson.mapper();

    @TempDir
    Path home;

    private FakeController controller;
    private CliHarness cli;

    @BeforeEach
    void start() throws IOException {
        controller = FakeController.start(CliHarness.TOKEN);
        cli = CliHarness.against(home, controller);
    }

    @AfterEach
    void stop() throws IOException {
        controller.close();
    }

    @Test
    void digest_noCursorFile_startsAtTheHead_printsTheText_savesTheCursor0600() throws IOException {
        controller.onJson("GET /digest", view("42.7", false));

        var run = cli.run("digest");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(run.out()).containsExactlyElementsOf(DigestText.render(view("42.7", false)).lines().toList());
        assertThat(targets()).containsExactly("/api/v1/digest");
        assertThat(Files.readString(cursorFile())).isEqualTo("42.7\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(cursorFile()))).isEqualTo("rw-------");
        assertThat(Files.exists(cursorFile().resolveSibling("digest.cursor.tmp"))).isFalse();
    }

    @Test
    void digest_cursorVariable_readsAndAdvancesThatFile_leavesTheDefaultAlone() throws IOException {
        cursor("5.1");
        var own = home.resolve("agent-a.cursor");
        Files.writeString(own, "17.3\n");
        controller.onJson("GET /digest", view("42.7", false));

        var run = cli.env("TEMPER_DIGEST_CURSOR", own.toString()).run("digest");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(targets()).containsExactly("/api/v1/digest?since=17.3");
        assertThat(Files.readString(own)).isEqualTo("42.7\n");
        assertThat(Files.readString(cursorFile())).isEqualTo("5.1\n");
    }

    @Test
    void digest_withCursorFile_sendsIt_thenAdvancesIt() throws IOException {
        cursor("17.3");
        controller.onJson("GET /digest", view("42.7", false));

        var run = cli.run("digest");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(targets()).containsExactly("/api/v1/digest?since=17.3");
        assertThat(Files.readString(cursorFile())).isEqualTo("42.7\n");
    }

    @Test
    void digest_controllerFails_cursorFileNotAdvanced() throws IOException {
        cursor("17.3");
        controller.on("GET /digest", FakeController.Reply.json(500, "{\"error\":\"internal error\"}"));

        var run = cli.run("digest");

        assertThat(run.exit()).isEqualTo(69);
        assertThat(run.out()).isEmpty();
        assertThat(Files.readString(cursorFile())).isEqualTo("17.3\n");
    }

    @Test
    void digest_cursorRejected_cursorFileNotAdvanced() throws IOException {
        cursor("17.3");
        controller.on("GET /digest", FakeController.Reply.json(400, "{\"error\":\"Cursor 17.3 is ahead of the event log (head 3)\"}"));

        var run = cli.run("digest");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(run.err()).contains("ahead of the event log");
        assertThat(Files.readString(cursorFile())).isEqualTo("17.3\n");
    }

    @Test
    void digest_controllerUnreachable_cursorFileNotAdvanced() throws IOException {
        cursor("17.3");

        var run = cli.env("TEMPER_URL", "http://127.0.0.1:" + closedPort()).run("digest");

        assertThat(run.exit()).isEqualTo(69);
        assertThat(Files.readString(cursorFile())).isEqualTo("17.3\n");
    }

    /// stdout died before the digest was flushed: the reader may not have it, so it is not recorded as read.
    @Test
    void digest_stdoutClosed_cursorFileNotAdvanced() throws IOException {
        cursor("17.3");
        controller.onJson("GET /digest", view("42.7", false));

        var run = cli.run(CapturedOutput.killedAfter(1), "digest");

        assertThat(run.exit()).isEqualTo(5);
        assertThat(Files.readString(cursorFile())).isEqualTo("17.3\n");
    }

    @Test
    void digest_since_neitherReadsNorWritesTheFile() throws IOException {
        controller.onJson("GET /digest", view("42.7", false));

        var fresh = cli.run("digest", "--since", "5.-");

        assertThat(fresh.exit()).as(fresh.err()).isZero();
        assertThat(Files.exists(cursorFile())).as("no file created").isFalse();
        cursor("17.3");
        assertThat(cli.run("digest", "--since", "0.-").exit()).isZero();
        assertThat(targets()).containsExactly("/api/v1/digest?since=5.-", "/api/v1/digest?since=0.-");
        assertThat(Files.readString(cursorFile())).isEqualTo("17.3\n");
    }

    @Test
    void digest_json_printsTheView() throws IOException {
        controller.onJson("GET /digest", view("42.7", false));

        var run = cli.run("digest", "--json");

        assertThat(run.exit()).as(run.err()).isZero();
        assertThat(JSON.readString(String.join("\n", run.out()), DigestView.class).unwrap()).isEqualTo(view("42.7", false));
        assertThat(Files.readString(cursorFile())).isEqualTo("42.7\n");
    }

    /// A file that does not hold a cursor is never read as 0, which would replay the whole event log.
    @Test
    void digest_unreadableCursor_exit64_noRequest() throws IOException {
        cursor("not-a-number");

        var run = cli.run("digest");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(targets()).isEmpty();
        assertThat(Files.readString(cursorFile())).isEqualTo("not-a-number\n");
    }

    @Test
    void digest_negativeSince_isUsage() {
        var run = cli.run("digest", "--since", "-1");

        assertThat(run.exit()).isEqualTo(64);
        assertThat(targets()).isEmpty();
    }

    /// 409 `CURSOR_FOREIGN`: the file's cursor is from another event log. A clear line, exit 2, and the file stays.
    @Test
    void digest_foreignCursor_exit2_saysSo_cursorFileNotAdvanced() throws IOException {
        cursor("17.3");
        controller.on("GET /digest",
                      FakeController.Reply.json(409, "{\"error\":\"CURSOR_FOREIGN: cursor 17.3 is not from this event log; restart without since\"}"));

        var run = cli.run("digest");

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.out()).isEmpty();
        assertThat(run.err()).contains("is not from this controller's event log; delete the file to start at the head", "CURSOR_FOREIGN");
        assertThat(Files.readString(cursorFile())).isEqualTo("17.3\n");
    }

    @Test
    void digest_max_isSent_andValidatedAsTheControllerDoes() {
        controller.onJson("GET /digest", view("42.7", false));

        assertThat(cli.run("digest", "--max", "50", "--since", "17.3").exit()).isZero();
        assertThat(targets()).containsExactly("/api/v1/digest?max=50&since=17.3");
        for (var bad : List.of("0", "1001", "x")) {
            assertThat(cli.run("digest", "--max", bad).exit()).as(bad).isEqualTo(64);
        }
        assertThat(cli.run("digest", "--max", "1000", "--since", "1.-").exit()).isZero();
        assertThat(targets()).hasSize(2);
    }

    /// The controller stopped at `max`: the text ends by saying more events wait; the CLI reads once and does not loop.
    @Test
    void digest_more_endsWithTheMoreWaitingLine_readsOnce() {
        controller.onJson("GET /digest", view("42.7", true));

        var run = cli.run("digest");

        assertThat(run.exit()).isZero();
        assertThat(run.out().getLast()).isEqualTo("more events waiting: run temper digest again (HTTP: since=42.7)");
        assertThat(targets()).hasSize(1);
    }

    /// A bare `seq` is not a cursor: the controller would refuse it, so the CLI does first.
    @Test
    void digest_bareSeqSince_isUsage() {
        assertThat(cli.run("digest", "--since", "17").exit()).isEqualTo(64);
        assertThat(targets()).isEmpty();
    }

    private Path cursorFile() {
        return home.resolve(".config/temper/digest.cursor");
    }

    private void cursor(String text) throws IOException {
        Files.writeString(cursorFile(), text + "\n");
    }

    private List<String> targets() {
        return controller.received().stream().map(FakeController.Received::target).toList();
    }

    private static DigestView view(String cursor, boolean more) {
        return DigestView.digestView(cursor,
                                     more,
                                     none(),
                                     Instant.parse("2026-10-16T10:00:00Z"),
                                     List.of(),
                                     List.of(),
                                     List.of(),
                                     List.of(),
                                     0,
                                     List.of(DigestView.Host.host("bb", "UP", none(), 5, 0, 0, 0)),
                                     MaintenanceView.maintenanceView(none(), none()));
    }

    private static int closedPort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
