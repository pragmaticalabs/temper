package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// A `unit_attempts` row (m4-design §2.3): an attempt that ended `ERROR(host-lost)` when its host was LOST, with the
/// host, its grant and start (none when never recorded) and when the settle ended it.
public record LostAttempt(int attempt, HostName host, Instant grantedAt, Option<Instant> startedAt, Instant endedAt) {
    public static LostAttempt lostAttempt(int attempt,
                                          HostName host,
                                          Instant grantedAt,
                                          Option<Instant> startedAt,
                                          Instant endedAt) {
        return new LostAttempt(attempt, host, grantedAt, startedAt, endedAt);
    }
}
