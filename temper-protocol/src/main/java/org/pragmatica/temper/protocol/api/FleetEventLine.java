package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import tools.jackson.databind.JsonNode;


/// One fleet event line, NDJSON, fields in the order written there. `payload` is a JSON object.
@JsonPropertyOrder({"v", "epoch", "seq", "at", "observedAt", "kind", "subject", "catchUp", "source", "payload"})
public record FleetEventLine(int v,
                             String epoch,
                             long seq,
                             Instant at,
                             Instant observedAt,
                             String kind,
                             String subject,
                             boolean catchUp,
                             String source,
                             JsonNode payload) {
    public static final int VERSION = 1;

    public static FleetEventLine fleetEventLine(String epoch,
                                                long seq,
                                                Instant at,
                                                Instant observedAt,
                                                String kind,
                                                String subject,
                                                boolean catchUp,
                                                String source,
                                                JsonNode payload) {
        return new FleetEventLine(VERSION, epoch, seq, at, observedAt, kind, subject, catchUp, source, payload);
    }
}
