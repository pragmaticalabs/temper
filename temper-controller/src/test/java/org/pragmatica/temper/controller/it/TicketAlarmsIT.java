package org.pragmatica.temper.controller.it;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.t3.RetireWarn;
import org.pragmatica.temper.controller.t3.TicketAlarms;
import org.pragmatica.temper.controller.t3.TicketCollector;
import org.pragmatica.temper.controller.t3.TicketError;
import org.pragmatica.temper.controller.t3.TicketEvents;
import org.pragmatica.temper.controller.t3.TicketTransport;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbStore.RoundWrite;
import org.pragmatica.temper.store.api.FleetEventRow;
import org.pragmatica.temper.store.api.FleetPending;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.node.JsonNodeFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;


/// The collection alarms through the real round over Postgres: the mark
/// and the event share the round's transaction, so "once" survives a restart (a NEW collector over the same database)
/// and a failed event insert leaves no mark. Rows S1.3 (with the maintainer's restart cut), S1.6 (the failed round between),
/// S1.7, S1.10/S1.11 (the restart), S1.13, S1.14 (the round succeeds).
@Tag("pg")
class TicketAlarmsIT {
    /// The host clock's `at` of the first listing (epoch seconds); the controller clock starts 20 s ahead of it.
    private static final long AT = 1_793_900_000L;
    private static final long NANOS = 1_000_000_000L;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.ofEpochSecond(AT + 20));
    /// The listing the transport answers: the ticket lines; `at` is the controller time minus 20 s.
    private final AtomicReference<List<String>> lines = new AtomicReference<>(List.of());
    private final AtomicReference<Option<String>> failure = new AtomicReference<>(none());
    private Store store;
    private Fleet fleet;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        store = Pg.freshStore();
        fleet = Fleet.fleet(ControllerConfig.Fleet.fleet(none(), dir.resolve("state")),
                            List.of(),
                            store.fleet(),
                            ProcessRunner.processRunner(),
                            none(),
                            now::get);
        fleet.settle().await(Pg.WAIT).unwrap();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private TicketTransport transport() {
        return _ -> failure.get()
                           .map(message -> TicketError.SshExit.sshExit(255, message).<String> promise())
                           .or(() -> Promise.success(stdout()));
    }

    private String stdout() {
        var at = now.get().getEpochSecond() - 20;
        var body = new StringBuilder();

        lines.get().forEach(line -> body.append(line)
                                        .append('\n'));
        body.append("{\"v\":1,\"end\":true,\"count\":")
            .append(lines.get().size())
            .append(",\"queue\":\"present\",\"at\":")
            .append(at)
            .append("}\n");

        return body.toString();
    }

    private TicketCollector collector(TicketAlarms.Round round) {
        return TicketCollector.ticketCollector(store.bb(),
                                               store.fleet(),
                                               fleet.writer(),
                                               List.of(TicketCollector.Target.target("h1", transport())),
                                               TemperJson.mapper(),
                                               now::get,
                                               round,
                                               TicketCollector.READ_BOUND);
    }

    private TicketCollector collector() {
        return collector(TicketAlarms.round(TicketEvents.ticketEvents(), TicketAlarms.Limits.DEFAULT));
    }

    /// One round, 30 s after the previous one.
    private void round(TicketCollector collector) {
        now.set(now.get().plusSeconds(30));
        collector.round().await(Pg.WAIT).unwrap();
    }

    private static String line(String id, String dir, String waiter, String... kv) {
        var node = JsonNodeFactory.instance.objectNode();
        var fields = new LinkedHashMap<String, String>();

        for (var i = 0; i < kv.length; i += 2) {
            fields.put(kv[i], kv[i + 1]);
        }

        node.put("v", 1);
        node.put("ticket", id);
        node.put("dir", dir);
        if (waiter == null) {
            node.putNull("waiter");
        } else {
            node.put("waiter", waiter);
        }

        var fieldNode = node.putObject("fields");

        fields.forEach(fieldNode::put);
        node.putArray("dropped");

        return node.toString();
    }

    /// A waiting ticket created at host time `createdAt` (epoch seconds).
    private static String waiting(String id, long createdAt, String... more) {
        var kv = new ArrayList<>(List.of("created", Long.toString(createdAt * NANOS), "owner", "s1a"));

        kv.addAll(List.of(more));

        return line(id, "waiting", "alive", kv.toArray(String[]::new));
    }

    private List<FleetEventRow> events(String kind) {
        return ((FleetPending.Events) store.fleet()
                                           .pending(0, 1000)
                                           .await(Pg.WAIT)
                                           .unwrap()).rows()
                                                     .stream()
                                                     .filter(row -> row.kind()
                                                                       .equals(kind))
                                                     .toList();
    }

    private static int field(FleetEventRow row, String key) {
        return TemperJson.mapper()
                         .readTree(row.payload())
                         .unwrap()
                         .path(key)
                         .intValue();
    }

    private List<BbAlarm> marks() {
        return store.bb()
                    .alarms()
                    .await(Pg.WAIT)
                    .unwrap();
    }

    // ---- S1.3 -------------------------------------------------------------------------------------------------
    /// S1.3 with the maintainer's restart cut: the same over-limit ticket across three rounds, then a NEW collector over the
    /// same database whose first round falls right after the round that committed the mark, then two more rounds:
    /// exactly one `queue-wait` event. Mutations: an in-memory fired set (marks not read from the database) → the new
    /// collector fires again, red; no fired-row check → every round fires, red.
    @Test
    void s1_3_oneEvent_acrossRoundsAndARestartRightAfterTheMarkCommitted() {
        var created = AT - 3600;

        lines.set(List.of(waiting("w1", created)));
        var first = collector();

        round(first);
        assertThat(events("queue-wait")).hasSize(1);
        assertThat(marks()).extracting(BbAlarm::subject, BbAlarm::threshold).containsExactly(tuple("w1", "60"));
        round(first);
        round(first);
        assertThat(events("queue-wait")).hasSize(1);
        first.stop().await(Pg.WAIT).unwrap();
        var restarted = collector();

        round(restarted);
        assertThat(events("queue-wait")).as("restart: the persisted mark holds").hasSize(1);
        round(restarted);
        round(restarted);
        assertThat(events("queue-wait")).hasSize(1);
        var cut = collector();

        lines.set(List.of(waiting("w1", created),
                          waiting("w2",
                                  now.get().getEpochSecond() + 30 - 20 - 3600)));
        round(cut);
        assertThat(events("queue-wait")).as("w2 fires in this round, its mark commits with it").hasSize(2);
        round(collector());
        round(collector());
        assertThat(events("queue-wait")).as("a restart right after the commit adds nothing").hasSize(2);
    }

    // ---- S1.7 -------------------------------------------------------------------------------------------------
    /// S1.7: a fired ticket that leaves running/waiting has its marks deleted in the same round (the row count is
    /// asserted). Mutation: no deletion → the marks stay, red.
    @Test
    void s1_7_aTicketThatLeaves_hasItsMarksDeletedInTheSameRound() {
        var collector = collector();

        lines.set(List.of(waiting("w1", AT - 200 * 60), waiting("w2", AT - 3600)));
        round(collector);
        assertThat(marks()).hasSize(3);
        lines.set(List.of(line("w1", "done", null, "state", "finished", "rc", "0"),
                          line("w2", "running", "alive", "started", Long.toString(AT))));
        round(collector);
        assertThat(marks()).extracting(BbAlarm::subject).containsExactly("w2");
        lines.set(List.of());
        round(collector);
        assertThat(marks()).isEmpty();
    }

    // ---- S1.6 -------------------------------------------------------------------------------------------------
    /// S1.6 (the failed round between): gone, a failed collection, gone → fires; a failed collection is not a listing,
    /// so the baseline stays the last good one.
    @Test
    void s1_6_waiterGone_acrossAFailedRound_fires() {
        var collector = collector();

        lines.set(List.of(line("r1", "running", "gone", "started", Long.toString(AT))));
        round(collector);
        round(collector);
        assertThat(events("waiter-gone")).hasSize(1);
        var other = collector();

        lines.set(List.of(line("w9", "waiting", "gone", "created", Long.toString(AT * NANOS))));
        round(other);
        failure.set(some("Connection refused"));
        round(other);
        assertThat(store.bb().collects().await(Pg.WAIT).unwrap().getFirst().failingSince().isPresent()).isTrue();
        failure.set(none());
        round(other);
        assertThat(events("waiter-gone")).extracting(FleetEventRow::subject).containsExactly("h1/r1", "h1/w9");
    }

    // ---- S1.13 ------------------------------------------------------------------------------------------------
    /// S1.13: the event insert fails inside the round → no `bb_alarm` row, no event, no snapshot; the next round emits the
    /// event once. Mutation: the mark written in its own transaction first → the mark survives the failed insert and
    /// the next round is silent, red.
    @Test
    void s1_13_aFailedEventInsert_leavesNoMark_theNextRoundEmitsOnce() {
        var calls = new AtomicInteger();
        var real = TicketAlarms.round(TicketEvents.ticketEvents(), TicketAlarms.Limits.DEFAULT);
        TicketAlarms.Round brokenOnce = (host, at, listing, baseline, rows) -> {
            var write = real.write(host, at, listing, baseline, rows);

            return calls.getAndIncrement() == 0
                   ? RoundWrite.roundWrite(write.events()
                                                .stream()
                                                .map(event -> NewFleetEvent.newFleetEvent(event.observedAt(),
                                                                                          event.source(),
                                                                                          event.kind(),
                                                                                          event.subject(),
                                                                                          "not json"))
                                                .toList(),
                                           write.fire(),
                                           write.clear())
                   : write;
        };
        var collector = collector(brokenOnce);

        lines.set(List.of(waiting("w1", AT - 3600)));
        round(collector);
        assertThat(marks()).as("the mark rolled back with the event").isEmpty();
        assertThat(events("queue-wait")).isEmpty();
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap().isPresent()).isFalse();
        round(collector);
        round(collector);
        assertThat(events("queue-wait")).hasSize(1);
        assertThat(marks()).hasSize(1);
    }

    // ---- S1.14 ------------------------------------------------------------------------------------------------
    /// S1.14: one ticket with `created=abc` and one over the limit: the second fires and the round succeeds (the snapshot
    /// and the trailer `at` and skew are stored). Mutation: the whole round fails → no snapshot, no event, red.
    @Test
    void s1_14_aBadTicket_doesNotFailTheRound_andAtAndSkewAreStored() {
        lines.set(List.of(line("w0", "waiting", "alive", "created", "abc"), waiting("w1", AT - 3600)));
        round(collector());
        var snapshot = store.bb().snapshot("h1").await(Pg.WAIT).unwrap().unwrap();

        assertThat(events("queue-wait")).extracting(FleetEventRow::subject).containsExactly("h1/w1");
        assertThat(snapshot.hostAt()).isEqualTo(some(Instant.ofEpochSecond(now.get().getEpochSecond() - 20)));
        assertThat(snapshot.skewS()).as("host 20 s behind the controller's read").isEqualTo(some(-20));
        assertThat(events("clock-skew")).isEmpty();
    }

    /// A host 45 s ahead of the controller's read fires `clock-skew` once over two rounds, stored on the snapshot; the
    /// payload is signed.
    @Test
    void clockSkew_isFiredOnce_andStored() {
        TicketTransport ahead = _ -> Promise.success("{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"present\",\"at\":" + (now.get()
                                                                                                                             .getEpochSecond() + 45)
                                                    + "}\n");
        var skewed = TicketCollector.ticketCollector(store.bb(),
                                                     store.fleet(),
                                                     fleet.writer(),
                                                     List.of(TicketCollector.Target.target("h1", ahead)),
                                                     TemperJson.mapper(),
                                                     now::get,
                                                     TicketAlarms.round(TicketEvents.ticketEvents(),
                                                                        TicketAlarms.Limits.DEFAULT),
                                                     TicketCollector.READ_BOUND);

        round(skewed);
        round(skewed);
        assertThat(events("clock-skew")).hasSize(1);
        assertThat(field(events("clock-skew").getFirst(), "skewS")).isEqualTo(45);
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap().unwrap().skewS()).isEqualTo(some(45));
    }

    // ---- S1.10, S1.11 over a restart --------------------------------------------------------------------------
    /// S1.10 / S1.11 across a restart: a first tick at 12 h says `days 1` once; a NEW [RetireWarn] over the same
    /// database ticking on says nothing more, and never `days 3`. Mutation: no persisted mark → the new one fires again,
    /// red.
    @Test
    void s1_10_s1_11_retireMarks_surviveARestart() {
        var retireAt = Instant.parse("2026-11-22T00:00:00Z");
        var hosts = List.of(RetireWarn.Retire.retire("cloudbb-3", some(retireAt)));

        now.set(retireAt.minus(Duration.ofHours(12)));
        RetireWarn.retireWarn(store.bb(), fleet.writer(), hosts, now::get).tick().await(Pg.WAIT).unwrap();
        assertThat(events("host-retiring")).hasSize(1);
        assertThat(field(events("host-retiring").getFirst(), "days")).isEqualTo(1);
        for (var i = 1; i <= 5; i++) {
            now.set(now.get().plusSeconds(60));
            RetireWarn.retireWarn(store.bb(), fleet.writer(), hosts, now::get).tick().await(Pg.WAIT).unwrap();
        }

        assertThat(events("host-retiring")).hasSize(1);
        assertThat(marks()).extracting(BbAlarm::threshold).containsExactlyInAnyOrder("3", "1");
        assertThat(marks()).extracting(BbAlarm::subject).containsOnly(retireAt.toString());
    }
}
