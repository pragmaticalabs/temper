package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;


/// The grant policy's answer (m0-design §3.10): the unit to grant, if any, and the next starvation state.
public record GrantDecision(Option<Long> grant, Starvation next) {
    public static GrantDecision grantDecision(Option<Long> grant, Starvation next) {
        return new GrantDecision(grant, next);
    }
}
