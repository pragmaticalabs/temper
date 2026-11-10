package org.pragmatica.kiln.service.store;

import java.time.Instant;
import java.util.Map;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.lang.Option;


/// A host's line of counts per status and its known total size (spec §6 panel 4), with the oldest `facts_at` the
/// counts rest on (§3.4). The shape is WP1's; WP5 and WP10 may extend it. `knownSizeKb` sums the measured rows only;
/// `measuredRows` counts them (WP10), so an unmeasured host never reads as 0 KiB.
public record HostCounts(HostName host,
                         Map<CheckoutStatus, Integer> counts,
                         long knownSizeKb,
                         int measuredRows,
                         Option<Instant> oldestFactsAt) {
    public HostCounts {
        counts = Map.copyOf(counts);
    }
}
