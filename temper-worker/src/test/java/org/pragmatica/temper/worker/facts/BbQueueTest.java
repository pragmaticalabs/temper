package org.pragmatica.temper.worker.facts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ExecError;
import org.pragmatica.temper.worker.FakeRunner;
import org.pragmatica.temper.worker.config.WorkerConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.worker.Fixtures.ok;

/// bb-queue facts (m0-design §12.7). The fixtures `bbqueue/busy.txt` and `bbqueue/idle.txt` are verbatim output of
/// the real `~/tools/bb-queue 0` (bb-queue + bb-queue-lib.sh of 2026-09-30) run on Linux against a test queue
/// (`BB_TEST_QUEUE_ROOT`): two running tickets (one holding the port group, so the header names its holder and the
/// row carries `[port-group]`), one reactor outside the queue, three waiting tickets (one `[port-group]`, one
/// `[cap-blocked ...]`); then the same queue empty.
class BbQueueTest {
    private static final Command BB_QUEUE = ok(WorkerConfig.bbQueue(Map.of("PATH", "/usr/bin", "HOME", "/home/builder", "USER", "builder"),
                                                                 Path.of("/home/builder")));

    private static String fixture(String name) throws IOException {
        try (var in = BbQueueTest.class.getResourceAsStream("/bbqueue/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parse_countsRunningAndWaitingTickets_inRealOutput() throws IOException {
        var busy = fixture("busy.txt");

        assertThat(busy).contains("port group held by: 20260930-101500-v2174-1", "[port-group]", "  + 1 reactor(s)");
        assertThat(ok(BbQueue.listedTickets(busy))).isEqualTo(BbQueue.Tickets.tickets(2, 3));
    }

    /// `new-tools.txt` is verbatim stdout of ops/bb/bb-queue (the 2026-11-11 tools: load-gate line, port-group slots) run on
    /// cloudbb-d1 against a private queue with one running host-exclusive group ticket and one waiting group ticket. The
    /// worker once saw `None` counts on every host with those tools: the header label had changed and the gate line sat
    /// between the WAITING header and its column line.
    @Test
    void parse_countsRunningAndWaitingTickets_inTheOutputOfTheCurrentTools() throws IOException {
        var listing = fixture("new-tools.txt");

        assertThat(listing).contains("port-group slots: 3; port group held by: ", "[port-group host-exclusive]", "LOAD GATE");
        assertThat(ok(BbQueue.listedTickets(listing))).isEqualTo(BbQueue.Tickets.tickets(1, 1));
    }

    @Test
    void parse_countsZero_inRealIdleOutput() throws IOException {
        var idle = fixture("idle.txt");

        assertThat(idle).contains("port group held by: )");
        assertThat(ok(BbQueue.listedTickets(idle))).isEqualTo(BbQueue.Tickets.tickets(0, 0));
    }

    @Test
    void parse_fails_whenAHeaderOrColumnLineIsMissing() throws IOException {
        var busy = fixture("busy.txt");

        assertThat(BbQueue.listedTickets("").isFailure()).isTrue();
        assertThat(BbQueue.listedTickets("usage: bb-queue [N]\n").isFailure()).isTrue();
        assertThat(BbQueue.listedTickets(busy.replace("port group held by:", "group:")).isFailure()).as("held-by header required")
                                                                                         .isTrue();
        assertThat(BbQueue.listedTickets(busy.replace("POS  TICKET", "TICKET")).isFailure()).as("waiting columns required")
                                                                                    .isTrue();
        assertThat(BbQueue.listedTickets(busy.substring(0, busy.indexOf("WAITING ("))).isFailure()).as("waiting section required")
                                                                                            .isTrue();
    }

    @Test
    void parse_fails_onARowOfTheWrongShape() throws IOException {
        var busy = fixture("busy.txt");

        assertThat(BbQueue.listedTickets(busy.replace("1    20260930-110600-v2174-4", "x    20260930-110600-v2174-4")).isFailure()).isTrue();
        assertThat(BbQueue.listedTickets(busy.replace("20260930-111000-cto-2  ", " stray-indent ")).isFailure()).isTrue();
    }

    @Test
    void read_givesCounts_andNoNote_forARealListing() throws IOException {
        var runner = new FakeRunner().on(BB_QUEUE.argv(), 0, fixture("busy.txt"), "");

        var facts = ok(BbQueue.read(runner, BB_QUEUE)
                              .await());

        assertThat(facts.running()).isEqualTo(some(2));
        assertThat(facts.waiting()).isEqualTo(some(3));
        assertThat(facts.notes()).isEmpty();
        assertThat(runner.commands()
                         .getFirst()
                         .env()).isEqualTo(Map.of("PATH", "/usr/bin", "HOME", "/home/builder", "USER", "builder"));
        assertThat(BB_QUEUE.argv()).containsExactly("/home/builder/tools/bb-queue", "0");
    }

    @Test
    void read_givesNoneAndANote_forUnparsableOutput() {
        var runner = new FakeRunner().on(BB_QUEUE.argv(), 0, "garbage\n", "");

        var facts = ok(BbQueue.read(runner, BB_QUEUE)
                              .await());

        assertThat(facts.running()).isEqualTo(none());
        assertThat(facts.waiting()).isEqualTo(none());
        assertThat(facts.notes()).singleElement()
                                 .asString()
                                 .startsWith("bb-queue: ");
    }

    @Test
    void read_givesNoneAndANote_whenTheToolFailsOrIsMissing() throws IOException {
        var nonZero = new FakeRunner().on(BB_QUEUE.argv(), 2, fixture("busy.txt"), "boom");
        var missing = new FakeRunner().failing(BB_QUEUE.argv(), Causes.cause("No such file or directory"));
        var timedOut = new FakeRunner().failing(BB_QUEUE.argv(), ExecError.TimedOut.FACTORY.apply("bb-queue", BB_QUEUE.timeout()));

        for (var runner : List.of(nonZero, missing, timedOut)) {
            var facts = ok(BbQueue.read(runner, BB_QUEUE)
                                  .await());

            assertThat(facts.running()).isEqualTo(none());
            assertThat(facts.waiting()).isEqualTo(none());
            assertThat(facts.notes()).hasSize(1);
        }
    }
}
