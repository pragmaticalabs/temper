package org.pragmatica.temper.controller.fleet;

import java.time.Duration;
import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.store.api.FleetSourceRow;


/// Staleness by age, for every producer (F13): `stale = now − lastOkAt > 3 × periodS`, or never
/// ok, or a `lastOkAt` more than [#FUTURE_SLACK_S] s in the future (a clock anomaly). No state word and no connection error is read: a kiln whose GitHub poller died still answers, and its forwarded
/// `kiln:github` success ages into staleness.
public sealed interface Staleness {
    long FUTURE_SLACK_S = 60;

    static boolean stale(Option<Instant> lastOkAt, int periodS, Instant now) {
        return lastOkAt.map(last -> stale(last, periodS, now))
                       .or(true);
    }

    private static boolean stale(Instant last, int periodS, Instant now) {
        return Duration.between(last, now).compareTo(Duration.ofSeconds(3L * periodS)) > 0 || Duration.between(now, last).compareTo(Duration.ofSeconds(FUTURE_SLACK_S)) > 0;
    }

    static boolean stale(FleetSourceRow row, Instant now) {
        return stale(row.lastOkAt(), row.periodS(), now);
    }

    /// As [#stale(FleetSourceRow, Instant)], and `selfcheck:highwater` is stale while a rotation is pending
    /// ([FleetWriter#rotationPending]): the high-water file is then not to be trusted, whatever its last write.
    static boolean stale(FleetSourceRow row, Instant now, boolean rotationPending) {
        return stale(row, now) || rotationPending && row.name()
                                                        .equals(Fleet.HIGHWATER);
    }

    record unused() implements Staleness {}
}
