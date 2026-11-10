package org.pragmatica.temper.store.pg;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.store.api.BbAlarm;
import org.pragmatica.temper.store.api.BbCollect;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbStore;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.FleetSeqs;
import org.pragmatica.temper.store.api.NewFleetEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;


/// The bb collection store: the snapshot upsert, `failing_since` kept across failures and
/// cleared by a success, the one transaction of a success (diff over the baseline, events last, source seen), and the
/// all-or-nothing rollback.
@Tag("pg")
class BbStoreIT {
    private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");

    private StoreHarness harness;
    private BbStore bb;

    @BeforeEach
    void setUp() {
        harness = new StoreHarness();
        bb = harness.store.bb();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void collected_writesSnapshot_collectState_andTheSource_atTheCollectionTime() {
        var tickets = List.of(ticket("a", "running", "alive"), ticket("b", "done", null));

        collected(bb, "h1", T0, "present", tickets, _ -> List.of()).await(PgFixture.WAIT).unwrap();
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap()).isEqualTo(some(snap("h1",
                                                                                         T0,
                                                                                         some("present"),
                                                                                         tickets)));
        assertThat(bb.snapshot("h2").await(PgFixture.WAIT).unwrap()).isEqualTo(none());
        assertThat(bb.collects().await(PgFixture.WAIT).unwrap()).containsExactly(BbCollect.bbCollect("h1",
                                                                                                     some(T0),
                                                                                                     some(T0),
                                                                                                     none(),
                                                                                                     none()));
        var source = harness.store.fleet().source("tickets:h1").await(PgFixture.WAIT).unwrap().unwrap();

        assertThat(source.lastOkAt()).isEqualTo(some(T0));
        assertThat(source.periodS()).isEqualTo(30);
    }

    @Test
    void snapshot_isOneRowPerHost_replacedByEverySuccess_inOutputOrder() {
        collected(bb,
                  "h1",
                  T0,
                  "present",
                  List.of(ticket("z", "waiting", "alive"),
                          ticket("a", "waiting", "alive")),
                  _ -> List.of()).await(PgFixture.WAIT)
                 .unwrap();
        collected(bb,
                  "h1",
                  T0.plusSeconds(30),
                  "present",
                  List.of(ticket("m", "running", "gone")),
                  _ -> List.of()).await(PgFixture.WAIT)
                 .unwrap();
        collected(bb, "h2", T0, "present", List.of(), _ -> List.of()).await(PgFixture.WAIT).unwrap();
        assertThat(text("SELECT count(*)::text AS v FROM bb_snapshot")).isEqualTo("2");
        assertThat(bb.snapshots().await(PgFixture.WAIT).unwrap()).extracting(BbSnapshot::host)
                  .containsExactly("h1", "h2");
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap().unwrap().tickets()).extracting(BbTicket::ticket)
                  .containsExactly("m");
        var order = collected(bb,
                              "h3",
                              T0,
                              "present",
                              List.of(ticket("z", "waiting", "alive"),
                                      ticket("a", "waiting", "alive"),
                                      ticket("m", "done", null)),
                              _ -> List.of());

