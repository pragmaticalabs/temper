package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// A producer's last success as reported (kiln's `sources`, a link UP, a passed self-check): written to
/// `fleet_source.last_ok_at`/`period_s`, the row created when absent. An absent `lastOkAt` leaves the stored one.
public record SourceSeen(String name, Option<Instant> lastOkAt, int periodS) {
    public static SourceSeen sourceSeen(String name, Option<Instant> lastOkAt, int periodS) {
        return new SourceSeen(name, lastOkAt, periodS);
    }
}
