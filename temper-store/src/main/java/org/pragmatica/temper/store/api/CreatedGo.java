package org.pragmatica.temper.store.api;

import java.time.Instant;


/// A GO row inserted: its id and `created_at` (database clock; it expires 10 minutes later).
public record CreatedGo(String id, Instant createdAt) {
    public static CreatedGo createdGo(String id, Instant createdAt) {
        return new CreatedGo(id, createdAt);
    }
}
