package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;


/// A `known` row as `known list` and an expiry show it (ruling R-M2-15): an entry that can affect a verdict is never
/// invisible. A row `KnownEntry` reads is [Readable]; one it cannot read (only a hand edit or an older schema makes one,
/// V8's CHECKs being its rules) is [Unreadable], with its stored values as they are and why it does not read.
public sealed interface KnownListing {
    long id();
    Option<Instant> expiredAt();

    static KnownListing readable(KnownRow row) {
        return new Readable(row);
    }

    record Readable(KnownRow row) implements KnownListing {
        @Override
        public long id() {
            return row.id();
        }

        @Override
        public Option<Instant> expiredAt() {
            return row.expiredAt();
        }
    }

    /// The stored columns, unvalidated, and `problem`: the domain's reasons for refusing them, joined by `; `.
    record Unreadable(long id,
                      String testId,
                      int issue,
                      String reason,
                      String until,
                      String createdBy,
                      Instant createdAt,
                      Option<Instant> expiredAt,
                      String problem) implements KnownListing {
        public static Unreadable unreadable(long id,
                                            String testId,
                                            int issue,
                                            String reason,
                                            String until,
                                            String createdBy,
                                            Instant createdAt,
                                            Option<Instant> expiredAt,
                                            String problem) {
            return new Unreadable(id, testId, issue, reason, until, createdBy, createdAt, expiredAt, problem);
        }
    }
}
