package org.pragmatica.temper.store.api;

import java.time.Instant;


/// A LOST host's mark (m4-design §2.1, §6): when the controller declared it LOST and why, `link-down` or `host-key`
/// (`hosts.lost_at`, `hosts.lost_reason`).
public record HostLoss(Instant at, String reason) {
    public static HostLoss hostLoss(Instant at, String reason) {
        return new HostLoss(at, reason);
    }
}