        order.await(PgFixture.WAIT).unwrap();
        assertThat(bb.snapshot("h3").await(PgFixture.WAIT).unwrap().unwrap().tickets()).extracting(BbTicket::ticket)
                  .containsExactly("z", "a", "m");
    }

    /// T3.8c's store half: the first failure after a success is kept as `failing_since` by every later failure; a
    /// success clears it. Mutation: set `failing_since` on every failure (drop the COALESCE) → red.
    @Test
    void failing_sinceIsTheFirstFailure_keptByLaterOnes_clearedBySuccess_andTheSnapshotNeverMoves() {
        var tickets = List.of(ticket("a", "running", "alive"));

        collected(bb, "h1", T0, "present", tickets, _ -> List.of()).await(PgFixture.WAIT).unwrap();
        bb.failed("h1", T0.plusSeconds(30), "ssh exit 255: no route").await(PgFixture.WAIT).unwrap();
        bb.failed("h1", T0.plusSeconds(60), "timeout").await(PgFixture.WAIT).unwrap();
        assertThat(bb.collects().await(PgFixture.WAIT).unwrap()).containsExactly(BbCollect.bbCollect("h1",
                                                                                                     some(T0.plusSeconds(60)),
                                                                                                     some(T0),
                                                                                                     some(T0.plusSeconds(30)),
                                                                                                     some("timeout")));
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap()).isEqualTo(some(snap("h1",
                                                                                         T0,
                                                                                         some("present"),
                                                                                         tickets)));
        collected(bb, "h1", T0.plusSeconds(90), "present", tickets, _ -> List.of()).await(PgFixture.WAIT).unwrap();
        assertThat(bb.collects().await(PgFixture.WAIT).unwrap()).containsExactly(BbCollect.bbCollect("h1",
                                                                                                     some(T0.plusSeconds(90)),
                                                                                                     some(T0.plusSeconds(90)),
                                                                                                     none(),
                                                                                                     none()));
    }

    @Test
    void failed_beforeAnySuccess_createsTheRow_withoutLastOk_andLongErrorsAreCut() {
        bb.failed("h1", T0, "x".repeat(600)).await(PgFixture.WAIT).unwrap();
        var row = bb.collects().await(PgFixture.WAIT).unwrap().getFirst();

        assertThat(row.lastOkAt()).isEqualTo(none());
        assertThat(row.failingSince()).isEqualTo(some(T0));
        assertThat(row.lastError().unwrap()).hasSize(512);
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap()).isEqualTo(none());
    }

    @Test
    void diff_seesTheBaselineRead_andItsEventsAreInsertedWithTheSnapshot() {
        var seen = new AtomicReference<Option<BbSnapshot>>();
        var first = List.of(ticket("a", "waiting", "alive"));

        collected(bb,
                  "h1",
                  T0,
                  "present",
                  first,
                  baseline -> {
                      seen.set(baseline);

                      return List.of();
                  }).await(PgFixture.WAIT)
                 .unwrap();
        assertThat(seen.get()).isEqualTo(none());
        var seqs = collected(bb,
                             "h1",
                             T0.plusSeconds(30),
                             "present",
                             List.of(ticket("a", "running", "alive")),
                             baseline -> {
                                 seen.set(baseline);

                                 return List.of(event("ticket-started", "h1/a"),
                                                event("ticket-queued", "h1/b"));
                             }).await(PgFixture.WAIT)
                            .unwrap();

        assertThat(seen.get()).isEqualTo(some(snap("h1", T0, some("present"), first)));
        assertThat(seqs.unwrap().last() - seqs.unwrap().first()).isEqualTo(1);
        assertThat(text("SELECT string_agg(kind, ',' ORDER BY seq) AS v FROM fleet_events WHERE source = 'controller'")).isEqualTo("ticket-started,ticket-queued");
    }

    /// The events and the snapshot are one transaction: an event the stream refuses rolls the snapshot, the collect
    /// state and the source back. Mutation: write the snapshot outside the transaction → red.
    @Test
    void aFailingEventInsert_rollsBackSnapshot_collectState_andSource() {
        var old = List.of(ticket("a", "waiting", "alive"));

        collected(bb, "h1", T0, "present", old, _ -> List.of()).await(PgFixture.WAIT).unwrap();
        var bad = new NewFleetEvent(T0.plusSeconds(30),
                                    "controller",
                                    none(),
                                    none(),
                                    "ticket-started",
                                    "h1/a",
                                    "not json",
                                    false);
        Result<?> outcome = collected(bb,
                                      "h1",
                                      T0.plusSeconds(30),
                                      "present",
                                      List.of(ticket("a", "running", "alive")),
                                      _ -> List.of(bad)).await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).as("a payload that is not JSON violates the jsonb column").isTrue();
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap()).isEqualTo(some(snap("h1", T0, some("present"), old)));
        assertThat(bb.collects().await(PgFixture.WAIT).unwrap().getFirst().lastOkAt()).isEqualTo(some(T0));
        assertThat(harness.store.fleet().source("tickets:h1").await(PgFixture.WAIT).unwrap().unwrap().lastOkAt()).isEqualTo(some(T0));
    }

    /// Two steps of one host held concurrently (an abandoned step still running): the second waits for the first's
    /// transaction and diffs against its snapshot, so the event is emitted once and the newer snapshot is final.
    /// Mutation: drop the advisory lock → both read the empty baseline, two events, red.
    @Test
    void concurrentStepsOfOneHost_areSerialized_eventsOnce_andTheNewerSnapshotWins() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondSaw = new AtomicReference<Option<BbSnapshot>>();
        var tickets = List.of(ticket("a", "running", "alive"));
        var first = collected(bb,
                              "h1",
                              T0.plusSeconds(30),
                              "present",
                              tickets,
                              baseline -> {
                                  entered.countDown();
                                  await(release);

                                  return startedUnless(baseline);
                              });

        assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
        var second = collected(bb,
                               "h1",
                               T0.plusSeconds(60),
                               "present",
                               tickets,
                               baseline -> {
                                   secondSaw.set(baseline);

                                   return startedUnless(baseline);
                               });

        Thread.sleep(1000);
        release.countDown();
        first.await(PgFixture.WAIT).unwrap();
        second.await(PgFixture.WAIT).unwrap();
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events WHERE kind = 'ticket-started'")).isEqualTo("1");
        assertThat(secondSaw.get().map(BbSnapshot::takenAt)).isEqualTo(some(T0.plusSeconds(30)));
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap().unwrap().takenAt()).isEqualTo(T0.plusSeconds(60));
    }

    /// A step older than the baseline writes nothing and fails with `StaleStep`. Mutation: drop the stale check → the
    /// snapshot moves back and its events land, red.
    @Test
    void aStaleStep_writesNothing_noSnapshot_noEvents_noCollectState() {
        var newer = List.of(ticket("a", "running", "alive"));

        collected(bb, "h1", T0.plusSeconds(60), "present", newer, _ -> List.of()).await(PgFixture.WAIT).unwrap();
        Result<?> outcome = collected(bb,
                                      "h1",
                                      T0.plusSeconds(30),
                                      "absent",
                                      List.of(),
                                      _ -> List.of(event("ticket-finished", "h1/a"))).await(PgFixture.WAIT);

        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isInstanceOf(BbStore.StaleStep.class));
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap()).isEqualTo(some(snap("h1",
                                                                                         T0.plusSeconds(60),
                                                                                         some("present"),
                                                                                         newer)));
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events WHERE kind = 'ticket-finished'")).isEqualTo("0");
        assertThat(bb.collects().await(PgFixture.WAIT).unwrap().getFirst().lastOkAt()).isEqualTo(some(T0.plusSeconds(60)));
    }

    /// A failure recorded at an older `now` than a success leaves `bb_collect` as the success left it. Mutation: drop
    /// the WHERE guard of the failure upsert → `failing_since` and `last_error` appear, red.
    @Test
    void aStaleFailure_afterANewerSuccess_leavesBbCollectUnchanged() {
        collected(bb, "h1", T0.plusSeconds(60), "present", List.of(), _ -> List.of()).await(PgFixture.WAIT).unwrap();
        bb.failed("h1", T0.plusSeconds(30), "timeout").await(PgFixture.WAIT).unwrap();
        assertThat(bb.collects().await(PgFixture.WAIT).unwrap()).containsExactly(BbCollect.bbCollect("h1",
                                                                                                     some(T0.plusSeconds(60)),
                                                                                                     some(T0.plusSeconds(60)),
                                                                                                     none(),
                                                                                                     none()));
    }

    /// An abandoned success (older `now`) landing after a newer failure leaves `bb_collect` as the failure left it: the
    /// stale-step check does not see it (no snapshot yet), so only the COLLECT_OK guard protects the row. Mutation: drop
    /// the WHERE guard of the success upsert → the failure is wiped (attempt moves back, `failing_since`/`last_error` null), red.
    @Test
    void aStaleSuccess_afterANewerFailure_leavesBbCollectAsTheFailureLeftIt() {
        bb.failed("h1", T0.plusSeconds(60), "timeout").await(PgFixture.WAIT).unwrap();
        collected(bb, "h1", T0.plusSeconds(30), "present", List.of(), _ -> List.of()).await(PgFixture.WAIT).unwrap();
        assertThat(bb.collects().await(PgFixture.WAIT).unwrap()).containsExactly(BbCollect.bbCollect("h1",
                                                                                                     some(T0.plusSeconds(60)),
                                                                                                     none(),
                                                                                                     some(T0.plusSeconds(60)),
                                                                                                     some("timeout")));
    }

    /// A step whose `now` equals the baseline's `takenAt` is stale; one microsecond later is applied (timestamptz keeps
    /// microseconds). Mutation: stale only when strictly after (`isAfter`) → the equal step is applied, red.
    @Test
    void aStepAtExactlyTheBaselineTime_isStale_oneMicrosecondLaterIsApplied() {
        var tickets = List.of(ticket("a", "running", "alive"));
        var later = T0.plus(1, ChronoUnit.MICROS);

        collected(bb, "h1", T0, "present", tickets, _ -> List.of()).await(PgFixture.WAIT).unwrap();
        Result<?> equal = collected(bb, "h1", T0, "absent", List.of(), _ -> List.of(event("ticket-finished", "h1/a"))).await(PgFixture.WAIT);

        assertThat(equal.isFailure()).isTrue();
        equal.onFailure(cause -> assertThat(cause).isInstanceOf(BbStore.StaleStep.class));
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events WHERE kind = 'ticket-finished'")).isEqualTo("0");
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap().unwrap().takenAt()).isEqualTo(T0);
        collected(bb,
                  "h1",
                  later,
                  "absent",
                  List.of(),
                  _ -> List.of(event("ticket-finished", "h1/a"))).await(PgFixture.WAIT)
                 .unwrap();
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events WHERE kind = 'ticket-finished'")).isEqualTo("1");
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap().unwrap().takenAt()).isEqualTo(later);
    }

    /// Two hosts' collections do not serialize: while host h1's step is held inside its diff, host h2's completes.
    /// Mutation: a constant lock key (no host) → h2 waits for h1, the bounded wait times out, red.
    @Test
    void stepsOfDifferentHosts_doNotBlockEachOther() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var held = collected(bb,
                             "h1",
                             T0,
                             "present",
                             List.of(),
                             baseline -> {
                                 entered.countDown();
                                 await(release);

                                 return List.of();
                             });

        try {
            assertThat(entered.await(30, TimeUnit.SECONDS)).isTrue();
            var other = collected(bb, "h2", T0, "present", List.of(), _ -> List.of()).await(TimeSpan.timeSpan(5).seconds());

            assertThat(other.isSuccess()).as("h2 completes while h1 is held").isTrue();
        } finally {
            release.countDown();
        }

        held.await(PgFixture.WAIT).unwrap();
    }

    private static List<NewFleetEvent> startedUnless(Option<BbSnapshot> baseline) {
        var known = baseline.map(snapshot -> !snapshot.tickets()
                                                      .isEmpty()).or(false);

        return known
               ? List.of()
               : List.of(event("ticket-started", "h1/a"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void v15_checksRefuseABadHost() {
        assertThat(bb.failed("-bad", T0, "x").await(PgFixture.WAIT).isFailure()).isTrue();
        assertThat(collected(bb, "a b", T0, "present", List.of(), _ -> List.of()).await(PgFixture.WAIT).isFailure()).isTrue();
    }

    @Test
    void theQueueOfTheTrailer_isStoredWithTheSnapshot_andReplacedByTheNextSuccess() {
        collected(bb, "h1", T0, "absent", List.of(), _ -> List.of()).await(PgFixture.WAIT).unwrap();
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap().map(BbSnapshot::queue)).isEqualTo(some(some("absent")));
        collected(bb, "h1", T0.plusSeconds(30), "present", List.of(), _ -> List.of()).await(PgFixture.WAIT).unwrap();
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap().map(BbSnapshot::queue)).isEqualTo(some(some("present")));
        assertThat(collected(bb,
                             "h2",
                             T0,
                             "maybe",
                             List.of(),
                             _ -> List.of()).await(PgFixture.WAIT).isFailure()).as("CHECK")
                  .isTrue();
    }

    // ---- host collection -------------------------------------------------------------------------------
    private static BbAlarm mark(String host, String kind, String subject, String threshold) {
        return BbAlarm.bbAlarm(host, kind, subject, threshold, T0);
    }

    /// The listing's trailer `at` and the round's skew are stored on the snapshot; the round sees the host's marks only,
    /// fires and clears marks in the round's transaction, and `alarms()` lists every mark.
    @Test
    void s1_collected_storesAtAndSkew_andChangesTheMarksTheRoundReturns() {
        var seen = new AtomicReference<List<BbAlarm>>();
        var at = T0.minusSeconds(40);

        bb.retiring(T0,
                    _ -> BbStore.RoundWrite.roundWrite(List.of(),
                                                       List.of(mark("h2", "host-retiring", "2026-11-22T00:00:00Z", "3")),
                                                       List.of()))
          .await(PgFixture.WAIT)
          .unwrap();
        bb.collected("h1",
                     T0,
                     BbStore.Listing.listing("present",
                                             List.of(),
                                             at,
                                             - 31),
                     (_, rows) -> {
                         seen.set(rows);

                         return BbStore.RoundWrite.roundWrite(List.of(event("clock-skew", "h1")),
                                                              List.of(mark("h1", "clock-skew", "-", "30"),
                                                                      mark("h1", "queue-wait", "w1", "60")),
                                                              List.of());
                     })
          .await(PgFixture.WAIT)
          .unwrap();
        assertThat(seen.get()).isEmpty();
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap().unwrap()).isEqualTo(BbSnapshot.bbSnapshot("h1",
                                                                                                              T0,
                                                                                                              some("present"),
                                                                                                              List.of(),
                                                                                                              some(at),
                                                                                                              some(-31)));
        bb.collected("h1",
                     T0.plusSeconds(30),
                     BbStore.Listing.listing("present",
                                             List.of(),
                                             at.plusSeconds(30),
                                             0),
                     (_, rows) -> {
                         seen.set(rows);

                         return BbStore.RoundWrite.roundWrite(List.of(),
                                                              List.of(),
                                                              List.of(rows.getFirst()));
                     })
          .await(PgFixture.WAIT)
          .unwrap();
        assertThat(seen.get()).extracting(BbAlarm::kind).containsExactly("clock-skew", "queue-wait");
        assertThat(bb.alarms().await(PgFixture.WAIT).unwrap()).containsExactly(mark("h1", "queue-wait", "w1", "60"),
                                                                               mark("h2",
                                                                                    "host-retiring",
                                                                                    "2026-11-22T00:00:00Z",
                                                                                    "3"));
        assertThat(text("SELECT count(*)::text AS v FROM fleet_events WHERE kind = 'clock-skew'")).isEqualTo("1");
    }

    /// S1.13 at the store: an event that cannot be inserted rolls back the marks of its round (and of a retire tick);
    /// the key is the primary key, so a second insert of one mark fails the round instead of firing twice.
    @Test
    void s1_13_aFailedEventInsert_rollsBackTheMarks_andAMarkIsUnique() {
        var bad = NewFleetEvent.newFleetEvent(T0, "controller", "queue-wait", "h1/w1", "not json");
        var round = bb.collected("h1",
                                 T0,
                                 BbStore.Listing.listing("present",
                                                         List.of(),
                                                         T0,
                                                         0),
                                 (_, _) -> BbStore.RoundWrite.roundWrite(List.of(bad),
                                                                         List.of(mark("h1", "queue-wait", "w1", "60")),
                                                                         List.of()))
                      .await(PgFixture.WAIT);
        var tick = bb.retiring(T0,
                               _ -> BbStore.RoundWrite.roundWrite(List.of(bad),
                                                                  List.of(mark("h1", "host-retiring", "x", "1")),
                                                                  List.of()))
                     .await(PgFixture.WAIT);

        assertThat(round.isFailure()).isTrue();
        assertThat(tick.isFailure()).isTrue();
        assertThat(bb.alarms().await(PgFixture.WAIT).unwrap()).isEmpty();
        assertThat(bb.snapshot("h1").await(PgFixture.WAIT).unwrap()).isEqualTo(none());
        var twice = bb.retiring(T0,
                                _ -> BbStore.RoundWrite.roundWrite(List.of(),
                                                                   List.of(mark("h1", "host-retiring", "x", "1"),
                                                                           mark("h1", "host-retiring", "x", "1")),
                                                                   List.of()))
                      .await(PgFixture.WAIT);

        assertThat(twice.isFailure()).as("the primary key").isTrue();
        assertThat(bb.alarms().await(PgFixture.WAIT).unwrap()).isEmpty();
        assertThat(bb.retiring(T0,
                               _ -> BbStore.RoundWrite.roundWrite(List.of(),
                                                                  List.of(mark("h1", "nonsense", "x", "1")),
                                                                  List.of()))
                     .await(PgFixture.WAIT)
                     .isFailure()).as("V17 kind CHECK")
                  .isTrue();
    }

    /// The pre-S1 call shape: the diff only, the trailer `at` equal to `now`, no skew, no alarm marks.
    private static Promise<Option<FleetSeqs>> collected(BbStore bb,
                                                        String host,
                                                        Instant now,
                                                        String queue,
                                                        List<BbTicket> tickets,
                                                        Fn1<List<NewFleetEvent>, Option<BbSnapshot>> diff) {
        return bb.collected(host,
                            now,
                            BbStore.Listing.listing(queue, tickets, now, 0),
                            (baseline, _) -> BbStore.RoundWrite.eventsOnly(diff.apply(baseline)));
    }

    /// A snapshot as [#collected] writes it: `hostAt` = `takenAt`, skew 0.
    private static BbSnapshot snap(String host, Instant at, Option<String> queue, List<BbTicket> tickets) {
        return BbSnapshot.bbSnapshot(host, at, queue, tickets, some(at), some(0));
    }

    private static BbTicket ticket(String id, String dir, String waiter) {
        return BbTicket.bbTicket(1,
                                 id,
                                 dir,
                                 Option.option(waiter),
                                 Map.of("owner", "t3wpc1", "created", "1793667998"),
                                 List.of());
    }

    private static NewFleetEvent event(String kind, String subject) {
        return NewFleetEvent.newFleetEvent(T0, "controller", kind, subject, "{\"ticket\":\"x\"}");
    }

    private String text(String sql) {
        return Sql.query(harness.raw.executor(),
                         sql)
                  .map(resultSet -> Sql.first(resultSet)
                                       .unwrap()
                                       .getString("v"))
                  .await(PgFixture.WAIT)
                  .unwrap();
    }
}
