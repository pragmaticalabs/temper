package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.kiln.core.feed.BaseFeed;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.Sha;


/// One `base_tip` row (kiln V6): a configured base's tip as the poller stored it, its tree, and
/// when that tip was stored.
public record BaseTip(BranchName base, Sha sha, Sha tree, Instant seenAt) {
    /// The tip the base-move walk starts from.
    public BaseFeed.Tip tip() {
        return new BaseFeed.Tip(sha, tree);
    }
}
