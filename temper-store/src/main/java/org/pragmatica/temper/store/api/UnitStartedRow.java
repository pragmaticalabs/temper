package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.model.MarkerEpoch;


/// A unit's start as reported by `UnitStarted`; `facts` become its `unit_facts` phase `start`. `ticket` is the
/// bb-* ticket of a hold unit, carried by `UnitStarted.ticket` (protocol v2); none for a direct unit.
public record UnitStartedRow(String containerId,
                             String imageId,
                             MarkerEpoch marker,
                             Instant startedAt,
                             HostFacts facts,
                             Option<String> ticket) {
    public static UnitStartedRow unitStartedRow(String containerId,
                                                String imageId,
                                                MarkerEpoch marker,
                                                Instant startedAt,
                                                HostFacts facts,
                                                Option<String> ticket) {
        return new UnitStartedRow(containerId, imageId, marker, startedAt, facts, ticket);
    }
}
