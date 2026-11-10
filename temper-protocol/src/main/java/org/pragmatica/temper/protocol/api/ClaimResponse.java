package org.pragmatica.temper.protocol.api;

import java.time.Instant;
import java.util.List;


/// `200` answer to a claim (m3-design §6.1): the task and its stage, visit and new epoch, the lease token (returned
/// once, never stored), the issued read point (§6.5), the round, the lease's TTL and step deadline, where to clone the
/// task's repository from (`temper task claim --checkout`, R-M3-24), and the context: checklist items, the rulings given
/// so far, the accepted evidence ids and the linked jobs.
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
                            List<TaskView.LinkedJob> jobs) {
    /// Never prints the token.
    @Override
    public String toString() {
        return "ClaimResponse[task=" + task + ", stage=" + stage + ", epoch=" + epoch + ", readPoint=" + readPoint + "]";
    }
}
