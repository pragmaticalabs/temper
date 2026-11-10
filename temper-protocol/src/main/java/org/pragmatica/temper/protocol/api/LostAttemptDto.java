package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// One lost attempt of a unit (m4-design §2.3, §11): a `unit_attempts` row, written when its host was LOST and the
/// attempt ended `ERROR(host-lost)` (requeued, or the unit ended). `startedAt` is absent for an attempt whose start was
/// never recorded.
public record LostAttemptDto(int attempt, String host, Instant grantedAt, Option<Instant> startedAt, Instant endedAt) {
    public static LostAttemptDto lostAttemptDto(int attempt,
                                                String host,
                                                Instant grantedAt,
                                                Option<Instant> startedAt,
                                                Instant endedAt) {
        return new LostAttemptDto(attempt, host, grantedAt, startedAt, endedAt);
    }
}
