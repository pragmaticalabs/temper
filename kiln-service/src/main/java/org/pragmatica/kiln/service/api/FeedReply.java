package org.pragmatica.kiln.service.api;

import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.vo.Uuid;


/// The three answers of `GET /api/v1/changes`: `reset` when the caller's epoch is not kiln's (the
/// database was rebuilt, review H4), else `gap` when rows the caller has not read were pruned (`after < floor - 1`),
/// else the listed changes. Without an epoch the caller holds none to compare, so it is never told to reset.
public sealed interface FeedReply {
    ChangeFeed feed();

    record Listed(ChangeFeed feed) implements FeedReply {}

    record Reset(ChangeFeed feed) implements FeedReply {}

    /// The ids above `from` and below the floor are gone.
    record Gap(long from, ChangeFeed feed) implements FeedReply {}

    /// Condition: the epoch decides first, then the floor.
    static FeedReply reply(Option<Uuid> epoch, long after, ChangeFeed feed) {
        return epoch.filter(held -> !held.equals(feed.epoch()))
                    .isPresent()
               ? new Reset(feed)
               : gapOrListed(after, feed);
    }

    private static FeedReply gapOrListed(long after, ChangeFeed feed) {
        return after < feed.floor() - 1
               ? new Gap(after, feed)
               : new Listed(feed);
    }
}
