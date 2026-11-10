package org.pragmatica.temper.store.api;

import java.time.Instant;


/// One `mac_session` row: `(pid, start)` is the identity, so a reused pid is another session.
public record MacSession(int pid, long start, String label, Instant firstSeen, Instant lastSeen) {
    public static MacSession macSession(int pid, long start, String label, Instant firstSeen, Instant lastSeen) {
        return new MacSession(pid, start, label, firstSeen, lastSeen);
    }
}
