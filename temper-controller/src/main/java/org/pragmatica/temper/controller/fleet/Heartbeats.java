package org.pragmatica.temper.controller.fleet;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.protocol.api.HeartbeatLine;
import org.pragmatica.temper.store.api.FleetHead;
import org.pragmatica.temper.store.api.FleetSourceRow;
import org.pragmatica.temper.store.api.FleetStore;


/// The heartbeat line as of now: the epoch and head from the store, every `fleet_source` row with
/// its staleness computed at reply time from ages only, the latest self-check, and `kilnLag` (kiln's head at the last
/// pull minus the cursor).
public record Heartbeats(FleetStore store,
                         AtomicReference<HeartbeatLine.SelfCheck> selfCheck,
                         Supplier<Instant> clock,
                         BooleanSupplier rotationPending) {
    /// `rotationPending`: whether a restore rotation is pending ([FleetWriter#rotationPending]); `selfcheck:highwater`
    /// then reads stale.
    public static Heartbeats heartbeats(FleetStore store, Supplier<Instant> clock, BooleanSupplier rotationPending) {
        return new Heartbeats(store, new AtomicReference<>(SelfCheck.NOT_RUN), clock, rotationPending);
    }

    public Promise<HeartbeatLine> now() {
        return Promise.all(store.head(),
                           store.sources())
                      .map((head, sources) -> line(head,
                                                   sources,
                                                   selfCheck.get(),
                                                   clock.get(),
                                                   rotationPending.getAsBoolean()));
    }

    static HeartbeatLine line(FleetHead head,
                              List<FleetSourceRow> rows,
                              HeartbeatLine.SelfCheck check,
                              Instant now,
                              boolean rotationPending) {
        return HeartbeatLine.heartbeatLine(head.epoch(),
                                           now,
                                           head.seq(),
                                           sources(rows, now, rotationPending),
                                           check,
                                           kilnLag(rows));
    }

    private static Map<String, HeartbeatLine.Source> sources(List<FleetSourceRow> rows,
                                                             Instant now,
                                                             boolean rotationPending) {
        var sources = new LinkedHashMap<String, HeartbeatLine.Source>();

        rows.forEach(row -> sources.put(row.name(),
                                        HeartbeatLine.Source.source(row.lastOkAt(),
                                                                    row.periodS(),
                                                                    Staleness.stale(row, now, rotationPending))));

        return sources;
    }

    private static long kilnLag(List<FleetSourceRow> rows) {
        return Option.from(rows.stream().filter(row -> row.name()
                                                          .equals(KilnPlan.KILN)).findFirst())
                     .map(kiln -> Math.max(0,
                                           kiln.head() - kiln.cursor()))
                     .or(0L);
    }
}
