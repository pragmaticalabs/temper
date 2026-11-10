package org.pragmatica.kiln.service.store;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.checkout.Derived;
import org.pragmatica.lang.Option;


/// A checkout with its derivation, absent until derived. The shape is WP1's; WP5 and WP10 may extend it.
public record CheckoutView(CheckoutRow row, Option<DerivedRow> derived) {
    /// The status a page files the checkout under, as the store's counts do (`CheckoutRows.STATUS`): the derived
    /// status, and `UNSCANNED` while the row has no full facts (Q-5) or no derivation yet. Added in WP10.
    public CheckoutStatus status() {
        return row.factsAt()
                  .flatMap(_ -> derived)
                  .map(DerivedRow::derived)
                  .map(Derived::status)
                  .or(CheckoutStatus.UNSCANNED);
    }
}
