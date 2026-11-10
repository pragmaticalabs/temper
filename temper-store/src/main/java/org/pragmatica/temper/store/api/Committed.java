package org.pragmatica.temper.store.api;

import org.pragmatica.lang.Option;


/// What a writing transaction that also inserted fleet events committed: its own `value` and the lowest and highest seq
/// it inserted (none when it inserted no event), which the fleet writer's restore check reads.
public record Committed<T>(T value, Option<FleetSeqs> seqs) {
    public static <T> Committed<T> committed(T value, Option<FleetSeqs> seqs) {
        return new Committed<>(value, seqs);
    }
}
