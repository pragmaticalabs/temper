package org.pragmatica.temper.store.api;

import java.time.Instant;


/// A stored fleet event: `epoch` is temper's epoch when it was written, `payload` a JSON object's text.
public record FleetEventRow(String epoch,
                            long seq,
                            Instant at,
                            Instant observedAt,
                            String kind,
                            String subject,
                            boolean catchUp,
                            String source,
                            String payload) {
    public static FleetEventRow fleetEventRow(String epoch,
                                              long seq,
                                              Instant at,
                                              Instant observedAt,
                                              String kind,
                                              String subject,
                                              boolean catchUp,
                                              String source,
                                              String payload) {
        return new FleetEventRow(epoch, seq, at, observedAt, kind, subject, catchUp, source, payload);
    }
}
