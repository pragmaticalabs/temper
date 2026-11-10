package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The Mac liveness tables: the latest post and every session any post has listed.
public interface MacStore {
    /// One accepted post in ONE transaction, at `post.receivedAt()` (temper's own clock, F13): the single `mac_post` row
    /// replaced; each process upserted into `mac_session` (`last_seen` and `label` refreshed on a conflict); sessions
    /// not seen for [#RETAIN_DAYS] days deleted; and the `mac` source marked seen at the same instant.
    Promise<Instant> post(NewMacPost post);
    /// The latest post, if any has been accepted.
    Promise<Option<MacPost>> latest();
    /// Every session row, by (pid, start).
    Promise<List<MacSession>> sessions();
    /// Retention of `mac_session` rows, as T1's `Fleet.RETAIN`.
    int RETAIN_DAYS = 30;
    /// The source's name in `fleet_source`, and its period; stale after 3 periods (F13).
    String SOURCE = "mac";
    int PERIOD_S = 60;
}
