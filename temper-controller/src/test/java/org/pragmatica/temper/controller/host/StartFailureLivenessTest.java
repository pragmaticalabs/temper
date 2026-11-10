package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.Granted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// Verification V2-1 (main-session ruling): a `UnitStarted` that cannot be recorded never resets the session. The
/// worker posts a re-sent start right before the exit report (`Collect`, `Reattach`) and builds the report only after
/// the start is posted, so the report trails the start by its parse time; a start failing in between used to reset
/// the session, lose the report, and repeat on every reconnect (livelock). Every scenario here fails the start at
/// once, with nothing holding it back until the report is in. Ruling R-13 extends this to the direct (non-hold)
/// `StartUnit` reply and puts the retry schedule in `LinkTiming`, which [LinkHarness#FAST] runs at 1 ms.
class StartFailureLivenessTest {
    private static final long JOB = 12;
    private static final UnitRef REF = UnitRef.unitRef(JobId.jobId(JOB).unwrap(), UnitName.MAIN);
    private static final Instant GRANTED_AT = Instant.parse("2026-10-14T08:00:00Z");
    private static final UnitAttempt ATTEMPT = UnitAttempt.unitAttempt(REF, 1);
    private static final int START_ATTEMPTS = LinkHarness.FAST.startRetry().size() + 1;
    private static final List<TestcaseDto> ROWS = List.of(new TestcaseDto("org.x.T", "t", "passed", 0.1, "main", false));
    private LinkHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = upWith(LinkHarness.FAST);
    }

    /// The unit is active on the host and its container runs, so the handshake's reconcile reattaches it and knows
    /// its attempt ([#GRANTED_AT]), which every `UnitStarted` of it here belongs to.
    private static LinkHarness upWith(LinkTiming timing) throws Exception {
        var up = new LinkHarness(timing);
        up.units.activeOn(LinkHarness.HOST)
                .add(ActiveUnit.activeUnit(REF, JOB, UnitState.RUNNING, 6144, Duration.ofMinutes(60), none(),
                                           some(Instant.now()), true, none(), none(), UnitKind.MVN, some(GRANTED_AT), 1));
        up.link.start();
        up.nextWorker().send(hello(LinkHarness.VERSION, List.of(running())));
        waitFor(up.link::isUp, "link UP");
        return up;
    }

    private static DiskUnitDto running() {
        return DiskUnitDto.diskUnitDto(JOB, "main", true, none(), ContainerStatus.RUNNING, none(), none(), none(), none());
    }

    /// [LinkHarness#FAST] with another start retry schedule and attempt timeout.
    private void restartWith(List<TimeSpan> startRetry, TimeSpan startAttemptTimeout) throws Exception {
        var fast = LinkHarness.FAST;
        harness.link.stop();
        harness = upWith(LinkTiming.linkTiming(fast.hello(), fast.silence(), fast.livenessCheck(), fast.request(),
                                               fast.prepare(), fast.start(), fast.deploy(), fast.backoff(),
                                               fast.stableUp(), startRetry, startAttemptTimeout, fast.unrecordedAfter()));
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    /// The fake store answers at once and the report follows the start back to back.
    @RepeatedTest(20)
    void persistentStartFailure_reportRightBehind_completesInTheFirstSession() throws Exception {
        harness.units.startGate = Causes.cause("start row rejected (persistent)").promise();

        startThenReport(0);

        assertCompletedInTheFirstSession();
    }

    /// The production order: a 3 ms store round trip, and the report ~30 ms behind the start (the worker's
    /// surefire parse).
    @RepeatedTest(20)
    void persistentStartFailure_reportAfterTheWorkersParse_completesInTheFirstSession() throws Exception {
        harness.units.delayMs = 3;
        harness.units.startGate = Causes.cause("start row rejected (persistent)").promise();

        startThenReport(30);

        assertCompletedInTheFirstSession();
    }

    /// A start write that fails once is retried, recorded, and the completion follows it.
    @Test
    void transientStartFailure_recordedOnRetry_thenCompleted() throws Exception {
        harness.units.failStarts.set(1);

        startThenReport(0);

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(harness.units.started).as("start write attempts").hasSize(2);
        assertThat(harness.units.recordedStarts).containsExactly(REF);
        assertThat(harness.units.completions).hasSize(1);
        assertThat(harness.workers).as("no reconnect").isEmpty();
    }

    /// Ruling R-13: the production schedule is 200 ms, 1 s, 2 s, 5 s, so 5 attempts over about 8 s.
    @Test
    void startRetry_default_isTheR13Schedule() {
        assertThat(LinkTiming.DEFAULT.startRetry()).containsExactly(timeSpan(200).millis(),
                                                                    timeSpan(1).seconds(),
                                                                    timeSpan(2).seconds(),
                                                                    timeSpan(5).seconds());
    }

    /// Ruling R-13 on the direct (non-hold) path: a `StartUnit` reply whose start cannot be recorded is retried and
    /// dropped like an unsolicited start, never a reset, and the report right behind it completes the unit.
    @Test
    void directStart_persistentStartFailure_noReset_completesOnItsReport() throws Exception {
        harness.units.startGate = Causes.cause("start row rejected (persistent)").promise();
        harness.responder = StartFailureLivenessTest::startsDirectlyThenReports;

        harness.link.launch(directUnit()).await();

        assertCompletedInTheFirstSession();
    }

    /// Verification F2: the pauses are applied in the order `LinkTiming.startRetry` lists them. A short pause, then a
    /// long one: applied reversed, the long gap would come first.
    @Test
    void startRetry_appliesThePausesInOrder() throws Exception {
        restartWith(List.of(timeSpan(1).millis(), timeSpan(400).millis()), LinkHarness.FAST.startAttemptTimeout());
        harness.units.failStarts.set(2);

        harness.lastWorker.send(started(none()));

        waitFor(() -> harness.units.recordedStarts.contains(REF), "start recorded on the third attempt");
        var at = harness.units.startNanos;
        assertThat(at).as("start write attempts").hasSize(3);
        assertThat(Duration.ofNanos(at.get(1) - at.get(0))).as("gap after the first pause (1 ms)")
                                                           .isLessThan(Duration.ofMillis(200));
        assertThat(Duration.ofNanos(at.get(2) - at.get(1))).as("gap after the second pause (400 ms)")
                                                           .isGreaterThanOrEqualTo(Duration.ofMillis(400));
    }

    /// Ruling R-13, per-attempt timeout (verification F3): a store that never answers fails each attempt after
    /// `startAttemptTimeout`, so the start is dropped within attempts × timeout + pauses and the report behind it
    /// completes the unit in the same session.
    @Test
    void hangingStore_eachAttemptTimesOut_startDroppedWithinTheBound_completesOnItsReport() throws Exception {
        restartWith(Collections.nCopies(4, timeSpan(1).millis()), timeSpan(100).millis());
        harness.units.startGate = Promise.promise();
        var sent = System.nanoTime();

        startThenReport(0);

        assertCompletedInTheFirstSession();
        assertThat(Duration.ofNanos(System.nanoTime() - sent)).as("5 × 100 ms + 4 × 1 ms, with slack")
                                                              .isLessThan(Duration.ofSeconds(2));
    }

    /// Verification F1: a direct start is registered before `StartUnit` is sent and removed on every terminal path,
    /// here a `StartUnit` the worker never answers (the request times out, the session resets).
    @Test
    void directStart_neverAnswered_leavesNoStartInFlight() throws Exception {
        harness.responder = StartFailureLivenessTest::preparesThenIgnoresTheStart;

        var launched = harness.link.launch(directUnit());

        harness.lastWorker.expect(Message.StartUnit.class, 5);
        assertThat(harness.link.pendingStarts()).as("registered while StartUnit is in flight").isEqualTo(1);
        launched.await();
        assertThat(harness.link.pendingStarts()).isZero();
        assertThat(harness.units.started).isEmpty();
    }

    /// Verification F-a: the start is registered BEFORE `StartUnit` is written, not just before its reply is read. The
    /// count is taken on the sending thread as the frame is written, so no scheduling can let a registration made
    /// right after the send pass.
    @Test
    void startUnit_isWrittenOnlyOnceItsStartIsRegistered() throws Exception {
        var atStartUnit = new CopyOnWriteArrayList<Integer>();
        harness.onSend = message -> countStartsAt(message, atStartUnit);
        harness.responder = StartFailureLivenessTest::startsDirectly;

        harness.link.launch(directUnit()).await();

        assertThat(atStartUnit).as("starts registered when StartUnit was written").containsExactly(1);
        assertThat(harness.units.recordedStarts).containsExactly(REF);
    }

    /// The recorded start leaves the in-flight set as a chain step of the launch, not some time after it. The start
    /// write resolves late, on a timer thread, so the start's chain is unresolved when its removal is attached; the
    /// launch's own continuation reads the count, which an `on*` callback (run after the dependents) would still see.
    /// No seam slows the removal itself, so the race is made near-certain by repetition, not certain: against the old
    /// callback a single repetition failed in 9 of 10 runs of the one-carrier fork (it is named `directStart*`) and
    /// rarely in a cold multi-carrier JVM; ten repetitions failed 7 to 10 times in every run of both forks.
    @RepeatedTest(10)
    void directStart_recorded_leavesNoStartInFlight_whenTheLaunchResolves() {
        harness.units.startGate = Promise.promise(timeSpan(100).millis(), Result::unitResult);
        harness.responder = StartFailureLivenessTest::startsDirectly;

        var inFlight = harness.link.launch(directUnit())
                                   .map(_ -> harness.link.pendingStarts());

        assertThat(inFlight.await()
                           .or(-1)).isZero();
        assertThat(harness.units.recordedStarts).containsExactly(REF);
    }

    /// The same when the link drops before the reply: the pending request fails and the start is removed.
    @Test
    void directStart_linkDropsBeforeTheReply_leavesNoStartInFlight() throws Exception {
        harness.responder = this::preparesThenDropsTheLink;

        harness.link.launch(directUnit()).await();

        assertThat(harness.link.pendingStarts()).isZero();
        assertThat(harness.units.started).isEmpty();
    }

    /// Ruling R-13, amended: the store stays down past the start's retries and through several attempts of the
    /// UNKNOWN mark too; the mark keeps retrying in the background, without resetting the session, and lands once the
    /// store is back while the unit still runs.
    @Test
    void persistentOutage_throughTheDropWindow_unitEndsUpUnknownOnceTheStoreIsBack() throws Exception {
        harness.units.startGate = Causes.cause("start row rejected (persistent)").promise();
        harness.units.failUnknowns.set(6);

        harness.lastWorker.send(started(none()));

        waitFor(() -> harness.units.markedUnknown.contains(REF), "the mark landed once the store is back");
        assertThat(harness.units.unknownAttempts).as("mark attempts: 6 refused, then the landing one").hasSize(7);
        assertThat(harness.workers).as("no reconnect").isEmpty();
        waitFor(() -> harness.link.pendingUnknowns() == 0, "no UNKNOWN mark left in flight");
    }

    /// Verification F4: the mark is scoped to the attempt the `UnitStarted` belongs to, every time it is tried.
    @Test
    void droppedStart_marksTheAttemptItBelongsTo() throws Exception {
        harness.units.startGate = Causes.cause("start row rejected (persistent)").promise();
        harness.units.failUnknowns.set(2);

        harness.lastWorker.send(started(none()));

        waitFor(() -> harness.units.markedUnknown.contains(REF), "the mark landed");
        assertThat(harness.units.unknownGrants).hasSize(3).containsOnly(1);
    }

    /// A start that is recorded is never marked UNKNOWN.
    @Test
    void recordedStart_isNeverMarkedUnknown() throws Exception {
        startThenReport(0);

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(harness.units.recordedStarts).containsExactly(REF);
        assertThat(harness.units.unknownAttempts).isEmpty();
    }

    /// m1-design §14 (maintainer ruling): a start write still in flight is known unrecorded once it has been in flight
    /// `unrecordedAfter` (here 300 ms), not before, and no longer once it lands.
    @Test
    void slowStartWrite_knownUnrecordedAfterUnrecordedAfter_untilItLands() throws Exception {
        var gate = Promise.<Unit> promise();
        harness.units.startGate = gate;

        harness.lastWorker.send(started(none()));

        waitFor(() -> harness.units.started.size() == 1, "the first start attempt in flight");
        var inFlight = System.nanoTime();
        assertThat(harness.link.unrecordedStarts()).as("in flight, not failed, under the threshold").isEmpty();
        waitFor(() -> harness.link.unrecordedStarts().contains(ATTEMPT), "known unrecorded once in flight 300 ms");
        assertThat(Duration.ofNanos(System.nanoTime() - inFlight)).isGreaterThanOrEqualTo(Duration.ofMillis(250));
        gate.succeed(Unit.unit());
        waitFor(() -> harness.units.recordedStarts.contains(REF), "start recorded");
        waitFor(() -> harness.link.unrecordedStarts().isEmpty(), "no longer known unrecorded");
        assertThat(harness.units.started).as("one attempt").hasSize(1);
    }

    /// A healthy start lands before `unrecordedAfter`: it is never known unrecorded, also after the threshold passes.
    @Test
    void healthyStart_isNeverKnownUnrecorded() throws Exception {
        harness.lastWorker.send(started(none()));

        waitFor(() -> harness.units.recordedStarts.contains(REF), "start recorded");
        assertThat(harness.link.unrecordedStarts()).isEmpty();
        Thread.sleep(LinkHarness.FAST.unrecordedAfter().duration().toMillis() + 200);
        assertThat(harness.link.unrecordedStarts()).isEmpty();
    }

    /// The production threshold is the maintainer's 1 s.
    @Test
    void unrecordedAfter_default_isOneSecond() {
        assertThat(LinkTiming.DEFAULT.unrecordedAfter()).isEqualTo(timeSpan(1).seconds());
    }

    /// m1-design §14: the unit is known unrecorded right after the FIRST failed attempt, while the retry is still
    /// pending, and no longer once a retry records the start (a late start shows RUNNING).
    @Test
    void firstAttemptFails_knownUnrecordedAtOnce_untilTheRetryRecordsTheStart() throws Exception {
        restartWith(List.of(timeSpan(1).seconds()), LinkHarness.FAST.startAttemptTimeout());
        harness.units.failStarts.set(1);

        harness.lastWorker.send(started(none()));

        waitFor(() -> harness.link.unrecordedStarts().contains(ATTEMPT), "known unrecorded after the first failed attempt");
        assertThat(Duration.ofNanos(System.nanoTime() - harness.units.startNanos.getFirst()))
            .as("at once, well before unrecordedAfter (300 ms)").isLessThan(Duration.ofMillis(250));
        assertThat(harness.units.started).as("still before the retry").hasSize(1);
        waitFor(() -> harness.units.recordedStarts.contains(REF), "start recorded by the retry");
        waitFor(() -> harness.link.unrecordedStarts().isEmpty(), "no longer known unrecorded");
    }

    /// m1-design §14: a dropped start stays known unrecorded while its UNKNOWN mark is refused, and after the mark
    /// lands too, until the unit completes.
    @Test
    void droppedStart_knownUnrecordedUntilTheUnitCompletes_markLandedOrNot() throws Exception {
        harness.units.startGate = Causes.cause("start row rejected (persistent)").promise();
        harness.units.failUnknowns.set(Integer.MAX_VALUE);

        harness.lastWorker.send(started(none()));

        waitFor(() -> harness.units.unknownAttempts.size() > 1, "the UNKNOWN mark refused");
        assertThat(harness.link.unrecordedStarts()).containsExactly(ATTEMPT);
        harness.units.failUnknowns.set(0);
        waitFor(() -> harness.units.markedUnknown.contains(REF), "the mark landed");
        waitFor(() -> harness.link.pendingUnknowns() == 0, "the mark loop ended");
        assertThat(harness.link.unrecordedStarts()).as("the mark landed, the unit still runs").containsExactly(ATTEMPT);
        harness.lastWorker.send(exited());
        harness.lastWorker.send(report());
        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        waitFor(() -> harness.link.unrecordedStarts().isEmpty(), "no longer known unrecorded once completed");
    }

    /// Verification F3: the mark loop ends unlanded on a terminal cause; the unit still runs, so it stays known
    /// unrecorded (and shows UNKNOWN) until it completes.
    @Test
    void markEndsOnATerminalCause_unitStaysKnownUnrecorded_untilItCompletes() throws Exception {
        harness.units.startGate = Causes.cause("start row rejected (persistent)").promise();
        harness.units.terminalUnknowns = true;

        harness.lastWorker.send(started(none()));

        waitFor(() -> !harness.units.unknownAttempts.isEmpty(), "the UNKNOWN mark tried");
        waitFor(() -> harness.link.pendingUnknowns() == 0, "the mark loop ended on the terminal cause");
        Thread.sleep(100);
        assertThat(harness.units.markedUnknown).isEmpty();
        assertThat(harness.link.unrecordedStarts()).containsExactly(ATTEMPT);
        harness.lastWorker.send(exited());
        harness.lastWorker.send(report());
        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        waitFor(() -> harness.link.unrecordedStarts().isEmpty(), "no longer known unrecorded once completed");
    }

    /// Verification F5: `HostLink.stop()` ends the UNKNOWN mark loop; no store write follows the stop.
    @Test
    void stoppedLink_markLoopWritesNothingAfterTheStop() throws Exception {
        harness.units.startGate = Causes.cause("store down").promise();
        harness.units.failUnknowns.set(Integer.MAX_VALUE);

        harness.lastWorker.send(started(none()));
        waitFor(() -> harness.units.unknownAttempts.size() >= 3, "mark loop running");

        harness.link.stop();
        Thread.sleep(100);
        var atStop = harness.units.unknownAttempts.size();
        Thread.sleep(500);

        assertThat(harness.units.unknownAttempts).as("mark writes after stop()").hasSize(atStop);
        waitFor(() -> harness.link.pendingUnknowns() == 0, "no mark loop left");
    }

    /// Verification F5: `HostLink.stop()` ends a start's retries too; no start write follows the stop.
    @Test
    void stoppedLink_startRetryWritesNothingAfterTheStop() throws Exception {
        restartWith(Collections.nCopies(4, timeSpan(300).millis()), LinkHarness.FAST.startAttemptTimeout());
        harness.units.failStarts.set(Integer.MAX_VALUE);
        harness.units.failUnknowns.set(Integer.MAX_VALUE);

        harness.lastWorker.send(started(none()));
        waitFor(() -> harness.units.started.size() == 1, "the first start attempt failed");

        harness.link.stop();
        Thread.sleep(1500);

        assertThat(harness.units.started).as("start writes after stop()").hasSize(1);
        assertThat(harness.units.unknownAttempts).as("mark writes after stop()").isEmpty();
    }

    /// Verification F5: the mark loop ends once the unit completes; it writes nothing after the completion.
    @Test
    void completedUnit_markLoopWritesNothingAfterTheCompletion() throws Exception {
        restartWith(Collections.nCopies(4, timeSpan(50).millis()), LinkHarness.FAST.startAttemptTimeout());
        harness.units.startGate = Causes.cause("store down").promise();
        harness.units.failUnknowns.set(Integer.MAX_VALUE);

        harness.lastWorker.send(started(none()));
        waitFor(() -> harness.units.unknownAttempts.size() >= 2, "mark loop running");
        harness.lastWorker.send(exited());
        harness.lastWorker.send(report());
        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        Thread.sleep(100);
        var atCompletion = harness.units.unknownAttempts.size();
        Thread.sleep(400);

        assertThat(harness.units.unknownAttempts).as("mark writes after the completion").hasSize(atCompletion);
        waitFor(() -> harness.link.pendingUnknowns() == 0, "no mark loop left");
    }

    /// Unchanged by the ruling: a transient store fault fails the completion write too, nothing is written, and the
    /// session resets.
    @Test
    void startAndCompletionFail_resetsTheSession() throws Exception {
        harness.units.startGate = Causes.cause("store down").promise();
        harness.units.failCompletes.set(1);

        startThenReport(0);

        harness.nextWorker(5);
        assertThat(harness.units.completions).isEmpty();
    }

    private void assertCompletedInTheFirstSession() throws InterruptedException {
        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(harness.units.completions).hasSize(1);
        assertThat(harness.units.started).as("every start attempt ran before the completion").hasSize(START_ATTEMPTS);
        assertThat(harness.units.recordedStarts).isEmpty();
        assertThat(harness.workers).as("no reconnect").isEmpty();
        assertThat(harness.link.isUp()).isTrue();
        waitFor(() -> harness.link.pendingStarts() == 0, "no start left in flight");
        waitFor(() -> harness.link.pendingUnknowns() == 0, "no UNKNOWN mark left in flight (landed, or ended by the completion)");
        assertThat(harness.link.unrecordedStarts()).as("completed: no longer known unrecorded").isEmpty();
    }

    private void startThenReport(long gapMs) throws InterruptedException {
        var worker = harness.lastWorker;

        worker.send(started(some("20261013-7")));
        if (gapMs > 0) {
            Thread.sleep(gapMs);
        }
        worker.send(exited());
        worker.send(report());
    }

    private static List<Message> startsDirectlyThenReports(Message request) {
        return switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit _ -> List.of(started(none()), exited(), report());
            default -> LinkHarness.ackEverything(request);
        };
    }

    private void countStartsAt(Message sent, List<Integer> counts) {
        if (sent instanceof Message.StartUnit) {
            counts.add(harness.link.pendingStarts());
        }
    }

    private static List<Message> startsDirectly(Message request) {
        return switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit _ -> List.of(started(none()));
            default -> LinkHarness.ackEverything(request);
        };
    }

    private static List<Message> preparesThenIgnoresTheStart(Message request) {
        return switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit _ -> List.of();
            default -> LinkHarness.ackEverything(request);
        };
    }

    private List<Message> preparesThenDropsTheLink(Message request) {
        return switch (request) {
            case Message.StartUnit _ -> dropped();
            default -> preparesThenIgnoresTheStart(request);
        };
    }

    private List<Message> dropped() {
        harness.lastWorker.close();
        return List.of();
    }

    private static Granted directUnit() {
        return Granted.granted(REF, JOB, UnitKind.MVN, List.of(List.of("mvn", "-B", "verify")), Duration.ofMinutes(60),
                               6144, Sha.sha("a".repeat(40)).unwrap(), false, 5, none(), GRANTED_AT, 1);
    }

    private static Message.UnitStarted started(Option<String> ticket) {
        return new Message.UnitStarted(JOB, "main", 1, "c-main", "sha256:img", 1_760_000_000_000_000_000L, Instant.now(),
                                       LinkHarness.facts(0), ticket);
    }

    private static Message.UnitExited exited() {
        return new Message.UnitExited(JOB, "main", 1, 0, none(), Instant.now(),
                                      new UnitEndFactsDto(LinkHarness.facts(0), none(), Instant.now(), Map.of(),
                                                          new TcLeftoversDto(List.of(), none()), none()),
                                      1, ROWS.size(), 0, none(), none());
    }

    private static Message.UnitReport report() {
        return new Message.UnitReport(JOB, "main", 1, 0, true, ROWS);
    }
}
