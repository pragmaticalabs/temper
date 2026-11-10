package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;


/// What one `t2:detector` batch writes, in one transaction: the GOs it invalidates, the `merges`
/// rows, the PRs leaving their queue (each removal shifts the later positions down by one, in list order), the new
/// cursor with the batch's `last_ok_at`, and the events, inserted last under the order lock.
public record DetectorBatch(long cursor,
                            Instant lastOkAt,
                            List<GoInvalidation> invalidated,
                            List<MergeRow> merges,
                            List<QueueEntry> dequeued,
                            List<NewFleetEvent> events) {
    public DetectorBatch {
        invalidated = List.copyOf(invalidated);
        merges = List.copyOf(merges);
        dequeued = List.copyOf(dequeued);
        events = List.copyOf(events);
    }

    public static DetectorBatch detectorBatch(long cursor,
                                              Instant lastOkAt,
                                              List<GoInvalidation> invalidated,
                                              List<MergeRow> merges,
                                              List<QueueEntry> dequeued,
                                              List<NewFleetEvent> events) {
        return new DetectorBatch(cursor, lastOkAt, invalidated, merges, dequeued, events);
    }

    /// A batch that invalidates no GO.
    public static DetectorBatch detectorBatch(long cursor,
                                              Instant lastOkAt,
                                              List<MergeRow> merges,
                                              List<QueueEntry> dequeued,
                                              List<NewFleetEvent> events) {
        return new DetectorBatch(cursor, lastOkAt, List.of(), merges, dequeued, events);
    }
}
