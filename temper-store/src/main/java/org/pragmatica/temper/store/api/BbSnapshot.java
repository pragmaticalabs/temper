package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;


/// The latest successful ticket collection of one host: the diff baseline, and what
/// `GET fleet/tickets` lists. `tickets` are in `bb-tickets` output order. `hostAt` is the listing's trailer `at` (host
/// clock) and `skewS` the round's measured skew; both none for a row written before S1.
public record BbSnapshot(String host,
                         Instant takenAt,
                         Option<String> queue,
                         List<BbTicket> tickets,
                         Option<Instant> hostAt,
                         Option<Integer> skewS) {
    /// `queue` = the `bb-tickets` trailer's `queue` (`present` | `absent`), none for a row stored without one.
    public static BbSnapshot bbSnapshot(String host, Instant takenAt, Option<String> queue, List<BbTicket> tickets) {
        return bbSnapshot(host, takenAt, queue, tickets, Option.none(), Option.none());
    }

    public static BbSnapshot bbSnapshot(String host,
                                        Instant takenAt,
                                        Option<String> queue,
                                        List<BbTicket> tickets,
                                        Option<Instant> hostAt,
                                        Option<Integer> skewS) {
        return new BbSnapshot(host, takenAt, queue, List.copyOf(tickets), hostAt, skewS);
    }
}
