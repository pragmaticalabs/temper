package org.pragmatica.temper.protocol.api;

import java.time.Instant;


/// A LOST host's mark (m4-design §2.1, §11): when the controller declared it LOST and why, `link-down` or `host-key`.
public record HostLossDto(Instant at, String reason) {
    public static HostLossDto hostLossDto(Instant at, String reason) {
        return new HostLossDto(at, reason);
    }
}
