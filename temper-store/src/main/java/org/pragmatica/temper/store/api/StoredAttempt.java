package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.domain.state.UnitState;


/// A unit's current attempt as the store has it ([UnitStore#attempts], m4-design §4.5): `attempt` 0 for a unit never
/// granted; `host` none while it is QUEUED or was never granted.
public record StoredAttempt(UnitRef ref, int attempt, Option<HostName> host, UnitState state) {
    public static StoredAttempt storedAttempt(UnitRef ref, int attempt, Option<HostName> host, UnitState state) {
        return new StoredAttempt(ref, attempt, host, state);
    }
}
