package org.pragmatica.temper.controller.host;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.controller.fake.Rows;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.protocol.message.UnitRefDto;
import org.pragmatica.temper.store.api.ActiveUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// Report collection decisions (m0-design §4.2): complete only units active on this host; ack without writing
/// what is already settled; never ack a unit queued again or active elsewhere.
class WorkerEventsTest {
    private static final TcLeftoversDto LEFTOVERS = new TcLeftoversDto(List.of(new TcLeftoversDto.ContainerDto("c0ffee", "s-1")),
                                                                       none());
    private LinkHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new LinkHarness();
        harness.link.start();
        var worker = harness.nextWorker();
        worker.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "link UP");
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    private void report(long job, List<TestcaseDto> rows) {
        harness.lastWorker.send(new Message.UnitExited(job, "main", 1, 0, none(), Instant.now(),
                                                       new UnitEndFactsDto(LinkHarness.facts(0), none(), Instant.now(), Map.of(), LEFTOVERS, none()),
                                                       1, rows.size(), 0, none(), none()));
        harness.lastWorker.send(new Message.UnitReport(job, "main", 1, 0, true, rows));
    }

    private static TestcaseDto row(String outcome) {
        return new TestcaseDto("org.x.T", "t", outcome, 0.1, "main", false);
    }

    @Test
    void report_settledUnit_ackedWithoutWriting() throws Exception {
        harness.jobs.rows.put(JobId.jobId(1).unwrap(), Rows.job(1, JobState.PASSED, List.of(Rows.unit(UnitState.PASSED, none(), none()))));

        report(1, List.of(row("passed")));

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(harness.units.completions).isEmpty();
    }

    @Test
    void report_unknownJob_ackedSoTheWorkerCleansUp() throws Exception {
        report(2, List.of());

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(harness.units.completions).isEmpty();
    }

    @Test
    void report_unitQueuedAgain_neitherCompletedNorAcked() throws Exception {
        harness.jobs.rows.put(JobId.jobId(3).unwrap(), Rows.job(3, JobState.QUEUED, List.of(Rows.unit(UnitState.QUEUED, none(), none()))));

        report(3, List.of());
        Thread.sleep(500);

        assertThat(harness.lastWorker.count(Message.UnitReportAck.class)).isZero();
        assertThat(harness.units.completions).isEmpty();
    }

    @Test
    void report_malformedRow_marksErrorReportAndKeepsParsedRows() throws Exception {
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(4).unwrap(), UnitName.MAIN), 4, UnitState.RUNNING,
                                                6144, java.time.Duration.ofMinutes(60), none(), some(Instant.now()),
                                                false, none(), none(), UnitKind.MVN, some(Instant.now()), 1));

        report(4, List.of(row("passed"), row("exploded")));

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        var completion = harness.units.completions.getFirst();
        assertThat(completion.finish().errorReason()).isEqualTo(some("report"));
        assertThat(completion.rows()).hasSize(1);
        assertThat(completion.finish()
                             .endFacts()
                             .map(facts -> facts.tcLeftovers())).isEqualTo(some(TcLeftovers.tcLeftovers(List.of(TcLeftovers.TcLeftover.tcLeftover("c0ffee",
                                                                                                                                              "s-1")), none())));
    }

    private void activeHere(long job) {
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(job).unwrap(), UnitName.MAIN), job, UnitState.RUNNING,
                                                6144, java.time.Duration.ofMinutes(60), none(), some(Instant.now()),
                                                false, none(), none(), UnitKind.MVN, some(Instant.now()), 1));
    }

    /// Integration F4: the worker re-sends a held unit's `UnitStarted` right before its exit report. The completion
    /// waits until the start is recorded, so the store sees the start (and its ticket) before it completes the unit.
    @Test
    void report_rightAfterAUnitStarted_completesOnlyOnceTheStartIsRecorded() throws Exception {
        activeHere(7);
        var gate = Promise.<Unit>promise();
        harness.units.startGate = gate;

        harness.lastWorker.send(new Message.UnitStarted(7, "main", 1, "c-main", "sha256:img", 1_760_000_000_000_000_000L,
                                                        Instant.now(), LinkHarness.facts(0), some("20261013-7")));
        report(7, List.of(row("passed")));
        waitFor(() -> !harness.units.started.isEmpty(), "start being recorded");
        Thread.sleep(500);

        assertThat(harness.units.completions).as("no completion before the start is recorded").isEmpty();

        gate.succeed(Unit.unit());
        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(harness.units.completions).hasSize(1);
    }

    /// §12.7: the unsolicited `lost` names its unit, and the controller ends that unit `ERROR(lost)` at once.
    @Test
    void lostFailure_namingAUnitActiveHere_endsItErrorLost() throws Exception {
        activeHere(6);

        harness.lastWorker.send(Message.Failure.failure("lost", "temper-6-main: gone", new UnitRefDto(6, "main", 1)));

        waitFor(() -> !harness.units.completions.isEmpty(), "lost completion");
        var completion = harness.units.completions.getFirst();
        assertThat(completion.ref()).isEqualTo(UnitRef.unitRef(JobId.jobId(6).unwrap(), UnitName.MAIN));
        assertThat(completion.finish().errorReason()).isEqualTo(some("lost"));
        assertThat(completion.finish().rc()).isEqualTo(none());
        assertThat(completion.rows()).isEmpty();
    }

    /// The absorbed-nothing path: a store failure while ending the lost unit resets the session (as a failed report
    /// completion does), so the reconnect's reconcile ends it; nothing was written.
    @Test
    void lostFailure_storeFails_resetsTheSession() throws Exception {
        activeHere(9);
        harness.units.failCompletes.set(1);

        harness.lastWorker.send(Message.Failure.failure("lost", "temper-9-main: gone", new UnitRefDto(9, "main", 1)));

        harness.nextWorker(2);
        assertThat(harness.units.completions).isEmpty();
    }

    @Test
    void lostFailure_forAUnitNotActiveHere_isOnlyLogged() throws Exception {
        harness.jobs.rows.put(JobId.jobId(7).unwrap(), Rows.job(7, JobState.PASSED, List.of(Rows.unit(UnitState.PASSED, none(), none()))));

        harness.lastWorker.send(Message.Failure.failure("lost", "temper-7-main: gone", new UnitRefDto(7, "main", 1)));
        Thread.sleep(300);

        assertThat(harness.units.completions).isEmpty();
        assertThat(harness.link.isUp()).isTrue();
    }

    @Test
    void failure_withoutAUnit_orNotLost_completesNothing() throws Exception {
        activeHere(8);

        harness.lastWorker.send(Message.Failure.failure("lost", "temper-8-main: gone"));
        harness.lastWorker.send(Message.Failure.failure("bad-frame", "x", new UnitRefDto(8, "main", 1)));
        Thread.sleep(300);

        assertThat(harness.units.completions).isEmpty();
        assertThat(harness.link.isUp()).isTrue();
    }

    @Test
    void reportChunk_withoutUnitExited_isIgnored() throws Exception {
        harness.lastWorker.send(new Message.UnitReport(5, "main", 1, 0, true, List.of()));
        Thread.sleep(300);

        assertThat(harness.lastWorker.count(Message.UnitReportAck.class)).isZero();
        assertThat(harness.link.isUp()).isTrue();
    }
}
