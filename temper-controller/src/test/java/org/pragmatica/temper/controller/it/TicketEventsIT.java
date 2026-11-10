package org.pragmatica.temper.controller.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.t3.LocalTicketTransport;
import org.pragmatica.temper.controller.t3.TicketCollector;
import org.pragmatica.temper.controller.t3.TicketEvents;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.pragmatica.lang.Option.none;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;


/// The ticket diff through the collection round, over Postgres and the REAL `ops/bb/bb-tickets`: the baseline is the stored snapshot, so a restart over an unchanged queue is silent, and a ticket's
/// life across rounds is exactly one `ticket-queued`, `ticket-started`, `ticket-finished`.
@Tag("pg")
class TicketEventsIT {
    private static final long EPOCH_NOW = 1_793_700_000L;
    private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
    private Store store;
    private Fleet fleet;
    private Path queue;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws IOException {
        assumeThat(System.getProperty("os.name")).as("bb-tickets needs find -printf and pgrep -a").startsWith("Linux");
        store = Pg.freshStore();
        fleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), dir.resolve("state")),
                            List.of(),
                            store.fleet(),
                            ProcessRunner.processRunner(),
                            none(),
                            now::get);
        fleet.settle().await(Pg.WAIT).unwrap();
        queue = dir.resolve("queue");
        for (var sub : List.of("running", "waiting", "done")) {
            Files.createDirectories(queue.resolve(sub));
        }
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private TicketCollector collector() {
        var transport = LocalTicketTransport.localTicketTransport(LocalTicketTransport.BB_TICKETS.toAbsolutePath().normalize(),
                                                                  _ -> queue,
                                                                  Map.of("BB_TICKETS_NOW", Long.toString(EPOCH_NOW)));

        return TicketCollector.ticketCollector(store.bb(),
                                               store.fleet(),
                                               fleet.writer(),
                                               List.of(TicketCollector.Target.target("h1", transport)),
                                               TemperJson.mapper(),
                                               now::get,
                                               TicketEvents.ticketEvents());
    }

    private void round(TicketCollector collector) {
        now.set(now.get().plusSeconds(30));
        collector.round().await(Pg.WAIT).unwrap();
    }

    private void put(String sub, String id, String content) throws IOException {
        Files.writeString(queue.resolve(sub).resolve(id),
                          content);
    }

    private void move(String from, String to, String id, String append) throws IOException {
        var source = queue.resolve(from).resolve(id);

        Files.writeString(source, Files.readString(source) + append);
        Files.move(source,
                   queue.resolve(to).resolve(id));
    }

    private List<FleetEventRow> ticketEvents() {
        return ((FleetPending.Events) store.fleet()
                                           .pending(0, 1000)
                                           .await(Pg.WAIT)
                                           .unwrap()).rows()
                                                     .stream()
                                                     .filter(row -> row.kind()
                                                                       .startsWith("ticket-"))
                                                     .toList();
    }

    /// T3.10: no baseline at all gives zero events however many tickets there are; a restart (a NEW collector over the
    /// same Postgres) over the unchanged queue gives zero. Mutations: a first collection diffs against an empty
    /// baseline; the baseline kept in the collector's memory.
    @Test
    void t310_noBaseline_zeroEvents_andARestartOverAnUnchangedQueue_zeroEvents() throws Exception {
        put("running", "r1", "id=r1\ncreated=100\nowner=o\nstarted=150\n");
        put("waiting", "w1", "id=w1\ncreated=200\nowner=o\n");
        put("done",
            "d1",
            "id=d1\ncreated=" + (EPOCH_NOW - 100) + "000000000\nended=" + (EPOCH_NOW - 10) + "\nstate=finished\nrc=0\n");
        var first = collector();

        round(first);
        assertThat(ticketEvents()).as("no baseline: nothing").isEmpty();
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap().isPresent()).isTrue();
        round(first);
        assertThat(ticketEvents()).as("unchanged: nothing").isEmpty();
        round(collector());
        assertThat(ticketEvents()).as("restart over the persisted baseline: nothing").isEmpty();
    }

    /// T3.10b: waiting, running, done over three rounds (after the baseline round) is exactly one queued, one started,
    /// one finished, the last with `state` and `rc` from the done file; a further unchanged round adds none. Mutation:
    /// emit on every round (no diff).
    @Test
    void t310b_aTicketsLife_isOneQueued_oneStarted_oneFinished() throws Exception {
        var collector = collector();

        round(collector);
        put("waiting", "x1", "id=x1\ncreated=" + (EPOCH_NOW - 50) + "000000000\nowner=o\nco=/w/co\n");
        round(collector);
        move("waiting", "running", "x1", "started=" + (EPOCH_NOW - 40) + "\n");
        round(collector);
        move("running", "done", "x1", "state=finished\nrc=7\nended=" + (EPOCH_NOW - 5) + "\n");
        round(collector);
        round(collector);
        var events = ticketEvents();

        assertThat(events).extracting(FleetEventRow::kind)
                  .containsExactly("ticket-queued", "ticket-started", "ticket-finished");
        assertThat(events).extracting(FleetEventRow::subject).containsOnly("h1/x1");
        var finished = TemperJson.mapper().readTree(events.getLast().payload()).unwrap();

        assertThat(finished.path("state").stringValue()).isEqualTo("finished");
        assertThat(finished.path("rc").stringValue()).isEqualTo("7");
    }
}
