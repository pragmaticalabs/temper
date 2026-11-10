package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.manifest.ManifestSpec;
import org.pragmatica.temper.domain.manifest.Reach;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.domain.verdict.KillCause;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.ManifestSpecDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.protocol.message.PortGroupHoldDto;
import org.pragmatica.temper.protocol.message.StartFailureKind;
import org.pragmatica.temper.protocol.message.TcLeftoversDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.Granted;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// The bb-hold start split on the controller side (m1-design §1.2, §1.3): `StartUnit.portGroup` for a port-group
/// unit on a shared host, `UnitHolding` → `UnitStore.holding`, and the unsolicited `UnitStarted` (with the ticket)
/// or `UnitStartFailed` that ends the wait.
class HoldLaunchTest {
    private static final Duration TIMEOUT = Duration.ofHours(3);
    private static final UnitName GATE = UnitName.unitName("gate").unwrap();
    private static final UnitRef REF = UnitRef.unitRef(JobId.jobId(11).unwrap(), GATE);
    private static final ManifestSpec SPEC = ManifestSpec.manifestSpec(List.of("mvn", "-B", ManifestSpec.EFFECTIVE_POM_GOAL),
                                                                       Reach.INTEGRATION_TEST,
                                                                       Map.of(),
                                                                       none(),
                                                                       none());
    private static final List<TestcaseDto> ROWS = List.of(new TestcaseDto("org.x.T", "t", "passed", 0.1, "gate", false));
    private LinkHarness harness;

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    private static Granted gate(boolean needsPortGroup) {
        return unit(UnitKind.GATE_UNSHARDED, needsPortGroup);
    }

    private static Granted unit(UnitKind kind, boolean needsPortGroup) {
        return Granted.granted(REF, 11, kind, List.of(List.of("mvn", "-B", "clean", "install")), TIMEOUT,
                               16384, Sha.sha("a".repeat(40)).unwrap(), needsPortGroup, 5, some(SPEC), Instant.now(), 1);
    }

