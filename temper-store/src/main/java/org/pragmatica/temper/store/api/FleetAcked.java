package org.pragmatica.temper.store.api;

/// What one fleet ack found (lead ruling F2), read in the same statement that moved the cursor or
/// did not: the current epoch, the DB head, and whether the subscriber's cursor was updated.
public record FleetAcked(String epoch, long head, boolean moved) {
    public static FleetAcked fleetAcked(String epoch, long head, boolean moved) {
        return new FleetAcked(epoch, head, moved);
    }
}
