package org.pragmatica.temper.store.api;

import java.time.Instant;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// A `[[hosts]]` entry as stored in `hosts` (m0-design §6.1). `sharedCpu` (`hosts.shared_cpu`, m4-design §9.1): the
/// host's CPU is shared with others, so it is never granted a `--dedicated-cpu` job's unit. `retireAt`
/// (`hosts.retire_at`, §10): from that instant on the host gets no new grants.
public record HostConfig(HostName name,
                         String address,
                         Option<String> hostKey,
                         int cap,
                         int memoryBudgetMb,
                         boolean portIsolated,
                         boolean sharedCpu,
                         Option<Instant> retireAt) {
    /// A dedicated-CPU host that is never retired.
    public static Result<HostConfig> hostConfig(HostName name,
                                                String address,
                                                Option<String> hostKey,
                                                int cap,
                                                int memoryBudgetMb,
                                                boolean portIsolated) {
        return hostConfig(name, address, hostKey, cap, memoryBudgetMb, portIsolated, false, none());
    }

    public static Result<HostConfig> hostConfig(HostName name,
                                                String address,
                                                Option<String> hostKey,
                                                int cap,
                                                int memoryBudgetMb,
                                                boolean portIsolated,
                                                boolean sharedCpu,
                                                Option<Instant> retireAt) {
        return Result.all(Verify.ensure(address,
                                        Verify.Is::notBlank,
                                        invalid("host address")),
                          Verify.ensure(cap,
                                        Verify.Is::positive,
                                        invalid("host cap")),
                          Verify.ensure(memoryBudgetMb,
                                        Verify.Is::positive,
                                        invalid("host memoryBudgetMb")))
                     .map((validAddress, validCap, validBudget) -> new HostConfig(name,
                                                                                  validAddress,
                                                                                  hostKey,
                                                                                  validCap,
                                                                                  validBudget,
                                                                                  portIsolated,
                                                                                  sharedCpu,
                                                                                  retireAt));
    }
}
