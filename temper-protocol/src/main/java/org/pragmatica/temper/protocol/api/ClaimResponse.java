package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;


/// `200` answer to a claim (m3-design §6.1): the task and its stage, visit and new epoch, the lease token (returned
/// once, never stored), the issued read point (§6.5), the round, the lease's TTL and step deadline, where to clone the
/// task's repository from (`temper task claim --checkout`, R-M3-24), and the context: checklist items, the rulings given
/// so far, the accepted evidence ids and the linked jobs. `checkpoint` (the lane's latest) and `warnings` (e.g.
/// `dirty-predecessor`) are left OUT of the JSON when absent or empty, so a claim of a task without either reads
/// byte-for-byte as before, for an old strict client too.
public record ClaimResponse(long task,
                            String slug,
                            String stage,
                            int visit,
                            long epoch,
                            String leaseToken,
                            String readPoint,
                            int round,
                            Instant leaseUntil,
                            Instant deadline,
                            String repo,
                            String cloneUrl,
                            List<TaskView.Item> items,
                            List<TaskView.RulingDto> rulings,
                            List<Long> evidence,
                            List<TaskView.LinkedJob> jobs,
                            @JsonInclude(JsonInclude.Include.NON_NULL) CheckpointDto checkpoint,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {
    public ClaimResponse {
        warnings = warnings == null
                   ? List.of()
                   : List.copyOf(warnings);
    }

    /// Never prints the token.
    @Override
    public String toString() {
        return "ClaimResponse[task=" + task + ", stage=" + stage + ", epoch=" + epoch + ", readPoint=" + readPoint + "]";
    }
}
