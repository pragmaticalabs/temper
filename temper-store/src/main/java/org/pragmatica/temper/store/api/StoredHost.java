package org.pragmatica.temper.store.api;

/// A `hosts` row as the digest reads it: the link state the controller last stored (`DOWN`, `CONNECTING` or `UP`)
/// and how many of its units are GRANTED, UNKNOWN, RUNNING or CANCELLING (`UnitStore.active`).
public record StoredHost(HostName name, String state, int active) {
    public static StoredHost storedHost(HostName name, String state, int active) {
        return new StoredHost(name, state, active);
    }
}
