package org.pragmatica.temper.store.api;

/// What one sweep did (m3-design §6.2, §6.6, §5.2 T1): leases expired (requeued or parked), WAITING tasks resumed, and
/// TRIAGED tasks advanced. A task another transaction held locked is skipped and seen at the next sweep.
public record SweepOutcome(int expired, int resumed, int advanced) {
    public static SweepOutcome sweepOutcome(int expired, int resumed, int advanced) {
        return new SweepOutcome(expired, resumed, advanced);
    }
}
