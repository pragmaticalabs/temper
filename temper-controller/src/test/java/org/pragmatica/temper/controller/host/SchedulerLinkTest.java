package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.temper.controller.policy.Fit;
import org.pragmatica.temper.controller.policy.GrantExplain;
import org.pragmatica.temper.controller.scheduler.Scheduler;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.Granted;
import org.pragmatica.temper.store.api.PortGroup;
import org.pragmatica.temper.store.api.QueuedUnit;
import org.pragmatica.temper.store.api.Starvation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// The grant loop against a real [HostLink] over a fake worker (m0-design §6.5).
class SchedulerLinkTest {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final Instant T0 = Instant.parse("2026-10-13T10:00:00Z");
    private LinkHarness harness;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        start(false);
    }

    private void start(boolean portIsolated) throws Exception {
        over(new LinkHarness(LinkHarness.FAST, portIsolated));
    }

    private void over(LinkHarness next) {
        harness = next;
        harness.responder = request -> switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit start -> List.of(new Message.UnitStarted(start.job(), start.unit(), 1, "c", "i", 1L,
                                                                             Instant.now(), LinkHarness.facts(0), none()));
            default -> LinkHarness.ackEverything(request);
        };
        var links = HostLinks.hostLinks(List.of(harness.link), harness.jobs);
        scheduler = Scheduler.scheduler(links, harness.units, Duration.ofSeconds(30), Duration.ofSeconds(1200), Duration.ofMinutes(10), 50 * GIB, 1,
                                        () -> T0, TimeSpan.timeSpan(200).millis());
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    private void up(int foreignReactors) throws Exception {
        up(LinkHarness.facts(foreignReactors));
    }

    private void up(HostFactsDto facts) throws Exception {
        harness.link.start();
        var worker = harness.nextWorker();
        var hello = hello(LinkHarness.VERSION, List.of());
        worker.send(new Message.Hello(hello.protocol(), hello.workerVersion(), hello.host(), 1, 1, 1, 32,
                                      facts, List.of()));
        waitFor(harness.link::isUp, "link UP");
    }

    private void queue(long id, int memoryMb) {
        queue(id, memoryMb, false);
    }

    private void queue(long id, int memoryMb, boolean needsPortGroup) {
        queue(id, memoryMb, needsPortGroup, UnitKind.MVN);
    }

    private void queue(long id, int memoryMb, boolean needsPortGroup, UnitKind kind) {
        harness.units.queue.add(QueuedUnit.queuedUnit(id, -20, T0, memoryMb, needsPortGroup, kind));
        harness.units.grantable.put(id, Granted.granted(UnitRef.unitRef(JobId.jobId(id).unwrap(), UnitName.MAIN), id,
                                                        kind, List.of(List.of("mvn", "-B", "verify")),
                                                        Duration.ofMinutes(60), memoryMb, Sha.sha("a".repeat(40)).unwrap(),
                                                        needsPortGroup, 0, none(), Instant.now(), 1));
    }

    /// D1: a host the scheduler would skip (its link not UP, no facts) answers `no-link` for every unit.
    @Test
    void explain_linkNotUp_everyUnitIsNoLink() {
        var units = List.of(QueuedUnit.queuedUnit(1, -20, T0, 6144, false, UnitKind.MVN));

        var explained = scheduler.explain(units).await().unwrap();

        assertThat(explained).containsExactly(GrantExplain.HostReasons.hostReasons(LinkHarness.HOST,
                                                                                    Map.of(1L, List.of(new GrantExplain.NoLink(harness.link.state().wire(),
                                                                                                                               false)))));
    }

    /// D1: a host whose session closed after `Hello` keeps its last facts but is not UP, so the tick skips it and the
    /// explanation says `no-link` with facts, not the grant a facts-only check would describe.
    @Test
    void explain_sessionLostWithFacts_everyUnitIsNoLink() throws Exception {
        up(0);
        queue(1, 6144);
        harness.lastWorker.close();
        waitFor(() -> !harness.link.isUp(), "link not UP");

        var explained = scheduler.explain(List.copyOf(harness.units.queue)).await().unwrap();

        assertThat(harness.link.facts().isPresent()).isTrue();
        assertThat(explained.getFirst().reasons().get(1L)).singleElement()
                                                          .isInstanceOfSatisfying(GrantExplain.NoLink.class,
                                                                                  noLink -> assertThat(noLink.facts()).isTrue());
    }

    /// A LOST host is named so in its skip reason, with its mark, never by its link state (m4-design §2.3, §11: `lost
    /// <reason>`).
    @Test
    void explain_lostHost_saysLost() throws Exception {
        harness.link.stop();
        over(new LinkHarness(LinkHarness.FAST, Duration.ofMillis(300)));
        harness.link.start();
        waitFor(() -> harness.link.lost().isPresent(), "LOST");
        var mark = harness.link.lost().unwrap();

        var explained = scheduler.explain(List.of(QueuedUnit.queuedUnit(1, -20, T0, 6144, false, UnitKind.MVN))).await().unwrap();

        assertThat(explained.getFirst().reasons().get(1L)).containsExactly(new GrantExplain.Lost(mark.reason(), mark.at()));
    }

    /// Q-M4-12: an UP host whose verdict is deferred takes no grant, and its skip reason says why, not `link=UP`. No
    /// tick runs, so nothing declares it LOST however late the UP comes.
    @Test
    void explain_deferredHost_saysDeferred() throws Exception {
        harness.link.stop();
        over(new LinkHarness(LinkHarness.timing(LinkHarness.FAST.hello(), Duration.ofSeconds(30), TimeSpan.timeSpan(1).hours(),
                                                LinkHarness.FAST.request(), Duration.ofSeconds(60)),
                             Duration.ofSeconds(1)));
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        waitFor(() -> harness.link.state() instanceof LinkState.Up, "UP");
        waitFor(() -> !harness.link.isUp(), "deferred past hostLost");

        var explained = scheduler.explain(List.of(QueuedUnit.queuedUnit(1, -20, T0, 6144, false, UnitKind.MVN))).await().unwrap();

        assertThat(explained.getFirst().reasons().get(1L)).containsExactly(new GrantExplain.NoLink("DEFERRED", true));
    }

    /// D1: an active read that fails, or hangs past the tick bound, makes the host unknown instead of failing the answer.
    @Test
    void explain_activeReadFailsOrHangs_hostIsUnknown() throws Exception {
        up(0);
        queue(1, 6144);
        harness.units.failActive = true;

        var failed = scheduler.explain(List.copyOf(harness.units.queue)).await().unwrap();

        assertThat(failed.getFirst().reasons().get(1L)).singleElement()
                                                      .isInstanceOfSatisfying(GrantExplain.Unknown.class,
                                                                              unknown -> assertThat(unknown.cause().message()).contains("store down"));
        harness.units.failActive = false;
        harness.units.hangActive = true;

        var hung = scheduler.explain(List.copyOf(harness.units.queue)).await(TimeSpan.timeSpan(5).seconds()).unwrap();

        assertThat(hung.getFirst().reasons().get(1L)).singleElement().isInstanceOf(GrantExplain.Unknown.class);
        harness.units.hangActive = false;
    }

    /// D1: while the host's previous round is still in flight the tick skips it, and so the explanation says `busy`.
    @Test
    void explain_roundInFlight_hostIsBusy() throws Exception {
        up(0);
        queue(1, 6144);
        harness.units.hangActive = true;
        var tick = scheduler.tick();

        waitFor(() -> scheduler.roundsInFlight() == 1, "round in flight");
        harness.units.hangActive = false;
        var explained = scheduler.explain(List.copyOf(harness.units.queue)).await().unwrap();

        assertThat(explained.getFirst().reasons()).isEqualTo(Map.of(1L, List.of(new GrantExplain.Busy())));
        tick.await();
    }

    /// D1 on an UP host: the explanation reads the same inputs as a tick (facts, `UnitStore.active`, the starvation
    /// state), names the port-group holder as the reason, says the plain unit would start, and the next tick grants
    /// exactly that unit. Explaining writes no starvation state.
    @Test
    void explain_upHost_agreesWithTheNextTick_leavesStarvationAlone() throws Exception {
        up(0);
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(90).unwrap(), UnitName.MAIN), 90, UnitState.GRANTED,
                                                6144, Duration.ofHours(3), none(), none(), true, some(PortGroup.WAITING), none(), UnitKind.MVN, some(Instant.now()), 1));
        queue(1, 6144, true);
        queue(2, 6144);

        var explained = scheduler.explain(List.copyOf(harness.units.queue)).await().unwrap();

        assertThat(explained).containsExactly(GrantExplain.HostReasons.hostReasons(LinkHarness.HOST,
                                                                                    Map.of(1L, List.of(new Fit.PortGroup(1, 1)),
                                                                                           2L, List.of())));
        assertThat(scheduler.starvation(LinkHarness.HOST)).isEqualTo(Starvation.NONE);

        scheduler.tick().await();

        assertThat(harness.units.queue).extracting(QueuedUnit::unitId).containsExactly(1L);
    }

    /// Q-M1-12 across ticks (m1-design §1.3): a port-group unit already waiting for or holding the group on this
    /// host, as the store lists it, blocks the next one; without it the same unit is granted.
    @Test
    void tick_grantsNoSecondPortGroupUnit_whileOneWaitsOrHoldsOnTheHost() throws Exception {
        up(0);
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(90).unwrap(), UnitName.MAIN), 90, UnitState.GRANTED,
                                                6144, Duration.ofHours(3), none(), none(), true, some(PortGroup.WAITING), none(), UnitKind.MVN, some(Instant.now()), 1));
        queue(1, 6144, true);

        scheduler.tick().await();

        assertThat(harness.units.queue).extracting(QueuedUnit::unitId).containsExactly(1L);
        assertThat(harness.lastWorker.count(Message.PrepareJob.class)).isZero();

        harness.units.activeOn(LinkHarness.HOST).clear();
        scheduler.tick().await();

        assertThat(harness.units.queue).as("granted once the holder is gone").isEmpty();
    }

    /// Ruling R-3 across ticks: on a port-isolated host a Gate still goes through bb-hold (m0-design §12.4), so while
    /// one Gate waits for or holds the group a second Gate is not granted only to wait in bb-hold; a non-Gate
    /// port-group unit, which starts directly there, is.
    @Test
    void tick_portIsolatedHost_grantsNoSecondGate_whileOneHolds() throws Exception {
        harness.link.stop();
        start(true);
        up(0);
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(90).unwrap(), UnitName.GATE), 90, UnitState.RUNNING,
                                                6144, Duration.ofHours(3), none(), none(), true, some(PortGroup.HELD), some("t-1"),
                                                UnitKind.GATE_UNSHARDED, some(Instant.now()), 1));
        queue(1, 6144, true, UnitKind.GATE_UNSHARDED);
        queue(2, 6144, true, UnitKind.MODULE_TEST);

        scheduler.tick().await();

        assertThat(harness.units.queue).as("the second Gate waits; the isolated module test is granted")
                                       .extracting(QueuedUnit::unitId)
                                       .containsExactly(1L);

        harness.units.activeOn(LinkHarness.HOST).removeIf(unit -> unit.unitId() == 90);
        scheduler.tick().await();

        assertThat(harness.units.queue).as("granted once the first Gate is gone").isEmpty();
    }

    /// The host is released as a step of its round, not by a detached callback (those run on another thread, after
    /// the tick's promise may already have resolved): with a store that answers on another thread, a tick that
    /// completed has freed its host, so the next tick grants. Sampled immediately after every await, many times,
    /// because a detached release loses that race only for microseconds.
    @Test
    void tick_afterACompletedTick_grantsOnTheSameHost_storeAnsweringLater() throws Exception {
        up(0);
        harness.units.delayMs = 1;
        for (var round = 0; round < 200; round++) {
            scheduler.tick().await();

            assertThat(scheduler.roundsInFlight()).as("round " + round).isZero();
        }
        queue(1, 6144);

        scheduler.tick().await();

        assertThat(harness.units.queue).isEmpty();
    }

    @Test
    void tick_grantsAndLaunches_queuedUnit() throws Exception {
        up(0);
        queue(1, 6144);

        scheduler.tick().await();

        var start = harness.lastWorker.expect(Message.StartUnit.class, 5);
        assertThat(start.job()).isEqualTo(1);
        waitFor(() -> harness.units.started.size() == 1, "start recorded");
        assertThat(harness.units.grantCalls).containsExactly(5);
    }

    @Test
    void tick_grantsNothing_capReachedByForeignReactors() throws Exception {
        up(5);
        queue(1, 6144);

        scheduler.tick().await();

        assertThat(harness.units.queue).hasSize(1);
        assertThat(harness.lastWorker.count(Message.PrepareJob.class)).isZero();
    }

    @Test
    void tick_respectsMemoryBudget_withinOneTick() throws Exception {
        up(0);
        queue(1, 8000);
        queue(2, 8000);
        queue(3, 8000);

        scheduler.tick().await();

        assertThat(harness.units.queue).extracting(QueuedUnit::unitId).containsExactly(3L);
    }

    /// Owner rule 2026-11-02: MemAvailable 10 GiB, floor 4 GiB, two 4 GiB units fit only together (6 GiB is left after
    /// one, 2 GiB after both). One tick grants one; the next tick, on the same facts, still does not grant the second,
    /// because the first is GRANTED in the store (no recorded start) and so counts as pending memory.
    @Test
    void tick_memFloor_grantsOneOfTwoUnitsThatFitOnlyTogether_inOneTickAndTheNext() throws Exception {
        over(LinkHarness.withMemFloor(LinkHarness.FAST, 4));
        up(LinkHarness.facts(0, 10L * 1024 * 1024));
        queue(1, 4096);
        queue(2, 4096);

        scheduler.tick().await();

        assertThat(harness.units.queue).as("first tick").extracting(QueuedUnit::unitId).containsExactly(2L);

        scheduler.tick().await();

        assertThat(harness.units.queue).as("second tick, same facts").extracting(QueuedUnit::unitId).containsExactly(2L);
        assertThat(harness.units.grantCalls).as("both ticks ran a round").hasSize(2);
    }

    /// The pending term reads the facts' own instant: a RUNNING unit whose recorded start is after the latest sample
    /// still counts, though newer facts (sampled before that start) arrived; once a sample taken after the start
    /// arrives, the unit is taken to be in MemAvailable and stops counting. MemAvailable 10 GiB throughout, floor
    /// 4 GiB, two 4 GiB units: unit 2 waits while unit 1 is pending (10 − 4 − 4 = 2 < 4), then is granted (10 − 4 =
    /// 6 ≥ 4).
    @Test
    void tick_memFloor_runningUnitStartedAfterTheFacts_staysPendingUntilASampleAfterItsStart() throws Exception {
        var hello = Instant.parse("2026-10-13T10:00:00Z");
        var started = hello.plusSeconds(5);
        over(LinkHarness.withMemFloor(LinkHarness.FAST, 4));
        up(LinkHarness.facts(0, 10L * 1024 * 1024, hello));
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(90).unwrap(), UnitName.MAIN), 90, UnitState.RUNNING,
                                                4096, Duration.ofHours(3), none(), some(started), false, none(), none(), UnitKind.MVN,
                                                some(hello), 1));
        queue(2, 4096);

        heartbeat(LinkHarness.facts(0, 10L * 1024 * 1024, started.minusSeconds(2)));
        scheduler.tick().await();

        assertThat(harness.units.queue).as("newer facts, still sampled before the start").extracting(QueuedUnit::unitId)
                                       .containsExactly(2L);

        heartbeat(LinkHarness.facts(0, 10L * 1024 * 1024, started.plusSeconds(1)));
        scheduler.tick().await();

        assertThat(harness.units.queue).as("facts sampled after the start reflect the unit").isEmpty();
    }

    /// A heartbeat listing the running unit, with `facts`; returns once the link holds them.
    private void heartbeat(HostFactsDto facts) throws InterruptedException {
        harness.lastWorker.send(new Message.Heartbeat(List.of(new UnitRefDto(90, "main", 1)), facts));
        waitFor(() -> harness.link.facts().filter(latest -> latest.at().equals(facts.at())).isPresent(), "facts at " + facts.at());
    }

    /// The same two units on a host without a floor (0, off): both are granted, as before the floor existed.
    @Test
    void tick_memFloorOff_grantsBoth() throws Exception {
        up(LinkHarness.facts(0, 10L * 1024 * 1024));
        queue(1, 4096);
        queue(2, 4096);

        scheduler.tick().await();

        assertThat(harness.units.queue).isEmpty();
    }

    @Test
    void tick_skipsHost_notUp() {
        queue(1, 6144);

        scheduler.tick().await();

        assertThat(harness.units.grantCalls).isEmpty();
    }

    @Test
    void requestTick_skipsHostWithRoundInFlight_thenGrantsOnceReleased() throws Exception {
        up(0);
        harness.units.hangActive = true;
        var reads = harness.units.calls.size();
        scheduler.requestTick();
        waitFor(() -> harness.units.calls.size() > reads, "the round hung on its read");
        harness.units.hangActive = false;
        queue(1, 6144);
        scheduler.requestTick();
        Thread.sleep(300);

        assertThat(harness.units.grantCalls).as("no overlapping round while the hung one is in flight").isEmpty();
        waitFor(() -> {
            scheduler.requestTick();
            return harness.units.grantCalls.size() == 1;
        }, "a grant once the hung round was released (5 x tickBound)");
    }

    @Test
    void tick_keepsStarvationAcrossTicks() throws Exception {
        up(0);
        queue(1, 30000);
        queue(2, 1000);

        scheduler.tick().await();

        assertThat(scheduler.starvation(LinkHarness.HOST)).isEqualTo(Starvation.starvation(some(1L), some(T0)));
        assertThat(harness.units.queue).extracting(QueuedUnit::unitId).containsExactly(1L);
    }
}
