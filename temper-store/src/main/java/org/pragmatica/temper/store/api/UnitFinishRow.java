package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.temper.domain.facts.UnitEndFacts;
import org.pragmatica.temper.domain.verdict.KillCause;


/// How a unit ended. `rc` is absent when the unit never ran or was lost; `voidReason` / `errorReason` are set by
/// the worker or the controller (disk, lost, report, infra, seed-isolation); `endFacts` become `unit_facts` phase
/// `end` when present.
public record UnitFinishRow(Option<Integer> rc,
                            Option<KillCause> killedBy,
                            Instant endedAt,
                            Option<UnitEndFacts> endFacts,
                            long testsAttr,
                            Option<String> voidReason,
                            Option<String> errorReason) {
    public static UnitFinishRow unitFinishRow(Option<Integer> rc,
                                              Option<KillCause> killedBy,
                                              Instant endedAt,
                                              Option<UnitEndFacts> endFacts,
                                              long testsAttr,
                                              Option<String> voidReason,
                                              Option<String> errorReason) {
        return new UnitFinishRow(rc, killedBy, endedAt, endFacts, testsAttr, voidReason, errorReason);
    }
}
