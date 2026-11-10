package org.pragmatica.temper.controller.host;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.controller.fake.FakeStores;
import org.pragmatica.temper.domain.evidence.EvidenceKind;
import org.pragmatica.temper.domain.manifest.ManifestOutcome;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.JobState;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.message.ManifestOutcomeDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.protocol.message.VoidReason;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.CompleteOutcome;
import org.pragmatica.temper.store.api.StoredEvidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// A unit's end on the controller (m1-design §1.4, §2.5, §3.2, §3.3): the void reason reaches the store, the
/// manifest chunks are folded into one outcome, the small tarball is pulled before `complete` and recorded by it,
/// the full one after the ack, and `result.json` is written when the completion finished the job.
class UnitEndTest {
    private static final UnitRef REF = UnitRef.unitRef(JobId.jobId(21).unwrap(), UnitName.MAIN);
    private static final ManifestEntryDto FOO = new ManifestEntryDto("core", "org.x.FooTest", "surefire");
    private static final ManifestEntryDto BAR = new ManifestEntryDto("core", "org.x.BarTest", "surefire");
    private LinkHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new LinkHarness();
        harness.link.start();
        harness.nextWorker()
               .send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "link UP");
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(REF, 21, UnitState.RUNNING, 6144, Duration.ofMinutes(60), none(),
                                                some(Instant.now()), false, none(), none(), UnitKind.MVN, some(Instant.now()), 1));
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    private static Message.UnitExited exited(int rc, VoidReason voidReason) {
        return exited(rc, voidReason, none());
    }

    private static Message.UnitExited exited(int rc, VoidReason voidReason, Option<String> errorReason) {
        return new Message.UnitExited(21, "main", 1, rc, none(), Instant.now(),
                                      new UnitEndFactsDto(LinkHarness.facts(0), none(), Instant.now(), Map.of(),
                                                          new TcLeftoversDto(List.of(), none()), none()),
                                      1, 1, 0, errorReason, voidReason == null ? none() : some(voidReason));
    }

    private void report() {
        harness.lastWorker.send(new Message.UnitReport(21, "main", 1, 0, true,
                                                       List.of(new TestcaseDto("org.x.FooTest", "t", "failed", 0.1, "main", false))));
    }

    private FakeStores.Completion completion() throws InterruptedException {
        waitFor(() -> !harness.units.completions.isEmpty(), "completion");
        return harness.units.completions.getFirst();
    }

    @Test
    void voidReason_reachesTheStore_andTheRedsAreStillStored() throws Exception {
        harness.lastWorker.send(exited(137, VoidReason.PORT_GROUP_LOST));
        report();

        var done = completion();
        assertThat(done.finish().voidReason()).isEqualTo(some("port-group-lost"));
        assertThat(done.finish().rc()).isEqualTo(some(137));
        assertThat(done.rows()).hasSize(1);
    }

    /// F7: an exit's `errorReason` reaches the store whole, its detail after the token included (no producer sends one
    /// with detail today; this pins the plumbing, so re-tokenising it here goes red).
    @Test
    void errorReason_reachesTheStore_withItsDetail() throws Exception {
        harness.lastWorker.send(exited(1, null, some("infra: docker: logs failed (rc 1)")));
        report();

        assertThat(completion().finish().errorReason()).isEqualTo(some("infra: docker: logs failed (rc 1)"));
    }

    @Test
    void manifestChunks_areFoldedInOrder_andPassedToComplete() throws Exception {
        harness.lastWorker.send(exited(1, null));
        harness.lastWorker.send(new Message.UnitManifest(21, "main", 1, 0, false,
                                                         new ManifestOutcomeDto.Computed(List.of(BAR), List.of(), List.of(), List.of())));
        harness.lastWorker.send(new Message.UnitManifest(21, "main", 1, 1, true,
                                                         new ManifestOutcomeDto.Computed(List.of(FOO), List.of(), List.of("aether/ember"),
                                                                                         List.of("org.x.Slow"))));
        report();

        var manifest = completion().manifest();
        assertThat(manifest.isPresent()).isTrue();
        assertThat(manifest.unwrap().outcome()).isInstanceOfSatisfying(ManifestOutcome.Computed.class, computed -> {
            assertThat(computed.entries()).extracting(entry -> entry.className())
                                          .containsExactlyInAnyOrder("org.x.FooTest", "org.x.BarTest");
            assertThat(computed.notReached()).containsExactly("aether/ember");
            assertThat(computed.unresolvedAnnotations()).containsExactly("org.x.Slow");
        });
    }

    /// Half a manifest is never stored: without its last chunk the job's coverage is simply not computed.
    @Test
    void manifestWithoutItsLastChunk_isNotStored() throws Exception {
        harness.lastWorker.send(exited(0, null));
        harness.lastWorker.send(new Message.UnitManifest(21, "main", 1, 0, false,
                                                         new ManifestOutcomeDto.Computed(List.of(FOO), List.of(), List.of(), List.of())));
        report();

        assertThat(completion().manifest()).isEqualTo(none());
    }

    @Test
    void unsupportedManifest_isPassedAsIs() throws Exception {
        harness.lastWorker.send(exited(0, null));
        harness.lastWorker.send(new Message.UnitManifest(21, "main", 1, 0, true, new ManifestOutcomeDto.Unsupported("includesFile in core")));
        report();

        assertThat(completion().manifest().unwrap().outcome()).isInstanceOf(ManifestOutcome.Unsupported.class);
    }

    @Test
    void smallEvidence_isPulledBeforeComplete_andFullAfterTheAck() throws Exception {
        harness.lastWorker.send(exited(0, null));
        report();

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        var done = completion();
        assertThat(done.evidence().map(StoredEvidence::path).map(Object::toString)).isEqualTo(some("/ev/temper-21-main.small"));
        waitFor(() -> !harness.evidence.recorded.isEmpty(), "full tarball recorded");
        assertThat(harness.pulls).containsExactly("temper-21-main:small", "temper-21-main:full");
        assertThat(harness.evidence.recorded.getFirst().kind()).isEqualTo(EvidenceKind.FULL);
    }

    /// A re-sent report of a unit already complete (the store answers AlreadyComplete) is acked and pulls no second
    /// full tarball: only the completing report does.
    @Test
    void resentReport_ofACompletedUnit_isAcked_withoutAFullPull() throws Exception {
        harness.units.completeAnswer = new CompleteOutcome.AlreadyComplete(REF, UnitState.PASSED);
        harness.lastWorker.send(exited(0, null));
        report();

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        Thread.sleep(300);
        assertThat(harness.pulls).containsExactly("temper-21-main:small");
        assertThat(harness.evidence.recorded).isEmpty();
    }

    /// m4-design §4.3: the completion is scoped to the report's attempt (1, the store's current one); a report
    /// the store finds superseded is acked (nothing of it is left to reconcile) and pulls no full tarball.
    @Test
    void report_ofASupersededAttempt_isAcked_withoutAFullPull() throws Exception {
        harness.units.activeOn(LinkHarness.HOST)
                     .add(0,
                          ActiveUnit.activeUnit(UnitRef.unitRef(JobId.jobId(22).unwrap(), UnitName.MAIN), 22, UnitState.RUNNING, 6144,
                                                Duration.ofMinutes(60), none(), some(Instant.now()), false, none(), none(), UnitKind.MVN,
                                                some(Instant.now()), 9));
        harness.units.completeAnswer = new CompleteOutcome.Superseded(REF, UnitState.RUNNING, 1, 5);
        harness.lastWorker.send(exited(0, null));
        report();

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(completion().attempt()).as("the reporting unit's attempt, not another active unit's").isEqualTo(1);
        Thread.sleep(300);
        assertThat(harness.pulls).containsExactly("temper-21-main:small");
        assertThat(harness.evidence.recorded).isEmpty();
    }

    /// FER: a failed small pull completes the unit without evidence (the record then says `stored: false`); the
    /// report is still acked and nothing else is pulled.
    @Test
    void smallPullFails_unitStillCompletesAndIsAcked_withoutEvidence() throws Exception {
        harness.puller = (host, ref, attempt, kind) -> Causes.cause("ssh down").promise();
        harness.lastWorker.send(exited(0, null));
        report();

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(completion().evidence()).isEqualTo(none());
        Thread.sleep(200);
        assertThat(harness.evidence.recorded).isEmpty();
    }

    @Test
    void resultJson_isWritten_whenTheCompletionFinishedTheJob() throws Exception {
        harness.units.completeAnswer = new CompleteOutcome.Completed(REF, UnitState.FAILED, some(JobState.FAILED));
        harness.results.json.put(REF.job(), "{\"job\":21}");
        harness.lastWorker.send(exited(1, null));
        report();

        var file = harness.evidenceRoot.resolve("jobs/21/result.json");
        waitFor(() -> Files.exists(file), "result.json");
        assertThat(Files.readString(file)).isEqualTo("{\"job\":21}");
    }

    @Test
    void resultJson_isNotWritten_whenTheJobGoesOn() throws Exception {
        harness.results.json.put(REF.job(), "{\"job\":21}");
        harness.lastWorker.send(exited(0, null));
        report();

        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        Thread.sleep(200);
        assertThat(harness.evidenceRoot.resolve("jobs/21/result.json")).doesNotExist();
    }
}
