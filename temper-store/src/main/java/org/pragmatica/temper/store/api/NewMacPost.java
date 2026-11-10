package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;


/// An accepted post: `receivedAt` is temper's clock, `postedAt` the Mac's claim (shown, never compared).
public record NewMacPost(Instant receivedAt, Instant postedAt, List<MacProcess> processes) {
    public static NewMacPost newMacPost(Instant receivedAt, Instant postedAt, List<MacProcess> processes) {
        return new NewMacPost(receivedAt, postedAt, List.copyOf(processes));
    }
}
