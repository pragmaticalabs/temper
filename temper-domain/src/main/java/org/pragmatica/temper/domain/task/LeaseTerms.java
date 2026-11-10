package org.pragmatica.temper.domain.task;

import java.time.Duration;


/// The two clocks of one lease (m3-design §6.2): the TTL (liveness, extended by every accepted lease-token call) and
/// the step timeout (the wall bound from the claim, never extended).
public record LeaseTerms(Duration ttl, Duration stepTimeout) {
    public static LeaseTerms leaseTerms(Duration ttl, Duration stepTimeout) {
        return new LeaseTerms(ttl, stepTimeout);
    }

    /// The defaults per stage (m3-design §6.2 [G, Q-M3-20], ruling R-M3-25: measured in M3a and revised): 30 min TTL
    /// and a 4 h step for DESIGN, VERIFY and FIX, 6 h for CODE, a 60 min TTL and 8 h for GATE. A stage nobody claims
    /// has no lease; it gets VERIFY's terms only so the function is total.
    public static LeaseTerms forStage(Stage stage) {
        return switch (stage) {
            case CODE -> new LeaseTerms(Duration.ofMinutes(30), Duration.ofHours(6));
            case GATE -> new LeaseTerms(Duration.ofMinutes(60), Duration.ofHours(8));
            case DESIGN, VERIFY, FIX, TRIAGED, CI, MERGE_READY, NEEDS_RULING, MERGED, ABANDONED -> new LeaseTerms(Duration.ofMinutes(30),
                                                                                                                  Duration.ofHours(4));
        };
    }
}
