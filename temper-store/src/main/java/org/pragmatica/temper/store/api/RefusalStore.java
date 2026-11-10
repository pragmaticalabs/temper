package org.pragmatica.temper.store.api;

import java.util.List;

import org.pragmatica.lang.Promise;
import org.pragmatica.temper.domain.refusal.NewRefusal;
import org.pragmatica.temper.domain.refusal.RefusalRule;


/// Tree-specific refusals held as data (spec §2.5, m1-design §6).
public interface RefusalStore {
    /// Rows with `expired_at IS NULL`, ordered by code.
    Promise<List<RefusalRule>> active();
    /// Every row, active and expired, ordered by code.
    Promise<List<RefusalRow>> list();
    /// Inserts an active refusal. A code that exists, active or expired, fails with [StoreError.RefusalExists]
    /// (the API's 409): codes are never reused.
    Promise<RefusalRow> add(NewRefusal refusal);
    /// `expired_at = now()`; an already expired refusal keeps its first expiry. An unknown code fails with
    /// [StoreError.RefusalNotFound].
    Promise<RefusalRow> expire(String code);
}
