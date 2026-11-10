package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;


/// What one `t2:detector` batch reads: its cursor, its `started_at` (catch-up rows observed
/// before it are skipped), the fleet rows after the cursor or the gap retention left there, and the outstanding GOs
/// (creation order) the rules may invalidate.
public record DetectorInput(long cursor, Option<Instant> startedAt, FleetPending pending, List<OpenGo> open) {
    public DetectorInput {
        open = List.copyOf(open);
    }

    public static DetectorInput detectorInput(long cursor,
                                              Option<Instant> startedAt,
                                              FleetPending pending,
                                              List<OpenGo> open) {
        return new DetectorInput(cursor, startedAt, pending, open);
    }

    /// A batch while no GO is outstanding.
    public static DetectorInput detectorInput(long cursor, Option<Instant> startedAt, FleetPending pending) {
        return new DetectorInput(cursor, startedAt, pending, List.of());
    }
}
