package org.pragmatica.kiln.service.api;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.pragmatica.kiln.service.store.ChangeFeed;
import org.pragmatica.lang.vo.Uuid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// CI interface T1: `reset` when the caller's epoch is not kiln's, else `gap` when `after < floor - 1`, else listed.
class FeedReplyTest {
    private static final Uuid KILN = Uuid.uuid(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final Uuid OTHER = Uuid.uuid(UUID.fromString("00000000-0000-0000-0000-000000000002"));

    private static ChangeFeed feed(long floor, long head) {
        return new ChangeFeed(KILN, floor, head, List.of(), none());
    }

    @Test
    void reply_isAReset_onlyForAnotherEpoch() {
        assertThat(FeedReply.reply(some(OTHER), 0, feed(1, 3))).isEqualTo(new FeedReply.Reset(feed(1, 3)));
        assertThat(FeedReply.reply(some(KILN), 0, feed(1, 3))).isEqualTo(new FeedReply.Listed(feed(1, 3)));
        assertThat(FeedReply.reply(none(), 0, feed(1, 3))).isEqualTo(new FeedReply.Listed(feed(1, 3)));
    }

    /// The reset wins over a gap: a cursor from another epoch means nothing against this floor.
    @Test
    void reply_resetsBeforeItJudgesTheFloor() {
        assertThat(FeedReply.reply(some(OTHER), 0, feed(10, 12))).isInstanceOf(FeedReply.Reset.class);
    }

    @Test
    void reply_isAGap_exactlyBelowFloorMinusOne() {
        assertThat(FeedReply.reply(some(KILN), 2, feed(4, 5))).isEqualTo(new FeedReply.Gap(2, feed(4, 5)));
        assertThat(FeedReply.reply(none(), 0, feed(4, 5))).isEqualTo(new FeedReply.Gap(0, feed(4, 5)));
        assertThat(FeedReply.reply(none(), 3, feed(4, 5))).isEqualTo(new FeedReply.Listed(feed(4, 5)));
        assertThat(FeedReply.reply(none(), 9, feed(4, 5))).isEqualTo(new FeedReply.Listed(feed(4, 5)));
    }

    /// A never-written table (head 0, floor 1) is never a gap.
    @Test
    void reply_ofAnEmptyFeed_isListed() {
        assertThat(FeedReply.reply(none(), 0, feed(1, 0))).isEqualTo(new FeedReply.Listed(feed(1, 0)));
        assertThat(FeedReply.reply(none(), 7, feed(1, 0))).isEqualTo(new FeedReply.Listed(feed(1, 0)));
    }

    /// Every row pruned after id 5 (head 5, floor 6): a cursor behind them is a gap, one at the head is not.
    @Test
    void reply_ofAFullyPrunedFeed_keepsTheGap() {
        assertThat(FeedReply.reply(none(), 2, feed(6, 5))).isEqualTo(new FeedReply.Gap(2, feed(6, 5)));
        assertThat(FeedReply.reply(none(), 5, feed(6, 5))).isEqualTo(new FeedReply.Listed(feed(6, 5)));
    }
}
