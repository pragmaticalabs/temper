package org.pragmatica.temper.worker.unit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.temper.domain.model.UnitRef;
import org.pragmatica.temper.protocol.exec.Command;
import org.pragmatica.temper.protocol.exec.ProcessRunner;
import org.pragmatica.temper.protocol.exec.Spawned;
import org.pragmatica.temper.worker.WorkerError;
import org.pragmatica.temper.worker.config.UnitFile;
import org.pragmatica.temper.worker.facts.ProcParsers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The bb-hold launch (m1-design §1.2, Zone C: argv only, no shell, `inheritEnv = false`).
///
/// 1. argv = `setsid <bbHold> <owner> <prio> --container temper-<job>-<unit> -- docker <DockerRunArgs…>`.
///    `setsid` without `-f` execs in place (a spawned child is never a process-group leader), so the spawned
///    pid is bb-hold's own, and bb-hold keeps that pid when it re-execs itself. The new session means an ssh
///    hangup never reaches it, and the JVM exiting never kills it.
/// 2. stdout goes to `bbhold.out` (the container id `docker run -d` prints), stderr to `bbhold.err` (every
///    bb-hold message, `granted <ticket>` included); stdin is closed at once (bb-hold never reads it).
/// 3. `bbhold.pid` = `<pid> <startTicks>`, written atomically: the identity the holder checks need (§1.4).
///
/// The environment is the worker's tool environment (`PATH`, `HOME`, and `DOCKER_HOST` when set, which the
/// `docker run` bb-hold executes needs). bb-hold itself needs none (maintainer ruling M1-14).
///
/// The caller wrote the marker before this runs, so "no marker" still means "bb-hold was never spawned"
/// (Q-11's requeue safety).
public record BbHoldLauncher(ProcessRunner runner,
                             UnitFiles files,
                             Path bbHold,
                             Map<String, String> env,
                             Path procRoot,
                             HolderProbe.Signaller signaller) implements PortGroupLauncher {
    private static final Logger LOG = LoggerFactory.getLogger(BbHoldLauncher.class);
    public static final String SETSID = "setsid";
    /// Not applied to a spawned process; required by [Command].
    private static final TimeSpan UNBOUNDED = timeSpan(365).days();

    public BbHoldLauncher {
        env = Map.copyOf(env);
    }

    public static BbHoldLauncher bbHoldLauncher(ProcessRunner runner,
                                                UnitFiles files,
                                                Path bbHold,
                                                Map<String, String> env,
                                                Path procRoot,
                                                HolderProbe.Signaller signaller) {
        return new BbHoldLauncher(runner, files, bbHold, env, procRoot, signaller);
    }

    @Override
    public Promise<UnitLauncher.Launch.Holding> enqueue(UnitLaunch launch, HoldRequest hold) {
        var ref = launch.request().ref();

        return staleTicketRemoved(ref).flatMap(_ -> command(launch, hold))
                                 .flatMap(runner::spawn)
                                 .flatMap(spawned -> recordedOrSignalled(ref, spawned))
                                 .async();
    }

    /// The bb-hold argv, pinned by `BbHoldLauncherTest`.
    public static List<String> argv(Path bbHold, UnitLaunch launch, HoldRequest hold) {
        return Stream.of(List.of(SETSID,
                                 bbHold.toString(),
                                 hold.owner(),
                                 Integer.toString(hold.priority()),
                                 "--container",
                                 launch.request().ref().containerName(),
                                 "--",
                                 "docker"),
                         DockerRunArgs.build(launch))
                     .flatMap(List::stream)
                     .toList();
    }

    /// The grant files of an earlier attempt of this unit (`bbhold.ticket`, `start`, `container`) would read as
    /// this attempt's grant; a fresh enqueue starts without them (no container of the unit exists at this point).
    private Result<Boolean> staleTicketRemoved(UnitRef ref) {
        return Result.allOf(Stream.of(UnitFile.BBHOLD_TICKET, UnitFile.START, UnitFile.CONTAINER)
                                  .map(file -> removed(files.path(ref, file)))
                                  .toList()).map(removed -> removed.contains(true));
    }

    private static Result<Boolean> removed(Path file) {
        return FileOps.deleteIfExists(file).mapError(origin -> WorkerError.Io.FACTORY.apply("remove a stale " + file.getFileName(),
                                                                                            origin));
    }

    private Result<Command> command(UnitLaunch launch, HoldRequest hold) {
        var ref = launch.request().ref();

        return Command.command(argv(bbHold, launch, hold), env, UNBOUNDED).map(command -> command.stdoutTo(files.path(ref,
                                                                                                                      UnitFile.BBHOLD_OUT))
                                                                                                 .stderrTo(files.path(ref,
                                                                                                                      UnitFile.BBHOLD_ERR)));
    }

    private Result<UnitLauncher.Launch.Holding> recorded(UnitRef ref, Spawned spawned) {
        var holder = HolderRecord.holderRecord(spawned.pid(), startTicks(ref, spawned.pid()));

        return stdinClosed(spawned).flatMap(_ -> files.store(ref,
                                                             UnitFile.BBHOLD_PID,
                                                             holder.fileText()))
                          .map(_ -> UnitLauncher.Launch.holding(holder,
                                                                spawned.exit()));
    }

    /// BER (compensate by inverse): a holder whose `bbhold.pid` cannot be recorded could never be identified, watched
    /// or cancelled, so it is signalled at once (SIGTERM: bb-hold dequeues its ticket and exits); the pid is still
    /// this JVM's unreaped child, so it cannot have been recycled. The enqueue then fails with the recording's cause,
    /// and the start's own cleanup follows.
    private Result<UnitLauncher.Launch.Holding> recordedOrSignalled(UnitRef ref, Spawned spawned) {
        return recorded(ref, spawned).onFailure(cause -> holderSignalled(ref, spawned, cause));
    }

    private Unit holderSignalled(UnitRef ref, Spawned spawned, Cause cause) {
        var signalled = signaller.signal(spawned.pid());

        LOG.error("unit {}: bb-hold pid {} not recorded ({}), SIGTERM sent: {}",
                  ref.containerName(),
                  spawned.pid(),
                  cause,
                  signalled);

        return unit();
    }

    /// FER (degrade forward): a stat that cannot be read right after the spawn belongs to a holder that already
    /// exited (a refusal at the front takes milliseconds). The record then carries [HolderRecord#UNKNOWN], which
    /// never matches a live process, so every later check reads it as dead, which is what it is; the exit's rc
    /// still reaches the `GrantWatcher` through `exit`.
    private long startTicks(UnitRef ref, long pid) {
        return FileOps.readString(procRoot.resolve(Long.toString(pid)).resolve("stat"))
                      .option()
                      .flatMap(ProcParsers::startTicks)
                      .onEmpty(() -> LOG.warn("unit {}: no start time for bb-hold pid {}",
                                              ref.containerName(),
                                              pid))
                      .or(HolderRecord.UNKNOWN);
    }

    /// FER (degrade forward): bb-hold never reads stdin and runs argv with `< /dev/null`; closing our end only
    /// spares it an open pipe, so a failed close changes nothing it does and is dropped.
    private static Result<Spawned> stdinClosed(Spawned spawned) {
        return Result.lift(Causes::fromThrowable,
                           () -> spawned.stdin()
                                        .close())
                     .onFailure(cause -> LOG.debug("bb-hold pid {}: stdin not closed: {}",
                                                   spawned.pid(),
                                                   cause))
                     .recover(_ -> unit())
                     .map(_ -> spawned);
    }
}
