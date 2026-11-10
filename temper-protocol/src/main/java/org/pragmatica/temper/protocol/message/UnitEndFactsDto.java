package org.pragmatica.temper.protocol.message;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.temper.domain.facts.TcLeftovers;
import org.pragmatica.temper.domain.facts.UnitEndFacts;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.temper.domain.DomainError.invalid;


/// Wire form of [UnitEndFacts]; also the `unit_facts` phase `end` jsonb shape. `tcLeftovers` is a protocol v1
/// addition (m0-design §12.7); `memAvailableMinKb` a protocol v2 one (m1-design §4.5), absent in older rows.
public record UnitEndFactsDto(HostFactsDto host,
                              Option<Long> memoryPeakBytes,
                              Instant memoryPeakSampledAt,
                              Map<String, Long> cpuStat,
                              TcLeftoversDto tcLeftovers,
                              Option<Long> memAvailableMinKb) {
    public static UnitEndFactsDto unitEndFactsDto(UnitEndFacts facts) {
        return new UnitEndFactsDto(HostFactsDto.hostFactsDto(facts.host()),
                                   facts.memoryPeakBytes(),
                                   facts.memoryPeakSampledAt(),
                                   facts.cpuStat(),
                                   TcLeftoversDto.tcLeftoversDto(facts.tcLeftovers()),
                                   facts.memAvailableMinKb());
    }

    /// Wire input from the worker: `host`, `memoryPeakSampledAt` and `cpuStat` are required. `tcLeftovers` never fails
    /// the end facts: see [#leftovers].
    public Result<UnitEndFacts> toDomain() {
        return Result.all(option(host).toResult(invalid("endFacts.host").apply("missing"))
                                .flatMap(HostFactsDto::toDomain),
                          Verify.ensure(memoryPeakSampledAt,
                                        Verify.Is::notNull,
                                        invalid("endFacts.memoryPeakSampledAt")),
                          Verify.ensure(cpuStat,
                                        Verify.Is::notNull,
                                        invalid("endFacts.cpuStat")))
                     .map((hostFacts, sampledAt, stat) -> UnitEndFacts.unitEndFacts(hostFacts,
                                                                                    memoryPeakBytes,
                                                                                    sampledAt,
                                                                                    stat,
                                                                                    leftovers(),
                                                                                    memAvailableMinKb));
    }

    /// FER (degrade forward): an absent `tcLeftovers` (an `exited.json` recorded before the field existed) or one that
    /// does not parse reads as an unverified reap with the reason. Guarantee: a reap is never reported clean unless the
    /// worker said so, and a bad leftover entry never costs the memory and cpu facts. Mechanism: the reason is kept in
    /// `unverified`.
    private TcLeftovers leftovers() {
        return option(tcLeftovers).map(UnitEndFactsDto::readable)
                     .or(() -> unverified(TcLeftoversDto.NOT_REPORTED));
    }

    private static TcLeftovers readable(TcLeftoversDto dto) {
        return dto.toDomain()
                  .fold(cause -> unverified(TcLeftoversDto.UNREADABLE + cause.message()),
                        reap -> reap);
    }

    private static TcLeftovers unverified(String reason) {
        return TcLeftovers.tcLeftovers(List.of(), some(reason));
    }
}
