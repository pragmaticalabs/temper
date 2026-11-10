package org.pragmatica.temper.worker.facts;

import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// Samples a running unit container's cgroup (`memory.peak`, `cpu.stat`) and keeps the last sample per
/// container (m0-design §7.7). The cgroup is removed when the container exits, so the end facts can only carry
/// the last sample: `memory.peak` is a lower bound at most one heartbeat stale, stamped with `at`.
///
/// Both docker cgroup layouts are tried: the systemd driver (`system.slice/docker-<id>.scope`) and cgroupfs
/// (`docker/<id>`). When neither exists (no cgroup v2, or the container is already gone) nothing is recorded;
/// a file that is missing (`memory.peak` needs Linux 5.19+) leaves its field empty.
public record CgroupSampler(Path cgroupRoot, InstantSource clock, Map<String, CgroupSample> last) {
    public static CgroupSampler cgroupSampler(Path cgroupRoot, InstantSource clock) {
        return new CgroupSampler(cgroupRoot, clock, new ConcurrentHashMap<>());
    }

    /// Samples every container in `containerIds` and keeps what was read.
    public Unit sampleAll(List<String> containerIds) {
        containerIds.forEach(this::sample);

        return unit();
    }

    /// Samples one container; `none()` when its cgroup directory is not found.
    public Option<CgroupSample> sample(String containerId) {
        return directory(containerId).map(this::sampled)
                        .onPresent(sample -> last.put(containerId, sample));
    }

    /// The last sample of a container, if any was taken.
    public Option<CgroupSample> lastSample(String containerId) {
        return option(last.get(containerId));
    }

    /// Drops a container's sample once its unit is collected.
    public Unit forget(String containerId) {
        last.remove(containerId);

        return unit();
    }

    private Option<Path> directory(String containerId) {
        return Option.from(List.of(cgroupRoot.resolve("system.slice").resolve("docker-" + containerId + ".scope"),
                                   cgroupRoot.resolve("docker").resolve(containerId))
                               .stream()
                               .filter(FileOps::isDirectory)
                               .findFirst());
    }

    private CgroupSample sampled(Path directory) {
        return CgroupSample.cgroupSample(text(directory.resolve("memory.peak")).flatMap(ProcParsers::memoryPeak),
                                         text(directory.resolve("cpu.stat")).map(ProcParsers::cpuStat).or(Map.of()),
                                         clock.instant());
    }

    /// FER (degrade forward): a cgroup file that cannot be read (absent on this kernel, or the container exited
    /// between the directory check and the read) leaves its field empty; the sample is evidence, not a gate.
    private static Option<String> text(Path file) {
        return FileOps.readString(file).option();
    }

    /// One cgroup sample.
    public record CgroupSample(Option<Long> memoryPeakBytes, Map<String, Long> cpuStat, Instant at) {
        public CgroupSample {
            cpuStat = Map.copyOf(cpuStat);
        }

        public static CgroupSample cgroupSample(Option<Long> memoryPeakBytes, Map<String, Long> cpuStat, Instant at) {
            return new CgroupSample(memoryPeakBytes, cpuStat, at);
        }
    }
}
