package org.pragmatica.temper.protocol.api;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.refusal.Refusal;


/// `422` answer to a refused submit. `job` is the persisted REFUSED job's id (Q-16).
public record RefusedResponse(String refused, String detail, Option<Long> job) {
    public static RefusedResponse refusedResponse(Refusal refusal, Option<Long> job) {
        return new RefusedResponse(refusal.code(), refusal.detail(), job);
    }
}
