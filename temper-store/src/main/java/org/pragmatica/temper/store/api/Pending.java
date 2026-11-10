package org.pragmatica.temper.store.api;

import java.util.List;


/// What a subscriber at a cursor reads next ([EventStore#pending], m1-design §4.4): a gap, when retention deleted
/// events above the cursor, or the events after it.
public sealed interface Pending {
    /// Events `from..to` (inclusive) were deleted by retention before this subscriber read them. `to` is the event
    /// floor; the subscriber resumes after it.
    record Gap(long from, long to) implements Pending {}

    /// The events after the cursor, ascending, at most the limit; empty when there are none yet.
    record Events(List<EventRow> rows) implements Pending {
        public Events {
            rows = List.copyOf(rows);
        }
    }
}
