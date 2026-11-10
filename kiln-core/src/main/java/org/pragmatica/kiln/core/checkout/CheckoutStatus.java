package org.pragmatica.kiln.core.checkout;

/// A checkout's status (spec §5.3, rules 1–7 in this order), plus [#UNSCANNED]: no full-facts observation from any
/// pass yet (Q-5), never a status of its own.
public enum CheckoutStatus {
    ACTIVE,
    IN_USE,
    REMOVABLE,
    BASE_CLEAN,
    NEEDS_SALVAGE,
    STALE_UNOWNED,
    UNOWNED,
    UNSCANNED
}
