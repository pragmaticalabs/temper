package org.pragmatica.temper.domain.facts;

import java.time.Instant;
import java.util.Map;

import org.pragmatica.lang.Option;


/// Facts at unit end. The container cgroup is gone after exit, so `memory.peak` and `cpu.stat` are the last
/// heartbeat sample: `memoryPeakBytes` is a lower bound at most one heartbeat stale, sampled at
/// `memoryPeakSampledAt`; it is absent where the kernel/cgroup setup does not provide it. `tcLeftovers` is the
/// Testcontainers reap (m0-design §12.4). `memAvailableMinKb` is the host's lowest `MemAvailable` sampled on the
/// heartbeat while the unit ran (m1-design §4.5, sizing §8.3): unlike `memory.peak` it also sees the
/// Testcontainers siblings and the bb-* load; it is absent when no sample was taken.
public record UnitEndFacts(HostFacts host,
                           Option<Long> memoryPeakBytes,
                           Instant memoryPeakSampledAt,
                           Map<String, Long> cpuStat,
                           TcLeftovers tcLeftovers,
                           Option<Long> memAvailableMinKb) {
    public UnitEndFacts {
        cpuStat = Map.copyOf(cpuStat);
    }

    public static UnitEndFacts unitEndFacts(HostFacts host,
                                            Option<Long> memoryPeakBytes,
                                            Instant memoryPeakSampledAt,
                                            Map<String, Long> cpuStat,
                                            TcLeftovers tcLeftovers,
                                            Option<Long> memAvailableMinKb) {
        return new UnitEndFacts(host, memoryPeakBytes, memoryPeakSampledAt, cpuStat, tcLeftovers, memAvailableMinKb);
    }
}
