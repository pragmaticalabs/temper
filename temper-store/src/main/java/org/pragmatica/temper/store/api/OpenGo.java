package org.pragmatica.temper.store.api;

/// An outstanding GO (not consumed, not invalidated, created less than 10 minutes ago), as the
/// `t2:detector` reads it under the T2 lock.
public record OpenGo(String id, GoBasis basis) {
    public static OpenGo openGo(String id, GoBasis basis) {
        return new OpenGo(id, basis);
    }
}
