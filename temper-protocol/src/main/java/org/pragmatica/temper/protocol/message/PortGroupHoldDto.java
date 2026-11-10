package org.pragmatica.temper.protocol.message;

/// `StartUnit.portGroup` (protocol v2): start the unit through bb-hold as `bb-hold <owner> <priority> …`
/// (m1-design §1.2). `owner` is the configured owner tag (`temper`); `priority` is the job priority, which
/// bb-hold treats as advisory (Q-M1-12).
public record PortGroupHoldDto(String owner, int priority) {
    public static PortGroupHoldDto portGroupHoldDto(String owner, int priority) {
        return new PortGroupHoldDto(owner, priority);
    }
}
