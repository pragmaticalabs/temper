package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// The grant policy's starvation state (m0-design §3.10): the blocked head unit and since when. Held in memory
/// by the controller; declared here because [GrantDecision] carries it.
public record Starvation(Option<Long> headUnitId, Option<Instant> blockedSince) {
    public static final Starvation NONE = new Starvation(Option.none(), Option.none());

    public static Starvation starvation(Option<Long> headUnitId, Option<Instant> blockedSince) {
        return new Starvation(headUnitId, blockedSince);
    }
}
