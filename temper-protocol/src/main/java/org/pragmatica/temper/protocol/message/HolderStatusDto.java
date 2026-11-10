package org.pragmatica.temper.protocol.message;

import org.pragmatica.temper.domain.hold.HolderStatus;


/// Wire form of [HolderStatus] (protocol v2): the bb-hold holder of a unit found by the worker's scan, for
/// reconcile (m1-design §1.5). Upper-case on the wire, as [ContainerStatus]. `toDomain`/`from` follow the sibling
/// wire enum [KilledBy].
public enum HolderStatusDto {
    WAITING,
    HELD,
    DEAD;
    public HolderStatus toDomain() {
        return switch (this) {
            case WAITING -> HolderStatus.WAITING;
            case HELD -> HolderStatus.HELD;
            case DEAD -> HolderStatus.DEAD;
        };
    }
    public static HolderStatusDto from(HolderStatus status) {
        return switch (status) {
            case WAITING -> WAITING;
            case HELD -> HELD;
            case DEAD -> DEAD;
        };
    }
}
