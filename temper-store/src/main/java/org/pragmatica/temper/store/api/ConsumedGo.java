package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;


/// A GO consumed: its row, the siblings the same transaction invalidated with
/// `sibling-consumed` (creation order), and the report fields of the verdict row it rests on.
public record ConsumedGo(String id,
                         GoBasis basis,
                         long verdictId,
                         Instant consumedAt,
                         List<InvalidatedGo> invalidated,
                         String reportPath,
                         String reportSha256) {
    public ConsumedGo {
        invalidated = List.copyOf(invalidated);
    }

    public static ConsumedGo consumedGo(String id,
                                        GoBasis basis,
                                        long verdictId,
                                        Instant consumedAt,
                                        List<InvalidatedGo> invalidated,
                                        String reportPath,
                                        String reportSha256) {
        return new ConsumedGo(id, basis, verdictId, consumedAt, invalidated, reportPath, reportSha256);
    }
}
