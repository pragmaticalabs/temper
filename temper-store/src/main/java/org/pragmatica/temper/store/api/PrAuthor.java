package org.pragmatica.temper.store.api;

import java.time.Instant;


/// One lane of a PR's author set.
public record PrAuthor(String lane, String addedBy, Instant addedAt) {
    public static PrAuthor prAuthor(String lane, String addedBy, Instant addedAt) {
        return new PrAuthor(lane, addedBy, addedAt);
    }
}
