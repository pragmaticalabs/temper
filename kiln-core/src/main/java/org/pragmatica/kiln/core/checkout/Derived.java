package org.pragmatica.kiln.core.checkout;

import org.pragmatica.lang.Option;


/// What [Derivation#derive] gives for one checkout: its status, preservation, PR link and `atBase` (absent without
/// facts). The observation times of the inputs travel beside it, in the store's derived row (kiln-f1-design §2,
/// `checkout_derived`).
public record Derived(CheckoutStatus status, Preservation preservation, PrLink link, Option<Boolean> atBase) {}
