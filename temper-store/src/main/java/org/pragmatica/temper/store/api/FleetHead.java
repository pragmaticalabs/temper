package org.pragmatica.temper.store.api;

/// temper's epoch, the stream's head `GREATEST(max(seq), floor)`, and the highest value `fleet_events_seq_seq` has
/// handed out (0 when never used). A restore resets all three; a rolled-back insert moves the sequence only.
public record FleetHead(String epoch, long seq, long sequence) {
    public static FleetHead fleetHead(String epoch, long seq, long sequence) {
        return new FleetHead(epoch, seq, sequence);
    }

    /// The highest seq the database has reached: committed, pruned, or handed out by the sequence. It decides only
    /// whether the stream was used ([#used]); a restore check compares a mark with [#seq] alone: a mark is always
    /// a committed seq, and a sequence value handed out to an insert in flight when a dump was taken could hide a
    /// restore of that dump.
    public long reached() {
        return Math.max(seq, sequence);
    }

    /// Whether the stream was ever written (a row, a floor, or a used sequence).
    public boolean used() {
        return reached() > 0;
    }
}