    private void up(boolean portIsolated, Message reply) throws Exception {
        harness = new LinkHarness(LinkHarness.FAST, portIsolated);
        harness.responder = request -> switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit _ -> List.of(reply);
            default -> LinkHarness.ackEverything(request);
        };
        harness.link.start();
        var worker = harness.nextWorker();
        worker.send(hello(LinkHarness.VERSION, List.of()));
        waitFor(harness.link::isUp, "link UP");
    }

    private void activeHere() {
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(REF, 11, UnitState.GRANTED, 16384, TIMEOUT, none(), none(), true, none(), none(), UnitKind.GATE_UNSHARDED, some(Instant.now()), 1));
    }

    private static Message holding() {
        return new Message.UnitHolding(11, "gate", 1, 1_760_000_000_000_000_000L, LinkHarness.facts(0));
    }

    private static Message.UnitStarted started(String ticket) {
        return new Message.UnitStarted(11, "gate", 1, "c-gate", "sha256:img", 1_760_000_000_000_000_000L, Instant.now(),
                                       LinkHarness.facts(0), some(ticket));
    }

    @Test
    void launch_sendsPortGroupAndManifest_andRecordsHolding() throws Exception {
        up(false, holding());

        harness.link.launch(gate(true)).await();

        var start = harness.lastWorker.expect(Message.StartUnit.class, 5);
        assertThat(start.portGroup()).isEqualTo(some(new PortGroupHoldDto("temper", 5)));
        assertThat(start.manifest()).isEqualTo(some(ManifestSpecDto.manifestSpecDto(SPEC)));
        assertThat(harness.units.holding).containsExactly(REF);
        assertThat(harness.units.holdingRows.getFirst().marker().nanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(harness.units.started).isEmpty();
        assertThat(harness.units.completions).isEmpty();
    }

    /// A non-Gate port-group unit on a port-isolated host starts directly (spec §6.2).
    @Test
    void launch_startsDirectly_onAPortIsolatedHost() throws Exception {
        up(true, started("unused"));

        harness.link.launch(unit(UnitKind.MODULE_TEST, true)).await();

        assertThat(harness.lastWorker.expect(Message.StartUnit.class, 5).portGroup()).isEqualTo(none());
    }

    /// m0-design §12.4 (maintainer): an unsharded Gate keeps bb-hold until E1, even on a port-isolated host.
    @Test
    void launch_holdsAGate_evenOnAPortIsolatedHost() throws Exception {
        up(true, holding());

        harness.link.launch(gate(true)).await();

        assertThat(harness.lastWorker.expect(Message.StartUnit.class, 5).portGroup()).isEqualTo(some(new PortGroupHoldDto("temper", 5)));
        assertThat(harness.units.holding).containsExactly(REF);
    }

    @Test
    void launch_startsDirectly_unitWithoutPortGroup() throws Exception {
        up(false, started("unused"));

        harness.link.launch(gate(false)).await();

        assertThat(harness.lastWorker.expect(Message.StartUnit.class, 5).portGroup()).isEqualTo(none());
    }

    /// The reply's ticket reaches the store: without it the result record's unit `portGroup` stays empty.
    @Test
    void unsolicitedUnitStarted_recordsTheStartWithItsTicket() throws Exception {
        up(false, holding());
        harness.link.launch(gate(true)).await();
        activeHere();

        harness.lastWorker.send(started("20261013-7"));

        waitFor(() -> !harness.units.started.isEmpty(), "start recorded");
        assertThat(harness.units.started).containsExactly(REF);
        assertThat(harness.units.startedRows.getFirst().ticket()).isEqualTo(some("20261013-7"));
        assertThat(harness.units.startedRows.getFirst().containerId()).isEqualTo("c-gate");
    }

    @Test
    void unsolicitedUnitStartFailed_endsTheWaitingUnit() throws Exception {
        up(false, holding());
        harness.link.launch(gate(true)).await();
        activeHere();

        harness.lastWorker.send(new Message.UnitStartFailed(11, "gate", 1, StartFailureKind.ERROR,
                                                            "port-group: bb-hold refused (rc 2)", none()));

        waitFor(() -> !harness.units.completions.isEmpty(), "completion");
        assertThat(harness.units.completions.getFirst().ref()).isEqualTo(REF);
        assertThat(harness.units.completions.getFirst().finish().errorReason()).isEqualTo(some("port-group: bb-hold refused (rc 2)"));
    }

    @Test
    void unsolicitedUnitStartFailed_cancelledWhileWaiting_endsCancelled() throws Exception {
        up(false, holding());
        harness.link.launch(gate(true)).await();
        activeHere();

        harness.lastWorker.send(new Message.UnitStartFailed(11, "gate", 1, StartFailureKind.CANCELLED, "cancelled", none()));

        waitFor(() -> !harness.units.completions.isEmpty(), "completion");
        assertThat(harness.units.completions.getFirst().finish().killedBy()).isEqualTo(some(KillCause.CANCEL));
    }

    @Test
    void unsolicitedMessages_forAUnitNotActiveHere_areIgnored() throws Exception {
        up(false, holding());

        harness.lastWorker.send(started("T1"));
        harness.lastWorker.send(new Message.UnitStartFailed(11, "gate", 1, StartFailureKind.ERROR, "port-group: x", none()));
        Thread.sleep(300);

        assertThat(harness.units.started).isEmpty();
        assertThat(harness.units.completions).isEmpty();
        assertThat(harness.link.isUp()).isTrue();
    }

    /// Verification F-b: on the hold path the unsolicited `UnitStarted` replaces the start the launch registered while
    /// the `UnitHolding` write is still in flight. When that write settles, the launch removes only its own entry, so
    /// the report that follows still waits for the start being written.
    @Test
    void holdingWriteSettlesAfterTheUnsolicitedStart_reportStillWaitsForTheStart() throws Exception {
        up(false, holding());
        activeHere();
        var holdingWrite = Promise.<Unit> promise();
        var startWrite = Promise.<Unit> promise();
        harness.units.holdingGate = holdingWrite;
        harness.units.startGate = startWrite;
        harness.responder = HoldLaunchTest::holdsThenStarts;

        var launched = harness.link.launch(gate(true));
        waitFor(() -> !harness.units.holding.isEmpty() && !harness.units.started.isEmpty(),
                "holding and start writes in flight");
        holdingWrite.succeed(Unit.unit());
        launched.await();

        waitFor(() -> harness.link.pendingStarts() == 1, "the start being written still registered after the launch");
        harness.lastWorker.send(exited());
        harness.lastWorker.send(report());
        Thread.sleep(150);
        assertThat(harness.units.completions).as("no completion while the start is in flight").isEmpty();

        startWrite.succeed(Unit.unit());
        harness.lastWorker.expect(Message.UnitReportAck.class, 5);
        assertThat(harness.units.recordedStarts).containsExactly(REF);
        assertThat(harness.units.completions).hasSize(1);
        waitFor(() -> harness.link.pendingStarts() == 0, "no start left in flight");
    }

    private static List<Message> holdsThenStarts(Message request) {
        return switch (request) {
            case Message.PrepareJob prepare -> List.of(new Message.JobPrepared(prepare.job(), "/ws"));
            case Message.StartUnit _ -> List.of(holding(), started("T7"));
            default -> LinkHarness.ackEverything(request);
        };
    }

    private static Message.UnitExited exited() {
        return new Message.UnitExited(11, "gate", 1, 0, none(), Instant.now(),
                                      new UnitEndFactsDto(LinkHarness.facts(0), none(), Instant.now(), Map.of(),
                                                          new TcLeftoversDto(List.of(), none()), none()),
                                      1, ROWS.size(), 0, none(), none());
    }

    private static Message.UnitReport report() {
        return new Message.UnitReport(11, "gate", 1, 0, true, ROWS);
    }
}
