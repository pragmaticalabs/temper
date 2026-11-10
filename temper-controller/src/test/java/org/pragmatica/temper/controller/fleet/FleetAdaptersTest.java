package org.pragmatica.temper.controller.fleet;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.Completed;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.protocol.json.TemperJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The fleet adapters against real local endpoints: kiln's `/changes` client (query and decode), the self-check's
/// two probes, and the high-water file.
class FleetAdaptersTest {
    private static final TimeSpan WAIT = TimeSpan.timeSpan(20).seconds();
    private HttpServer server;
    private final AtomicReference<String> query = new AtomicReference<>("");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            var body = "{\"epoch\":\"E1\",\"floor\":1,\"head\":3,\"asOf\":\"2026-11-03T12:00:00Z\",\"sources\":{},\"changes\":[]}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void kilnClient_sendsEpochAfterAndMax_andDecodesTheReply() {
        var client = KilnClient.http(base(), TemperJson.mapper(), Duration.ofSeconds(5));

        var reply = client.changes(some("E1"), 7).await(WAIT).unwrap();

        assertThat(query.get()).isEqualTo("after=7&max=500&epoch=E1");
        assertThat(reply).isInstanceOfSatisfying(KilnReply.Changes.class, changes -> assertThat(changes.head()).isEqualTo(3));
        client.changes(none(), 0).await(WAIT).unwrap();
        assertThat(query.get()).isEqualTo("after=0&max=500");
    }

    @Test
    void kilnClient_non200_failsThePull() {
        status.set(503);

        assertThat(KilnClient.http(base(), TemperJson.mapper(), Duration.ofSeconds(5)).changes(none(), 0).await(WAIT).isFailure()).isTrue();
    }

    @Test
    void selfCheck_bannerAndAnsweringLoopback_isOk_andADeadPortIsLoopbackFailed() {
        var keyLine = runner(Completed.completed("ssh-keyscan", 0, "127.0.0.1 ssh-ed25519 AAAA\n".getBytes(), new byte[0], false));
        var noKey = runner(Completed.completed("ssh-keyscan", 1, new byte[0], new byte[0], false));

        var ok = SelfCheck.selfCheck(keyLine, server.getAddress().getPort()).run().await(WAIT).unwrap();
        server.stop(0);
        var dead = SelfCheck.selfCheck(noKey, server.getAddress().getPort()).run().await(WAIT).unwrap();

        assertThat(ok.ok()).isTrue();
        assertThat(ok.advisory().cores()).isEqualTo(Runtime.getRuntime().availableProcessors());
        assertThat(dead.ok()).isFalse();
        assertThat(dead.failed()).containsExactly(SelfCheck.BANNER, SelfCheck.LOOPBACK);
    }

    /// FIX-5: a seq handed out after the mark was read is above it unless a restore set the sequence back; one at the
    /// mark is a seq used twice, as is one below it.
    @Test
    void mark_behind_atOrBelowTheMark() {
        var mark = new HighWater.Mark("E", 5);

        assertThat(mark.behind(4)).isTrue();
        assertThat(mark.behind(5)).as("the mark's own seq, handed out again").isTrue();
        assertThat(mark.behind(6)).isFalse();
        assertThat(new HighWater.Mark("", -1).behind(0)).as("no mark yet").isFalse();
    }

    /// The mark only moves forward ([HighWater#advance] never writes a lower seq); [HighWater#force] sets it whatever it
    /// held; [HighWater#refresh] rewrites it, moved up only.
    @Test
    void highWater_advancesForwardOnly_forceSets_refreshNeverLowers() throws IOException {
        var hwm = HighWater.highWater(dir.resolve("state"));

        assertThat(hwm.read()).isEqualTo(none());
        assertThat(hwm.mark()).isEqualTo(new HighWater.Mark("", -1));
        assertThat(hwm.force(new HighWater.Mark("E", 2)).isSuccess()).isTrue();
        hwm.advance(5);
        hwm.advance(3);
        assertThat(Files.readString(dir.resolve("state/fleet-hwm"))).isEqualTo("E 5\n");
        assertThat(hwm.mark()).isEqualTo(new HighWater.Mark("E", 5));
        assertThat(hwm.refresh(4).isSuccess()).isTrue();
        assertThat(hwm.read()).isEqualTo(some(new HighWater.Mark("E", 5)));
        assertThat(hwm.refresh(7).isSuccess()).isTrue();
        assertThat(hwm.read()).isEqualTo(some(new HighWater.Mark("E", 7)));
        hwm.force(new HighWater.Mark("F", 0));
        assertThat(hwm.read()).isEqualTo(some(new HighWater.Mark("F", 0)));
        Files.writeString(dir.resolve("state/fleet-hwm"), "garbage");
        assertThat(hwm.read()).isEqualTo(none());
    }

    /// A file that cannot be written: the failure is answered (start rotates on it), and the mark is kept in memory
    /// all the same, so the running checks still compare against it.
    @Test
    void highWater_unwritable_answersTheFailure_andKeepsTheMarkInMemory() throws IOException {
        Files.writeString(dir.resolve("blocked"), "a file where the state directory should be");
        var hwm = HighWater.highWater(dir.resolve("blocked/state"));

        assertThat(hwm.force(new HighWater.Mark("E", 3)).isFailure()).isTrue();
        hwm.advance(4);
        assertThat(hwm.mark()).isEqualTo(new HighWater.Mark("E", 4));
        assertThat(hwm.refresh(4).isFailure()).isTrue();
        assertThat(hwm.read()).isEqualTo(none());
    }

    /// A runner whose every `run` completes with `completed`; nothing is spawned.
    private static ProcessRunner runner(Completed completed) {
        return new ProcessRunner() {
            @Override
            public Promise<Completed> run(Command command) {
                return Promise.success(completed);
            }

            @Override
            public Result<Spawned> spawn(Command command) {
                return Causes.cause("not spawned").result();
            }
        };
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
