package org.pragmatica.kiln.core.checkout;

import java.util.Set;

import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;


/// A checkout HEAD's ancestry in the mirror, computed by the mirror adapter (kiln-f1-design §3.3): whether HEAD is in
/// the mirror and at a configured base, the PRs whose head contains HEAD, and HEAD's commits on no base.
public record Ancestry(boolean headInMirror,
                       boolean atBase,
                       Set<PrNumber> headsContainingHead,
                       Set<Sha> offBaseCommitsOfHead) {
    public Ancestry {
        headsContainingHead = Set.copyOf(headsContainingHead);
        offBaseCommitsOfHead = Set.copyOf(offBaseCommitsOfHead);
    }
}
