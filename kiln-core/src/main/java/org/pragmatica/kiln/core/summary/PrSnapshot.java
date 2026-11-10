package org.pragmatica.kiln.core.summary;

import java.util.Map;

import org.pragmatica.kiln.core.github.PrState;
import org.pragmatica.kiln.core.github.RequiredCheck;
import org.pragmatica.kiln.core.model.BranchName;
import org.pragmatica.kiln.core.model.PrNumber;
import org.pragmatica.kiln.core.model.Sha;


/// The observed values [Changes#between] compares: each PR's state, each PR's cell values by cell key
/// ([RequiredCheck#key]: `context`, or `context` U+001F `appId` when an app is pinned), so two cells of one context stay
/// apart, and each PR's head with its base.
public record PrSnapshot(Map<PrNumber, PrState> states,
                         Map<PrNumber, Map<String, String>> cells,
                         Map<PrNumber, Head> heads) {
    /// A PR's head SHA and the base it targets.
    public record Head(Sha sha, BranchName base) {}

    public PrSnapshot {
        states = Map.copyOf(states);
        cells = Map.copyOf(cells);
        heads = Map.copyOf(heads);
    }
}
