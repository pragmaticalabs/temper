package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.kiln.core.checkout.Derived;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.lang.Option;


/// One `checkout_derived` row: the derivation with the observation times of the inputs it used, so a status always
/// shows the age of the fact it came from (spec §5.6).
public record DerivedRow(CheckoutPath path,
                         Derived derived,
                         Option<Instant> factsAt,
                         Option<Instant> mirrorAt,
                         Option<Instant> prsAt,
                         Instant derivedAt) {}
