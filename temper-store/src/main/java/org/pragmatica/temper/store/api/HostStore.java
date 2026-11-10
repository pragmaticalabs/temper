package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.temper.domain.facts.HostFacts;


/// Configured worker hosts.
public interface HostStore {
    /// Inserts the host, or updates its configuration; `state` and `facts` are kept.
    Promise<Unit> upsert(HostConfig h);
    /// `DOWN`, `CONNECTING` or `UP`. Kept a String because §5.3 fixes this signature; the table's CHECK constraint
    /// rejects anything else as `StoreError.Database`, `LOST` included ([#lost] writes it). A LOST host keeps its
    /// state and mark: it leaves LOST only through [#recovered] (m4-design §2.5 step 5).
    Promise<Unit> state(HostName h, String state);
    /// `LOST` with its mark (m4-design §2.1). A repeat keeps the first mark, so `lost_at` is when the host became LOST.
    /// Refused with `StoreError.StaleLoss` when recovery already cleared this mark or a later one, so a write that
    /// commits late never makes a recovered host LOST again (§2.5).
    Promise<Unit> lost(HostName h, HostLoss loss);
    /// Recovery's UP write (m4-design §2.5 step 5): `UP`, the mark cleared and remembered as cleared, only while the
    /// host is LOST with `seen`; otherwise `StoreError.StaleLoss`.
    Promise<Unit> recovered(HostName h, HostLoss seen);
    /// The stored LOST mark; none when the host is not LOST (a LOST host stays LOST across a controller restart,
    /// m4-design §2.1).
    Promise<Option<HostLoss>> loss(HostName h);
    /// The latest facts and their sample time.
    Promise<Unit> facts(HostName h, HostFacts f);
}
