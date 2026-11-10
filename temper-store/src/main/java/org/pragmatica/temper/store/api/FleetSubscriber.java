package org.pragmatica.temper.store.api;

/// A fleet subscriber: its cursor and the id of the token it is bound to (R2-f).
public record FleetSubscriber(String name, long cursor, String tokenId) {
    public static FleetSubscriber fleetSubscriber(String name, long cursor, String tokenId) {
        return new FleetSubscriber(name, cursor, tokenId);
    }
}
