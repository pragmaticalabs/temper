package org.pragmatica.temper.controller.host;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.LostSettle;
import org.pragmatica.temper.store.api.HostLoss;
import org.pragmatica.temper.store.api.LostSettled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// m4-design §2.1–§2.5, §3 at the link: when the LOST flag is set, what it does while set, and the recovery order.
///
/// Probes:
/// - P15 (link half): settle only when LOST is declared, never on later ticks → `lost_settlesEveryTick_whileLost` red.
/// - P16: recovery reconciles before (or without) the settle → `recovery_settlesBeforeReconcile_storedLost` red.
/// - P8 (link half): [SshDiagnosis] never classifies → both `hostKey` rows red (LOST only after `hostLost`, 10 min).
/// - Q-M4-12: decide ignores the link being UP → the `deferred_upThroughStableUp` row red (LOST while UP); `isUp`
///   ignores the deferral → the same row red (granting while deferred); no decision on leaving UP →
///   `deferred_leavesUp_lostAtOnce` red.
/// - Settle outside the host-write slot → `recovery_flagClearsAfterUpWrite_queuedSettleIdle` red (a tick's mark and
///   settle land over the gated UP write).
/// - No timeout on the slot's store calls → `recovery_hungUpWrite_closesKeepsFlag` and
///   `settle_hungStore_nextTickSettlesAgain` red.
///
/// Rows that need the clock at a given point use [#MANUAL] (no scheduled tick) and call [HostLink#livenessTick]
/// themselves, so they wait only for lower bounds and never race a tick.
class HostLostLinkTest {
    /// No liveness tick within a test (the first comes after one period): the only settle is the recovery's.
    private static final LinkTiming NO_TICKS = manual(Duration.ofSeconds(60));

