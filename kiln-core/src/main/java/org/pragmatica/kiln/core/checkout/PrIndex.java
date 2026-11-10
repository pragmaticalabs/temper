package org.pragmatica.kiln.core.checkout;

import java.util.Collection;
import java.util.List;

import org.pragmatica.kiln.core.github.PullRequest;


/// The **full** PR table a checkout links against, with forks excluded (spec §5.3, K-13).
// Accepted gap: a record cannot hide its canonical constructor, so `new PrIndex(...)` bypasses the fork filter.
public record PrIndex(List<PullRequest> prs) {
    public PrIndex {
        prs = List.copyOf(prs);
    }

    /// Every PR of `all` whose head lives in `baseRepo`, whatever its state or age: a window would let an old merged
    /// PR's checkout read as unowned (K-13).
    public static PrIndex prIndex(Collection<PullRequest> all, String baseRepo) {
        return new PrIndex(all.stream().filter(pr -> pr.sameRepo(baseRepo)).toList());
    }
}
