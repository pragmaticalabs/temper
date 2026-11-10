package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.JobId;


/// An `events` row.
public record EventRow(long seq, Instant at, Option<JobId> job, Kind kind, EventPayload payload) {
    public static EventRow eventRow(long seq, Instant at, Option<JobId> job, Kind kind, EventPayload payload) {
        return new EventRow(seq, at, job, kind, payload);
    }

    /// Event kinds (spec §8.3) as the application names them (ruling R-10); the store's adapter maps them to the
    /// lowercase-with-dash `events.kind` and wire form.
    public enum Kind {
        QUEUED,
        STARTED,
        START_UNKNOWN,
        UNIT_FINISHED,
        FINISHED,
        CANCELLED,
        SUPERSEDED,
        /// A unit's attempt was lost with its host (m4-design §2.3): one per settled unit but a cancelled one.
        UNIT_LOST
    }
}
