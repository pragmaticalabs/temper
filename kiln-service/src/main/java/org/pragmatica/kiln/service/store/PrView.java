package org.pragmatica.kiln.service.store;

import java.util.List;

import org.pragmatica.kiln.core.checkout.CheckoutStatus;
import org.pragmatica.kiln.core.github.Cell;
import org.pragmatica.kiln.core.github.OpenDetail;
import org.pragmatica.kiln.core.github.PullRequest;
import org.pragmatica.kiln.core.github.RequiredChecks;
import org.pragmatica.kiln.core.model.CheckoutPath;
import org.pragmatica.kiln.core.model.HostName;
import org.pragmatica.kiln.core.model.Observed;
import org.pragmatica.kiln.core.temper.GateRead;
import org.pragmatica.kiln.service.mirror.BehindFacts;
import org.pragmatica.lang.Option;


/// An open PR's row (spec §6 panel 2), each fact with its observation time. The shape is WP1's, extended in WP5: a
/// base whose list was never read has no `required` (no observation time exists to show), each cell carries its own
/// time (ordered by context, then app id, an unpinned check first), and the linked checkouts (host:path, status) are
/// listed. `behind` and `gate` are those of the current head
/// only; a fact about an older head is not shown as this head's.
public record PrView(Observed<PullRequest> pr,
                     Option<Observed<OpenDetail>> detail,
                     Option<Observed<BehindFacts>> behind,
                     Option<Observed<RequiredChecks>> required,
                     List<Observed<Cell>> cells,
                     Option<Observed<GateRead>> gate,
                     List<Observed<LinkedCheckout>> checkouts) {
    public PrView {
        cells = List.copyOf(cells);
        checkouts = List.copyOf(checkouts);
    }

    /// A checkout linked to the PR by its derivation, observed at the derivation's time.
    public record LinkedCheckout(HostName host, CheckoutPath path, CheckoutStatus status) {}
}
