package org.pragmatica.temper.controller.host;

import org.pragmatica.temper.domain.model.UnitRef;


/// One attempt of a unit: the unit and its attempt number, the store's grant count (`Granted.attempt`, m4-design §4.1).
/// A requeued unit is granted again as the next attempt, so what this controller knows about one attempt (its start ran
/// unrecorded, its UNKNOWN mark) never applies to the next one (verification F4).
public record UnitAttempt(UnitRef ref, int attempt) {
    public static UnitAttempt unitAttempt(UnitRef ref, int attempt) {
        return new UnitAttempt(ref, attempt);
    }
}
