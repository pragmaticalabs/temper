package org.pragmatica.temper.store.api;

import java.time.Instant;
import java.util.List;


/// The latest stored post.
public record MacPost(Instant receivedAt, Instant postedAt, List<MacProcess> processes) {
    public static MacPost macPost(Instant receivedAt, Instant postedAt, List<MacProcess> processes) {
        return new MacPost(receivedAt, postedAt, List.copyOf(processes));
    }
}
