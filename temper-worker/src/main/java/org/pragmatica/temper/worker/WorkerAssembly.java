package org.pragmatica.temper.worker;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.message.DiskUnitDto;
import org.pragmatica.temper.protocol.message.Handshake;
import org.pragmatica.temper.protocol.message.HostFactsDto;
import org.pragmatica.temper.protocol.message.Message;
import org.pragmatica.temper.worker.config.WorkerConfig;
import org.pragmatica.temper.worker.docker.DockerCli;
import org.pragmatica.temper.worker.facts.CgroupSampler;
import org.pragmatica.temper.worker.facts.FactsProbe;
import org.pragmatica.temper.worker.gc.JobGc;
import org.pragmatica.temper.worker.gc.NetworkSweep;
import org.pragmatica.temper.worker.host.StateScan;
import org.pragmatica.temper.worker.link.HeartbeatTask;
import org.pragmatica.temper.worker.link.Outbox;
import org.pragmatica.temper.worker.link.Router;
import org.pragmatica.temper.worker.manifest.ManifestCollector;
import org.pragmatica.temper.worker.manifest.PomProbe;
import org.pragmatica.temper.worker.unit.BbHoldLauncher;
import org.pragmatica.temper.worker.unit.BbQueueReap;
import org.pragmatica.temper.worker.unit.GrantWatcher;
import org.pragmatica.temper.worker.unit.HoldMonitor;
import org.pragmatica.temper.worker.unit.HolderProbe;
import org.pragmatica.temper.worker.unit.LogTailer;
import org.pragmatica.temper.worker.unit.ReportCollector;
import org.pragmatica.temper.worker.unit.RunnerImage;
import org.pragmatica.temper.worker.unit.TestcontainersReap;
import org.pragmatica.temper.worker.unit.ThreadDumper;
import org.pragmatica.temper.worker.unit.Timers;
import org.pragmatica.temper.worker.unit.UnitCanceller;
import org.pragmatica.temper.worker.unit.UnitCollector;
import org.pragmatica.temper.worker.unit.UnitFiles;
import org.pragmatica.temper.worker.unit.UnitLauncher;
import org.pragmatica.temper.worker.unit.UnitRegistry;
import org.pragmatica.temper.worker.unit.UnitStarter;
import org.pragmatica.temper.worker.unit.UnitTimer;
import org.pragmatica.temper.worker.unit.UnitWatcher;
import org.pragmatica.temper.worker.workspace.Git;
import org.pragmatica.temper.worker.workspace.Seeder;
import org.pragmatica.temper.worker.workspace.WorkspacePreparer;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Wiring only: builds every worker component from the configuration and the three effects tests replace (the
/// process runner, the clock, the timers). No behaviour lives here.
public record WorkerAssembly(WorkerConfig config,
                             Router router,
                             StateScan scan,
                             FactsProbe facts,
                             HeartbeatTask heartbeat,
                             NetworkSweep networks) {
    /// How long one `docker wait` may block before the watcher re-inspects the container.
    public static final long WAIT_BOUND_HOURS = 24;
    /// Pause before a watcher waits again after a `docker wait` that ended without an rc.
    public static final long WAIT_RETRY_SECONDS = 5;

    /// Resolves the docker root (configured, or `docker info` once) and wires the components.
    public static Promise<WorkerAssembly> assemble(WorkerConfig config,
                                                   ProcessRunner runner,
                                                   JsonMapper mapper,
                                                   Outbox outbox,
                                                   InstantSource clock,
                                                   Timers timers) {
        var docker = DockerCli.dockerCli(runner, config.toolEnv(), config.timings());

        return config.dockerRoot()
                     .map(Promise::success)
                     .or(docker::rootDir)
                     .map(dockerRoot -> workerAssembly(config, runner, mapper, outbox, clock, timers, docker, dockerRoot));
    }

    /// The `Hello` frame: identity, facts, and the startup disk scan; the orphan network sweep runs alongside (it
    /// never fails, see [NetworkSweep]).
    public Promise<Message> hello() {
        return Promise.all(facts.probe(),
                           scan.scan(),
                           networks.sweep())
                      .map((host, units, _) -> hello(config,
                                                     HostFactsDto.hostFactsDto(host),
                                                     units));
    }

    private static Message hello(WorkerConfig config, HostFactsDto facts, List<DiskUnitDto> units) {
        return new Message.Hello(Handshake.PROTOCOL_VERSION,
                                 config.workerVersion(),
                                 config.host(),
                                 config.uid(),
                                 config.gid(),
                                 config.dockerGid(),
                                 facts.cores(),
                                 facts,
                                 units);
    }

    private static WorkerAssembly workerAssembly(WorkerConfig config,
                                                 ProcessRunner runner,
                                                 JsonMapper mapper,
                                                 Outbox outbox,
                                                 InstantSource clock,
                                                 Timers timers,
                                                 DockerCli docker,
                                                 Path dockerRoot) {
        var paths = config.paths();
        var timings = config.timings();
        var files = UnitFiles.unitFiles(paths);
        var registry = UnitRegistry.unitRegistry();
        var cgroups = CgroupSampler.cgroupSampler(config.cgroupRoot(), clock);
        var facts = FactsProbe.hostProbe(config.procRoot(),
                                         paths.jobsDir(),
                                         dockerRoot,
                                         docker,
                                         runner,
                                         config.bbQueue(),
                                         clock);
        var reap = TestcontainersReap.testcontainersReap(docker,
                                                         files,
                                                         timings.reapWait(),
                                                         timings.voidedReapWait(),
                                                         timings.reapPoll(),
                                                         clock);
        var holders = HolderProbe.procHolderProbe(config.procRoot(), files);
        var bbReap = BbQueueReap.bbQueueReap(runner, config.bbQueue());
        var collector = UnitCollector.unitCollector(paths,
                                                    files,
                                                    docker,
                                                    ReportCollector.reportCollector(paths, files),
                                                    reap,
                                                    facts,
                                                    cgroups,
                                                    ManifestCollector.manifestCollector(paths, files, mapper),
                                                    mapper,
                                                    clock);
        var watcher = UnitWatcher.unitWatcher(docker,
                                              files,
                                              registry,
                                              collector,
                                              outbox,
                                              timeSpan(WAIT_BOUND_HOURS).hours(),
                                              timeSpan(WAIT_RETRY_SECONDS).seconds());
        var timer = UnitTimer.unitTimer(files,
                                        paths,
                                        docker,
                                        ThreadDumper.threadDumper(docker, timings.jcmdCall()),
                                        timers,
                                        clock,
                                        registry);
        var bbHold = BbHoldLauncher.bbHoldLauncher(runner,
                                                   files,
                                                   config.bbHoldPath(),
                                                   config.toolEnv(),
                                                   config.procRoot(),
                                                   HolderProbe.Signaller.sigterm());
        var grants = GrantWatcher.grantWatcher(files,
                                               docker,
                                               holders,
                                               registry,
                                               timer,
                                               watcher,
                                               facts,
                                               outbox,
                                               timers,
                                               clock,
                                               timings.hold(),
                                               bbHold,
                                               bbReap);
        var starter = UnitStarter.unitStarter(config,
                                              files,
                                              docker,
                                              RunnerImage.runnerImage(docker, paths),
                                              UnitLauncher.routed(UnitLauncher.direct(docker), bbHold),
                                              facts,
                                              UnitStarter.DiskSpace.hostDiskSpace(dockerRoot, paths.jobsDir()),
                                              reap,
                                              registry,
                                              timer,
                                              watcher,
                                              PomProbe.pomProbe(docker, paths, timings.pomProbe()),
                                              grants,
                                              holders,
                                              mapper,
                                              clock);
        var preparer = WorkspacePreparer.workspacePreparer(paths,
                                                           Git.git(runner, config.toolEnv(), timings.gitCall()),
                                                           Seeder.copying());
        var networks = NetworkSweep.networkSweep(docker, holders, clock, NetworkSweep.MIN_AGE);
        var router = Router.router(preparer,
                                   starter,
                                   watcher,
                                   timer,
                                   UnitCanceller.unitCanceller(docker,
                                                               files,
                                                               registry,
                                                               holders,
                                                               timings.hold().holderExit()),
                                   LogTailer.logTailer(paths, files, docker),
                                   facts,
                                   cgroups,
                                   files,
                                   registry,
                                   docker,
                                   grants,
                                   holders,
                                   JobGc.jobGc(paths, files, docker, registry, networks));
        var holdMonitor = HoldMonitor.holdMonitor(holders,
                                                  docker,
                                                  files,
                                                  registry,
                                                  bbReap,
                                                  timings.hold().observationGap());

        return new WorkerAssembly(config,
                                  router,
                                  StateScan.stateScan(paths,
                                                      files,
                                                      docker,
                                                      holders,
                                                      timings.hold().absentRecheck()),
                                  facts,
                                  HeartbeatTask.heartbeatTask(registry,
                                                              cgroups,
                                                              facts,
                                                              outbox,
                                                              files,
                                                              holdMonitor,
                                                              config.runFloorBytes()),
                                  networks);
    }
}
