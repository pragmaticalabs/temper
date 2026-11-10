package org.pragmatica.temper.controller.host;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitKind;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.store.api.ActiveUnit;
import org.pragmatica.temper.store.api.PortGroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.controller.host.LinkHarness.hello;
import static org.pragmatica.temper.controller.host.LinkHarness.waitFor;

/// Verification F5 (and F3 of the maintainer-rulings round): after a controller restart the in-memory set is empty, and the
/// reconnect's reconcile finds a unit GRANTED with no recorded start whose container runs. Its `StartUnknown` is
/// performed (the scoped mark is written) and the unit is known unrecorded BEFORE that write, so a store that refuses
/// it still shows the unit UNKNOWN; a bb-hold unit stored `waiting`, which the start bound leaves out, included.
class ReconcileUnknownTest {
    private static final long JOB = 31;
    private static final UnitRef REF = UnitRef.unitRef(JobId.jobId(JOB).unwrap(), UnitName.MAIN);
    private static final Instant GRANTED_AT = Instant.parse("2026-10-14T09:00:00Z");
    private LinkHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new LinkHarness();
    }

    @AfterEach
    void tearDown() {
        harness.link.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restart_grantedUnitRunning_markRefused_knownUnrecorded_andTheMarkWritten(boolean waitingInHold) throws Exception {
        harness.units.failUnknowns.set(Integer.MAX_VALUE);
        harness.units.activeOn(LinkHarness.HOST)
                     .add(ActiveUnit.activeUnit(REF, JOB, UnitState.GRANTED, 6144, Duration.ofMinutes(60), none(), none(),
                                                waitingInHold, waitingInHold ? some(PortGroup.WAITING) : none(), none(),
                                                UnitKind.MVN, some(GRANTED_AT), 3));
        harness.link.start();

        harness.nextWorker().send(hello(LinkHarness.VERSION, List.of(running())));

        waitFor(() -> !harness.units.unknownAttempts.isEmpty(), "the reconcile's StartUnknown performed");
        assertThat(harness.units.unknownAttempts.getFirst()).isEqualTo(REF);
        assertThat(harness.units.unknownGrants.getFirst()).as("scoped to the attempt").isEqualTo(3);
        assertThat(harness.units.markedUnknown).as("refused").isEmpty();
        assertThat(harness.link.unrecordedStarts()).containsExactly(UnitAttempt.unitAttempt(REF, 3));
    }

    private static DiskUnitDto running() {
        return DiskUnitDto.diskUnitDto(JOB, "main", true, none(), ContainerStatus.RUNNING, none(), none(), Option.none(), some(3));
    }
}
