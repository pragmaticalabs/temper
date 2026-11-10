package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.known.KnownEntry;


/// A `known` row (spec §9, m2-design §4.2): its generated id, the entry (test, issue, reason, until, created by), when
/// it was added, and when it was expired (none while active).
public record KnownRow(long id, KnownEntry entry, Instant createdAt, Option<Instant> expiredAt) {
    public static KnownRow knownRow(long id, KnownEntry entry, Instant createdAt, Option<Instant> expiredAt) {
        return new KnownRow(id, entry, createdAt, expiredAt);
    }
}
