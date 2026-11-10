package org.pragmatica.temper.protocol.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.known.KnownEntry;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One `known` row in `GET /api/v1/known` (m2-design §4.4): its id, the test it covers, the issue, why, the free-text
/// end condition, who added it and when, and when it was expired (absent while active).
///
/// `unreadable` (ruling R-M2-15) is present for a stored row the domain cannot read: the other fields are then its
/// stored values as they are, and `unreadable` says why, the domain's reasons joined by `; ` [G]. Such a row never
/// marks a record; it is shown so that it is never invisible, and it can be expired.
public record KnownDto(long id,
                       String testId,
                       int issue,
                       String reason,
                       String until,
                       String createdBy,
                       Instant createdAt,
                       Option<Instant> expiredAt,
                       Option<String> unreadable) {
    public static KnownDto knownDto(long id, KnownEntry entry, Instant createdAt, Option<Instant> expiredAt) {
        return new KnownDto(id,
                            entry.testId(),
                            entry.issue(),
                            entry.reason(),
                            entry.until(),
                            entry.createdBy().value(),
                            createdAt,
                            expiredAt,
                            none());
    }

    public static KnownDto unreadableDto(long id,
                                         String testId,
                                         int issue,
                                         String reason,
                                         String until,
                                         String createdBy,
                                         Instant createdAt,
                                         Option<Instant> expiredAt,
                                         String problem) {
        return new KnownDto(id, testId, issue, reason, until, createdBy, createdAt, expiredAt, some(problem));
    }
}