    private LinkHarness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.link.stop();
        }
    }

    /// No scheduled tick, no silence close (the fake worker sends no heartbeat), FAST's request timeout (the host-write
    /// slot's bound, 1.5 s).
    private static LinkTiming manual(Duration stableUp) {
        return LinkHarness.timing(LinkHarness.FAST.hello(), Duration.ofSeconds(30), timeSpan(1).hours(),
                                  LinkHarness.FAST.request(), stableUp);
    }

    /// R-M4-1: a link that never reaches UP is LOST `hostLost` after the controller start, not before.
    @Test
    void lost_linkDown_afterHostLostNotBefore() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofSeconds(1));
        var start = Instant.now();
        harness.link.start();
        harness.nextWorker();

        waitFor(() -> harness.link.lost().isPresent(), "LOST");
        waitFor(() -> !harness.hosts.losses.isEmpty(), "the mark written");
        var loss = harness.link.lost().unwrap();

        assertThat(loss.reason()).isEqualTo("link-down");
        assertThat(Duration.between(start, loss.at())).as("LOST not before hostLost").isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(harness.hosts.losses.getFirst().reason()).isEqualTo("link-down");
        assertThat(harness.link.isUp()).isFalse();
    }

    /// §2.3 "level, not edge": while LOST, every tick settles again, so a failed settle is retried and a late grant is
    /// caught; the LOST mark is written before each settle (the store settles only a host stored LOST).
    @Test
    void lost_settlesEveryTick_whileLost() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMillis(300));
        harness.units.failSettles.set(2);
        harness.link.start();

        waitFor(() -> harness.units.lostCalls.size() >= 5, "five settles while LOST");

        assertThat(harness.units.lostCalls).containsOnly(LinkHarness.HOST);
        assertThat(harness.hosts.losses.size()).as("the mark written before each settle").isGreaterThanOrEqualTo(5);
        assertThat(harness.hosts.losses.stream().distinct()).as("one declaration, one mark").hasSize(1);
    }

    /// §2.2, Q-M4-2: ssh's host-key line on the link session makes the host LOST at once, long before `hostLost`.
    @Test
    void lost_hostKeyAtOnce_linkSessionStderr() throws Exception {
        harness = new LinkHarness(LinkHarness.timing(timeSpan(60).seconds(), LinkHarness.FAST.silence(),
                                                     LinkHarness.FAST.livenessCheck(), LinkHarness.FAST.request(),
                                                     LinkHarness.FAST.stableUp()),
                                  Duration.ofMinutes(10));
        harness.link.start();
        harness.nextWorker();
        var process = harness.lastProcess;

        process.workerErr().write((SshDiagnosisTest.CHANGED + "\n").getBytes(StandardCharsets.UTF_8));
        process.workerErr().flush();

        waitFor(() -> harness.link.lost().isPresent(), "LOST at once");
        assertThat(harness.link.lost().unwrap().reason()).isEqualTo("host-key");
        waitFor(process::killed, "the session closed by the declaration (the Hello timeout is 60 s)");
        waitFor(() -> !harness.units.lostCalls.isEmpty(), "settled");
    }

    /// §2.2: the deploy session's captured stderr is classified the same way.
    @Test
    void lost_hostKeyAtOnce_deploySession() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMinutes(10));
        harness.installExit = 255;
        harness.installStderr = SshDiagnosisTest.UNKNOWN + "\n" + SshDiagnosisTest.FAILED + "\n";
        harness.link.start();
        harness.nextWorker().close();
        harness.nextWorker();

        assertThat(harness.spawns).anyMatch(argv -> argv.contains("install"));
        assertThat(harness.link.lost().isPresent()).as("decided before the connect after the deploy").isTrue();
        assertThat(harness.link.lost().unwrap().reason()).isEqualTo("host-key");
    }

    /// §2.5 (P16) and Q-M4-8: a host stored LOST stays LOST across a restart; its next handshake settles to completion,
    /// then reconciles (its first store read is `active`), then goes UP, which clears the flag and the stored mark.
    @Test
    void recovery_settlesBeforeReconcile_storedLost() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(stored);
        harness.link.start();
        var worker = harness.nextWorker();

        assertThat(harness.link.lost()).as("stored LOST kept at start").isEqualTo(some(stored));
        assertThat(harness.units.calls).as("nothing before the handshake").isEmpty();
        worker.send(hello(LinkHarness.VERSION, List.of()));

        waitFor(harness.link::isUp, "UP");
        assertThat(harness.units.calls).startsWith("settleLost", "active");
        assertThat(harness.link.lost().isEmpty()).isTrue();
        assertThat(harness.hosts.stored.isEmpty()).as("the mark cleared").isTrue();
        assertThat(harness.hosts.states.getLast()).isEqualTo("UP");
    }

    /// §2.5: a settle that fails ends the session before any reconcile; the next handshake recovers.
    @Test
    void recovery_failedSettle_closesBeforeReconcile() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        harness.hosts.stored = some(HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "host-key"));
        harness.units.failSettles.set(1);
        harness.link.start();
        var first = harness.nextWorker();
        var firstProcess = harness.lastProcess;
        first.send(hello(LinkHarness.VERSION, List.of()));

        waitFor(firstProcess::killed, "the first session closed");
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));

        waitFor(harness.link::isUp, "UP on the second session");
        assertThat(harness.units.calls.subList(0, 3)).containsExactly("settleLost", "settleLost", "active");
    }

    /// Q-M4-1: an UP of `stableUp` clears the clock at the link, so a healthy host is never LOST however long it runs:
    /// a tick after `stableUp` of UP clears it, and a tick past `hostLost` then finds nothing to decide.
    @Test
    void healthy_upForStableUp_neverLost() throws Exception {
        var hostLost = Duration.ofSeconds(1);
        harness = new LinkHarness(manual(Duration.ofMillis(200)), hostLost);
        var start = Instant.now();
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        waitFor(() -> harness.link.state() instanceof LinkState.Up, "UP");
        var up = (LinkState.Up) harness.link.state();

        sleepUntil(up.since().plus(Duration.ofMillis(200)));
        harness.link.livenessTick();
        sleepUntil(start.plus(hostLost).plusMillis(100));
        harness.link.livenessTick();

        assertThat(harness.link.lost().isEmpty()).as("UP past stableUp: the clock cleared").isTrue();
        assertThat(harness.link.isUp()).isTrue();
        assertThat(harness.hosts.losses).isEmpty();
    }

    /// Q-M4-12 (a), (c): the clock reaches `hostLost` while the link is UP but not yet for `stableUp`: no verdict, the
    /// host keeps its session and units, and takes no new grant ([HostLink#isUp] false) until the UP has lasted
    /// `stableUp`; then the clock clears, the scheduler is woken, and nothing was settled.
    /// The ticks are the row's own: the first past `hostLost` while UP (however late the UP came), the second after
    /// `stableUp` of UP.
    @Test
    void deferred_upThroughStableUp_noGrantUntilStable_noSettle() throws Exception {
        var hostLost = Duration.ofSeconds(1);
        var stableUp = Duration.ofSeconds(4);
        harness = new LinkHarness(manual(stableUp), hostLost);
        var wakes = new AtomicInteger();
        harness.wake.listen(wakes::incrementAndGet);
        var start = Instant.now();
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        var process = harness.lastProcess;
        waitFor(() -> harness.link.state() instanceof LinkState.Up, "UP");
        waitFor(() -> wakes.get() > 0, "the UP's own wake");
        var up = (LinkState.Up) harness.link.state();

        sleepUntil(start.plus(hostLost));
        assertThat(Duration.between(up.since(), Instant.now())).as("precondition: the tick comes before stableUp")
                                                               .isLessThan(stableUp);
        harness.link.livenessTick();
        assertThat(harness.link.state()).isSameAs(up);
        assertThat(harness.link.isUp()).as("deferred: no new grant").isFalse();
        assertThat(harness.link.grantState()).isEqualTo("DEFERRED");
        assertThat(harness.link.lost().isEmpty()).as("no verdict while UP").isTrue();
        var before = wakes.get();

        sleepUntil(up.since().plus(stableUp));
        harness.link.livenessTick();
        assertThat(harness.link.isUp()).as("UP for stableUp clears the clock").isTrue();
        assertThat(harness.link.grantState()).isEqualTo("UP");
        assertThat(wakes.get()).as("the scheduler woken by the clear").isEqualTo(before + 1);
        harness.link.livenessTick();
        assertThat(wakes.get()).as("once, not by every healthy tick").isEqualTo(before + 1);
        assertThat(process.killed()).as("the same session").isFalse();
        assertThat(harness.units.lostCalls).as("no settle, no requeue").isEmpty();
        assertThat(harness.hosts.losses).isEmpty();
    }

    /// Q-M4-12 (b): leaving UP during the deferral is LOST at once, the clock going on from where it was. No ticks
    /// here, so only the move to DOWN can declare it.
    @Test
    void deferred_leavesUp_lostAtOnce() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofSeconds(1));
        harness.link.start();
        var first = harness.nextWorker();
        first.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "UP");
        waitFor(() -> !harness.link.isUp(), "deferred after hostLost");
        assertThat(harness.link.lost().isEmpty()).isTrue();

        first.close();

        waitFor(() -> harness.link.lost().isPresent(), "LOST as the link left UP (no tick runs: nothing else declares it)");
        assertThat(harness.link.lost().unwrap().reason()).isEqualTo("link-down");
        waitFor(() -> !harness.units.lostCalls.isEmpty(), "settled");
    }

    /// A loss declared while the reconcile runs closes the session; the reconcile that ends during that close does
    /// not go UP and does not clear the flag: the loss is settled, never cleared unsettled. The close is held half-way
    /// (the session already not open, the link not yet DOWN) while the reconcile ends.
    @Test
    void lostDuringReconcile_notUp_flagKept() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofSeconds(1));
        var reconcileGate = Promise.<Unit>promise();
        harness.units.activeGate = reconcileGate;
        var closing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        harness.link.start();
        var worker = harness.nextWorker();
        harness.lastProcess.onKill = () -> hold(closing, release);
        worker.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(() -> harness.units.calls.contains("active"), "the reconcile reading");

        assertThat(closing.await(10, TimeUnit.SECONDS)).as("LOST declared, the session closing").isTrue();
        reconcileGate.succeed(unit());
        Thread.sleep(500);
        var lostMidClose = harness.link.lost();
        var stateMidClose = harness.link.state();
        release.countDown();

        assertThat(lostMidClose.isPresent()).as("the flag kept").isTrue();
        assertThat(stateMidClose).as("not UP in memory either").isNotInstanceOf(LinkState.Up.class);
        waitFor(() -> !harness.units.lostCalls.isEmpty(), "settled");
        assertThat(harness.link.lost().isPresent()).isTrue();
        assertThat(harness.hosts.states).as("never UP").doesNotContain("UP");
    }

    /// §2.5: recovery's UP write commits before the flag clears (until then no grant), and no tick's mark or settle
    /// runs while that write is in flight: a `hosts.lost` landing after the UP write would leave the store LOST while
    /// memory is healthy, and its settle would requeue fresh grants. The ticks' settle waits in the slot and, once its
    /// turn comes, finds the flag clear and settles nothing: a fresh grant on the recovered host is never requeued.
    @Test
    void recovery_flagClearsAfterUpWrite_queuedSettleIdle() throws Exception {
        harness = new LinkHarness(LinkHarness.timing(LinkHarness.FAST.hello(), Duration.ofSeconds(30), timeSpan(1).hours(),
                                                     timeSpan(60).seconds(), Duration.ofSeconds(60)),
                                  Duration.ofMinutes(10));
        harness.hosts.stored = some(HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down"));
        var upWrite = Promise.<Unit>promise();
        harness.hosts.recoveredGate = upWrite;
        harness.link.start();
        var worker = harness.nextWorker();
        worker.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(() -> harness.hosts.states.contains("UP"), "the UP write, held");
        var settles = harness.units.lostCalls.size();
        var marks = harness.hosts.losses.size();

        harness.link.livenessTick();
        harness.link.livenessTick();

        assertThat(harness.hosts.losses).as("no LOST mark written while the UP write is in flight").hasSize(marks);
        assertThat(harness.units.lostCalls).as("no settle while the UP write is in flight").hasSize(settles);
        assertThat(harness.link.isUp()).as("no grant before the UP write commits").isFalse();
        assertThat(harness.link.lost().isPresent()).isTrue();
        upWrite.succeed(unit());

        waitFor(harness.link::isUp, "UP once the write committed");
        worker.close();
        waitFor(() -> harness.hosts.states.contains("DOWN"), "the DOWN write, queued behind the ticks' settle");
        assertThat(harness.units.lostCalls).as("the queued settle found the flag clear").hasSize(settles);
        assertThat(harness.hosts.losses).hasSize(marks);
        assertThat(harness.link.lost().isEmpty()).isTrue();
    }

    /// §2.5: an UP write that hangs is failed by the slot's bound (`request`, 1.5 s): the session closes and the flag
    /// stays, as for a failed write; the slot is free again for the next recovery.
    @Test
    void recovery_hungUpWrite_closesKeepsFlag() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(stored);
        harness.hosts.recoveredGate = Promise.promise();
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        var first = harness.lastProcess;

        waitFor(first::killed, "the session closed after the UP write timed out");
        assertThat(harness.link.lost()).isEqualTo(some(stored));
        assertThat(harness.link.isUp()).isFalse();
    }

    /// §2.5: an UP write that fails closes the session and keeps the flag; the next handshake recovers again.
    @Test
    void recovery_failedUpWrite_closesKeepsFlag() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(stored);
        harness.hosts.recoveredGate = Causes.cause("store down").promise();
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        var first = harness.lastProcess;

        waitFor(first::killed, "the session closed after the failed UP write");
        assertThat(harness.link.lost()).isEqualTo(some(stored));
        assertThat(harness.link.isUp()).isFalse();
    }

    /// §2.2: after `Hello`, stderr is the worker's; a host-key text there never makes the host LOST.
    @Test
    void hostKeyText_afterHello_notLost() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMinutes(10));
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "UP");
        var session = harness.link.state().connection().unwrap();
        var judged = session.stderrLines();

        harness.lastProcess.workerErr().write((SshDiagnosisTest.CHANGED + "\n").getBytes(StandardCharsets.UTF_8));
        harness.lastProcess.workerErr().flush();
        waitFor(() -> session.stderrLines() > judged, "the line judged");

        assertThat(session.hostKeyFailure().isResolved()).as("not classified after Hello").isFalse();
        assertThat(harness.link.lost().isEmpty()).isTrue();
        assertThat(harness.link.isUp()).isTrue();
    }

    /// §2.2: a deploy that fails with the remote command's exit code (not ssh's 255) is no host-key failure, whatever
    /// it printed.
    @Test
    void hostKeyText_deployExitNot255_notLost() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMinutes(10));
        harness.installExit = 1;
        harness.installStderr = SshDiagnosisTest.FAILED + "\n";
        harness.link.start();
        harness.nextWorker().close();
        harness.nextWorker();

        assertThat(harness.spawns).as("the deploy ran before this connect").anyMatch(argv -> argv.contains("install"));
        assertThat(harness.link.lost().isEmpty()).as("its verdict lands before the connect").isTrue();
    }

    /// §2.2: a session the link has moved past (it holds a newer one) reports nothing, even before its `Hello`.
    @Test
    void hostKeyText_staleSession_ignored() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMinutes(10));
        harness.link.start();
        var first = harness.nextWorker();
        var stale = harness.lastProcess;
        stale.keepStderr = true;
        waitFor(() -> harness.link.state().connection().isPresent(), "the first session held");
        var staleSession = harness.link.state().connection().unwrap();
        first.close();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "UP on the second session");

        stale.workerErr().write((SshDiagnosisTest.CHANGED + "\n").getBytes(StandardCharsets.UTF_8));
        stale.workerErr().flush();
        waitFor(() -> staleSession.hostKeyFailure().isResolved(), "the stale session's failure classified");

        // The link's reaction is a callback of that promise; it would declare within milliseconds, so a 2 s window
        // after the positive control above is wide.
        var deadline = Instant.now().plusSeconds(2);
        while (Instant.now().isBefore(deadline)) {
            assertThat(harness.link.lost().isEmpty()).isTrue();
            Thread.sleep(20);
        }
    }

    /// Q-M4-8: a stored mark that cannot be read is read again (backoff) before anything is connected or written to
    /// `hosts` (a state write would erase it); the host then starts LOST with it.
    @Test
    void start_unreadableMark_retriedNeverErased() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "host-key");
        harness.hosts.stored = some(stored);
        harness.hosts.failLoss.set(2);
        harness.link.start();
        harness.nextWorker();

        assertThat(harness.hosts.lossReads.get()).isEqualTo(3);
        assertThat(harness.link.lost()).isEqualTo(some(stored));
        assertThat(harness.hosts.stored).as("the mark not erased").isEqualTo(some(stored));
        assertThat(harness.hosts.states).as("only the mark written").allMatch("LOST"::equals);
    }

    /// A stopped link's ticks end: no settle after `stop`.
    @Test
    void stop_endsTicks() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMillis(300));
        harness.link.start();
        waitFor(() -> harness.units.lostCalls.size() >= 2, "settles while LOST");

        var running = harness.units.lostCalls.size();
        Thread.sleep(500);
        assertThat(harness.units.lostCalls.size()).as("positive control: the window sees ticks of a running link")
                                                  .isGreaterThan(running);

        harness.link.stop();
        Thread.sleep(300);
        var settles = harness.units.lostCalls.size();
        Thread.sleep(500);

        assertThat(harness.units.lostCalls).as("the same window after stop").hasSize(settles);
    }

    /// §2.3: one settle at a time; ticks during a slow settle are skipped, not queued. Manual ticks; the slot is FIFO,
    /// so the settle of the tick after the release runs after any queued backlog and anchors the count.
    @Test
    void settle_overlappingTicksSkipped() throws Exception {
        harness = new LinkHarness(LinkHarness.timing(LinkHarness.FAST.hello(), Duration.ofSeconds(30), timeSpan(1).hours(),
                                                     timeSpan(60).seconds(), Duration.ofSeconds(60)),
                                  Duration.ofMinutes(10));
        harness.hosts.stored = some(HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down"));
        var slow = Promise.<Unit>promise();
        harness.units.settleGate = slow;
        harness.link.start();
        harness.link.livenessTick();
        waitFor(() -> !harness.units.lostCalls.isEmpty(), "the first settle");
        for (var tick = 0; tick < 5; tick++) {
            harness.link.livenessTick();
        }

        slow.succeed(unit());
        waitFor(() -> {
            harness.link.livenessTick();
            return harness.units.lostCalls.size() >= 2;
        }, "the settle of a tick after the release");

        assertThat(harness.units.lostCalls.size()).as("no backlog of the 5 skipped ticks").isLessThanOrEqualTo(3);
    }

    /// §2.5, the late mark (verifier p6late): a tick's `hosts.lost` reaches the store only after its bound failed it,
    /// recovery has written UP and the flag has cleared. The store refuses it (the V12 guard, modelled by the fake):
    /// the store stays recovered, nothing settles after the UP, and the link stays UP.
    @Test
    void recovery_lateMarkCommit_refused_storeStaysUp_noSettle() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(stored);
        var late = Promise.<Unit>promise();
        harness.link.start();
        var worker = harness.nextWorker();
        waitFor(() -> harness.hosts.losses.size() == 1, "the spawn's mark write");
        harness.hosts.lostGate = late;
        harness.link.livenessTick();
        waitFor(() -> harness.hosts.losses.size() == 2, "the tick's mark write (the settle's first step), hung");
        harness.hosts.lostGate = Promise.unitPromise();

        worker.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "UP after the hung mark write timed out");
        var settles = harness.units.lostCalls.size();
        late.succeed(unit());
        waitFor(() -> harness.hosts.staleMarks.get() == 1, "the late mark refused");

        assertThat(harness.hosts.stored).as("the store stays recovered").isEqualTo(none());
        assertThat(harness.hosts.cleared).isEqualTo(some(stored.at()));
        assertThat(harness.units.lostCalls).as("no settle after the UP").hasSize(settles);
        assertThat(harness.units.requeued).isEmpty();
        assertThat(harness.link.lost()).isEqualTo(none());
        assertThat(harness.link.isUp()).isTrue();
    }

    /// §2.5, the late answer: a tick's mark write commits but answers only after its bound failed it and recovery
    /// completed. The settle is chained to the bounded write, so it never runs (outside the slot, after the UP).
    /// The anchor is the late write's own facts write; the settle would be the next step of the same chain.
    @Test
    void recovery_lateMarkAnswer_noSettleAfterUp() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(stored);
        var late = Promise.<Unit>promise();
        harness.link.start();
        var worker = harness.nextWorker();
        waitFor(() -> harness.hosts.losses.size() == 1, "the spawn's mark write");
        harness.hosts.lostAnswerGate = late;
        harness.link.livenessTick();
        waitFor(() -> harness.hosts.losses.size() == 2, "the tick's mark write (the settle's first step), its answer hung");
        harness.hosts.lostAnswerGate = Promise.unitPromise();

        worker.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "UP after the hung mark write timed out");
        var settles = harness.units.lostCalls.size();
        var facts = harness.hosts.facts.size();
        late.succeed(unit());
        waitFor(() -> harness.hosts.facts.size() > facts, "the late write's chain ran on");
        Thread.sleep(300);

        assertThat(harness.units.lostCalls).as("no settle after the UP").hasSize(settles);
        assertThat(harness.hosts.stored).isEqualTo(none());
        assertThat(harness.link.isUp()).isTrue();
    }

    /// §2.5, the late UP: recovery's UP write commits only after its bound failed it (session closed, flag kept). The
    /// store recovered, so the next mark write is refused, the flag follows the store without a settle, and the next
    /// handshake is a healthy one: never stuck LOST in memory against a recovered store.
    @Test
    void recovery_lateUpCommit_flagFollowsStore_nextHandshakeUp() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(stored);
        var lateUp = Promise.<Unit>promise();
        harness.hosts.recoveredGate = lateUp;
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        var first = harness.lastProcess;
        waitFor(first::killed, "the session closed after the UP write timed out");
        assertThat(harness.link.lost()).isEqualTo(some(stored));
        var settles = harness.units.lostCalls.size();

        harness.hosts.recoveredGate = Promise.unitPromise();
        lateUp.succeed(unit());
        waitFor(() -> harness.hosts.stored.isEmpty(), "the late UP committed");
        harness.link.livenessTick();

        waitFor(() -> harness.link.lost().isEmpty(), "the flag follows the store");
        assertThat(harness.hosts.staleMarks.get()).isEqualTo(1);
        assertThat(harness.units.lostCalls).as("no settle on a refused mark").hasSize(settles);
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "a healthy UP");
        assertThat(harness.hosts.stored).isEqualTo(none());
    }

    /// §2.5: the flag follows a stale refusal only while it is still the refused mark. A tick's mark write of M1
    /// reaches the store late, after recovery from M1 and a new loss M2 (declared and stored): the refusal of M1 must
    /// not clear M2, or memory is healthy while the store stays LOST (a restart would requeue running units).
    @Test
    void staleRefusal_olderMark_newerLossKept() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofSeconds(2));
        var first = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(first);
        var late = Promise.<Unit>promise();
        harness.link.start();
        var worker = harness.nextWorker();
        waitFor(() -> harness.hosts.losses.size() == 1, "the spawn's mark write");
        harness.hosts.lostGate = late;
        harness.link.livenessTick();
        waitFor(() -> harness.hosts.losses.size() == 2, "the tick's mark write of M1, hung");
        harness.hosts.lostGate = Promise.unitPromise();
        worker.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "recovered from M1");
        // The flag clears when the UP is committed, after isUp; ticks while it is set only settle (no clock, no M2).
        waitFor(() -> harness.link.lost().isEmpty() && harness.hosts.stored.isEmpty(), "the UP from M1 committed");

        worker.close();
        waitFor(() -> !harness.link.isUp(), "the link down");
        harness.link.livenessTick();
        sleepUntil(Instant.now().plusSeconds(2));
        // Ticks keep coming in production; one tick at the 2 s edge is not a test of the declaration.
        waitFor(() -> harness.link.lost().isPresent()
                      ? harness.hosts.stored.isPresent()
                      : harness.link.livenessTick() == null,
                "M2 declared and stored");
        var second = harness.link.lost().unwrap();
        late.succeed(unit());
        waitFor(() -> harness.hosts.staleMarks.get() == 1, "the late M1 refused");
        Thread.sleep(300);

        assertThat(second).isNotEqualTo(first);
        assertThat(harness.link.lost()).as("M2 kept in memory").isEqualTo(some(second));
        assertThat(harness.hosts.stored).isEqualTo(some(second));
    }

    /// §2.5: only a stale refusal clears the flag; a mark write that fails because the store is down keeps it, and the
    /// next tick settles.
    @Test
    void lost_transientMarkFailure_flagKept() throws Exception {
        harness = new LinkHarness(NO_TICKS, Duration.ofMinutes(10));
        var stored = HostLoss.hostLoss(Instant.parse("2026-10-26T10:00:00Z"), "link-down");
        harness.hosts.stored = some(stored);
        harness.link.start();
        harness.nextWorker();
        waitFor(() -> harness.hosts.losses.size() == 1, "the spawn's mark write");
        harness.hosts.failLost.set(1);
        harness.link.livenessTick();
        waitFor(() -> harness.hosts.failLost.get() == 0, "the tick's mark write failed");

        waitFor(() -> {
            harness.link.livenessTick();
            return !harness.units.lostCalls.isEmpty();
        }, "a later tick settles: the flag kept");
        assertThat(harness.link.lost()).isEqualTo(some(stored));
    }

    /// §2.3, §2.5: a settle whose store hangs is failed by the slot's bound (`request`, 1.5 s), so the next tick
    /// settles again instead of the host's settles stalling for good.
    @Test
    void settle_hungStore_nextTickSettlesAgain() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMillis(300));
        harness.units.settleGate = Promise.promise();
        harness.link.start();

        waitFor(() -> harness.units.lostCalls.size() >= 2, "a second settle after the first timed out");
    }

    /// §2.3: settled units wake the scheduler (a requeued unit may run elsewhere).
    @Test
    void settle_settledUnits_wakeScheduler() throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, Duration.ofMillis(300));
        harness.units.settleAnswer = List.of(LostSettled.lostSettled(UnitRef.unitRef(JobId.jobId(7).unwrap(), UnitName.MAIN),
                                                                     1, LostSettle.Outcome.REQUEUED, none()));
        var wakes = new AtomicInteger();
        harness.wake.listen(wakes::incrementAndGet);
        harness.link.start();

        waitFor(() -> wakes.get() > 0, "the scheduler woken after a settle");
        assertThat(harness.link.isUp()).isFalse();
    }

    private static void hold(CountDownLatch closing, CountDownLatch release) {
        closing.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// A lower bound only: the row then ticks itself, so a late wake-up changes no outcome.
    private static void sleepUntil(Instant at) throws InterruptedException {
        var left = Duration.between(Instant.now(), at);
        if (left.isPositive()) {
            Thread.sleep(left.toMillis() + 1);
        }
    }
}
