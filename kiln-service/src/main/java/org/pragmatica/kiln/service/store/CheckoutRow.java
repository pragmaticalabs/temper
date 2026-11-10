package org.pragmatica.kiln.service.store;

import java.time.Instant;

import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.scan.CheckoutFacts;
import org.pragmatica.lang.Option;


/// One `checkout` row. `facts` is absent until a pass gave full facts (`UNSCANNED`, Q-5), with the pass and time
/// that gave them; the size is absent until measured.
public record CheckoutRow(HostName host,
                          CheckoutPath path,
                          String commonDir,
                          Instant activityAt,
                          Option<CheckoutFacts> facts,
                          Option<PassKind> factsPass,
                          Option<Instant> factsAt,
                          Instant listedAt,
                          Option<Long> sizeKb,
                          Option<Instant> sizeAt) {}
