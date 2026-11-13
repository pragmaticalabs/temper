package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;


/// One stored checkpoint of a lane (`task_checkpoint`): what `POST tasks/{id}/checkpoint` recorded, and what a claim
/// of the task returns as `checkpoint` (the latest). `epoch` is the lease epoch that wrote it; `pushedSha` was reachable
/// from the task's branch at the controller's mirror when it was accepted.
public record CheckpointDto(int seq,
                            long epoch,
                            String pushedSha,
                            List<Item> checklist,
                            List<Entry> readMap,
                            String note,
                            Instant at) {
    /// A checklist line of the lane's own.
    public record Item(String item, boolean done) {}

    /// A file range a successor should read first.
    public record Entry(String path, String lines) {}
}
