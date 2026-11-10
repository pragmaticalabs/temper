package org.pragmatica.kiln.core.source;

import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Option;


/// A source with its state and the time of its last success, which survives a failure: the header shows both (spec
/// §6 panel 1).
public record SourceStatus(Source source, SourceState state, Option<Instant> lastSuccess) {
    /// Each staleness bound is this many periods of the source's loop (spec §6: "red when its source is older than 3× its
    /// period").
    public static final int STALE_PERIODS = 3;

    public static SourceStatus sourceStatus(Source source, SourceState state, Option<Instant> lastSuccess) {
        return new SourceStatus(source, state, lastSuccess);
    }

    /// Whether the last success is older than 3 × `period` at `now`. A source that never succeeded is stale: a value
    /// never observed is never fresh.
    public boolean staleAt(Instant now, Duration period) {
        return lastSuccess.map(at -> at.isBefore(now.minus(period.multipliedBy(STALE_PERIODS))))
                          .or(true);
    }
}
