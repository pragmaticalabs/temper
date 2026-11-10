package org.pragmatica.temper.controller.host;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.temper.controller.policy.DiskUnit;
import org.pragmatica.temper.domain.hold.ContainerState;
import org.pragmatica.temper.domain.hold.HolderStatus;
import org.pragmatica.temper.domain.model.JobId;
import org.pragmatica.temper.domain.model.UnitName;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.message.ContainerStatus;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.HolderStatusDto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The `Hello` disk scan is read at the link (R-4): the wire `DiskUnitDto` becomes the policy's [DiskUnit], with
/// the protocol's container status mapped onto the domain's [ContainerState].
class ReconcileRunTest {
    private static final UnitRef GATE = UnitRef.unitRef(JobId.jobId(1).unwrap(), UnitName.unitName("gate").unwrap());

    private static DiskUnit read(ContainerStatus container, Option<HolderStatusDto> holder, Option<String> ticket) {
        return ReconcileRun.diskUnit(DiskUnitDto.diskUnitDto(1, "gate", true, none(), container, holder, ticket, Option.none(), none()))
                           .unwrap();
    }

    @Test
    void diskUnit_readsHolderAndTicket_fromTheWire() {
        assertThat(read(ContainerStatus.ABSENT, some(HolderStatusDto.DEAD), some("T9")))
            .isEqualTo(DiskUnit.diskUnit(GATE, 1, true, none(), ContainerState.ABSENT, some(HolderStatus.DEAD), some("T9"), none()));
    }

    @Test
    void diskUnit_mapsEveryContainerStatus_toTheDomainState() {
        assertThat(read(ContainerStatus.RUNNING, none(), none()).container()).isEqualTo(ContainerState.RUNNING);
        assertThat(read(ContainerStatus.EXITED, none(), none()).container()).isEqualTo(ContainerState.EXITED);
        assertThat(read(ContainerStatus.ABSENT, none(), none()).container()).isEqualTo(ContainerState.ABSENT);
    }

    @Test
    void diskUnit_readsAbsent_whenTheWireOmitsTheContainer() {
        assertThat(read(null, none(), none()).container()).isEqualTo(ContainerState.ABSENT);
    }

    @Test
    void diskUnit_fails_whenTheUnitNameDoesNotParse() {
        assertThat(ReconcileRun.diskUnit(DiskUnitDto.diskUnitDto(1, "", true, none(), ContainerStatus.RUNNING, none(), none(), Option.none(), none()))
                               .isFailure()).isTrue();
    }
}
