package org.pragmatica.temper.acceptance.sizing;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.temper.acceptance.trust.TemperSide;
import org.pragmatica.temper.acceptance.trust.TrustComparison;
import org.pragmatica.temper.protocol.api.ResultRecordDto.UnitResultDto;
import org.pragmatica.temper.protocol.api.TestcaseDto;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.ManifestEntryDto;
import org.pragmatica.temper.protocol.message.UnitEndFactsDto;

import static org.pragmatica.lang.Option.option;


/// One T1 job's sizing data (m1-design §8.3), pure: temper's side (record, rows, manifest, log tail) and the unit's
/// facts at start (`facts-start.json`) and end (`facts-end.json`), none when the evidence is not on this host.
///
/// Sizing runs on dedicated-CPU hosts only (m4-design §9.4, ruling R-M4-9): [#comparable] refuses a job any of whose
/// units the record says ran on a shared-CPU host (`sharedCpu = true`), since its wall times and `memory.peak` are not
/// comparable. A record written before M4 says nothing (bigboy was the only host then) and is accepted.
public record JobSizing(long job, TemperSide side, Option<HostFactsDto> start, Option<UnitEndFactsDto> end) {
    /// The module of rows whose class the manifest does not list (drift, or a pruned manifest).
    public static final String UNATTRIBUTED = "(unattributed)";
    public static final long MIB = 1024 * 1024;

    public static JobSizing jobSizing(long job,
                                      TemperSide side,
                                      Option<HostFactsDto> start,
                                      Option<UnitEndFactsDto> end) {
        return new JobSizing(job, side, start, end);
    }

    /// Why a job does not size anything: the units of `job` that ran on a shared-CPU host.
    public record SharedCpu(long job, List<String> units, String message) implements Cause {
        public static SharedCpu sharedCpu(long job, List<String> units) {
            return new SharedCpu(job,
                                 units,
                                 "job " + job
                                + ": unit " + String.join(", ", units)
                                + " ran on a shared-CPU host; its wall time and memory.peak are not comparable (R-M4-9)");
        }
    }

    /// This job, or [SharedCpu] naming its units whose record says `sharedCpu = true`.
    public Result<JobSizing> comparable() {
        var shared = side.result()
                         .units()
                         .stream()
                         .filter(unit -> unit.sharedCpu()
                                             .or(false))
                         .map(UnitResultDto::id)
                         .toList();

        return shared.isEmpty()
               ? Result.success(this)
               : SharedCpu.sharedCpu(job, shared).result();
    }

    /// Σ testcase `time_s` per module path, through the manifest's entries and optional classes; a class the
    /// manifest does not name, even by its outermost class, is [#UNATTRIBUTED]. Test time, not wall time.
    public Map<String, Double> testSecondsByModule() {
        var modules = moduleOfClass();

        return side.rows()
                   .stream()
                   .collect(Collectors.groupingBy(row -> moduleOf(modules, row),
                                                  Collectors.summingDouble(TestcaseDto::timeSeconds)));
    }

    private Map<String, String> moduleOfClass() {
        return Stream.concat(side.manifest().entries().stream(),
                             side.manifest().optional().stream())
                     .collect(Collectors.toMap(ManifestEntryDto::className,
                                               ManifestEntryDto::module,
                                               (first, _) -> first));
    }

    private static String moduleOf(Map<String, String> modules, TestcaseDto row) {
        return module(modules, row.className());
    }

    private static String module(Map<String, String> modules, String className) {
        return option(modules.get(className)).orElse(() -> option(modules.get(TrustComparison.outermost(className))))
                     .or(UNATTRIBUTED);
    }

    /// The modules whose path starts with `prefix` (the Ember modules for §12.1's `rest`).
    public double testSecondsUnder(String prefix) {
        var byModule = testSecondsByModule();

        return byModule.keySet()
                       .stream()
                       .filter(module -> module.startsWith(prefix))
                       .mapToDouble(byModule::get)
                       .sum();
    }

    /// Per-module wall time from the log's Maven Reactor Summary (display names; compile time included).
    public Map<String, Double> wallSecondsByModule() {
        return ReactorSummary.parse(side.logTail());
    }

    /// `memory.peak` of the unit's cgroup in MiB (10 s sampling, a lower bound: no Testcontainers siblings).
    public Option<Long> peakMib() {
        return end.flatMap(UnitEndFactsDto::memoryPeakBytes)
                  .map(bytes -> bytes / MIB);
    }

    /// The host's lowest sampled `MemAvailable` during the unit, in MiB (siblings and bb-* load included).
    public Option<Long> memAvailableMinMib() {
        return end.flatMap(UnitEndFactsDto::memAvailableMinKb)
                  .map(kb -> kb / 1024);
    }

    /// The Gate's run time (`timings.run`: last end − first start).
    public Option<Duration> wall() {
        return side.result()
                   .timings()
                   .run();
    }
}
