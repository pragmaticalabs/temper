package org.pragmatica.temper.controller.t3;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.BbSnapshot;
import org.pragmatica.temper.store.api.BbTicket;
import org.pragmatica.temper.store.api.NewFleetEvent;


/// The events one successful collection makes of the baseline and the new ticket list. It runs
/// inside the collection's transaction on the baseline that transaction read, so it is pure: no I/O, the same inputs
/// the same events.
@FunctionalInterface
public interface TicketDiff {
    /// No events, whatever changed: the collection round without the diff.
    TicketDiff NONE = (_, _, _, _) -> List.of();

    List<NewFleetEvent> events(String host, Instant now, List<BbTicket> tickets, Option<BbSnapshot> baseline);
}
