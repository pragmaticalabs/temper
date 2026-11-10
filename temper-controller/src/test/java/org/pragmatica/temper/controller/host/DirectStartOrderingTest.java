package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.Granted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// Verification F1 (R-13): a collection of a directly started unit waits for its start. The `StartUnit` reply
/// resolves on the async executor while the report right behind it is read on the reader thread; with ONE
/// virtual-thread carrier the reader reads the whole report before the reply's continuation runs, so a start
/// registered by the reply was always too late (40/40 in the verifier's probe). This class runs only in the
/// `one-carrier` surefire execution (`-Djdk.virtualThreadScheduler.parallelism=1`), which makes that order
/// deterministic; the start write is held open by a gate, so a completion before the gate opens is the defect.
/// To run it alone: `mvn -pl temper-controller test-compile surefire:test@one-carrier`. `-Dtest` overrides the default
/// execution's exclude, so `-Dtest=DirectStartOrderingTest` runs it there, with many carriers, and the guard fails.
class DirectStartOrderingTest {
    private static final long JOB = 12;
    private static final UnitRef REF = UnitRef.unitRef(JobId.jobId(JOB).unwrap(), UnitName.MAIN);
    private static final List<TestcaseDto> ROWS = List.of(new TestcaseDto("org.x.T", "t", "passed", 0.1, "main", false));
    private static final String RUN_ALONE = "run through the one-carrier execution; alone: "
                                            + "mvn -pl temper-controller test-compile surefire:test@one-carrier "
                                            + "(-Dtest overrides the default execution's exclude)";
    private LinkHarness harness;

    /// Without one carrier the scheduling this test pins does not occur, and it would pass for the wrong reason. The
    /// guard fails rather than skips, so a build that runs this class with many carriers (the default execution
    /// without its exclude, or the one-carrier execution without its argLine) goes red.
    @BeforeAll
    static void oneCarrier() {
        assertThat(System.getProperty("jdk.virtualThreadScheduler.parallelism")).as(RUN_ALONE)
                                                                                .isEqualTo("1");
    }

    @BeforeEach
    void setUp() throws Exception {
        harness = new LinkHarness();
        harness.responder = DirectStartOrderingTest::startsDirectlyThenReports;
        harness.link.start();
        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "link UP");
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(REF, JOB, UnitState.GRANTED, 6144, Duration.ofMinutes(60), none(),
                                                none(), true, none(), none(), UnitKind.MVN, some(Instant.now()), 1));
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    @RepeatedTest(10)
    void directStart_reportRightBehindTheReply_completesOnlyOnceTheStartIsRecorded() throws Exception {
        var gate = Promise.<Unit>promise();
        harness.units.startGate = gate;

        var launched = harness.link.launch(directUnit());
        waitFor(() -> !harness.units.started.isEmpty() || !harness.units.completions.isEmpty(),
                "start write attempted or unit completed");
        Thread.sleep(150);

        assertThat(harness.units.completions).as("no completion while the start is in flight").isEmpty();

        gate.succeed(Unit.unit());
        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        launched.await();
        assertThat(harness.units.recordedStarts).containsExactly(REF);
        assertThat(harness.units.completions).hasSize(1);
        assertThat(harness.workers).as("no reconnect").isEmpty();
        waitFor(() -> harness.link.pendingStarts() == 0, "no start left in flight");
    }

    private static List<Message> startsDirectlyThenReports(Message request) {
        return switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit _ -> List.of(started(), exited(), report());
            default -> LinkHarness.ackEverything(request);
        };
    }

    private static Granted directUnit() {
        return Granted.granted(REF, JOB, UnitKind.MVN, List.of(List.of("mvn", "-B", "verify")), Duration.ofMinutes(60),
                               6144, Sha.sha("a".repeat(40)).unwrap(), false, 5, none(), Instant.now(), 1);
    }

    private static Message.UnitStarted started() {
        return new Message.UnitStarted(JOB, "main", 1, "c-main", "sha256:img", 1_760_000_000_000_000_000L, Instant.now(),
                                       LinkHarness.facts(0), none());
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
