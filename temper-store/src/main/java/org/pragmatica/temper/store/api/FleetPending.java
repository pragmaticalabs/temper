package org.pragmatica.temper.store.api;

import java.util.List;


/// What a fleet reader at a cursor reads next, from one snapshot: a gap when retention deleted events above the
/// cursor, otherwise the events after it.
public sealed interface FleetPending {
    record Gap(long from, long to) implements FleetPending {}

    record Events(List<FleetEventRow> rows) implements FleetPending {
        public Events {
            rows = List.copyOf(rows);
        }
    }
}
