package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.pragmatica.lang.Option;


/// Every work package's current state ([WorkCard]), oldest submission first.
public record WorkBoard(List<WorkCard> cards) {
    /// The board of an empty ledger.
    public static final WorkBoard EMPTY = new WorkBoard(List.of());

    public WorkBoard {
        cards = List.copyOf(cards);
    }

    /// The ledger's freshness: the time of its last event, none while it holds no event. Every event belongs to an
    /// item and each card carries its item's latest one, so the newest card time is the newest event time.
    public Option<Instant> lastEventAt() {
        return Option.from(cards.stream().map(WorkCard::at).max(Comparator.naturalOrder()));
    }
}
