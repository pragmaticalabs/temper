package org.pragmatica.temper.store.api;

import java.util.List;


/// One kiln pull, written in ONE transaction (design §2.2, F10): the kiln row's new pull state, the per-source
/// successes kiln forwarded, and the events (change rows inserted `ON CONFLICT DO NOTHING`, plus the pull's own
/// `source-reset`/`gap` announcements).
public record KilnBatch(FleetSourceRow kiln, List<SourceSeen> sources, List<NewFleetEvent> events) {
    public KilnBatch {
        sources = List.copyOf(sources);
        events = List.copyOf(events);
    }

    public static KilnBatch kilnBatch(FleetSourceRow kiln, List<SourceSeen> sources, List<NewFleetEvent> events) {
        return new KilnBatch(kiln, sources, events);
    }
}
