package org.pragmatica.temper.protocol.api;

import java.util.List;

import org.pragmatica.lang.Option;


/// Bodies of the lease-token calls (m3-design §6.4, §11.1). Each names the epoch its caller holds; the controller accepts
/// it only where the task is LEASED at that epoch under the presented token (`Authorization: Lease <token>`), and
/// answers `409 lease-lost` otherwise.
public sealed interface LeaseCall {
    long epoch();

    /// `POST tasks/{id}/heartbeat`; the optional `report` is the agent's self-reported tree (head, dirty, unpushed).
    record Heartbeat(long epoch, Option<Tree> report) implements LeaseCall {
        public Heartbeat(long epoch) {
            this(epoch, Option.none());
        }
    }

    /// The tree an agent reports at a heartbeat: HEAD sha, changed files, commits not pushed.
    record Tree(String head, int dirty, int unpushed) {}

    /// `POST tasks/{id}/checkpoint`: the pushed head, the lane's checklist and read map, and a note; at most 16 KB on the wire.
    record Checkpoint(long epoch,
                      String pushedSha,
                      List<CheckpointDto.Item> checklist,
                      List<CheckpointDto.Entry> readMap,
                      String note) implements LeaseCall {}

    /// `POST tasks/{id}/wait`: linked jobs, CI, or both.
    record Wait(long epoch, Option<List<Long>> jobs, Option<Boolean> ci) implements LeaseCall {}

    /// `POST tasks/{id}/release`.
    record Release(long epoch, String reason) implements LeaseCall {}
}
