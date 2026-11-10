package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.Sha;
import org.pragmatica.temper.domain.task.TaskId;


/// What a claim grants besides the row's own facts (m3-design §6.1, §6.5): the new lease token's sha256 and its holder,
/// the base tip the controller resolved for a task without a head (its read point, §6.5), and, for a directed claim of
/// a task that is not the head of its stage, the head it passed over (recorded on the `leased` event).
public record ClaimGrant(String tokenHash, String holder, Option<Sha> baseTip, Option<TaskId> passedOver) {
    public static ClaimGrant claimGrant(String tokenHash,
                                        String holder,
                                        Option<Sha> baseTip,
                                        Option<TaskId> passedOver) {
        return new ClaimGrant(tokenHash, holder, baseTip, passedOver);
    }

    /// Never prints the hash.
    @Override
    public String toString() {
        return "ClaimGrant[holder=" + holder + ", baseTip=" + baseTip + ", passedOver=" + passedOver + "]";
    }
}
