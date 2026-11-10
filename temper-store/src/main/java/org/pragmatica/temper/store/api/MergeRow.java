package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// A `merges` row to insert: `goId` is the gating GO (none for an ungated merge),
/// `route` is `temper-merge` or `kiln`, and `eventSeq` the seq of the fleet row that reported the merge.
public record MergeRow(MergeKey key,
                       String base,
                       Option<String> goId,
                       String route,
                       Option<String> mergedBy,
                       Option<Instant> mergedAt,
                       Option<Long> eventSeq) {
    public static MergeRow mergeRow(MergeKey key,
                                    String base,
                                    Option<String> goId,
                                    String route,
                                    Option<String> mergedBy,
                                    Option<Instant> mergedAt,
                                    Option<Long> eventSeq) {
        return new MergeRow(key, base, goId, route, mergedBy, mergedAt, eventSeq);
    }
}
