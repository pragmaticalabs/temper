package org.pragmatica.temper.protocol.message;

import java.time.Instant;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.facts.HostFacts;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Wire form of [HostFacts]; also the `unit_facts`/`hosts.facts` jsonb shape. `notes` is a protocol v1 addition
/// (m0-design §12.7).
public record HostFactsDto(double load1,
                           double load5,
                           int cores,
                           int foreignReactors,
                           int temperUnitsRunning,
                           Option<Integer> bbRunning,
                           Option<Integer> bbWaiting,
                           long memAvailableKb,
                           long freeDockerRootBytes,
                           long freeWorkspaceBytes,
                           Instant at,
                           List<String> notes) {
    public static HostFactsDto hostFactsDto(HostFacts facts) {
        return new HostFactsDto(facts.load1(),
                                facts.load5(),
                                facts.cores(),
                                facts.foreignReactors(),
                                facts.temperUnitsRunning(),
                                facts.bbRunning(),
                                facts.bbWaiting(),
                                facts.memAvailableKb(),
                                facts.freeDockerRootBytes(),
                                facts.freeWorkspaceBytes(),
                                facts.at(),
                                facts.notes());
    }

    /// Wire input from the worker: `at` is required; the optional ticket counts are `Option` already; absent
    /// `notes` are none.
    public Result<HostFacts> toDomain() {
        return Verify.ensure(at, Verify.Is::notNull, invalid("facts.at")).map(present -> HostFacts.hostFacts(load1,
                                                                                                             load5,
                                                                                                             cores,
                                                                                                             foreignReactors,
                                                                                                             temperUnitsRunning,
                                                                                                             bbRunning,
                                                                                                             bbWaiting,
                                                                                                             memAvailableKb,
                                                                                                             freeDockerRootBytes,
                                                                                                             freeWorkspaceBytes,
                                                                                                             present,
                                                                                                             option(notes).or(List.of())));
    }
}
