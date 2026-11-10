package org.pragmatica.temper.store.api;

/// The seqs one fleet insert committed: the lowest and the highest. The lowest is what the running restore check reads:
/// a seq handed out after the high-water mark was read is above it, unless a restore set the
/// sequence back, so a lowest seq at or below that mark is a seq used twice.
public record FleetSeqs(long first, long last) {
    public static FleetSeqs fleetSeqs(long first, long last) {
        return new FleetSeqs(first, last);
    }
}
