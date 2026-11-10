package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// One producer's `fleet_source` row (design §2.5). Every producer has `lastOkAt`, `periodS` and the last announced
/// `stale` verdict; the kiln row also carries the pull state (`epoch`, `cursor`, `generation`, `catchUpTo`, `head`),
/// which no other producer uses.
public record FleetSourceRow(String name,
                             Option<String> epoch,
                             long cursor,
                             Option<Instant> lastOkAt,
                             int periodS,
                             int generation,
                             long catchUpTo,
                             long head,
                             boolean stale) {
    public static FleetSourceRow fleetSourceRow(String name,
                                                Option<String> epoch,
                                                long cursor,
                                                Option<Instant> lastOkAt,
                                                int periodS,
                                                int generation,
                                                long catchUpTo,
                                                long head,
                                                boolean stale) {
        return new FleetSourceRow(name, epoch, cursor, lastOkAt, periodS, generation, catchUpTo, head, stale);
    }

    /// A producer never seen: no epoch, cursor 0, never ok.
    public static FleetSourceRow unseen(String name, int periodS) {
        return new FleetSourceRow(name, Option.none(), 0, Option.none(), periodS, 0, 0, 0, false);
    }
}
