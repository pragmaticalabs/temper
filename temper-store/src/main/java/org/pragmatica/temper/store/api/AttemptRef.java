package org.pragmatica.temper.store.api;

import org.pragmatica.temper.domain.model.UnitRef;


/// One attempt of a unit (m4-design §4.1): `attempt` is `units.attempt` as the grant that started it wrote it
/// ([Granted#attempt]). A write scoped to it is never applied to another attempt of the same unit.
public record AttemptRef(UnitRef ref, int attempt) {
    public static AttemptRef attemptRef(UnitRef ref, int attempt) {
        return new AttemptRef(ref, attempt);
    }
}
