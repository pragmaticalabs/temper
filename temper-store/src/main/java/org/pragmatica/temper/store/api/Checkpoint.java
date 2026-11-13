package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;


/// A `task_checkpoint` row: version `seq` of the task's lane state, written under lease epoch `epoch`, for a head
/// `pushedSha` that was on the task's branch at the remote when it was accepted.
public record Checkpoint(int seq,
                         long epoch,
                         String pushedSha,
                         List<Item> checklist,
                         List<Entry> readMap,
                         String note,
                         Instant at) {
    public Checkpoint {
        checklist = List.copyOf(checklist);
        readMap = List.copyOf(readMap);
    }

    /// A checklist line of the lane's own.
    public record Item(String item, boolean done) {}

    /// A file range a successor should read first.
    public record Entry(String path, String lines) {}

    /// The body of a checkpoint before the store numbers it.
    public record New(String pushedSha, List<Item> checklist, List<Entry> readMap, String note) {
        public New {
            checklist = List.copyOf(checklist);
            readMap = List.copyOf(readMap);
        }
    }
}
