package org.pragmatica.temper.worker.facts;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.facts.HostFacts;
import org.pragmatica.temper.domain.facts.HostReactors;
import org.pragmatica.temper.domain.facts.ProcInfo;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.docker.DockerCli;

import static org.pragmatica.lang.Promise.promise;


/// Contention facts (spec §4.5, m0-design §7.7), sampled at unit start, at unit end and on every heartbeat.
@FunctionalInterface
public interface FactsProbe {
    Pattern PID = Pattern.compile("^[0-9]+$");
    Promise<HostFacts> probe();

    /// The host probe. Independent reads run in parallel (Fork-Join):
    ///
    /// | Fact | Source |
    /// |---|---|
    /// | load1, load5 | `<proc>/loadavg` |
    /// | cores | `Runtime.availableProcessors()` |
    /// | MemAvailable | `<proc>/meminfo` |
    /// | free disk | `FileStore.getUsableSpace` of the jobs directory and of the docker root |
    /// | foreign reactors | `<proc>/<pid>/{cmdline,cgroup}` + `docker ps` temper ids, through [HostReactors#foreign] (A7) |
    /// | temper units running | the number of running temper unit containers |
    /// | bb-* tickets | `bbQueue` (`~/tools/bb-queue 0`) through [BbQueue#read]: `none()` plus a note when unreadable (Q-10) |
    static FactsProbe hostProbe(Path procRoot,
                                Path workspaceRoot,
                                Path dockerRoot,
                                DockerCli docker,
                                ProcessRunner runner,
                                Command bbQueue,
                                InstantSource clock) {
        return () -> probed(procRoot, workspaceRoot, dockerRoot, docker, runner, bbQueue, clock);
    }

    private static Promise<HostFacts> probed(Path procRoot,
                                             Path workspaceRoot,
                                             Path dockerRoot,
                                             DockerCli docker,
                                             ProcessRunner runner,
                                             Command bbQueue,
                                             InstantSource clock) {
        return Promise.all(loadAverage(procRoot),
                           memAvailableKb(procRoot),
                           usableSpace(dockerRoot),
                           usableSpace(workspaceRoot),
                           docker.temperContainerIds(),
                           processes(procRoot),
                           BbQueue.read(runner, bbQueue))
                      .map((load, memory, freeDockerRoot, freeWorkspace, temperIds, procs, bb) -> facts(load,
                                                                                                        memory,
                                                                                                        freeDockerRoot,
                                                                                                        freeWorkspace,
                                                                                                        temperIds,
                                                                                                        procs,
                                                                                                        bb,
                                                                                                        clock));
    }

    private static HostFacts facts(ProcParsers.LoadAverage load,
                                   long memAvailableKb,
                                   long freeDockerRoot,
                                   long freeWorkspace,
                                   Set<String> temperIds,
                                   List<ProcInfo> procs,
                                   BbQueue.BbFacts bb,
                                   InstantSource clock) {
        return HostFacts.hostFacts(load.load1(),
                                   load.load5(),
                                   Runtime.getRuntime().availableProcessors(),
                                   HostReactors.foreign(procs, temperIds),
                                   temperIds.size(),
                                   bb.running(),
                                   bb.waiting(),
                                   memAvailableKb,
                                   freeDockerRoot,
                                   freeWorkspace,
                                   clock.instant(),
                                   bb.notes());
    }

    private static Promise<ProcParsers.LoadAverage> loadAverage(Path procRoot) {
        return readText(procRoot.resolve("loadavg")).flatMap(ProcParsers::loadAverage)
                       .async();
    }

    private static Promise<Long> memAvailableKb(Path procRoot) {
        return readText(procRoot.resolve("meminfo")).flatMap(ProcParsers::memAvailableKb)
                       .async();
    }

    /// Usable bytes on the filesystem holding `path` (the path itself is created if missing, so a fresh host
    /// with no jobs yet still measures its workspace filesystem).
    static Promise<Long> usableSpace(Path path) {
        return FileOps.createDirectories(path)
                      .async()
                      .flatMap(_ -> Promise.lift(thrown -> WorkerError.Io.FACTORY.apply("measure free space of " + path,
                                                                                        Causes.fromThrowable(thrown)),
                                                 () -> Files.getFileStore(path).getUsableSpace()));
    }

    /// Every process of `<proc>` as [ProcInfo]; the listing runs on a virtual thread.
    static Promise<List<ProcInfo>> processes(Path procRoot) {
        return promise(() -> processList(procRoot));
    }

    private static Result<List<ProcInfo>> processList(Path procRoot) {
        return FileOps.list(procRoot)
                      .map(FactsProbe::processInfos)
                      .mapError(origin -> WorkerError.Io.FACTORY.apply("list " + procRoot, origin));
    }

    private static List<ProcInfo> processInfos(List<Path> entries) {
        return entries.stream()
                      .filter(FactsProbe::isProcess)
                      .map(FactsProbe::procInfo)
                      .flatMap(Option::stream)
                      .toList();
    }

    private static boolean isProcess(Path entry) {
        return PID.matcher(entry.getFileName().toString()).matches();
    }

    /// FER (degrade forward): a process that exits between the directory listing and the reads, or whose files
    /// are unreadable, is skipped. It no longer runs (or cannot be attributed), so leaving it out of the reactor
    /// count is the correct reading of "now"; no failure is hidden from a caller that could act on it.
    private static Option<ProcInfo> procInfo(Path processDir) {
        return Result.all(Number.parseLong(processDir.getFileName().toString()),
                          FileOps.readBytes(processDir.resolve("cmdline")),
                          FileOps.readString(processDir.resolve("cgroup")))
                     .map((pid, cmdline, cgroup) -> ProcInfo.procInfo(pid,
                                                                      ProcParsers.cmdline(cmdline),
                                                                      cgroup))
                     .option();
    }

    private static Result<String> readText(Path file) {
        return FileOps.readString(file).mapError(origin -> WorkerError.Io.FACTORY.apply("read " + file, origin));
    }
}
