package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.store.api.ActiveUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;
import static org.assertj.core.api.Assertions.assertThat;


/// #103: a host past its `retireAt` with no active unit is not dialed: its link stops for good, whether the instant
/// passed before the start or while the link runs. One still holding units keeps its link until they are gone
/// (Q-M4-10). Mutation: drop the retirement check from the connect path → [#retiredBeforeStart_isNeverDialed] red
/// (one dial before the first tick); from the liveness tick → [#retireAtPassingWhileUp_shutsTheSessionDown_andNeverRedials]
/// red; from both → [#retireAtPassingWhileTheHostFlaps_stopsTheRedialing] red too.
class RetiredHostLinkTest {
    /// Several liveness ticks (100 ms) and reconnect backoffs (≤ 200 ms) of [LinkHarness#FAST].
    private static final long QUIET_MS = 1000;

    private LinkHarness harness;
    private final AtomicBoolean flapping = new AtomicBoolean(true);

    @AfterEach
    void tearDown() {
        flapping.set(false);
        harness.link.stop();
    }

    private boolean stopped() {
        return harness.link.state() instanceof LinkState.Stopped;
    }

    /// Waits for the link to stop, lets any spawn already under way land, then proves no further spawn within
    /// [#QUIET_MS]; answers the spawn count at the stop.
    private int noDialAfterTheStop() throws InterruptedException {
        waitFor(this::stopped, "link stopped");
        Thread.sleep(300);
        var atStop = harness.spawns.size();

        Thread.sleep(QUIET_MS);
        assertThat(harness.spawns).as("spawns after the retired link stopped").hasSize(atStop);

        return atStop;
    }

    @Test
    void retiredBeforeStart_isNeverDialed() throws Exception {
        harness = LinkHarness.retiring(Instant.now().minus(Duration.ofMinutes(1)));
        harness.link.start();
        noDialAfterTheStop();
        assertThat(harness.spawns).isEmpty();
        assertThat(harness.link.state().wire()).isEqualTo("DOWN");
        assertThat(harness.units.calls).contains("active");
    }

    /// The 2026-11-07 teardown: the host's address is gone, every session ends before `Hello`, and the link redials
    /// with backoff. Once `retireAt` passes, it stops redialing.
    @Test
    void retireAtPassingWhileTheHostFlaps_stopsTheRedialing() throws Exception {
        var retireAt = Instant.now().plus(Duration.ofHours(1));

        harness = LinkHarness.retiring(retireAt);
        // Mechanism: retireAt passes by the controlled clock after three dials, so no assertion reads wall time (the old 1.5 s real-time window starved under load).
        harness.controlledNow.set(retireAt.minusSeconds(1));
        Thread.ofVirtual().start(this::closeEverySession);
        harness.link.start();
        waitFor(() -> harness.spawns.size() > 2, 30, "redialed before retireAt");
        harness.controlledNow.set(retireAt);

        assertThat(noDialAfterTheStop()).as("dialed before retireAt").isGreaterThan(2);
    }

    /// The connect path reads the link's clock: the controlled clock says retired while the wall clock says an hour
    /// to go. Mutation: `connect` reads `Instant.now()` → the host is dialed (red).
    @Test
    void connect_judgesRetirementByTheLinksClock_notTheWallClock() throws Exception {
        var retireAt = Instant.now().plus(Duration.ofHours(1));

        harness = LinkHarness.retiring(retireAt);
        harness.controlledNow.set(retireAt.plusSeconds(1));
        harness.link.start();
        noDialAfterTheStop();
        assertThat(harness.spawns).isEmpty();
    }

    private void closeEverySession() {
        try {
            while (flapping.get()) {
                var worker = harness.workers.poll(100, TimeUnit.MILLISECONDS);

                if (worker != null) {
                    worker.close();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void retireAtPassingWhileUp_shutsTheSessionDown_andNeverRedials() throws Exception {
        var retireAt = Instant.now().plus(Duration.ofHours(1));

        harness = LinkHarness.retiring(retireAt);
        harness.controlledNow.set(retireAt.minusSeconds(1));
        harness.link.start();
        var worker = harness.nextWorker();

        worker.send(hello(LinkHarness.VERSION, List.of()));
        worker.expect(Message.Welcome.class, 5);
        waitFor(harness.link::isUp, "link UP");
        harness.controlledNow.set(retireAt);
        worker.expect(Message.Shutdown.class, 10);
        assertThat(noDialAfterTheStop()).isEqualTo(1);
    }

    /// A retired host whose active units cannot be read keeps its link (here: it is dialed): stopping it on a guess
    /// could strand units. Mutation: stop, or do nothing, on a failed read → red.
    @Test
    void retired_activeUnitsUnreadable_keepsItsLink() throws Exception {
        harness = LinkHarness.retiring(Instant.now().minus(Duration.ofMinutes(1)));
        harness.units.failActive = true;

        harness.link.start();

        harness.nextWorker();
        assertThat(stopped()).isFalse();
    }

    /// Q-M4-10: the units a retired host still runs are what its loss would cost, so it keeps its link (here: it is
    /// dialed) until they are gone; then the next tick stops it.
    @Test
    void retiredWithAnActiveUnit_keepsItsLink_untilItHasNone() throws Exception {
        harness = LinkHarness.retiring(Instant.now().minus(Duration.ofMinutes(1)));
        var units = harness.units.activeOn(LinkHarness.HOST);

        units.add(ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(7).unwrap(),
                                                        UnitName.MAIN),
                                        7,
                                        UnitState.RUNNING,
                                        6144,
                                        Duration.ofMinutes(60),
                                        none(),
                                        some(Instant.now()),
                                        false,
                                        none(),
                                        none(),
                                        UnitKind.MVN,
                                        some(Instant.now()),
                                        1));
        harness.link.start();
        harness.nextWorker();
        Thread.sleep(500);
        assertThat(stopped()).as("stopped while a unit is active").isFalse();
        units.clear();
        noDialAfterTheStop();
    }
}
