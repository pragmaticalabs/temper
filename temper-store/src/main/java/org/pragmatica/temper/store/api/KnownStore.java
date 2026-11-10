package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.known.KnownEntry;


/// The known-failure registry (spec §9, m2-design §4.1), the sibling of [RefusalStore] with its lifecycle: rows are
/// never deleted, and an expiry is kept once set. The active entries that mark a result record are read by the store
/// itself, inside the transaction that completes the job, so they are not a method here.
public interface KnownStore {
    /// Every row, active and expired, ordered by id; a row `KnownEntry` cannot read is listed as
    /// [KnownListing.Unreadable], never hidden (ruling R-M2-15).
    Promise<List<KnownListing>> list();
    /// Inserts an active entry. A `testId` with an active entry fails with [StoreError.KnownExists] (the API's 409); an
    /// expired entry for it does not.
    Promise<KnownRow> add(KnownEntry entry);
    /// `expired_at = now()`; an already expired entry keeps its first expiry. An unreadable row is expired too and
    /// answered as [KnownListing.Unreadable], so it can be removed. An unknown id fails with [StoreError.KnownNotFound].
    Promise<KnownListing> expire(long id);
}
