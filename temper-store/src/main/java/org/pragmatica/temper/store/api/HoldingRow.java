package org.pragmatica.temper.store.api;

import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.MarkerEpoch;


/// A unit waiting in bb-hold as reported by `UnitHolding` (m1-design §1.3): its marker (written before the hold)
/// and the host facts at that moment, which become its `unit_facts` phase `hold`.
public record HoldingRow(MarkerEpoch marker, HostFacts facts) {
    public static HoldingRow holdingRow(MarkerEpoch marker, HostFacts facts) {
        return new HoldingRow(marker, facts);
    }
}
