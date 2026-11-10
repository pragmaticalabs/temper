package org.pragmatica.temper.controller.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.config.ControllerConfig;
import org.pragmatica.temper.controller.fleet.Fleet;
import org.pragmatica.temper.controller.t3.LocalTicketTransport;
import org.pragmatica.temper.controller.t3.TicketCollector;
import org.pragmatica.temper.controller.t3.TicketDiff;
import org.pragmatica.temper.controller.t3.TicketError;
import org.pragmatica.temper.controller.t3.TicketTransport;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.json.TemperJson;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbCollect;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.FleetSeqs;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.NewFleetEvent;
import org.pragmatica.temper.store.api.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;


/// The ticket collection round end to end over Postgres and the REAL `ops/bb/bb-tickets` on fixture queues
/// (T3.8b, T3.8c). The queue is read through [LocalTicketTransport], the same
/// port the ssh transports implement.
@Tag("pg")
class TicketCollectorIT {
    private static final long EPOCH_NOW = 1_793_700_000L;
    private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);
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

    private TicketCollector collector(TicketDiff diff, TicketCollector.Target... targets) {
        return collector(store.bb(), TicketCollector.READ_BOUND, diff, targets);
    }

    private TicketCollector collector(BbStore bb, TimeSpan bound, TicketDiff diff, TicketCollector.Target... targets) {
        return TicketCollector.ticketCollector(bb,
                                               store.fleet(),
                                               fleet.writer(),
                                               List.of(targets),
                                               TemperJson.mapper(),
                                               now::get,
                                               diff,
                                               bound);
    }

    private TicketTransport fixture(Path queue) {
        return LocalTicketTransport.localTicketTransport(LocalTicketTransport.BB_TICKETS.toAbsolutePath().normalize(),
                                                         _ -> queue,
                                                         Map.of("BB_TICKETS_NOW", Long.toString(EPOCH_NOW)));
    }

    private Path queue(String name) throws IOException {
        assumeThat(System.getProperty("os.name")).as("bb-tickets needs find -printf and pgrep -a").startsWith("Linux");
        var queue = dir.resolve(name);

        for (var sub : List.of("running", "waiting", "done")) {
            Files.createDirectories(queue.resolve(sub));
        }

        Files.writeString(queue.resolve("running").resolve("r1"),
                          "id=r1\ncreated=100\nowner=t3wpc1\nagent=a1\n");
        Files.writeString(queue.resolve("waiting").resolve("w1"),
                          "id=w1\ncreated=200\nowner=t3wpc1\n");
        Files.writeString(queue.resolve("done").resolve("d1"),
                          "id=d1\nended=" + (EPOCH_NOW - 10) + "\nstate=finished\nrc=0\n");

        return queue;
    }

    private TicketCollector.Target target(String host, TicketTransport transport) {
        return TicketCollector.Target.target(host, transport);
    }

    private static TicketTransport failing(String message) {
        return _ -> TicketError.SshExit.sshExit(255, message).promise();
    }

    // ---- T3.8 ------------------------------------------------------------------------------------------------
    /// Of two hosts one fails: h1 lists its tickets, h2 is recorded UNREACHABLE since its first failure with the ssh
    /// error. Mutation: skip a host whose read failed (record nothing) → red.
    @Test
    void t38_oneHostFails_theOtherListsItsTickets_theFailureKeepsItsFirstTimeAndError() throws Exception {
        var collector = collector(TicketDiff.NONE,
                                  target("h1", fixture(queue("q1"))),
                                  target("h2", failing("Connection refused")));

        collector.round().await(Pg.WAIT).unwrap();
        now.set(T0.plusSeconds(30));
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap().unwrap().tickets()).extracting(BbTicket::ticket)
                  .containsExactly("r1", "w1", "d1");
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap().unwrap().tickets().getFirst().fields()).containsEntry("agent",
                                                                                                                           "a1");
        assertThat(store.bb().snapshot("h2").await(Pg.WAIT).unwrap()).isEqualTo(none());
        assertThat(store.bb().collects().await(Pg.WAIT).unwrap()).containsExactly(BbCollect.bbCollect("h1",
                                                                                                      some(T0.plusSeconds(30)),
                                                                                                      some(T0.plusSeconds(30)),
                                                                                                      none(),
                                                                                                      none()),
                                                                                  BbCollect.bbCollect("h2",
                                                                                                      some(T0.plusSeconds(30)),
                                                                                                      none(),
                                                                                                      some(T0),
                                                                                                      some("ssh exit 255: Connection refused")));
    }

    @Test
    void settle_registersTheTicketsSourceOfEveryCollectedHost_neverSeen_period30() {
        var collector = collector(TicketDiff.NONE, target("h1", failing("x")), target("h2", failing("x")));

        collector.settle().await(Pg.WAIT).unwrap();
        for (var host : List.of("h1", "h2")) {
            var source = store.fleet().source("tickets:" + host).await(Pg.WAIT).unwrap().unwrap();

            assertThat(source.lastOkAt()).isEqualTo(none());
            assertThat(source.periodS()).isEqualTo(30);
        }

        assertThat(store.fleet().source("tickets:oldbox").await(Pg.WAIT).unwrap()).as("a NOT-COLLECTED host registers none")
                  .isEqualTo(none());
    }

    @Test
    void aSuccess_marksTheSource_aFailure_doesNot() throws Exception {
        var collector = collector(TicketDiff.NONE, target("h1", fixture(queue("q1"))), target("h2", failing("x")));

        collector.settle().await(Pg.WAIT).unwrap();
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(store.fleet().source("tickets:h1").await(Pg.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(some(T0));
        assertThat(store.fleet().source("tickets:h2").await(Pg.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(none());
    }

    // ---- T3.8b -----------------------------------------------------------------------------------------------
    /// A reply without its trailer is a failed collection, not a partial snapshot. Mutation: accept the reply without
    /// the trailer proof → red.
    @Test
    void t38b_aReplyWithoutItsTrailer_isTruncated_andTheSnapshotIsUnchanged() throws Exception {
        var real = fixture(queue("q1"));
        var cut = new AtomicReference<>(false);
        TicketTransport transport = host -> real.read(host)
                                                .map(output -> cut.get()
                                                               ? output.lines()
                                                                       .filter(line -> !line.contains("\"end\""))
                                                                       .collect(java.util.stream.Collectors.joining("\n",
                                                                                                                    "",
                                                                                                                    "\n"))
                                                               : output);
        var collector = collector(TicketDiff.NONE, target("h1", transport));

        collector.round().await(Pg.WAIT).unwrap();
        var before = store.bb().snapshot("h1").await(Pg.WAIT).unwrap();

        cut.set(true);
        now.set(T0.plusSeconds(30));
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(before.unwrap().tickets()).hasSize(3);
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap()).isEqualTo(before);
        assertThat(store.bb().collects().await(Pg.WAIT).unwrap().getFirst()).isEqualTo(BbCollect.bbCollect("h1",
                                                                                                           some(T0.plusSeconds(30)),
                                                                                                           some(T0),
                                                                                                           some(T0.plusSeconds(30)),
                                                                                                           some("truncated")));
        assertThat(store.fleet().source("tickets:h1").await(Pg.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(some(T0));
    }

    @Test
    void aLineThatDoesNotParse_isMalformedWithItsNumber_andNothingIsStored() {
        TicketTransport transport = _ -> Promise.success("{\"v\":1,\"ticket\":\"a\",\"dir\":\"running\",\"waiter\":\"alive\",\"fields\":{},\"dropped\":[]}\nnot json\n{\"v\":1,\"end\":true,\"count\":1,\"queue\":\"present\",\"at\":1}\n");

        collector(TicketDiff.NONE, target("h1", transport)).round().await(Pg.WAIT).unwrap();
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap()).isEqualTo(none());
        assertThat(store.bb().collects().await(Pg.WAIT).unwrap().getFirst().lastError()).isEqualTo(some("malformed line 2"));
    }

    // ---- T3.8c -----------------------------------------------------------------------------------------------
    /// `failing_since` is in Postgres, so a restarted controller (a new collector over the same database) keeps it.
    /// Mutation: keep `failing_since` in memory (the failure sets it to the failing attempt's time) → red.
    @Test
    void t38c_failingSince_survivesARestart() {
        collector(TicketDiff.NONE, target("h2", failing("Connection refused"))).round().await(Pg.WAIT).unwrap();
        now.set(T0.plusSeconds(90));
        var restarted = collector(TicketDiff.NONE, target("h2", failing("Connection timed out")));

        restarted.round().await(Pg.WAIT).unwrap();
        var row = store.bb().collects().await(Pg.WAIT).unwrap().getFirst();

        assertThat(row.failingSince()).isEqualTo(some(T0));
        assertThat(row.lastAttemptAt()).isEqualTo(some(T0.plusSeconds(90)));
        assertThat(row.lastError()).isEqualTo(some("ssh exit 255: Connection timed out"));
    }

    // ---- the round -------------------------------------------------------------------------------------------
    @Test
    void aHostIsNeverReadTwiceAtOnce_aRoundThatFindsItBusySkipsIt() {
        var reads = new AtomicInteger();
        var release = Promise.<String> promise();
        TicketTransport slow = _ -> {
            reads.incrementAndGet();

            return release;
        };
        var collector = collector(TicketDiff.NONE, target("h1", slow));
        var first = collector.round();

        collector.round().await(Pg.WAIT).unwrap();
        assertThat(reads).hasValue(1);
        release.succeed("{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":1}\n");
        first.await(Pg.WAIT).unwrap();
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(reads).hasValue(2);
    }

    @Test
    void anAbsentQueue_isASuccessWithNoTickets() {
        TicketTransport transport = _ -> Promise.success("{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":1}\n");

        collector(TicketDiff.NONE, target("h1", transport)).round().await(Pg.WAIT).unwrap();
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap().unwrap().tickets()).isEmpty();
        assertThat(store.bb().collects().await(Pg.WAIT).unwrap().getFirst().failingSince()).isEqualTo(none());
    }

    // ---- stop, the read bound, a store failure ---------------------------------------------------------------
    /// `stop` completes only when the read in flight has been recorded (no store write after the caller closes the
    /// store), and a round after `stop` reads nothing. Mutations: `stop` returns at once → red; the stopped flag is
    /// never set → red.
    @Test
    void stop_waitsForTheReadInFlight_andNoReadStartsAfterIt() {
        var reads = new AtomicInteger();
        var release = Promise.<String> promise();
        TicketTransport slow = _ -> {
            reads.incrementAndGet();

            return release;
        };
        var collector = collector(TicketDiff.NONE, target("h1", slow));
        var first = collector.round();
        var stopping = collector.stop();

        assertThat(stopping.isResolved()).as("a read is in flight").isFalse();
        release.succeed("{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":1}\n");
        stopping.await(Pg.WAIT).unwrap();
        first.await(Pg.WAIT).unwrap();
        assertThat(store.bb().snapshot("h1").await(Pg.WAIT).unwrap()).as("the read in flight was recorded")
                  .isNotEqualTo(none());
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(reads).as("no read after stop").hasValue(1);
    }

    /// A transport whose promise never completes is a `timeout` after the collector's bound, and the host is
    /// read again at the next round. Mutation: no bound on the read → the round never completes → red.
    @Test
    void aReadThatNeverCompletes_isATimeout_andTheHostIsReadAgainNextRound() {
        var transports = new AtomicInteger();
        var okay = "{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":1}\n";
        TicketTransport hung = _ -> transports.getAndIncrement() == 0
                                    ? Promise.<String> promise()
                                    : Promise.success(okay);
        var collector = collector(store.bb(),
                                  TimeSpan.timeSpan(300).millis(),
                                  TicketDiff.NONE,
                                  target("h1", hung));

        collector.round().await(Pg.WAIT).unwrap();
        var row = store.bb().collects().await(Pg.WAIT).unwrap().getFirst();

        assertThat(row.lastError()).isEqualTo(some("timeout"));
        assertThat(row.failingSince()).isEqualTo(some(T0));
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(store.bb().collects().await(Pg.WAIT).unwrap().getFirst().lastError()).isEqualTo(none());
    }

    private static BbStore writesNeverComplete(BbStore real) {
        return new BbStore() {
            @Override
            public Promise<Option<FleetSeqs>> collected(String host,
                                                        Instant at,
                                                        BbStore.Listing listing,
                                                        org.pragmatica.lang.Functions.Fn2<BbStore.RoundWrite, Option<BbSnapshot>, List<BbAlarm>> round) {
                return Promise.promise();
            }

            @Override
            public Promise<Unit> failed(String host, Instant at, String error) {
                return Promise.promise();
            }

            @Override
            public Promise<Option<BbSnapshot>> snapshot(String host) {
                return real.snapshot(host);
            }

            @Override
            public Promise<List<BbSnapshot>> snapshots() {
                return real.snapshots();
            }

            @Override
            public Promise<List<BbCollect>> collects() {
                return real.collects();
            }

            @Override
            public Promise<Option<FleetSeqs>> retiring(Instant at,
                                                       org.pragmatica.lang.Functions.Fn1<BbStore.RoundWrite, List<BbAlarm>> decide) {
                return real.retiring(at, decide);
            }

            @Override
            public Promise<List<BbAlarm>> alarms() {
                return real.alarms();
            }
        };
    }

    /// A store write that never completes cannot hold shutdown: the whole step (read plus record) is bounded, `stop`
    /// completes within it, and the abandoned host is logged. Mutation: bound only the read → `stop` hangs → red.
    @Test
    void stop_completesWhenTheStoreWriteNeverDoes_andLogsTheAbandonedHost() throws Exception {
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(TicketCollector.class);
        var okay = "{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":1}\n";
        var collector = collector(writesNeverComplete(store.bb()),
                                  TimeSpan.timeSpan(300).millis(),
                                  TicketDiff.NONE,
                                  target("h1", _ -> Promise.success(okay)));

        appender.start();
        logger.addAppender(appender);
        try {
            collector.round();
            collector.stop().await(TimeSpan.timeSpan(5).seconds()).unwrap();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                  .anyMatch(message -> message.contains("h1") && message.contains("step abandoned"));
    }

    /// A transport that throws instead of returning a promise is a failed read: the host is recorded failed, is read
    /// again next round, and `stop` completes. Mutation: call the transport unwrapped → `busy` stays set → red.
    @Test
    void aTransportThatThrows_isRecordedFailed_readAgainNextRound_andStopCompletes() {
        var calls = new AtomicInteger();
        var okay = "{\"v\":1,\"end\":true,\"count\":0,\"queue\":\"absent\",\"at\":1}\n";
        TicketTransport throwing = _ -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
            }

            return Promise.success(okay);
        };
        var collector = collector(TicketDiff.NONE, target("h1", throwing));

        collector.round().await(Pg.WAIT).unwrap();
        var row = store.bb().collects().await(Pg.WAIT).unwrap().getFirst();

        assertThat(row.lastError()).isEqualTo(some("exit 127: boom"));
        assertThat(row.failingSince()).isEqualTo(some(T0));
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(calls).as("read again").hasValue(2);
        assertThat(store.bb().collects().await(Pg.WAIT).unwrap().getFirst().lastError()).isEqualTo(none());
        collector.stop().await(Pg.WAIT).unwrap();
    }

    /// A database error while storing a good reply is logged, never written as the host's `last_error` (that holds only
    /// the causes). Mutation: record the store failure through `failed` → red.
    @Test
    void aStoreFailure_isLogged_notRecordedAsTheHostsError() throws Exception {
        var real = store.bb();
        var failedCalls = new AtomicInteger();
        var dbError = Causes.cause("connection reset by db");
        var broken = new BbStore() {
            @Override
            public Promise<Option<FleetSeqs>> collected(String host,
                                                        Instant at,
                                                        BbStore.Listing listing,
                                                        org.pragmatica.lang.Functions.Fn2<BbStore.RoundWrite, Option<BbSnapshot>, List<BbAlarm>> round) {
                return dbError.promise();
            }

            @Override
            public Promise<Unit> failed(String host, Instant at, String error) {
                failedCalls.incrementAndGet();

                return real.failed(host, at, error);
            }

            @Override
            public Promise<Option<BbSnapshot>> snapshot(String host) {
                return real.snapshot(host);
            }

            @Override
            public Promise<List<BbSnapshot>> snapshots() {
                return real.snapshots();
            }

            @Override
            public Promise<List<BbCollect>> collects() {
                return real.collects();
            }

            @Override
            public Promise<Option<FleetSeqs>> retiring(Instant at,
                                                       org.pragmatica.lang.Functions.Fn1<BbStore.RoundWrite, List<BbAlarm>> decide) {
                return real.retiring(at, decide);
            }

            @Override
            public Promise<List<BbAlarm>> alarms() {
                return real.alarms();
            }
        };
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(TicketCollector.class);

        appender.start();
        logger.addAppender(appender);
        try {
            collector(broken,
                      TicketCollector.READ_BOUND,
                      TicketDiff.NONE,
                      target("h1",
                             fixture(queue("q1")))).round()
                     .await(Pg.WAIT)
                     .unwrap();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(failedCalls).as("not a failure cause").hasValue(0);
        assertThat(real.collects().await(Pg.WAIT).unwrap()).isEmpty();
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                  .anyMatch(message -> message.contains("cannot store the tickets") && message.contains("connection reset by db"));
    }

    /// The seam C2 plugs into: `diff` sees the baseline the transaction read (none the first time, then the stored
    /// snapshot), and the events it makes are inserted with the snapshot, through the fleet writer.
    @Test
    void theDiff_seesTheStoredBaseline_andItsEventsAreAppended() throws Exception {
        var queue = queue("q1");
        var baselines = new CopyOnWriteArrayList<Option<BbSnapshot>>();
        TicketDiff diff = (host, at, tickets, baseline) -> {
            baselines.add(baseline);

            return baseline.isPresent()
                   ? List.of(NewFleetEvent.newFleetEvent(at,
                                                         "controller",
                                                         "ticket-queued",
                                                         host + "/" + tickets.getFirst().ticket(),
                                                         "{\"ticket\":\"r1\"}"))
                   : List.of();
        };
        var collector = collector(diff, target("h1", fixture(queue)));

        collector.round().await(Pg.WAIT).unwrap();
        now.set(T0.plusSeconds(30));
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(baselines).hasSize(2);
        assertThat(baselines.get(0)).isEqualTo(none());
        assertThat(baselines.get(1).unwrap().takenAt()).isEqualTo(T0);
        assertThat(baselines.get(1).unwrap().tickets()).extracting(BbTicket::ticket).containsExactly("r1", "w1", "d1");
        assertThat(fleetKinds()).contains("ticket-queued");
    }

    private List<String> fleetKinds() {
        var rows = ((org.pragmatica.temper.store.api.FleetPending.Events) store.fleet()
                                                                               .pending(0, 1000)
                                                                               .await(Pg.WAIT)
                                                                               .unwrap()).rows();

        return rows.stream()
                   .map(org.pragmatica.temper.store.api.FleetEventRow::kind)
                   .toList();
    }

    // ---- #103, retired hosts ------------------------------------------------------------------------------------
    /// A transport that counts its reads and fails each (an unreachable, released address).
    private static TicketTransport counting(AtomicInteger reads) {
        return _ -> {
            reads.incrementAndGet();

            return TicketError.SshExit.sshExit(255, "Connection refused").promise();
        };
    }

    /// #103: a host whose `retireAt` passed before the first round is never read; the host beside it is. Mutation: read
    /// a retired host anyway → red.
    @Test
    void retiredBeforeTheFirstRound_isNeverRead() {
        var retired = new AtomicInteger();
        var live = new AtomicInteger();
        var collector = collector(TicketDiff.NONE,
                                  TicketCollector.Target.target("h1", counting(retired), some(T0.minusSeconds(1))),
                                  target("h2", counting(live)));

        collector.round().await(Pg.WAIT).unwrap();
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(retired).hasValue(0);
        assertThat(live).hasValue(2);
    }

    /// #103: a host is read until its `retireAt`, and not from that instant on (retired at the instant itself).
    @Test
    void retireAtPassingBetweenRounds_stopsTheReads() {
        var retiring = new AtomicInteger();
        var live = new AtomicInteger();
        var collector = collector(TicketDiff.NONE,
                                  TicketCollector.Target.target("h1", counting(retiring), some(T0.plusSeconds(60))),
                                  target("h2", counting(live)));

        collector.round().await(Pg.WAIT).unwrap();
        assertThat(retiring).hasValue(1);
        now.set(T0.plusSeconds(60));
        collector.round().await(Pg.WAIT).unwrap();
        now.set(T0.plusSeconds(90));
        collector.round().await(Pg.WAIT).unwrap();
        assertThat(retiring).hasValue(1);
        assertThat(live).hasValue(3);
    }
}
